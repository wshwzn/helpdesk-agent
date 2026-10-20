package com.anying.helpdesk.knowledge.answer;

import com.anying.helpdesk.knowledge.answer.trace.KnowledgeAnswerTrace;
import com.anying.helpdesk.knowledge.dto.KnowledgeSearchRequest;
import com.anying.helpdesk.knowledge.dto.KnowledgeSearchResponse;
import com.anying.helpdesk.knowledge.service.KnowledgeRetrievalService;
import com.anying.helpdesk.modelconfig.factory.ResolvedChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static com.anying.helpdesk.knowledge.answer.KnowledgeAnswerFailure.Code.*;
import static com.anying.helpdesk.knowledge.answer.KnowledgeRetrievalRun.StopReason.*;
import static com.anying.helpdesk.knowledge.answer.trace.KnowledgeAnswerRunSnapshot.Stage.*;

/** 在固定知识库和范围内收集证据；查询规划与最终答案核验各自独立。 */
@Service
public class KnowledgeEvidenceCollector {
    private final KnowledgeRetrievalService retrieval;
    private final KnowledgeAnswerModelClient client;
    private final KnowledgeMultihopProperties properties;
    private final LongSupplier ticker;

    /** 使用单调时钟约束补查调度，外部调用继续受现有请求超时机制约束。 */
    @Autowired
    public KnowledgeEvidenceCollector(KnowledgeRetrievalService retrieval, KnowledgeAnswerModelClient client,
                                      KnowledgeMultihopProperties properties) {
        this(retrieval, client, properties, System::nanoTime);
    }

    /** 注入可控计时器以验证耗时边界，避免测试依赖真实等待。 */
    KnowledgeEvidenceCollector(KnowledgeRetrievalService retrieval, KnowledgeAnswerModelClient client,
                               KnowledgeMultihopProperties properties, LongSupplier ticker) {
        this.retrieval = retrieval;
        this.client = client;
        this.properties = properties;
        this.ticker = ticker;
    }

    public record Collected(List<KnowledgeAnswerReport.Evidence> evidence,
                            KnowledgeAnswerModelClient.TokenUsage planningUsage, KnowledgeAnswerContext context) { }
    private record Key(Long documentId, int chunkSequence) { }

    /** 最多执行首轮和两轮补查，所有停止结果都沿原可信草案及复核链路交付。 */
    public Collected collect(Long baseId, ResolvedChatModel model, String question, String scope, KnowledgeAnswerTrace trace) {
        boolean enabled = properties.isEnabled();
        long started = ticker.getAsLong();
        long budgetMs = properties.getSchedulingBudgetSeconds() * 1000L;
        trace.retrievalStarted(enabled, budgetMs);
        var evidence = new LinkedHashMap<Key, KnowledgeAnswerReport.Evidence>();
        var queries = new ArrayList<String>();
        boolean ruleChecksEnabled = enabled && properties.isRuleChecksEnabled();
        var assessment = new Assessment(ruleChecksEnabled);
        var seen = new LinkedHashSet<String>();
        var usage = new KnowledgeAnswerModelClient.TokenUsage(0, 0, 0);
        search(baseId, scope, 1, question, trace, evidence);
        queries.add(question);
        seen.add(normalize(question));
        if (evidence.isEmpty()) return stop(trace, NO_MATCH, evidence, usage, assessment, queries);
        if (!enabled) return stop(trace, SINGLE_PASS, evidence, usage, assessment, queries);
        for (int round = 2; round <= 3; round++) {
            if (expired(started, budgetMs)) return stop(trace, TIME_BUDGET, evidence, usage, assessment, queries);
            int afterRound = round - 1;
            var planned = trace.stage(PLANNING, () -> {
                var result = external(KNOWLEDGE_PLANNING_CALL_FAILED,
                        () -> ruleChecksEnabled
                                ? client.plan(model, question, scope, List.copyOf(evidence.values()), List.copyOf(queries))
                                : client.planLegacy(model, question, scope, List.copyOf(evidence.values()), List.copyOf(queries)));
                if (result == null || result.value() == null)
                    throw new KnowledgeAnswerFailure(ruleChecksEnabled ? KNOWLEDGE_PLAN_PAYLOAD_INVALID : KNOWLEDGE_PROTOCOL_INVALID, "missing retrieval plan");
                trace.planned(afterRound, result);
                if (ruleChecksEnabled) result.value().validateEvidence(List.copyOf(evidence.values()));
                else result.value().validateLegacy();
                return result;
            });
            usage = new KnowledgeAnswerModelClient.TokenUsage(sum(usage.promptTokens(), planned.promptTokens()),
                    sum(usage.completionTokens(), planned.completionTokens()), sum(usage.totalTokens(), planned.totalTokens()));
            var plan = planned.value();
            assessment.accept(plan, queries.size());
            var pending = assessment.pending(queries);
            if (plan.decision() != KnowledgeRetrievalPlan.Decision.SEARCH && pending.isEmpty())
                return stop(trace, KnowledgeRetrievalRun.StopReason.valueOf(plan.decision().name()), evidence, usage, assessment, queries);
            var scheduled = ruleChecksEnabled ? assessment.scheduled(queries) : plan.queries();
            int before = evidence.size();
            int executed = 0;
            for (String query : scheduled) {
                if (executed == 2) break;
                if (!seen.add(normalize(query))) continue;
                if (expired(started, budgetMs)) return stop(trace, TIME_BUDGET, evidence, usage, assessment, queries);
                search(baseId, scope, round, query.strip(), trace, evidence);
                queries.add(query.strip());
                executed++;
            }
            if (executed == 0) return stop(trace, REPEATED_QUERY, evidence, usage, assessment, queries);
            if (evidence.size() == before && (!ruleChecksEnabled || assessment.scheduled(queries).isEmpty()))
                return stop(trace, NO_NEW_EVIDENCE, evidence, usage, assessment, queries);
        }
        return stop(trace, ROUND_BUDGET, evidence, usage, assessment, queries);
    }

    /** 每次搜索都绑定同一身份，保存实际召回并按文档与块序号累计完整原文。 */
    private void search(Long baseId, String scope, int round, String query, KnowledgeAnswerTrace trace,
                        LinkedHashMap<Key, KnowledgeAnswerReport.Evidence> evidence) {
        trace.searchStarted(round, query);
        trace.stage(RETRIEVAL, () -> {
            var response = external(KNOWLEDGE_RETRIEVAL_FAILED,
                    () -> retrieval.search(baseId, new KnowledgeSearchRequest(query, scope)));
            List<String> added = List.of();
            try {
                added = merge(baseId, scope, response, evidence, trace);
                trace.evidence(List.copyOf(evidence.values()));
                return response;
            } finally {
                if (response != null && response.results() != null) trace.searchFinished(response, added);
            }
        });
    }

    /** 整批校验再合并，不能静默截掉超预算的后续证据或用变化原文替换已有来源。 */
    private List<String> merge(Long baseId, String scope, KnowledgeSearchResponse response,
            LinkedHashMap<Key, KnowledgeAnswerReport.Evidence> evidence, KnowledgeAnswerTrace trace) {
        if (response == null || response.results() == null || !Objects.equals(baseId, response.knowledgeBaseId()))
            throw new KnowledgeAnswerFailure(KNOWLEDGE_EVIDENCE_CHECK_FAILED, "invalid retrieval identity");
        var merged = new LinkedHashMap<>(evidence);
        var added = new ArrayList<String>();
        for (var hit : response.results()) {
            if (hit.documentId() == null || hit.metadata() == null || hit.content() == null || hit.content().isBlank()
                    || !(scope.equals(hit.metadata().applicabilityScope()) || "ALL".equals(hit.metadata().applicabilityScope())))
                throw new KnowledgeAnswerFailure(KNOWLEDGE_EVIDENCE_CHECK_FAILED, "incomplete or out-of-scope evidence");
            var key = new Key(hit.documentId(), hit.chunkSequence());
            var previous = merged.get(key);
            if (previous != null) {
                if (!Objects.equals(previous.metadata(), hit.metadata()) || !previous.content().equals(hit.content()))
                    throw new KnowledgeAnswerFailure(KNOWLEDGE_EVIDENCE_CHANGED, "knowledge evidence changed during retrieval");
                continue;
            }
            String id = "E" + (merged.size() + 1);
            merged.put(key, new KnowledgeAnswerReport.Evidence(id, hit.documentId(), hit.chunkSequence(),
                    hit.originalName(), hit.content(), hit.metadata()));
            added.add(id);
        }
        if (merged.size() > 50 || merged.values().stream().mapToLong(item -> item.content().length()).sum() > 50000) {
            trace.retrievalStopped(EVIDENCE_BUDGET);
            throw new KnowledgeAnswerFailure(KNOWLEDGE_CONTEXT_TOO_LARGE, "knowledge evidence exceeds budget");
        }
        evidence.clear();
        evidence.putAll(merged);
        return added;
    }

    /** 结束补查并返回稳定 E 编号，规划输出本身不进入最终证据目录。 */
    private Collected stop(KnowledgeAnswerTrace trace, KnowledgeRetrievalRun.StopReason reason,
            LinkedHashMap<Key, KnowledgeAnswerReport.Evidence> evidence, KnowledgeAnswerModelClient.TokenUsage usage,
            Assessment assessment, List<String> queries) {
        trace.retrievalStopped(reason);
        var context = assessment.enabled ? new KnowledgeAnswerContext(reason,
                assessment.plan == null ? List.of() : assessment.plan.gaps(),
                assessment.plan == null ? List.of() : assessment.plan.userConditions(),
                assessment.pending(queries), queries.size() > assessment.queryCount) : null;
        trace.answerContext(context);
        return new Collected(List.copyOf(evidence.values()), usage, context);
    }

    /** 请求内保留未完成的核查责任，后续规划不能通过省略字段遗忘旧任务。 */
    private static class Assessment {
        private final boolean enabled;
        private KnowledgeRetrievalPlan plan;
        private int queryCount;
        private final LinkedHashMap<String, String> checks = new LinkedHashMap<>();
        private final LinkedHashMap<String, String> tasks = new LinkedHashMap<>();

        /** 明确本次是否启用规划约束，单轮对照不伪造规划条件。 */
        private Assessment(boolean enabled) { this.enabled = enabled; }

        /** 保存最近一次缺口分类，同时累计所有已经声明且有原文锚点的核查查询。 */
        private void accept(KnowledgeRetrievalPlan value, int count) {
            plan = value;
            queryCount = count;
            if (enabled) {
                value.queries().forEach(this::enqueue);
                value.ruleChecks().forEach(c -> {
                    enqueue(c.query());
                    checks.putIfAbsent(KnowledgeRetrievalPlan.normalize(c.query()), c.query());
                });
            }
        }

        /** 所有查询进入同一请求内队列，缺口查询按规划顺序入队，规则核查不再插队。 */
        private void enqueue(String query) {
            tasks.putIfAbsent(KnowledgeRetrievalPlan.normalize(query), query);
        }

        /** 跨轮保留尚未执行的任务，先过滤已查项，再由调用方限制本轮实际执行次数。 */
        private List<String> scheduled(List<String> queries) {
            var executed = queries.stream().map(KnowledgeRetrievalPlan::normalize).collect(java.util.stream.Collectors.toSet());
            return tasks.entrySet().stream().filter(e -> !executed.contains(e.getKey())).map(java.util.Map.Entry::getValue).toList();
        }

        /** 只用已经成功返回的真实请求解除核查责任，不采信模型自称已查。 */
        private List<String> pending(List<String> queries) {
            var executed = queries.stream().map(KnowledgeRetrievalPlan::normalize).collect(java.util.stream.Collectors.toSet());
            return checks.entrySet().stream().filter(e -> !executed.contains(e.getKey())).map(java.util.Map.Entry::getValue).toList();
        }
    }

    /** 查询判重兼容大小写、全角和空白差异，原始查询仍保留在诊断中。 */
    private String normalize(String query) {
        return KnowledgeRetrievalPlan.normalize(query);
    }

    /** 仅检查是否还能启动新的补查，不伪称硬中断已经发出的请求。 */
    private boolean expired(long started, long budgetMs) {
        return (ticker.getAsLong() - started) / 1_000_000 >= budgetMs;
    }

    /** 任一调用缺少用量则保持未知，避免将未知成本误记为零。 */
    private Integer sum(Integer first, Integer second) {
        return first == null || second == null ? null : first + second;
    }

    /** 外部异常仅映射固定分类，已知协议错误和用量保持原样。 */
    private <T> T external(KnowledgeAnswerFailure.Code code, Supplier<T> action) {
        try {
            return action.get();
        } catch (KnowledgeAnswerFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new KnowledgeAnswerFailure(code, code.name());
        }
    }
}
