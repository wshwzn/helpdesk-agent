package com.anying.helpdesk.knowledge.exception;

public class KnowledgeRetrievalException extends RuntimeException {

    /**
     * 创建不携带底层原因的知识检索异常。
     */
    public KnowledgeRetrievalException(String message) {
        super(message);
    }

    /**
     * 创建保留底层原因、但对接口仅暴露脱敏消息的知识检索异常。
     */
    public KnowledgeRetrievalException(String message, Throwable cause) {
        super(message, cause);
    }
}
