package com.anying.helpdesk.conversation;

import com.anying.helpdesk.auth.domain.User;
import com.anying.helpdesk.auth.domain.UserRole;
import com.anying.helpdesk.conversation.domain.ChatMessage;
import com.anying.helpdesk.conversation.domain.ChatMessageStatus;
import com.anying.helpdesk.conversation.domain.ChatMode;
import com.anying.helpdesk.conversation.dto.SendMessageRequest;
import com.anying.helpdesk.conversation.mapper.ChatMessageMapper;
import com.anying.helpdesk.conversation.mapper.ConversationMapper;
import com.anying.helpdesk.conversation.model.ChatCompletionResult;
import com.anying.helpdesk.conversation.model.ConversationModelClient;
import com.anying.helpdesk.conversation.service.ConversationService;
import com.anying.helpdesk.knowledge.answer.KnowledgeAnswerDraft;
import com.anying.helpdesk.knowledge.answer.KnowledgeAnswerReport;
import com.anying.helpdesk.knowledge.answer.KnowledgeAnswerFailure;
import com.anying.helpdesk.knowledge.answer.TrustedKnowledgeAnswerService;
import com.anying.helpdesk.knowledge.dto.KnowledgeDocumentMetadata;
import com.anying.helpdesk.modelconfig.factory.DefaultChatModelProvider;
import com.anying.helpdesk.modelconfig.factory.ResolvedChatModel;
import com.anying.helpdesk.security.JwtTokenService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import com.anying.helpdesk.conversation.dto.ChatStreamEvent;
import com.anying.helpdesk.knowledge.answer.trace.KnowledgeAnswerTrace;
import static com.anying.helpdesk.knowledge.answer.trace.KnowledgeAnswerRunSnapshot.Stage.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TrustedKnowledgeConversationIntegrationTest {
    private static final long CUSTOMER = 91001L;
    @Autowired private MockMvc mvc;
    @Autowired private JwtTokenService jwt;
    @Autowired private ChatMessageMapper messages;
    @Autowired private ConversationMapper conversations;
    @Autowired private ConversationService service;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private DefaultChatModelProvider provider;
    @MockitoBean private ConversationModelClient agentClient;
    @MockitoBean private TrustedKnowledgeAnswerService knowledge;
    private long conversationId;
    private String authorization;

    /** 创建独立对话，模型故意不支持流式以验证知识模式只依赖同步生成。 */
    @BeforeEach
    void setup() throws Exception {
        User user = new User();
        user.setId(CUSTOMER);
        user.setUsername("knowledge_test");
        user.setRole(UserRole.CUSTOMER);
        authorization = "Bearer " + jwt.issue(user).value();
        String body = mvc.perform(post("/api/conversations").header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"知识问答\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        conversationId = ((Number) JsonPath.read(body, "$.data.id")).longValue();
        when(provider.get()).thenReturn(new ResolvedChatModel(1L, "test", false, mock(ChatModel.class)));
        when(knowledge.answer(any(), any(), any(), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return completion(invocation.getArgument(2));
        });
    }

    /** 清理本类创建的数据，保留其他用例的知识文档。 */
    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM knowledge_answer_runs WHERE conversation_id=?", conversationId);
        messages.delete(new LambdaQueryWrapper<ChatMessage>().eq(ChatMessage::getConversationId, conversationId));
        conversations.deleteById(conversationId);
    }

    /** 验证真实数据库 JSON 往返和历史引用来源，不只检查即刻内存响应。 */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(ChatMode.class)
    @DisplayName("同步知识问答保存模式、范围、引文及业务版本并能回读")
    void persistsReportAndHistory(ChatMode mode) throws Exception {
        send("{\"content\":\"上海住宿额度？\",\"mode\":\"%s\",\"applicabilityScope\":\"SH\"}".formatted(mode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assistantMessage.mode").value("KNOWLEDGE"))
                .andExpect(jsonPath("$.data.assistantMessage.knowledgeAnswer.answer.status").value("ANSWERABLE"))
                .andExpect(jsonPath("$.data.assistantMessage.citations[0].documentId").value(10));
        var saved = assistant();
        assertThat(saved.getKnowledgeAnswer()).isEqualTo(completion().knowledgeAnswer());
        assertThat(saved.getApplicabilityScope()).isEqualTo("SH");
        mvc.perform(get("/api/conversations/{id}/messages", conversationId).header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[1].knowledgeAnswer.evidence[0].metadata.businessVersion").value("v2"))
                .andExpect(jsonPath("$.data.records[1].citations[0].content").value("上海住宿上限每晚600元。"));
        assertThat(saved.getPromptTokens()).isEqualTo(30);
        assertThat(saved.getCompletionTokens()).isEqualTo(10);
        assertThat(saved.getTotalTokens()).isEqualTo(40);
        assertThat(jdbc.queryForObject("SELECT status FROM knowledge_answer_runs WHERE run_id=?", String.class, saved.getKnowledgeRunId())).isEqualTo("SUCCEEDED");
        verifyNoInteractions(agentClient);
    }

    /** 验证升级前的历史消息保留正文并默认归为普通模式，不补造核验记录。 */
    @Test
    @DisplayName("V17 升级保留旧消息并将其标为 AGENT")
    void migratesLegacyMessages() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:knowledge_answer_migration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(dataSource).target("16").load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO chat_messages (conversation_id, role, content, status) VALUES (1, 'ASSISTANT', '旧回答', 'COMPLETED')");
        Flyway.configure().dataSource(dataSource).load().migrate();
        var row = jdbc.queryForMap("SELECT * FROM chat_messages");
        assertThat(row.get("content")).isEqualTo("旧回答");
        assertThat(row.get("mode")).isEqualTo("AGENT");
        assertThat(row.get("knowledge_answer")).isNull();
        assertThat(row.get("applicability_scope")).isNull();
    }

    /** 即使调用新模式也不能读取或继续其他客户的对话。 */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(ChatMode.class)
    @DisplayName("知识模式不能绕过会话归属校验")
    void enforcesOwnershipBeforeModelCalls(ChatMode mode) throws Exception {
        User other = new User();
        other.setId(CUSTOMER + 1);
        other.setUsername("other_knowledge_customer");
        other.setRole(UserRole.CUSTOMER);
        mvc.perform(post("/api/conversations/{id}/messages", conversationId)
                        .header("Authorization", "Bearer " + jwt.issue(other).value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"问题\",\"mode\":\"%s\"}".formatted(mode)))
                .andExpect(status().isNotFound());
        verifyNoInteractions(provider, knowledge, agentClient);
    }

    /** 客户端参数问题必须在写入用户消息前返回。 */
    @Test
    @DisplayName("所有入口拒绝非法范围超长问题和无效续答")
    void validatesBeforeSaving() throws Exception {
        send(mapper.writeValueAsString(new SendMessageRequest("问".repeat(2001), ChatMode.KNOWLEDGE, "SH")))
                .andExpect(status().isBadRequest());
        send("{\"content\":\"问题\",\"mode\":\"KNOWLEDGE\",\"applicabilityScope\":\"sh\"}")
                .andExpect(status().isBadRequest());
        send("{\"content\":\"问题\",\"mode\":\"AGENT\",\"applicabilityScopes\":[\"ALL\",\"SH\"]}")
                .andExpect(status().isBadRequest());
        send(mapper.writeValueAsString(new SendMessageRequest("问".repeat(2001))))
                .andExpect(status().isBadRequest());
        send("{\"content\":\"问题\",\"clarificationMessageId\":1}")
                .andExpect(status().isBadRequest());
        send(mapper.writeValueAsString(java.util.Map.of("content", "问".repeat(2001), "mode", "AGENT")))
                .andExpect(status().isBadRequest());
        send("{\"content\":\"问题\",\"mode\":\"UNKNOWN\"}").andExpect(status().isBadRequest());
        assertThat(messages.selectCount(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId))).isZero();
        verifyNoInteractions(knowledge);
    }

    /** 模型核验异常必须落入失败状态，不能生成一条看似完成的拒答。 */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(ChatMode.class)
    @DisplayName("知识核验失败时无正文无报告且异常细节不会外泄")
    void failsClosed(ChatMode mode) throws Exception {
        when(knowledge.answer(any(), any(), any(), any())).thenThrow(new IllegalStateException("private draft secret"));
        var response = send("{\"content\":\"问题\",\"mode\":\"%s\"}".formatted(mode))
                .andExpect(status().isBadGateway()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("private draft secret");
        assertThat(assistant().getStatus()).isEqualTo(ChatMessageStatus.FAILED);
        assertThat(assistant().getContent()).isNull();
        assertThat(assistant().getKnowledgeAnswer()).isNull();
        assertThat(assistant().getApplicabilityScope()).isEqualTo("ALL");
        assertThat(assistant().getErrorCode()).isEqualTo("KNOWLEDGE_ANSWER_FAILED");
        verifyNoInteractions(agentClient);
    }

    /** 同步和流式两条入口持久化同一个分类码，客户端错误正文仍脱敏。 */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(ChatMode.class)
    @DisplayName("知识失败阶段能从历史消息追溯且不会暴露内部说明")
    void persistsSafeFailureStageInBothModes(ChatMode mode) throws Exception {
        when(knowledge.answer(any(), any(), any(), any())).thenThrow(new KnowledgeAnswerFailure(
                KnowledgeAnswerFailure.Code.KNOWLEDGE_CITATION_INVALID, "private unverified draft"));
        String response = send("{\"content\":\"问题\",\"mode\":\"%s\"}".formatted(mode))
                .andExpect(status().isBadGateway()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("private unverified draft");
        assertThat(assistant().getErrorCode()).isEqualTo("KNOWLEDGE_CITATION_INVALID");
        var events = service.streamMessage(conversationId,
                new SendMessageRequest("问题", mode, "ALL"), CUSTOMER).collectList().block(Duration.ofSeconds(5));
        assertThat(events).extracting(event -> event.type()).containsExactly("start", "error");
        assertThat(assistant().getErrorCode()).isEqualTo("KNOWLEDGE_CITATION_INVALID");
        assertThat(assistant().getKnowledgeAnswer()).isNull();
    }

    /** SSE 只展示经过知识服务核验的最终正文，不要求模型厂商支持流式。 */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(ChatMode.class)
    @DisplayName("知识 SSE 成功时按 start、delta、done 返回并保存报告")
    void streamsOnlyFinalAnswer(ChatMode mode) throws Exception {
        var pending = mvc.perform(post("/api/conversations/{id}/messages/stream", conversationId)
                        .header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"content\":\"上海住宿额度？\",\"applicabilityScope\":\"SH\",\"mode\":\"%s\"}".formatted(mode)))
                .andExpect(request().asyncStarted()).andReturn();
        var response = mvc.perform(asyncDispatch(pending)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(response).contains("event:start", "event:progress", "MESSAGE_PERSISTENCE", "event:delta", "event:done", "knowledgeAnswer");
        assertThat(response).doesNotContain("event:action_required");
        assertThat(response.indexOf("event:start")).isLessThan(response.indexOf("event:delta"));
        assertThat(response.indexOf("event:delta")).isLessThan(response.indexOf("event:done"));
        assertThat(assistant().getKnowledgeAnswer()).isNotNull();
        assertThat(assistant().getMode()).isEqualTo(ChatMode.KNOWLEDGE);
        verifyNoInteractions(agentClient);
    }

    /** 复核失败时 SSE 不允许先发草案再报错。 */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(ChatMode.class)
    @DisplayName("知识 SSE 核验失败时只发 start 和 error")
    void failedStreamNeverEmitsDelta(ChatMode mode) throws Exception {
        when(knowledge.answer(any(), any(), any(), any())).thenThrow(new IllegalStateException("unverified draft"));
        var events = service.streamMessage(conversationId,
                new SendMessageRequest("问题", mode, "ALL"), CUSTOMER).collectList().block(Duration.ofSeconds(5));
        assertThat(events).extracting(event -> event.type()).containsExactly("start", "error");
        assertThat(assistant().getContent()).isNull();
        verifyNoInteractions(agentClient);
    }

    /** 在模型仍阻塞时必须已经收到阶段事件，修订后重复核验也必须按真实顺序通知。 */
    @Test
    @DisplayName("阶段实时送达且修订复核顺序正确，正文始终等待核验结束")
    void streamsProgressBeforeModelFinishes() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<Void> release = new CompletableFuture<>();
        when(knowledge.answer(any(), any(), any(), any())).thenAnswer(invocation -> {
            KnowledgeAnswerTrace trace = invocation.getArgument(3);
            trace.stage(RETRIEVAL, () -> "private evidence");
            trace.stage(DRAFT, () -> {
                entered.countDown();
                release.join();
                return "private draft";
            });
            trace.stage(REVIEW, () -> false);
            trace.stage(REVISION, () -> "private revision");
            trace.stage(VALIDATION, () -> true);
            trace.stage(REVIEW, () -> true);
            return completion();
        });
        var events = new CopyOnWriteArrayList<ChatStreamEvent>();
        CountDownLatch ended = new CountDownLatch(1);
        var subscription = service.streamMessage(conversationId,
                new SendMessageRequest("问题", ChatMode.KNOWLEDGE, "SH"), CUSTOMER)
                .doFinally(signal -> ended.countDown()).subscribe(events::add);
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(events).extracting(ChatStreamEvent::type).containsExactly("start", "progress", "progress");
            assertThat(assistant().getStatus()).isEqualTo(ChatMessageStatus.PENDING);
            release.complete(null);
            assertThat(ended.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(events.stream().filter(e -> e.type().equals("progress"))).extracting(ChatStreamEvent::stage)
                    .containsExactly(RETRIEVAL, DRAFT, REVIEW, REVISION, VALIDATION, REVIEW, MESSAGE_PERSISTENCE);
            assertThat(events.stream().filter(e -> e.type().equals("progress"))).allSatisfy(event -> {
                assertThat(event.assistantMessageId()).isEqualTo(assistant().getId());
                assertThat(event.content()).isNull();
                assertThat(event.assistantMessage()).isNull();
                assertThat(event.message()).isNotBlank();
            });
            assertThat(mapper.writeValueAsString(events)).doesNotContain("private evidence", "private draft", "private revision");
            assertThat(events.subList(events.size() - 2, events.size())).extracting(ChatStreamEvent::type)
                    .containsExactly("delta", "done");
        } finally {
            release.complete(null);
            subscription.dispose();
        }
    }

    /** 客户端收到保存阶段立即取消时，不能因通知回调重入而继续保存答案。 */
    @Test
    @DisplayName("保存阶段通知触发取消时不执行最终落库")
    void cancellingAtPersistenceProgressPreventsCompletion() {
        service.streamMessage(conversationId,
                new SendMessageRequest("问题", ChatMode.KNOWLEDGE, "SH"), CUSTOMER)
                .takeUntil(event -> event.stage() == MESSAGE_PERSISTENCE)
                .collectList().block(Duration.ofSeconds(5));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(assistant().getStatus()).isEqualTo(ChatMessageStatus.FAILED);
            assertThat(assistant().getErrorCode()).isEqualTo("STREAM_CANCELLED");
            assertThat(assistant().getContent()).isNull();
        });
    }

    /** 已发送阶段提示并不代表核验通过，失败时仍只能返回安全错误。 */
    @Test
    @DisplayName("复核阶段报错保留进度但不发送草案或完成事件")
    void progressFailureNeverLeaksDraft() throws Exception {
        when(knowledge.answer(any(), any(), any(), any())).thenAnswer(invocation -> {
            KnowledgeAnswerTrace trace = invocation.getArgument(3);
            return trace.stage(REVIEW, () -> { throw new IllegalStateException("private failed draft"); });
        });
        var events = service.streamMessage(conversationId,
                new SendMessageRequest("问题", ChatMode.KNOWLEDGE, "SH"), CUSTOMER)
                .collectList().block(Duration.ofSeconds(5));
        assertThat(events).extracting(ChatStreamEvent::type).containsExactly("start", "progress", "error");
        assertThat(mapper.writeValueAsString(events)).doesNotContain("private failed draft");
        assertThat(assistant().getStatus()).isEqualTo(ChatMessageStatus.FAILED);
        assertThat(assistant().getContent()).isNull();
    }

    /** 缺省、显式空值、知识模式与旧AGENT别名均使用同一可信路径，历史回读保持核验报告。 */
    @ParameterizedTest
    @ValueSource(strings = {"{\"content\":\"报销期限？\"}",
            "{\"content\":\"报销期限？\",\"mode\":null}",
            "{\"content\":\"报销期限？\",\"mode\":\"KNOWLEDGE\"}",
            "{\"content\":\"报销期限？\",\"mode\":\"AGENT\"}"})
    @DisplayName("四种请求形态均持久化核验报告且不触发旧模型")
    void defaultsToTrustedKnowledge(String body) throws Exception {
        send(body).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userMessage.mode").value("KNOWLEDGE"))
                .andExpect(jsonPath("$.data.assistantMessage.mode").value("KNOWLEDGE"))
                .andExpect(jsonPath("$.data.assistantMessage.applicabilityScope").value("ALL"))
                .andExpect(jsonPath("$.data.assistantMessage.knowledgeAnswer.applicabilityScope").value("ALL"))
                .andExpect(jsonPath("$.data.assistantMessage.knowledgeRunId").isNotEmpty())
                .andExpect(jsonPath("$.data.pendingActions").isEmpty());
        assertThat(assistant().getKnowledgeAnswer()).isNotNull();
        mvc.perform(get("/api/conversations/{id}/messages", conversationId).header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[1].mode").value("KNOWLEDGE"))
                .andExpect(jsonPath("$.data.records[1].knowledgeAnswer").exists());
        verify(knowledge).answer(any(), eq("报销期限？"), eq("ALL"), any());
        verifyNoInteractions(agentClient);
    }

    /** 旧模式历史保持原标记；同一会话的新缺省请求改用知识链路，不给旧回答补造核验。 */
    @Test
    @DisplayName("升级前AGENT历史不补造核验且新消息统一可信")
    void preservesLegacyHistoryWithoutUpgradingItsTrust() throws Exception {
        jdbc.update("INSERT INTO chat_messages (conversation_id, role, content, status, mode) VALUES (?, 'USER', '旧问题', 'COMPLETED', 'AGENT')", conversationId);
        jdbc.update("INSERT INTO chat_messages (conversation_id, role, content, status, mode) VALUES (?, 'ASSISTANT', '旧模式回答', 'COMPLETED', 'AGENT')", conversationId);
        assertThat(assistant().getKnowledgeRunId()).isNull();
        send("{\"content\":\"新问题\"}").andExpect(status().isOk());
        mvc.perform(get("/api/conversations/{id}/messages", conversationId).header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[1].mode").value("AGENT"))
                .andExpect(jsonPath("$.data.records[1].content").value("旧模式回答"))
                .andExpect(jsonPath("$.data.records[1].knowledgeAnswer").doesNotExist())
                .andExpect(jsonPath("$.data.records[3].mode").value("KNOWLEDGE"))
                .andExpect(jsonPath("$.data.records[3].knowledgeAnswer").exists());
        verifyNoInteractions(agentClient);
    }

    /** 模拟忽略线程中断的迟到模型响应，检查取消后不落库为完成。 */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(ChatMode.class)
    @DisplayName("核验期间取消连接后迟到结果不能覆盖失败状态")
    void cancelledStreamCannotPersistLateResult(ChatMode mode) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        CompletableFuture<Void> release = new CompletableFuture<>();
        when(knowledge.answer(any(), any(), any(), any())).thenAnswer(invocation -> {
            entered.countDown();
            release.join();
            finished.countDown();
            return completion();
        });
        var subscription = service.streamMessage(conversationId,
                new SendMessageRequest("问题", mode, "SH"), CUSTOMER).subscribe();
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            subscription.dispose();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(assistant().getStatus()).isEqualTo(ChatMessageStatus.FAILED));
        } finally {
            release.complete(null);
            subscription.dispose();
        }
        assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
        await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(assistant().getErrorCode()).isEqualTo("STREAM_CANCELLED");
            assertThat(assistant().getKnowledgeAnswer()).isNull();
            assertThat(jdbc.queryForObject("SELECT status FROM knowledge_answer_runs WHERE run_id=?", String.class,
                    assistant().getKnowledgeRunId())).isEqualTo("CANCELLED");
        });
        verifyNoInteractions(agentClient);
    }

    /** 发送当前对话的同步请求。 */
    private ResultActions send(String json) throws Exception {
        return mvc.perform(post("/api/conversations/{id}/messages", conversationId)
                .header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    /** 读取真实数据库中本轮助手记录。 */
    private ChatMessage assistant() {
        return messages.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId).orderByAsc(ChatMessage::getId)).getLast();
    }

    /** 提供一份已核验报告作为会话边界测试输入，语义判断另由知识服务测试覆盖。 */
    private ChatCompletionResult completion() {
        return completion("SH");
    }

    /** 为入口边界测试返回与请求范围一致的核验报告，不访问真实模型。 */
    private ChatCompletionResult completion(String scope) {
        String quote = "上海住宿上限每晚600元。";
        var draft = new KnowledgeAnswerDraft(KnowledgeAnswerDraft.Status.ANSWERABLE,
                List.of(new KnowledgeAnswerDraft.Claim(quote,
                        List.of(new KnowledgeAnswerDraft.Reference("E1", quote)))), List.of(), List.of());
        var report = new KnowledgeAnswerReport(1, KnowledgeAnswerReport.Reason.REVIEWED, 2L, "制度库", scope,
                LocalDateTime.of(2026, 10, 18, 8, 0), draft,
                List.of(new KnowledgeAnswerReport.Evidence("E1", 10L, 1, "住宿.md", quote,
                        new KnowledgeDocumentMetadata("travel", "v2", "SH", LocalDateTime.of(2026, 10, 1, 0, 0), null))));
        return new ChatCompletionResult(quote + " [E1]", 30, 10, 40, report);
    }
}
