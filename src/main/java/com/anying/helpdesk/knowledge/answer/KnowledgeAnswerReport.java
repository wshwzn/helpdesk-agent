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
        List<Evidence> evidence
) {
    public enum Reason { REVIEWED, NO_MATCH, NOT_CONFIGURED }

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
