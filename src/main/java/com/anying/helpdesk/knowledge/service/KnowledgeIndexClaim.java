package com.anying.helpdesk.knowledge.service;

/** 一次索引执行的所有权凭证，同时用作 ES 批次隔离标识。 */
public record KnowledgeIndexClaim(Long knowledgeBaseId, Long documentId, String token) {
}
