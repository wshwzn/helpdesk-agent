package com.anying.helpdesk.knowledge.service;

/** 已校验的原始文件及其摘要，仅在上传请求期间持有字节内容。 */
public record KnowledgeUploadFile(byte[] content, String originalName, String extension, String contentType, String sha256) {
}
