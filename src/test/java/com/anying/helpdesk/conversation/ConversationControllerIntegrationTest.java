package com.anying.helpdesk.conversation;

import com.anying.helpdesk.agent.AgentExecutionContext;
import com.anying.helpdesk.agent.domain.AgentToolCall;
import com.anying.helpdesk.agent.mapper.AgentToolCallMapper;
import com.anying.helpdesk.agent.service.AgentToolCallService;
import com.anying.helpdesk.auth.domain.User;
import com.anying.helpdesk.auth.domain.UserRole;
import com.anying.helpdesk.auth.mapper.UserMapper;
import com.anying.helpdesk.conversation.domain.ChatMessage;
import com.anying.helpdesk.conversation.domain.ChatMessageRole;
import com.anying.helpdesk.conversation.domain.ChatMessageStatus;
import com.anying.helpdesk.conversation.mapper.ChatMessageMapper;
import com.anying.helpdesk.conversation.mapper.ConversationMapper;
import com.anying.helpdesk.conversation.model.ChatCompletionResult;
import com.anying.helpdesk.conversation.model.ChatStreamChunk;
import com.anying.helpdesk.conversation.model.ConversationModelHistory;
import com.anying.helpdesk.conversation.model.ConversationModelClient;
import com.anying.helpdesk.modelconfig.factory.DefaultChatModelProvider;
import com.anying.helpdesk.modelconfig.factory.ResolvedChatModel;
import com.anying.helpdesk.modelconfig.exception.ModelConfigConflictException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConversationControllerIntegrationTest {

    private static final String PASSWORD = "Password123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Autowired
    private AgentToolCallMapper agentToolCallMapper;

    @Autowired
    private AgentToolCallService agentToolCallService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private DefaultChatModelProvider defaultChatModelProvider;

    @MockitoBean
    private ConversationModelClient modelClient;

    @AfterEach
    void cleanUp() {
        agentToolCallMapper.delete(null);
        chatMessageMapper.delete(null);
        conversationMapper.delete(null);
        userMapper.delete(new LambdaQueryWrapper<User>()
                .likeRight(User::getUsername, "conversation_"));
    }

    @Test
    void customerChatsWithHistoryOutsideDatabaseTransaction() throws Exception {
        String token = registerCustomer("conversation_customer_one");
        long conversationId = createConversation(token, "登录问题");
        ResolvedChatModel resolvedModel = new ResolvedChatModel(
                99L,
                "test-chat-model",
                true,
                mock(ChatModel.class)
        );
        when(defaultChatModelProvider.get()).thenReturn(resolvedModel);
        when(modelClient.complete(
                any(ResolvedChatModel.class),
                any(ConversationModelHistory.class),
                any()
        )).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            ConversationModelHistory modelHistory = invocation.getArgument(1);
            List<ChatMessage> history = modelHistory.messages();
            String lastUserMessage = history.getLast().getContent();
            return new ChatCompletionResult("AI: " + lastUserMessage, 12, 6, 18);
        });

        sendMessage(token, conversationId, "我无法登录")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userMessage.role").value("USER"))
                .andExpect(jsonPath("$.data.userMessage.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.assistantMessage.role").value("ASSISTANT"))
                .andExpect(jsonPath("$.data.assistantMessage.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.assistantMessage.content").value("AI: 我无法登录"))
                .andExpect(jsonPath("$.data.assistantMessage.modelConfigId").value(99))
                .andExpect(jsonPath("$.data.assistantMessage.modelName").value("test-chat-model"))
                .andExpect(jsonPath("$.data.assistantMessage.totalTokens").value(18));

        User customer = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, "conversation_customer_one"));
        ChatMessage firstAssistantMessage = chatMessageMapper.selectList(
                        new LambdaQueryWrapper<ChatMessage>()
                                .eq(ChatMessage::getConversationId, conversationId)
                                .eq(ChatMessage::getRole, ChatMessageRole.ASSISTANT)
                                .orderByDesc(ChatMessage::getId)
                )
                .getFirst();
        AgentToolCall toolCall = agentToolCallService.start(
                new AgentExecutionContext(customer.getId(), conversationId, firstAssistantMessage.getId()),
                "listCustomerTickets",
                "{\"status\":\"PENDING\"}"
        );
        agentToolCallService.completeSuccess(toolCall.getId(), "{\"tickets\":[]}");

        sendMessage(token, conversationId, "还是提示密码错误")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assistantMessage.content").value("AI: 还是提示密码错误"));

        mockMvc.perform(get("/api/conversations/{id}/messages", conversationId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.records[0].role").value("USER"))
                .andExpect(jsonPath("$.data.records[3].role").value("ASSISTANT"));

        ArgumentCaptor<ConversationModelHistory> historyCaptor = ArgumentCaptor.forClass(
                ConversationModelHistory.class
        );
        ArgumentCaptor<AgentExecutionContext> contextCaptor = ArgumentCaptor.forClass(AgentExecutionContext.class);
        verify(modelClient, org.mockito.Mockito.times(2)).complete(
                any(),
                historyCaptor.capture(),
                contextCaptor.capture()
        );
        ConversationModelHistory secondModelHistory = historyCaptor.getAllValues().get(1);
        List<ChatMessage> secondHistory = secondModelHistory.messages();
        assertThat(secondHistory)
                .extracting(ChatMessage::getRole)
                .containsExactly(ChatMessageRole.USER, ChatMessageRole.ASSISTANT, ChatMessageRole.USER);
        assertThat(secondHistory)
                .extracting(ChatMessage::getContent)
                .containsExactly("我无法登录", "AI: 我无法登录", "还是提示密码错误");
        assertThat(secondModelHistory.recentToolCalls())
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.getAssistantMessageId()).isEqualTo(firstAssistantMessage.getId());
                    assertThat(record.getToolName()).isEqualTo("listCustomerTickets");
                    assertThat(record.getResultJson()).isEqualTo("{\"tickets\":[]}");
                });
        assertThat(contextCaptor.getAllValues())
                .allSatisfy(context -> assertThat(context.conversationId()).isEqualTo(conversationId))
                .extracting(AgentExecutionContext::customerId)
                .containsOnly(contextCaptor.getValue().customerId());
        assertThat(contextCaptor.getAllValues())
                .allSatisfy(context -> assertThat(context.assistantMessageId()).isPositive());

        mockMvc.perform(get("/api/conversations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].lastMessageAt").isNotEmpty());
    }

    @Test
    void customerCannotReadOrSendToAnotherCustomersConversation() throws Exception {
        String ownerToken = registerCustomer("conversation_owner");
        String otherToken = registerCustomer("conversation_other");
        long conversationId = createConversation(ownerToken, "私有对话");

        mockMvc.perform(get("/api/conversations/{id}", conversationId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(otherToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONVERSATION_NOT_FOUND"));

        sendMessage(otherToken, conversationId, "尝试访问")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONVERSATION_NOT_FOUND"));

        verifyNoInteractions(defaultChatModelProvider, modelClient);
    }

    @Test
    void modelFailurePersistsSanitizedFailedAssistantMessage() throws Exception {
        String token = registerCustomer("conversation_customer_failure");
        long conversationId = createConversation(token, "失败测试");
        ResolvedChatModel resolvedModel = new ResolvedChatModel(
                100L,
                "failure-model",
                true,
                mock(ChatModel.class)
        );
        when(defaultChatModelProvider.get()).thenReturn(resolvedModel);
        doThrow(new IllegalStateException("provider leaked sk-secret-value"))
                .when(modelClient).complete(any(), any(ConversationModelHistory.class), any());

        sendMessage(token, conversationId, "触发失败")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("CHAT_GENERATION_FAILED"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("sk-secret-value")
                )));

        List<ChatMessage> messages = chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getConversationId, conversationId)
                        .orderByAsc(ChatMessage::getId)
        );
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).getStatus()).isEqualTo(ChatMessageStatus.COMPLETED);
        assertThat(messages.get(1).getStatus()).isEqualTo(ChatMessageStatus.FAILED);
        assertThat(messages.get(1).getErrorCode()).isEqualTo("MODEL_CALL_FAILED");
        assertThat(messages.get(1).getContent()).isNull();
    }

    @Test
    void supportCannotAccessCustomerConversationApi() throws Exception {
        String token = createUserAndLogin("conversation_support", UserRole.SUPPORT);

        mockMvc.perform(get("/api/conversations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void missingDefaultModelDoesNotPersistMessages() throws Exception {
        String token = registerCustomer("conversation_no_default");
        long conversationId = createConversation(token, "未配置模型");
        when(defaultChatModelProvider.get())
                .thenThrow(new ModelConfigConflictException("no enabled default model configuration is available"));

        sendMessage(token, conversationId, "你好")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MODEL_CONFIG_CONFLICT"));

        assertThat(chatMessageMapper.selectCount(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId))).isZero();
        verifyNoInteractions(modelClient);
    }

    @Test
    @DisplayName("客户应收到完整的 SSE 事件序列且最终回答正确入库")
    void customerReceivesStreamingAnswerAndPersistsCompletion() throws Exception {
        String token = registerCustomer("conversation_stream_success");
        long conversationId = createConversation(token, "流式成功测试");
        ResolvedChatModel resolvedModel = new ResolvedChatModel(
                101L,
                "stream-model",
                true,
                mock(ChatModel.class)
        );
        when(defaultChatModelProvider.get()).thenReturn(resolvedModel);
        when(modelClient.stream(any(), any(ConversationModelHistory.class), any())).thenReturn(Flux.just(
                new ChatStreamChunk("可以", null, null, null),
                new ChatStreamChunk("重置密码", 16, 5, 21)
        ));

        MvcResult streamingRequest = mockMvc.perform(post(
                                "/api/conversations/{id}/messages/stream",
                                conversationId
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("""
                                {"content": "我忘记密码了"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        String eventStream = mockMvc.perform(asyncDispatch(streamingRequest))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(eventStream)
                .contains("event:start", "event:delta", "event:done")
                .containsSubsequence("event:start", "event:delta", "event:delta", "event:done")
                .contains("\"content\":\"可以\"", "\"content\":\"重置密码\"")
                .doesNotContain("event:error");

        List<ChatMessage> messages = chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getConversationId, conversationId)
                        .orderByAsc(ChatMessage::getId)
        );
        assertThat(messages).hasSize(2);
        assertThat(messages.getLast().getStatus()).isEqualTo(ChatMessageStatus.COMPLETED);
        assertThat(messages.getLast().getContent()).isEqualTo("可以重置密码");
        assertThat(messages.getLast().getTotalTokens()).isEqualTo(21);
    }

    @Test
    @DisplayName("流式模型调用失败时应发送脱敏错误事件并保存失败状态")
    void streamingFailureSendsSanitizedErrorAndPersistsFailure() throws Exception {
        String token = registerCustomer("conversation_stream_failure");
        long conversationId = createConversation(token, "流式失败测试");
        ResolvedChatModel resolvedModel = new ResolvedChatModel(
                102L,
                "stream-failure-model",
                true,
                mock(ChatModel.class)
        );
        when(defaultChatModelProvider.get()).thenReturn(resolvedModel);
        when(modelClient.stream(any(), any(ConversationModelHistory.class), any()))
                .thenReturn(Flux.error(new IllegalStateException("provider leaked sk-stream-secret")));

        MvcResult streamingRequest = mockMvc.perform(post(
                                "/api/conversations/{id}/messages/stream",
                                conversationId
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("""
                                {"content": "触发流式失败"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        String eventStream = mockMvc.perform(asyncDispatch(streamingRequest))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(eventStream)
                .contains("event:start", "event:error", "CHAT_GENERATION_FAILED")
                .doesNotContain("sk-stream-secret", "event:done");

        List<ChatMessage> messages = chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getConversationId, conversationId)
                        .orderByAsc(ChatMessage::getId)
        );
        assertThat(messages).hasSize(2);
        assertThat(messages.getLast().getStatus()).isEqualTo(ChatMessageStatus.FAILED);
        assertThat(messages.getLast().getErrorCode()).isEqualTo("MODEL_CALL_FAILED");
    }

    @Test
    @DisplayName("默认模型未开启流式能力时不应保存任何消息")
    void modelWithoutStreamingSupportDoesNotPersistMessages() throws Exception {
        String token = registerCustomer("conversation_stream_unsupported");
        long conversationId = createConversation(token, "不支持流式测试");
        ResolvedChatModel resolvedModel = new ResolvedChatModel(
                103L,
                "non-stream-model",
                false,
                mock(ChatModel.class)
        );
        when(defaultChatModelProvider.get()).thenReturn(resolvedModel);

        mockMvc.perform(post("/api/conversations/{id}/messages/stream", conversationId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "尝试流式调用"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MODEL_STREAMING_NOT_SUPPORTED"));

        assertThat(chatMessageMapper.selectCount(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId))).isZero();
        verify(modelClient, never()).stream(any(), any(ConversationModelHistory.class), any());
    }

    private org.springframework.test.web.servlet.ResultActions sendMessage(
            String token,
            long conversationId,
            String message
    ) throws Exception {
        return mockMvc.perform(post("/api/conversations/{id}/messages", conversationId)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"content": "%s"}
                        """.formatted(message)));
    }

    private long createConversation(String token, String title) throws Exception {
        String response = mockMvc.perform(post("/api/conversations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "%s"}
                                """.formatted(title)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return ((Number) JsonPath.read(response, "$.data.id")).longValue();
    }

    private String registerCustomer(String username) throws Exception {
        String response = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "%s",
                                  "password": "%s",
                                  "displayName": "对话客户"
                                }
                                """.formatted(username, PASSWORD)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(response, "$.data.accessToken");
    }

    private String createUserAndLogin(String username, UserRole role) throws Exception {
        LocalDateTime now = LocalDateTime.now();
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setDisplayName("对话权限测试");
        user.setRole(role);
        user.setEnabled(true);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userMapper.insert(user);

        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "%s", "password": "%s"}
                                """.formatted(username, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(response, "$.data.accessToken");
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
