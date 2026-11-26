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
        boolean taskPlanningEnabled = enabled && properties.isTaskPlanningEnabled();
        var assessment = new Assessment(taskPlanningEnabled);
        var seen = new LinkedHashSet<String>();
        var usage = new KnowledgeAnswerModelClient.TokenUsage(0, 0, 0);
        search(baseId, scope, 1, question, trace, evidence);
        queries.add(question);
        seen.add(normalize(question));
        if (evidence.isEmpty()) return stop(trace, NO_MATCH, evidence, usage, assessment, queries);
        if (!enabled) return stop(trace, SINGLE_PASS, evidence, usage, assessment, queries);
        for (int round = 2; round <= 3; round++) {
            if (expired(started, budgetMs)) return stop(trace, TIME_BUDGET, evidence, usage, assessment, queries);
            var planned = planNext(model, question, scope, evidence, queries, round - 1, taskPlanningEnabled, trace);
            usage = addUsage(usage, planned);
            var plan = planned.value();
            assessment.accept(plan, queries.size());
            if (plan.decision() != KnowledgeRetrievalPlan.Decision.SEARCH)
                return stop(trace, KnowledgeRetrievalRun.StopReason.valueOf(plan.decision().name()), evidence, usage, assessment, queries);
            var scheduled = plan.queries();
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
            if (evidence.size() == before)
                return stop(trace, NO_NEW_EVIDENCE, evidence, usage, assessment, queries);
        }
        return stop(trace, ROUND_BUDGET, evidence, usage, assessment, queries);
    }

    /** 多范围先联合初检，未召回地区各补查至多一次，再用整题剩余预算补事实关系。 */
    public Collected collectScopes(Long baseId, ResolvedChatModel model, String question, List<String> scopes,
                                   KnowledgeAnswerTrace trace) {
        KnowledgeScopes.checked(scopes);
        long started = ticker.getAsLong();
        long budgetMs = properties.getSchedulingBudgetSeconds() * 1000L;
        boolean enabled = properties.isEnabled();
        trace.scopes(scopes);
        trace.retrievalStarted(enabled, budgetMs);
        var evidence = new LinkedHashMap<Key, KnowledgeAnswerReport.Evidence>();
        var queries = new ArrayList<String>();
        var attempted = new LinkedHashSet<String>();
        var seen = new LinkedHashSet<String>();
        var usage = new KnowledgeAnswerModelClient.TokenUsage(0, 0, 0);
        var assessment = new Assessment(false);
        searchScopes(baseId, scopes, 1, question, trace, evidence);
        queries.add(question);
        seen.add(scopes + ":" + normalize(question));
        if (evidence.isEmpty()) return stop(trace, NO_MATCH, evidence, usage, assessment, queries);
        if (!enabled) return stop(trace, SINGLE_PASS, evidence, usage, assessment, queries);
        for (int round = 2; round <= 3; round++) {
            if (expired(started, budgetMs)) return stop(trace, TIME_BUDGET, evidence, usage, assessment, queries);
            var missing = scopes.stream().filter(scope -> !attempted.contains(scope)
                    && evidence.values().stream().noneMatch(e -> scope.equals(e.metadata().applicabilityScope())))
                    .limit(2).toList();
            var tasks = new ArrayList<ScopeSearch>();
            if (!missing.isEmpty()) {
                for (var scope : missing) tasks.add(new ScopeSearch(question, List.of(scope)));
            } else {
                var planned = planNext(model, question, KnowledgeScopes.describe(scopes), evidence, queries, round - 1, false, trace);
                usage = addUsage(usage, planned);
                if (planned.value().decision() != KnowledgeRetrievalPlan.Decision.SEARCH)
                    return stop(trace, KnowledgeRetrievalRun.StopReason.valueOf(planned.value().decision().name()),
                            evidence, usage, assessment, queries);
                for (var query : planned.value().queries()) tasks.add(new ScopeSearch(query, scopes));
            }
            int before = evidence.size();
            int executed = 0;
            for (var task : tasks) {
                if (!seen.add(task.scopes() + ":" + normalize(task.query()))) continue;
                if (expired(started, budgetMs)) return stop(trace, TIME_BUDGET, evidence, usage, assessment, queries);
                searchScopes(baseId, task.scopes(), round, task.query(), trace, evidence);
                queries.add(task.query());
                if (task.scopes().size() == 1) attempted.add(task.scopes().getFirst());
                executed++;
            }
            if (executed == 0) return stop(trace, REPEATED_QUERY, evidence, usage, assessment, queries);
            // 地区覆盖补查用掉一轮后仍允许处理其余地区或事实缺口，不因一侧没资料吞掉其他任务。
            if (missing.isEmpty() && evidence.size() == before)
                return stop(trace, NO_NEW_EVIDENCE, evidence, usage, assessment, queries);
        }
        return stop(trace, ROUND_BUDGET, evidence, usage, assessment, queries);
    }

    private record ScopeSearch(String query, List<String> scopes) { }

    /** 执行单次规划模型调用、协议校验与诊断记录，统一单范围与多范围的规划入口。 */
    private KnowledgeAnswerModelClient.ModelResult<KnowledgeRetrievalPlan> planNext(
            ResolvedChatModel model, String question, String scopeDescription,
            LinkedHashMap<Key, KnowledgeAnswerReport.Evidence> evidence, List<String> queries,
            int afterRound, boolean taskPlanningEnabled, KnowledgeAnswerTrace trace) {
        return trace.stage(PLANNING, () -> {
            var result = KnowledgeAnswerFailure.wrap(KNOWLEDGE_PLANNING_CALL_FAILED,
                    () -> taskPlanningEnabled
                            ? client.plan(model, question, scopeDescription, List.copyOf(evidence.values()), List.copyOf(queries))
                            : client.planLegacy(model, question, scopeDescription, List.copyOf(evidence.values()), List.copyOf(queries)));
            if (result == null || result.value() == null)
                throw new KnowledgeAnswerFailure(taskPlanningEnabled ? KNOWLEDGE_PLAN_PAYLOAD_INVALID : KNOWLEDGE_PROTOCOL_INVALID, "missing retrieval plan");
            trace.planned(afterRound, result);
            if (taskPlanningEnabled) result.value().validate();
            else result.value().validateLegacy();
            return result;
        });
    }

    /** 联合查询仍只调用一次混合检索，实际过滤集合写入逐次诊断。 */
    private void searchScopes(Long baseId, List<String> scopes, int round, String query, KnowledgeAnswerTrace trace,
            LinkedHashMap<Key, KnowledgeAnswerReport.Evidence> evidence) {
        trace.searchStarted(round, query, scopes);
        executeSearch(baseId, scopes, new KnowledgeSearchRequest(query, null, scopes), trace, evidence);
    }

    /** 每次搜索都绑定同一身份，保存实际召回并按文档与块序号累计完整原文。 */
    private void search(Long baseId, String scope, int round, String query, KnowledgeAnswerTrace trace,
                        LinkedHashMap<Key, KnowledgeAnswerReport.Evidence> evidence) {
        trace.searchStarted(round, query);
        executeSearch(baseId, List.of(scope), new KnowledgeSearchRequest(query, scope), trace, evidence);
    }

    /** 统一执行一次混合检索并合并累计证据，失败时仍将有效检索摘要写入诊断。 */
    private void executeSearch(Long baseId, List<String> scopes, KnowledgeSearchRequest request,
            KnowledgeAnswerTrace trace, LinkedHashMap<Key, KnowledgeAnswerReport.Evidence> evidence) {
        trace.stage(RETRIEVAL, () -> {
            var response = KnowledgeAnswerFailure.wrap(KNOWLEDGE_RETRIEVAL_FAILED,
                    () -> retrieval.search(baseId, request));
            List<String> added = List.of();
            try {
                added = mergeScopes(baseId, scopes, response, evidence, trace);
                trace.evidence(List.copyOf(evidence.values()));
                return response;
            } finally {
                if (response != null && response.results() != null) trace.searchFinished(response, added);
            }
        });
    }

    /** 每个候选都核对本次实际过滤集合，保持稳定编号及累计证据预算。 */
    private List<String> mergeScopes(Long baseId, List<String> scopes, KnowledgeSearchResponse response,
            LinkedHashMap<Key, KnowledgeAnswerReport.Evidence> evidence, KnowledgeAnswerTrace trace) {
        if (response == null || response.results() == null || !Objects.equals(baseId, response.knowledgeBaseId()))
            throw new KnowledgeAnswerFailure(KNOWLEDGE_EVIDENCE_CHECK_FAILED, "invalid retrieval identity");
        var merged = new LinkedHashMap<>(evidence);
        var added = new ArrayList<String>();
        for (var hit : response.results()) {
            if (hit.documentId() == null || hit.metadata() == null || hit.content() == null || hit.content().isBlank()
                    || !(scopes.contains(hit.metadata().applicabilityScope()) || "ALL".equals(hit.metadata().applicabilityScope())))
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

    /** 请求内只保存最近一次单列表计划，用实际执行记录标明预算中断的待查任务。 */
    private static class Assessment {
        private final boolean enabled;
        private KnowledgeRetrievalPlan plan;
        private int queryCount;

        /** 默认链路不注入候选的用户条件和任务约束。 */
        private Assessment(boolean enabled) { this.enabled = enabled; }

        /** 每批最多两项且在下一规划前执行，只有预算中断时留下未执行任务。 */
        private void accept(KnowledgeRetrievalPlan value, int count) {
            plan = value;
            queryCount = count;
        }

        /** 查询成功返回才视为执行，重复查询沿用此前实际记录，不新增核查账本。 */
        private List<String> pending(List<String> queries) {
            if (!enabled || plan == null) return List.of();
            var executed = queries.stream().map(KnowledgeRetrievalPlan::normalize).collect(java.util.stream.Collectors.toSet());
            return plan.queries().stream().filter(q -> !executed.contains(KnowledgeRetrievalPlan.normalize(q))).distinct().toList();
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

    /** 累加单次规划模型调用的已知用量，任一字段缺失保持未知。 */
    private KnowledgeAnswerModelClient.TokenUsage addUsage(KnowledgeAnswerModelClient.TokenUsage current,
            KnowledgeAnswerModelClient.ModelResult<?> next) {
        return new KnowledgeAnswerModelClient.TokenUsage(sum(current.promptTokens(), next.promptTokens()),
                sum(current.completionTokens(), next.completionTokens()), sum(current.totalTokens(), next.totalTokens()));
    }

    /** 任一调用缺少用量则保持未知，避免将未知成本误记为零。 */
    private Integer sum(Integer first, Integer second) {
        return first == null || second == null ? null : first + second;
    }
}
