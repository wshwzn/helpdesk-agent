"""创建独立练习客户和稳定资料库；重复执行核验现状，不撤回资料或重置聊天。"""
import argparse
import hashlib
import json
import secrets
import time
import uuid
from datetime import datetime
from pathlib import Path

from evaluate_trusted_knowledge import Client, ApiError, ROOT, FIXTURES, DOCUMENTS, CST, load_credentials


def save(path, value):
    """以临时文件替换保存进度，降低初始化中断后状态文件损坏的概率。"""
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def metadata(item):
    """将公开资料清单转换为服务端业务元数据，不修改正文中的有效期。"""
    _, source, version, scope, start, end = item
    return dict(sourceKey=source, businessVersion=version, applicabilityScope=scope, validFrom=start, validUntil=end)


def verify_document(document, item, publication):
    """已有资料发生内容、范围、版本或发布状态变化时停下，不覆盖人工修改。"""
    expected = metadata(item)
    if document["sha256"] != hashlib.sha256((FIXTURES / item[0]).read_bytes()).hexdigest():
        raise RuntimeError("practice document content changed")
    for key, value in expected.items():
        actual = document.get(key)
        if key in ("validFrom", "validUntil"):
            actual = datetime.fromisoformat(actual) if actual else None
            value = datetime.fromisoformat(value) if value else None
        if actual != value:
            raise RuntimeError("practice document metadata changed: " + key)
    if document["status"] != "READY" or document["publicationStatus"] != publication:
        raise RuntimeError("practice document is no longer in its initialized state")


def prepare(client, output, activate=False):
    """创建并核验脚本拥有的库和账号，仅在全部准备成功后可切换默认库。"""
    output = output.resolve()
    if not output.is_relative_to(ROOT / "target"):
        raise ValueError("practice state and credentials must stay under ignored target")
    now = datetime.now(CST)
    if not datetime(2026, 10, 1, tzinfo=CST) <= now < datetime(2027, 1, 1, tzinfo=CST):
        raise RuntimeError("practice fixtures need a new version for the current date")
    output.mkdir(parents=True, exist_ok=True)
    state_path, credentials_path = output / "state.json", output / "customer-credentials.json"
    if state_path.exists():
        state = json.loads(state_path.read_text(encoding="utf-8"))
        if state.get("schemaVersion") != 1 or state["baseUrl"] != client.base:
            raise RuntimeError("practice state belongs to another environment")
    else:
        state = dict(schemaVersion=1, baseUrl=client.base, owner=uuid.uuid4().hex,
                     documents={}, initialized=False, createdAt=now.isoformat())
        save(state_path, state)
    if not credentials_path.exists():
        if state.get("customerUsername"):
            raise RuntimeError("existing practice credentials missing; do not recreate the account")
        save(credentials_path, dict(username="practice_" + state["owner"][:10], password=secrets.token_urlsafe(24)))
    credentials = json.loads(credentials_path.read_text(encoding="utf-8"))
    if state.get("customerUsername") and state["customerUsername"] != credentials["username"]:
        raise RuntimeError("practice credentials do not match local state")
    try:
        client.call("POST", "/api/auth/login", credentials, role=None)
    except ApiError as error:
        if error.status != 401 or state.get("customerUsername"):
            raise
        client.call("POST", "/api/auth/register", {**credentials, "displayName": "知识问答练习客户"}, role=None)
        client.call("POST", "/api/auth/login", credentials, role=None)
    state["customerUsername"] = credentials["username"]
    save(state_path, state)
    name = "口述练习资料库-" + state["owner"][:10]
    description = "prepare_knowledge_practice.py owner=" + state["owner"]
    if not state.get("knowledgeBaseId"):
        matches = [b for b in client.call("GET", "/api/admin/knowledge-bases")
                   if b["name"] == name and b["description"] == description]
        created = matches[0] if len(matches) == 1 else client.call("POST", "/api/admin/knowledge-bases", {"name": name, "description": description})
        state["knowledgeBaseId"] = created["id"]
        save(state_path, state)
    path = f"/api/admin/knowledge-bases/{state['knowledgeBaseId']}"
    base = client.call("GET", path)
    if base["name"] != name or base["description"] != description:
        raise RuntimeError("practice knowledge base identity changed")
    all_documents = client.call("GET", path + "/documents?size=100")
    known_ids = {value["id"] for value in state["documents"].values()}
    if all_documents["total"] != len(all_documents["records"]) or any(d["id"] not in known_ids for d in all_documents["records"]):
        raise RuntimeError("untracked documents found; inspect local state before continuing")
    for index, item in enumerate(DOCUMENTS):
        filename = item[0]
        publication = "PUBLISHED" if index < 5 else "DRAFT"
        if filename not in state["documents"]:
            uploaded = client.call("POST", path + "/documents", upload=FIXTURES / filename)
            state["documents"][filename] = {"id": uploaded["id"], "metadataSet": False, "complete": False}
            save(state_path, state)
        entry = state["documents"][filename]
        location = path + f"/documents/{entry['id']}"
        document = client.call("GET", location)
        if entry["complete"]:
            verify_document(document, item, publication)
            continue
        if not entry["metadataSet"]:
            document = client.call("PUT", location + "/metadata", {"version": document["version"], **metadata(item)})
            entry["metadataSet"] = True
            save(state_path, state)
        if document["status"] not in ("READY", "PROCESSING"):
            client.call("POST", location + "/index")
        deadline = time.monotonic() + 120
        while document["status"] != "READY":
            document = client.call("GET", location)
            if document["status"] == "FAILED" or time.monotonic() > deadline:
                raise RuntimeError("practice indexing failed: " + filename)
            if document["status"] != "READY":
                time.sleep(1)
        if publication == "PUBLISHED" and document["publicationStatus"] == "DRAFT":
            document = client.call("POST", location + "/publish", {"version": document["version"]})
        verify_document(document, item, publication)
        entry["complete"] = True
        save(state_path, state)
        print("READY " + filename, flush=True)
    policy = json.loads((FIXTURES / "clarification-policy.json").read_text(encoding="utf-8"))
    current = client.call("GET", path + "/clarification-policy")
    if not state["initialized"] and current["revision"] == 0:
        current = client.call("PUT", path + "/clarification-policy", policy)
    if current["topics"] != policy["topics"]:
        raise RuntimeError("practice clarification policy changed")
    state.update(initialized=True, policyRevision=current["revision"], knowledgeBaseName=name)
    save(state_path, state)
    if activate:
        bases = client.call("GET", "/api/admin/knowledge-bases")
        active = next((b for b in bases if b["isDefault"]), None)
        if active and active["id"] != state["knowledgeBaseId"]:
            state["previousDefaultId"] = active["id"]
            save(state_path, state)
        if not active or active["id"] != state["knowledgeBaseId"]:
            client.call("POST", path + "/default")
        if not client.call("GET", path)["isDefault"]:
            raise RuntimeError("default knowledge base switch not confirmed")
    return state


def main():
    """管理员认证仅用于初始化，客户密码写入忽略目录且从不打印。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--output", type=Path, default=ROOT / "target/knowledge-practice")
    parser.add_argument("--auth-file", type=Path)
    parser.add_argument("--activate", action="store_true")
    args = parser.parse_args()
    load_credentials(ROOT / ".env")
    client = Client(args.base_url, json.loads(args.auth_file.read_text(encoding="utf-8")) if args.auth_file else None)
    client.login("admin")
    state = prepare(client, args.output, args.activate)
    print(json.dumps({k: state[k] for k in ("knowledgeBaseId", "knowledgeBaseName", "customerUsername", "initialized")}, ensure_ascii=False))
    print("Local customer credentials: " + str(args.output.resolve() / "customer-credentials.json"))


if __name__ == "__main__":
    main()
