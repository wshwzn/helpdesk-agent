package com.anying.helpdesk.knowledge.exception;

public class KnowledgeBaseConflictException extends RuntimeException {

    /**
     * 表示知识库名称或删除条件发生业务冲突。
     */
    public KnowledgeBaseConflictException(String message) {
        super(message);
    }
}
