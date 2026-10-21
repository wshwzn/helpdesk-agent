package com.anying.helpdesk.knowledge.answer;

import com.anying.helpdesk.auth.domain.*;
import com.anying.helpdesk.conversation.domain.*;
import com.anying.helpdesk.conversation.dto.*;
import com.anying.helpdesk.conversation.service.ConversationService;
import com.anying.helpdesk.knowledge.domain.*;
import com.anying.helpdesk.knowledge.dto.*;
import com.anying.helpdesk.knowledge.mapper.KnowledgeBaseMapper;
import com.anying.helpdesk.knowledge.service.*;
import com.anying.helpdesk.knowledge.answer.trace.KnowledgeAnswerRunStore;
import com.anying.helpdesk.modelconfig.factory.*;
import com.anying.helpdesk.security.JwtTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "app.knowledge.multihop.task-planning-enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class KnowledgeClarificationIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private JwtTokenService jwt;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private KnowledgeBaseMapper baseMapper;
    @Autowired private KnowledgeApplicabilityService policies;
    @Autowired private ConversationService conversations;
    @Autowired private KnowledgeAnswerRunStore runs;
    @MockitoBean private DefaultChatModelProvider provider;
    @MockitoBean private KnowledgeBaseService bases;
    @MockitoBean private KnowledgeRetrievalService retrieval;
    @MockitoBean private KnowledgeDocumentEligibilityService eligibility;
    @MockitoBean private KnowledgeAnswerModelClient model;
    private static final long CUSTOMER = 93001L;
    private static final String QUESTION = "普通员工出差住宿上限是多少？";
    private static final String QUOTE = "上海普通员工出差住宿每晚600元，超出不报销。";
    private Long baseId;
    private final List<Long> conversationIds = new ArrayList<>();
    private String admin;
    private String customer;

    /** 只替换外部检索与模型，真实验证数据库配置、会话事务和澄清报告往返。 */
    @BeforeEach
    void setup() {
        admin = auth(UserRole.ADMIN, CUSTOMER);
        customer = auth(UserRole.CUSTOMER, CUSTOMER);
        var base = new KnowledgeBase();
        base.setName("澄清测试-" + UUID.randomUUID());
        base.setCreatedBy(CUSTOMER);
        baseMapper.insert(base);
        baseId = base.getId();
        var response = KnowledgeBaseResponse.from(baseMapper.selectById(baseId), 1);
        when(bases.findDefault()).thenReturn(Optional.of(response));
        when(bases.getById(baseId)).thenReturn(response);
        policies.update(baseId, policy(0L));
        when(provider.get()).thenReturn(new ResolvedChatModel(1L, "test", false, mock(ChatModel.class)));
        when(model.route(any(), any(), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new KnowledgeAnswerModelClient.ModelResult<>(new KnowledgeApplicabilityPolicy.Route(List.of("lodging"), false), 8, 2, 10);
        });
        var document = new KnowledgeDocument();
        document.setId(99001L);
        document.setSourceKey("lodging");
        document.setBusinessVersion("v2");
        document.setApplicabilityScope("CN_SH");
        document.setValidFrom(LocalDateTime.of(2026, 1, 1, 0, 0));
        when(eligibility.findEligible(eq(baseId), eq("CN_SH"), any())).thenReturn(Map.of(document.getId(), document));
        when(retrieval.search(eq(baseId), any())).thenReturn(new KnowledgeSearchResponse(baseId, QUESTION, 50,
                BigDecimal.ZERO, 5, 1, 1, List.of(new KnowledgeSearchResultResponse(1, document.getId(), 1,
                "地区制度.md", QUOTE, 0.03, 1, 1.0, 1, 0.9, KnowledgeDocumentMetadata.from(document)))));
        when(model.plan(any(), any(), any(), any(), any())).thenReturn(new KnowledgeAnswerModelClient.ModelResult<>(
                new KnowledgeRetrievalPlan(KnowledgeRetrievalPlan.Decision.SUFFICIENT, List.of(), List.of(),
                        List.of(), List.of()), 0, 0, 0));
        when(model.draft(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            KnowledgeCitationCatalog catalog = invocation.getArgument(3);
            return new KnowledgeAnswerModelClient.ModelResult<>(new KnowledgeAnswerSelection(catalog.citationSetId(),
                    KnowledgeAnswerDraft.Status.ANSWERABLE, List.of(new KnowledgeAnswerSelection.Claim(QUOTE,
                    List.of(new KnowledgeAnswerSelection.Reference("E1", "E1-P1")))), List.of(), List.of()), 10, 5, 15);
        });
        when(model.review(any(), any(), any(), any(), any())).thenReturn(
                new KnowledgeAnswerModelClient.ModelResult<>(new KnowledgeAnswerModelClient.Review(true), 20, 5, 25));
    }

    /** 删除本类独立会话、诊断和配置，不修改其他测试数据。 */
    @AfterEach
    void cleanup() {
        for (Long id : conversationIds) {
            jdbc.update("DELETE FROM knowledge_answer_runs WHERE conversation_id=?", id);
            jdbc.update("DELETE FROM chat_messages WHERE conversation_id=?", id);
            jdbc.update("DELETE FROM conversations WHERE id=?", id);
        }
        baseMapper.deleteById(baseId);
    }

    /** 澄清不读取跨范围制度；确认后固定原知识库并用原问题重新检索。 */
    @Test
    @DisplayName("缺地区先澄清，选择后重新检索核验，默认库切换不改变续答来源")
    void completesClarificationLoop() {
        Long id = conversation();
        var first = ask(id);
        assertThat(first.knowledgeAnswer().reason()).isEqualTo(KnowledgeAnswerReport.Reason.APPLICABILITY_REQUIRED);
        assertThat(first.knowledgeAnswer().evidence()).isEmpty();
        assertThat(first.knowledgeAnswer().clarification().scopeOptions()).hasSize(2);
        assertThat(first.totalTokens()).isEqualTo(10);
        verifyNoInteractions(retrieval, eligibility);
        var initialRun = runs.find(first.knowledgeRunId()).orElseThrow();
        assertThat(initialRun.applicability().route().topicIds()).containsExactly("lodging");
        assertThat(initialRun.stages()).extracting(stage -> stage.stage().name()).containsExactly("APPLICABILITY", "MESSAGE_PERSISTENCE");
        when(bases.findDefault()).thenReturn(Optional.empty());
        var second = conversations.sendMessage(id, continuation(first.id(), "CN_SH"), CUSTOMER).assistantMessage();
        assertThat(second.content()).contains("600");
        assertThat(second.knowledgeAnswer().continuationOfMessageId()).isEqualTo(first.id());
        assertThat(second.totalTokens()).isEqualTo(40);
        verify(retrieval).search(baseId, new KnowledgeSearchRequest(QUESTION, "CN_SH"));
        verify(model).draft(any(), eq(QUESTION), eq("CN_SH"), any(), any());
        verify(model, times(1)).route(any(), any(), any());
        var run = runs.find(second.knowledgeRunId()).orElseThrow();
        assertThat(run.question()).isEqualTo(QUESTION);
        assertThat(run.applicability().continuationOfMessageId()).isEqualTo(first.id());
        assertThat(run.citationCatalog()).isNotNull();
        var history = conversations.pageMessages(id, 1, 20, CUSTOMER).records();
        assertThat(history.get(1).knowledgeAnswer()).isEqualTo(first.knowledgeAnswer());
        assertThat(history.get(3).knowledgeAnswer()).isEqualTo(second.knowledgeAnswer());
    }

    /** 不传模式的 HTTP 请求同样支持澄清及续答，保留原问题和证据绑定。 */
    @Test
    @DisplayName("省略模式也能完成范围澄清与续答并回读报告")
    void defaultHttpRequestsCompleteClarification() throws Exception {
        Long id = conversation();
        mvc.perform(post("/api/conversations/{id}/messages", id).header("Authorization", customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("content", QUESTION))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assistantMessage.mode").value("KNOWLEDGE"))
                .andExpect(jsonPath("$.data.assistantMessage.knowledgeAnswer.reason").value("APPLICABILITY_REQUIRED"))
                .andExpect(jsonPath("$.data.pendingActions").isEmpty());
        var first = conversations.pageMessages(id, 1, 20, CUSTOMER).records().getLast();
        mvc.perform(post("/api/conversations/{id}/messages", id).header("Authorization", customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("content", "我选择上海",
                                "applicabilityScope", "CN_SH", "clarificationMessageId", first.id()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assistantMessage.knowledgeAnswer.answer.status").value("ANSWERABLE"))
                .andExpect(jsonPath("$.data.assistantMessage.knowledgeAnswer.continuationOfMessageId").value(first.id()))
                .andExpect(jsonPath("$.data.pendingActions").isEmpty());
        verify(retrieval).search(baseId, new KnowledgeSearchRequest(QUESTION, "CN_SH"));
        var history = conversations.pageMessages(id, 1, 20, CUSTOMER).records();
        assertThat(history.get(1).knowledgeAnswer()).isEqualTo(first.knowledgeAnswer());
        assertThat(history.get(3).knowledgeAnswer().continuationOfMessageId()).isEqualTo(first.id());
    }

    /** 通用主题不需要地区，无配置的库保持原路径，并正确累加路由费用。 */
    @Test
    @DisplayName("通用问题不多问地区，关闭配置后不调用路由")
    void genericAndDisabledPolicy() {
        when(model.route(any(), any(), any())).thenReturn(new KnowledgeAnswerModelClient.ModelResult<>(
                new KnowledgeApplicabilityPolicy.Route(List.of("process"), false), 8, 2, 10));
        when(retrieval.search(any(), any())).thenReturn(new KnowledgeSearchResponse(baseId, "期限？", 50,
                BigDecimal.ZERO, 5, 0, 0, List.of()));
        var answer = ask(conversation());
        assertThat(answer.knowledgeAnswer().reason()).isEqualTo(KnowledgeAnswerReport.Reason.NO_MATCH);
        assertThat(answer.totalTokens()).isEqualTo(10);
        policies.update(baseId, new KnowledgeApplicabilityPolicy(1L, List.of()));
        var noPolicy = ask(conversation());
        assertThat(noPolicy.totalTokens()).isZero();
        verify(model, times(1)).route(any(), any(), any());
    }

    /** 无关或重复使用的消息不能恢复旧问题，拒绝发生在写入本轮消息之前。 */
    @Test
    @DisplayName("跨会话、其他客户、重复续答及错误选项均被拒绝")
    void protectsContinuationIdentity() throws Exception {
        Long id = conversation();
        var first = ask(id);
        send(conversation(), continuation(first.id(), "CN_SH"), customer).andExpect(status().isConflict());
        send(id, continuation(first.id(), "CN_SH"), auth(UserRole.CUSTOMER, CUSTOMER + 1)).andExpect(status().isNotFound());
        send(id, continuation(first.id(), "CN_SZ"), customer).andExpect(status().isBadRequest());
        send(id, continuation(first.id(), "ALL"), customer).andExpect(status().isBadRequest());
        assertThat(conversations.pageMessages(id, 1, 20, CUSTOMER).total()).isEqualTo(2);
        send(id, continuation(first.id(), "CN_SH"), customer).andExpect(status().isOk());
        send(id, continuation(first.id(), "CN_SH"), customer).andExpect(status().isConflict());
        assertThat(conversations.pageMessages(id, 1, 20, CUSTOMER).total()).isEqualTo(4);
    }

    /** 配置变更使旧卡片失效；当前条件不能被旧的允许范围覆盖。 */
    @Test
    @DisplayName("配置改变后旧澄清返回409且不产生消息或模型调用")
    void rejectsChangedPolicy() throws Exception {
        Long id = conversation();
        var first = ask(id);
        policies.update(baseId, policy(1L));
        send(id, continuation(first.id(), "CN_SH"), customer).andExpect(status().isConflict());
        assertThat(conversations.pageMessages(id, 1, 20, CUSTOMER).total()).isEqualTo(2);
        verifyNoInteractions(retrieval);
    }

    /** 补充条件后若资料已撤回，走全新检索，不复用任何历史证据。 */
    @Test
    @DisplayName("澄清后资料不可用时返回无命中，SSE不产生旧答案")
    void freshRetrievalAndStreaming() {
        Long id = conversation();
        var first = ask(id);
        when(retrieval.search(any(), any())).thenReturn(new KnowledgeSearchResponse(baseId, QUESTION, 50,
                BigDecimal.ZERO, 5, 0, 0, List.of()));
        var events = conversations.streamMessage(id, continuation(first.id(), "CN_SH"), CUSTOMER)
                .collectList().block(Duration.ofSeconds(10));
        assertThat(events).extracting(ChatStreamEvent::type).containsExactly("start", "delta", "done");
        var latest = conversations.pageMessages(id, 1, 20, CUSTOMER).records().getLast();
        assertThat(latest.knowledgeAnswer().reason()).isEqualTo(KnowledgeAnswerReport.Reason.NO_MATCH);
        assertThat(latest.knowledgeAnswer().continuationOfMessageId()).isEqualTo(first.id());
        verify(model, never()).draft(any(), any(), any(), any(), any());
    }

    /** 不确定分类不能静默当作通用问题，合法候选也不能绕过已有语义复核。 */
    @Test
    @DisplayName("主题不确定或续答复核失败均保持技术失败，不发放草案")
    void failsClosedAtRoutingAndReview() throws Exception {
        when(model.route(any(), any(), any())).thenReturn(new KnowledgeAnswerModelClient.ModelResult<>(
                new KnowledgeApplicabilityPolicy.Route(List.of(), true), 8, 2, 10));
        Long ambiguous = conversation();
        send(ambiguous, new SendMessageRequest(QUESTION, ChatMode.KNOWLEDGE, "ALL"), customer).andExpect(status().isBadGateway());
        var failure = conversations.pageMessages(ambiguous, 1, 20, CUSTOMER).records().getLast();
        assertThat(failure.errorCode()).isEqualTo("KNOWLEDGE_APPLICABILITY_UNCERTAIN");
        assertThat(runs.find(failure.knowledgeRunId()).orElseThrow().stages().getFirst().usage().totalTokens()).isEqualTo(10);
        verifyNoInteractions(retrieval);
        when(model.route(any(), any(), any())).thenReturn(new KnowledgeAnswerModelClient.ModelResult<>(
                new KnowledgeApplicabilityPolicy.Route(List.of("lodging"), false), 8, 2, 10));
        Long id = conversation();
        var first = ask(id);
        when(model.review(any(), any(), any(), any(), any())).thenReturn(new KnowledgeAnswerModelClient.ModelResult<>(
                new KnowledgeAnswerModelClient.Review(false), 20, 5, 25));
        send(id, continuation(first.id(), "CN_SH"), customer).andExpect(status().isBadGateway());
        var last = conversations.pageMessages(id, 1, 20, CUSTOMER).records().getLast();
        assertThat(last.knowledgeAnswer()).isNull();
        assertThat(last.content()).isNull();
    }

    /** 模型耗时期间管理员修改配置，已过时的判断不能在最后落库交付。 */
    @Test
    @DisplayName("续答生成期间配置变更时失败且历史澄清保持原快照")
    void policyChangeDuringGenerationFailsClosed() throws Exception {
        Long id = conversation();
        var first = ask(id);
        when(model.review(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            policies.update(baseId, policy(1L));
            return new KnowledgeAnswerModelClient.ModelResult<>(new KnowledgeAnswerModelClient.Review(true), 20, 5, 25);
        });
        send(id, continuation(first.id(), "CN_SH"), customer).andExpect(status().isBadGateway());
        var history = conversations.pageMessages(id, 1, 20, CUSTOMER).records();
        assertThat(history.get(1).knowledgeAnswer()).isEqualTo(first.knowledgeAnswer());
        assertThat(history.getLast().errorCode()).isEqualTo("KNOWLEDGE_APPLICABILITY_CHANGED");
        assertThat(history.getLast().knowledgeAnswer()).isNull();
    }

    /** 已核验续答在 SSE 完成后才发送正文，新问题仍需明确范围而不继承旧选择。 */
    @Test
    @DisplayName("SSE续答成功保存关联，新问题不会自动继承上海范围")
    void successfulStreamDoesNotCreateImplicitMemory() {
        Long id = conversation();
        var first = ask(id);
        var events = conversations.streamMessage(id, continuation(first.id(), "CN_SH"), CUSTOMER)
                .collectList().block(Duration.ofSeconds(10));
        assertThat(events).extracting(ChatStreamEvent::type).containsExactly("start", "delta", "done");
        var completed = conversations.pageMessages(id, 1, 20, CUSTOMER).records().getLast();
        assertThat(completed.knowledgeAnswer().answer().status()).isEqualTo(KnowledgeAnswerDraft.Status.ANSWERABLE);
        assertThat(completed.knowledgeAnswer().continuationOfMessageId()).isEqualTo(first.id());
        var next = ask(id);
        assertThat(next.applicabilityScope()).isEqualTo("ALL");
        assertThat(next.knowledgeAnswer().reason()).isEqualTo(KnowledgeAnswerReport.Reason.APPLICABILITY_REQUIRED);
        verify(retrieval, times(1)).search(any(), any());
        verify(model, times(2)).route(any(), any(), any());
    }

    /** 验证管理员权限、配置乐观并发及领域校验，不仅测试服务内部路径。 */
    @Test
    @DisplayName("配置接口仅管理员可用，旧修订和非法配置不会覆盖现有配置")
    void validatesAdminPolicy() throws Exception {
        String path = "/api/admin/knowledge-bases/" + baseId + "/clarification-policy";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization", customer)).andExpect(status().isForbidden());
        mvc.perform(get(path).header("Authorization", admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(1));
        mvc.perform(put(path).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(policy(0L)))).andExpect(status().isConflict());
        var topic = policy(1L).topics().getFirst();
        mvc.perform(put(path).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new KnowledgeApplicabilityPolicy(1L, List.of(topic, topic)))))
                .andExpect(status().isBadRequest());
        mvc.perform(put(path).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"revision\":1,\"topics\":[null]}" )).andExpect(status().isBadRequest());
        assertThat(policies.get(baseId).revision()).isEqualTo(1);
        mvc.perform(put(path).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new KnowledgeApplicabilityPolicy(1L, List.of()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(2));
    }

    /** 原问题已明确地区名称时不再要求单选，并真实保存多范围报告与消息字段。 */
    @Test @DisplayName("自然语言比较两地区联合检索且历史消息保存多范围")
    void namedComparisonPersistsScopes() {
        multiScopeFixtures();
        Long id = conversation();
        var answer = conversations.sendMessage(id, new SendMessageRequest("比较上海和北京的住宿标准"), CUSTOMER).assistantMessage();
        assertThat(answer.applicabilityScopes()).containsExactly("CN_SH", "CN_BJ");
        assertThat(answer.applicabilityScope()).isNull();
        assertThat(answer.knowledgeAnswer().applicabilityScopes()).containsExactly("CN_SH", "CN_BJ");
        assertThat(answer.knowledgeAnswer().answer().status()).isEqualTo(KnowledgeAnswerDraft.Status.ANSWERABLE);
        verify(retrieval).search(baseId, new KnowledgeSearchRequest("比较上海和北京的住宿标准", null, List.of("CN_SH", "CN_BJ")));
        verify(model, times(1)).draft(any(), any(), any(), any(), any());
        verify(model, times(1)).review(any(), any(), any(), any(), any());
        var history = conversations.pageMessages(id, 1, 20, CUSTOMER).records();
        assertThat(history.getLast().applicabilityScopes()).isEqualTo(answer.applicabilityScopes());
        assertThat(jdbc.queryForObject("SELECT applicability_scope FROM chat_messages WHERE id=?", String.class, answer.id())).isNull();
        assertThat(runs.find(answer.knowledgeRunId()).orElseThrow().applicabilityScopes()).containsExactly("CN_SH", "CN_BJ");
    }

    /** 显式多选必须原样穿过同步与流式会话入口，不能靠正文中的地区名弥补参数丢失。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false, KNOWLEDGE", "true, KNOWLEDGE", "false, AGENT", "true, AGENT"})
    @DisplayName("同步和流式均保留显式多范围且不重复路由")
    void explicitScopesSurviveConversationEntry(boolean streaming, ChatMode mode) {
        multiScopeFixtures();
        Long id = conversation();
        var request = new SendMessageRequest("请比较所选地区的住宿标准", mode,
                null, null, List.of("CN_SH", "CN_BJ"));
        if (streaming) conversations.streamMessage(id, request, CUSTOMER).collectList().block(java.time.Duration.ofSeconds(10));
        else conversations.sendMessage(id, request, CUSTOMER);
        var answer = conversations.pageMessages(id, 1, 20, CUSTOMER).records().getLast();
        assertThat(answer.status()).isEqualTo(ChatMessageStatus.COMPLETED);
        assertThat(answer.applicabilityScopes()).containsExactly("CN_SH", "CN_BJ");
        verify(retrieval).search(baseId, new KnowledgeSearchRequest("请比较所选地区的住宿标准", null, List.of("CN_SH", "CN_BJ")));
        verify(model, never()).route(any(), any(), any());
        var run = runs.find(answer.knowledgeRunId()).orElseThrow();
        assertThat(run.applicabilityScopes()).containsExactly("CN_SH", "CN_BJ");
    }

    /** 原先未选范围的比较问题可以通过同一澄清卡片选择多个地区，恢复原问题继续处理。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(ChatMode.class)
    @DisplayName("两种模式值澄清续答允许多选且绑定原问题原知识库")
    void multiSelectContinuation(ChatMode mode) {
        Long id = conversation();
        var first = ask(id);
        multiScopeFixtures();
        var answer = conversations.sendMessage(id, new SendMessageRequest("比较两个地区", mode,
                null, first.id(), List.of("CN_SH", "CN_BJ")), CUSTOMER).assistantMessage();
        assertThat(answer.knowledgeAnswer().continuationOfMessageId()).isEqualTo(first.id());
        assertThat(answer.applicabilityScopes()).containsExactly("CN_SH", "CN_BJ");
        verify(retrieval).search(baseId, new KnowledgeSearchRequest(QUESTION, null, List.of("CN_SH", "CN_BJ")));
        verify(model, times(1)).route(any(), any(), any());
    }

    /** 无效集合在建消息和调用模型之前被拒绝。 */
    @Test @DisplayName("HTTP拒绝范围混选冲突和超限且不写入消息")
    void rejectsInvalidScopeListsBeforePersistence() throws Exception {
        Long id = conversation();
        for (var scopes : List.of(List.of("ALL", "CN_SH"), List.of("CN_SH", "CN_SH"),
                List.of("A", "B", "C", "D"), List.<String>of())) {
            mvc.perform(post("/api/conversations/{id}/messages", id).header("Authorization", customer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("content", "比较", "applicabilityScopes", scopes))))
                    .andExpect(status().isBadRequest());
        }
        assertThat(conversations.pageMessages(id, 1, 20, CUSTOMER).records()).isEmpty();
        verifyNoInteractions(retrieval, model);
    }

    /** 提供两地区完整证据；草案与复核只各发生一次，使用真实引用解析。 */
    private void multiScopeFixtures() {
        var sh = new KnowledgeDocument(); sh.setId(99001L); sh.setSourceKey("lodging-sh"); sh.setBusinessVersion("v1");
        sh.setApplicabilityScope("CN_SH"); sh.setValidFrom(LocalDateTime.of(2026, 1, 1, 0, 0));
        var bj = new KnowledgeDocument(); bj.setId(99002L); bj.setSourceKey("lodging-bj"); bj.setBusinessVersion("v1");
        bj.setApplicabilityScope("CN_BJ"); bj.setValidFrom(LocalDateTime.of(2026, 1, 1, 0, 0));
        when(eligibility.findEligibleScopes(eq(baseId), eq(List.of("CN_SH", "CN_BJ")), any()))
                .thenReturn(Map.of(sh.getId(), sh, bj.getId(), bj));
        when(retrieval.search(eq(baseId), any())).thenReturn(new KnowledgeSearchResponse(baseId, "比较", 50,
                BigDecimal.ZERO, 5, 2, 2, List.of(
                    new KnowledgeSearchResultResponse(1, sh.getId(), 0, "上海.md", "上海每晚600元。", 0.03, 1, 1.0, 1, 0.9, KnowledgeDocumentMetadata.from(sh)),
                    new KnowledgeSearchResultResponse(2, bj.getId(), 0, "北京.md", "北京每晚500元。", 0.02, 2, 0.9, 2, 0.8, KnowledgeDocumentMetadata.from(bj)))));
        when(model.planLegacy(any(), any(), any(), any(), any())).thenReturn(new KnowledgeAnswerModelClient.ModelResult<>(
                new KnowledgeRetrievalPlan(KnowledgeRetrievalPlan.Decision.SUFFICIENT, List.of(), List.of()), 1, 1, 2));
        doAnswer(invocation -> {
            KnowledgeCitationCatalog catalog = invocation.getArgument(3);
            return new KnowledgeAnswerModelClient.ModelResult<>(new KnowledgeAnswerSelection(catalog.citationSetId(),
                    KnowledgeAnswerDraft.Status.ANSWERABLE, List.of(
                        new KnowledgeAnswerSelection.Claim("上海每晚600元。", List.of(new KnowledgeAnswerSelection.Reference("E1", "E1-P1"))),
                        new KnowledgeAnswerSelection.Claim("北京每晚500元。", List.of(new KnowledgeAnswerSelection.Reference("E2", "E2-P1")))), List.of(), List.of()), 10, 5, 15);
        }).when(model).draft(any(), any(), any(), any(), any());
    }

    /** 构造同时包含需要范围和无需范围主题的配置。 */
    private KnowledgeApplicabilityPolicy policy(long revision) {
        return new KnowledgeApplicabilityPolicy(revision, List.of(
                new KnowledgeApplicabilityPolicy.Topic("lodging", "出差住宿报销标准与额度", List.of(
                        new KnowledgeApplicabilityPolicy.ScopeOption("CN_SH", "上海"),
                        new KnowledgeApplicabilityPolicy.ScopeOption("CN_BJ", "北京"))),
                new KnowledgeApplicabilityPolicy.Topic("process", "通用报销提交期限及所需材料", List.of())));
    }

    /** 创建本测试归属客户的独立会话。 */
    private Long conversation() {
        var id = conversations.create(new CreateConversationRequest("澄清"), CUSTOMER).id();
        conversationIds.add(id);
        return id;
    }

    /** 发出固定缺条件问题并返回持久化助手消息。 */
    private ChatMessageResponse ask(Long id) {
        return conversations.sendMessage(id, new SendMessageRequest(QUESTION, ChatMode.KNOWLEDGE, "ALL"), CUSTOMER).assistantMessage();
    }

    /** 续答正文作为用户原话保留，有效问题和范围来自澄清来源及显式选项。 */
    private SendMessageRequest continuation(Long id, String scope) {
        return new SendMessageRequest("我选择上海", ChatMode.KNOWLEDGE, scope, id);
    }

    /** 走真实 HTTP 参数校验和鉴权入口。 */
    private ResultActions send(Long id, SendMessageRequest request, String authorization) throws Exception {
        return mvc.perform(post("/api/conversations/{id}/messages", id).header("Authorization", authorization)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)));
    }

    /** 为测试环境签发指定身份，不读取本地真实凭据。 */
    private String auth(UserRole role, long id) {
        var user = new User(); user.setId(id); user.setUsername("clarification-test"); user.setRole(role);
        return "Bearer " + jwt.issue(user).value();
    }
}
