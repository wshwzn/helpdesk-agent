package com.anying.helpdesk.knowledge.exception;

public class KnowledgeDocumentConflictException extends RuntimeException {

    /**
     * 表示文档当前状态或并发版本不允许执行目标操作。
     */
    public KnowledgeDocumentConflictException(String message) {
        super(message);
    }
}
