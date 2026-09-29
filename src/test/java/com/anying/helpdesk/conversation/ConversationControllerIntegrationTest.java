package com.anying.helpdesk.conversation;

import com.anying.helpdesk.agent.AgentExecutionContext;
import com.anying.helpdesk.agent.action.mapper.AgentPendingActionMapper;
import com.anying.helpdesk.agent.action.domain.AgentPendingAction;
import com.anying.helpdesk.agent.action.domain.PendingActionStatus;
import com.anying.helpdesk.agent.action.service.PendingActionService;
import com.anying.helpdesk.agent.domain.AgentToolCall;
import com.anying.helpdesk.agent.domain.AgentToolCallStatus;
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
import com.anying.helpdesk.conversation.service.ConversationPersistenceService;
import com.anying.helpdesk.conversation.service.PreparedChatExchange;
import com.anying.helpdesk.conversation.service.StaleChatGenerationRecoveryService;
import com.anying.helpdesk.modelconfig.factory.DefaultChatModelProvider;
import com.anying.helpdesk.modelconfig.factory.ResolvedChatModel;
import com.anying.helpdesk.modelconfig.exception.ModelConfigConflictException;
import com.anying.helpdesk.ticket.domain.TicketPriority;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
    private AgentPendingActionMapper pendingActionMapper;

    @Autowired
    private PendingActionService pendingActionService;

    @Autowired
    private ConversationPersistenceService conversationPersistenceService;

    @Autowired
    private StaleChatGenerationRecoveryService staleGenerationRecoveryService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private DefaultChatModelProvider defaultChatModelProvider;

    @MockitoBean
    private ConversationModelClient modelClient;

    @AfterEach
    void cleanUp() {
        pendingActionMapper.delete(null);
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
                .andExpect(jsonPath("$.data.assistantMessage.totalTokens").value(18))
                .andExpect(jsonPath("$.data.pendingActions").isEmpty());

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
    @DisplayName("知识检索结果应作为引用随本轮回答返回并保留在历史消息中")
    void knowledgeCitationsAreReturnedWithCurrentAndHistoricalMessages() throws Exception {
        String token = registerCustomer("conversation_knowledge_citations");
        long conversationId = createConversation(token, "账号锁定规则");
        ResolvedChatModel resolvedModel = new ResolvedChatModel(
                105L,
                "citation-model",
                true,
                mock(ChatModel.class)
        );
        when(defaultChatModelProvider.get()).thenReturn(resolvedModel);
        when(modelClient.complete(any(), any(ConversationModelHistory.class), any()))
                .thenAnswer(invocation -> {
                    AgentExecutionContext context = invocation.getArgument(2);
                    AgentToolCall knowledgeCall = agentToolCallService.start(
                            context,
                            "search_knowledge_base",
                            "{\"query\":\"账号锁定规则\"}"
                    );
                    agentToolCallService.completeSuccess(knowledgeCall.getId(), """
                            {
                              "status": "FOUND",
                              "message": "请仅依据以下资料回答",
                              "knowledgeBase": "产品帮助中心",
                              "query": "账号锁定规则",
                              "chunks": [
                                {
                                  "rank": 1,
                                  "source": "账号安全说明.pdf",
                                  "chunkSequence": 8,
                                  "content": "连续五次输入错误密码后，账号将锁定三十分钟。"
                                }
                              ]
                            }
                            """);
                    AgentToolCall ticketCall = agentToolCallService.start(
                            context,
                            "list_customer_tickets",
                            "{}"
                    );
                    agentToolCallService.completeSuccess(ticketCall.getId(), "{\"tickets\":[]}");
                    return new ChatCompletionResult("账号会锁定三十分钟。", 10, 6, 16);
                });

        sendMessage(token, conversationId, "密码输错后会锁定多久？")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userMessage.citations").isEmpty())
                .andExpect(jsonPath("$.data.assistantMessage.citations.length()").value(1))
                .andExpect(jsonPath("$.data.assistantMessage.citations[0].knowledgeBaseName")
                        .value("产品帮助中心"))
                .andExpect(jsonPath("$.data.assistantMessage.citations[0].sourceName")
                        .value("账号安全说明.pdf"))
                .andExpect(jsonPath("$.data.assistantMessage.citations[0].chunkSequence").value(8))
                .andExpect(jsonPath("$.data.assistantMessage.citations[0].content")
                        .value("连续五次输入错误密码后，账号将锁定三十分钟。"));

        mockMvc.perform(get("/api/conversations/{id}/messages", conversationId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].citations").isEmpty())
                .andExpect(jsonPath("$.data.records[1].citations.length()").value(1))
                .andExpect(jsonPath("$.data.records[1].citations[0].sourceName")
                        .value("账号安全说明.pdf"));
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

        mockMvc.perform(delete("/api/conversations/{id}", conversationId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(otherToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONVERSATION_NOT_FOUND"));

        verifyNoInteractions(defaultChatModelProvider, modelClient);
    }

    /**
     * 验证删除对话采用归档语义，活动列表隐藏但历史消息继续保留且不能再次发送。
     */
    @Test
    @DisplayName("客户删除自己的对话后应归档并保留历史")
    void customerDeletesOwnConversationByArchivingIt() throws Exception {
        String token = registerCustomer("conversation_delete");
        long conversationId = createConversation(token, "准备删除的对话");
        when(defaultChatModelProvider.get()).thenReturn(new ResolvedChatModel(
                107L,
                "delete-test-model",
                true,
                mock(ChatModel.class)
        ));
        when(modelClient.complete(any(), any(ConversationModelHistory.class), any()))
                .thenReturn(new ChatCompletionResult("这条历史应当保留", 6, 5, 11));
        sendMessage(token, conversationId, "保存一轮历史").andExpect(status().isOk());

        mockMvc.perform(delete("/api/conversations/{id}", conversationId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/conversations/{id}", conversationId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/conversations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/api/conversations/{id}", conversationId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ARCHIVED"));
        mockMvc.perform(get("/api/conversations/{id}/messages", conversationId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));
        sendMessage(token, conversationId, "归档后不能继续发送")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONVERSATION_NOT_ACTIVE"));
    }

    /**
     * 验证非流式接口直接返回可供前端渲染的结构化确认卡片。
     */
    @Test
    @DisplayName("同步对话应在响应中返回 AI 生成的待确认操作")
    void synchronousChatReturnsPendingAction() throws Exception {
        String token = registerCustomer("conversation_action_sync");
        long conversationId = createConversation(token, "创建工单");
        ResolvedChatModel resolvedModel = new ResolvedChatModel(
                104L,
                "action-model",
                true,
                mock(ChatModel.class)
        );
        when(defaultChatModelProvider.get()).thenReturn(resolvedModel);
        when(modelClient.complete(any(), any(ConversationModelHistory.class), any()))
                .thenAnswer(invocation -> {
                    AgentExecutionContext context = invocation.getArgument(2);
                    pendingActionService.prepareCreateTicket(
                            context,
                            "无法完成支付",
                            "客户付款时持续提示支付失败，需要人工处理",
                            TicketPriority.HIGH,
                            "核心支付流程完全不可用"
                    );
                    return new ChatCompletionResult("工单草稿已经整理好，请确认。", 10, 8, 18);
                });

        sendMessage(token, conversationId, "帮我转人工处理")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pendingActions[0].actionType").value("CREATE_TICKET"))
                .andExpect(jsonPath("$.data.pendingActions[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data.pendingActions[0].title").value("无法完成支付"))
                .andExpect(jsonPath("$.data.pendingActions[0].priority").value("HIGH"))
                .andExpect(jsonPath("$.data.pendingActions[0].version").value(0));
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
        when(modelClient.complete(any(), any(ConversationModelHistory.class), any()))
                .thenAnswer(invocation -> {
                    AgentExecutionContext context = invocation.getArgument(2);
                    pendingActionService.prepareCreateTicket(
                            context,
                            "失败前创建的草稿",
                            "模型最终回答失败时该草稿必须失效",
                            TicketPriority.MEDIUM,
                            "一般问题"
                    );
                    throw new IllegalStateException("provider leaked sk-secret-value");
                });

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
        AgentPendingAction invalidatedAction = pendingActionMapper.selectOne(
                new LambdaQueryWrapper<AgentPendingAction>()
                        .eq(AgentPendingAction::getAssistantMessageId, messages.get(1).getId())
        );
        assertThat(invalidatedAction.getStatus()).isEqualTo(PendingActionStatus.INVALIDATED);
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

    /**
     * 验证上一条 AI 回复尚未生成完成时，同一对话不能创建下一轮消息。
     */
    @Test
    @DisplayName("同一对话生成回复期间应拒绝再次发送消息")
    void rejectsNewMessageWhileAssistantResponseIsPending() throws Exception {
        String username = "conversation_busy";
        String token = registerCustomer(username);
        long conversationId = createConversation(token, "并发发送测试");
        User customer = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username)
        );
        ResolvedChatModel resolvedModel = new ResolvedChatModel(
                104L,
                "pending-model",
                true,
                mock(ChatModel.class)
        );
        when(defaultChatModelProvider.get()).thenReturn(resolvedModel);
        conversationPersistenceService.prepareExchange(
                conversationId,
                customer.getId(),
                "第一条消息",
                resolvedModel
        );

        sendMessage(token, conversationId, "第二条消息")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONVERSATION_GENERATION_IN_PROGRESS"));
        mockMvc.perform(post("/api/conversations/{id}/messages/stream", conversationId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "第三条消息"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONVERSATION_GENERATION_IN_PROGRESS"));
        mockMvc.perform(delete("/api/conversations/{id}", conversationId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONVERSATION_GENERATION_IN_PROGRESS"));

        List<ChatMessage> messages = chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getConversationId, conversationId)
                        .orderByAsc(ChatMessage::getId)
        );
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).getContent()).isEqualTo("第一条消息");
        assertThat(messages.get(1).getStatus()).isEqualTo(ChatMessageStatus.PENDING);
        verifyNoInteractions(modelClient);
    }

    /**
     * 验证进程中断遗留的过期回答会在下一次发送前失败化，并同步清理半完成的关联状态。
     */
    @Test
    @DisplayName("过期的生成任务应自动恢复并允许客户继续对话")
    void recoversExpiredPendingMessageBeforeNextExchange() throws Exception {
        String username = "conversation_interrupted";
        String token = registerCustomer(username);
        long conversationId = createConversation(token, "中断恢复测试");
        User customer = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username)
        );
        ResolvedChatModel resolvedModel = new ResolvedChatModel(
                105L,
                "recovery-model",
                true,
                mock(ChatModel.class)
        );
        when(defaultChatModelProvider.get()).thenReturn(resolvedModel);
        when(modelClient.complete(any(), any(ConversationModelHistory.class), any()))
                .thenReturn(new ChatCompletionResult("现在可以继续对话", 8, 5, 13));

        PreparedChatExchange abandoned = conversationPersistenceService.prepareExchange(
                conversationId,
                customer.getId(),
                "生成时服务被关闭",
                resolvedModel
        );
        AgentExecutionContext abandonedContext = new AgentExecutionContext(
                customer.getId(),
                conversationId,
                abandoned.assistantMessageId()
        );
        AgentToolCall runningToolCall = agentToolCallService.start(
                abandonedContext,
                "search_knowledge_base",
                "{\"query\":\"测试\"}"
        );
        pendingActionService.prepareCreateTicket(
                abandonedContext,
                "未完成回答中的草稿",
                "生成中断后不应继续确认该草稿",
                TicketPriority.MEDIUM,
                "测试中断恢复"
        );
        chatMessageMapper.update(null, new LambdaUpdateWrapper<ChatMessage>()
                .eq(ChatMessage::getId, abandoned.assistantMessageId())
                .set(ChatMessage::getUpdatedAt, LocalDateTime.now().minusMinutes(11)));

        sendMessage(token, conversationId, "恢复后继续提问")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assistantMessage.content").value("现在可以继续对话"));

        ChatMessage recoveredMessage = chatMessageMapper.selectById(abandoned.assistantMessageId());
        assertThat(recoveredMessage.getStatus()).isEqualTo(ChatMessageStatus.FAILED);
        assertThat(recoveredMessage.getErrorCode())
                .isEqualTo(StaleChatGenerationRecoveryService.GENERATION_INTERRUPTED);
        AgentToolCall recoveredToolCall = agentToolCallMapper.selectById(runningToolCall.getId());
        assertThat(recoveredToolCall.getStatus()).isEqualTo(AgentToolCallStatus.FAILED);
        assertThat(recoveredToolCall.getCompletedAt()).isNotNull();
        AgentPendingAction recoveredAction = pendingActionMapper.selectOne(
                new LambdaQueryWrapper<AgentPendingAction>()
                        .eq(AgentPendingAction::getAssistantMessageId, abandoned.assistantMessageId())
        );
        assertThat(recoveredAction.getStatus()).isEqualTo(PendingActionStatus.INVALIDATED);
        assertThat(chatMessageMapper.selectCount(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId)
                .eq(ChatMessage::getStatus, ChatMessageStatus.PENDING))).isZero();
    }

    /**
     * 验证后台扫描使用的全局入口也能回收过期消息，而不依赖客户再次发送。
     */
    @Test
    @DisplayName("后台扫描应主动回收过期的生成任务")
    void scheduledRecoveryEntryRecoversExpiredPendingMessage() throws Exception {
        String username = "conversation_scheduled_recovery";
        String token = registerCustomer(username);
        long conversationId = createConversation(token, "后台恢复测试");
        User customer = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username)
        );
        PreparedChatExchange abandoned = conversationPersistenceService.prepareExchange(
                conversationId,
                customer.getId(),
                "等待后台恢复",
                new ResolvedChatModel(106L, "recovery-model", true, mock(ChatModel.class))
        );
        chatMessageMapper.update(null, new LambdaUpdateWrapper<ChatMessage>()
                .eq(ChatMessage::getId, abandoned.assistantMessageId())
                .set(ChatMessage::getUpdatedAt, LocalDateTime.now().minusMinutes(11)));

        assertThat(staleGenerationRecoveryService.recoverAll()).isEqualTo(1);
        ChatMessage recoveredMessage = chatMessageMapper.selectById(abandoned.assistantMessageId());
        assertThat(recoveredMessage.getStatus()).isEqualTo(ChatMessageStatus.FAILED);
        assertThat(recoveredMessage.getErrorCode())
                .isEqualTo(StaleChatGenerationRecoveryService.GENERATION_INTERRUPTED);
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
        when(modelClient.stream(any(), any(ConversationModelHistory.class), any())).thenAnswer(invocation -> {
            AgentExecutionContext context = invocation.getArgument(2);
            AgentToolCall knowledgeCall = agentToolCallService.start(
                    context,
                    "search_knowledge_base",
                    "{\"query\":\"重置密码\"}"
            );
            agentToolCallService.completeSuccess(knowledgeCall.getId(), """
                    {
                      "status": "FOUND",
                      "message": "请仅依据以下资料回答",
                      "knowledgeBase": "产品帮助中心",
                      "query": "重置密码",
                      "chunks": [
                        {
                          "rank": 1,
                          "source": "密码重置指南.md",
                          "chunkSequence": 3,
                          "content": "用户可以通过登录页的忘记密码入口重置密码。"
                        }
                      ]
                    }
                    """);
            pendingActionService.prepareCreateTicket(
                    context,
                    "重置密码后仍无法登录",
                    "客户重置密码后仍然无法登录，需要人工协助",
                    TicketPriority.HIGH,
                    "账号完全无法使用"
            );
            return Flux.just(
                    new ChatStreamChunk("可以", null, null, null),
                    new ChatStreamChunk("重置密码", 16, 5, 21)
            );
        });

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
                .contains("event:start", "event:delta", "event:action_required", "event:done")
                .containsSubsequence(
                        "event:start",
                        "event:delta",
                        "event:delta",
                        "event:action_required",
                        "event:done"
                )
                .contains("\"content\":\"可以\"", "\"content\":\"重置密码\"")
                .contains("\"title\":\"重置密码后仍无法登录\"", "\"priority\":\"HIGH\"")
                .contains("\"sourceName\":\"密码重置指南.md\"", "\"chunkSequence\":3")
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
