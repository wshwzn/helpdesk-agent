package com.anying.helpdesk.knowledge.service;

import com.anying.helpdesk.knowledge.domain.KnowledgeDocument;
import com.anying.helpdesk.knowledge.dto.KnowledgeDocumentResponse;
import com.anying.helpdesk.knowledge.index.KnowledgeChunkStore;
import com.anying.helpdesk.modelconfig.factory.DefaultEmbeddingModelProvider;
import com.anying.helpdesk.modelconfig.factory.ResolvedEmbeddingModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class KnowledgeDocumentIndexingService {

    private final KnowledgeDocumentPersistenceService persistenceService;
    private final DefaultEmbeddingModelProvider embeddingModelProvider;
    private final KnowledgeChunkStore chunkStore;
    private final KnowledgeDocumentIndexWorker indexWorker;

    /**
     * 完成 Embedding 配置和 ES 映射预检后，将文档推进到 PROCESSING 并提交后台任务。
     */
    public KnowledgeDocumentResponse start(Long knowledgeBaseId, Long documentId) {
        ResolvedEmbeddingModel embeddingModel = embeddingModelProvider.get();
        chunkStore.ensureIndex(embeddingModel.dimensions());
        KnowledgeDocument document = persistenceService.markProcessing(knowledgeBaseId, documentId);
        try {
            indexWorker.index(knowledgeBaseId, documentId);
        } catch (RuntimeException exception) {
            persistenceService.markIndexingFailed(documentId, "INDEX_TASK_REJECTED");
            throw exception;
        }
        return KnowledgeDocumentResponse.from(document);
    }
}
