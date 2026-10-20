package com.anying.helpdesk.knowledge.source;

import java.time.LocalDateTime;

public record KnowledgeSource(Long id, Long knowledgeBaseId, String bucketName, String objectPrefix,
        boolean enabled, Long createdBy, String scanStatus, LocalDateTime leaseUntil, LocalDateTime lastScanAt,
        String errorCode, int addedCount, int updatedCount, int unchangedCount, int failedCount, int missingCount) {
}
