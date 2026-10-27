"""验证评测工具不会因状态、引用或场景错误而给出虚假的通过成绩。"""

import hashlib
import unittest
from datetime import datetime
from unittest.mock import Mock

import evaluate_trusted_knowledge as evaluation


class EvaluationTest(unittest.TestCase):
    """使用固定报告验证判分边界，模型语义不在单元测试里伪造为真实成绩。"""

    def setUp(self):
        """构造一份正常住宿回答和权威文档快照。"""
        self.case = {"id": "T01", "scenario": "baseline", "question": "住宿上限？", "scope": "CN_SH",
                     "expectedStatus": "ANSWERABLE", "requiredSources": ["travel-policy:v2:CN_SH"]}
        self.document = {"id": 10, "status": "READY", "publicationStatus": "PUBLISHED", "chunkCount": 1,
                         "sourceKey": "travel-policy", "businessVersion": "v2", "applicabilityScope": "CN_SH",
                         "validFrom": "2026-10-01T00:00:00", "validUntil": "2027-01-01T00:00:00"}
        self.state = {"knowledgeBase": {"id": 1}, "documents": [self.document]}
        self.quote = "上海普通员工住宿每晚600元。"
        self.message = {"status": "COMPLETED", "citations": [{"documentId": 10, "chunkSequence": 1}],
                        "knowledgeAnswer": {"knowledgeBaseId": 1, "applicabilityScope": "CN_SH",
                            "checkedAt": "2026-10-19T08:00:00", "reason": "REVIEWED",
                            "evidence": [{"id": "E1", "documentId": 10, "chunkSequence": 1,
                                          "content": self.quote, "metadata": self.document.copy()}],
                            "answer": {"status": "ANSWERABLE", "claims": [{"text": self.quote,
                                "references": [{"evidenceId": "E1", "quote": self.quote}]}], "conflicts": []}}}

    def test_valid_answer_requires_real_identity_and_quote(self):
        """真实引用通过后，伪造引文不能继续算为通过。"""
        problems, details = evaluation.check_answer(self.case, self.message, self.state)
        self.assertEqual([], problems)
        self.assertEqual(1, details["referenceCount"])
        self.message["knowledgeAnswer"]["answer"]["claims"][0]["references"][0]["quote"] = "每晚999元"
        problems, details = evaluation.check_answer(self.case, self.message, self.state)
        self.assertIn("invalid_reference", problems)
        self.assertEqual(1, details["invalidReferenceCount"])

    def test_draft_and_expired_document_are_ineligible(self):
        """模型引用存在的文档仍必须满足当时准入规则。"""
        self.document["publicationStatus"] = "DRAFT"
        self.assertIn("ineligible_evidence", evaluation.check_answer(self.case, self.message, self.state)[0])
        self.document["publicationStatus"] = "PUBLISHED"
        self.document["validUntil"] = "2026-10-19T08:00:00"
        self.assertFalse(evaluation.eligible(self.document, "CN_SH", datetime(2026, 10, 19, 8)))

    def test_missing_condition_status_not_counted_as_correct_refusal(self):
        """缺条件却直接拒答应单独标为状态失败。"""
        self.case["expectedStatus"] = "NEEDS_CLARIFICATION"
        self.message["knowledgeAnswer"]["answer"]["status"] = "INSUFFICIENT_EVIDENCE"
        self.assertIn("status_mismatch", evaluation.check_answer(self.case, self.message, self.state)[0])

    def test_no_match_exception_is_explicit(self):
        """只有题集明确允许无命中时才接受不调用模型的不足结果。"""
        self.case.update(expectedStatus="NEEDS_CLARIFICATION", allowNoMatch=True, requiredSources=[])
        self.message["knowledgeAnswer"].update(reason="NO_MATCH", evidence=[], answer={
            "status": "INSUFFICIENT_EVIDENCE", "claims": [], "conflicts": []})
        self.message["citations"] = []
        self.assertEqual([], evaluation.check_answer(self.case, self.message, self.state)[0])

    def test_citation_chunk_must_match(self):
        """相同文档的错误 Chunk 不能混入已用引用列表。"""
        self.message["citations"][0]["chunkSequence"] = 2
        self.assertIn("citation_list_mismatch", evaluation.check_answer(self.case, self.message, self.state)[0])

    def test_wrong_base_is_not_model_success(self):
        """默认库被切换后的回答不能混作当前题集成绩。"""
        self.message["knowledgeAnswer"]["knowledgeBaseId"] = 2
        self.assertIn("wrong_knowledge_base", evaluation.check_answer(self.case, self.message, self.state)[0])

    def test_technical_failure_stays_in_denominator(self):
        """技术失败不重试、不变成业务拒答，并保留可追踪错误码。"""
        class FailingClient:
            """模拟生成失败而历史接口仍可用。"""

            def call(self, method, path, body=None, role=None):
                """返回一个创建结果、一处失败和相应历史记录。"""
                if path == "/api/conversations":
                    return {"id": 100}
                if method == "POST":
                    raise evaluation.ApiError(502, "CHAT_GENERATION_FAILED")
                return {"records": [{"status": "FAILED", "errorCode": "KNOWLEDGE_CITATION_INVALID"}]}

        result = evaluation.execute_case(FailingClient(), self.case, self.state)
        self.assertEqual("TECHNICAL_ERROR", result["outcome"])
        metrics = evaluation.measures({"planned": 1, "results": [result]})
        self.assertEqual(1, metrics["executed"])
        self.assertEqual(1, metrics["technicalFailures"])
        self.assertEqual(0, metrics["automaticPassed"])

    def test_fixture_state_and_hash_are_verified(self):
        """不正确的冲突场景或改过正文的资料必须在跑题之前被拒绝。"""
        documents = []
        for index, (filename, source, version, scope, start, end) in enumerate(evaluation.DOCUMENTS):
            documents.append({"id": index, "status": "READY", "sourceKey": source, "businessVersion": version,
                              "applicabilityScope": scope, "validFrom": start, "validUntil": end,
                              "publicationStatus": "DRAFT" if index in (5, 6) else "PUBLISHED",
                              "sha256": hashlib.sha256((evaluation.FIXTURES / filename).read_bytes()).hexdigest()})
        state = {"documents": documents}
        evaluation.validate_scenario(state, "baseline")
        with self.assertRaisesRegex(RuntimeError, "publication status"):
            evaluation.validate_scenario(state, "conflict")
        documents[0]["sha256"] = "0" * 64
        with self.assertRaisesRegex(RuntimeError, "content differs"):
            evaluation.validate_scenario(state, "baseline")

    def test_unavailable_usage_is_not_zero_cost(self):
        """未知用量保持独立计数，不能把已知 Token 和当作整轮费用。"""
        metrics = evaluation.measures({"planned": 3, "results": [
            {"outcome": "CHECK_PASSED", "message": {"totalTokens": 10}},
            {"outcome": "CHECK_PASSED", "message": {"totalTokens": None}},
            {"outcome": "TECHNICAL_ERROR"}]})
        self.assertEqual(10, metrics["knownAnswerTokens"])
        self.assertEqual(1, metrics["completedAnswersMissingUsage"])
        self.assertEqual(1, metrics["failedCallUsageUnavailable"])

    def test_restores_only_own_default_selection(self):
        """异常恢复只改变本轮评测库的默认选择，且恢复后需读取验证。"""
        client = Mock()
        client.call.side_effect = [[{"id": 9, "isDefault": True}], {}, [{"id": 7, "isDefault": True}]]
        self.assertEqual("RESTORED", evaluation.restore_default(client, 7, 9))
        self.assertEqual(("POST", "/api/admin/knowledge-bases/7/default"), client.call.call_args_list[1].args)
        client.reset_mock(side_effect=True)
        client.call.return_value = [{"id": 88, "isDefault": True}]
        self.assertEqual("UNCHANGED_BY_RUNNER_CONCURRENT_CHANGE", evaluation.restore_default(client, 7, 9))
        client.call.assert_called_once_with("GET", "/api/admin/knowledge-bases")

    def test_failed_restoration_is_visible(self):
        """网络中断导致恢复失败时不能宣称已恢复。"""
        client = Mock()
        client.call.side_effect = evaluation.ApiError(0, "TRANSPORT_OR_TIMEOUT")
        self.assertEqual("FAILED_MANUAL_RESTORE_REQUIRED", evaluation.restore_default(client, 7, 9))

    def test_admin_diagnostic_does_not_change_business_outcome(self):
        """诊断失败不能覆盖问答判分，成功获取时核对消息身份并保存失败阶段。"""
        run_id = "d62f598e-8adc-4bdb-aa6a-0a8ebef708c3"
        result = {"outcome": "TECHNICAL_ERROR", "conversationId": 100, "question": "住宿？", "scope": "CN_SH",
                  "failedMessage": {"id": 5, "status": "FAILED", "knowledgeRunId": run_id}}
        client = Mock()
        client.call.side_effect = evaluation.ApiError(403, "FORBIDDEN")
        evaluation.collect_diagnostics(client, result)
        self.assertEqual("TECHNICAL_ERROR", result["outcome"])
        self.assertEqual("UNAVAILABLE", result["diagnosticState"])
        client.call.side_effect = None
        client.call.return_value = {"runId": run_id, "assistantMessageId": 5, "conversationId": 100,
                                   "question": "住宿？", "applicabilityScope": "CN_SH", "status": "FAILED",
                                   "stages": [{"stage": "DRAFT", "usage": {"totalTokens": 12}},
                                              {"stage": "REVIEW", "usage": {"totalTokens": 7}},
                                              {"stage": "REVISION", "usage": {"totalTokens": 9}},
                                              {"stage": "REVIEW", "usage": None}]}
        evaluation.collect_diagnostics(client, result)
        self.assertEqual("CAPTURED", result["diagnosticState"])
        metrics = evaluation.measures({"planned": 1, "results": [result]})
        self.assertEqual(28, metrics["diagnosticKnownAnswerTokens"])
        self.assertEqual(1, metrics["diagnosticModelStagesMissingUsage"])
        self.assertEqual(1, metrics["technicalFailures"])
        client.call.return_value["assistantMessageId"] = 6
        evaluation.collect_diagnostics(client, result)
        self.assertEqual("INVALID", result["diagnosticState"])

    def test_unfinished_trace_not_counted_as_complete_diagnostics(self):
        """消息已失败而快照仍运行中，不能把部分记录计作完整诊断。"""
        run_id = "d62f598e-8adc-4bdb-aa6a-0a8ebef708c3"
        result = {"outcome": "TECHNICAL_ERROR", "conversationId": 100, "question": "住宿？", "scope": "CN_SH",
                  "failedMessage": {"id": 5, "status": "FAILED", "knowledgeRunId": run_id}}
        client = Mock()
        client.call.return_value = {"runId": run_id, "assistantMessageId": 5, "conversationId": 100,
                                   "question": "住宿？", "applicabilityScope": "CN_SH", "status": "RUNNING", "stages": []}
        evaluation.collect_diagnostics(client, result)
        self.assertEqual("INCOMPLETE", result["diagnosticState"])
        self.assertEqual(0, evaluation.measures({"planned": 1, "results": [result]})["diagnosticCaptured"])


if __name__ == "__main__":
    unittest.main()
