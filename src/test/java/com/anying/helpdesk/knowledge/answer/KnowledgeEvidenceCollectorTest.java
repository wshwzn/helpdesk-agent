package com.anying.helpdesk.knowledge.answer;

import com.anying.helpdesk.knowledge.answer.trace.KnowledgeAnswerTrace;
import com.anying.helpdesk.knowledge.dto.*;
import com.anying.helpdesk.knowledge.service.KnowledgeRetrievalService;
import com.anying.helpdesk.modelconfig.factory.ResolvedChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.chat.model.ChatModel;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.anying.helpdesk.knowledge.answer.KnowledgeRetrievalRun.StopReason.*;

class KnowledgeEvidenceCollectorTest {
    private final KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
    private final KnowledgeAnswerModelClient client = mock(KnowledgeAnswerModelClient.class);
    private final KnowledgeMultihopProperties properties = new KnowledgeMultihopProperties();
    private final AtomicLong nanos = new AtomicLong();
    private final KnowledgeEvidenceCollector collector = new KnowledgeEvidenceCollector(retrieval, client, properties, nanos::get);
    private final ResolvedChatModel model = new ResolvedChatModel(1L, "test", false, mock(ChatModel.class));
    private final KnowledgeAnswerTrace trace = new KnowledgeAnswerTrace("test", 1L, 2L, "计划额度", "SH",
            model, Clock.systemUTC(), ignored -> { });

    /** 验证链式和并列补查累计同一批证据、保留稳定编号并严格限制调用次数。 */
    @Test @DisplayName("三轮五次搜索绑定范围并保留中间关系及新增冲突来源")
    void accumulatesAndBoundsSearches() {
        when(retrieval.search(eq(7L), any())).thenReturn(response(hit(1, "计划对应R7")),
                response(hit(1, "计划对应R7"), hit(2, "R7对应L2")), response(hit(3, "十五日提交")),
                response(hit(4, "L2上海680元")), response(hit(4, "L2上海680元"), hit(5, "L2上海700元")));
        when(client.plan(any(), any(), any(), any(), any())).thenReturn(searchPlan("R7等级", "提交期限"), searchPlan("L2上海额度", "L2上海额度通知"));
        var result = collect();
        assertThat(result.evidence()).extracting(KnowledgeAnswerReport.Evidence::id).containsExactly("E1", "E2", "E3", "E4", "E5");
        assertThat(result.planningUsage().totalTokens()).isEqualTo(24);
        var run = trace.snapshot().retrievalRun();
        assertThat(run.stopReason()).isEqualTo(ROUND_BUDGET);
        assertThat(run.searches()).extracting(KnowledgeRetrievalRun.Search::round).containsExactly(1, 2, 2, 3, 3);
        assertThat(run.searches().getLast().addedEvidenceIds()).containsExactly("E5");
        assertThat(trace.snapshot().retrieval().results()).hasSize(1);
        verify(retrieval, times(5)).search(eq(7L), argThat(request -> request.applicabilityScope().equals("SH")));
        verify(client).plan(eq(model), eq("计划额度"), eq("SH"), argThat(items -> items.size() == 3), eq(List.of("计划额度", "R7等级", "提交期限")));
    }

    /** 直接问题充分、真实需用户条件、穷尽资料均由不同原因停止，不绕过最终核验。 */
    @ParameterizedTest @EnumSource(value = KnowledgeRetrievalPlan.Decision.class, names = {"SUFFICIENT", "NEEDS_USER_INPUT", "EXHAUSTED"})
    @DisplayName("规划明确停止时不继续搜索")
    void plannedStops(KnowledgeRetrievalPlan.Decision decision) {
        when(retrieval.search(any(), any())).thenReturn(response(hit(1, "资料")));
        when(client.plan(any(), any(), any(), any(), any())).thenReturn(new KnowledgeAnswerModelClient.ModelResult<>(
                new KnowledgeRetrievalPlan(decision, decision == KnowledgeRetrievalPlan.Decision.SUFFICIENT ? List.of() : List.of("缺少条件"), List.of()), 10, 2, 12));
        collect();
        assertThat(trace.snapshot().retrievalRun().stopReason().name()).isEqualTo(decision.name());
        verify(retrieval).search(any(), any());
    }

    /** 环路改写没有新增片段时结束，不能不断靠新措辞消耗搜索预算。 */
    @Test @DisplayName("循环资料无新增证据时停止")
    void stopsWithoutNewEvidence() {
        when(retrieval.search(any(), any())).thenReturn(response(hit(1, "A参照B"), hit(2, "B参照A")));
        when(client.plan(any(), any(), any(), any(), any())).thenReturn(searchPlan("B指引金额"));
        collect();
        assertThat(trace.snapshot().retrievalRun().stopReason()).isEqualTo(NO_NEW_EVIDENCE);
        verify(client).plan(any(), any(), any(), any(), any());
        verify(retrieval, times(2)).search(any(), any());
    }

    /** 空白等同义格式不应产生新请求，避免模型反复搜索同一问题。 */
    @Test @DisplayName("重复查询规范化后被跳过")
    void stopsRepeatedQueries() {
        when(retrieval.search(any(), any())).thenReturn(response(hit(1, "资料")));
        when(client.plan(any(), any(), any(), any(), any())).thenReturn(searchPlan("  计划  额度 ", "计划额度"));
        collect();
        assertThat(trace.snapshot().retrievalRun().stopReason()).isEqualTo(REPEATED_QUERY);
        verify(retrieval).search(any(), any());
    }

    /** 时间预算在发起下一调用之前检查，当前模型返回后也不能越界开始补查。 */
    @Test @DisplayName("规划消耗完时间预算后不再搜索")
    void stopsOnElapsedBudget() {
        when(retrieval.search(any(), any())).thenReturn(response(hit(1, "资料")));
        when(client.plan(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            nanos.set(90_000_000_000L);
            return searchPlan("R7等级");
        });
        var result = collect();
        assertThat(result.planningUsage().totalTokens()).isEqualTo(12);
        assertThat(trace.snapshot().retrievalRun().stopReason()).isEqualTo(TIME_BUDGET);
        verify(retrieval).search(any(), any());
    }

    /** 超预算批次不能只截取前几条而丢掉冲突，对原有证据也不能半批提交。 */
    @Test @DisplayName("证据超限明确失败且保留实际截断标记")
    void rejectsOversizeEvidence() {
        when(retrieval.search(any(), any())).thenReturn(response(IntStream.rangeClosed(1, 51)
                .mapToObj(i -> hit(i, "资料内容")).toArray(KnowledgeSearchResultResponse[]::new)));
        assertThatThrownBy(this::collect).hasMessage("knowledge evidence exceeds budget");
        assertThat(trace.snapshot().retrievalRun().stopReason()).isEqualTo(EVIDENCE_BUDGET);
        assertThat(trace.snapshot().retrievalRun().searches().getFirst().truncated()).isTrue();
        assertThat(trace.snapshot().evidence()).isEmpty();
        verifyNoInteractions(client);
    }

    /** 即使片段数不多也不能发送超大正文，超额保持技术失败。 */
    @Test @DisplayName("累计字符超限不进入规划")
    void rejectsOversizeCharacters() {
        when(retrieval.search(any(), any())).thenReturn(response(hit(1, "字".repeat(50001))));
        assertThatThrownBy(this::collect).isInstanceOf(KnowledgeAnswerFailure.class);
        assertThat(trace.snapshot().retrievalRun().stopReason()).isEqualTo(EVIDENCE_BUDGET);
        verifyNoInteractions(client);
    }

    /** 同一片段在补查期间发生内容变化必须中止，避免拼接不同版本。 */
    @Test @DisplayName("补查发现同一块变化时拒绝混用")
    void rejectsChangedChunk() {
        when(retrieval.search(any(), any())).thenReturn(response(hit(1, "旧内容")), response(hit(1, "新内容")));
        when(client.plan(any(), any(), any(), any(), any())).thenReturn(searchPlan("规则"));
        assertThatThrownBy(this::collect).hasMessage("knowledge evidence changed during retrieval");
        assertThat(trace.snapshot().evidence()).extracting(KnowledgeAnswerReport.Evidence::content).containsExactly("旧内容");
    }

    /** 后续检索服务异常不得伪装成搜索穷尽，诊断须保留失败查询。 */
    @Test @DisplayName("补查失败保留阶段和查询且不泄露异常正文")
    void technicalFailureIsNotExhaustion() {
        when(retrieval.search(any(), any())).thenReturn(response(hit(1, "资料"))).thenThrow(new IllegalStateException("secret"));
        when(client.plan(any(), any(), any(), any(), any())).thenReturn(searchPlan("规则"));
        assertThatThrownBy(this::collect).hasMessage("KNOWLEDGE_RETRIEVAL_FAILED");
        assertThat(trace.snapshot().retrievalRun().stopReason()).isEqualTo(TECHNICAL_FAILURE);
        assertThat(trace.snapshot().retrievalRun().searches().getLast().query()).isEqualTo("规则");
        assertThat(trace.snapshot().retrievalRun().searches().getLast().response()).isNull();
    }

    /** 错误规划协议不能被默认当作停止或证据充分，同时保留已发生用量。 */
    @Test @DisplayName("非法规划动作组合失败并保留用量")
    void rejectsInvalidPlan() {
        when(retrieval.search(any(), any())).thenReturn(response(hit(1, "资料")));
        when(client.plan(any(), any(), any(), any(), any())).thenReturn(new KnowledgeAnswerModelClient.ModelResult<>(
                new KnowledgeRetrievalPlan(KnowledgeRetrievalPlan.Decision.SEARCH, List.of(), List.of()), 10, 2, 12));
        assertThatThrownBy(this::collect).hasMessage("invalid retrieval plan");
        assertThat(trace.snapshot().stages().getLast().usage().totalTokens()).isEqualTo(12);
    }

    /** 未配置用量的规划调用不能在客户总用量中被当作零消耗。 */
    @Test @DisplayName("未知规划用量保持未知")
    void preservesUnknownUsage() {
        when(retrieval.search(any(), any())).thenReturn(response(hit(1, "资料")));
        when(client.plan(any(), any(), any(), any(), any())).thenReturn(new KnowledgeAnswerModelClient.ModelResult<>(
                new KnowledgeRetrievalPlan(KnowledgeRetrievalPlan.Decision.SUFFICIENT, List.of(), List.of()), null, null, null));
        assertThat(collect().planningUsage().totalTokens()).isNull();
    }

    /** 模型晚返回不能在已取消运行中继续触发搜索或保存规划。 */
    @Test @DisplayName("规划期间取消后不会执行补查")
    void cancellationPreventsFurtherSearch() {
        when(retrieval.search(any(), any())).thenReturn(response(hit(1, "资料")));
        when(client.plan(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            trace.cancelled();
            return searchPlan("规则");
        });
        assertThatThrownBy(this::collect).isInstanceOf(java.util.concurrent.CancellationException.class);
        assertThat(trace.snapshot().retrievalRun().stopReason()).isEqualTo(CANCELLED);
        assertThat(trace.snapshot().retrievalRun().plans()).isEmpty();
        verify(retrieval).search(any(), any());
    }

    /** 关闭多跳时保留首轮行为，便于同数据对照及故障回退。 */
    @Test @DisplayName("关闭开关不调用规划模型")
    void singlePassSwitch() {
        properties.setEnabled(false);
        when(retrieval.search(any(), any())).thenReturn(response(hit(1, "资料")));
        collect();
        assertThat(trace.snapshot().retrievalRun().stopReason()).isEqualTo(SINGLE_PASS);
        verifyNoInteractions(client);
    }

    /** 返回错库资料是依赖契约错误，不能被纳入累计证据。 */
    @Test @DisplayName("检索响应来自其他库时拒绝")
    void rejectsCrossBaseResponse() {
        when(retrieval.search(any(), any())).thenReturn(new KnowledgeSearchResponse(99L, "问题", 50,
                BigDecimal.ZERO, 5, 1, 1, List.of(hit(1, "资料"))));
        assertThatThrownBy(this::collect).hasMessage("invalid retrieval identity");
    }

    /** 通过固定库和地区执行一次真实编排，外部依赖由桩控制。 */
    private KnowledgeEvidenceCollector.Collected collect() {
        return collector.collect(7L, model, "计划额度", "SH", trace);
    }

    /** 构造具有有效身份和范围的检索片段。 */
    private KnowledgeSearchResultResponse hit(long id, String text) {
        return new KnowledgeSearchResultResponse(1, id, 0, "资料.md", text, 0.1, 1, 1.0, 1, 0.9,
                new KnowledgeDocumentMetadata("source-" + id, "v1", "SH", null, null));
    }

    /** 包装相同知识库下的实际候选列表。 */
    private KnowledgeSearchResponse response(KnowledgeSearchResultResponse... hits) {
        return new KnowledgeSearchResponse(7L, "查询", 50, BigDecimal.ZERO, 5, hits.length, hits.length, List.of(hits));
    }

    /** 提供带已知用量的补查建议以验证调用预算和成本汇总。 */
    private KnowledgeAnswerModelClient.ModelResult<KnowledgeRetrievalPlan> searchPlan(String... queries) {
        return new KnowledgeAnswerModelClient.ModelResult<>(new KnowledgeRetrievalPlan(
                KnowledgeRetrievalPlan.Decision.SEARCH, List.of("目标规则缺口"), List.of(queries)), 10, 2, 12);
    }
}
