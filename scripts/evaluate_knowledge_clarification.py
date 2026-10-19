#!/usr/bin/env python3
"""用隔离资料库验证主动澄清和地区续答；保留全部单次结果，不自动重试模型。"""

import argparse
import hashlib
import json
import subprocess
import time
from datetime import datetime
from pathlib import Path

import evaluate_trusted_knowledge as base


def check_clarification(result, policy):
    """澄清必须可实际续答，只有自然语言追问不能算作结构化闭环通过。"""
    if result["outcome"] == "TECHNICAL_ERROR":
        return
    report = result["message"]["knowledgeAnswer"]
    clarification = report.get("clarification") or {}
    required = next(topic for topic in policy["topics"] if topic["id"] == "travel-lodging")
    if (report["reason"] != "APPLICABILITY_REQUIRED"
            or clarification.get("originalQuestion") != result["question"]
            or clarification.get("policyRevision") != policy["revision"]
            or clarification.get("topicIds") != [required["id"]]
            or clarification.get("scopeOptions") != required["scopeOptions"]
            or report["evidence"] or report["answer"]["claims"]):
        result["problems"].append("invalid_structured_clarification")
        result["outcome"] = "CHECK_FAILED"


def continue_case(client, initial, scope, state):
    """补充明确地区后按原问题判分，并检查续答来源和新的检索诊断。"""
    case = {"id": "CONTINUE_" + scope, "scenario": "baseline", "question": initial["question"],
            "scope": scope, "expectedStatus": "ANSWERABLE",
            "requiredSources": ["travel-policy:" + ("v2:CN_SH" if scope == "CN_SH" else "v1:CN_BJ")],
            "requiredPatterns": ["600|六百" if scope == "CN_SH" else "450|四百五十"]}
    result = {**case, "conversationId": initial["conversationId"], "semanticReview": "PENDING"}
    if initial["outcome"] != "CHECK_PASSED":
        return {**result, "outcome": "NOT_RUN", "elapsedSeconds": 0,
                "diagnosticState": "NO_RUN_ID", "problems": ["initial_clarification_failed"]}
    started = time.monotonic()
    source_id = initial["message"]["id"]
    try:
        exchange = client.call("POST", f"/api/conversations/{initial['conversationId']}/messages",
                               {"content": "我选择" + ("上海" if scope == "CN_SH" else "北京"), "mode": "KNOWLEDGE",
                                "applicabilityScope": scope, "clarificationMessageId": source_id}, "customer")
        result["message"] = exchange["assistantMessage"]
        result["problems"], details = base.check_answer(case, result["message"], state)
        result.update(details)
        if result["message"]["knowledgeAnswer"].get("continuationOfMessageId") != source_id:
            result["problems"].append("wrong_continuation_source")
        result["outcome"] = "CHECK_FAILED" if result["problems"] else "CHECK_PASSED"
    except base.ApiError as error:
        result.update(outcome="TECHNICAL_ERROR", httpStatus=error.status, errorCode=error.code)
        try:
            history = client.call("GET", f"/api/conversations/{initial['conversationId']}/messages", role="customer")
            result["failedMessage"] = history["records"][-1]
        except base.ApiError:
            result["historyUnavailable"] = True
    result["elapsedSeconds"] = round(time.monotonic() - started, 3)
    base.collect_diagnostics(client, result)
    if result["outcome"] == "CHECK_PASSED":
        diagnostic = result.get("diagnostic") or {}
        if (result.get("diagnosticState") != "CAPTURED"
                or (diagnostic.get("retrieval") or {}).get("query") != case["question"]
                or (diagnostic.get("applicability") or {}).get("continuationOfMessageId") != source_id
                or not diagnostic.get("citationCatalog")):
            result["problems"].append("missing_fresh_retrieval_or_lineage")
            result["outcome"] = "CHECK_FAILED"
    return result


def rejected_request(client, initial, scope, expected_status, expected_code):
    """验证无效续答在新增消息前拒绝，不把意外调用成功悄悄忽略。"""
    path = f"/api/conversations/{initial['conversationId']}/messages"
    before = client.call("GET", path, role="customer")
    actual = {"status": 200, "code": "UNEXPECTED_SUCCESS"}
    try:
        client.call("POST", path, {"content": "补充范围", "mode": "KNOWLEDGE", "applicabilityScope": scope,
                                  "clarificationMessageId": initial["message"]["id"]}, "customer")
    except base.ApiError as error:
        actual = {"status": error.status, "code": error.code}
    after = client.call("GET", path, role="customer")
    return {"expectedStatus": expected_status, "expectedCode": expected_code, "actual": actual,
            "unchangedHistory": before == after,
            "passed": actual == {"status": expected_status, "code": expected_code} and before == after}


def main():
    """创建并启用专用配置，执行固定回归和双地区续答，最后恢复默认库。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--auth-file", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--runtime-label", required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise RuntimeError("output already exists; preserve previous results")
    now = datetime.now(base.CST)
    if not datetime(2026, 10, 1, tzinfo=base.CST) <= now < datetime(2027, 1, 1, tzinfo=base.CST):
        raise RuntimeError("fixture validity window is not current")
    base.load_credentials(base.ROOT / ".env")
    client = base.Client(args.base_url, json.loads(args.auth_file.read_text(encoding="utf-8")) if args.auth_file else None)
    client.login("admin")
    client.login("customer")
    original = base.snapshot(client)
    policy_path = base.FIXTURES / "clarification-policy.json"
    policy_input = json.loads(policy_path.read_text(encoding="utf-8"))
    dataset_path = base.ROOT / "docs/evaluation/trusted-knowledge-cases.json"
    cases = json.loads(dataset_path.read_text(encoding="utf-8"))["cases"]
    report = {"startedAt": now.isoformat(), "planned": len(cases) + 3, "results": [], "controls": [],
              "original": original, "baseUrl": args.base_url, "runtimeLabel": args.runtime_label,
              "policyInput": policy_input, "policySha256": hashlib.sha256(policy_path.read_bytes()).hexdigest(),
              "datasetSha256": hashlib.sha256(dataset_path.read_bytes()).hexdigest(),
              "gitCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=base.ROOT, text=True).strip(),
              "dirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=base.ROOT, text=True).strip()),
              "sourceSha256": base.fingerprint((base.ROOT / "src/main/java").rglob("*.java")),
              "scenarioSnapshots": {}}
    evaluation_id = None
    try:
        evaluation_id, documents = base.prepare_isolated(client, original, report, args.output)
        path = f"/api/admin/knowledge-bases/{evaluation_id}"
        policy = client.call("PUT", path + "/clarification-policy", policy_input)
        report["policy"] = policy
        report["restoration"] = "PENDING"
        base.save_report(args.output, report)
        client.call("POST", path + "/default")
        previous = None
        for case in cases:
            if case["scenario"] != previous:
                base.transition(client, evaluation_id, documents, case["scenario"])
                state = base.snapshot(client)
                base.validate_scenario(state, case["scenario"])
                report["scenarioSnapshots"][case["scenario"]] = state
                previous = case["scenario"]
            if base.snapshot(client) != state or client.call("GET", path + "/clarification-policy") != policy:
                raise RuntimeError("environment changed concurrently")
            result = base.execute_case(client, case, state)
            if case["id"] == "B08":
                check_clarification(result, policy)
            report["results"].append(result)
            base.save_report(args.output, report)
            print(case["id"], result["outcome"], flush=True)
            if case["id"] == "B08":
                if result["outcome"] == "CHECK_PASSED":
                    report["controls"].append(rejected_request(client, result, "CN_SZ", 400, "INVALID_KNOWLEDGE_CLARIFICATION"))
                    report["controls"].append(rejected_request(client, result, "ALL", 400, "VALIDATION_ERROR"))
                followup = continue_case(client, result, "CN_SH", state)
                report["results"].append(followup)
                print(followup["id"], followup["outcome"], flush=True)
                if followup["outcome"] in ("CHECK_PASSED", "CHECK_FAILED"):
                    report["controls"].append(rejected_request(client, result, "CN_SH", 409, "KNOWLEDGE_CLARIFICATION_STALE"))
                second = base.execute_case(client, {**case, "id": "CLARIFY_BJ"}, state)
                check_clarification(second, policy)
                report["results"].append(second)
                followup_bj = continue_case(client, second, "CN_BJ", state)
                report["results"].append(followup_bj)
                print("CLARIFY_BJ", second["outcome"], "/", followup_bj["outcome"], flush=True)
                base.save_report(args.output, report)
    except BaseException as error:
        report["runError"] = type(error).__name__
        raise
    finally:
        if report.get("restoration") == "PENDING":
            report["restoration"] = base.restore_default(client, original["knowledgeBase"]["id"], evaluation_id)
        report["finishedAt"] = datetime.now(base.CST).isoformat()
        report["finalState"] = base.snapshot(client)
        base.save_report(args.output, report)
    passed = (report.get("restoration") == "RESTORED" and len(report["results"]) == report["planned"]
              and all(row["outcome"] == "CHECK_PASSED" and row["diagnosticState"] == "CAPTURED" for row in report["results"])
              and len(report["controls"]) == 3 and all(row["passed"] for row in report["controls"]))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
