#!/usr/bin/env python3
"""在独立资料库验证多范围检索和澄清；保留失败，不重跑选优，不切换默认模型。"""

import argparse
from collections import Counter
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
import uuid
import evaluate_trusted_knowledge as base

FIXTURES = base.ROOT / "docs/demo/multi-scope"
CASES = [
    ("S01", "普通员工在上海出差每晚住宿报销上限是多少？", ["CN_SH"], False, "ANSWERABLE", ["640"], False),
    ("S02", "比较上海和北京普通员工每晚住宿报销上限。", ["CN_SH", "CN_BJ"], False, "ANSWERABLE", ["640", "480"], False),
    ("S03", "比较上海和北京普通员工每晚住宿报销上限。", ["CN_SH", "CN_BJ"], True, "ANSWERABLE", ["640", "480"], False),
    ("S04", "比较上海和广州普通员工每晚住宿报销上限。", ["CN_SH", "CN_GZ"], False, "INSUFFICIENT_EVIDENCE", ["640"], False),
    ("S05", "比较两个地区普通员工每晚住宿报销上限。", ["CN_SH", "CN_BJ"], True, "ANSWERABLE", ["640", "480"], True),
    ("S06", "上海和北京的员工分别应在出差结束后多久内提交报销？", ["CN_SH", "CN_BJ"], False, "ANSWERABLE", ["十二|12"], False),
]


def save(path, report):
    """每题后保存完整结果和已知用量，中断也不丢失先前失败。"""
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def check(message, scopes, status, patterns):
    """检查范围、业务状态和引用，语义质量仍须逐题阅读。"""
    report = message.get("knowledgeAnswer") or {}
    answer = report.get("answer") or {}
    errors = []
    if set(report.get("applicabilityScopes", [])) != set(scopes):
        errors.append("wrong_scopes")
    if answer.get("status") != status:
        errors.append("wrong_status")
    evidence = {e["id"]: e for e in report.get("evidence", [])}
    claims = list(answer.get("claims", []))
    for conflict in answer.get("conflicts", []):
        claims.extend([conflict["first"], conflict["second"]])
    for item in evidence.values():
        if item["metadata"]["applicabilityScope"] not in [*scopes, "ALL"]:
            errors.append("foreign_evidence")
    for claim in claims:
        if not claim.get("references"):
            errors.append("uncited_claim")
        for ref in claim.get("references", []):
            source = evidence.get(ref["evidenceId"])
            if not source or not ref.get("quote") or ref["quote"] not in source["content"]:
                errors.append("invalid_reference")
    text = "\n".join(c["text"] for c in claims)
    for pattern in patterns:
        if not re.search(pattern, text):
            errors.append("missing_key_point:" + pattern)
    return errors


def run_case(client, case, base_id):
    """每题单独建会话，澄清题只续答一次，失败获取诊断但不再次生成。"""
    identity, question, scopes, infer, status, patterns, continuation = case
    row = {"id": identity, "question": question, "scopes": scopes, "expectedStatus": status}
    started = time.monotonic()
    conversation = client.call("POST", "/api/conversations", {"title": "scope-eval-" + identity}, "customer")
    row["conversationId"] = conversation["id"]
    path = f"/api/conversations/{conversation['id']}/messages"
    try:
        body = {"content": question}
        if not infer:
            if len(scopes) == 1:
                body["applicabilityScope"] = scopes[0]
            else:
                body["applicabilityScopes"] = scopes
        message = client.call("POST", path, body, "customer")["assistantMessage"]
        if continuation:
            row["clarification"] = message
            if (message.get("knowledgeAnswer") or {}).get("reason") != "APPLICABILITY_REQUIRED":
                raise ValueError("expected clarification before continuation")
            message = client.call("POST", path, {"content": "比较上海和北京", "applicabilityScopes": scopes,
                "clarificationMessageId": message["id"]}, "customer")["assistantMessage"]
        row["message"] = message
        row["problems"] = check(message, scopes, status, patterns)
        if message["knowledgeAnswer"]["knowledgeBaseId"] != base_id:
            row["problems"].append("wrong_knowledge_base")
        row["outcome"] = "CHECK_FAILED" if row["problems"] else "CHECK_PASSED"
    except base.ApiError as error:
        row.update(outcome="TECHNICAL_ERROR", httpStatus=error.status, errorCode=error.code)
        history = client.call("GET", path, role="customer")["records"]
        if history:
            row["failedMessage"] = history[-1]
    row["elapsedSeconds"] = round(time.monotonic() - started, 3)
    message = row.get("message") or row.get("failedMessage") or {}
    if message.get("knowledgeRunId"):
        diagnostic = client.call("GET", "/api/admin/knowledge-answer-runs/" + message["knowledgeRunId"])
        row["diagnostic"] = diagnostic
        if diagnostic["assistantMessageId"] != message["id"] or diagnostic["conversationId"] != conversation["id"]:
            raise ValueError("diagnostic identity mismatch")
        if row.get("outcome") == "CHECK_PASSED":
            if set(diagnostic.get("applicabilityScopes", [])) != set(scopes):
                row["problems"].append("wrong_diagnostic_scopes")
            if not infer and (diagnostic.get("applicability") or {}).get("routingAttempted"):
                row["problems"].append("unexpected_routing_for_explicit_scopes")
            if row["problems"]:
                row["outcome"] = "CHECK_FAILED"
        searches = (diagnostic.get("retrievalRun") or {}).get("searches", [])
        stages = diagnostic["stages"]
        row["searches"] = len(searches)
        row["modelStages"] = dict(Counter(s["stage"] for s in stages if s["stage"] in ("PLANNING", "DRAFT", "REVISION", "REVIEW") or (s["stage"] == "APPLICABILITY" and (diagnostic.get("applicability") or {}).get("routingAttempted"))))
        row["missingUsage"] = sum(1 for s in stages if (s["stage"] in ("PLANNING", "DRAFT", "REVISION", "REVIEW") or
            (s["stage"] == "APPLICABILITY" and (diagnostic.get("applicability") or {}).get("routingAttempted"))) and
            (s.get("usage") or {}).get("totalTokens") is None)
        row["knownTokens"] = sum((s.get("usage") or {}).get("totalTokens") or 0 for s in stages)
        if len(searches) > 5:
            raise ValueError("search budget exceeded")
    if row.get("clarification"):
        row["clarificationDiagnostic"] = client.call("GET", "/api/admin/knowledge-answer-runs/" + row["clarification"]["knowledgeRunId"])
    return row


def main():
    """导入三份虚构资料，验证当前默认模型，退出前恢复原默认知识库。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--runtime-jar", type=Path, required=True)
    parser.add_argument("--expected-model-name", default="dp官方")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    base.load_credentials(base.ROOT / ".env")
    client = base.Client("http://localhost:8080")
    client.login("admin")
    client.login("customer")
    original = base.snapshot(client)
    models = client.call("GET", "/api/admin/model-configs")
    selected = next(m for m in models if m["isDefault"] and m["modelType"] == "CHAT")
    if selected["name"] != args.expected_model_name or not selected["enabled"]:
        raise ValueError("unexpected default text model; no model switch performed")
    report = {"startedAt": datetime.now(base.CST).isoformat(), "planned": len(CASES), "results": [],
        "originalKnowledgeBaseId": original["knowledgeBase"]["id"], "restoration": "NOT_NEEDED",
        "textModel": {k: selected[k] for k in ("id", "name", "modelName", "updatedAt")},
        "gitCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
        "runtimeJarSha256": hashlib.sha256(args.runtime_jar.read_bytes()).hexdigest(),
        "sourceSha256": base.fingerprint((base.ROOT / "src/main/java").rglob("*.java")),
        "fixtureSha256": base.fingerprint(FIXTURES.glob("*.md")), "models": original["models"]}
    result_path = args.output / "results.json"
    save(result_path, report)
    kb = client.call("POST", "/api/admin/knowledge-bases", {"name": "多范围评测-" + uuid.uuid4().hex[:8], "description": "阶段42隔离验证"})
    report["evaluationKnowledgeBaseId"] = kb["id"]
    path = f"/api/admin/knowledge-bases/{kb['id']}"
    try:
        for file, scope in [("01-上海住宿.md", "CN_SH"), ("02-北京住宿.md", "CN_BJ"), ("03-通用报销期限.md", "ALL")]:
            doc = client.call("POST", path + "/documents", upload=FIXTURES / file)
            doc_path = path + f"/documents/{doc['id']}"
            client.call("POST", doc_path + "/submit", {"version": doc["version"], "sourceKey": "scope-" + scope.lower(),
                "businessVersion": "v1", "applicabilityScope": scope, "validFrom": "2026-01-01T00:00:00", "validUntil": None})
            deadline = time.monotonic() + 180
            while True:
                doc = client.call("GET", doc_path)
                if doc["status"] == "READY":
                    break
                if doc["status"] == "FAILED" or time.monotonic() > deadline:
                    raise ValueError("fixture indexing failed")
                time.sleep(1)
            client.call("POST", doc_path + "/publish", {"version": doc["version"]})
        client.call("PUT", path + "/clarification-policy", {"revision": 0, "topics": [{"id": "lodging",
            "description": "员工出差住宿报销金额和地区比较", "scopeOptions": [{"value": "CN_SH", "label": "上海"},
            {"value": "CN_BJ", "label": "北京"}, {"value": "CN_GZ", "label": "广州"}]}]})
        report["restoration"] = "PENDING"
        save(result_path, report)
        client.call("POST", path + "/default")
        state = base.snapshot(client)
        report["evaluationSnapshot"] = state
        for case in CASES:
            if base.snapshot(client) != state or state["models"] != original["models"]:
                raise ValueError("evaluation documents or models changed")
            row = run_case(client, case, kb["id"])
            report["results"].append(row)
            save(result_path, report)
            print(row["id"], row["outcome"], "searches=", row.get("searches"), "tokens=", row.get("knownTokens"), flush=True)
        if base.snapshot(client) != state:
            raise ValueError("evaluation state changed during final question")
    finally:
        if report["restoration"] == "PENDING":
            report["restoration"] = base.restore_default(client, original["knowledgeBase"]["id"], kb["id"])
        report["finishedAt"] = datetime.now(base.CST).isoformat()
        save(result_path, report)
    return 0 if report["restoration"] == "RESTORED" and len(report["results"]) == len(CASES) and all(
        r["outcome"] == "CHECK_PASSED" for r in report["results"]) else 1


if __name__ == "__main__":
    raise SystemExit(main())
