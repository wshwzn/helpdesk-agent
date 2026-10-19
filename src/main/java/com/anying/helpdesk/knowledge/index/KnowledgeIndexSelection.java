package com.anying.helpdesk.knowledge.index;

/** 指定一份获准文档的有效索引批次；空批次只允许读取升级前的历史片段。 */
public record KnowledgeIndexSelection(Long documentId, String buildId) {
}
