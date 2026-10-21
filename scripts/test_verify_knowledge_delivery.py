"""检查版本隔离验收不会将空结果、旧版混入或多分块误判。"""
import unittest
from verify_knowledge_delivery import exact_document_ids


class DeliveryChecksTest(unittest.TestCase):
    """约束真实回归中最关键的新旧文档判定。"""

    def test_empty_search_cannot_prove_new_version_is_visible(self):
        """需要新版时空召回必须失败。"""
        self.assertFalse(exact_document_ids([], [2]))

    def test_old_version_mixed_with_new_is_rejected(self):
        """即使新版被召回，旧版混入仍必须失败。"""
        self.assertFalse(exact_document_ids([{"documentId": 1}, {"documentId": 2}], [2]))

    def test_multiple_chunks_of_same_version_are_allowed(self):
        """同一有效文档的多个分块不误判为额外版本。"""
        self.assertTrue(exact_document_ids([{"documentId": 2}, {"documentId": 2}], [2]))

    def test_unpublished_documents_require_empty_results(self):
        """待处理和草稿阶段只接受空文档集合。"""
        self.assertTrue(exact_document_ids([], []))
        self.assertFalse(exact_document_ids([{"documentId": 1}], []))


if __name__ == "__main__":
    unittest.main()
