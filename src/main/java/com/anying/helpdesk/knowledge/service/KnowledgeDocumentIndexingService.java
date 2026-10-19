package com.anying.helpdesk.knowledge.service;

import com.anying.helpdesk.knowledge.domain.KnowledgeDocument;
import com.anying.helpdesk.knowledge.dto.KnowledgeDocumentResponse;
import com.anying.helpdesk.knowledge.domain.KnowledgeDocumentStatus;
import com.anying.helpdesk.knowledge.dto.KnowledgeIndexTaskResponse;
import com.anying.helpdesk.knowledge.dto.UpdateKnowledgeDocumentMetadataRequest;
import com.anying.helpdesk.knowledge.exception.KnowledgeDocumentConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class KnowledgeDocumentIndexingService {

    private final KnowledgeDocumentPersistenceService persistenceService;
    private final KnowledgeDocumentPublicationService publicationService;
    private final KnowledgeIndexTaskStore taskStore;

    /**
     * 将草稿和索引任务一起提交到数据库；外部依赖由工作者检查，活动任务重复请求不重复入队。
     */
    @Transactional
    public KnowledgeDocumentResponse start(Long knowledgeBaseId, Long documentId) {
        KnowledgeDocument document = persistenceService.lock(knowledgeBaseId, documentId);
        if (document.getStatus() == KnowledgeDocumentStatus.PROCESSING) {
            KnowledgeIndexTaskResponse task = taskStore.find(documentId);
            if (task != null && ("QUEUED".equals(task.status()) || "RUNNING".equals(task.status()))) {
                return KnowledgeDocumentResponse.from(document);
            }
            throw new KnowledgeDocumentConflictException("processing document has no active index task");
        }
        document = persistenceService.markProcessing(knowledgeBaseId, documentId);
        taskStore.enqueue(documentId);
        return KnowledgeDocumentResponse.from(document);
    }

    /** 保存完整元数据并自动入队，两步同一事务，避免元数据已提交但任务丢失。 */
    @Transactional
    public KnowledgeDocumentResponse submit(Long knowledgeBaseId, Long documentId,
                                             UpdateKnowledgeDocumentMetadataRequest request) {
        persistenceService.lock(knowledgeBaseId, documentId);
        publicationService.updateMetadata(knowledgeBaseId, documentId, request);
        return start(knowledgeBaseId, documentId);
    }

    /** 返回最近一次索引提交的进度；尚未提交时返回空，先校验知识库归属。 */
    public KnowledgeIndexTaskResponse task(Long knowledgeBaseId, Long documentId) {
        persistenceService.get(knowledgeBaseId, documentId);
        return taskStore.find(documentId);
    }
}
