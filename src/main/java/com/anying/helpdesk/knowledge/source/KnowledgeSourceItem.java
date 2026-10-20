package com.anying.helpdesk.knowledge.source;

import java.time.LocalDateTime;

public record KnowledgeSourceItem(Long id, Long sourceId, String objectKey, String acceptedFingerprint,
        Long lastDocumentId, String itemStatus, String errorCode, LocalDateTime updatedAt) {
}
