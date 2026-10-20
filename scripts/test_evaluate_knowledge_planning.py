"""规划回放的完整性与准入检查，不调用模型。"""
import unittest
from evaluate_knowledge_planning import summarize


class PlanningSummaryTest(unittest.TestCase):
    """失败和不完整运行不能通过自动门槛。"""

    def test_gate_requires_two_complete_valid_repeats(self):
        dataset = {"cases": [{"id": "P01"}]}
        rows = [{"id": "P01", "repeat": i, "durationMs": 1, "plan": {}, "errorCode": None,
                 "usage": None, "scenarioChecksPassed": True} for i in (1, 2)]
        self.assertTrue(summarize(dataset, rows, 2)["automaticGatePassed"])
        self.assertFalse(summarize(dataset, rows[:1], 2)["automaticGatePassed"])
        self.assertFalse(summarize(dataset, rows[:1], 1)["automaticGatePassed"])
        rows[1].update(plan=None, errorCode="KNOWLEDGE_PLAN_FIELDS_INVALID", scenarioChecksPassed=False)
        summary = summarize(dataset, rows, 2)
        self.assertFalse(summary["automaticGatePassed"])
        self.assertEqual(2, summary["metrics"]["callsMissingUsage"])

    def test_duplicate_and_unknown_identity_are_rejected(self):
        row = {"id": "P01", "repeat": 1, "durationMs": 1, "plan": {}, "errorCode": None, "scenarioChecksPassed": True}
        with self.assertRaises(ValueError):
            summarize({"cases": [{"id": "P01"}]}, [row, row], 2)
        with self.assertRaises(ValueError):
            summarize({"cases": [{"id": "P02"}]}, [row], 2)


if __name__ == "__main__":
    unittest.main()
