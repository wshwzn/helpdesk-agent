package com.anying.helpdesk.knowledge.dto;

import java.time.LocalDateTime;

/** 最近一次索引提交的运行状态；不向客户端公开执行者令牌。 */
public record KnowledgeIndexTaskResponse(Long documentId, String status, int attemptCount, int maxAttempts,
        LocalDateTime availableAt, LocalDateTime leaseUntil, String errorCode,
        LocalDateTime createdAt, LocalDateTime updatedAt) {
}
