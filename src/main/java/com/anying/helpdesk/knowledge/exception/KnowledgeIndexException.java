package com.anying.helpdesk.knowledge.exception;

public class KnowledgeIndexException extends RuntimeException {

    /**
     * 创建不包含服务端响应正文的索引异常。
     */
    public KnowledgeIndexException(String message) {
        super(message);
    }

    /**
     * 保留内部异常链，接口层只返回脱敏业务信息。
     */
    public KnowledgeIndexException(String message, Throwable cause) {
        super(message, cause);
    }
}
