#!/usr/bin/env python3
"""复用真实检索接口，对同一完整候选并集评测 BM25、向量与 RRF；不调用聊天模型。"""

import argparse
import csv
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import re
import subprocess
import time

from evaluate_trusted_knowledge import Client, ROOT, load_credentials, fingerprint
from prepare_public_retrieval_corpus import BENCHMARK, CACHE, sha

SETTINGS = {"candidateLimit": 25, "vectorMinSimilarity": 0.0, "resultLimit": 10}


def read(path):
    """读取评测文件。"""
    return json.loads(path.read_text(encoding="utf-8"))


def write(path, value):
    """逐步保存运行快照，便于定位中断和复现。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def normalized(text):
    """忽略解析过程中的空白变化，其余字符必须精确对应原文。"""
    return re.sub(r"\s+", "", text)


def corpus():
    """先核对语料摘要，再加载有稳定段落编号的本地正文。"""
    sources = read(BENCHMARK / "sources.json")
    documents = {}
    for source in sources["documents"]:
        key = source["id"]
        text = (CACHE / "documents" / (key + ".md")).read_text(encoding="utf-8")
        document = read(CACHE / (key + ".json"))
        if sha(text) != source["textSha256"]:
            raise ValueError("corpus hash mismatch: " + key)
        for paragraph in source["paragraphs"]:
            if sha(document["paragraphs"][paragraph["id"]]) != paragraph["sha256"]:
                raise ValueError("paragraph hash mismatch: " + key)
        documents[key] = document
    return sources, documents


def validate_cases(documents):
    """检查固定题集与证据的对应关系，并生成供用户复核的原文清单。"""
    dataset = read(BENCHMARK / "cases.json")
    cases = dataset["cases"]
    if len({c["id"] for c in cases}) != len(cases) or len({c["question"] for c in cases}) != len(cases):
        raise ValueError("duplicate case id or question")
    with (BENCHMARK / "questions.tsv").open(encoding="utf-8") as file:
        listing = list(csv.DictReader(file, delimiter="\t"))
    if len(listing) != len(cases) or any(any(row[k] != case[k] for k in ["id", "split", "category", "question"])
                                       for row, case in zip(listing, cases)):
        raise ValueError("question listing differs from frozen cases")
    split_evidence = {split: {(e["document"], e["paragraph"]) for c in cases if c["split"] == split
                             for group in c["evidenceGroups"] for e in group} for split in ["dev", "test"]}
    if split_evidence["dev"] & split_evidence["test"]:
        raise ValueError("dev and test share annotated paragraphs")
    review = ["# 公开制度检索题集复核表", "", "AI 辅助构题及标注，尚未经过独立人工复核。请检查问题、证据完整性及其他等价证据。", ""]
    for case in cases:
        if not case["evidenceGroups"]:
            raise ValueError("answerable case must have evidence")
        review += [f"## {case['id']} / {case['split']} / {case['category']}", "", case["question"], ""]
        for group in case["evidenceGroups"]:
            for evidence in group:
                paragraph = documents[evidence["document"]]["paragraphs"][evidence["paragraph"]]
                if sha(paragraph) != evidence["paragraphSha256"]:
                    raise ValueError("evidence mismatch: " + case["id"])
                review += [f"- {evidence['document']} / {evidence['paragraph']}", "", "> " + paragraph, ""]
    (CACHE / "人工复核清单.md").write_text("\n".join(review), encoding="utf-8")
    return dataset


def prepare(client, sources):
    """在独立知识库上传固定资料，可按已保存的状态继续，保持用户默认库不变。"""
    state_path = CACHE / "state.json"
    if state_path.exists():
        state = read(state_path)
        if state["baseUrl"] != client.base:
            raise ValueError("state belongs to another backend")
    else:
        base = client.call("POST", "/api/admin/knowledge-bases", {
            "name": "公开制度检索评测-SZU-v1", "description": "23份公开制度固定快照，仅用于离线检索评测，不代表现行政策咨询"})
        state = {"baseUrl": client.base, "baseId": base["id"], "documents": {}}
        write(state_path, state)
    root = f"/api/admin/knowledge-bases/{state['baseId']}"
    client.call("PUT", root + "/retrieval-settings", SETTINGS)
    for source in sources["documents"]:
        key = source["id"]
        if key not in state["documents"]:
            document = client.call("POST", root + "/documents", upload=CACHE / "documents" / (key + ".md"))
            state["documents"][key] = document["id"]
            write(state_path, state)
        location = root + f"/documents/{state['documents'][key]}"
        document = client.call("GET", location)
        if document["sha256"] != source["textSha256"]:
            raise ValueError("uploaded document hash mismatch: " + key)
        if document["status"] == "PENDING":
            client.call("POST", location + "/submit", {"version": document["version"],
                "sourceKey": "benchmark:" + key, "businessVersion": "snapshot-v1", "applicabilityScope": "ALL",
                "validFrom": "2000-01-01T00:00:00", "validUntil": None})
        print("SUBMITTED " + key, flush=True)
    deadline = time.monotonic() + 1200
    remaining = set(state["documents"])
    while remaining:
        for key in sorted(remaining):
            location = root + f"/documents/{state['documents'][key]}"
            document = client.call("GET", location)
            if document["status"] == "FAILED":
                task = client.call("GET", location + "/index-task")
                raise RuntimeError(f"index failed: {key}, {task['errorCode']}")
            if document["status"] == "READY":
                if document["publicationStatus"] == "DRAFT":
                    client.call("POST", location + "/publish", {"version": document["version"]})
                remaining.remove(key)
                print("READY " + key, flush=True)
        if time.monotonic() > deadline:
            raise TimeoutError("indexing deadline exceeded; rerun prepare to inspect/continue")
        if remaining:
            time.sleep(2)
    return state


def rankings(response):
    """确认两路候选没有被融合截断，再还原独立排名，RRF 顺序直接使用后端结果。"""
    results = response["results"]
    ranked = {"RRF": results}
    for name, field, count in [("BM25", "bm25Rank", response["bm25CandidateCount"]),
                                ("VECTOR", "vectorRank", response["vectorCandidateCount"])]:
        hits = sorted((r for r in results if r[field] is not None), key=lambda r: r[field])
        if [h[field] for h in hits] != list(range(1, count + 1)):
            raise ValueError("truncated candidate union; cannot compare retrieval methods")
        ranked[name] = hits
    return ranked


def metrics(case, hits, documents, id_to_source, k):
    """按证据组计算段落覆盖，并单独统计参考文档命中；重复 chunk 不重复计分。"""
    top = hits[:k]
    content = {}
    for hit in top:
        key = id_to_source[hit["documentId"]]
        content.setdefault(key, []).append(normalized(hit["content"]))
    evidence_hits = []
    for group in case["evidenceGroups"]:
        matched = False
        for evidence in group:
            paragraph = normalized(documents[evidence["document"]]["paragraphs"][evidence["paragraph"]])
            # 严格要求至少一个结果包含完整标注段落，跨 chunk 截断不会被部分匹配冒充完整证据。
            if any(paragraph in chunk for chunk in content.get(evidence["document"], [])):
                matched = True
        evidence_hits.append(matched)
    doc_groups = {frozenset(e["document"] for e in group) for group in case["evidenceGroups"]}
    doc_recall = sum(bool(set(content) & group) for group in doc_groups) / len(doc_groups)
    first_doc = next((i + 1 for i, h in enumerate(top)
                      if any(id_to_source[h["documentId"]] in g for g in doc_groups)), None)
    first_evidence = None
    for rank, hit in enumerate(top, 1):
        if any(e["document"] == id_to_source[hit["documentId"]]
               and normalized(documents[e["document"]]["paragraphs"][e["paragraph"]]) in normalized(hit["content"])
               for group in case["evidenceGroups"] for e in group):
            first_evidence = rank
            break
    return {"documentRecall": doc_recall, "documentHit": float(doc_recall > 0),
            "documentMRR": 1 / first_doc if first_doc else 0,
            "evidenceRecall": sum(evidence_hits) / len(evidence_hits),
            "evidenceHit": float(any(evidence_hits)), "evidenceComplete": float(all(evidence_hits)),
            "evidenceMRR": 1 / first_evidence if first_evidence else 0}


def aggregate(rows):
    """以全部计划题为分母，技术失败计零并单列，耗时仅报告共享检索请求。"""
    summary = {"count": len(rows), "technicalFailures": sum("error" in r for r in rows), "methods": {}}
    fields = ["documentRecall", "documentHit", "documentMRR", "evidenceRecall", "evidenceHit", "evidenceComplete", "evidenceMRR"]
    for method in ["BM25", "VECTOR", "RRF"]:
        summary["methods"][method] = {str(k): {field: round(sum(r.get("metrics", {}).get(method, {}).get(str(k), {}).get(field, 0)
            for r in rows) / len(rows), 6) for field in fields} for k in [1, 5, 10]}
    latencies = sorted(r["latencyMs"] for r in rows if "error" not in r)
    summary["sharedRequestLatencyP95Ms"] = latencies[math.ceil(len(latencies) * .95) - 1] if latencies else None
    return summary


def evaluate(client, sources, documents, dataset, split, output):
    """固定环境、顺序和参数，保存逐题候选与各检索方案的指标。"""
    if output.exists():
        raise ValueError("output already exists; choose a new run file to preserve history")
    state = read(CACHE / "state.json")
    if state["baseUrl"] != client.base:
        raise ValueError("state belongs to another backend")
    root = f"/api/admin/knowledge-bases/{state['baseId']}"
    base = client.call("GET", root)
    actual = {"candidateLimit": base["retrievalCandidateLimit"], "vectorMinSimilarity": base["vectorMinSimilarity"],
              "resultLimit": base["retrievalResultLimit"]}
    if actual != SETTINGS:
        raise ValueError("retrieval settings changed")
    models = client.call("GET", "/api/admin/model-configs")
    model = next(m for m in models if m["isDefault"] and m["modelType"] == "EMBEDDING")
    snapshots = []
    for source in sources["documents"]:
        document = client.call("GET", root + f"/documents/{state['documents'][source['id']]}" )
        if document["sha256"] != source["textSha256"] or document["status"] != "READY" or document["publicationStatus"] != "PUBLISHED":
            raise ValueError("benchmark document changed: " + source["id"])
        snapshots.append(document)
    cases = [c for c in dataset["cases"] if c["split"] == split]
    report = {"benchmark": dataset["id"], "annotationStatus": dataset["annotationStatus"], "split": split,
              "startedAt": datetime.now(timezone.utc).isoformat(), "settings": SETTINGS,
              "casesSha256": hashlib.sha256((BENCHMARK / "cases.json").read_bytes()).hexdigest(),
              "sourcesSha256": hashlib.sha256((BENCHMARK / "sources.json").read_bytes()).hexdigest(),
              "model": {k: model.get(k) for k in ["id", "modelName", "embeddingDimensions", "updatedAt"]},
              "knowledgeBaseId": state["baseId"], "documents": snapshots, "planned": len(cases), "results": []}
    report["gitHead"] = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    report["implementationSha256"] = fingerprint(list((ROOT / "src/main/java").rglob("*.java"))
                                                  + [Path(__file__).resolve()])
    preview = client.call("POST", root + f"/documents/{snapshots[0]['id']}/chunk-preview?size=1")
    report["chunking"] = {k: preview[k] for k in ["chunkSize", "chunkOverlap"]}
    id_to_source = {v: k for k, v in state["documents"].items()}
    # 用开发集问题预热一次，排除服务首次加载；它不计入任何题目的成绩。
    warmup = next(c for c in dataset["cases"] if c["split"] == "dev")
    client.call("POST", root + "/search-candidates", {"query": warmup["question"]})
    write(output, report)
    for case in cases:
        row = {"id": case["id"], "question": case["question"], "category": case["category"]}
        started = time.perf_counter()
        try:
            response = client.call("POST", root + "/search-candidates", {"query": case["question"]})
            row["latencyMs"] = round((time.perf_counter() - started) * 1000)
            row["response"] = response
            row["metrics"] = {method: {str(k): metrics(case, hits, documents, id_to_source, k)
                for k in [1, 5, 10]} for method, hits in rankings(response).items()}
        except Exception as error:
            row["error"] = type(error).__name__
        report["results"].append(row)
        write(output, report)
        print(f"{case['id']} {row.get('error', 'OK')}", flush=True)
    report["summary"] = aggregate(report["results"])
    report["byCategory"] = {category: aggregate([r for r in report["results"] if r["category"] == category])
                            for category in sorted({r["category"] for r in report["results"]})}
    report["finishedAt"] = datetime.now(timezone.utc).isoformat()
    end_models = client.call("GET", "/api/admin/model-configs")
    end_model = next(m for m in end_models if m["isDefault"] and m["modelType"] == "EMBEDDING")
    end_documents = [client.call("GET", root + f"/documents/{d['id']}") for d in snapshots]
    report["environmentStable"] = (end_documents == snapshots and client.call("GET", root) == base
        and all(end_model.get(k) == model.get(k) for k in ["id", "modelName", "embeddingDimensions", "updatedAt"]))
    write(output, report)
    if not report["environmentStable"]:
        raise RuntimeError("environment changed during evaluation; metrics must not be used")
    print(json.dumps(report["summary"], ensure_ascii=False, indent=2))


def main():
    """提供资料导入、标注校验和分集评测三个独立命令。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["prepare", "validate", "evaluate"])
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--tokens", type=Path)
    parser.add_argument("--split", choices=["dev", "test"], default="dev")
    parser.add_argument("--output", type=Path, default=CACHE / "dev-run.json")
    args = parser.parse_args()
    sources, documents = corpus()
    if args.command == "validate":
        dataset = validate_cases(documents)
        print(dict(Counter(c["split"] for c in dataset["cases"])))
        return
    load_credentials(ROOT / ".env")
    client = Client(args.base_url, read(args.tokens) if args.tokens else None)
    client.login("admin")
    if args.command == "prepare":
        prepare(client, sources)
    else:
        evaluate(client, sources, documents, validate_cases(documents), args.split, args.output)


if __name__ == "__main__":
    main()
