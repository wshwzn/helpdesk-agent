"""验证多范围评测不会把状态、范围或引用错误计为通过。"""
import unittest
from evaluate_knowledge_scopes import check

class ScopeEvaluationTest(unittest.TestCase):
    def message(self):
        """构造有一条真实引用的部分回答。"""
        return {"knowledgeAnswer": {"applicabilityScopes": ["CN_SH", "CN_GZ"],
            "answer": {"status": "INSUFFICIENT_EVIDENCE", "claims": [{"text": "上海640元", "references": [{"evidenceId": "E1", "quote": "640元"}]}], "conflicts": []},
            "evidence": [{"id": "E1", "content": "上海640元", "metadata": {"applicabilityScope": "CN_SH"}}]}}
    def test_partial_answer_keeps_missing_scope(self):
        """有据部分和不足状态应能通过，不能强求未知金额。"""
        self.assertEqual([], check(self.message(), ["CN_SH", "CN_GZ"], "INSUFFICIENT_EVIDENCE", ["640"]))
    def test_wrong_complete_status_fails(self):
        """不得把部分回答当成完整比较。"""
        self.assertIn("wrong_status", check(self.message(), ["CN_SH", "CN_GZ"], "ANSWERABLE", ["640"]))
    def test_wrong_scope_fails(self):
        """不能忽略缺失的请求范围。"""
        self.assertIn("wrong_scopes", check(self.message(), ["CN_SH", "CN_BJ"], "INSUFFICIENT_EVIDENCE", []))
    def test_fabricated_quote_fails(self):
        """有数字但引文伪造仍必须失败。"""
        message = self.message()
        message["knowledgeAnswer"]["answer"]["claims"][0]["references"][0]["quote"] = "999元"
        self.assertIn("invalid_reference", check(message, ["CN_SH", "CN_GZ"], "INSUFFICIENT_EVIDENCE", ["640"]))

if __name__ == "__main__":
    unittest.main()
