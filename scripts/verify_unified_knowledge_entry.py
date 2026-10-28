#!/usr/bin/env python3
"""复用已冻结的隔离资料验证统一入口，不重试生成，不改模型或资料，最终恢复默认库。"""
import argparse
from datetime import datetime, timedelta
import hashlib
import json
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request
import evaluate_trusted_knowledge as base
from evaluate_knowledge_scopes import check, save


def read_events(lines):
    """按SSE空行边界解析事件，保留顺序，不能把error冒充完成。"""
    events, data, name = [], [], None
    for raw in lines:
        line = raw.decode("utf-8").rstrip("\r\n")
        if not line:
            if data:
                event = json.loads("\n".join(data))
                if name != event.get("type"):
                    raise ValueError("SSE event type mismatch")
                events.append(event)
            data, name = [], None
        elif line.startswith("event:"):
            name = line[6:].strip()
        elif line.startswith("data:"):
            data.append(line[5:].lstrip())
    if data:
        raise ValueError("incomplete SSE event")
    return events


def stream(client, path, body):
    """通过真实SSE接口交付已核验结果，不调用模型厂商流式端点。"""
    request = urllib.request.Request(client.base + path + "/stream",
        data=json.dumps(body, ensure_ascii=False).encode("utf8"), method="POST",
        headers={"Authorization": "Bearer " + client.tokens["customer"],
                 "Content-Type": "application/json", "Accept": "text/event-stream, application/json"})
    try:
        with client.opener.open(request, timeout=client.timeout) as response:
            return read_events(response)
    except urllib.error.HTTPError as error:
        raise base.ApiError(error.code, "STREAM_HTTP_ERROR") from None
    except (urllib.error.URLError, TimeoutError):
        raise base.ApiError(0, "TRANSPORT_OR_TIMEOUT") from None


def matches_history(message, saved):
    """业务字段逐项相等；仅允许MySQL微秒精度造成的创建/更新时间舍入差异。"""
    if set(message) != set(saved):
        return False
    fields = set(message) | set(saved)
    for key in fields:
        if message.get(key) == saved.get(key):
            continue
        if key not in ("createdAt", "updatedAt") or not message.get(key) or not saved.get(key):
            return False
        try:
            if abs(datetime.fromisoformat(message[key]) - datetime.fromisoformat(saved[key])) > timedelta(microseconds=1):
                return False
        except ValueError:
            return False
    return True


def stream_answer(events):
    """兼容阶段通知，仍要求唯一完整正文与最终持久化消息一致。"""
    delivery = [event for event in events if event["type"] != "progress"]
    if [event["type"] for event in delivery] != ["start", "delta", "done"]:
        raise base.ApiError(200, "STREAM_NOT_COMPLETED")
    message = delivery[-1]["assistantMessage"]
    if delivery[1]["content"] != message["content"]:
        raise ValueError("SSE delta differs from persisted answer")
    return message


def run(client, identity, body, scopes, patterns, streaming=False, continuation=False):
    """一题只生成一次，澄清题只续答一次；读取历史与诊断核对同一消息。"""
    conversation = client.call("POST", "/api/conversations", {"title": "entry-eval-" + identity}, "customer")
    path = f"/api/conversations/{conversation['id']}/messages"
    row = {"id": identity, "request": body, "conversationId": conversation["id"], "streaming": streaming}
    started = time.monotonic()
    try:
        if streaming:
            events = stream(client, path, body)
            row["events"] = events
            message = stream_answer(events)
        else:
            exchange = client.call("POST", path, body, "customer")
            if exchange["pendingActions"]:
                raise ValueError("unexpected legacy action")
            message = exchange["assistantMessage"]
        if continuation:
            row["clarification"] = message
            if message["knowledgeAnswer"]["reason"] != "APPLICABILITY_REQUIRED":
                raise ValueError("expected clarification")
            row["clarificationDiagnostic"] = client.call("GET", "/api/admin/knowledge-answer-runs/" + message["knowledgeRunId"])
            followup = {"content": "选择上海和北京", "mode": "KNOWLEDGE", "applicabilityScopes": scopes,
                        "clarificationMessageId": message["id"]}
            row["continuationRequest"] = followup
            message = client.call("POST", path, followup, "customer")["assistantMessage"]
        row["message"] = message
        problems = check(message, scopes, "ANSWERABLE", patterns)
        if message["mode"] != "KNOWLEDGE" or message["status"] != "COMPLETED":
            problems.append("wrong_effective_mode_or_status")
        history = client.call("GET", path, role="customer")["records"]
        row["history"] = history
        if not matches_history(message, history[-1]) or any(m["mode"] != "KNOWLEDGE" for m in history):
            problems.append("history_contract_mismatch")
        diagnostic = client.call("GET", "/api/admin/knowledge-answer-runs/" + message["knowledgeRunId"])
        row["diagnostic"] = diagnostic
        if diagnostic["assistantMessageId"] != message["id"] or diagnostic["conversationId"] != conversation["id"] or diagnostic["status"] != "SUCCEEDED":
            problems.append("diagnostic_contract_mismatch")
        if set(diagnostic["applicabilityScopes"]) != set(scopes):
            problems.append("diagnostic_scope_mismatch")
        row.update(problems=problems, outcome="CHECK_FAILED" if problems else "CHECK_PASSED")
    except base.ApiError as error:
        row.update(outcome="TECHNICAL_ERROR", httpStatus=error.status, errorCode=error.code)
        history = client.call("GET", path, role="customer")["records"]
        if history:
            row["failedMessage"] = history[-1]
            if history[-1].get("knowledgeRunId"):
                row["diagnostic"] = client.call("GET", "/api/admin/knowledge-answer-runs/" + history[-1]["knowledgeRunId"])
    row["elapsedSeconds"] = round(time.monotonic() - started, 3)
    return row


def main():
    """只临时切换已验证的隔离库，保存运行摘要，并在退出时恢复原默认库。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--runtime-jar", type=Path, required=True)
    parser.add_argument("--fixture-run", type=Path, default=base.ROOT / "docs/evaluation/runs/2026-10-21-多范围修复后验证数据.json")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    base.load_credentials(base.ROOT / ".env")
    client = base.Client("http://localhost:8080")
    client.login("admin"); client.login("customer")
    original = base.snapshot(client)
    models = client.call("GET", "/api/admin/model-configs")
    selected = next(m for m in models if m["isDefault"] and m["modelType"] == "CHAT")
    if selected["name"] != "dp官方" or not selected["enabled"]:
        raise ValueError("unexpected default model; no switch performed")
    fixture = json.loads(args.fixture_run.read_text(encoding="utf8"))
    kb = fixture["evaluationKnowledgeBaseId"]
    path = f"/api/admin/knowledge-bases/{kb}"
    report = {"startedAt": datetime.now(base.CST).isoformat(), "planned": 4, "results": [],
        "originalKnowledgeBaseId": original["knowledgeBase"]["id"], "evaluationKnowledgeBaseId": kb,
        "textModel": {k: selected[k] for k in ("id", "name", "modelName", "updatedAt")},
        "gitCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
        "runtimeJarSha256": hashlib.sha256(args.runtime_jar.read_bytes()).hexdigest(),
        "sourceSha256": base.fingerprint((base.ROOT / "src/main/java").rglob("*.java")),
        "fixtureRunSha256": hashlib.sha256(args.fixture_run.read_bytes()).hexdigest(), "restoration": "PENDING"}
    result_path = args.output / "results.json"
    save(result_path, report)
    try:
        client.call("POST", path + "/default")
        state = base.snapshot(client)
        if state["documents"] != fixture["evaluationSnapshot"]["documents"] or state["models"] != fixture["models"]:
            raise ValueError("fixture documents or models changed")
        report["evaluationSnapshot"] = state
        cases = [
            ("U01", {"content": "普通员工在上海出差的住宿上限是多少？", "mode": "AGENT", "applicabilityScope": "CN_SH"}, ["CN_SH"], ["640"], False, False),
            ("U02", {"content": "比较所选地区普通员工的住宿上限。", "mode": "AGENT", "applicabilityScopes": ["CN_SH", "CN_BJ"]}, ["CN_SH", "CN_BJ"], ["640", "480"], True, False),
            ("U03", {"content": "比较两个地区普通员工的住宿上限。", "mode": "AGENT"}, ["CN_SH", "CN_BJ"], ["640", "480"], False, True),
            ("U04", {"content": "员工出差结束后多久内提交报销？", "mode": "KNOWLEDGE"}, ["ALL"], ["十二|12"], False, False)]
        for case in cases:
            if base.snapshot(client) != state:
                raise ValueError("evaluation state changed")
            row = run(client, *case)
            report["results"].append(row)
            save(result_path, report)
            print(row["id"], row["outcome"], flush=True)
        if base.snapshot(client) != state:
            raise ValueError("evaluation state changed during final question")
    finally:
        report["restoration"] = base.restore_default(client, original["knowledgeBase"]["id"], kb)
        report["finishedAt"] = datetime.now(base.CST).isoformat()
        save(result_path, report)
    return 0 if report["restoration"] == "RESTORED" and len(report["results"]) == 4 and all(
        r["outcome"] == "CHECK_PASSED" for r in report["results"]) else 1


if __name__ == "__main__":
    raise SystemExit(main())
