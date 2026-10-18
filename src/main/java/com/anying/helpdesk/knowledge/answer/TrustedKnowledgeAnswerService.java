package com.anying.helpdesk.knowledge.answer;

import com.anying.helpdesk.conversation.model.ChatCompletionResult;
import com.anying.helpdesk.knowledge.dto.KnowledgeDocumentMetadata;
import com.anying.helpdesk.knowledge.dto.KnowledgeSearchRequest;
import com.anying.helpdesk.knowledge.service.KnowledgeBaseService;
import com.anying.helpdesk.knowledge.service.KnowledgeDocumentEligibilityService;
import com.anying.helpdesk.knowledge.service.KnowledgeRetrievalService;
import com.anying.helpdesk.modelconfig.factory.ResolvedChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TrustedKnowledgeAnswerService {

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeRetrievalService retrievalService;
    private final KnowledgeDocumentEligibilityService eligibilityService;
    private final KnowledgeAnswerModelClient modelClient;
    private final KnowledgeAnswerGuard guard;
    private final Clock knowledgeClock;

    /** 执行检索、草案校验、语义复核及准入复查，只返回可以落库展示的回答。 */
    public ChatCompletionResult answer(ResolvedChatModel model, String question, String scope) {
        if (question == null || question.isBlank() || question.length() > 2000
                || scope == null || !scope.matches("[A-Z][A-Z0-9_-]{0,39}")) {
            throw new IllegalArgumentException("invalid knowledge question");
        }
        var base = knowledgeBaseService.findDefault();
        if (base.isEmpty()) {
            return empty(KnowledgeAnswerReport.Reason.NOT_CONFIGURED, null, null, scope);
        }
        var knowledgeBase = base.get();
        var search = retrievalService.search(knowledgeBase.id(), new KnowledgeSearchRequest(question, scope));
        if (search.results().isEmpty()) {
            return empty(KnowledgeAnswerReport.Reason.NO_MATCH, knowledgeBase.id(), knowledgeBase.name(), scope);
        }
        List<KnowledgeAnswerReport.Evidence> evidence = new ArrayList<>();
        for (var hit : search.results()) {
            if (hit.documentId() == null || hit.metadata() == null || hit.content() == null || hit.content().isBlank()) {
                throw new IllegalStateException("incomplete evidence metadata");
            }
            evidence.add(new KnowledgeAnswerReport.Evidence("E" + (evidence.size() + 1), hit.documentId(),
                    hit.chunkSequence(), hit.originalName(), hit.content(), hit.metadata()));
        }
        if (evidence.size() > 50 || evidence.stream().mapToInt(item -> item.content().length()).sum() > 50000) {
            throw new IllegalStateException("knowledge evidence exceeds budget");
        }
        evidence = List.copyOf(evidence);
        var draft = modelClient.draft(model, question, scope, evidence);
        guard.validate(draft.value(), evidence);
        var review = modelClient.review(model, question, scope, evidence, draft.value());
        if (review.value() == null || !Boolean.TRUE.equals(review.value().approved())) {
            throw new IllegalStateException("knowledge answer review rejected");
        }
        LocalDateTime checkedAt = LocalDateTime.now(knowledgeClock);
        var current = eligibilityService.findEligible(knowledgeBase.id(), scope, checkedAt);
        for (var item : evidence) {
            var document = current.get(item.documentId());
            if (document == null || !Objects.equals(item.metadata(), KnowledgeDocumentMetadata.from(document))) {
                throw new IllegalStateException("knowledge evidence changed during generation");
            }
        }
        var report = new KnowledgeAnswerReport(1, KnowledgeAnswerReport.Reason.REVIEWED,
                knowledgeBase.id(), knowledgeBase.name(), scope, checkedAt, draft.value(), evidence);
        return new ChatCompletionResult(render(report), sum(draft.promptTokens(), review.promptTokens()),
                sum(draft.completionTokens(), review.completionTokens()), sum(draft.totalTokens(), review.totalTokens()), report);
    }

    /** 没有可用资料时直接返回明确原因，不让模型凭记忆补全回答。 */
    private ChatCompletionResult empty(KnowledgeAnswerReport.Reason reason, Long baseId, String name, String scope) {
        var draft = new KnowledgeAnswerDraft(KnowledgeAnswerDraft.Status.INSUFFICIENT_EVIDENCE,
                List.of(), List.of(), List.of());
        var report = new KnowledgeAnswerReport(1, reason, baseId, name, scope,
                LocalDateTime.now(knowledgeClock), draft, List.of());
        return new ChatCompletionResult(render(report), 0, 0, 0, report);
    }

    /** 由服务端固定模板组织正文，模型只能提供通过核验的结论与问题。 */
    private String render(KnowledgeAnswerReport report) {
        if (report.reason() == KnowledgeAnswerReport.Reason.NOT_CONFIGURED) {
            return "尚未配置默认知识库，暂时无法提供有依据的回答。";
        }
        if (report.reason() == KnowledgeAnswerReport.Reason.NO_MATCH) {
            return "在当前适用范围和生效时间内未检索到可用依据。请补充适用范围或联系知识维护者。";
        }
        var answer = report.answer();
        StringBuilder content = new StringBuilder(switch (answer.status()) {
            case ANSWERABLE -> "根据本轮检索资料：";
            case NEEDS_CLARIFICATION -> "需要补充条件后才能确定答案：";
            case INSUFFICIENT_EVIDENCE -> "现有证据不足以完整回答问题。";
            case CONFLICT -> "检索到疑似冲突，请知识维护者核实；暂不选择其中一方。";
        });
        answer.claims().forEach(claim -> content.append("\n\n").append(renderClaim(claim)));
        answer.missingConditions().forEach(question -> content.append("\n\n待补充：").append(question));
        for (var conflict : answer.conflicts()) {
            content.append("\n\n冲突事项：").append(conflict.issue())
                    .append("\n依据一：").append(renderClaim(conflict.first()))
                    .append("\n依据二：").append(renderClaim(conflict.second()));
        }
        return content.toString();
    }

    /** 为核验后的结论附加服务端认可的本轮引用编号。 */
    private String renderClaim(KnowledgeAnswerDraft.Claim claim) {
        return claim.text() + " " + claim.references().stream().map(KnowledgeAnswerDraft.Reference::evidenceId)
                .distinct().map(id -> "[" + id + "]").collect(Collectors.joining(" "));
    }

    /** 任一模型调用缺失用量时保留未知状态，避免把不完整统计当成实际总量。 */
    private Integer sum(Integer first, Integer second) {
        return first == null || second == null ? null : Math.addExact(first, second);
    }
}
