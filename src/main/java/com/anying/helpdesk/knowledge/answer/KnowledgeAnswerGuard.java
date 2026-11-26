package com.anying.helpdesk.knowledge.answer;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import static com.anying.helpdesk.knowledge.answer.KnowledgeAnswerFailure.Code.*;

/** 确定性校验只负责结构、证据身份与逐字引文；不声称验证语义蕴含。 */
@Component
public class KnowledgeAnswerGuard {

    private static final Pattern EVIDENCE_TAG = Pattern.compile("\\[E[0-9]+]");

    /** 不同地区的独立金额不是制度冲突；ALL 与具体范围仍可能构成同条件冲突。 */
    public void validateScopeConflicts(KnowledgeAnswerDraft draft, List<KnowledgeAnswerReport.Evidence> evidence) {
        var byId = indexEvidence(evidence);
        for (var conflict : draft.conflicts()) {
            var first = conflict.first().references().stream().map(r -> byId.get(r.evidenceId()).metadata().applicabilityScope())
                    .collect(Collectors.toSet());
            var second = conflict.second().references().stream().map(r -> byId.get(r.evidenceId()).metadata().applicabilityScope())
                    .collect(Collectors.toSet());
            require(first.contains("ALL") || second.contains("ALL") || first.stream().anyMatch(second::contains),
                    "different scopes alone do not establish a conflict");
        }
    }

    /** 将模型片段选择解析为服务端原文，再校验状态与冲突结构，避免对已截取原文重复子串扫描。 */
    public KnowledgeAnswerDraft resolve(KnowledgeAnswerSelection selection, KnowledgeCitationCatalog catalog,
                                        List<KnowledgeAnswerReport.Evidence> evidence) {
        require(selection != null, "missing answer selection");
        if (!Objects.equals(catalog.citationSetId(), selection.citationSetId())) {
            throw new KnowledgeAnswerFailure(KNOWLEDGE_CITATION_INVALID, "citation set mismatch");
        }
        require(selection.claims() != null && selection.claims().size() <= 8, "invalid claims");
        require(selection.conflicts() != null && selection.conflicts().size() <= 3, "invalid conflicts");
        Map<String, KnowledgeCitationCatalog.Source> sourcesById = catalog.sources().stream()
                .collect(Collectors.toMap(KnowledgeCitationCatalog.Source::id, item -> item));
        Map<String, KnowledgeAnswerReport.Evidence> evidenceById = indexEvidence(evidence);
        var claims = selection.claims().stream().map(claim -> resolveClaim(claim, sourcesById, evidenceById)).toList();
        var conflicts = selection.conflicts().stream().map(conflict -> {
            require(conflict != null, "invalid conflict issue");
            return new KnowledgeAnswerDraft.Conflict(conflict.issue(),
                    resolveClaim(conflict.first(), sourcesById, evidenceById),
                    resolveClaim(conflict.second(), sourcesById, evidenceById));
        }).toList();
        var draft = new KnowledgeAnswerDraft(selection.status(), claims, selection.missingConditions(), conflicts);
        validateStructure(draft, evidenceById);
        return draft;
    }

    /** 仅接受对应证据内可选择的片段；按原始快照下标取文，禁止拼接或归一化引文。 */
    private KnowledgeAnswerDraft.Claim resolveClaim(KnowledgeAnswerSelection.Claim claim,
                                                     Map<String, KnowledgeCitationCatalog.Source> sourcesById,
                                                     Map<String, KnowledgeAnswerReport.Evidence> evidenceById) {
        require(claim != null && validText(claim.text(), 1000), "invalid claim");
        require(claim.references() != null && !claim.references().isEmpty()
                && claim.references().size() <= 5, "claim requires references");
        var references = claim.references().stream().map(reference -> {
            if (reference == null || reference.evidenceId() == null || reference.paragraphId() == null) {
                throw new KnowledgeAnswerFailure(KNOWLEDGE_CITATION_INVALID, "missing paragraph reference");
            }
            var source = sourcesById.get(reference.evidenceId());
            if (source == null) {
                throw new KnowledgeAnswerFailure(KNOWLEDGE_CITATION_INVALID, "unknown evidence id");
            }
            var paragraph = source.paragraphs().stream().filter(item -> item.id().equals(reference.paragraphId()))
                    .findFirst().orElseThrow(() -> new KnowledgeAnswerFailure(KNOWLEDGE_CITATION_INVALID, "unknown paragraph id"));
            var original = evidenceById.get(reference.evidenceId());
            if (original == null) {
                throw new KnowledgeAnswerFailure(KNOWLEDGE_CITATION_INVALID, "unknown evidence id");
            }
            if (!paragraph.selectable() || paragraph.startOffset() < 0 || paragraph.endOffset() > original.content().length()
                    || paragraph.startOffset() >= paragraph.endOffset()
                    || !original.content().substring(paragraph.startOffset(), paragraph.endOffset()).equals(paragraph.text())) {
                throw new KnowledgeAnswerFailure(KNOWLEDGE_CITATION_INVALID, "paragraph is not selectable");
            }
            return new KnowledgeAnswerDraft.Reference(original.id(),
                    original.content().substring(paragraph.startOffset(), paragraph.endOffset()));
        }).toList();
        return new KnowledgeAnswerDraft.Claim(claim.text(), references);
    }

    /** 拒绝非法状态、无依据结论以及伪造的引用，防止草案绕过证据约束。 */
    public void validate(KnowledgeAnswerDraft draft, List<KnowledgeAnswerReport.Evidence> evidence) {
        require(draft != null, "missing answer status");
        require(draft.claims() != null && draft.claims().size() <= 8, "invalid claims");
        require(draft.conflicts() != null && draft.conflicts().size() <= 3, "invalid conflicts");
        Map<String, KnowledgeAnswerReport.Evidence> byId = indexEvidence(evidence);
        draft.claims().forEach(claim -> validateClaim(claim, byId));
        for (var conflict : draft.conflicts()) {
            require(conflict != null, "invalid conflict issue");
            validateClaim(conflict.first(), byId);
            validateClaim(conflict.second(), byId);
        }
        validateStructure(draft, byId);
    }

    /** 校验草案状态、条件文本以及冲突双方来自不同文档。 */
    private void validateStructure(KnowledgeAnswerDraft draft, Map<String, KnowledgeAnswerReport.Evidence> byId) {
        require(draft.status() != null, "missing answer status");
        require(draft.missingConditions() != null && draft.missingConditions().size() <= 5, "invalid conditions");
        draft.missingConditions().forEach(condition -> require(validText(condition, 300), "invalid condition"));
        for (var conflict : draft.conflicts()) {
            require(conflict != null && validText(conflict.issue(), 300), "invalid conflict issue");
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

    /** 建立证据编号索引，避免校验多条引用时反复线性扫描。 */
    private Map<String, KnowledgeAnswerReport.Evidence> indexEvidence(List<KnowledgeAnswerReport.Evidence> evidence) {
        return evidence.stream().collect(Collectors.toMap(KnowledgeAnswerReport.Evidence::id, item -> item));
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
                && !EVIDENCE_TAG.matcher(text).find();
    }

    /** 核验不通过时终止生成，禁止把技术失败伪装为证据不足。 */
    private void require(boolean valid, String reason) {
        if (!valid) {
            throw new KnowledgeAnswerFailure(KNOWLEDGE_STRUCTURE_INVALID, reason);
        }
    }
}
