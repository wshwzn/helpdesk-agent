"""验证基线统计与层次诊断，不让漏题、未知覆盖或技术故障变成成功。"""

import copy
import json
import unittest

import evaluate_knowledge_multihop as evaluation


class MultihopEvaluationTest(unittest.TestCase):
    """基线判分脚本的独立回归测试。"""

    def setUp(self):
        """加载冻结题集并构造有完整身份的假结果。"""
        self.dataset = json.loads(evaluation.DATASET.read_text(encoding="utf-8"))
        self.rows = [{"id": c["id"], "question": c["question"], "expectedStatus": c["expectedStatus"],
                      "outcome": "CHECK_PASSED", "coverageKnown": True, "retrievalCovered": True,
                      "failureLayer": "PASSED_CHECKS"} for c in self.dataset["cases"]]

    def test_frozen_dataset_has_consistent_gold_sources(self):
        """全部金标准来源必须存在并符合题目范围及发布条件。"""
        evaluation.validate_dataset(self.dataset)
        self.assertEqual(12, len(self.dataset["cases"]))
        self.assertEqual(18, len(self.dataset["documents"]))
        self.dataset["cases"][0]["requiredSources"] = ["nonexistent:v1:ALL"]
        with self.assertRaises(ValueError):
            evaluation.validate_dataset(self.dataset)

    def test_missing_results_remain_in_denominator(self):
        """未执行题不计通过，计划题数始终来自冻结题集。"""
        report = evaluation.summarize(self.dataset, self.rows[:-1])
        self.assertEqual(12, report["planned"])
        self.assertEqual(11, report["executed"])
        self.assertFalse(report["complete"])

    def test_duplicate_or_changed_labels_are_rejected(self):
        """重复结果及跑后修改的预期标签不能混入比较。"""
        with self.assertRaises(ValueError):
            evaluation.summarize(self.dataset, self.rows + [self.rows[0]])
        self.rows[0]["expectedStatus"] = "INSUFFICIENT_EVIDENCE"
        with self.assertRaises(ValueError):
            evaluation.summarize(self.dataset, self.rows)

    def test_negative_technical_failure_is_not_correct_refusal(self):
        """负例调用失败单列，不把没有输出当作有依据的拒答。"""
        row = next(row for row in self.rows if row["id"] == "N01")
        row.update(outcome="TECHNICAL_ERROR", coverageKnown=False, retrievalCovered=None, failureLayer="DIAGNOSTIC_UNAVAILABLE")
        summary = evaluation.summarize(self.dataset, self.rows)
        self.assertEqual(1, summary["byKind"]["missing_rule"]["technicalFailures"])
        self.assertEqual(0, summary["byKind"]["missing_rule"]["automaticPassed"])
        self.assertEqual(1, summary["coverageUnknown"])

    def test_missing_or_truncated_diagnostics_are_unknown(self):
        """诊断缺失或截断时不能推断来源未召回。"""
        case = self.dataset["cases"][0]
        for result in ({}, {"diagnosticState": "CAPTURED", "diagnostic": {"retrieval": {"results": []}, "retrievalTruncated": True}}):
            self.assertIsNone(evaluation.inspect_retrieval(case, result, {})["retrievalCovered"])

    def test_complete_retrieval_and_failed_review_are_separate(self):
        """资料已找齐但复核失败应标为回答或核验问题。"""
        case = self.dataset["cases"][0]
        state = {"documents": [{"id": 1, "sourceKey": "l2-sh", "businessVersion": "v1", "applicabilityScope": "CN_SH"}]}
        result = {"outcome": "TECHNICAL_ERROR", "diagnosticState": "CAPTURED", "diagnostic": {
            "retrieval": {"results": [{"documentId": 1}]}, "evidence": [{"documentId": 1}],
            "stages": [{"stage": "RETRIEVAL"}, {"stage": "REVIEW"}]}}
        inspected = evaluation.inspect_retrieval(case, result, state)
        self.assertTrue(inspected["retrievalCovered"])
        self.assertEqual("ANSWER_OR_VERIFICATION", inspected["failureLayer"])
        missing = copy.deepcopy(result)
        missing["diagnostic"]["retrieval"]["results"] = []
        self.assertEqual("RETRIEVAL_GAP", evaluation.inspect_retrieval(case, missing, state)["failureLayer"])


if __name__ == "__main__":
    unittest.main()
