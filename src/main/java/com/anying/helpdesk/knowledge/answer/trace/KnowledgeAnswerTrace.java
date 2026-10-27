package com.anying.helpdesk.knowledge.answer.trace;

import com.anying.helpdesk.knowledge.answer.KnowledgeApplicabilityPolicy;
import com.anying.helpdesk.knowledge.answer.KnowledgeRetrievalPlan;
import com.anying.helpdesk.knowledge.answer.KnowledgeRetrievalRun;
import com.anying.helpdesk.knowledge.answer.KnowledgeAnswerContext;

import com.anying.helpdesk.knowledge.answer.KnowledgeAnswerDraft;
import com.anying.helpdesk.knowledge.answer.KnowledgeAnswerSelection;
import com.anying.helpdesk.knowledge.answer.KnowledgeCitationCatalog;
import com.anying.helpdesk.knowledge.answer.KnowledgeAnswerFailure;
import com.anying.helpdesk.knowledge.answer.KnowledgeAnswerModelClient;
import com.anying.helpdesk.knowledge.answer.KnowledgeAnswerReport;
import com.anying.helpdesk.knowledge.dto.KnowledgeSearchResponse;
import com.anying.helpdesk.knowledge.dto.KnowledgeSearchResultResponse;
import com.anying.helpdesk.modelconfig.factory.ResolvedChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.anying.helpdesk.knowledge.answer.trace.KnowledgeAnswerRunSnapshot.*;

/** 单次请求显式传递的诊断上下文，避免在线程切换或并发请求间混用证据。 */
public final class KnowledgeAnswerTrace {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeAnswerTrace.class);
    private final String runId;
    private final Long messageId;
    private final Long conversationId;
    private final String question;
    private final String scope;
    private List<String> scopes;
    private final Long modelConfigId;
    private final String modelName;
    private final Clock clock;
    private final Consumer<KnowledgeAnswerRunSnapshot> writer;
    private final LocalDateTime startedAt;
    private final List<StageResult> stages = new ArrayList<>();
    private Status status = Status.RUNNING;
    private String errorCode;
    private long revision;
    private int writeFailures;
    private LocalDateTime finishedAt;
    private KnowledgeSearchResponse retrieval;
    private boolean retrievalTruncated;
    private List<KnowledgeAnswerReport.Evidence> evidence = List.of();
    private KnowledgeAnswerDraft draft;
    private KnowledgeAnswerSelection selection;
    private KnowledgeCitationCatalog citationCatalog;
    private KnowledgeAnswerModelClient.Review review;
    private KnowledgeAnswerReport.Reason answerReason;
    private long stageStartedNanos;
    private KnowledgeApplicabilityPolicy.Assessment applicability;
    private KnowledgeRetrievalRun retrievalRun;
    private KnowledgeAnswerContext answerContext;
    private final List<AnswerAttempt> answerAttempts = new ArrayList<>();

    /** 保存服务端解析的有效范围，未解析前不冒充已经选定。 */
    public synchronized void scopes(List<String> values) {
        if (status == Status.RUNNING) scopes = List.copyOf(values);
    }

    /** 保存实际搜索结束后的未完成任务和条件分类，不把规划当作引用事实。 */
    public synchronized void answerContext(KnowledgeAnswerContext value) {
        if (status == Status.RUNNING) answerContext = value;
    }

    /** 冻结本次检索调度预算，防止运行中配置变化改变诊断口径。 */
    public synchronized void retrievalStarted(boolean enabled, long budgetMs) {
        if (status != Status.RUNNING) return;
        retrievalRun = new KnowledgeRetrievalRun(enabled, enabled ? 3 : 1, enabled ? 5 : 1,
                50, 50000, budgetMs, List.of(), List.of(), null);
    }

    /** 在外部请求前保存轮次和查询，失败调用也有可定位的输入。 */
    public synchronized void searchStarted(int round, String query) {
        searchStarted(round, query, scopes);
    }

    /** 每个补查记录实际过滤范围，联合查询不伪装为逐地区多次调用。 */
    public synchronized void searchStarted(int round, String query, List<String> searchScopes) {
        if (status != Status.RUNNING) return;
        var searches = new ArrayList<>(retrievalRun.searches());
        searches.add(new KnowledgeRetrievalRun.Search(round, query, null, false, List.of(), List.copyOf(searchScopes)));
        updateRetrievalRun(searches, retrievalRun.plans(), retrievalRun.stopReason());
    }

    /** 限量保存每次实际检索结果；旧 retrieval 字段始终代表首轮，不能覆盖成最后一次查询。 */
    public synchronized void searchFinished(KnowledgeSearchResponse response, List<String> added) {
        if (status != Status.RUNNING) return;
        var first = retrieval;
        boolean firstTruncated = retrievalTruncated;
        retrievalTruncated = false;
        retrieved(response);
        var searches = new ArrayList<>(retrievalRun.searches());
        var pending = searches.getLast();
        searches.set(searches.size() - 1, new KnowledgeRetrievalRun.Search(pending.round(), pending.query(),
                retrieval, retrievalTruncated, List.copyOf(added), pending.scopes()));
        if (first != null) {
            retrieval = first;
            retrievalTruncated = firstTruncated;
        }
        updateRetrievalRun(searches, retrievalRun.plans(), retrievalRun.stopReason());
    }

    /** 保存规划结果和用量；规划文字只供诊断，不成为可引用证据。 */
    public synchronized void planned(int round, KnowledgeAnswerModelClient.ModelResult<KnowledgeRetrievalPlan> result) {
        if (status != Status.RUNNING) return;
        var plans = new ArrayList<>(retrievalRun.plans());
        plans.add(new KnowledgeRetrievalRun.Planning(round, result.value()));
        updateRetrievalRun(retrievalRun.searches(), plans, null);
        usage(new KnowledgeAnswerModelClient.TokenUsage(result.promptTokens(), result.completionTokens(), result.totalTokens()));
    }

    /** 停止原因与最终回答状态分开，预算耗尽不等于技术错误或已经充分。 */
    public synchronized void retrievalStopped(KnowledgeRetrievalRun.StopReason reason) {
        if (status == Status.RUNNING && retrievalRun != null)
            updateRetrievalRun(retrievalRun.searches(), retrievalRun.plans(), reason);
    }

    /** 用不可变列表替换多轮快照，避免后续修改影响已经送往存储的对象。 */
    private void updateRetrievalRun(List<KnowledgeRetrievalRun.Search> searches,
            List<KnowledgeRetrievalRun.Planning> plans, KnowledgeRetrievalRun.StopReason stop) {
        retrievalRun = new KnowledgeRetrievalRun(retrievalRun.enabled(), retrievalRun.maxRounds(),
                retrievalRun.maxSearches(), retrievalRun.maxEvidence(), retrievalRun.maxCharacters(),
                retrievalRun.schedulingBudgetMs(), List.copyOf(searches), List.copyOf(plans), stop);
    }

    /** 保存条件配置、主题路由和续答来源，并将已发生的路由用量计入独立阶段。 */
    public synchronized void applicability(KnowledgeApplicabilityPolicy policy,
            KnowledgeAnswerModelClient.ModelResult<KnowledgeApplicabilityPolicy.Route> result,
            Long continuationId, boolean routingAttempted) {
        if (status != Status.RUNNING) return;
        applicability = new KnowledgeApplicabilityPolicy.Assessment(
                policy, result == null ? null : result.value(), continuationId, routingAttempted);
        if (result != null) usage(new KnowledgeAnswerModelClient.TokenUsage(
                result.promptTokens(), result.completionTokens(), result.totalTokens()));
    }


    /** 为已经接受的助手消息建立独立诊断上下文，身份字段均来自服务端。 */
    public KnowledgeAnswerTrace(String runId, Long messageId, Long conversationId, String question, String scope,
                                ResolvedChatModel model, Clock clock, Consumer<KnowledgeAnswerRunSnapshot> writer) {
        this.runId = runId;
        this.messageId = messageId;
        this.conversationId = conversationId;
        this.question = question;
        this.scope = scope;
        this.scopes = scope == null ? List.of() : List.of(scope);
        this.modelConfigId = model.configId();
        this.modelName = model.modelName();
        this.clock = clock;
        this.writer = writer;
        this.startedAt = LocalDateTime.now(clock);
    }

    /** 保存初始运行记录；诊断存储不可用时仍允许业务链路继续。 */
    public synchronized void start() {
        flush();
    }

    /** 记录阶段起止和安全错误码，业务调用始终在诊断锁及数据库事务之外执行。 */
    public <T> T stage(Stage stage, Supplier<T> action) {
        begin(stage);
        try {
            T value = action.get();
            if (!completeStage()) {
                throw new CancellationException("knowledge run already cancelled");
            }
            return value;
        } catch (RuntimeException failure) {
            failed(failure);
            throw failure;
        }
    }

    /** 限量保存实际召回，超出预算时明确标记截断，不把诊断截断传回问答链路。 */
    public synchronized void retrieved(KnowledgeSearchResponse response) {
        if (status != Status.RUNNING) return;
        List<KnowledgeSearchResultResponse> saved = new ArrayList<>();
        int characters = 0;
        for (var item : response.results()) {
            int length = item.content() == null ? 0 : item.content().length();
            if (saved.size() >= 50 || characters + length > 50000) {
                retrievalTruncated = true;
                break;
            }
            saved.add(item);
            characters += length;
        }
        retrieval = new KnowledgeSearchResponse(response.knowledgeBaseId(), response.query(), response.candidateLimit(),
                response.vectorMinSimilarity(), response.resultLimit(), response.bm25CandidateCount(),
                response.vectorCandidateCount(), List.copyOf(saved));
    }

    /** 关联模型使用的 E 编号与原文，保持与本轮草案完全相同的证据身份。 */
    public synchronized void evidence(List<KnowledgeAnswerReport.Evidence> value) {
        if (status == Status.RUNNING) evidence = List.copyOf(value);
    }

    /** 保存模型原始片段选择及用量，即使后续引用解析失败也可诊断。 */
    public synchronized void selected(KnowledgeAnswerModelClient.ModelResult<KnowledgeAnswerSelection> value) {
        if (status != Status.RUNNING) return;
        selection = value.value();
        draft = null;
        review = null;
        usage(new KnowledgeAnswerModelClient.TokenUsage(value.promptTokens(), value.completionTokens(), value.totalTokens()));
    }

    /** 保存本轮目录和原文偏移，供管理员核对模型的片段选择。 */
    public synchronized void catalog(KnowledgeCitationCatalog value) {
        if (status == Status.RUNNING) citationCatalog = value;
    }

    /** 保存后端完成引用解析与结构校验的草案，语义仍须复核。 */
    public synchronized void resolved(KnowledgeAnswerDraft value) {
        if (status == Status.RUNNING) draft = value;
    }

    /** 保存复核布尔结论及可选原因；原因只是模型判断而非人工金标准。 */
    public synchronized void reviewed(KnowledgeAnswerModelClient.ModelResult<KnowledgeAnswerModelClient.Review> value) {
        if (status != Status.RUNNING) return;
        review = value.value();
        answerAttempts.add(new AnswerAttempt(answerAttempts.size() + 1, selection, draft, review));
        usage(new KnowledgeAnswerModelClient.TokenUsage(value.promptTokens(), value.completionTokens(), value.totalTokens()));
    }

    /** 记录无库、无命中或已核验结果，未调用阶段保持缺席而不是伪造零用量。 */
    public synchronized void answerReason(KnowledgeAnswerReport.Reason reason) {
        if (status == Status.RUNNING) answerReason = reason;
    }

    /** 只有最终客户消息成功落库后才允许将整次运行标记为成功。 */
    public synchronized void succeeded() {
        finish(Status.SUCCEEDED, null);
    }

    /** 保留首次业务失败，诊断写入异常不会替换或附加到原始异常。 */
    public synchronized void failed(Throwable failure) {
        if (status != Status.RUNNING) return;
        String code = failure instanceof KnowledgeAnswerFailure known ? known.errorCode() : fallbackCode();
        if (failure instanceof KnowledgeAnswerFailure known && known.usage() != null) usage(known.usage());
        finish(Status.FAILED, code);
    }

    /** 取消是终态，迟到的模型响应不能追加草案或把运行翻转为成功。 */
    public synchronized void cancelled() {
        finish(Status.CANCELLED, "STREAM_CANCELLED");
    }

    /** 导出不可变列表快照，供持久化与确定性测试使用。 */
    public synchronized KnowledgeAnswerRunSnapshot snapshot() {
        return new KnowledgeAnswerRunSnapshot(7, runId, messageId, conversationId, question, scope,
                modelConfigId, modelName, status, errorCode, revision, writeFailures,
                startedAt, LocalDateTime.now(clock), finishedAt, retrieval, retrievalTruncated,
                evidence, draft, review, answerReason, List.copyOf(stages), citationCatalog, selection, applicability, retrievalRun, answerContext, scopes,
                List.copyOf(answerAttempts));
    }

    /** 在调用前持久化运行中阶段，进程中断时仍可定位最后进入的位置。 */
    private synchronized void begin(Stage stage) {
        if (status != Status.RUNNING) throw new CancellationException("knowledge run already finished");
        stages.add(new StageResult(stage, StageStatus.RUNNING, LocalDateTime.now(clock), null, null, null));
        stageStartedNanos = System.nanoTime();
        flush();
    }

    /** 用单调时钟记录阶段耗时，取消后的迟到完成不能修改快照。 */
    private synchronized boolean completeStage() {
        if (status != Status.RUNNING) return false;
        closeStage(StageStatus.SUCCEEDED, null);
        flush();
        return true;
    }

    /** 仅更新当前阶段用量，缺失字段保留 null，失败调用不伪装成零成本。 */
    private void usage(KnowledgeAnswerModelClient.TokenUsage value) {
        if (stages.isEmpty()) return;
        int last = stages.size() - 1;
        var old = stages.get(last);
        stages.set(last, new StageResult(old.stage(), old.status(), old.startedAt(), old.durationMs(), old.errorCode(), value));
    }

    /** 结束活跃阶段，已经结束的阶段保留原结果。 */
    private void closeStage(StageStatus stageStatus, String code) {
        if (stages.isEmpty()) return;
        int last = stages.size() - 1;
        var old = stages.get(last);
        if (old.status() != StageStatus.RUNNING) return;
        stages.set(last, new StageResult(old.stage(), stageStatus, old.startedAt(),
                Math.max(0L, (System.nanoTime() - stageStartedNanos) / 1_000_000), code, old.usage()));
    }

    /** 以首次终态为准，避免取消、失败和完成之间互相覆盖。 */
    private void finish(Status value, String code) {
        if (status != Status.RUNNING) return;
        if (retrievalRun != null && retrievalRun.stopReason() == null)
            retrievalStopped(value == Status.CANCELLED ? KnowledgeRetrievalRun.StopReason.CANCELLED
                    : KnowledgeRetrievalRun.StopReason.TECHNICAL_FAILURE);
        closeStage(value == Status.CANCELLED ? StageStatus.CANCELLED
                : value == Status.SUCCEEDED ? StageStatus.SUCCEEDED : StageStatus.FAILED, code);
        status = value;
        errorCode = code;
        finishedAt = LocalDateTime.now(clock);
        flush();
    }

    /** 未分类异常只保留固定阶段，不保存厂商错误文本或堆栈。 */
    private String fallbackCode() {
        if (stages.isEmpty()) return "KNOWLEDGE_ANSWER_FAILED";
        return switch (stages.getLast().stage()) {
            case APPLICABILITY -> "KNOWLEDGE_APPLICABILITY_CALL_FAILED";
            case RETRIEVAL -> "KNOWLEDGE_RETRIEVAL_FAILED";
            case PLANNING -> "KNOWLEDGE_PLANNING_CALL_FAILED";
            case DRAFT -> "KNOWLEDGE_DRAFT_CALL_FAILED";
            case REVISION -> "KNOWLEDGE_REVISION_CALL_FAILED";
            case VALIDATION -> "KNOWLEDGE_STRUCTURE_INVALID";
            case REVIEW -> "KNOWLEDGE_REVIEW_CALL_FAILED";
            case EVIDENCE_RECHECK -> "KNOWLEDGE_EVIDENCE_CHECK_FAILED";
            case MESSAGE_PERSISTENCE -> "KNOWLEDGE_MESSAGE_SAVE_FAILED";
        };
    }

    /** 写入失败只记录固定告警与次数，后续阶段继续尝试保存完整快照。 */
    private void flush() {
        revision++;
        try {
            writer.accept(snapshot());
        } catch (RuntimeException failure) {
            writeFailures++;
            log.warn("Knowledge diagnostic write failed for run {} ({})", runId, failure.getClass().getSimpleName());
        }
    }
}
