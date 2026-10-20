package com.anying.helpdesk.knowledge.dto;

import java.time.LocalDateTime;

/** 一次审核事务后的双方状态和交接时刻。 */
public record KnowledgeRevisionApprovalResponse(KnowledgeDocumentResponse predecessor,
                                                KnowledgeDocumentResponse successor, LocalDateTime effectiveAt) {
}
