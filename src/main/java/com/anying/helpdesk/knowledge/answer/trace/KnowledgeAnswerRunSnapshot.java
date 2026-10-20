package com.anying.helpdesk.knowledge.answer.trace;

import com.anying.helpdesk.knowledge.answer.KnowledgeApplicabilityPolicy;
import com.anying.helpdesk.knowledge.answer.KnowledgeRetrievalRun;
import com.anying.helpdesk.knowledge.answer.KnowledgeAnswerContext;

import com.anying.helpdesk.knowledge.answer.KnowledgeAnswerDraft;
import com.anying.helpdesk.knowledge.answer.KnowledgeAnswerSelection;
import com.anying.helpdesk.knowledge.answer.KnowledgeCitationCatalog;
import com.anying.helpdesk.knowledge.answer.KnowledgeAnswerModelClient;
import com.anying.helpdesk.knowledge.answer.KnowledgeAnswerReport;
import com.anying.helpdesk.knowledge.dto.KnowledgeSearchResponse;

import java.time.LocalDateTime;
import java.util.List;

/** 管理员专用的运行快照；未核验草案不得并入客户消息响应。 */
public record KnowledgeAnswerRunSnapshot(
        int schemaVersion, String runId, Long assistantMessageId, Long conversationId,
        String question, String applicabilityScope, Long modelConfigId, String modelName,
        Status status, String errorCode, long revision, int diagnosticWriteFailures,
        LocalDateTime startedAt, LocalDateTime updatedAt, LocalDateTime finishedAt,
        KnowledgeSearchResponse retrieval, boolean retrievalTruncated,
        List<KnowledgeAnswerReport.Evidence> evidence, KnowledgeAnswerDraft draft,
        KnowledgeAnswerModelClient.Review review, KnowledgeAnswerReport.Reason answerReason,
        List<StageResult> stages, KnowledgeCitationCatalog citationCatalog, KnowledgeAnswerSelection selection,
        KnowledgeApplicabilityPolicy.Assessment applicability, KnowledgeRetrievalRun retrievalRun,
        KnowledgeAnswerContext answerContext
) {
    public enum Status { RUNNING, SUCCEEDED, FAILED, CANCELLED }
    public enum Stage { APPLICABILITY, RETRIEVAL, PLANNING, DRAFT, VALIDATION, REVIEW, EVIDENCE_RECHECK, MESSAGE_PERSISTENCE }
    public enum StageStatus { RUNNING, SUCCEEDED, FAILED, CANCELLED }

    public record StageResult(Stage stage, StageStatus status, LocalDateTime startedAt,
                              Long durationMs, String errorCode, KnowledgeAnswerModelClient.TokenUsage usage) { }
}
