package com.anying.helpdesk.knowledge.dto;

import com.anying.helpdesk.knowledge.domain.KnowledgeDocument;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 保存业务版本与适用条件的快照，用于索引、模型证据及历史引用。
 */
public record KnowledgeDocumentMetadata(
        String sourceKey,
        String businessVersion,
        String applicabilityScope,
        @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDateTime validFrom,
        @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDateTime validUntil
) {
    /**
     * 从权威文档记录提取不含存储位置的业务元数据。
     */
    public static KnowledgeDocumentMetadata from(KnowledgeDocument document) {
        LocalDateTime from = document.getValidFrom();
        if (document.getActivatedAt() != null && (from == null || document.getActivatedAt().isAfter(from))) {
            from = document.getActivatedAt();
        }
        LocalDateTime until = document.getValidUntil();
        if (document.getSupersededAt() != null && (until == null || document.getSupersededAt().isBefore(until))) {
            until = document.getSupersededAt();
        }
        return new KnowledgeDocumentMetadata(document.getSourceKey(), document.getBusinessVersion(),
                document.getApplicabilityScope(), from, until);
    }
}
