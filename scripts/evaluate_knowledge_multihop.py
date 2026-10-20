#!/usr/bin/env python3
"""使用冻结多跳题集对照单轮与受控补查；检索覆盖、答案检查和技术失败分别记录。"""

import argparse
from collections import Counter
from datetime import datetime
import hashlib
import json
from pathlib import Path
import subprocess
import time
import uuid

import evaluate_trusted_knowledge as base

DATASET = base.ROOT / "docs/evaluation/multihop-cases.json"
FIXTURES = base.ROOT / "docs/demo/multihop"


def validate_dataset(dataset):
    """验证来源身份、文件路径与题目标签自洽，避免漏题或错误金标准进入跑分。"""
    documents, cases = dataset["documents"], dataset["cases"]
    identities = [f"{d['sourceKey']}:{d['businessVersion']}:{d['scope']}" for d in documents]
    if not cases or len({c["id"] for c in cases}) != len(cases) or len(set(identities)) != len(identities):
        raise ValueError("duplicate identities or empty cases")
    for document in documents:
        path = (FIXTURES / document["file"]).resolve()
        if path.parent != FIXTURES.resolve() or not path.is_file():
            raise ValueError("fixture path is invalid")
    for case in cases:
        if not set(case["requiredSources"]).issubset(identities):
            raise ValueError("unknown gold source")
        for identity in case["requiredSources"]:
            document = documents[identities.index(identity)]
            if document["publicationStatus"] != "PUBLISHED" or document["scope"] not in (case["scope"], "ALL"):
                raise ValueError("gold source outside publication or scope")
        if case["expectedStatus"] not in ("ANSWERABLE", "INSUFFICIENT_EVIDENCE", "CONFLICT"):
            raise ValueError("unsupported expected status")


def inspect_retrieval(case, result, state):
    """即使回答失败也从真实诊断中检查证据链；未知诊断不视为未召回或完整召回。"""
    diagnostic = result.get("diagnostic") or {}
    retrieval = diagnostic.get("retrieval")
    run = diagnostic.get("retrievalRun")
    searches = run.get("searches", []) if run else []
    incomplete = diagnostic.get("schemaVersion", 1) >= 3 and (not searches or
            len(searches) != sum(stage["stage"] == "RETRIEVAL" for stage in diagnostic.get("stages", [])) or
            any(row.get("response") is None or row.get("truncated") for row in searches))
    if result.get("diagnosticState") != "CAPTURED" or retrieval is None or diagnostic.get("retrievalTruncated") or incomplete:
        return {"coverageKnown": False, "retrievalCovered": None, "evidenceCovered": None,
                "failureLayer": "DIAGNOSTIC_UNAVAILABLE"}
    documents = {d["id"]: d for d in state["documents"]}

    def sources(items):
        """通过冻结文档身份映射检索结果，不依赖返回的文件名猜测来源。"""
        return sorted({base.source_key(documents[item["documentId"]]) for item in items if item["documentId"] in documents})

    retrieved = sources([hit for row in searches for hit in row["response"]["results"]] if searches else retrieval["results"])
    evidence = sources(diagnostic.get("evidence") or [])
    required = set(case["requiredSources"])
    covered = required.issubset(retrieved)
    actual_rounds = sum(stage["stage"] == "RETRIEVAL" for stage in diagnostic["stages"])
    return {"coverageKnown": True, "retrievedSources": retrieved, "evidenceSources": evidence,
            "missingSources": sorted(required - set(retrieved)), "retrievalCovered": covered,
            "evidenceCovered": required.issubset(evidence), "recordedRetrievalStages": actual_rounds,
            "firstSearchCovered": required.issubset(sources(retrieval["results"])),
            "retrievalRounds": max((row["round"] for row in searches), default=1),
            "planningCalls": sum(stage["stage"] == "PLANNING" for stage in diagnostic["stages"]),
            "stopReason": run.get("stopReason") if run else "LEGACY_SINGLE_PASS",
            "failureLayer": "PASSED_CHECKS" if result["outcome"] == "CHECK_PASSED"
            else "RETRIEVAL_GAP" if not covered else "ANSWER_OR_VERIFICATION"}


def summarize(dataset, results):
    """以冻结题数为分母，并保留遗漏、未知覆盖与技术失败，禁止重复结果灌高分。"""
    expected = {c["id"]: c for c in dataset["cases"]}
    if len({r["id"] for r in results}) != len(results):
        raise ValueError("duplicate case result")
    for result in results:
        case = expected.get(result["id"])
        if case is None or result["expectedStatus"] != case["expectedStatus"] or result["question"] != case["question"]:
            raise ValueError("result does not match frozen dataset")
    buckets = {}
    for kind in dict.fromkeys(c["kind"] for c in dataset["cases"]):
        rows = [r for r in results if expected[r["id"]]["kind"] == kind]
        buckets[kind] = {"planned": sum(c["kind"] == kind for c in dataset["cases"]), "executed": len(rows),
                         "automaticPassed": sum(r["outcome"] == "CHECK_PASSED" for r in rows),
                         "covered": sum(r.get("retrievalCovered") is True for r in rows),
                         "technicalFailures": sum(r["outcome"] == "TECHNICAL_ERROR" for r in rows)}
    return {"complete": len(results) == len(expected), "planned": len(expected), "executed": len(results),
            "retrievalCovered": sum(r.get("retrievalCovered") is True for r in results),
            "coverageUnknown": sum(not r.get("coverageKnown", False) for r in results),
            "layers": dict(Counter(r.get("failureLayer", "UNKNOWN") for r in results)), "byKind": buckets}


def save(output, report, dataset):
    """逐题存盘，并在原报告中追加证据覆盖诊断，避免把技术失败解释为正确拒答。"""
    report["multihopMetrics"] = summarize(dataset, report["results"])
    base.save_report(output, report)
    with (output / "report.md").open("a", encoding="utf-8") as handle:
        handle.write("\n## 累计证据链覆盖\n\n| 题号 | 覆盖完整 | 缺少来源 | 层次诊断 |\n| --- | --- | --- | --- |\n")
        for result in report["results"]:
            handle.write(f"| {result['id']} | {result.get('retrievalCovered')} | {', '.join(result.get('missingSources', []))} | {result.get('failureLayer')} |\n")
        handle.write("\n覆盖完整只表示金标准来源都进入候选，不证明答案正确。金标准引导的搜索探针属于人工指定查询，不属于 Agent 多跳成功。\n")


def prepare(client, dataset, report, output):
    """创建独立库并按冻结清单提交索引，草稿及失效资料也入库以验证资格边界。"""
    created = client.call("POST", "/api/admin/knowledge-bases", {"name": "多跳基线-" + uuid.uuid4().hex[:10], "description": dataset["datasetId"]})
    report["evaluationKnowledgeBaseId"] = created["id"]
    save(output, report, dataset)
    path = f"/api/admin/knowledge-bases/{created['id']}"
    client.call("PUT", path + "/retrieval-settings", dataset["retrievalSettings"])
    for item in dataset["documents"]:
        document = client.call("POST", path + "/documents", upload=FIXTURES / item["file"])
        location = path + f"/documents/{document['id']}"
        client.call("POST", location + "/submit", {"version": document["version"], "sourceKey": item["sourceKey"],
                    "businessVersion": item["businessVersion"], "applicabilityScope": item["scope"],
                    "validFrom": item["validFrom"], "validUntil": item["validUntil"]})
        deadline = time.monotonic() + 180
        while True:
            document = client.call("GET", location)
            if document["status"] == "READY":
                break
            if document["status"] == "FAILED" or time.monotonic() >= deadline:
                raise RuntimeError("fixture indexing did not succeed: " + item["file"])
            time.sleep(1)
        if item["publicationStatus"] == "PUBLISHED":
            client.call("POST", location + "/publish", {"version": document["version"]})
        print("INDEXED " + item["file"], flush=True)
    return created["id"]


def validate_state(state, dataset):
    """核对实际资料哈希、版本、期限及发布状态，禁止跑错库后仍计成绩。"""
    fields = {"candidateLimit": "retrievalCandidateLimit", "resultLimit": "retrievalResultLimit",
              "vectorMinSimilarity": "vectorMinSimilarity"}
    if any(state["knowledgeBase"][actual] != dataset["retrievalSettings"][expected] for expected, actual in fields.items()):
        raise ValueError("retrieval settings differ from frozen dataset")
    if len(state["documents"]) != len(dataset["documents"]):
        raise ValueError("fixture count mismatch")
    documents = {base.source_key(d): d for d in state["documents"]}
    for expected in dataset["documents"]:
        identity = f"{expected['sourceKey']}:{expected['businessVersion']}:{expected['scope']}"
        actual = documents.get(identity)
        if not actual or actual["status"] != "READY" or actual["publicationStatus"] != expected["publicationStatus"]:
            raise ValueError("fixture state mismatch")
        if actual["sha256"] != hashlib.sha256((FIXTURES / expected["file"]).read_bytes()).hexdigest():
            raise ValueError("fixture content mismatch")
        for key in ("validFrom", "validUntil"):
            parse = lambda value: datetime.fromisoformat(value) if value else None
            if parse(actual[key]) != parse(expected[key]):
                raise ValueError("fixture validity mismatch")
        if any(actual.get(key) is not None for key in ("predecessorDocumentId", "successorDocumentId", "activatedAt", "supersededAt", "sourceItemId")):
            raise ValueError("fixture unexpectedly belongs to a revision or source chain")


def probes(client, dataset, state, report, output):
    """基线回答全部结束后单独执行金标准查询，探测补查能否召回，绝不注入模型上下文。"""
    path = f"/api/admin/knowledge-bases/{state['knowledgeBase']['id']}/search"
    documents = {d["id"]: d for d in state["documents"]}
    cache = {}
    report["goldGuidedProbes"] = []
    for case in dataset["cases"]:
        if not case["probeQueries"]:
            continue
        rows, union = [], set()
        for query in case["probeQueries"]:
            cache_key = (case["scope"], query)
            if cache_key not in cache:
                started = time.monotonic()
                try:
                    result = client.call("POST", path, {"query": query, "applicabilityScope": case["scope"]})
                    cache[cache_key] = {"query": query, "scope": case["scope"], "sources": sorted({base.source_key(documents[r['documentId']]) for r in result["results"]}),
                                        "elapsedSeconds": round(time.monotonic() - started, 3), "result": result}
                except base.ApiError as error:
                    cache[cache_key] = {"query": query, "scope": case["scope"], "error": str(error), "sources": [],
                                        "elapsedSeconds": round(time.monotonic() - started, 3)}
            row = cache[cache_key]
            rows.append(row)
            union.update(row["sources"])
        report["goldGuidedProbes"].append({"id": case["id"], "queries": rows,
                "allCallsSucceeded": not any("error" in row for row in rows),
                "goldSourcesCovered": set(case["requiredSources"]).issubset(union)})
        report["probeUniqueCalls"] = len(cache)
        save(output, report, dataset)


def main():
    """执行一次真实基线，记录运行制品与冻结摘要，结束后恢复原默认知识库。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--runtime-label", required=True)
    parser.add_argument("--runtime-jar", type=Path)
    parser.add_argument("--knowledge-base-id", type=int, help="复用已导入的冻结题库，仍严格核对文件和元数据")
    parser.add_argument("--skip-probes", action="store_true", help="不重复执行人工金标准探针")
    args = parser.parse_args()
    if args.output.exists():
        raise RuntimeError("output already exists; preserve the original run")
    dataset = json.loads(DATASET.read_text(encoding="utf-8"))
    validate_dataset(dataset)
    now = datetime.now(base.CST)
    if not datetime.fromisoformat(dataset["validEvaluationFrom"]) <= now.replace(tzinfo=None) < datetime.fromisoformat(dataset["validEvaluationUntil"]):
        raise RuntimeError("outside frozen fixture validity window")
    base.load_credentials(base.ROOT / ".env")
    client = base.Client(args.base_url)
    client.login("admin")
    client.login("customer")
    original = base.snapshot(client)
    report = {"startedAt": now.isoformat(), "planned": len(dataset["cases"]), "results": [], "original": original,
              "baseUrl": args.base_url, "datasetId": dataset["datasetId"], "datasetSha256": hashlib.sha256(DATASET.read_bytes()).hexdigest(),
              "fixtureSha256": base.fingerprint([FIXTURES / d["file"] for d in dataset["documents"]]),
              "gitCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=base.ROOT, text=True).strip(),
              "dirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=base.ROOT, text=True).strip()),
              "runtimeLabel": args.runtime_label, "sourceSha256": base.fingerprint((base.ROOT / "src/main/java").rglob("*.java")),
              "runtimeJarSha256": hashlib.sha256(args.runtime_jar.read_bytes()).hexdigest() if args.runtime_jar else None,
              "restoration": "NOT_NEEDED"}
    save(args.output, report, dataset)
    evaluation_id = None
    try:
        evaluation_id = args.knowledge_base_id or prepare(client, dataset, report, args.output)
        report["evaluationKnowledgeBaseId"] = evaluation_id
        report["restoration"] = "PENDING"
        save(args.output, report, dataset)
        client.call("POST", f"/api/admin/knowledge-bases/{evaluation_id}/default")
        state = base.snapshot(client)
        validate_state(state, dataset)
        if state["models"] != original["models"]:
            raise RuntimeError("models changed during setup")
        report["evaluationSnapshot"] = state
        save(args.output, report, dataset)
        for case in dataset["cases"]:
            if base.snapshot(client) != state:
                raise RuntimeError("evaluation state changed concurrently")
            result = base.execute_case(client, case, state)
            result["kind"] = case["kind"]
            result.update(inspect_retrieval(case, result, state))
            report["results"].append(result)
            save(args.output, report, dataset)
            print(f"{case['id']} {result['outcome']} coverage={result.get('retrievalCovered')} {result['elapsedSeconds']}s", flush=True)
        if base.snapshot(client) != state:
            raise RuntimeError("evaluation state changed before diagnostic probes")
        if not args.skip_probes:
            probes(client, dataset, state, report, args.output)
    except BaseException as error:
        report["runError"] = type(error).__name__
        raise
    finally:
        if report["restoration"] == "PENDING":
            report["restoration"] = base.restore_default(client, original["knowledgeBase"]["id"], evaluation_id)
        report["finishedAt"] = datetime.now(base.CST).isoformat()
        save(args.output, report, dataset)
    return 0 if report["restoration"] == "RESTORED" and all(
        r["outcome"] == "CHECK_PASSED" and r.get("coverageKnown") for r in report["results"]) else 1


if __name__ == "__main__":
    raise SystemExit(main())
