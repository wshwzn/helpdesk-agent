#!/usr/bin/env python3
"""通过真实 HTTP 接口评测可信问答；凭据仅从环境变量或忽略的本地令牌文件读取。"""

import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import statistics
import subprocess
import time
from datetime import datetime, timezone, timedelta
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "docs/demo/trusted-knowledge"
CST = timezone(timedelta(hours=8))
MODEL_FIELDS = ("id", "modelType", "modelName", "enabled", "embeddingDimensions", "updatedAt")
DOCUMENTS = [
    ("01-通用报销流程.md", "expense-process", "v1", "ALL", "2026-01-01T00:00:00", None),
    ("02-上海住宿旧版.md", "travel-policy", "v1", "CN_SH", "2026-01-01T00:00:00", "2026-10-01T00:00:00"),
    ("03-上海住宿新版.md", "travel-policy", "v2", "CN_SH", "2026-10-01T00:00:00", "2027-01-01T00:00:00"),
    ("04-上海住宿未来版.md", "travel-policy", "v3", "CN_SH", "2027-01-01T00:00:00", None),
    ("05-北京住宿标准.md", "travel-policy", "v1", "CN_BJ", "2026-01-01T00:00:00", None),
    ("06-上海住宿未审核草稿.md", "travel-draft", "v1", "CN_SH", "2026-01-01T00:00:00", None),
    ("conflict/07-上海住宿冲突通知.md", "travel-notice", "v1", "CN_SH", "2026-10-01T00:00:00", "2027-01-01T00:00:00"),
]


class ApiError(RuntimeError):
    """只保留 HTTP 状态和脱敏业务码，避免报告记录认证信息或厂商响应。"""

    def __init__(self, status, code):
        """保存可用于错误归类的非敏感字段。"""
        super().__init__(f"HTTP {status}: {code}")
        self.status, self.code = status, code


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """带身份令牌的评测请求不自动跳转到其他地址。"""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        """拒绝重定向，防止 Authorization 被转发。"""
        return None


class Client:
    """封装不自动重试的 HTTP 请求，超时不能悄悄变成额外计费调用。"""

    def __init__(self, base, tokens=None, timeout=180):
        """设置服务地址和仅保存在内存中的令牌。"""
        self.base, self.tokens, self.timeout = base.rstrip("/"), tokens or {}, timeout
        self.opener = urllib.request.build_opener(NoRedirect())

    def call(self, method, path, body=None, role="admin", upload=None):
        """发送 JSON 或单文件上传，返回业务数据并屏蔽原始错误正文。"""
        headers = {}
        if role in self.tokens:
            headers["Authorization"] = "Bearer " + self.tokens[role]
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
        if upload:
            boundary = "eval" + uuid.uuid4().hex
            data = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; '
                    f'filename="{upload.name}"\r\nContent-Type: text/markdown\r\n\r\n').encode()
            data += upload.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
            headers["Content-Type"] = "multipart/form-data; boundary=" + boundary
        request = urllib.request.Request(self.base + path, data=data, headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=self.timeout) as response:
                raw = response.read()
                return json.loads(raw).get("data") if raw else None
        except urllib.error.HTTPError as error:
            try:
                code = json.loads(error.read()).get("code", "HTTP_ERROR")
            except (ValueError, AttributeError):
                code = "HTTP_ERROR"
            raise ApiError(error.code, code) from None
        except (urllib.error.URLError, TimeoutError):
            raise ApiError(0, "TRANSPORT_OR_TIMEOUT") from None

    def login(self, role):
        """通过正常登录获取身份，不生成或伪造本地 JWT。"""
        if role in self.tokens:
            return
        prefix = "HELPDESK_" + role.upper()
        username, password = os.getenv(prefix + "_USERNAME"), os.getenv(prefix + "_PASSWORD")
        if not username or not password:
            raise RuntimeError(f"missing {prefix}_USERNAME / {prefix}_PASSWORD")
        self.tokens[role] = self.call("POST", "/api/auth/login",
                                     {"username": username, "password": password}, role=None)["accessToken"]


def source_key(document):
    """以稳定业务身份匹配题集，不把本地数据库自增 ID 写死。"""
    return ":".join(str(document.get(key, "")) for key in ("sourceKey", "businessVersion", "applicabilityScope"))


def load_credentials(path):
    """只读取本地配置中的评测登录字段，不读取或输出模型密钥和其他配置。"""
    if not path.exists():
        return
    allowed = {"HELPDESK_" + role + "_" + field for role in ("ADMIN", "CUSTOMER") for field in ("USERNAME", "PASSWORD")}
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        key, separator, value = line.partition("=")
        if separator and key.strip() in allowed:
            value = value.strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
                value = value[1:-1]
            os.environ.setdefault(key.strip(), value)


def fingerprint(paths):
    """对实际源码或固定资料取摘要，避免把未提交运行冒充某个 Git 提交版本。"""
    digest = hashlib.sha256()
    for path in sorted(paths):
        digest.update(str(path.relative_to(ROOT)).replace("\\", "/").encode())
        digest.update(path.read_bytes())
    return digest.hexdigest()


def validate_scenario(state, scenario):
    """先验证真实资料身份、内容摘要和发布状态，错误环境不进入题目成绩。"""
    documents = {source_key(item): item for item in state["documents"]}
    fixture_keys = {":".join(item[1:4]) for item in DOCUMENTS}
    if set(documents) - fixture_keys or len(documents) != len(state["documents"]):
        raise RuntimeError("knowledge base contains non-fixture or duplicate documents")
    for index, (filename, source, version, scope, start, end) in enumerate(DOCUMENTS):
        key = ":".join((source, version, scope))
        item = documents.get(key)
        if index == 6 and scenario == "baseline" and not item:
            continue
        if not item or item["status"] != "READY":
            raise RuntimeError("missing READY fixture: " + key)
        if item["sha256"] != hashlib.sha256((FIXTURES / filename).read_bytes()).hexdigest():
            raise RuntimeError("fixture content differs from repository: " + key)
        if datetime.fromisoformat(item["validFrom"]) != datetime.fromisoformat(start) or (
                (datetime.fromisoformat(item["validUntil"]) if item["validUntil"] else None)
                != (datetime.fromisoformat(end) if end else None)):
            raise RuntimeError("fixture business validity differs: " + key)
        expected = "DRAFT" if index in (5, 6) else "PUBLISHED"
        if index == 6:
            expected = {"baseline": "DRAFT", "conflict": "PUBLISHED", "restored": "WITHDRAWN", "withdrawn": "WITHDRAWN"}[scenario]
        if index == 2 and scenario == "withdrawn":
            expected = "WITHDRAWN"
        if item["publicationStatus"] != expected:
            raise RuntimeError("unexpected fixture publication status: " + key)


def measures(report):
    """使用已执行题数作明确分母，技术失败单列且不能计为正确拒答。"""
    results = report["results"]
    outcomes = Counter(item["outcome"] for item in results)
    available = [item for item in results if item.get("message")]
    known_usage = [item["message"]["totalTokens"] for item in available if item["message"].get("totalTokens") is not None]
    diagnostics = [item["diagnostic"] for item in results if item.get("diagnosticState") == "CAPTURED"]
    model_stages = [stage for item in diagnostics for stage in item["stages"] if stage["stage"] in ("DRAFT", "REVIEW")]
    diagnostic_usage = [(stage.get("usage") or {}).get("totalTokens") for stage in model_stages]
    return {"planned": report["planned"], "executed": len(results),
            "automaticPassed": outcomes["CHECK_PASSED"], "technicalFailures": outcomes["TECHNICAL_ERROR"],
            "checkFailures": outcomes["CHECK_FAILED"], "completedWithReport": len(available),
            "statusMatchesIncludingTechnicalFailures": sum(item.get("statusMatched", False) for item in results),
            "knownAnswerTokens": sum(known_usage), "completedAnswersMissingUsage": len(available) - len(known_usage),
            "failedCallUsageUnavailable": outcomes["TECHNICAL_ERROR"],
            "referenceCount": sum(item.get("referenceCount", 0) for item in results),
            "invalidReferenceCount": sum(item.get("invalidReferenceCount", 0) for item in results),
            "diagnosticCaptured": len(diagnostics),
            "diagnosticMissing": len(results) - len(diagnostics),
            "diagnosticKnownAnswerTokens": sum(value for value in diagnostic_usage if value is not None),
            "diagnosticModelStagesMissingUsage": sum(value is None for value in diagnostic_usage)}


def snapshot(client):
    """保存默认库、检索参数、默认模型名称及文档元数据，不保存密钥字段。"""
    bases = client.call("GET", "/api/admin/knowledge-bases")
    default = next((base for base in bases if base["isDefault"]), None)
    if not default:
        raise RuntimeError("a default knowledge base is required for restoration")
    models = client.call("GET", "/api/admin/model-configs")
    docs, page = [], 1
    while True:
        result = client.call("GET", f"/api/admin/knowledge-bases/{default['id']}/documents?size=100&page={page}")
        docs.extend(result["records"])
        if page >= result["pages"]:
            break
        page += 1
    return {"knowledgeBase": default, "documents": docs,
            "models": [{key: model.get(key) for key in MODEL_FIELDS} for model in models if model["isDefault"]]}


def eligible(document, scope, checked_at):
    """独立按报告核验时刻检查发布、就绪、范围和半开有效期。"""
    return (document["status"] == "READY" and document["publicationStatus"] == "PUBLISHED"
            and document["applicabilityScope"] in ("ALL", scope)
            and document["validFrom"] is not None
            and datetime.fromisoformat(document["validFrom"]) <= checked_at
            and (not document["validUntil"] or checked_at < datetime.fromisoformat(document["validUntil"])))


def check_answer(case, message, state):
    """检查状态和引用结构；文本关键点只能辅助筛查，不能替代语义人工核验。"""
    report = message.get("knowledgeAnswer")
    if message.get("status") != "COMPLETED" or not report:
        return ["missing_completed_report"], {}
    problems, referenced = [], set()
    answer, evidence = report["answer"], report["evidence"]
    expected = case["expectedStatus"]
    if report["reason"] == "NO_MATCH" and case.get("allowNoMatch"):
        expected = "INSUFFICIENT_EVIDENCE"
    if answer["status"] != expected:
        problems.append("status_mismatch")
    if report["knowledgeBaseId"] != state["knowledgeBase"]["id"]:
        problems.append("wrong_knowledge_base")
    if report["applicabilityScope"] != case["scope"]:
        problems.append("wrong_scope")
    by_id = {item["id"]: item for item in evidence}
    documents = {item["id"]: item for item in state["documents"]}
    if len(by_id) != len(evidence):
        problems.append("duplicate_evidence_id")
    checked_at = datetime.fromisoformat(report["checkedAt"])
    for item in evidence:
        document = documents.get(item["documentId"])
        if not document or not eligible(document, case["scope"], checked_at):
            problems.append("ineligible_evidence")
        elif source_key(item["metadata"]) != source_key(document):
            problems.append("wrong_evidence_metadata")
        elif not 1 <= item["chunkSequence"] <= document["chunkCount"]:
            problems.append("invalid_chunk_sequence")
    claims = list(answer["claims"])
    for conflict in answer["conflicts"]:
        claims.extend([conflict["first"], conflict["second"]])
    reference_count, invalid_references = 0, 0
    for claim in claims:
        if not claim["references"]:
            problems.append("uncited_claim")
        for reference in claim["references"]:
            reference_count += 1
            item = by_id.get(reference["evidenceId"])
            if not item or not reference.get("quote") or reference["quote"] not in item["content"]:
                problems.append("invalid_reference")
                invalid_references += 1
            else:
                referenced.add(source_key(item["metadata"]))
    if not set(case.get("requiredSources", [])).issubset(referenced):
        problems.append("missing_expected_source")
    text = "\n".join(claim["text"] for claim in claims)
    for pattern in case.get("requiredPatterns", []):
        if not re.search(pattern, text):
            problems.append("missing_key_point:" + pattern)
    cited_ids = {(item["documentId"], item["chunkSequence"]) for item in evidence if any(
        ref["evidenceId"] == item["id"] for claim in claims for ref in claim["references"])}
    if cited_ids != {(item["documentId"], item["chunkSequence"]) for item in message.get("citations", [])}:
        problems.append("citation_list_mismatch")
    return sorted(set(problems)), {"actualStatus": answer["status"], "reason": report["reason"],
                                  "referencedSources": sorted(referenced), "claimCount": len(claims),
                                  "statusMatched": answer["status"] == expected,
                                  "referenceCount": reference_count, "invalidReferenceCount": invalid_references,
                                  "candidateSources": sorted({source_key(item["metadata"]) for item in evidence})}


def execute_case(client, case, state):
    """每题创建独立会话，只执行一次生成，失败也完整进入分母。"""
    result = {"id": case["id"], "scenario": case["scenario"], "question": case["question"],
              "expectedStatus": case["expectedStatus"], "scope": case["scope"], "semanticReview": "PENDING"}
    start = time.monotonic()
    try:
        conversation = client.call("POST", "/api/conversations", {"title": "eval-" + case["id"]}, "customer")
        result["conversationId"] = conversation["id"]
        exchange = client.call("POST", f"/api/conversations/{conversation['id']}/messages",
                               {"content": case["question"], "mode": "KNOWLEDGE", "applicabilityScope": case["scope"]}, "customer")
        message = exchange["assistantMessage"]
        result["problems"], details = check_answer(case, message, state)
        result.update(details)
        result["outcome"] = "CHECK_FAILED" if result["problems"] else "CHECK_PASSED"
        result["message"] = message
    except ApiError as error:
        result.update(outcome="TECHNICAL_ERROR", httpStatus=error.status, errorCode=error.code)
        if result.get("conversationId"):
            try:
                history = client.call("GET", f"/api/conversations/{result['conversationId']}/messages", role="customer")
                result["failedMessage"] = history["records"][-1]
            except ApiError:
                result["historyUnavailable"] = True
    result["elapsedSeconds"] = round(time.monotonic() - start, 3)
    collect_diagnostics(client, result)
    return result


def collect_diagnostics(client, result):
    """管理员按消息的运行编号读取诊断；获取失败单独记录，不覆盖实际问答成绩。"""
    message = result.get("message") or result.get("failedMessage") or {}
    run_id = message.get("knowledgeRunId")
    if not run_id:
        result["diagnosticState"] = "NO_RUN_ID"
        return
    result["knowledgeRunId"] = run_id
    try:
        if str(uuid.UUID(run_id)) != run_id:
            raise ValueError("noncanonical run ID")
        diagnostic = client.call("GET", "/api/admin/knowledge-answer-runs/" + run_id)
        if (diagnostic["runId"] != run_id or diagnostic["assistantMessageId"] != message["id"]
                or diagnostic["conversationId"] != result["conversationId"]
                or diagnostic["question"] != result["question"] or diagnostic["applicabilityScope"] != result["scope"]):
            raise ValueError("diagnostic identity mismatch")
        expected = {"COMPLETED": "SUCCEEDED", "FAILED": "CANCELLED" if message.get("errorCode") == "STREAM_CANCELLED" else "FAILED"}
        if message.get("status") in expected and diagnostic["status"] != expected[message["status"]]:
            result["diagnosticState"] = "INCOMPLETE"
        else:
            result["diagnosticState"] = "CAPTURED"
        result["diagnostic"] = diagnostic
    except ApiError as error:
        result.update(diagnosticState="UNAVAILABLE", diagnosticError=str(error))
    except (ValueError, KeyError, TypeError):
        result["diagnosticState"] = "INVALID"


def save_report(path, report):
    """逐题保存 JSON 和可读报告，中断时保留已完成结果，不把漏跑题当作成功。"""
    path.mkdir(parents=True, exist_ok=True)
    report["metrics"] = measures(report)
    (path / "results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    results = report["results"]
    passed = sum(item["outcome"] == "CHECK_PASSED" for item in results)
    failures = sum(item["outcome"] == "TECHNICAL_ERROR" for item in results)
    lines = ["# 可信问答真实运行报告", "", f"开始时间：{report['startedAt']}",
             f"源码版本：`{report['gitCommit']}`（dirty={report['dirty']}）；题集摘要：`{report['datasetSha256']}`", "",
             f"计划 {report['planned']} 题，已执行 {len(results)} 题，自动检查通过 {passed} 题，技术失败 {failures} 题。",
             "自动检查通过不等于语义正确；真实正确拒答率和错误确定回答率须结合人工标注计算。", "",
             "| 用例 | 场景 | 预期 | 实际 | 自动结果 | 秒 |",
             "| --- | --- | --- | --- | --- | --- |"]
    for item in results:
        lines.append(f"| {item['id']} | {item['scenario']} | {item['expectedStatus']} | "
                     f"{item.get('actualStatus', '—')} | {item['outcome']} | {item['elapsedSeconds']} |")
    for item in results:
        lines.extend(["", "## " + item["id"], "", item["question"], "",
                      "自动问题：" + ", ".join(item.get("problems", [])), "",
                      "错误码：" + str(item.get("failedMessage", {}).get("errorCode", item.get("errorCode", "无"))), "",
                      "运行编号：" + str(item.get("knowledgeRunId", "旧服务或未获得")) +
                      "；诊断状态：" + str(item.get("diagnosticState", "未收集")), "",
                      item.get("message", {}).get("content") or "（未生成正文）", "", "人工语义核验：待标注"])
    if results:
        lines.extend(["", f"平均端到端耗时（含失败）：{statistics.mean(r['elapsedSeconds'] for r in results):.3f} 秒。"])
    lines.extend(["", "默认库恢复状态：" + str(report.get("restoration", "NOT_NEEDED")), ""])
    lines.extend(["运行是否完成：" + str(len(results) == report["planned"] and not report.get("runError")), "",
                  "消息口径用量：仅含成功消息的回答 Token，不含失败调用及 Embedding；诊断口径另列如下。", ""])
    lines.extend([f"诊断获取：{report['metrics']['diagnosticCaptured']}/{len(results)}；"
                  f"诊断中的已知回答 Token：{report['metrics']['diagnosticKnownAnswerTokens']}；"
                  f"缺用量的模型阶段：{report['metrics']['diagnosticModelStagesMissingUsage']}。",
                  "诊断 Token 与成功消息 Token 有重叠，不能相加；仍不含 Embedding 和未知厂商用量。", ""])
    (path / "report.md").write_text("\n".join(lines), encoding="utf-8")


def prepare_isolated(client, original, report, output):
    """创建专用虚构资料库，不修改用户已导入文档的发布状态。"""
    created = client.call("POST", "/api/admin/knowledge-bases",
                          {"name": "可信评测-" + uuid.uuid4().hex[:10], "description": "脚本创建的虚构制度评测库"})
    base = f"/api/admin/knowledge-bases/{created['id']}"
    report["evaluationKnowledgeBaseId"] = created["id"]
    save_report(output, report)
    settings = original["knowledgeBase"]
    client.call("PUT", base + "/retrieval-settings", {"candidateLimit": settings["retrievalCandidateLimit"],
                "vectorMinSimilarity": settings["vectorMinSimilarity"], "resultLimit": settings["retrievalResultLimit"]})
    uploaded = {}
    for index, (filename, source, version, scope, start, end) in enumerate(DOCUMENTS):
        document = client.call("POST", base + "/documents", upload=FIXTURES / filename)
        location = base + f"/documents/{document['id']}"
        document = client.call("PUT", location + "/metadata", {"version": document["version"],
                               "sourceKey": source, "businessVersion": version, "applicabilityScope": scope,
                               "validFrom": start, "validUntil": end})
        client.call("POST", location + "/index")
        deadline = time.monotonic() + 120
        while True:
            document = client.call("GET", location)
            if document["status"] == "READY":
                break
            if document["status"] == "FAILED" or time.monotonic() > deadline:
                raise RuntimeError("fixture indexing failed: " + filename)
            time.sleep(1)
        if index < 5:
            document = client.call("POST", location + "/publish", {"version": document["version"]})
        uploaded[source_key(document)] = document
        print("INDEXED " + filename, flush=True)
    return created["id"], uploaded


def transition(client, base_id, documents, scenario):
    """只在独立评测库内发布或撤回指定资料，形成可复现的场景顺序。"""
    operation = {"conflict": ("travel-notice:v1:CN_SH", "publish"),
                 "restored": ("travel-notice:v1:CN_SH", "withdraw"),
                 "withdrawn": ("travel-policy:v2:CN_SH", "withdraw")}.get(scenario)
    if operation:
        key, action = operation
        document = documents[key]
        documents[key] = client.call("POST", f"/api/admin/knowledge-bases/{base_id}/documents/{document['id']}/{action}",
                                     {"version": document["version"]})


def restore_default(client, original_id, evaluation_id):
    """只恢复本次仍持有的默认库选择，其他操作者已切换时保持其选择并明确报告。"""
    try:
        active = next((base for base in client.call("GET", "/api/admin/knowledge-bases") if base["isDefault"]), None)
        if active and active["id"] == original_id:
            return "RESTORED"
        if not active or active["id"] != evaluation_id:
            return "UNCHANGED_BY_RUNNER_CONCURRENT_CHANGE"
        client.call("POST", f"/api/admin/knowledge-bases/{original_id}/default")
        active = next((base for base in client.call("GET", "/api/admin/knowledge-bases") if base["isDefault"]), None)
        return "RESTORED" if active and active["id"] == original_id else "FAILED_MANUAL_RESTORE_REQUIRED"
    except ApiError:
        return "FAILED_MANUAL_RESTORE_REQUIRED"


def main():
    """校验环境后顺序跑题，并在异常退出时尽力恢复原默认知识库。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--auth-file", type=Path, help="仅用于本地的忽略文件，含 admin/customer JWT")
    parser.add_argument("--env-file", type=Path, default=ROOT / ".env", help="读取四个 HELPDESK_* 登录变量")
    parser.add_argument("--runtime-label", default="unverified-running-service", help="人工记录实际被测服务版本；源码摘要不证明运行版本")
    parser.add_argument("--dataset", type=Path, default=ROOT / "docs/evaluation/trusted-knowledge-cases.json")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--isolated", action="store_true", help="新建评测库，临时切换默认库并在结束时恢复")
    parser.add_argument("--require-diagnostics", action="store_true", help="诊断缺失、身份不符或未终结时也返回非零退出码")
    parser.add_argument("--scenario", choices=["baseline", "conflict", "restored", "withdrawn"], default="conflict")
    args = parser.parse_args()
    load_credentials(args.env_file)
    if (args.output / "results.json").exists():
        raise RuntimeError("output already exists; preserve previous results and choose a new directory")
    now = datetime.now(CST)
    if not datetime(2026, 10, 1, tzinfo=CST) <= now < datetime(2027, 1, 1, tzinfo=CST):
        raise RuntimeError("fixture validity window is not current; update the dataset and fixtures together")
    tokens = json.loads(args.auth_file.read_text(encoding="utf-8")) if args.auth_file else None
    client = Client(args.base_url, tokens)
    client.login("admin")
    client.login("customer")
    original = snapshot(client)
    if {item["modelType"] for item in original["models"] if item["enabled"]} != {"CHAT", "EMBEDDING"}:
        raise RuntimeError("enabled default CHAT and EMBEDDING configurations are required")
    dataset = json.loads(args.dataset.read_text(encoding="utf-8"))
    cases = dataset["cases"] if args.isolated else [case for case in dataset["cases"] if case["scenario"] == args.scenario]
    if not cases or len({case["id"] for case in cases}) != len(cases):
        raise RuntimeError("case list must be nonempty and contain unique IDs")
    scenario_order = list(dict.fromkeys(case["scenario"] for case in cases))
    if args.isolated and scenario_order != ["baseline", "conflict", "restored", "withdrawn"]:
        raise RuntimeError("isolated cases must follow baseline/conflict/restored/withdrawn order")
    if args.isolated and [case["scenario"] for case in cases] != sorted(
            (case["scenario"] for case in cases), key=scenario_order.index):
        raise RuntimeError("isolated scenarios cannot be interleaved")
    report = {"startedAt": now.isoformat(), "planned": len(cases), "results": [], "original": original,
              "baseUrl": args.base_url, "datasetSha256": hashlib.sha256(args.dataset.read_bytes()).hexdigest(),
              "gitCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
              "dirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip()),
              "runtimeLabel": args.runtime_label,
              "sourceSha256": fingerprint((ROOT / "src/main/java").rglob("*.java")),
              "fixtureSha256": fingerprint([FIXTURES / item[0] for item in DOCUMENTS]),
              "scenarioSnapshots": {}}
    save_report(args.output, report)
    evaluation_id = None
    try:
        if args.isolated:
            evaluation_id, documents = prepare_isolated(client, original, report, args.output)
            report["restoration"] = "PENDING"
            save_report(args.output, report)
            client.call("POST", f"/api/admin/knowledge-bases/{evaluation_id}/default")
        previous = None
        for case in cases:
            if case["scenario"] != previous:
                if args.isolated:
                    transition(client, evaluation_id, documents, case["scenario"])
                state = snapshot(client)
                validate_scenario(state, case["scenario"])
                report["scenarioSnapshots"][case["scenario"]] = state
                previous = case["scenario"]
            # 默认库属于全局配置；发现其他操作者切换时停止，避免把跑错资料当作成绩。
            if snapshot(client) != state:
                raise RuntimeError("knowledge base, fixture metadata or model configuration changed concurrently")
            result = execute_case(client, case, state)
            report["results"].append(result)
            save_report(args.output, report)
            print(f"{case['id']} {result['outcome']} {result['elapsedSeconds']}s", flush=True)
    except BaseException as error:
        report["runError"] = type(error).__name__
        raise
    finally:
        if report.get("restoration") == "PENDING":
            report["restoration"] = restore_default(client, original["knowledgeBase"]["id"], evaluation_id)
        report["finishedAt"] = datetime.now(CST).isoformat()
        save_report(args.output, report)
    restored = report.get("restoration", "NOT_NEEDED") in ("NOT_NEEDED", "RESTORED")
    diagnostics_ok = not args.require_diagnostics or all(item.get("diagnosticState") == "CAPTURED" for item in report["results"])
    return 0 if restored and diagnostics_ok and all(item["outcome"] == "CHECK_PASSED" for item in report["results"]) else 1


if __name__ == "__main__":
    raise SystemExit(main())
