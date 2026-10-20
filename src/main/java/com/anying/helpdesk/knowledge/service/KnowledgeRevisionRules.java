package com.anying.helpdesk.knowledge.service;

import com.anying.helpdesk.knowledge.domain.KnowledgeDocument;
import com.anying.helpdesk.knowledge.domain.KnowledgeDocumentStatus;
import com.anying.helpdesk.knowledge.domain.KnowledgePublicationStatus;
import com.anying.helpdesk.knowledge.exception.KnowledgeDocumentConflictException;

import java.time.LocalDateTime;

public final class KnowledgeRevisionRules {
    /** 只允许替代当前可用且尚未安排接替的版本，禁止从过期或已撤回版本分叉发布。 */
    public static void requireReplaceable(KnowledgeDocument old, LocalDateTime now) {
        if (old.getPublicationStatus() != KnowledgePublicationStatus.PUBLISHED
                || old.getStatus() != KnowledgeDocumentStatus.READY
                || old.getValidFrom() == null || old.getValidFrom().isAfter(now)
                || (old.getValidUntil() != null && !old.getValidUntil().isAfter(now))
                || (old.getActivatedAt() != null && old.getActivatedAt().isAfter(now))
                || old.getSuccessorDocumentId() != null) {
            throw new KnowledgeDocumentConflictException("predecessor must be currently available without an approved successor");
        }
    }

    /** 纯规则类无需实例化。 */
    private KnowledgeRevisionRules() {
    }
}
