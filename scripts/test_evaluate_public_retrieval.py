"""检索指标的关键口径回归，不调用外部服务。"""

import unittest

from evaluate_public_retrieval import aggregate, metrics, rankings


class RetrievalMetricsTest(unittest.TestCase):
    """覆盖多份证据、文档命中和候选完整性之间的区别。"""

    def test_document_hit_is_not_evidence_hit(self):
        """命中文档中无关片段时不能算作证据命中。"""
        case = {"evidenceGroups": [[{"document": "a", "paragraph": "p1"}]]}
        documents = {"a": {"paragraphs": {"p1": "必须在第一周申请。"}}}
        result = metrics(case, [{"documentId": 1, "content": "学校简介。"}], documents, {1: "a"}, 5)
        self.assertEqual(result["documentRecall"], 1)
        self.assertEqual(result["evidenceRecall"], 0)

    def test_duplicate_hits_do_not_complete_two_evidence_groups(self):
        """同一证据重复召回不增加召回率，多文档问题需要覆盖全部证据组。"""
        case = {"evidenceGroups": [[{"document": "a", "paragraph": "p1"}], [{"document": "b", "paragraph": "p2"}]]}
        documents = {"a": {"paragraphs": {"p1": "第一条"}}, "b": {"paragraphs": {"p2": "第二条"}}}
        hits = [{"documentId": 1, "content": "第一条"}, {"documentId": 1, "content": "第一条"},
                {"documentId": 2, "content": "第二 条"}]
        partial = metrics(case, hits, documents, {1: "a", 2: "b"}, 2)
        self.assertEqual(partial["evidenceRecall"], .5)
        self.assertEqual(partial["documentRecall"], .5)
        self.assertEqual(partial["evidenceComplete"], 0)
        self.assertEqual(metrics(case, hits, documents, {1: "a", 2: "b"}, 3)["evidenceComplete"], 1)

    def test_truncated_union_cannot_be_used_as_single_route_baseline(self):
        """返回结果缺失单路候选时必须拒绝计算基线。"""
        response = {"bm25CandidateCount": 2, "vectorCandidateCount": 1,
                    "results": [{"bm25Rank": 1, "vectorRank": 1}]}
        with self.assertRaises(ValueError):
            rankings(response)
        response["results"].append({"bm25Rank": 2, "vectorRank": None})
        self.assertEqual(len(rankings(response)["BM25"]), 2)

    def test_technical_failure_stays_in_denominator(self):
        """技术失败不从分母删除，也不混进成功请求耗时。"""
        values = {field: 1 for field in ["documentRecall", "documentHit", "documentMRR", "evidenceRecall",
                                       "evidenceHit", "evidenceComplete", "evidenceMRR"]}
        success = {"latencyMs": 20, "metrics": {method: {str(k): values for k in [1, 5, 10]}
                                                for method in ["BM25", "VECTOR", "RRF"]}}
        result = aggregate([success, {"error": "ApiError"}])
        self.assertEqual(result["methods"]["RRF"]["5"]["evidenceRecall"], .5)
        self.assertEqual(result["technicalFailures"], 1)
        self.assertEqual(result["sharedRequestLatencyP95Ms"], 20)


if __name__ == "__main__":
    unittest.main()
