package com.anying.helpdesk.knowledge.exception;

public class KnowledgeStorageException extends RuntimeException {

    /**
     * 将对象存储异常转换为不包含凭据和厂商正文的业务异常。
     */
    public KnowledgeStorageException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 表示对象存储配置或状态不满足当前操作。
     */
    public KnowledgeStorageException(String message) {
        super(message);
    }
}
