package com.anying.helpdesk.knowledge.answer;

import com.anying.helpdesk.knowledge.domain.KnowledgeDocument;
import com.anying.helpdesk.knowledge.dto.KnowledgeBaseResponse;
import com.anying.helpdesk.knowledge.dto.KnowledgeDocumentMetadata;
import com.anying.helpdesk.knowledge.dto.KnowledgeSearchRequest;
import com.anying.helpdesk.knowledge.dto.KnowledgeSearchResponse;
import com.anying.helpdesk.knowledge.dto.KnowledgeSearchResultResponse;
import com.anying.helpdesk.knowledge.service.KnowledgeBaseService;
import com.anying.helpdesk.knowledge.service.KnowledgeDocumentEligibilityService;
import com.anying.helpdesk.knowledge.service.KnowledgeRetrievalService;
import com.anying.helpdesk.modelconfig.factory.ResolvedChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TrustedKnowledgeAnswerServiceTest {
    private static final String QUESTION = "上海住宿额度是多少？";
    private static final String QUOTE = "上海员工住宿上限为每晚600元。";
    private static final String OTHER_QUOTE = "上海员工住宿上限为每晚650元。";
    private final KnowledgeBaseService bases = mock(KnowledgeBaseService.class);
    private final KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
    private final KnowledgeDocumentEligibilityService eligibility = mock(KnowledgeDocumentEligibilityService.class);
    private final KnowledgeAnswerModelClient client = mock(KnowledgeAnswerModelClient.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-18T00:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private final ResolvedChatModel model = new ResolvedChatModel(1L, "test", true, mock(ChatModel.class));
    private final TrustedKnowledgeAnswerService service = new TrustedKnowledgeAnswerService(
            bases, retrieval, eligibility, client, new KnowledgeAnswerGuard(), clock);

    /** 默认提供两份当前有效资料，以检查复核是否覆盖未被草案选中的候选。 */
    @BeforeEach
    void setup() {
        when(bases.findDefault()).thenReturn(Optional.of(new KnowledgeBaseResponse(1L, "制度库", null,
                50, new BigDecimal("0.65"), 5, true, 2, 0, null, null)));
        when(retrieval.search(eq(1L), any())).thenReturn(search(List.of(hit(10L, QUOTE), hit(20L, OTHER_QUOTE))));
        when(eligibility.findEligible(eq(1L), eq("SH"), any())).thenReturn(Map.of(10L, document(10L), 20L, document(20L)));
        when(client.review(eq(model), eq(QUESTION), eq("SH"), any(), any()))
                .thenReturn(new KnowledgeAnswerModelClient.ModelResult<>(new KnowledgeAnswerModelClient.Review(true), 20, 5, 25));
    }

    /** 检查确定性校验通过后，完整证据仍会送入复核且用量正确汇总。 */
    @Test
    @DisplayName("正常回答保留实际引用、版本快照和两次调用的用量")
    void answerWithVerifiedReferences() {
        var draft = draft(KnowledgeAnswerDraft.Status.ANSWERABLE);
        propose(draft);
        var result = service.answer(model, QUESTION, "SH");
        assertThat(result.content()).contains("600", "[E1]");
        assertThat(result.totalTokens()).isEqualTo(40);
        assertThat(result.knowledgeAnswer().citations()).hasSize(1)
                .first().satisfies(citation -> assertThat(citation.documentId()).isEqualTo(10L));
        assertThat(result.knowledgeAnswer().evidence()).hasSize(2);
        assertThat(result.knowledgeAnswer().checkedAt()).isEqualTo(LocalDateTime.of(2026, 10, 18, 8, 0));
        verify(retrieval).search(1L, new KnowledgeSearchRequest(QUESTION, "SH"));
        verify(client).review(eq(model), eq(QUESTION), eq("SH"), argThat(items -> items.size() == 2), eq(draft));
    }

    /** 四种状态均允许保留有据部分，冲突状态必须展示两个真实来源。 */
    @ParameterizedTest
    @EnumSource(KnowledgeAnswerDraft.Status.class)
    @DisplayName("四种业务状态经过同一核验链路，冲突不会被自动裁决")
    void supportsAllBusinessStates(KnowledgeAnswerDraft.Status status) {
        propose(draft(status));
        var result = service.answer(model, QUESTION, "SH");
        assertThat(result.knowledgeAnswer().answer().status()).isEqualTo(status);
        if (status == KnowledgeAnswerDraft.Status.CONFLICT) {
            assertThat(result.content()).contains("疑似冲突", "600", "650", "[E1]", "[E2]");
            assertThat(result.knowledgeAnswer().citations()).hasSize(2);
        }
    }

    /** 将真实协议适配器与编排串起来，检查两个模型响应经解析后仍能正确展示冲突。 */
    @Test
    @DisplayName("检索到两份相反资料时完整协议链路保留冲突双方")
    void completesConflictThroughRealProtocolAdapter() throws Exception {
        var json = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        when(model.chatModel().call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage(
                        json.writeValueAsString(draft(KnowledgeAnswerDraft.Status.CONFLICT)))))),
                new ChatResponse(List.of(new Generation(new AssistantMessage("{\"approved\":true}")))));
        var realAdapterService = new TrustedKnowledgeAnswerService(bases, retrieval, eligibility,
                new KnowledgeAnswerModelClient(json), new KnowledgeAnswerGuard(), clock);
        var result = realAdapterService.answer(model, QUESTION, "SH");
        assertThat(result.knowledgeAnswer().answer().status()).isEqualTo(KnowledgeAnswerDraft.Status.CONFLICT);
        assertThat(result.content()).contains("600", "650", "[E1]", "[E2]");
        verify(model.chatModel(), times(2)).call(any(Prompt.class));
    }

    /** 伪造的证据编号不得进入复核或最终输出。 */
    @Test
    @DisplayName("模型编造证据编号时直接失败")
    void rejectsFabricatedEvidence() {
        propose(new KnowledgeAnswerDraft(KnowledgeAnswerDraft.Status.ANSWERABLE,
                List.of(claim("E999", QUOTE)), List.of(), List.of()));
        assertThatThrownBy(() -> service.answer(model, QUESTION, "SH")).hasMessage("unknown evidence id");
        verify(client, never()).review(any(), any(), any(), any(), any());
    }

    /** 真编号不能为篡改金额的假引文提供合法性。 */
    @Test
    @DisplayName("真实证据编号搭配伪造引文也不能通过")
    void rejectsAlteredQuote() {
        propose(new KnowledgeAnswerDraft(KnowledgeAnswerDraft.Status.ANSWERABLE,
                List.of(claim("E1", "上海员工住宿上限为每晚900元。")), List.of(), List.of()));
        assertThatThrownBy(() -> service.answer(model, QUESTION, "SH")).hasMessage("quote is absent from evidence");
    }

    /** 同一文档的引用不能伪装成两个来源冲突。 */
    @Test
    @DisplayName("同一来源冒充冲突双方时拒绝输出")
    void rejectsSingleDocumentConflict() {
        propose(new KnowledgeAnswerDraft(KnowledgeAnswerDraft.Status.CONFLICT, List.of(), List.of(),
                List.of(new KnowledgeAnswerDraft.Conflict("额度", claim("E1", QUOTE), claim("E1", QUOTE)))));
        assertThatThrownBy(() -> service.answer(model, QUESTION, "SH"))
                .hasMessage("conflict requires distinct documents");
    }

    /** 引文命中不代表结论正确，独立语义复核仍能否决草案。 */
    @Test
    @DisplayName("复核发现曲解原文或遗漏冲突时阻止输出")
    void rejectsSemanticReviewFailure() {
        propose(draft(KnowledgeAnswerDraft.Status.ANSWERABLE));
        when(client.review(any(), any(), any(), any(), any())).thenReturn(
                new KnowledgeAnswerModelClient.ModelResult<>(new KnowledgeAnswerModelClient.Review(false), 2, 1, 3));
        assertThatThrownBy(() -> service.answer(model, QUESTION, "SH")).hasMessage("knowledge answer review rejected");
        verifyNoInteractions(eligibility);
    }

    /** 生成期间撤回或到期的证据不能继续支撑完成回答。 */
    @Test
    @DisplayName("复核之后证据已不可用时终止本轮生成")
    void rejectsEvidenceInvalidatedDuringReview() {
        propose(draft(KnowledgeAnswerDraft.Status.ANSWERABLE));
        when(eligibility.findEligible(any(), any(), any())).thenReturn(Map.of());
        assertThatThrownBy(() -> service.answer(model, QUESTION, "SH"))
                .hasMessage("knowledge evidence changed during generation");
    }

    /** 没有候选时返回可解释的不足状态，不向外部模型发送空问题证据。 */
    @Test
    @DisplayName("没有命中时跳过模型并返回 NO_MATCH")
    void noMatchSkipsModel() {
        when(retrieval.search(any(), any())).thenReturn(search(List.of()));
        var result = service.answer(model, QUESTION, "SH");
        assertThat(result.knowledgeAnswer().reason()).isEqualTo(KnowledgeAnswerReport.Reason.NO_MATCH);
        assertThat(result.totalTokens()).isZero();
        verifyNoInteractions(client, eligibility);
    }

    /** 未配置与未命中分开记录，方便演示和排查配置问题。 */
    @Test
    @DisplayName("未配置默认知识库时跳过检索及模型")
    void noDefaultSkipsRetrieval() {
        when(bases.findDefault()).thenReturn(Optional.empty());
        var result = service.answer(model, QUESTION, "SH");
        assertThat(result.knowledgeAnswer().reason()).isEqualTo(KnowledgeAnswerReport.Reason.NOT_CONFIGURED);
        verifyNoInteractions(retrieval, client, eligibility);
    }

    /** 检索故障保持技术失败语义，不能混进无依据业务结果。 */
    @Test
    @DisplayName("检索故障不伪装为没有依据")
    void retrievalFailureIsNotRefusal() {
        when(retrieval.search(any(), any())).thenThrow(new IllegalStateException("search unavailable"));
        assertThatThrownBy(() -> service.answer(model, QUESTION, "SH"))
                .isInstanceOfSatisfying(KnowledgeAnswerFailure.class,
                        error -> assertThat(error.errorCode()).isEqualTo("KNOWLEDGE_RETRIEVAL_FAILED"))
                .hasMessage("KNOWLEDGE_RETRIEVAL_FAILED");
        verifyNoInteractions(client);
    }

    /** 任一模型调用缺失用量时保持未知，不低报总消耗。 */
    @Test
    @DisplayName("缺失模型用量时汇总仍为未知")
    void unknownUsageStaysUnknown() {
        when(client.draft(any(), any(), any(), any())).thenReturn(new KnowledgeAnswerModelClient.ModelResult<>(
                draft(KnowledgeAnswerDraft.Status.ANSWERABLE), null, null, null));
        assertThat(service.answer(model, QUESTION, "SH").totalTokens()).isNull();
    }

    /** 分开记录草案调用与复核调用故障，不泄露厂商异常携带的信息。 */
    @Test
    @DisplayName("外部模型故障按阶段分类并丢弃原始异常正文")
    void classifiesExternalModelFailures() {
        when(client.draft(any(), any(), any(), any())).thenThrow(new IllegalStateException("private provider secret"));
        assertThatThrownBy(() -> service.answer(model, QUESTION, "SH"))
                .isInstanceOfSatisfying(KnowledgeAnswerFailure.class, failure -> {
                    assertThat(failure.errorCode()).isEqualTo("KNOWLEDGE_DRAFT_CALL_FAILED");
                    assertThat(failure.getMessage()).doesNotContain("private provider secret");
                    assertThat(failure.getCause()).isNull();
                });
        doReturn(new KnowledgeAnswerModelClient.ModelResult<>(draft(KnowledgeAnswerDraft.Status.ANSWERABLE), 1, 1, 2))
                .when(client).draft(any(), any(), any(), any());
        when(client.review(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("private review secret"));
        assertThatThrownBy(() -> service.answer(model, QUESTION, "SH"))
                .isInstanceOfSatisfying(KnowledgeAnswerFailure.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo("KNOWLEDGE_REVIEW_CALL_FAILED"));
    }

    /** 精确协议异常穿过阶段包装后仍保留原来的分类。 */
    @Test
    @DisplayName("模型协议错误不被笼统重标记为网络调用失败")
    void preservesProtocolFailure() {
        when(client.draft(any(), any(), any(), any())).thenThrow(new KnowledgeAnswerFailure(
                KnowledgeAnswerFailure.Code.KNOWLEDGE_PROTOCOL_INVALID, "invalid protocol"));
        assertThatThrownBy(() -> service.answer(model, QUESTION, "SH"))
                .isInstanceOfSatisfying(KnowledgeAnswerFailure.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo("KNOWLEDGE_PROTOCOL_INVALID"));
    }

    /** 不让复核模型的错误原因通过自由文本进入错误码或绕过布尔判定。 */
    @Test
    @DisplayName("复核否决原因被保留，批准和原因不一致时按协议错误处理")
    void classifiesAndValidatesReviewReason() {
        propose(draft(KnowledgeAnswerDraft.Status.ANSWERABLE));
        when(client.review(any(), any(), any(), any(), any())).thenReturn(new KnowledgeAnswerModelClient.ModelResult<>(
                new KnowledgeAnswerModelClient.Review(false, KnowledgeAnswerModelClient.ReviewReason.UNSUPPORTED_CLAIM), 1, 1, 2));
        assertThatThrownBy(() -> service.answer(model, QUESTION, "SH"))
                .isInstanceOfSatisfying(KnowledgeAnswerFailure.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo("KNOWLEDGE_REVIEW_UNSUPPORTED_CLAIM"));
        when(client.review(any(), any(), any(), any(), any())).thenReturn(new KnowledgeAnswerModelClient.ModelResult<>(
                new KnowledgeAnswerModelClient.Review(true, KnowledgeAnswerModelClient.ReviewReason.UNSUPPORTED_CLAIM), 1, 1, 2));
        assertThatThrownBy(() -> service.answer(model, QUESTION, "SH"))
                .isInstanceOfSatisfying(KnowledgeAnswerFailure.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo("KNOWLEDGE_PROTOCOL_INVALID"));
    }

    /** 组合固定草案以覆盖业务状态，不把模型桩结果作为真实准确率。 */
    private KnowledgeAnswerDraft draft(KnowledgeAnswerDraft.Status status) {
        return new KnowledgeAnswerDraft(status,
                status == KnowledgeAnswerDraft.Status.CONFLICT ? List.of() : List.of(claim("E1", QUOTE)),
                status == KnowledgeAnswerDraft.Status.NEEDS_CLARIFICATION ? List.of("请明确员工类别？") : List.of(),
                status == KnowledgeAnswerDraft.Status.CONFLICT
                        ? List.of(new KnowledgeAnswerDraft.Conflict("住宿额度", claim("E1", QUOTE), claim("E2", OTHER_QUOTE)))
                        : List.of());
    }

    /** 配置草案调用的确定性响应。 */
    private void propose(KnowledgeAnswerDraft draft) {
        when(client.draft(any(), any(), any(), any()))
                .thenReturn(new KnowledgeAnswerModelClient.ModelResult<>(draft, 10, 5, 15));
    }

    /** 构造包含逐字引文的结论。 */
    private KnowledgeAnswerDraft.Claim claim(String id, String quote) {
        return new KnowledgeAnswerDraft.Claim(quote, List.of(new KnowledgeAnswerDraft.Reference(id, quote)));
    }

    /** 模拟检索返回的 Chunk 与业务快照。 */
    private KnowledgeSearchResultResponse hit(long id, String content) {
        return new KnowledgeSearchResultResponse(1, id, 1, "制度" + id + ".md", content, 0.03,
                1, 1.0, 1, 1.0, KnowledgeDocumentMetadata.from(document(id)));
    }

    /** 构造准入复查的权威业务元数据。 */
    private KnowledgeDocument document(long id) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(id);
        document.setSourceKey("policy-" + id);
        document.setBusinessVersion("v1");
        document.setApplicabilityScope("SH");
        document.setValidFrom(LocalDateTime.of(2026, 10, 1, 0, 0));
        return document;
    }

    /** 包装检索候选列表。 */
    private KnowledgeSearchResponse search(List<KnowledgeSearchResultResponse> hits) {
        return new KnowledgeSearchResponse(1L, QUESTION, 50, new BigDecimal("0.65"), 5, hits.size(), hits.size(), hits);
    }
}
