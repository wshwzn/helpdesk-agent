package com.anying.helpdesk.knowledge.answer;

import com.anying.helpdesk.conversation.dto.KnowledgeCitationResponse;
import com.anying.helpdesk.knowledge.dto.KnowledgeDocumentMetadata;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

/** 已通过核验的回答与当时证据快照；历史查看不重新生成结论。 */
public record KnowledgeAnswerReport(
        int schemaVersion,
        Reason reason,
        Long knowledgeBaseId,
        String knowledgeBaseName,
        String applicabilityScope,
        @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDateTime checkedAt,
        KnowledgeAnswerDraft answer,
        List<Evidence> evidence,
        Clarification clarification,
        Long continuationOfMessageId, List<String> applicabilityScopes
) {
    /** 旧报告读取时从标量恢复范围，新报告不得丢失多范围信息。 */
    public KnowledgeAnswerReport {
        applicabilityScopes = KnowledgeScopes.resolve(applicabilityScope, applicabilityScopes);
        applicabilityScope = KnowledgeScopes.single(applicabilityScopes);
    }
    /** 兼容旧报告及单范围续答的构造入口。 */
    public KnowledgeAnswerReport(int version, Reason reason, Long baseId, String baseName, String scope,
            LocalDateTime checkedAt, KnowledgeAnswerDraft answer, List<Evidence> evidence,
            Clarification clarification, Long continuationId) {
        this(version, reason, baseId, baseName, scope, checkedAt, answer, evidence, clarification, continuationId, null);
    }
    public enum Reason { REVIEWED, NO_MATCH, NOT_CONFIGURED, APPLICABILITY_REQUIRED }

    public record Clarification(String originalQuestion, long policyRevision, List<String> topicIds,
                                List<KnowledgeApplicabilityPolicy.ScopeOption> scopeOptions) { }

    /** 兼容旧报告和没有可续答澄清的调用方。 */
    public KnowledgeAnswerReport(int schemaVersion, Reason reason, Long knowledgeBaseId, String knowledgeBaseName,
                                 String applicabilityScope, LocalDateTime checkedAt, KnowledgeAnswerDraft answer,
                                 List<Evidence> evidence) {
        this(schemaVersion, reason, knowledgeBaseId, knowledgeBaseName, applicabilityScope, checkedAt, answer,
                evidence, null, null);
    }

    public record Evidence(String id, Long documentId, int chunkSequence, String sourceName,
                           String content, KnowledgeDocumentMetadata metadata) { }

    /** 按引用标识返回实际使用的证据，未被回答使用的候选不冒充引用。 */
    public List<KnowledgeCitationResponse> citations() {
        var used = Stream.concat(answer.claims().stream(), answer.conflicts().stream()
                        .flatMap(conflict -> Stream.of(conflict.first(), conflict.second())))
                .flatMap(claim -> claim.references().stream())
                .map(KnowledgeAnswerDraft.Reference::evidenceId).collect(java.util.stream.Collectors.toSet());
        return evidence.stream().filter(item -> used.contains(item.id()))
                .map(item -> new KnowledgeCitationResponse(knowledgeBaseName, item.sourceName(),
                        item.chunkSequence(), item.content(), item.documentId(), item.metadata())).toList();
    }
}
