package com.anying.helpdesk.knowledge.exception;

public class KnowledgeBaseNotFoundException extends RuntimeException {

    /**
     * 表示指定知识库不存在。
     */
    public KnowledgeBaseNotFoundException(Long id) {
        super("knowledge base not found: " + id);
    }
}
