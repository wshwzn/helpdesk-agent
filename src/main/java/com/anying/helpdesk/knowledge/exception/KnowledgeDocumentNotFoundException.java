package com.anying.helpdesk.knowledge.exception;

public class KnowledgeDocumentNotFoundException extends RuntimeException {

    /**
     * 表示指定知识库中不存在该文档。
     */
    public KnowledgeDocumentNotFoundException(Long knowledgeBaseId, Long documentId) {
        super("knowledge document not found: " + knowledgeBaseId + "/" + documentId);
    }
}
