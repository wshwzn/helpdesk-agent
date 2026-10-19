"""验证澄清评测不会把普通拒答、漏跑或技术失败当作闭环成功。"""
import copy
import unittest
from unittest.mock import Mock
import evaluate_knowledge_clarification as evaluation


class ClarificationEvaluationTest(unittest.TestCase):
    """只验证判分边界，不模拟模型准确率。"""

    def test_requires_structured_original_question(self):
        """看似追问的自然语言结果缺少可续答结构时应判失败。"""
        policy = {"revision": 1, "topics": [{"id": "travel-lodging", "scopeOptions": [{"value": "CN_SH", "label": "上海"}]}]}
        result = {"outcome": "CHECK_PASSED", "question": "住宿额度？", "problems": [], "message": {"knowledgeAnswer": {
            "reason": "APPLICABILITY_REQUIRED", "evidence": [], "answer": {"claims": []},
            "clarification": {"originalQuestion": "住宿额度？", "policyRevision": 1, "topicIds": ["travel-lodging"],
                              "scopeOptions": policy["topics"][0]["scopeOptions"]}}}}
        evaluation.check_clarification(result, policy)
        self.assertEqual("CHECK_PASSED", result["outcome"])
        invalid = copy.deepcopy(result)
        invalid["message"]["knowledgeAnswer"]["clarification"]["originalQuestion"] = "别的问题"
        evaluation.check_clarification(invalid, policy)
        self.assertEqual("CHECK_FAILED", invalid["outcome"])

    def test_missing_initial_is_not_a_successful_followup(self):
        """首轮失败导致无法续答时保留漏跑结果且不调用 API。"""
        client = Mock()
        result = evaluation.continue_case(client, {"question": "住宿？", "conversationId": 1,
                                                  "outcome": "TECHNICAL_ERROR"}, "CN_SH", {})
        self.assertEqual("NOT_RUN", result["outcome"])
        client.call.assert_not_called()

    def test_continuation_technical_failure_remains_failure(self):
        """复核拒绝不能被当作成功续答或正确拒答。"""
        client = Mock()
        client.call.side_effect = [evaluation.base.ApiError(502, "CHAT_GENERATION_FAILED"),
                                   {"records": [{"status": "FAILED", "errorCode": "KNOWLEDGE_REVIEW_REJECTED"}]}]
        result = evaluation.continue_case(client, {"question": "住宿？", "conversationId": 1,
                       "outcome": "CHECK_PASSED", "message": {"id": 2}}, "CN_SH", {})
        self.assertEqual("TECHNICAL_ERROR", result["outcome"])
        self.assertEqual(2, client.call.call_count)

    def test_route_unknown_usage_is_counted(self):
        """已尝试的路由缺用量必须计为未知，未调用的条件阶段不计模型费用。"""
        rows = []
        for attempted in (True, False):
            rows.append({"outcome": "TECHNICAL_ERROR", "elapsedSeconds": 1, "diagnosticState": "CAPTURED",
                         "diagnostic": {"applicability": {"routingAttempted": attempted},
                                        "stages": [{"stage": "APPLICABILITY", "usage": None}]}})
        self.assertEqual(1, evaluation.base.measures({"results": rows, "planned": 2})["diagnosticModelStagesMissingUsage"])


if __name__ == "__main__":
    unittest.main()
