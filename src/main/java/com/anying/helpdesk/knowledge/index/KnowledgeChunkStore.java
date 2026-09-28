package com.anying.helpdesk.knowledge.index;

import java.util.List;

public interface KnowledgeChunkStore {

    /**
     * 确保 Chunk 索引存在，并校验已存在索引的向量维度。
     */
    void ensureIndex(int dimensions);

    /**
     * 原子业务语义上替换一份文档的全部 Chunk；失败时调用方负责状态补偿。
     */
    void replaceDocumentChunks(Long documentId, int dimensions, List<IndexedKnowledgeChunk> chunks);

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
            int vectorNumCandidates
    );

    /**
     * 删除指定文档在检索索引中的全部 Chunk，索引不存在时按成功处理。
     */
    void deleteDocument(Long documentId);
}
