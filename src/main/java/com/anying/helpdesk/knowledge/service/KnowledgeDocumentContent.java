package com.anying.helpdesk.knowledge.service;

import java.io.InputStream;

/**
 * 封装原始文档的响应元数据与对象存储读取流。
 */
public record KnowledgeDocumentContent(
        String originalName,
        String contentType,
        long contentLength,
        InputStream inputStream
) {
}
