"""检查练习环境重复执行与变更保护，不真实创建账号或调用模型。"""
import copy
import hashlib
import json
import tempfile
import unittest
from datetime import datetime
from pathlib import Path
from unittest.mock import Mock, patch
import prepare_knowledge_practice as practice


class FixedDateTime(datetime):
    """固定样本有效期内的测试时钟。"""

    @classmethod
    def now(cls, tz=None):
        """不因运行测试的实际日期改变业务样本预期。"""
        return cls(2026, 10, 19, 12, tzinfo=tz)


class PracticePreparationTest(unittest.TestCase):
    """模拟已经初始化完成的环境，重点验证不会盲目覆盖状态。"""

    def setUp(self):
        """只在忽略目录构建临时状态，所有账号数据都是测试替身。"""
        (practice.ROOT / "target").mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=practice.ROOT / "target")
        self.addCleanup(self.temp.cleanup)
        self.output = Path(self.temp.name)
        self.client = Mock(base="http://test")
        self.state = {"schemaVersion": 1, "baseUrl": "http://test", "owner": "sample-owner",
                      "documents": {}, "initialized": True, "knowledgeBaseId": 100, "customerUsername": "practice_test"}
        self.documents = []
        for index, item in enumerate(practice.DOCUMENTS):
            document = dict(id=index + 1, **practice.metadata(item), status="READY",
                            sha256=hashlib.sha256((practice.FIXTURES / item[0]).read_bytes()).hexdigest(),
                            publicationStatus="PUBLISHED" if index < 5 else "DRAFT")
            self.documents.append(document)
            self.state["documents"][item[0]] = {"id": index + 1, "complete": True, "metadataSet": True}
        practice.save(self.output / "state.json", self.state)
        practice.save(self.output / "customer-credentials.json", {"username": "practice_test", "password": "unit-test-value"})
        self.client.call.side_effect = self.call

    def call(self, method, path, body=None, role=None):
        """已就绪环境只允许登录和读取，任何重建、撤回或修改都使测试失败。"""
        if path == "/api/auth/login":
            return {"accessToken": "unit-test"}
        if method != "GET":
            raise AssertionError("unexpected mutation: " + method + " " + path)
        if path.endswith("/clarification-policy"):
            policy = json.loads((practice.FIXTURES / "clarification-policy.json").read_text(encoding="utf-8"))
            return {**policy, "revision": 1}
        if "/documents?" in path:
            return {"total": len(self.documents), "records": self.documents}
        if "/documents/" in path:
            return self.documents[int(path.rsplit("/", 1)[1]) - 1]
        base = {"id": 100, "name": "口述练习资料库-sample-own", "description": "prepare_knowledge_practice.py owner=sample-owner", "isDefault": True}
        return [base] if path == "/api/admin/knowledge-bases" else base

    @patch.object(practice, "datetime", FixedDateTime)
    def test_repeated_setup_has_no_business_mutation(self):
        """重复准备不创建客户、不重索引、不更新配置、不切换已正确默认库。"""
        state = practice.prepare(self.client, self.output, activate=True)
        self.assertTrue(state["initialized"])
        self.assertEqual(100, state["knowledgeBaseId"])
        self.assertTrue(all(call.args[0] == "GET" or call.args[1] == "/api/auth/login"
                            for call in self.client.call.call_args_list))

    @patch.object(practice, "datetime", FixedDateTime)
    def test_withdrawn_document_is_not_silently_republished(self):
        """被人工撤回的资料必须报错，不为凑齐演示强制改状态。"""
        self.documents[2]["publicationStatus"] = "WITHDRAWN"
        with self.assertRaisesRegex(RuntimeError, "initialized state"):
            practice.prepare(self.client, self.output)

    @patch.object(practice, "datetime", FixedDateTime)
    def test_unknown_documents_stop_initialization(self):
        """发现其他维护者添加的文件后拒绝继续写入。"""
        self.documents.append({"id": 999})
        with self.assertRaisesRegex(RuntimeError, "untracked documents"):
            practice.prepare(self.client, self.output)

    def test_credentials_cannot_be_written_outside_target(self):
        """凭据输出路径必须留在项目忽略目录内。"""
        with self.assertRaises(ValueError):
            practice.prepare(self.client, practice.ROOT / "docs/practice")
        self.client.call.assert_not_called()


if __name__ == "__main__":
    unittest.main()
