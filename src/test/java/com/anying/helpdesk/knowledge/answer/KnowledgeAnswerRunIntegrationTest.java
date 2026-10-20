package com.anying.helpdesk.knowledge.answer;

import com.anying.helpdesk.auth.domain.User;
import com.anying.helpdesk.auth.domain.UserRole;
import com.anying.helpdesk.conversation.domain.ChatMessage;
import com.anying.helpdesk.conversation.domain.ChatMessageRole;
import com.anying.helpdesk.conversation.dto.SendMessageRequest;
import com.anying.helpdesk.conversation.domain.ChatMode;
import com.anying.helpdesk.conversation.mapper.ChatMessageMapper;
import com.anying.helpdesk.conversation.mapper.ConversationMapper;
import com.anying.helpdesk.conversation.service.ConversationService;
import com.anying.helpdesk.knowledge.answer.trace.KnowledgeAnswerRunSnapshot;
import com.anying.helpdesk.knowledge.answer.trace.KnowledgeAnswerRunStore;
import com.anying.helpdesk.knowledge.domain.KnowledgeDocument;
import com.anying.helpdesk.knowledge.dto.*;
import com.anying.helpdesk.knowledge.service.KnowledgeBaseService;
import com.anying.helpdesk.knowledge.service.KnowledgeDocumentEligibilityService;
import com.anying.helpdesk.knowledge.service.KnowledgeRetrievalService;
import com.anying.helpdesk.modelconfig.factory.DefaultChatModelProvider;
import com.anying.helpdesk.modelconfig.factory.ResolvedChatModel;
import com.anying.helpdesk.security.JwtTokenService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "app.knowledge.multihop.rule-checks-enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class KnowledgeAnswerRunIntegrationTest {
    private static final long CUSTOMER = 92001L;
    private static final String QUOTE = "上海普通员工住宿上限每晚600元。";
    @Autowired private MockMvc mvc;
    @Autowired private JwtTokenService jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ChatMessageMapper messages;
    @Autowired private ConversationMapper conversations;
    @Autowired private ConversationService service;
    @Autowired private com.fasterxml.jackson.databind.ObjectMapper mapper;
    @MockitoSpyBean private KnowledgeAnswerRunStore runs;
    @MockitoBean private DefaultChatModelProvider provider;
    @MockitoBean private KnowledgeBaseService bases;
    @MockitoBean private KnowledgeRetrievalService retrieval;
    @MockitoBean private KnowledgeDocumentEligibilityService eligibility;
    @MockitoBean private KnowledgeAnswerModelClient modelClient;
    private long conversationId;
    private String customerAuth;
    private String adminAuth;

    /** 真实经过消息事务、核验链路和诊断表，仅替换外部检索及模型依赖。 */
    @BeforeEach
    void setup() throws Exception {
        customerAuth = authorization(UserRole.CUSTOMER);
        adminAuth = authorization(UserRole.ADMIN);
        String body = mvc.perform(post("/api/conversations").header("Authorization", customerAuth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"诊断测试\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        conversationId = ((Number) JsonPath.read(body, "$.data.id")).longValue();
        when(provider.get()).thenReturn(new ResolvedChatModel(1L, "test-model", false, mock(ChatModel.class)));
        when(bases.findDefault()).thenReturn(Optional.of(new KnowledgeBaseResponse(1L, "制度", null,
                50, BigDecimal.ZERO, 5, true, 1, 0, null, null)));
        var document = new KnowledgeDocument();
        document.setId(10L);
        document.setSourceKey("travel");
        document.setBusinessVersion("v2");
        document.setApplicabilityScope("SH");
        document.setValidFrom(LocalDateTime.of(2026, 1, 1, 0, 0));
        var metadata = KnowledgeDocumentMetadata.from(document);
        when(retrieval.search(any(), any())).thenReturn(new KnowledgeSearchResponse(1L, "住宿？", 50, BigDecimal.ZERO,
                5, 1, 1, List.of(new KnowledgeSearchResultResponse(1, 10L, 1, "制度.md", QUOTE,
                0.03, 1, 1.0, 1, 0.8, metadata))));
        when(eligibility.findEligible(any(), any(), any())).thenReturn(Map.of(10L, document));
        when(modelClient.plan(any(), any(), any(), any(), any())).thenReturn(new KnowledgeAnswerModelClient.ModelResult<>(
                new KnowledgeRetrievalPlan(KnowledgeRetrievalPlan.Decision.SUFFICIENT, List.of(), List.of(),
                        List.of(new KnowledgeRetrievalPlan.RuleCheck("E1", "住宿", "住宿？")), List.of()), 0, 0, 0));
        when(modelClient.draft(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new KnowledgeAnswerModelClient.ModelResult<>(selection(invocation.getArgument(3), "E1-P1"), 10, 5, 15);
        });
        when(modelClient.review(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new KnowledgeAnswerModelClient.ModelResult<>(new KnowledgeAnswerModelClient.Review(true), 20, 5, 25);
        });
    }

    /** 实际经过消息和诊断落库，验证新增检索轮次不会只停留在内存或丢失用量。 */
    @Test @DisplayName("多轮查询和规划用量可从管理员诊断往返读取")
    void persistsMultihopTraceAndUsage() throws Exception {
        when(modelClient.plan(any(), any(), any(), any(), any())).thenReturn(
                new KnowledgeAnswerModelClient.ModelResult<>(new KnowledgeRetrievalPlan(KnowledgeRetrievalPlan.Decision.SEARCH,
                        List.of("检查相关规则"), List.of("上海住宿额度通知")), 6, 3, 9));
        send().andExpect(status().isOk());
        var message = assistant();
        var snapshot = runs.find(message.getKnowledgeRunId()).orElseThrow();
        assertThat(snapshot.retrievalRun().searches()).hasSize(2);
        assertThat(snapshot.retrievalRun().searches().getLast().query()).isEqualTo("上海住宿额度通知");
        assertThat(snapshot.retrievalRun().searches().getLast().response().results()).hasSize(1);
        assertThat(snapshot.retrievalRun().plans()).hasSize(1);
        assertThat(snapshot.retrievalRun().stopReason()).isEqualTo(KnowledgeRetrievalRun.StopReason.NO_NEW_EVIDENCE);
        assertThat(message.getTotalTokens()).isEqualTo(49);
        assertThat(snapshot.stages()).filteredOn(stage -> stage.stage() == KnowledgeAnswerRunSnapshot.Stage.PLANNING)
                .singleElement().satisfies(stage -> assertThat(stage.usage().totalTokens()).isEqualTo(9));
        mvc.perform(get("/api/admin/knowledge-answer-runs/" + snapshot.runId()).header("Authorization", adminAuth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.retrievalRun.searches[1].round").value(2));
    }

    /** 清理本测试会话和独立诊断，不影响其他测试的业务资料。 */
    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM knowledge_answer_runs WHERE conversation_id=?", conversationId);
        messages.delete(new LambdaQueryWrapper<ChatMessage>().eq(ChatMessage::getConversationId, conversationId));
        conversations.deleteById(conversationId);
    }

    /** 管理员能复查完整证据，客户及客服不能通过运行 ID 获取草案。 */
    @Test
    @DisplayName("成功消息关联六阶段诊断且管理员接口严格鉴权")
    void successfulRunAndAccessControl() throws Exception {
        String body = send().andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String runId = JsonPath.read(body, "$.data.assistantMessage.knowledgeRunId");
        assertThat(runId).isEqualTo(assistant().getKnowledgeRunId());
        var snapshot = runs.find(runId).orElseThrow();
        assertThat(snapshot.status()).isEqualTo(KnowledgeAnswerRunSnapshot.Status.SUCCEEDED);
        assertThat(snapshot.stages()).extracting(KnowledgeAnswerRunSnapshot.StageResult::stage)
                .containsExactly(KnowledgeAnswerRunSnapshot.Stage.RETRIEVAL, KnowledgeAnswerRunSnapshot.Stage.PLANNING, KnowledgeAnswerRunSnapshot.Stage.DRAFT,
                        KnowledgeAnswerRunSnapshot.Stage.VALIDATION, KnowledgeAnswerRunSnapshot.Stage.REVIEW,
                        KnowledgeAnswerRunSnapshot.Stage.EVIDENCE_RECHECK, KnowledgeAnswerRunSnapshot.Stage.MESSAGE_PERSISTENCE);
        assertThat(snapshot.stages()).allSatisfy(stage -> {
            assertThat(stage.status()).isEqualTo(KnowledgeAnswerRunSnapshot.StageStatus.SUCCEEDED);
            assertThat(stage.durationMs()).isGreaterThanOrEqualTo(0);
        });
        assertThat(snapshot.draft()).isEqualTo(draft(QUOTE));
        assertThat(snapshot.schemaVersion()).isEqualTo(4);
        assertThat(snapshot.selection().citationSetId()).isEqualTo(snapshot.citationCatalog().citationSetId());
        assertThat(snapshot.stages().get(2).usage().totalTokens()).isEqualTo(15);
        assertThat(snapshot.stages().get(4).usage().totalTokens()).isEqualTo(25);
        String url = "/api/admin/knowledge-answer-runs/" + runId;
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        mvc.perform(get(url).header("Authorization", customerAuth)).andExpect(status().isForbidden());
        mvc.perform(get(url).header("Authorization", authorization(UserRole.SUPPORT))).andExpect(status().isForbidden());
        mvc.perform(get(url).header("Authorization", adminAuth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.evidence[0].content").value(QUOTE))
                .andExpect(jsonPath("$.data.review.approved").value(true));
        mvc.perform(get("/api/admin/knowledge-answer-runs").header("Authorization", adminAuth)
                        .param("conversationId", Long.toString(conversationId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].draft").doesNotExist());
        mvc.perform(get("/api/admin/knowledge-answer-runs/00000000-0000-0000-0000-000000000000")
                        .header("Authorization", adminAuth)).andExpect(status().isNotFound());
    }

    /** 复核不通过的草案只留在诊断表，失败客户消息仍没有正文和成功报告。 */
    @Test
    @DisplayName("复核否决保留草案与用量且不向客户泄露")
    void rejectedDraftIsOnlyInAdminTrace() throws Exception {
        when(modelClient.review(any(), any(), any(), any(), any())).thenReturn(
                new KnowledgeAnswerModelClient.ModelResult<>(new KnowledgeAnswerModelClient.Review(false), 20, 5, 25));
        send().andExpect(status().isBadGateway());
        var message = assistant();
        assertThat(message.getContent()).isNull();
        assertThat(message.getKnowledgeAnswer()).isNull();
        assertThat(message.getErrorCode()).isEqualTo("KNOWLEDGE_REVIEW_REJECTED");
        var snapshot = runs.find(message.getKnowledgeRunId()).orElseThrow();
        assertThat(snapshot.status()).isEqualTo(KnowledgeAnswerRunSnapshot.Status.FAILED);
        assertThat(snapshot.draft()).isEqualTo(draft(QUOTE));
        assertThat(snapshot.schemaVersion()).isEqualTo(4);
        assertThat(snapshot.selection().citationSetId()).isEqualTo(snapshot.citationCatalog().citationSetId());
        assertThat(snapshot.review().approved()).isFalse();
        assertThat(snapshot.stages().getLast().errorCode()).isEqualTo(message.getErrorCode());
        assertThat(snapshot.stages().getLast().usage().totalTokens()).isEqualTo(25);
        String history = mvc.perform(get("/api/conversations/{id}/messages", conversationId)
                        .header("Authorization", customerAuth)).andReturn().getResponse().getContentAsString();
        assertThat(history).contains(message.getKnowledgeRunId()).doesNotContain(QUOTE, "\"draft\"");
        verifyNoInteractions(eligibility);
    }

    /** 引用校验失败前已经保存草案，不会错记为已调用复核或零用量。 */
    @Test
    @DisplayName("假片段被阻断时保留模型选择且复核阶段不存在")
    void invalidCitationIsDiagnosable() throws Exception {
        doAnswer(invocation -> new KnowledgeAnswerModelClient.ModelResult<>(
                selection(invocation.getArgument(3), "E1-P999"), null, null, null))
                .when(modelClient).draft(any(), any(), any(), any(), any());
        send().andExpect(status().isBadGateway());
        var snapshot = runs.find(assistant().getKnowledgeRunId()).orElseThrow();
        assertThat(snapshot.errorCode()).isEqualTo("KNOWLEDGE_CITATION_INVALID");
        assertThat(snapshot.draft()).isNull();
        assertThat(snapshot.selection().claims().getFirst().references().getFirst().paragraphId()).isEqualTo("E1-P999");
        assertThat(snapshot.citationCatalog().citationSetId()).isEqualTo(snapshot.selection().citationSetId());
        assertThat(snapshot.stages()).hasSize(4);
        assertThat(snapshot.stages().get(2).usage().totalTokens()).isNull();
        verify(modelClient, never()).review(any(), any(), any(), any(), any());
    }

    /** 协议错误无法形成草案，但仍保存已知用量与失败阶段，而不是丢掉这次调用。 */
    @Test
    @DisplayName("草案协议失败的 Token 能进入管理员诊断")
    void failedModelUsageIsPersisted() throws Exception {
        doThrow(new KnowledgeAnswerFailure(KnowledgeAnswerFailure.Code.KNOWLEDGE_PROTOCOL_INVALID,
                "invalid knowledge answer protocol", new KnowledgeAnswerModelClient.TokenUsage(20, 8, 28)))
                .when(modelClient).draft(any(), any(), any(), any(), any());
        send().andExpect(status().isBadGateway());
        var snapshot = runs.find(assistant().getKnowledgeRunId()).orElseThrow();
        assertThat(snapshot.draft()).isNull();
        assertThat(snapshot.errorCode()).isEqualTo("KNOWLEDGE_PROTOCOL_INVALID");
        assertThat(snapshot.stages().getLast().usage().totalTokens()).isEqualTo(28);
    }

    /** 即使诊断表完全不可写，也不影响正常回答或覆盖原有的精确失败码。 */
    @Test
    @DisplayName("诊断存储故障同时与业务成功和业务失败隔离")
    void diagnosticStoreFailureCannotMaskBusinessResult() throws Exception {
        doThrow(new IllegalStateException("private database detail")).when(runs).save(any());
        send().andExpect(status().isOk());
        assertThat(runs.find(assistant().getKnowledgeRunId())).isEmpty();
        when(modelClient.review(any(), any(), any(), any(), any())).thenReturn(
                new KnowledgeAnswerModelClient.ModelResult<>(new KnowledgeAnswerModelClient.Review(false), null, null, null));
        send().andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("CHAT_GENERATION_FAILED"));
        assertThat(assistant().getErrorCode()).isEqualTo("KNOWLEDGE_REVIEW_REJECTED");
    }

    /** 无配置时不会伪造草案调用或已知 Token，仍有完整的消息交付终态。 */
    @Test
    @DisplayName("未配置知识库只记录检索与消息保存两个阶段")
    void noKnowledgeBaseHasNoModelStages() throws Exception {
        when(bases.findDefault()).thenReturn(Optional.empty());
        send().andExpect(status().isOk());
        var snapshot = runs.find(assistant().getKnowledgeRunId()).orElseThrow();
        assertThat(snapshot.answerReason()).isEqualTo(KnowledgeAnswerReport.Reason.NOT_CONFIGURED);
        assertThat(snapshot.stages()).extracting(KnowledgeAnswerRunSnapshot.StageResult::stage)
                .containsExactly(KnowledgeAnswerRunSnapshot.Stage.MESSAGE_PERSISTENCE);
        verifyNoInteractions(modelClient);
    }

    /** 第一次保存失败后，后续阶段允许插入完整累计快照，不丢失已知的失败次数。 */
    @Test
    @DisplayName("初始诊断写入失败后能够在后续阶段恢复记录")
    void recoversAfterInitialDiagnosticWriteFailure() throws Exception {
        doThrow(new IllegalStateException("temporary storage failure")).doCallRealMethod().when(runs).save(any());
        send().andExpect(status().isOk());
        var snapshot = runs.find(assistant().getKnowledgeRunId()).orElseThrow();
        assertThat(snapshot.status()).isEqualTo(KnowledgeAnswerRunSnapshot.Status.SUCCEEDED);
        assertThat(snapshot.diagnosticWriteFailures()).isEqualTo(1);
        assertThat(snapshot.stages()).hasSize(7);
    }

    /** 数据库也守住终态，避免其他线程持有的旧快照覆盖已经完成的记录。 */
    @Test
    @DisplayName("诊断终态拒绝较高修订号的迟到运行中快照")
    void databaseRejectsLateRunningSnapshot() throws Exception {
        send().andExpect(status().isOk());
        var snapshot = runs.find(assistant().getKnowledgeRunId()).orElseThrow();
        com.fasterxml.jackson.databind.node.ObjectNode late = mapper.valueToTree(snapshot);
        late.put("status", "RUNNING");
        late.put("revision", snapshot.revision() + 100);
        runs.save(mapper.treeToValue(late, KnowledgeAnswerRunSnapshot.class));
        assertThat(runs.find(snapshot.runId()).orElseThrow()).isEqualTo(snapshot);
    }

    /** 升级已有知识消息时不补造运行记录或改写历史核验内容。 */
    @Test
    @DisplayName("V18 升级保留既有知识消息且运行编号为空")
    void migrationPreservesExistingKnowledgeMessages() {
        var dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:h2:mem:knowledge_run_migration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        org.flywaydb.core.Flyway.configure().dataSource(dataSource).target("17").load().migrate();
        var database = new JdbcTemplate(dataSource);
        database.update("INSERT INTO chat_messages (conversation_id, role, status, mode, error_code) VALUES (1, 'ASSISTANT', 'FAILED', 'KNOWLEDGE', 'KNOWLEDGE_ANSWER_FAILED')");
        org.flywaydb.core.Flyway.configure().dataSource(dataSource).load().migrate();
        var row = database.queryForMap("SELECT * FROM chat_messages");
        assertThat(row.get("knowledge_run_id")).isNull();
        assertThat(row.get("error_code")).isEqualTo("KNOWLEDGE_ANSWER_FAILED");
        assertThat(database.queryForObject("SELECT COUNT(*) FROM knowledge_answer_runs", Long.class)).isZero();
    }

    /** 故意让厂商忽略中断，验证取消的数据库快照不会被迟到草案改写。 */
    @Test
    @DisplayName("流式取消后迟到草案不能覆盖取消终态或触发复核")
    void cancelledStreamRejectsLateDraft() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var returned = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            while (release.getCount() > 0) {
                try { release.await(100, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { }
            }
            returned.countDown();
            return new KnowledgeAnswerModelClient.ModelResult<>(selection(invocation.getArgument(3), "E1-P1"), 10, 5, 15);
        }).when(modelClient).draft(any(), any(), any(), any(), any());
        var subscription = service.streamMessage(conversationId,
                new SendMessageRequest("住宿？", ChatMode.KNOWLEDGE, "SH"), CUSTOMER).subscribe();
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            subscription.dispose();
            release.countDown();
            assertThat(returned.await(5, TimeUnit.SECONDS)).isTrue();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                var message = assistant();
                assertThat(message.getErrorCode()).isEqualTo("STREAM_CANCELLED");
                var snapshot = runs.find(message.getKnowledgeRunId()).orElseThrow();
                assertThat(snapshot.status()).isEqualTo(KnowledgeAnswerRunSnapshot.Status.CANCELLED);
                assertThat(snapshot.draft()).isNull();
                assertThat(snapshot.selection()).isNull();
                assertThat(snapshot.stages().getLast().status()).isEqualTo(KnowledgeAnswerRunSnapshot.StageStatus.CANCELLED);
            });
            verify(modelClient, never()).review(any(), any(), any(), any(), any());
        } finally {
            release.countDown();
            subscription.dispose();
        }
    }

    /** 通过统一接口发送一条知识问题，便于复用真实 HTTP 与事务路径。 */
    private ResultActions send() throws Exception {
        return mvc.perform(post("/api/conversations/{id}/messages", conversationId).header("Authorization", customerAuth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"住宿？\",\"mode\":\"KNOWLEDGE\",\"applicabilityScope\":\"SH\"}"));
    }

    /** 读取当前会话最新助手消息以核对持久化结果。 */
    private ChatMessage assistant() {
        return messages.selectList(new LambdaQueryWrapper<ChatMessage>().eq(ChatMessage::getConversationId, conversationId)
                .eq(ChatMessage::getRole, ChatMessageRole.ASSISTANT).orderByDesc(ChatMessage::getId)).getFirst();
    }

    /** 构造合法结论或故意不合法的逐字引文，以测试核验边界。 */
    private KnowledgeAnswerDraft draft(String quote) {
        return new KnowledgeAnswerDraft(KnowledgeAnswerDraft.Status.ANSWERABLE,
                List.of(new KnowledgeAnswerDraft.Claim(QUOTE, List.of(new KnowledgeAnswerDraft.Reference("E1", quote)))),
                List.of(), List.of());
    }

    /** 模拟模型选择本轮片段，支持构造不存在的编号以验证失败诊断。 */
    private KnowledgeAnswerSelection selection(KnowledgeCitationCatalog catalog, String paragraphId) {
        return new KnowledgeAnswerSelection(catalog.citationSetId(), KnowledgeAnswerDraft.Status.ANSWERABLE,
                List.of(new KnowledgeAnswerSelection.Claim(QUOTE,
                        List.of(new KnowledgeAnswerSelection.Reference("E1", paragraphId)))), List.of(), List.of());
    }

    /** 老诊断仍按原版本读取，新增目录和选择字段不在读历史时补造。 */
    @Test
    @DisplayName("版本一诊断和旧引用报告可继续反序列化")
    void readsLegacySnapshotAndReport() throws Exception {
        send().andExpect(status().isOk());
        var snapshot = runs.find(assistant().getKnowledgeRunId()).orElseThrow();
        com.fasterxml.jackson.databind.node.ObjectNode legacy = mapper.valueToTree(snapshot);
        legacy.put("schemaVersion", 1);
        legacy.remove(List.of("citationCatalog", "selection", "retrievalRun", "applicability", "answerContext"));
        jdbc.update("UPDATE knowledge_answer_runs SET snapshot=? WHERE run_id=?",
                mapper.writeValueAsString(legacy), snapshot.runId());
        var loaded = runs.find(snapshot.runId()).orElseThrow();
        assertThat(loaded.schemaVersion()).isEqualTo(1);
        assertThat(loaded.citationCatalog()).isNull();
        assertThat(loaded.selection()).isNull();
        assertThat(loaded.retrievalRun()).isNull();
        assertThat(loaded.draft()).isEqualTo(snapshot.draft());
        var report = assistant().getKnowledgeAnswer();
        assertThat(mapper.readValue(mapper.writeValueAsString(report), KnowledgeAnswerReport.class)).isEqualTo(report);
    }

    /** 为测试中的不同角色签发令牌，仅用于权限边界测试。 */
    private String authorization(UserRole role) {
        User user = new User();
        user.setId(CUSTOMER);
        user.setUsername("diagnostic-test");
        user.setRole(role);
        return "Bearer " + jwt.issue(user).value();
    }
}
