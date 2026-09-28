package com.anying.helpdesk.knowledge.exception;

public class KnowledgeDocumentParsingException extends RuntimeException {

    /**
     * 将解析器异常转换为不暴露文件内容和底层实现的业务异常。
     */
    public KnowledgeDocumentParsingException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 表示文档没有可用于检索的正文。
     */
    public KnowledgeDocumentParsingException(String message) {
        super(message);
    }
}
