"""独立固定输入回放规划协议；只读模型配置，不执行检索、草案、复核或知识库写入。"""

import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

from evaluate_trusted_knowledge import ROOT, fingerprint


def metrics(rows):
    """协议失败单列，已知成本和未知用量分开，不把失败当成正确停止。"""
    valid = [r for r in rows if r["errorCode"] is None]
    tokens = [(r.get("usage") or {}).get("totalTokens") for r in rows]
    return {"executed": len(rows), "protocolPassed": len(valid),
            "scenarioPassed": sum(r["scenarioChecksPassed"] for r in valid),
            "errors": dict(Counter(r["errorCode"] for r in rows if r["errorCode"])),
            "knownTokens": sum(t for t in tokens if t is not None),
            "callsMissingUsage": sum(t is None for t in tokens),
            "meanDurationMs": sum(r["durationMs"] for r in rows) / len(rows) if rows else None}


def summarize(dataset, rows, repeats):
    """两遍全部合法且场景检查通过才满足自动门槛，语义仍须另行阅读。"""
    ids = [c["id"] for c in dataset["cases"]]
    if repeats not in (1, 2) or not ids or len(set(ids)) != len(ids):
        raise ValueError("invalid dataset identity or repeats")
    seen = set()
    for row in rows:
        key = (row["id"], row["repeat"])
        if row["id"] not in ids or not 1 <= row["repeat"] <= repeats or key in seen:
            raise ValueError("invalid or duplicate evaluation identity")
        seen.add(key)
        if type(row.get("scenarioChecksPassed")) is not bool:
            raise ValueError("missing scenario check")
        if row["errorCode"] is None and not isinstance(row.get("plan"), dict):
            raise ValueError("missing plan without failure")
        if row["errorCode"] is not None and (row.get("plan") is not None or row["scenarioChecksPassed"]):
            raise ValueError("failed call cannot count as valid plan")
    complete = len(rows) == len(ids) * repeats
    return {"planned": len(ids) * repeats, "complete": complete, "metrics": metrics(rows),
            "byRepeat": {str(i): metrics([r for r in rows if r["repeat"] == i]) for i in range(1, repeats + 1)},
            "automaticGatePassed": complete and repeats == 2 and all(r["errorCode"] is None and r["scenarioChecksPassed"] for r in rows),
            "requiresSemanticReview": True}


def main():
    """显式构建独立规划 CLI，普通测试不联网，运行目录禁止覆盖。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=ROOT / "docs/evaluation/knowledge-planning-cases.json")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--repeats", type=int, default=2, choices=(1, 2))
    args = parser.parse_args()
    output = args.output.resolve()
    if not output.is_relative_to(ROOT / "target"):
        raise ValueError("raw evaluation output must be under target; archive only sanitized reports")
    output.mkdir(parents=True, exist_ok=False)
    dataset = json.loads(args.dataset.read_text(encoding="utf-8"))
    (output / "dataset.json").write_bytes(args.dataset.read_bytes())
    metadata = {"gitCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                "dirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip()),
                "sourceSha256": fingerprint((ROOT / "src/main/java").rglob("*.java")),
                "runnerSourceSha256": fingerprint((ROOT / "src/test/java/com/anying/helpdesk/knowledge/answer/evaluation").rglob("*.java")),
                "datasetSha256": hashlib.sha256(args.dataset.read_bytes()).hexdigest()}
    (output / "environment.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    classpath_file = ROOT / "target/planning-classpath.txt"
    with (output / "build.log").open("w", encoding="utf-8") as log:
        subprocess.run([shutil.which("mvn"), "-B", "-DskipTests", "test-compile", "dependency:build-classpath",
                        "-Dmdep.outputFile=" + str(classpath_file)], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True)
    classpath = os.pathsep.join([str(ROOT / "target/test-classes"), str(ROOT / "target/classes"),
                                classpath_file.read_text(encoding="utf-8").strip()])
    java_args = ["-cp", classpath, "com.anying.helpdesk.knowledge.answer.evaluation.KnowledgePlanningEvaluationCli",
                 str((output / "dataset.json").resolve()), str(output), str(args.repeats)]
    argument_file = output / "java-arguments.txt"
    argument_file.write_text("\n".join('"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"' for value in java_args), encoding="utf-8")
    command = [shutil.which("java"), "@" + str(argument_file)]
    print("Build completed; running fixed planning cases (logs stay under target).", flush=True)
    exit_code = 2
    try:
        with (output / "runtime.log").open("w", encoding="utf-8") as log:
            with subprocess.Popen(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                  text=True, encoding="utf-8", errors="replace") as process:
                try:
                    for line in process.stdout:
                        log.write(line)
                        log.flush()
                        if line.startswith("PLANNING "):
                            print(line.rstrip(), flush=True)
                    exit_code = process.wait()
                except BaseException:
                    process.terminate()
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        process.kill()
                    raise
    finally:
        path = output / "calls.jsonl"
        rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()] if path.exists() else []
        summary = summarize(dataset, rows, args.repeats)
        summary["runnerExitCode"] = exit_code
        summary["finished"] = (output / "finished.json").exists()
        if exit_code != 0 or not summary["finished"]:
            summary["automaticGatePassed"] = False
        (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(summary["metrics"], ensure_ascii=False), flush=True)
    return 0 if exit_code == 0 and summary["complete"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
