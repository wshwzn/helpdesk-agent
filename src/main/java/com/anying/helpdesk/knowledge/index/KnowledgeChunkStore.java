package com.anying.helpdesk.knowledge.index;

import java.util.List;

public interface KnowledgeChunkStore {

    /**
     * 确保 Chunk 索引存在，并校验已存在索引的向量维度。
     */
    void ensureIndex(int dimensions);

    /**
     * 写入指定执行批次，禁止删除其他批次；有效批次由数据库决定。
     */
    void writeBuildChunks(Long documentId, String buildId, int dimensions, List<IndexedKnowledgeChunk> chunks);

    /**
     * 在指定知识库中同时执行 BM25 与 KNN 检索，返回两路独立排序的候选。
     */
    KnowledgeChunkSearchResults search(
            Long knowledgeBaseId,
            Long embeddingModelConfigId,
            String embeddingModelName,
            String query,
            float[] queryVector,
            int rankWindowSize,
            int vectorNumCandidates,
            double vectorMinSimilarity,
            List<KnowledgeIndexSelection> eligibleIndexes
    );

    /**
     * 删除指定文档在检索索引中的全部 Chunk，索引不存在时按成功处理。
     */
    void deleteDocument(Long documentId);
}
