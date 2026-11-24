#!/usr/bin/env python3
"""固定公开制度的真实检索快照，对比同一初稿在语义复核前后的交付质量。"""

import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

from evaluate_public_retrieval import corpus, read, write, SETTINGS
from evaluate_trusted_knowledge import Client, ROOT, fingerprint
from prepare_public_retrieval_corpus import BENCHMARK, CACHE


def prepare(output, tokens):
    """冻结全部测试题、参考原文和真实 RRF 前十片段；标准证据只给评审，不给回答模型。"""
    output.mkdir(parents=True, exist_ok=False)
    _, documents = corpus()
    dataset = read(BENCHMARK / "cases.json")
    previous = read(CACHE / "test-run.json")
    if previous["casesSha256"] != hashlib.sha256((BENCHMARK / "cases.json").read_bytes()).hexdigest():
        raise ValueError("retrieval cases changed")
    client = Client("http://localhost:8080", read(tokens))
    state = read(CACHE / "state.json")
    root = f"/api/admin/knowledge-bases/{state['baseId']}"
    base = client.call("GET", root)
    fields = {"candidateLimit": "retrievalCandidateLimit", "resultLimit": "retrievalResultLimit", "vectorMinSimilarity": "vectorMinSimilarity"}
    if any(base[fields[k]] != value for k, value in SETTINGS.items()):
        raise ValueError("retrieval settings differ")
    current = [client.call("GET", root + f"/documents/{d['id']}") for d in previous["documents"]]
    if current != previous["documents"]:
        raise ValueError("corpus metadata changed since retrieval run")
    rows = {r["id"]: r for r in previous["results"]}
    negatives = []
    cases = []
    for case in [c for c in dataset["cases"] if c["split"] == "test"] + dataset["unanswerable"]:
        answerable = "evidenceGroups" in case
        if answerable:
            response = rows[case["id"]]["response"]
        else:
            response = client.call("POST", root + "/search-candidates", {"query": case["question"]})
            negatives.append({"id": case["id"], "response": response})
        evidence = [{"id": f"E{i}", "documentId": hit["documentId"], "chunkSequence": hit["chunkSequence"],
                     "sourceName": hit["originalName"], "content": hit["content"], "metadata": hit["metadata"]}
                    for i, hit in enumerate(response["results"][:10], 1)]
        references = [documents[e["document"]]["paragraphs"][e["paragraph"]]
                      for group in case.get("evidenceGroups", []) for e in group]
        cases.append({"id": case["id"], "question": case["question"], "answerable": answerable,
                      "referenceParagraphs": references, "evidence": evidence})
    write(output / "dataset.json", {"cases": cases})
    write(output / "negative-retrieval.json", negatives)
    write(output / "environment.json", {
        "gitHead": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "sourceSha256": fingerprint(list((ROOT / "src/main/java").rglob("*.java"))
                                   + list((ROOT / "src/test/java/com/anying/helpdesk/knowledge/answer/evaluation").glob("*.java"))
                                   + [Path(__file__).resolve()]),
        "retrievalRunSha256": hashlib.sha256((CACHE / "test-run.json").read_bytes()).hexdigest(),
        "annotationStatus": "AI_ASSISTED_NOT_HUMAN_REVIEWED", "settings": SETTINGS, "topChunks": 10,
        "plannedAnswerable": 70, "plannedUnanswerable": 10,
        "comparison": "same draft and citation structure validation; full adds semantic review and at most one revision",
        "exclusions": ["live retrieval", "multihop planning", "conversation history", "HTTP/SSE", "live eligibility recheck"]})
    print("PREPARED", len(cases), flush=True)


def run(output, judge_only=False):
    """显式编译并启动离线 CLI，普通测试不消耗模型额度，运行日志留在忽略目录。"""
    if (output / ("judgments.jsonl" if judge_only else "calls.jsonl")).exists():
        raise ValueError("run already exists; preserve prior calls")
    classpath_file = ROOT / "target/answer-classpath.txt"
    with (output / ("judge-build.log" if judge_only else "build.log")).open("w", encoding="utf-8") as log:
        subprocess.run([shutil.which("mvn"), "-B", "-DskipTests", "test-compile", "dependency:build-classpath",
                        "-Dmdep.outputFile=" + str(classpath_file)], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True)
    cp = os.pathsep.join([str(ROOT / "target/test-classes"), str(ROOT / "target/classes"), classpath_file.read_text().strip()])
    arguments = ["-cp", cp, "com.anying.helpdesk.knowledge.answer.evaluation.PublicAnswerEvaluationCli",
                 str(output / "dataset.json"), str(output)]
    if judge_only:
        arguments.append("judge")
    write(output / ("judge-build.json" if judge_only else "build-environment.json"), {
        "runnerSourceSha256": fingerprint((ROOT / "src/test/java/com/anying/helpdesk/knowledge/answer/evaluation").glob("*.java")),
        "runnerBytecodeSha256": fingerprint((ROOT / "target/test-classes/com/anying/helpdesk/knowledge/answer/evaluation").glob("*.class"))})
    argument_file = output / "java-arguments.txt"
    argument_file.write_text("\n".join('"' + a.replace("\\", "\\\\").replace('"', '\\"') + '"' for a in arguments), encoding="utf-8")
    print("RUNNING real model evaluation", flush=True)
    with (output / ("judge-runtime.log" if judge_only else "runtime.log")).open("w", encoding="utf-8") as log:
        with subprocess.Popen([shutil.which("java"), "@" + str(argument_file)], cwd=ROOT, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace") as process:
            for line in process.stdout:
                log.write(line)
                log.flush()
                if line.startswith("ANSWER "):
                    print(line.strip(), flush=True)
            if process.wait() != 0:
                raise RuntimeError("runner failed; inspect local runtime.log")


def metrics(cases, rows, arm):
    """失败和未执行项保留在题目分母；评审缺失显式统计，拒答不贡献引用支持率。"""
    by_id = {r["id"]: r for r in rows}
    if len(by_id) != len(rows) or set(by_id) - {c["id"] for c in cases}:
        raise ValueError("duplicate or unknown evaluation case")
    counts = dict(planned=len(cases), executed=len(rows), answerable=0, unanswerable=0,
                  delivered=0, answerableDelivered=0, answerableStatus=0, failures=0, unexecuted=0,
                  correctComplete=0, correctAbstentions=0, judgeFailures=0,
                  citedClaims=0, supportedCitedClaims=0, allClaims=0, unjudgedClaims=0)
    for case in cases:
        positive = case["answerable"]
        counts["answerable" if positive else "unanswerable"] += 1
        row = by_id.get(case["id"])
        if row is None:
            counts["unexecuted"] += 1
            continue
        answer = row.get(arm)
        if answer is None:
            counts["failures"] += 1
            continue
        counts["delivered"] += 1
        counts["answerableDelivered"] += positive
        counts["answerableStatus"] += positive and answer["status"] == "ANSWERABLE"
        claims = {f"claims/{i}": c for i, c in enumerate(answer["claims"])}
        for i, conflict in enumerate(answer["conflicts"]):
            claims.update({f"conflicts/{i}/{side}": conflict[side] for side in ("first", "second")})
        counts["allClaims"] += len(claims)
        counts["citedClaims"] += sum(bool(c["references"]) for c in claims.values())
        judge = (row.get(arm + "Judge") or {}).get("assessment")
        if judge is None:
            counts["judgeFailures"] += 1
            counts["unjudgedClaims"] += len(claims)
            continue
        checks = {c["path"]: c for c in judge["claims"]}
        if len(checks) != len(judge["claims"]) or set(checks) != set(claims):
            raise ValueError("judge omitted or duplicated claims")
        counts["correctComplete"] += positive and judge["correctComplete"]
        counts["correctAbstentions"] += not positive and judge["appropriateAbstention"]
        counts["supportedCitedClaims"] += sum(c["supported"] and bool(claims[p]["references"]) for p, c in checks.items())
    counts["correctDeliveryRate"] = counts["correctComplete"] / counts["answerable"] if counts["answerable"] and not counts["judgeFailures"] else None
    counts["correctAbstentionRate"] = counts["correctAbstentions"] / counts["unanswerable"] if counts["unanswerable"] and not counts["judgeFailures"] else None
    counts["citationSupportRate"] = (counts["supportedCitedClaims"] / counts["citedClaims"]
                                     if counts["citedClaims"] and not counts["unjudgedClaims"] else None)
    counts["citationCoverage"] = counts["citedClaims"] / counts["allClaims"] if counts["allClaims"] else None
    return counts


def summarize(output, archive=None):
    """生成可审计的辅助评分和逐题复核表，不将系统通过率冒充答案正确率。"""
    cases = read(output / "dataset.json")["cases"]
    rows = [json.loads(line) for line in (output / "calls.jsonl").read_text(encoding="utf-8").splitlines() if line.strip()]
    if (output / "judgments.jsonl").exists():
        judgments = [json.loads(line) for line in (output / "judgments.jsonl").read_text(encoding="utf-8").splitlines() if line.strip()]
        by_id = {r["id"]: r for r in judgments}
        if len(by_id) != len(judgments) or set(by_id) - {r["id"] for r in rows}:
            raise ValueError("invalid replacement judgments")
        for row in rows:
            for arm in ["baseline", "full"]:
                row.pop(arm + "Judge", None)
            row.update(by_id.get(row["id"], {}))
    summary = {"assessment": "SAME_MODEL_ASSISTED_NOT_HUMAN_REVIEWED",
               "complete": len(rows) == len(cases) and (output / "finished.json").exists()
                           and (not (output / "judgments.jsonl").exists() or (output / "judge-finished.json").exists()),
               "arms": {arm: metrics(cases, rows, arm) for arm in ["baseline", "full"]}}
    summary["fullErrors"] = dict(Counter(r["fullError"] for r in rows if r.get("fullError")))
    summary["revisedCases"] = [r["id"] for r in rows if any(s["stage"] == "REVISION" for s in r["diagnostic"]["stages"])]
    summary["pairedCorrectness"] = dict(Counter(
        str(bool((r.get("baselineJudge") or {}).get("assessment", {}).get("correctComplete"))) + "->" +
        str(bool((r.get("fullJudge") or {}).get("assessment", {}).get("correctComplete")))
        for r in rows if r["answerable"]))
    write(output / "summary.json", summary)
    lookup = {r["id"]: r for r in rows}
    review = ["# 问答评测逐题复核", "", "以下评分由同一模型辅助生成，未经过人工复核。请检查必要要点、无据扩展、引用支持及拒答。", ""]
    for case in cases:
        row = lookup.get(case["id"], {})
        review += [f"## {case['id']}：{case['question']}", "", f"预期有答案：{case['answerable']}", "", "### 参考段落", ""]
        review += ["> " + p.replace("\n", "\n> ") for p in case["referenceParagraphs"]]
        for arm in ["baseline", "full"]:
            review += ["", "### " + arm, "", "```json", json.dumps({"answer": row.get(arm), "judge": row.get(arm + "Judge")}, ensure_ascii=False, indent=2), "```", ""]
        review += ["人工结论：待复核", ""]
    (output / "人工复核.md").write_text("\n".join(review), encoding="utf-8")
    if archive:
        archive_result(output, cases, rows, summary, archive)
    print(json.dumps(summary, ensure_ascii=False, indent=2))


def archive_result(output, cases, rows, summary, archive):
    """归档不含原文大段正文的逐题结果、摘要和判断依据，原始答案及证据留在本地复核表。"""
    by_id = {r["id"]: r for r in rows}
    compact = []
    for case in cases:
        row = by_id.get(case["id"], {})
        item = {"id": case["id"], "question": case["question"], "answerable": case["answerable"],
                "fullError": row.get("fullError"), "durationMs": row.get("durationMs"),
                "baselineJudge": row.get("baselineJudge"), "fullJudge": row.get("fullJudge")}
        for arm in ["baseline", "full"]:
            answer = row.get(arm)
            item[arm] = None if answer is None else {
                "status": answer["status"], "claims": [c["text"] for c in answer["claims"]],
                "missingConditions": answer["missingConditions"], "conflicts": [c["issue"] for c in answer["conflicts"]]}
        compact.append(item)
    hashes = {name: hashlib.sha256((output / name).read_bytes()).hexdigest()
              for name in ["dataset.json", "calls.jsonl", "judgments.jsonl"] if (output / name).exists()}
    write(archive, {"summary": summary, "generation": read(output / "metadata.json"),
                    "judge": read(output / "judge-metadata.json") if (output / "judge-metadata.json").exists() else read(output / "metadata.json"),
                    "environment": read(output / "environment.json"), "rawSha256": hashes, "results": compact})


def main():
    """分离冻结输入、付费执行和本地统计三个步骤，拒绝覆盖已经完成的模型调用。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["prepare", "run", "judge", "summarize"])
    parser.add_argument("--output", type=Path, default=CACHE / "answers-initial")
    parser.add_argument("--tokens", type=Path, default=CACHE / "tokens.json")
    parser.add_argument("--archive", type=Path)
    args = parser.parse_args()
    output = args.output.resolve()
    if not output.is_relative_to(ROOT / "data"):
        raise ValueError("raw output must stay under ignored data/")
    if args.command == "prepare":
        prepare(output, args.tokens)
    elif args.command in ("run", "judge"):
        run(output, args.command == "judge")
    else:
        summarize(output, args.archive)


if __name__ == "__main__":
    main()
