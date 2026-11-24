"""小规模企业关联问答对照：真实检索与模型，固定事实规则评分，不自动调参。"""
import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time

from evaluate_trusted_knowledge import Client, ROOT, fingerprint
from evaluate_public_retrieval import read, write

DATASET = ROOT / "docs/evaluation/bridge-qa/cases.json"
CACHE = ROOT / "data/bridge-qa"


def prepare(tokens):
    """导入自建资料到独立知识库，保留逐步状态并冻结文档元数据，不改变默认库。"""
    dataset = read(DATASET)
    CACHE.mkdir(parents=True, exist_ok=True)
    client = Client("http://localhost:8080", read(tokens))
    state_path = CACHE / "state.json"
    checksum = hashlib.sha256(DATASET.read_bytes()).hexdigest()
    if state_path.exists():
        state = read(state_path)
        if state["datasetSha256"] != checksum:
            raise ValueError("dataset differs from imported corpus")
    else:
        kb = client.call("POST", "/api/admin/knowledge-bases", {"name": "企业关联问答评测-v1", "description": dataset["description"]})
        state = {"baseId": kb["id"], "datasetSha256": checksum, "documents": {}}
        write(state_path, state)
    root = f"/api/admin/knowledge-bases/{state['baseId']}"
    client.call("PUT", root + "/retrieval-settings", dataset["retrievalSettings"])
    for doc in dataset["documents"]:
        file = CACHE / (doc["id"] + ".md")
        file.write_bytes(doc["content"].encode("utf-8"))
        if doc["id"] not in state["documents"]:
            created = client.call("POST", root + "/documents", upload=file)
            state["documents"][doc["id"]] = created["id"]
            write(state_path, state)
        location = root + f"/documents/{state['documents'][doc['id']]}"
        current = client.call("GET", location)
        if current["sha256"] != hashlib.sha256(file.read_bytes()).hexdigest():
            raise ValueError("document content changed")
        if current["status"] == "PENDING":
            client.call("POST", location + "/submit", {"version": current["version"], "sourceKey": "bridge:" + doc["id"],
                        "businessVersion": "v1", "applicabilityScope": "ALL", "validFrom": "2020-01-01T00:00:00", "validUntil": None})
    deadline = time.monotonic() + 600
    while True:
        snapshots = [client.call("GET", root + f"/documents/{i}") for i in state["documents"].values()]
        if any(d["status"] == "FAILED" for d in snapshots):
            raise RuntimeError("index task failed")
        for d in snapshots:
            if d["status"] == "READY" and d["publicationStatus"] == "DRAFT":
                client.call("POST", root + f"/documents/{d['id']}/publish", {"version": d["version"]})
        if all(d["status"] == "READY" and d["publicationStatus"] == "PUBLISHED" for d in snapshots):
            break
        if time.monotonic() > deadline:
            raise TimeoutError("index tasks not ready")
        time.sleep(2)
    write(CACHE / "input.json", {"baseId": state["baseId"], "cases": dataset["cases"], "documents": snapshots})
    print("READY", len(snapshots), "documents", sum(d["chunkCount"] for d in snapshots), "chunks", flush=True)


def environment(client, base_id):
    """只保存无密钥的模型与知识库状态，确认对照过程中没有配置漂移。"""
    root = f"/api/admin/knowledge-bases/{base_id}"
    state = read(CACHE / "state.json")
    fields = ["id", "modelType", "modelName", "embeddingDimensions", "updatedAt"]
    models = [{k: m.get(k) for k in fields} for m in client.call("GET", "/api/admin/model-configs") if m["isDefault"]]
    return {"models": models, "knowledgeBase": client.call("GET", root),
            "documents": [client.call("GET", root + f"/documents/{i}") for i in state["documents"].values()]}


def run(output, tokens):
    """编译隔离 CLI，两组真实运行且共享首轮检索，运行失败不挑选重跑。"""
    output.mkdir(parents=True, exist_ok=False)
    shutil.copyfile(CACHE / "input.json", output / "input.json")
    client = Client("http://localhost:8080", read(tokens))
    state = read(CACHE / "state.json")
    before = environment(client, state["baseId"])
    if before["documents"] != read(CACHE / "input.json")["documents"]:
        raise ValueError("corpus changed since prepare")
    metadata = {"gitHead": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                "datasetSha256": hashlib.sha256(DATASET.read_bytes()).hexdigest(),
                "sourceSha256": fingerprint(list((ROOT / "src/main/java").rglob("*.java")) +
                    list((ROOT / "src/test/java/com/anying/helpdesk/knowledge/answer/evaluation").glob("*.java")) + [Path(__file__).resolve()]),
                "before": before}
    write(output / "environment.json", metadata)
    cpfile = ROOT / "target/bridge-classpath.txt"
    with (output / "build.log").open("w", encoding="utf-8") as log:
        subprocess.run([shutil.which("mvn"), "-B", "-DskipTests", "test-compile", "dependency:build-classpath",
                        "-Dmdep.outputFile=" + str(cpfile)], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True)
    cp = os.pathsep.join([str(ROOT / "target/test-classes"), str(ROOT / "target/classes"), cpfile.read_text().strip()])
    args = ["-cp", cp, "com.anying.helpdesk.knowledge.answer.evaluation.BridgeQaEvaluationCli",
            str(output), str(tokens.resolve())]
    argfile = output / "java-arguments.txt"
    argfile.write_text("\n".join('"' + a.replace("\\", "\\\\").replace('"', '\\"') + '"' for a in args), encoding="utf-8")
    print("RUNNING 20 pairs", flush=True)
    with (output / "runtime.log").open("w", encoding="utf-8") as log:
        with subprocess.Popen([shutil.which("java"), "@" + str(argfile)], cwd=ROOT, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace") as process:
            for line in process.stdout:
                log.write(line)
                log.flush()
                if line.startswith("BRIDGE "):
                    print(line.strip(), flush=True)
            exit_code = process.wait()
    metadata["after"] = environment(client, state["baseId"])
    metadata["stable"] = metadata["after"] == before
    metadata["exitCode"] = exit_code
    write(output / "environment.json", metadata)
    if exit_code != 0 or not metadata["stable"]:
        raise RuntimeError("runner failed or environment changed")


def grade(case, row):
    """两项标准须由正确规则引文支持且关联资料齐备；部分命中或未交付不算完整回答。"""
    diagnostic = row.get("diagnostic") or {}
    evidence = diagnostic.get("evidence") or []
    sources = {e["id"]: e["metadata"]["sourceKey"].removeprefix("bridge:") for e in evidence}
    covered = set(case["requiredDocuments"]).issubset(sources.values())
    answer = row.get("answer")
    facts = []
    for fact in case["facts"]:
        # 允许“每晚530元”和“530元/晚”等表述，必须在同一结论明确对应字段并引用目标细则。
        unit = fact["unit"].split("/")[0]
        pattern = rf"{re.escape(fact['field'])}[^\d。；;\n]{{0,20}}{fact['value']}\s*{re.escape(unit)}"
        facts.append(bool(answer) and any(re.search(pattern, c["text"])
                    and any(sources.get(r["evidenceId"]) == case["id"] + "-rule" for r in c["references"])
                    for c in answer["claims"]))
    searches = (diagnostic.get("retrievalRun") or {}).get("searches", [])
    return {"evidenceComplete": covered, "facts": facts,
            "completeAnswer": bool(answer and answer["status"] == "ANSWERABLE" and covered and all(facts)),
            "searchCalls": sum(s["stage"] == "RETRIEVAL" for s in diagnostic.get("stages", [])),
            "successfulSearchCalls": len(searches),
            "planningCalls": sum(s["stage"] == "PLANNING" for s in diagnostic.get("stages", []))}


def summarize(output):
    """按预先冻结的20题汇总，保存全部答案及失败，禁止删除不利样本。"""
    dataset = read(DATASET)
    cases = {c["id"]: c for c in dataset["cases"]}
    rows = [json.loads(line) for line in (output / "calls.jsonl").read_text(encoding="utf-8").splitlines() if line.strip()]
    identities = [(r["id"], r["arm"]) for r in rows]
    if len(set(identities)) != len(rows) or any(i not in cases or arm not in ["SINGLE", "MULTIHOP"] for i, arm in identities):
        raise ValueError("invalid result identities")
    for row in rows:
        row["metrics"] = grade(cases[row["id"]], row)
    summary = {}
    for arm in ["SINGLE", "MULTIHOP"]:
        chosen = [r for r in rows if r["arm"] == arm]
        summary[arm] = {"planned": len(cases), "executed": len(chosen),
            "completeAnswers": sum(r["metrics"]["completeAnswer"] for r in chosen),
            "completeEvidence": sum(r["metrics"]["evidenceComplete"] for r in chosen),
            "delivered": sum(r.get("answer") is not None for r in chosen),
            "errors": dict(Counter(r["error"] for r in chosen if r.get("error"))),
            "searchCalls": sum(r["metrics"]["searchCalls"] for r in chosen),
            "planningCalls": sum(r["metrics"]["planningCalls"] for r in chosen)}
    report = {"annotation": dataset["annotation"], "complete": len(rows) == 2 * len(cases) and (output / "finished.json").exists(),
              "summary": summary, "results": rows}
    write(output / "report.json", report)
    lines = ["# 企业关联问答逐题核对", "", "自建虚构场景，自动规则检查不等同于人工正确率；请核对结论、单位、关联链及引用。", ""]
    for case in cases.values():
        lines += [f"## {case['id']}：{case['question']}", "", "标准事实：" + json.dumps(case["facts"], ensure_ascii=False), ""]
        for row in [r for r in rows if r["id"] == case["id"]]:
            lines += ["### " + row["arm"], "", "```json", json.dumps({k: row.get(k) for k in ["answer", "error", "metrics"]}, ensure_ascii=False, indent=2), "```", ""]
    (output / "逐题核对.md").write_text("\n".join(lines), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


def main():
    """分别导入、显式付费运行和本地汇总，保持一次实验的参数和原始输出不变。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["prepare", "run", "summarize"])
    parser.add_argument("--tokens", type=Path, default=ROOT / "data/public-campus/tokens.json")
    parser.add_argument("--output", type=Path, default=CACHE / "initial")
    args = parser.parse_args()
    output = args.output.resolve()
    if not output.is_relative_to(ROOT / "data"):
        raise ValueError("raw output must be under data/")
    if args.command == "prepare":
        prepare(args.tokens)
    elif args.command == "run":
        run(output, args.tokens)
    else:
        summarize(output)


if __name__ == "__main__":
    main()
