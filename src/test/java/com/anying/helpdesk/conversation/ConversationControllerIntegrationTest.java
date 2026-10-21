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
import com.anying.helpdesk.conversation.domain.ChatMode;
import com.anying.helpdesk.conversation.dto.SendMessageRequest;
import com.anying.helpdesk.conversation.domain.ChatMessageRole;
import com.anying.helpdesk.conversation.domain.ChatMessageStatus;
import com.anying.helpdesk.conversation.mapper.ChatMessageMapper;
import com.anying.helpdesk.conversation.mapper.ConversationMapper;
import com.anying.helpdesk.conversation.model.ChatCompletionResult;
import com.anying.helpdesk.conversation.model.ConversationModelClient;
import com.anying.helpdesk.knowledge.answer.TrustedKnowledgeAnswerService;
import com.anying.helpdesk.knowledge.answer.KnowledgeAnswerReport;
import com.anying.helpdesk.knowledge.answer.KnowledgeAnswerDraft;
import com.anying.helpdesk.knowledge.dto.KnowledgeDocumentMetadata;
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

/** 旧AGENT请求归一后的会话回归，历史工具引用和中断清理仍保留验证。 */
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

    @MockitoBean
    private TrustedKnowledgeAnswerService knowledge;

    @AfterEach
    void cleanUp() {
        pendingActionMapper.delete(null);
        agentToolCallMapper.delete(null);
        chatMessageMapper.delete(null);
        conversationMapper.delete(null);
        userMapper.delete(new LambdaQueryWrapper<User>()
                .likeRight(User::getUsername, "conversation_"));
    }

    /** 新问答只携带本轮问题，调用发生在事务外，历史仍可阅读。 */
    @Test @DisplayName("旧别名进入可信服务且历史不作为新事实回放")
    void customerChatsWithHistoryOutsideDatabaseTransaction() throws Exception {
        String token = registerCustomer("conversation_customer_one");
        long conversationId = createConversation(token, "制度问题");
        when(defaultChatModelProvider.get()).thenReturn(new ResolvedChatModel(99L, "test-chat-model", true, mock(ChatModel.class)));
        when(knowledge.answer(any(), any(), any(), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return trustedCompletion("AI: " + invocation.getArgument(1), 12, 6, 18);
        });
        sendMessage(token, conversationId, "报销期限")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userMessage.mode").value("KNOWLEDGE"))
                .andExpect(jsonPath("$.data.assistantMessage.knowledgeAnswer").exists())
                .andExpect(jsonPath("$.data.assistantMessage.content").value("AI: 报销期限"))
                .andExpect(jsonPath("$.data.assistantMessage.modelConfigId").value(99))
                .andExpect(jsonPath("$.data.assistantMessage.totalTokens").value(18))
                .andExpect(jsonPath("$.data.pendingActions").isEmpty());
        sendMessage(token, conversationId, "住宿标准").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assistantMessage.content").value("AI: 住宿标准"));
        ArgumentCaptor<String> questions = ArgumentCaptor.forClass(String.class);
        verify(knowledge, org.mockito.Mockito.times(2)).answer(any(), questions.capture(), any(), any());
        assertThat(questions.getAllValues()).containsExactly("报销期限", "住宿标准");
        verifyNoInteractions(modelClient);
        mockMvc.perform(get("/api/conversations/{id}/messages", conversationId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.records[1].content").value("AI: 报销期限"));
        mockMvc.perform(get("/api/conversations").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
    }

    /** 历史引用读取沿用工具审计，不将其转成新核验报告。 */
    @Test
    @DisplayName("历史AGENT仍可读取工具引用但不补造核验报告")
    void knowledgeCitationsAreReturnedWithCurrentAndHistoricalMessages() throws Exception {
        String username = "conversation_knowledge_citations";
        String token = registerCustomer(username);
        long conversationId = createConversation(token, "旧引用读取");
        User owner = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        ChatMessage old = new ChatMessage();
        old.setConversationId(conversationId); old.setRole(ChatMessageRole.ASSISTANT);
        old.setMode(ChatMode.AGENT); old.setStatus(ChatMessageStatus.COMPLETED); old.setContent("历史检索回答");
        old.setCreatedAt(LocalDateTime.now()); old.setUpdatedAt(LocalDateTime.now()); chatMessageMapper.insert(old);
        var call = agentToolCallService.start(new AgentExecutionContext(owner.getId(), conversationId, old.getId()),
                "search_knowledge_base", "{}");
        agentToolCallService.completeSuccess(call.getId(), """
                {"status":"FOUND","knowledgeBase":"产品帮助中心","chunks":[{"source":"账号安全说明.pdf",
                "chunkSequence":8,"content":"连续五次输入错误密码后，账号将锁定三十分钟。"}]}
                """);
        mockMvc.perform(get("/api/conversations/{id}/messages", conversationId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].mode").value("AGENT"))
                .andExpect(jsonPath("$.data.records[0].knowledgeAnswer").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].knowledgeRunId").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].citations[0].sourceName").value("账号安全说明.pdf"))
                .andExpect(jsonPath("$.data.records[0].citations[0].chunkSequence").value(8));
        verifyNoInteractions(modelClient, knowledge);
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

        verifyNoInteractions(defaultChatModelProvider, modelClient, knowledge);
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
        when(knowledge.answer(any(), any(), any(), any()))
                .thenReturn(trustedCompletion("这条历史应当保留", 6, 5, 11));
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
     * 旧客户端请求也只交付可信结果，不再生成客服操作卡片。
     */
    @Test
    @DisplayName("旧AGENT请求不生成工单或待确认操作")
    void synchronousChatDoesNotCreatePendingAction() throws Exception {
        String token = registerCustomer("conversation_action_sync");
        long conversationId = createConversation(token, "旧客服请求");
        when(defaultChatModelProvider.get()).thenReturn(new ResolvedChatModel(104L, "test", false, mock(ChatModel.class)));
        when(knowledge.answer(any(), any(), any(), any())).thenReturn(trustedCompletion("维护流程依据", 10, 8, 18));
        sendMessage(token, conversationId, "帮我转人工处理").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pendingActions").isEmpty())
                .andExpect(jsonPath("$.data.assistantMessage.knowledgeAnswer").exists());
        assertThat(pendingActionMapper.selectCount(null)).isZero();
        assertThat(agentToolCallMapper.selectCount(null)).isZero();
        verifyNoInteractions(modelClient);
    }

    /** 旧别名核验失败后保存安全错误码，不回退旧生成器。 */
    @Test @DisplayName("旧AGENT失败保存诊断且不泄露正文")
    void modelFailurePersistsSanitizedFailedAssistantMessage() throws Exception {
        String token = registerCustomer("conversation_customer_failure");
        long conversationId = createConversation(token, "失败测试");
        when(defaultChatModelProvider.get()).thenReturn(new ResolvedChatModel(100L, "failure-model", true, mock(ChatModel.class)));
        when(knowledge.answer(any(), any(), any(), any())).thenThrow(new IllegalStateException("provider private detail"));
        sendMessage(token, conversationId, "触发失败").andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("CHAT_GENERATION_FAILED"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("provider private detail"))));
        var messages = chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId).orderByAsc(ChatMessage::getId));
        assertThat(messages).hasSize(2);
        assertThat(messages.getLast().getStatus()).isEqualTo(ChatMessageStatus.FAILED);
        assertThat(messages.getLast().getErrorCode()).isEqualTo("KNOWLEDGE_ANSWER_FAILED");
        assertThat(messages.getLast().getContent()).isNull();
        assertThat(messages.getLast().getKnowledgeAnswer()).isNull();
        assertThat(messages.getLast().getKnowledgeRunId()).isNotBlank();
        verifyNoInteractions(modelClient);
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
        verifyNoInteractions(modelClient, knowledge);
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
                new SendMessageRequest("第一条消息", ChatMode.AGENT, null),
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
                                {"mode": "AGENT", "content": "第三条消息"}
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
        when(knowledge.answer(any(), any(), any(), any()))
                .thenReturn(trustedCompletion("现在可以继续对话", 8, 5, 13));

        PreparedChatExchange abandoned = conversationPersistenceService.prepareExchange(
                conversationId,
                customer.getId(),
                new SendMessageRequest("生成时服务被关闭", ChatMode.AGENT, null),
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
                new SendMessageRequest("等待后台恢复", ChatMode.AGENT, null),
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

    /** SSE只发送完整已核验正文并保存汇总用量，不再夹带操作卡片。 */
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
        when(knowledge.answer(any(), any(), any(), any()))
                .thenReturn(trustedCompletion("可以重置密码", 16, 5, 21));

        MvcResult streamingRequest = mockMvc.perform(post(
                                "/api/conversations/{id}/messages/stream",
                                conversationId
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("""
                                {"mode": "AGENT", "content": "我忘记密码了"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        String eventStream = mockMvc.perform(asyncDispatch(streamingRequest))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(eventStream).containsSubsequence("event:start", "event:delta", "event:done")
                .contains("可以重置密码", "knowledgeAnswer", "KNOWLEDGE")
                .doesNotContain("event:action_required", "event:error");
        assertThat(eventStream.split("event:delta", -1)).hasSize(2);
        verifyNoInteractions(modelClient);

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

    /** 流式核验失败只发安全错误，不泄露草案或生成完成事件。 */
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
        when(knowledge.answer(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("provider leaked sk-stream-secret"));

        MvcResult streamingRequest = mockMvc.perform(post(
                                "/api/conversations/{id}/messages/stream",
                                conversationId
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("""
                                {"mode": "AGENT", "content": "触发流式失败"}
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
        assertThat(messages.getLast().getErrorCode()).isEqualTo("KNOWLEDGE_ANSWER_FAILED");
    }

    /** SSE是交付协议，不依赖厂商逐Token输出能力。 */
    @Test
    @DisplayName("旧AGENT的SSE不再要求厂商支持流式")
    void modelWithoutStreamingSupportStillDeliversVerifiedSse() throws Exception {
        String token = registerCustomer("conversation_stream_unsupported");
        long conversationId = createConversation(token, "厂商无流式能力");
        when(defaultChatModelProvider.get()).thenReturn(new ResolvedChatModel(103L, "non-stream-model", false, mock(ChatModel.class)));
        when(knowledge.answer(any(), any(), any(), any())).thenReturn(trustedCompletion("已核验正文", 1, 2, 3));
        var pending = mockMvc.perform(post("/api/conversations/{id}/messages/stream", conversationId)
                .header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM).content("""
                        {"mode":"AGENT","content":"报销期限"}
                        """)).andExpect(request().asyncStarted()).andReturn();
        var body = mockMvc.perform(asyncDispatch(pending)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).containsSubsequence("event:start", "event:delta", "event:done");
        verifyNoInteractions(modelClient);
    }

    /** 为会话边界测试构造带报告和用量的可信结果，避免旧自由文本桩绕过持久化约束。 */
    private ChatCompletionResult trustedCompletion(String text, int prompt, int output, int total) {
        var draft = new KnowledgeAnswerDraft(KnowledgeAnswerDraft.Status.ANSWERABLE,
                List.of(new KnowledgeAnswerDraft.Claim(text, List.of(new KnowledgeAnswerDraft.Reference("E1", text)))),
                List.of(), List.of());
        var report = new KnowledgeAnswerReport(3, KnowledgeAnswerReport.Reason.REVIEWED, 2L, "测试制度库", "ALL",
                LocalDateTime.now(), draft, List.of(new KnowledgeAnswerReport.Evidence("E1", 10L, 1, "测试资料.md", text,
                new KnowledgeDocumentMetadata("test", "v1", "ALL", LocalDateTime.of(2026, 1, 1, 0, 0), null))));
        return new ChatCompletionResult(text, prompt, output, total, report);
    }

    /** 显式发送旧AGENT别名，验证规范化后仍保留会话接口兼容。 */
    private org.springframework.test.web.servlet.ResultActions sendMessage(
            String token,
            long conversationId,
            String message
    ) throws Exception {
        return mockMvc.perform(post("/api/conversations/{id}/messages", conversationId)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"mode": "AGENT", "content": "%s"}
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
