"""固定问题、证据和草案，对比复核策略的误杀、漏检、稳定性与已知用量。"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
from collections import defaultdict

from evaluate_trusted_knowledge import ROOT, fingerprint


def metrics(rows, planned):
    """技术失败单独保留，不把负例的调用失败计为正确拦截。"""
    positives = [r for r in rows if r["expectedApproved"]]
    negatives = [r for r in rows if not r["expectedApproved"]]
    valid = [r for r in rows if r.get("errorCode") is None and r.get("review") is not None]
    false_reject = sum(r["expectedApproved"] and not r["review"]["approved"] for r in valid)
    false_accept = sum(not r["expectedApproved"] and r["review"]["approved"] for r in valid)
    known = [(r.get("usage") or {}).get("totalTokens") for r in rows]
    decisions = defaultdict(list)
    for row in valid:
        decisions[row["id"]].append(row["review"]["approved"])
    repeated = [values for values in decisions.values() if len(values) >= 2]
    return {"planned": planned, "executed": len(rows), "positiveAttempts": len(positives), "negativeAttempts": len(negatives),
            "falseRejects": false_reject, "falseAccepts": false_accept,
            "falseRejectRate": false_reject / len(positives) if positives else None,
            "falseAcceptRate": false_accept / len(negatives) if negatives else None,
            "technicalFailures": len(rows) - len(valid),
            "positiveTechnicalFailures": sum(r["expectedApproved"] and r not in valid for r in rows),
            "negativeTechnicalFailures": sum(not r["expectedApproved"] and r not in valid for r in rows),
            "correctDecisions": len(valid) - false_reject - false_accept,
            "knownTokens": sum(v for v in known if v is not None), "callsMissingUsage": sum(v is None for v in known),
            "meanDurationMs": sum(r["durationMs"] for r in rows) / len(rows) if rows else None,
            "casesWithRepeatedValidDecisions": len(repeated), "casesWithDecisionFlip": sum(len(set(v)) > 1 for v in repeated)}


def summarize(dataset, rows, repeats, candidate_strategy="CHECKLIST"):
    """核对结果身份、去重并分层计算；两轮都满足事先冻结的条件才进入端到端候选验证。"""
    if candidate_strategy not in ("CHECKLIST", "CONTEXTUAL", "REASONED"):
        raise ValueError("invalid candidate strategy")
    cases = {case["id"]: case for case in dataset["cases"]}
    seen = set()
    for row in rows:
        case = cases.get(row["id"])
        identity = (row["id"], row["strategy"], row["repeat"])
        if (not case or identity in seen or row["strategy"] not in ("BASELINE", candidate_strategy)
                or not 1 <= row["repeat"] <= repeats or row["expectedApproved"] != case["expectedApproved"]
                or row["split"] != case["split"] or row["pairId"] != case["pairId"]):
            raise ValueError("invalid or duplicate evaluation identity")
        seen.add(identity)
        if row.get("errorCode") is None and (not isinstance(row.get("review"), dict)
                                            or type(row["review"].get("approved")) is not bool):
            raise ValueError("missing valid decision without technical error")
    summary = {"candidate": candidate_strategy, "strategies": {}, "byRepeat": {}, "bySplit": {}, "complete": len(rows) == len(cases) * repeats * 2}
    for strategy in ("BASELINE", candidate_strategy):
        selected = [r for r in rows if r["strategy"] == strategy]
        summary["strategies"][strategy] = metrics(selected, len(cases) * repeats)
        summary["byRepeat"][strategy] = {str(i): metrics([r for r in selected if r["repeat"] == i], len(cases))
                                         for i in range(1, repeats + 1)}
        summary["bySplit"][strategy] = {split: metrics([r for r in selected if r["split"] == split],
                sum(c["split"] == split for c in cases.values()) * repeats) for split in ("DEVELOPMENT", "CHALLENGE")}
    eligible = summary["complete"] and repeats >= 2
    for i in range(1, repeats + 1):
        baseline = summary["byRepeat"]["BASELINE"][str(i)]
        candidate = summary["byRepeat"][candidate_strategy][str(i)]
        reject_gate = (candidate["falseRejects"] <= baseline["falseRejects"] if candidate_strategy == "REASONED"
                       else candidate["falseRejects"] < baseline["falseRejects"])
        eligible &= (reject_gate
                     and candidate["falseAccepts"] <= baseline["falseAccepts"]
                     and candidate["technicalFailures"] <= baseline["technicalFailures"])
        challenge = {strategy: metrics([r for r in rows if r["strategy"] == strategy
                    and r["repeat"] == i and r["split"] == "CHALLENGE"], sum(c["split"] == "CHALLENGE" for c in cases.values())) for strategy in ("BASELINE", candidate_strategy)}
        eligible &= all(challenge[candidate_strategy][key] <= challenge["BASELINE"][key]
                        for key in ("falseRejects", "falseAccepts", "technicalFailures"))
    if candidate_strategy == "REASONED":
        summary["gatePurpose"] = "诊断增强：每轮误拒、误放、技术失败及挑战集均不得退化；完整原因必需，不宣称准确率提升"
        eligible &= all(r.get("errorCode") is None and r["review"].get("reason") is not None
                        and isinstance(r["review"].get("issues"), list)
                        and (not r["review"]["approved"] or r["review"]["reason"] == "NONE")
                        and (r["review"]["approved"] or bool(r["review"]["issues"]))
                        for r in rows if r["strategy"] == "REASONED")
    summary["candidateEligibleForEndToEnd"] = bool(eligible)
    return summary


def main():
    """显式构建并调用测试目录中的 CLI，普通单元测试不联网；输出目录禁止覆盖。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=ROOT / "docs/evaluation/knowledge-review-cases.json")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--repeats", type=int, default=2, choices=range(1, 6))
    parser.add_argument("--candidate", choices=("CHECKLIST", "CONTEXTUAL", "REASONED"), default="CHECKLIST")
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
    classpath_file = ROOT / "target/review-classpath.txt"
    with (output / "build.log").open("w", encoding="utf-8") as log:
        subprocess.run([shutil.which("mvn"), "-B", "-DskipTests", "test-compile", "dependency:build-classpath",
                        "-Dmdep.outputFile=" + str(classpath_file)], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True)
    classpath = os.pathsep.join([str(ROOT / "target/test-classes"), str(ROOT / "target/classes"),
                                classpath_file.read_text(encoding="utf-8").strip()])
    java_args = ["-cp", classpath, "com.anying.helpdesk.knowledge.answer.evaluation.KnowledgeReviewEvaluationCli",
                 str((output / "dataset.json").resolve()), str(output), str(args.repeats), args.candidate]
    argument_file = output / "java-arguments.txt"
    argument_file.write_text("\n".join('"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"' for value in java_args), encoding="utf-8")
    command = [shutil.which("java"), "@" + str(argument_file)]
    print("Build completed; running fixed review cases (logs stay under target).", flush=True)
    exit_code = 2
    try:
        with (output / "runtime.log").open("w", encoding="utf-8") as log:
            with subprocess.Popen(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                  text=True, encoding="utf-8", errors="replace") as process:
                try:
                    for line in process.stdout:
                        log.write(line)
                        log.flush()
                        if line.startswith("REVIEW "):
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
        summary = summarize(dataset, rows, args.repeats, args.candidate)
        summary["runnerExitCode"] = exit_code
        summary["finished"] = (output / "finished.json").exists()
        if exit_code != 0 or not summary["finished"]:
            summary["candidateEligibleForEndToEnd"] = False
        (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(summary["strategies"], ensure_ascii=False), flush=True)
    return 0 if exit_code == 0 and summary["complete"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
