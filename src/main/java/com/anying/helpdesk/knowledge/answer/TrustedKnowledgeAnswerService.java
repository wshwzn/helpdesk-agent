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
import java.util.function.Supplier;
import static com.anying.helpdesk.knowledge.answer.KnowledgeAnswerFailure.Code.*;

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
        var base = atStage(KNOWLEDGE_RETRIEVAL_FAILED, knowledgeBaseService::findDefault);
        if (base.isEmpty()) {
            return empty(KnowledgeAnswerReport.Reason.NOT_CONFIGURED, null, null, scope);
        }
        var knowledgeBase = base.get();
        var search = atStage(KNOWLEDGE_RETRIEVAL_FAILED,
                () -> retrievalService.search(knowledgeBase.id(), new KnowledgeSearchRequest(question, scope)));
        if (search.results().isEmpty()) {
            return empty(KnowledgeAnswerReport.Reason.NO_MATCH, knowledgeBase.id(), knowledgeBase.name(), scope);
        }
        List<KnowledgeAnswerReport.Evidence> evidence = new ArrayList<>();
        for (var hit : search.results()) {
            if (hit.documentId() == null || hit.metadata() == null || hit.content() == null || hit.content().isBlank()) {
                throw new KnowledgeAnswerFailure(KNOWLEDGE_EVIDENCE_CHECK_FAILED, "incomplete evidence metadata");
            }
            evidence.add(new KnowledgeAnswerReport.Evidence("E" + (evidence.size() + 1), hit.documentId(),
                    hit.chunkSequence(), hit.originalName(), hit.content(), hit.metadata()));
        }
        if (evidence.size() > 50 || evidence.stream().mapToInt(item -> item.content().length()).sum() > 50000) {
            throw new KnowledgeAnswerFailure(KNOWLEDGE_CONTEXT_TOO_LARGE, "knowledge evidence exceeds budget");
        }
        var immutableEvidence = List.copyOf(evidence);
        var draft = atStage(KNOWLEDGE_DRAFT_CALL_FAILED,
                () -> modelClient.draft(model, question, scope, immutableEvidence));
        guard.validate(draft.value(), immutableEvidence);
        var review = atStage(KNOWLEDGE_REVIEW_CALL_FAILED,
                () -> modelClient.review(model, question, scope, immutableEvidence, draft.value()));
        requireApproved(review.value());
        LocalDateTime checkedAt = LocalDateTime.now(knowledgeClock);
        var current = atStage(KNOWLEDGE_EVIDENCE_CHECK_FAILED,
                () -> eligibilityService.findEligible(knowledgeBase.id(), scope, checkedAt));
        for (var item : evidence) {
            var document = current.get(item.documentId());
            if (document == null || !Objects.equals(item.metadata(), KnowledgeDocumentMetadata.from(document))) {
                throw new KnowledgeAnswerFailure(KNOWLEDGE_EVIDENCE_CHANGED, "knowledge evidence changed during generation");
            }
        }
        var report = new KnowledgeAnswerReport(1, KnowledgeAnswerReport.Reason.REVIEWED,
                knowledgeBase.id(), knowledgeBase.name(), scope, checkedAt, draft.value(), immutableEvidence);
        return new ChatCompletionResult(render(report), sum(draft.promptTokens(), review.promptTokens()),
                sum(draft.completionTokens(), review.completionTokens()), sum(draft.totalTokens(), review.totalTokens()), report);
    }

    /** 拒绝不一致的复核协议，并把合法否决原因映射成固定枚举码，避免输出自由文本。 */
    private void requireApproved(KnowledgeAnswerModelClient.Review review) {
        if (review == null || review.approved() == null
                || (Boolean.TRUE.equals(review.approved()) && review.reason() != null
                && review.reason() != KnowledgeAnswerModelClient.ReviewReason.NONE)
                || (Boolean.FALSE.equals(review.approved()) && review.reason() == KnowledgeAnswerModelClient.ReviewReason.NONE)) {
            throw new KnowledgeAnswerFailure(KNOWLEDGE_PROTOCOL_INVALID, "inconsistent review result");
        }
        if (!review.approved()) {
            var code = review.reason() == null ? KNOWLEDGE_REVIEW_REJECTED : switch (review.reason()) {
                case UNSUPPORTED_CLAIM -> KNOWLEDGE_REVIEW_UNSUPPORTED_CLAIM;
                case WRONG_STATUS -> KNOWLEDGE_REVIEW_WRONG_STATUS;
                case MISSED_CONFLICT -> KNOWLEDGE_REVIEW_MISSED_CONFLICT;
                case INCOMPLETE_ANSWER -> KNOWLEDGE_REVIEW_INCOMPLETE_ANSWER;
                case UNNECESSARY_CLARIFICATION -> KNOWLEDGE_REVIEW_UNNECESSARY_CLARIFICATION;
                case UNCERTAIN -> KNOWLEDGE_REVIEW_UNCERTAIN;
                case NONE -> throw new IllegalStateException("unreachable approved reason");
            };
            throw new KnowledgeAnswerFailure(code, "knowledge answer review rejected");
        }
    }

    /** 外部依赖失败只记录当前阶段，已有确定性错误保留精确分类，避免暴露敏感异常正文。 */
    private <T> T atStage(KnowledgeAnswerFailure.Code code, Supplier<T> action) {
        try {
            return action.get();
        } catch (KnowledgeAnswerFailure failure) {
            throw failure;
        } catch (RuntimeException exception) {
            throw new KnowledgeAnswerFailure(code, code.name());
        }
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
