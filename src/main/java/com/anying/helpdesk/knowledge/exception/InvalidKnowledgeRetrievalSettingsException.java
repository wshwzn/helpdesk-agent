package com.anying.helpdesk.knowledge.exception;

public class InvalidKnowledgeRetrievalSettingsException extends RuntimeException {

    /**
     * 创建包含安全业务提示的非法检索参数异常。
     */
    public InvalidKnowledgeRetrievalSettingsException(String message) {
        super(message);
    }
}
