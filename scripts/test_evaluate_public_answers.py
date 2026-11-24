"""验证问答评测的关键分母，避免拒答和评审缺失使质量指标虚高。"""

import unittest
from evaluate_public_answers import metrics


class AnswerMetricsTest(unittest.TestCase):
    """覆盖完整交付、错误分母和逐结论评分。"""

    def test_failure_and_missing_remain_in_denominator(self):
        """技术失败、未执行和无答案问题分别统计，不算正确拒答。"""
        cases = [{"id": "a", "answerable": True}, {"id": "b", "answerable": True}, {"id": "c", "answerable": False}]
        rows = [{"id": "a", "full": None}, {"id": "c", "full": None}]
        result = metrics(cases, rows, "full")
        self.assertEqual(result["correctDeliveryRate"], 0)
        self.assertEqual(result["correctAbstentionRate"], 0)
        self.assertEqual(result["unexecuted"], 1)
        self.assertIsNone(result["citationSupportRate"])

    def test_empty_refusal_has_no_perfect_citation_score(self):
        """正常拒答可以得拒答分，但无结论时引用支持率不可定义为100%。"""
        case = {"id": "a", "answerable": False}
        row = {"id": "a", "full": {"status": "INSUFFICIENT_EVIDENCE", "claims": [], "conflicts": []},
               "fullJudge": {"assessment": {"correctComplete": False, "appropriateAbstention": True, "claims": []}}}
        result = metrics([case], [row], "full")
        self.assertEqual(result["correctAbstentionRate"], 1)
        self.assertIsNone(result["citationSupportRate"])

    def test_claim_weighting_and_missing_judgment(self):
        """支持率按结论统计，遗漏引用影响覆盖率，缺失评审不能悄悄从支持率分母排除。"""
        case = {"id": "a", "answerable": True}
        row = {"id": "a", "full": {"status": "ANSWERABLE", "conflicts": [],
               "claims": [{"references": ["x"]}, {"references": ["x"]}, {"references": []}]},
               "fullJudge": {"assessment": {"correctComplete": False, "appropriateAbstention": False,
                   "claims": [{"path": "claims/0", "supported": True}, {"path": "claims/1", "supported": False},
                              {"path": "claims/2", "supported": False}]}}}
        result = metrics([case], [row], "full")
        self.assertEqual(result["citationSupportRate"], .5)
        self.assertAlmostEqual(result["citationCoverage"], 2 / 3)
        row["fullJudge"] = {"error": "failed"}
        self.assertIsNone(metrics([case], [row], "full")["citationSupportRate"])

    def test_duplicate_or_omitted_claims_rejected(self):
        """逐结论评审缺项或重复时拒绝出分。"""
        case = {"id": "a", "answerable": True}
        row = {"id": "a", "full": {"status": "ANSWERABLE", "conflicts": [], "claims": [{"references": ["x"]}]},
               "fullJudge": {"assessment": {"correctComplete": True, "appropriateAbstention": False, "claims": []}}}
        with self.assertRaises(ValueError):
            metrics([case], [row], "full")
        with self.assertRaises(ValueError):
            metrics([case], [row, row], "full")


if __name__ == "__main__":
    unittest.main()
