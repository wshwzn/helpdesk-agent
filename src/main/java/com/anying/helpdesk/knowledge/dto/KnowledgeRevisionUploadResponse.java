package com.anying.helpdesk.knowledge.dto;

/** CREATED 表示新候选已入队；UNCHANGED 与旧版相同；EXISTING 表示返回已有的相同候选。 */
public record KnowledgeRevisionUploadResponse(String outcome, KnowledgeDocumentResponse document) {
}
