#!/usr/bin/env python3
"""在独立知识库验证资料交接与问答；每题只运行一次，退出时恢复默认库。"""
import argparse
from datetime import datetime
import hashlib
import json
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request
import uuid
import evaluate_trusted_knowledge as base
from evaluate_knowledge_scopes import run_case, save
from verify_unified_knowledge_entry import matches_history, run as run_entry

FIXTURES = base.ROOT / "docs/demo/multi-scope"
REVISION = base.ROOT / "docs/demo/delivery/上海住宿新版.md"
QUESTION = "普通员工在上海出差，每晚住宿报销上限是多少？"
CASES = [
    ("E01", QUESTION, ["CN_SH"], False, "ANSWERABLE", ["640"], False),
    ("E02", QUESTION, ["CN_SH"], False, "ANSWERABLE", ["640"], False),
    ("E03", QUESTION, ["CN_SH"], False, "ANSWERABLE", ["760"], False),
    ("E04", "比较上海和北京普通员工每晚住宿报销上限。", ["CN_SH", "CN_BJ"], False, "ANSWERABLE", ["760", "480"], False),
    ("E05", "比较两个地区普通员工每晚住宿报销上限。", ["CN_SH", "CN_BJ"], True, "ANSWERABLE", ["760", "480"], True),
    ("E06", "比较上海和广州普通员工每晚住宿报销上限。", ["CN_SH", "CN_GZ"], False, "INSUFFICIENT_EVIDENCE", ["760"], False),
]


def revision(client, path, metadata):
    """上传固定候选及业务元数据，不自动重试写入或输出响应秘密。"""
    boundary = "delivery" + uuid.uuid4().hex
    data = (f'--{boundary}\r\nContent-Disposition: form-data; name="metadata"\r\n'
            'Content-Type: application/json\r\n\r\n').encode()
    data += json.dumps(metadata).encode() + b"\r\n"
    data += (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="sh-v2.md"\r\n'
             'Content-Type: text/markdown\r\n\r\n').encode()
    data += REVISION.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
    request = urllib.request.Request(client.base + path + "/revisions", data=data, method="POST",
        headers={"Authorization": "Bearer " + client.tokens["admin"],
                 "Content-Type": "multipart/form-data; boundary=" + boundary})
    try:
        with client.opener.open(request, timeout=client.timeout) as response:
            return json.loads(response.read())["data"]
    except urllib.error.HTTPError as error:
        raise base.ApiError(error.code, "REVISION_HTTP_ERROR") from None
    except (urllib.error.URLError, TimeoutError):
        raise base.ApiError(0, "TRANSPORT_OR_TIMEOUT") from None


def wait_ready(client, path):
    """只轮询任务结果，失败或超时即停止，不重新提交索引。"""
    deadline = time.monotonic() + 180
    while True:
        document = client.call("GET", path)
        if document["status"] == "READY":
            return document
        if document["status"] == "FAILED" or time.monotonic() >= deadline:
            raise ValueError("indexing_failed_or_timed_out")
        time.sleep(1)


def exact_document_ids(items, expected):
    """要求返回集合正好等于预期，空结果不能冒充成功的新旧版本隔离。"""
    return {item["documentId"] for item in items} == set(expected)


def main():
    """串起上传、索引、审核、问答、撤回和历史读取，原始失败随运行保存。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--runtime-jar", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    base.load_credentials(base.ROOT / ".env")
    client = base.Client("http://localhost:8080")
    client.login("admin")
    client.login("customer")
    original = base.snapshot(client)
    selected = next(m for m in client.call("GET", "/api/admin/model-configs")
                    if m["isDefault"] and m["modelType"] == "CHAT")
    if selected["name"] != "dp官方" or not selected["enabled"]:
        raise ValueError("unexpected_default_text_model")
    report = {"startedAt": datetime.now(base.CST).isoformat(), "plannedCases": CASES,
        "results": [], "lifecycle": [], "restoration": "NOT_NEEDED",
        "originalKnowledgeBaseId": original["knowledgeBase"]["id"],
        "textModel": {k: selected[k] for k in ("id", "name", "modelName", "updatedAt")},
        "models": original["models"],
        "gitCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
        "runtimeJarSha256": hashlib.sha256(args.runtime_jar.read_bytes()).hexdigest(),
        "sourceSha256": base.fingerprint((base.ROOT / "src/main/java").rglob("*.java")),
        "fixtureSha256": base.fingerprint([*FIXTURES.glob("*.md"), REVISION]),
        "runnerSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
    result_path = args.output / "results.json"
    save(result_path, report)
    kb = client.call("POST", "/api/admin/knowledge-bases", {"name": "交付回归-" + uuid.uuid4().hex[:8],
        "description": "E轮固定生命周期验证，虚构资料，保留失败"})
    report["evaluationKnowledgeBaseId"] = kb["id"]
    path = f"/api/admin/knowledge-bases/{kb['id']}"

    def record(name, passed, observed):
        """先持久化检查证据，再阻止基于失败前提继续执行后续修改。"""
        report["lifecycle"].append({"id": name, "passed": passed, "observed": observed})
        save(result_path, report)
        print(name, "PASS" if passed else "FAIL", flush=True)
        if not passed:
            raise ValueError(name)

    def search(name, expected):
        """使用实际混合检索核对当前能进入答案的文档版本集合。"""
        result = client.call("POST", path + "/search", {"query": QUESTION, "applicabilityScope": "CN_SH"})
        record(name, exact_document_ids(result["results"], expected), result)

    def upload(file, scope, source):
        """上传并提交固定元数据，等待索引后仍由调用方显式发布。"""
        doc = client.call("POST", path + "/documents", upload=FIXTURES / file)
        doc_path = path + f"/documents/{doc['id']}"
        if scope == "CN_SH":
            task = client.call("GET", doc_path + "/index-task")
            record("L01_pending_without_task", doc["status"] == "PENDING" and
                   doc["publicationStatus"] == "DRAFT" and task is None, {"document": doc, "task": task})
            search("L02_pending_invisible", [])
        client.call("POST", doc_path + "/submit", {"version": doc["version"], "sourceKey": source,
            "businessVersion": "v1", "applicabilityScope": scope,
            "validFrom": "2026-01-01T00:00:00", "validUntil": None})
        return wait_ready(client, doc_path)

    def ask(index, expected_ids=None):
        """每个固定问题仅问一次；检查失败仍保留并继续独立用例。"""
        state = base.snapshot(client)
        if state["knowledgeBase"]["id"] != kb["id"] or state["models"] != original["models"]:
            raise ValueError("concurrent_configuration_change")
        case = CASES[index]
        if index == 3:
            row = run_entry(client, case[0], {"content": case[1], "mode": "AGENT",
                "applicabilityScopes": case[2]}, case[2], case[5], streaming=True)
        else:
            row = run_case(client, case, kb["id"])
        message = row.get("message")
        if message:
            history = client.call("GET", f"/api/conversations/{row['conversationId']}/messages", role="customer")["records"]
            problems = row.setdefault("problems", [])
            if not matches_history(message, next(m for m in history if m["id"] == message["id"])):
                problems.append("history_contract_mismatch")
            if message.get("mode") != "KNOWLEDGE" or message.get("status") != "COMPLETED":
                problems.append("wrong_mode_or_message_status")
            answer = message.get("knowledgeAnswer") or {}
            if answer.get("knowledgeBaseId") != kb["id"]:
                problems.append("wrong_knowledge_base")
            if expected_ids is not None and not exact_document_ids(answer.get("evidence", []), expected_ids):
                problems.append("wrong_evidence_versions")
            if problems:
                row["outcome"] = "CHECK_FAILED"
        report["results"].append(row)
        save(result_path, report)
        print(case[0], row["outcome"], flush=True)
        if base.snapshot(client) != state:
            raise ValueError("state_changed_during_question")

    try:
        old = upload("01-上海住宿.md", "CN_SH", "delivery-sh")
        old_path = path + f"/documents/{old['id']}"
        record("L03_ready_still_draft", old["publicationStatus"] == "DRAFT", old)
        search("L04_ready_draft_invisible", [])
        old = client.call("POST", old_path + "/publish", {"version": old["version"]})
        search("L05_published_old_visible", [old["id"]])
        report["restoration"] = "PENDING"
        save(result_path, report)
        client.call("POST", path + "/default")
        ask(0, [old["id"]])
        metadata = {"predecessorVersion": old["version"], "businessVersion": "v2",
                    "validFrom": "2026-01-01T00:00:00", "validUntil": None}
        created = revision(client, old_path, metadata)
        report["revisionUpload"] = created
        candidate_path = path + f"/documents/{created['document']['id']}"
        candidate = wait_ready(client, candidate_path)
        record("L06_candidate_ready_draft", created["outcome"] == "CREATED" and
               candidate["publicationStatus"] == "DRAFT" and candidate["predecessorDocumentId"] == old["id"], candidate)
        duplicate = revision(client, old_path, metadata)
        record("L07_duplicate_reuses_candidate", duplicate["outcome"] == "EXISTING" and
               duplicate["document"]["id"] == candidate["id"], duplicate)
        search("L08_unapproved_candidate_invisible", [old["id"]])
        ask(1, [old["id"]])
        old = client.call("GET", old_path)
        candidate = client.call("GET", candidate_path)
        report["approval"] = client.call("POST", candidate_path + "/approve-replacement",
            {"version": candidate["version"], "predecessorVersion": old["version"]})
        search("L09_approved_new_only", [candidate["id"]])
        ask(2, [candidate["id"]])
        for file, scope in [("02-北京住宿.md", "CN_BJ"), ("03-通用报销期限.md", "ALL")]:
            doc = upload(file, scope, "delivery-" + scope.lower())
            client.call("POST", path + f"/documents/{doc['id']}/publish", {"version": doc["version"]})
        client.call("PUT", path + "/clarification-policy", {"revision": 0, "topics": [{"id": "lodging",
            "description": "员工出差住宿报销金额和地区比较", "scopeOptions": [
                {"value": "CN_SH", "label": "上海"}, {"value": "CN_BJ", "label": "北京"},
                {"value": "CN_GZ", "label": "广州"}]}]})
        for index in range(3, len(CASES)):
            ask(index)
        candidate = client.call("GET", candidate_path)
        report["withdrawn"] = client.call("POST", candidate_path + "/withdraw", {"version": candidate["version"]})
        search("L10_withdraw_does_not_revive_old", [doc["id"]])
        old_message = report["results"][0].get("message")
        history = client.call("GET", f"/api/conversations/{report['results'][0]['conversationId']}/messages", role="customer")["records"]
        record("L11_original_answer_preserved", old_message is not None and any(
            matches_history(old_message, m) for m in history), history)
        report["finalSnapshot"] = base.snapshot(client)
    except Exception as error:
        report["fatalError"] = {"type": type(error).__name__,
            "code": error.code if isinstance(error, base.ApiError) else "DELIVERY_ABORTED"}
        raise
    finally:
        if report["restoration"] == "PENDING":
            report["restoration"] = base.restore_default(client, original["knowledgeBase"]["id"], kb["id"])
        report["finishedAt"] = datetime.now(base.CST).isoformat()
        save(result_path, report)
    return 0 if report["restoration"] == "RESTORED" and len(report["results"]) == len(CASES) and all(
        row["outcome"] == "CHECK_PASSED" for row in report["results"]) else 1


if __name__ == "__main__":
    raise SystemExit(main())
