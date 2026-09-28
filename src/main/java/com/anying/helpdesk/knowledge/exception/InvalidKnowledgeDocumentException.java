package com.anying.helpdesk.knowledge.exception;

public class InvalidKnowledgeDocumentException extends RuntimeException {

    /**
     * 表示上传文件不满足知识库文档规则。
     */
    public InvalidKnowledgeDocumentException(String message) {
        super(message);
    }
}
