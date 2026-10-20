#!/usr/bin/env python3
"""真实 HTTP + OSS 来源同步验收，创建独立资料，不修改默认知识库。"""

import argparse
import json
import os
from pathlib import Path
import subprocess
import time
import uuid

from evaluate_trusted_knowledge import Client, ROOT, load_credentials


def load_oss_environment():
    """只在内存加载 OSS 必需字段，不记录原始配置或凭据。"""
    allowed = {"OSS_ENDPOINT", "OSS_BUCKET", "OSS_ACCESS_KEY_ID", "OSS_ACCESS_KEY_SECRET"}
    for line in (ROOT / ".env").read_text(encoding="utf-8-sig").splitlines():
        key, separator, value = line.partition("=")
        if separator and key.strip() in allowed:
            os.environ.setdefault(key.strip(), value.strip().strip("\"'"))
    if any(not os.getenv(key) for key in allowed):
        raise RuntimeError("missing OSS environment fields")


def fixture(operation, key, version):
    """调用项目已依赖的 OSS Java SDK，避免额外安装云存储 Python 库。"""
    classpath = (ROOT / "target/stage35-classpath.txt").read_text(encoding="utf-8-sig").strip()
    args_path = ROOT / "target/stage35-fixture.args"
    args_path.write_text('--class-path\n"' + classpath.replace("\\", "/") + '"\n"'
                         + (ROOT / "scripts/OssSourceFixture.java").as_posix() + '"\n'
                         + operation + '\n' + key + '\n' + version + '\n', encoding="utf-8")
    result = subprocess.run(["java", "@" + str(args_path)], cwd=ROOT, capture_output=True, timeout=90)
    if result.returncode:
        raise RuntimeError("OSS fixture operation failed; no credentials or vendor response recorded")


def main():
    """按验收顺序验证导入、幂等、交接和缺失，并输出仅含非敏感结果的报告。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--output", type=Path, default=ROOT / "target/oss-source-smoke.json")
    args = parser.parse_args()
    load_credentials(ROOT / ".env")
    load_oss_environment()
    client = Client(args.base_url, timeout=120)
    client.login("admin")
    suffix = uuid.uuid4().hex[:12]
    prefix = "external-knowledge/smoke-" + suffix + "/"
    key = prefix + "policy.md"
    report = {"run": suffix, "checks": [], "complete": False}
    base_id = None
    source_id = None

    def save():
        """每步持久化验收进度，意外中断时保留实际结果。"""
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    def check(name, passed):
        """断言业务结果，失败立即停止，不重跑挑选成功结果。"""
        report["checks"].append({"name": name, "passed": bool(passed)})
        save()
        if not passed:
            raise RuntimeError("smoke assertion failed: " + name)

    def scan():
        """调度与手动扫描共用 API，测试环境关闭自动调度以获得确定计数。"""
        return client.call("POST", source_path + "/scan")

    def document(document_id):
        """读取实际文档状态及乐观锁版本。"""
        return client.call("GET", base_path + "/documents/" + str(document_id))

    def current_document():
        """通过来源映射定位实际导入文档，避免把外部对象键当文档版本。"""
        items = client.call("GET", source_path + "/items")["records"]
        check("单个来源对象映射", len(items) == 1 and items[0]["lastDocumentId"] is not None)
        return document(items[0]["lastDocumentId"])

    def ready(document_id):
        """轮询已持久化任务至成功，不自动重试失败索引。"""
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            value = document(document_id)
            if value["status"] == "READY":
                return value
            if value["status"] == "FAILED":
                raise RuntimeError("index failed: " + str(value.get("errorCode")))
            time.sleep(2)
        raise RuntimeError("index wait timed out")

    def search_ids():
        """查询真实 Embedding 和 ES，检查当前检索结果的文档身份。"""
        result = client.call("POST", base_path + "/search", {"query": "普通员工上海住宿报销上限", "applicabilityScope": "CN_SH"})
        return {item["documentId"] for item in result["results"]}

    try:
        base = client.call("POST", "/api/admin/knowledge-bases", {"name": "OSS同步联调-" + suffix, "description": "独立来源同步验收"})
        base_id = base["id"]
        report["knowledgeBaseId"] = base_id
        base_path = "/api/admin/knowledge-bases/" + str(base_id)
        source = client.call("POST", base_path + "/sources", {"objectPrefix": prefix})
        source_id = source["id"]
        report["sourceId"] = source_id
        source_path = base_path + "/sources/" + str(source_id)
        save()
        fixture("put", key, "v1")
        result = scan()
        check("首次扫描导入一个草稿", result["scanStatus"] == "SUCCEEDED" and result["addedCount"] == 1)
        old = current_document()
        report["oldDocumentId"] = old["id"]
        check("重复扫描不重复入队", scan()["unchangedCount"] == 1 and current_document()["id"] == old["id"])
        old = ready(old["id"])
        task = client.call("GET", base_path + "/documents/" + str(old["id"]) + "/index-task")
        check("真实索引完成", task["status"] == "SUCCEEDED" and old["chunkCount"] > 0)
        client.call("POST", base_path + "/documents/" + str(old["id"]) + "/publish", {"version": old["version"]})
        check("真实检索命中旧版", search_ids() == {old["id"]})
        fixture("put", key, "v2")
        result = scan()
        check("外部修改生成新版", result["updatedCount"] == 1 and result["failedCount"] == 0)
        candidate = current_document()
        report["candidateDocumentId"] = candidate["id"]
        check("新版显式关联旧版", candidate["predecessorDocumentId"] == old["id"])
        candidate = ready(candidate["id"])
        check("审核前只检索旧版", search_ids() == {old["id"]})
        client.call("POST", base_path + "/documents/" + str(candidate["id"]) + "/approve-replacement",
                    {"version": candidate["version"], "predecessorVersion": document(old["id"])["version"]})
        check("审核后只检索新版", search_ids() == {candidate["id"]})
        fixture("delete", key, "v2")
        check("来源删除标记缺失", scan()["missingCount"] == 1)
        check("缺失不会自动撤回", search_ids() == {candidate["id"]})
        fixture("put", key, "v2")
        check("来源重现不重复索引", scan()["unchangedCount"] == 1 and current_document()["id"] == candidate["id"])
        report["complete"] = True
    except Exception as error:
        report["failure"] = type(error).__name__ + ": " + str(error)
        raise
    finally:
        if source_id is not None:
            try:
                client.call("PUT", source_path + "/enabled", {"enabled": False})
                report["sourcePaused"] = True
            except Exception:
                report["sourcePaused"] = False
        save()
        print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
