"""保证关联问答不能仅凭碰巧出现正确数字获得完整回答分。"""
import unittest
from evaluate_bridge_qa import grade


class BridgeGradeTest(unittest.TestCase):
    """覆盖目标引用、关联链、部分回答和技术失败。"""

    def test_requires_both_facts_chain_and_target_citation(self):
        """两项事实、完整链路和正确规则引用缺一不可。"""
        case = {"id": "b01", "requiredDocuments": ["b01-entry", "b01-rule"],
                "facts": [{"field": "响应时限", "value": 3, "unit": "小时"}, {"field": "到场时限", "value": 14, "unit": "小时"}]}
        row = {"answer": {"status": "ANSWERABLE", "claims": [{"text": "响应时限3小时，到场时限14小时。", "references": [{"evidenceId": "E2"}]}]},
               "diagnostic": {"evidence": [{"id": "E1", "metadata": {"sourceKey": "bridge:b01-entry"}},
                                           {"id": "E2", "metadata": {"sourceKey": "bridge:b01-rule"}}]}}
        self.assertTrue(grade(case, row)["completeAnswer"])
        row["answer"]["claims"][0]["text"] = "响应时限14小时，到场时限3小时。"
        self.assertFalse(grade(case, row)["completeAnswer"])
        row["answer"]["claims"][0]["text"] = "响应时限3小时，到场时限14小时。"
        row["answer"]["claims"][0]["references"][0]["evidenceId"] = "E1"
        self.assertFalse(grade(case, row)["completeAnswer"])
        row["answer"]["claims"][0]["references"][0]["evidenceId"] = "E2"
        row["diagnostic"]["evidence"].pop(0)
        self.assertFalse(grade(case, row)["completeAnswer"])

    def test_failure_or_partial_answer_is_not_complete(self):
        """检索完整不代表交付成功，证据不足和运行失败不计完整回答。"""
        case = {"id": "b01", "requiredDocuments": ["b01-rule"], "facts": [{"field": "合格分数", "value": 67, "unit": "分"}]}
        row = {"answer": {"status": "INSUFFICIENT_EVIDENCE", "claims": [{"text": "合格分数67分", "references": [{"evidenceId": "E1"}]}]},
               "diagnostic": {"evidence": [{"id": "E1", "metadata": {"sourceKey": "bridge:b01-rule"}}]}}
        self.assertFalse(grade(case, row)["completeAnswer"])
        row.pop("answer")
        self.assertTrue(grade(case, row)["evidenceComplete"])
        self.assertFalse(grade(case, row)["completeAnswer"])


if __name__ == "__main__":
    unittest.main()
