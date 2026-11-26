package com.anying.helpdesk.knowledge.answer;

import com.anying.helpdesk.knowledge.dto.KnowledgeBaseResponse;

import com.anying.helpdesk.conversation.model.ChatCompletionResult;
import com.anying.helpdesk.knowledge.dto.KnowledgeDocumentMetadata;
import com.anying.helpdesk.knowledge.service.KnowledgeBaseService;
import com.anying.helpdesk.knowledge.service.KnowledgeDocumentEligibilityService;
import com.anying.helpdesk.modelconfig.factory.ResolvedChatModel;
import com.anying.helpdesk.knowledge.answer.trace.KnowledgeAnswerTrace;
import static com.anying.helpdesk.knowledge.answer.trace.KnowledgeAnswerRunSnapshot.Stage.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import static com.anying.helpdesk.knowledge.answer.KnowledgeAnswerFailure.Code.*;

@Service
@RequiredArgsConstructor
public class TrustedKnowledgeAnswerService {

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeEvidenceCollector evidenceCollector;
    private final KnowledgeDocumentEligibilityService eligibilityService;
    private final KnowledgeAnswerModelClient modelClient;
    private final KnowledgeAnswerGuard guard;
    private final Clock knowledgeClock;
    private final KnowledgeApplicabilityService applicabilityService;

    /** 兼容直接业务调用；正式会话入口使用显式关联助手消息的诊断上下文。 */
    public ChatCompletionResult answer(ResolvedChatModel model, String question, String scope) {
        return answer(model, question, scope, new KnowledgeAnswerTrace(null, null, null, question, scope,
                model, knowledgeClock, ignored -> { }));
    }

    /** 执行原有核验链路并记录阶段快照，诊断信息不进入客户可见回答。 */
    public ChatCompletionResult answer(ResolvedChatModel model, String question, String scope, KnowledgeAnswerTrace trace) {
        return answer(model, new KnowledgeAnswerInput(question, scope, null, null, null), trace);
    }

    /** 用恢复的原问题和用户明确选择的范围重新执行完整检索及核验。 */
    public ChatCompletionResult answer(ResolvedChatModel model, KnowledgeAnswerInput input, KnowledgeAnswerTrace trace) {
        String question = input.question();
        String requestedScope = input.scope();
        if (question == null || question.isBlank() || question.length() > 2000
                || input.scopes().isEmpty()) {
            throw new IllegalArgumentException("invalid knowledge question");
        }
        var preparation = "ALL".equals(requestedScope) || input.continuationOfMessageId() != null
                ? trace.stage(APPLICABILITY, () -> prepareApplicability(model, input, trace)) : null;
        var selectedScopes = input.scopes();
        if (preparation != null && !preparation.options().isEmpty()) {
            var named = applicabilityService.namedScopes(question, preparation.options());
            if (!named.isEmpty()) selectedScopes = named;
        }
        final var scopes = selectedScopes;
        final String scope = KnowledgeScopes.describe(scopes);
        trace.scopes(scopes);
        if (preparation != null && !preparation.options().isEmpty() && scopes.equals(List.of("ALL"))) {
            applicabilityService.requireRevision(preparation.base().id(), preparation.policy().revision());
            var clarification = new KnowledgeAnswerReport.Clarification(question, preparation.policy().revision(),
                    preparation.route().value().topicIds(), preparation.options());
            var draft = new KnowledgeAnswerDraft(KnowledgeAnswerDraft.Status.NEEDS_CLARIFICATION, List.of(),
                    List.of("请明确本次问题适用的地区或范围？"), List.of());
            var report = new KnowledgeAnswerReport(2, KnowledgeAnswerReport.Reason.APPLICABILITY_REQUIRED,
                    preparation.base().id(), preparation.base().name(), scope, LocalDateTime.now(knowledgeClock),
                    draft, List.of(), clarification, null);
            trace.answerReason(report.reason());
            trace.resolved(draft);
            return withRoutingUsage(new ChatCompletionResult(render(report), 0, 0, 0, report), preparation);
        }
        var retrieved = retrieve(model, question, scopes, trace, preparation);
        if (retrieved.reason() != KnowledgeAnswerReport.Reason.REVIEWED) {
            trace.answerReason(retrieved.reason());
            checkPolicy(preparation);
            return withRoutingUsage(withScopes(withContinuation(empty(retrieved.reason(), retrieved.baseId(), retrieved.baseName(), KnowledgeScopes.single(scopes)), input), scopes), preparation);
        }
        var evidence = retrieved.evidence();
        var verified = verifyAnswer(model, question, scope, scopes, retrieved, trace);
        LocalDateTime checkedAt = trace.stage(EVIDENCE_RECHECK, () -> {
            LocalDateTime now = LocalDateTime.now(knowledgeClock);
            var current = KnowledgeAnswerFailure.wrap(KNOWLEDGE_EVIDENCE_CHECK_FAILED,
                    () -> scopes.size() == 1 ? eligibilityService.findEligible(retrieved.baseId(), scope, now)
                            : eligibilityService.findEligibleScopes(retrieved.baseId(), scopes, now));
            for (var item : evidence) {
                var document = current.get(item.documentId());
                if (document == null || !Objects.equals(item.metadata(), KnowledgeDocumentMetadata.from(document))) {
                    throw new KnowledgeAnswerFailure(KNOWLEDGE_EVIDENCE_CHANGED, "knowledge evidence changed during generation");
                }
            }
            return now;
        });
        checkPolicy(preparation);
        var report = new KnowledgeAnswerReport(3, KnowledgeAnswerReport.Reason.REVIEWED,
                retrieved.baseId(), retrieved.baseName(), KnowledgeScopes.single(scopes), checkedAt, verified.draft(), evidence, null, null, scopes);
        trace.answerReason(report.reason());
        var usage = addUsage(verified.usage(), retrieved.planningUsage());
        return withRoutingUsage(withContinuation(new ChatCompletionResult(render(report),
                usage.promptTokens(), usage.completionTokens(), usage.totalTokens(), report), input), preparation);
    }

    private record VerifiedAnswer(KnowledgeAnswerDraft draft, KnowledgeAnswerModelClient.TokenUsage usage) { }

    /** 首轮有具体语义缺陷时只修订一次；两轮都执行引用、条件及语义核验，不循环求批准。 */
    private VerifiedAnswer verifyAnswer(ResolvedChatModel model, String question, String scope, List<String> scopes,
                                        Retrieved retrieved, KnowledgeAnswerTrace trace) {
        var selection = trace.stage(DRAFT, () -> {
            var result = KnowledgeAnswerFailure.wrap(KNOWLEDGE_DRAFT_CALL_FAILED,
                    () -> modelClient.draft(model, question, scope, retrieved.catalog(), retrieved.context()));
            trace.selected(result);
            return result;
        });
        var draft = validateSelection(selection.value(), scopes, retrieved, trace);
        var review = reviewAnswer(model, question, scope, retrieved.evidence(), draft, trace, false);
        var usage = addUsage(usageOf(selection), usageOf(review));
        if (Boolean.TRUE.equals(review.value().approved())) return new VerifiedAnswer(draft, usage);

        var revised = trace.stage(REVISION, () -> {
            var result = KnowledgeAnswerFailure.wrap(KNOWLEDGE_REVISION_CALL_FAILED, () -> modelClient.revise(model, question, scope,
                    retrieved.catalog(), selection.value(), review.value(), retrieved.context()));
            trace.selected(result);
            return result;
        });
        var corrected = validateSelection(revised.value(), scopes, retrieved, trace);
        var finalReview = reviewAnswer(model, question, scope, retrieved.evidence(), corrected, trace, true);
        return new VerifiedAnswer(corrected, addUsage(usage, addUsage(usageOf(revised), usageOf(finalReview))));
    }

    /** 修订和首次生成复用同一目录与校验，不允许修订绕过范围及检索任务约束。 */
    private KnowledgeAnswerDraft validateSelection(KnowledgeAnswerSelection selection, List<String> scopes,
                                                   Retrieved retrieved, KnowledgeAnswerTrace trace) {
        return trace.stage(VALIDATION, () -> {
            var resolved = guard.resolve(selection, retrieved.catalog(), retrieved.evidence());
            trace.resolved(resolved);
            if (retrieved.context() != null) retrieved.context().validate(resolved);
            if (scopes.size() > 1) guard.validateScopeConflicts(resolved, retrieved.evidence());
            return resolved;
        });
    }

    /** 首次具体否决作为修订输入留档；无缺陷详情的旧协议和末次否决仍按失败终止。 */
    private KnowledgeAnswerModelClient.ModelResult<KnowledgeAnswerModelClient.Review> reviewAnswer(
            ResolvedChatModel model, String question, String scope, List<KnowledgeAnswerReport.Evidence> evidence,
            KnowledgeAnswerDraft draft, KnowledgeAnswerTrace trace, boolean finalAttempt) {
        return trace.stage(REVIEW, () -> {
            var result = KnowledgeAnswerFailure.wrap(KNOWLEDGE_REVIEW_CALL_FAILED,
                    () -> modelClient.review(model, question, scope, evidence, draft));
            trace.reviewed(result);
            var decision = result.value();
            if (finalAttempt || decision == null || !Boolean.FALSE.equals(decision.approved())
                    || decision.reason() == null || decision.reason() == KnowledgeAnswerModelClient.ReviewReason.NONE
                    || decision.issues() == null || decision.issues().isEmpty()) requireApproved(decision);
            return result;
        });
    }

    /** 将每次模型结果的用量独立计入本轮，包括被否决的首次草案及其复核。 */
    private KnowledgeAnswerModelClient.TokenUsage usageOf(KnowledgeAnswerModelClient.ModelResult<?> result) {
        return new KnowledgeAnswerModelClient.TokenUsage(result.promptTokens(), result.completionTokens(), result.totalTokens());
    }

    /** 任一调用用量未知时保持未知，不以修订后调用的统计覆盖前一次成本。 */
    private KnowledgeAnswerModelClient.TokenUsage addUsage(KnowledgeAnswerModelClient.TokenUsage first,
                                                          KnowledgeAnswerModelClient.TokenUsage second) {
        return new KnowledgeAnswerModelClient.TokenUsage(sum(first.promptTokens(), second.promptTokens()),
                sum(first.completionTokens(), second.completionTokens()), sum(first.totalTokens(), second.totalTokens()));
    }

    private record ApplicabilityPreparation(KnowledgeBaseResponse base,
            KnowledgeApplicabilityPolicy policy, KnowledgeAnswerModelClient.ModelResult<KnowledgeApplicabilityPolicy.Route> route,
            List<KnowledgeApplicabilityPolicy.ScopeOption> options) { }

    /** 只在缺少范围时进行主题路由；续答绑定原知识库并复核配置，不再次猜测主题。 */
    private ApplicabilityPreparation prepareApplicability(ResolvedChatModel model, KnowledgeAnswerInput input,
                                                         KnowledgeAnswerTrace trace) {
        var base = KnowledgeAnswerFailure.wrap(KNOWLEDGE_RETRIEVAL_FAILED, () -> input.knowledgeBaseId() == null
                ? knowledgeBaseService.findDefault().orElse(null) : knowledgeBaseService.getById(input.knowledgeBaseId()));
        if (base == null) return new ApplicabilityPreparation(null, null, null, List.of());
        var policy = applicabilityService.get(base.id());
        trace.applicability(policy, null, input.continuationOfMessageId(), false);
        if (input.continuationOfMessageId() != null) {
            applicabilityService.requireRevision(base.id(), input.policyRevision());
            return new ApplicabilityPreparation(base, policy, null, List.of());
        }
        if (policy.topics().isEmpty()) return new ApplicabilityPreparation(base, policy, null, List.of());
        trace.applicability(policy, null, null, true);
        var route = KnowledgeAnswerFailure.wrap(KNOWLEDGE_APPLICABILITY_CALL_FAILED, () -> modelClient.route(model, input.question(), policy));
        trace.applicability(policy, route, null, true);
        var options = applicabilityService.requiredOptions(policy, route.value());
        return new ApplicabilityPreparation(base, policy, route, options);
    }

    /** 在交付前确认配置没有变化，不能把过期条件判断作为当前澄清依据。 */
    private void checkPolicy(ApplicabilityPreparation preparation) {
        if (preparation != null && preparation.policy() != null)
            applicabilityService.requireRevision(preparation.base().id(), preparation.policy().revision());
    }

    /** 追加路由调用的已知用量；任何模型调用用量未知时保持未知。 */
    private ChatCompletionResult withRoutingUsage(ChatCompletionResult result, ApplicabilityPreparation preparation) {
        if (preparation == null || preparation.route() == null) return result;
        var route = preparation.route();
        return new ChatCompletionResult(result.content(), sum(result.promptTokens(), route.promptTokens()),
                sum(result.completionTokens(), route.completionTokens()), sum(result.totalTokens(), route.totalTokens()), result.knowledgeAnswer());
    }

    /** 最终报告关联澄清来源，历史卡片保持原样，不向客户暴露内部诊断。 */
    private ChatCompletionResult withContinuation(ChatCompletionResult result, KnowledgeAnswerInput input) {
        if (input.continuationOfMessageId() == null) return result;
        var old = result.knowledgeAnswer();
        var report = new KnowledgeAnswerReport(3, old.reason(), old.knowledgeBaseId(), old.knowledgeBaseName(),
                old.applicabilityScope(), old.checkedAt(), old.answer(), old.evidence(), null, input.continuationOfMessageId(), old.applicabilityScopes());
        return new ChatCompletionResult(result.content(), result.promptTokens(), result.completionTokens(), result.totalTokens(), report);
    }

    /** 无命中也保存完整范围，不把跨地区请求退化成通用查询。 */
    private ChatCompletionResult withScopes(ChatCompletionResult result, List<String> scopes) {
        var old = result.knowledgeAnswer();
        var report = new KnowledgeAnswerReport(3, old.reason(), old.knowledgeBaseId(), old.knowledgeBaseName(),
                KnowledgeScopes.single(scopes), old.checkedAt(), old.answer(), old.evidence(), old.clarification(),
                old.continuationOfMessageId(), scopes);
        return new ChatCompletionResult(result.content(), result.promptTokens(), result.completionTokens(), result.totalTokens(), report);
    }

    private record Retrieved(Long baseId, String baseName, KnowledgeAnswerReport.Reason reason,
                             List<KnowledgeAnswerReport.Evidence> evidence, KnowledgeCitationCatalog catalog,
                             KnowledgeAnswerModelClient.TokenUsage planningUsage, KnowledgeAnswerContext context) { }

    /** 固定知识库后累计受控检索证据，再一次性建立最终可引用目录。 */
    private Retrieved retrieve(ResolvedChatModel model, String question, List<String> scopes, KnowledgeAnswerTrace trace,
                               ApplicabilityPreparation preparation) {
        var base = preparation == null ? KnowledgeAnswerFailure.wrap(KNOWLEDGE_RETRIEVAL_FAILED, knowledgeBaseService::findDefault)
                : java.util.Optional.ofNullable(preparation.base());
        if (base.isEmpty()) return new Retrieved(null, null, KnowledgeAnswerReport.Reason.NOT_CONFIGURED, List.of(), null, null, null);
        var knowledgeBase = base.get();
        var collected = scopes.size() == 1
                ? evidenceCollector.collect(knowledgeBase.id(), model, question, scopes.getFirst(), trace)
                : evidenceCollector.collectScopes(knowledgeBase.id(), model, question, scopes, trace);
        var immutableEvidence = collected.evidence();
        if (immutableEvidence.isEmpty())
            return new Retrieved(knowledgeBase.id(), knowledgeBase.name(), KnowledgeAnswerReport.Reason.NO_MATCH,
                    List.of(), null, collected.planningUsage(), collected.context());
        var catalog = KnowledgeCitationCatalog.create(immutableEvidence);
        trace.catalog(catalog);
        return new Retrieved(knowledgeBase.id(), knowledgeBase.name(), KnowledgeAnswerReport.Reason.REVIEWED,
                immutableEvidence, catalog, collected.planningUsage(), collected.context());
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
        if (report.reason() == KnowledgeAnswerReport.Reason.APPLICABILITY_REQUIRED) {
            return "请补充本次问题适用的地区或范围（比较时最多选择3项）：" + report.clarification().scopeOptions().stream()
                    .map(KnowledgeApplicabilityPolicy.ScopeOption::label).collect(Collectors.joining("、")) + "。";
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
