package com.anying.helpdesk.knowledge.answer;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import static com.anying.helpdesk.knowledge.answer.KnowledgeAnswerFailure.Code.*;

/** 确定性校验只负责结构、证据身份与逐字引文；不声称验证语义蕴含。 */
@Component
public class KnowledgeAnswerGuard {

    /** 拒绝非法状态、无依据结论以及伪造的引用，防止草案绕过证据约束。 */
    public void validate(KnowledgeAnswerDraft draft, List<KnowledgeAnswerReport.Evidence> evidence) {
        require(draft != null && draft.status() != null, "missing answer status");
        require(draft.claims() != null && draft.claims().size() <= 8, "invalid claims");
        require(draft.missingConditions() != null && draft.missingConditions().size() <= 5, "invalid conditions");
        require(draft.conflicts() != null && draft.conflicts().size() <= 3, "invalid conflicts");
        Map<String, KnowledgeAnswerReport.Evidence> byId = evidence.stream()
                .collect(Collectors.toMap(KnowledgeAnswerReport.Evidence::id, item -> item));
        draft.claims().forEach(claim -> validateClaim(claim, byId));
        draft.missingConditions().forEach(condition -> require(validText(condition, 300), "invalid condition"));
        for (var conflict : draft.conflicts()) {
            require(conflict != null && validText(conflict.issue(), 300), "invalid conflict issue");
            validateClaim(conflict.first(), byId);
            validateClaim(conflict.second(), byId);
            var firstDocuments = conflict.first().references().stream()
                    .map(ref -> byId.get(ref.evidenceId()).documentId()).collect(Collectors.toSet());
            var secondDocuments = conflict.second().references().stream()
                    .map(ref -> byId.get(ref.evidenceId()).documentId()).collect(Collectors.toSet());
            require(firstDocuments.stream().anyMatch(first -> secondDocuments.stream()
                    .anyMatch(second -> !Objects.equals(first, second))), "conflict requires distinct documents");
        }
        require((draft.status() == KnowledgeAnswerDraft.Status.CONFLICT) == !draft.conflicts().isEmpty(),
                "conflict status mismatch");
        require((draft.status() == KnowledgeAnswerDraft.Status.NEEDS_CLARIFICATION)
                        == !draft.missingConditions().isEmpty(), "clarification status mismatch");
        if (draft.status() == KnowledgeAnswerDraft.Status.ANSWERABLE) {
            require(!draft.claims().isEmpty(), "answer requires supported claims");
        }
    }

    /** 每个结论必须引用本次检索分配的标识，并提供来源 Chunk 中连续存在的原文。 */
    private void validateClaim(KnowledgeAnswerDraft.Claim claim,
                               Map<String, KnowledgeAnswerReport.Evidence> evidence) {
        require(claim != null && validText(claim.text(), 1000), "invalid claim");
        require(claim.references() != null && !claim.references().isEmpty()
                && claim.references().size() <= 5, "claim requires references");
        for (var reference : claim.references()) {
            require(reference != null && reference.evidenceId() != null, "missing evidence id");
            var source = evidence.get(reference.evidenceId());
            if (source == null) {
                throw new KnowledgeAnswerFailure(KNOWLEDGE_CITATION_INVALID, "unknown evidence id");
            }
            if (!validText(reference.quote(), 1500) || reference.quote().strip().length() < 4
                    || !source.content().contains(reference.quote())) {
                throw new KnowledgeAnswerFailure(KNOWLEDGE_CITATION_INVALID, "quote is absent from evidence");
            }
        }
    }

    /** 限制用户可见模型文本的长度，引用标签由服务端统一生成。 */
    private boolean validText(String text, int max) {
        return text != null && !text.isBlank() && text.length() <= max
                && !text.matches("(?s).*\\[E[0-9]+].*");
    }

    /** 核验不通过时终止生成，禁止把技术失败伪装为证据不足。 */
    private void require(boolean valid, String reason) {
        if (!valid) {
            throw new KnowledgeAnswerFailure(KNOWLEDGE_STRUCTURE_INVALID, reason);
        }
    }
}
