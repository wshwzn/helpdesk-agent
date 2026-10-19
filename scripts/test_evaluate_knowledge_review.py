"""验证专项评测不把技术失败、漏题或混入预期标签的问题隐藏在高分中。"""
import copy
import json
import unittest
from pathlib import Path

import evaluate_knowledge_review as evaluation


class ReviewEvaluationTest(unittest.TestCase):
    """用固定判定构造混淆矩阵，实际模型得分另行归档。"""

    def setUp(self):
        """构造两种策略均正确的两轮结果。"""
        self.dataset = json.loads((Path(__file__).resolve().parents[1] / "docs/evaluation/knowledge-review-cases.json").read_text(encoding="utf-8"))
        self.rows = [{"id": c["id"], "pairId": c["pairId"], "split": c["split"], "expectedApproved": c["expectedApproved"],
                      "repeat": repeat, "strategy": strategy, "durationMs": 100, "review": {"approved": c["expectedApproved"]},
                      "errorCode": None, "usage": {"totalTokens": 10}}
                     for c in self.dataset["cases"] for repeat in (1, 2) for strategy in ("BASELINE", "CHECKLIST")]

    def test_technical_failure_is_not_correct_rejection(self):
        """负例调用失败单独计数，不充当正确否决或零成本。"""
        row = next(r for r in self.rows if not r["expectedApproved"])
        row.update(review=None, errorCode="KNOWLEDGE_PROTOCOL_INVALID", usage=None)
        result = evaluation.metrics([row], 1)
        self.assertEqual(0, result["correctDecisions"])
        self.assertEqual(1, result["negativeTechnicalFailures"])
        self.assertEqual(1, result["callsMissingUsage"])

    def test_duplicate_or_label_mismatch_is_rejected(self):
        """禁止重复结果或与题集不一致的预期标签进入汇总。"""
        with self.assertRaises(ValueError):
            evaluation.summarize(self.dataset, self.rows + [self.rows[0]], 2)
        self.rows[0]["expectedApproved"] = not self.rows[0]["expectedApproved"]
        with self.assertRaises(ValueError):
            evaluation.summarize(self.dataset, self.rows, 2)

    def test_missing_calls_and_no_improvement_cannot_pass_gate(self):
        """漏题或与基线持平不能被写成达到预先定义的改进门槛。"""
        self.assertFalse(evaluation.summarize(self.dataset, self.rows, 2)["candidateEligibleForEndToEnd"])
        result = evaluation.summarize(self.dataset, self.rows[:-1], 2)
        self.assertFalse(result["complete"])
        self.assertFalse(result["candidateEligibleForEndToEnd"])

    def test_gate_requires_both_rounds_and_no_challenge_regression(self):
        """降低误杀不足以抵消漏检或挑战集退化。"""
        for row in self.rows:
            if row["strategy"] == "BASELINE" and row["id"] == "R01P":
                row["review"]["approved"] = False
        self.assertTrue(evaluation.summarize(self.dataset, self.rows, 2)["candidateEligibleForEndToEnd"])
        next(r for r in self.rows if r["strategy"] == "CHECKLIST" and r["id"] == "R12N")["review"]["approved"] = True
        self.assertFalse(evaluation.summarize(self.dataset, self.rows, 2)["candidateEligibleForEndToEnd"])

    def test_contextual_candidate_is_compared_without_mixing_checklist(self):
        """候选切换后仍只比较两种策略，旧 CHECKLIST 结果不能混入新门槛。"""
        rows = copy.deepcopy(self.rows)
        for row in rows:
            if row["strategy"] == "CHECKLIST":
                row["strategy"] = "CONTEXTUAL"
            if row["strategy"] == "BASELINE" and row["id"] == "R01P":
                row["review"]["approved"] = False
        summary = evaluation.summarize(self.dataset, rows, 2, "CONTEXTUAL")
        self.assertEqual({"BASELINE", "CONTEXTUAL"}, set(summary["strategies"]))
        self.assertTrue(summary["candidateEligibleForEndToEnd"])
        with self.assertRaises(ValueError):
            evaluation.summarize(self.dataset, self.rows, 2, "CONTEXTUAL")

    def test_flips_require_two_valid_decisions(self):
        """重复样本判断翻转单列，协议失败不当作布尔否决。"""
        first = copy.deepcopy(self.rows[0])
        second = copy.deepcopy(first)
        second["review"]["approved"] = not first["review"]["approved"]
        self.assertEqual(1, evaluation.metrics([first, second], 2)["casesWithDecisionFlip"])
        second.update(review=None, errorCode="CALL_FAILED")
        self.assertEqual(0, evaluation.metrics([first, second], 2)["casesWithRepeatedValidDecisions"])


if __name__ == "__main__":
    unittest.main()
