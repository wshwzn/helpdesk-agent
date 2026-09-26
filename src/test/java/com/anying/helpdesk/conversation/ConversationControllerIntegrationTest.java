package com.anying.helpdesk.conversation;

import com.anying.helpdesk.auth.domain.User;
import com.anying.helpdesk.auth.domain.UserRole;
import com.anying.helpdesk.auth.mapper.UserMapper;
import com.anying.helpdesk.conversation.domain.ChatMessage;
import com.anying.helpdesk.conversation.domain.ChatMessageRole;
import com.anying.helpdesk.conversation.domain.ChatMessageStatus;
import com.anying.helpdesk.conversation.mapper.ChatMessageMapper;
import com.anying.helpdesk.conversation.mapper.ConversationMapper;
import com.anying.helpdesk.conversation.model.ChatCompletionResult;
import com.anying.helpdesk.conversation.model.ConversationModelClient;
import com.anying.helpdesk.modelconfig.factory.DefaultChatModelProvider;
import com.anying.helpdesk.modelconfig.factory.ResolvedChatModel;
import com.anying.helpdesk.modelconfig.exception.ModelConfigConflictException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private DefaultChatModelProvider defaultChatModelProvider;

    @MockitoBean
    private ConversationModelClient modelClient;

    @AfterEach
    void cleanUp() {
        chatMessageMapper.delete(null);
        conversationMapper.delete(null);
        userMapper.delete(new LambdaQueryWrapper<User>()
                .likeRight(User::getUsername, "conversation_"));
    }

    @Test
    void customerChatsWithHistoryOutsideDatabaseTransaction() throws Exception {
        String token = registerCustomer("conversation_customer_one");
        long conversationId = createConversation(token, "登录问题");
        ResolvedChatModel resolvedModel = new ResolvedChatModel(99L, "test-chat-model", mock(ChatModel.class));
        when(defaultChatModelProvider.get()).thenReturn(resolvedModel);
        when(modelClient.complete(any(ResolvedChatModel.class), anyList())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            List<ChatMessage> history = invocation.getArgument(1);
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

        sendMessage(token, conversationId, "还是提示密码错误")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assistantMessage.content").value("AI: 还是提示密码错误"));

        mockMvc.perform(get("/api/conversations/{id}/messages", conversationId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.records[0].role").value("USER"))
                .andExpect(jsonPath("$.data.records[3].role").value("ASSISTANT"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(modelClient, org.mockito.Mockito.times(2)).complete(any(), historyCaptor.capture());
        List<ChatMessage> secondHistory = historyCaptor.getAllValues().get(1);
        assertThat(secondHistory)
                .extracting(ChatMessage::getRole)
                .containsExactly(ChatMessageRole.USER, ChatMessageRole.ASSISTANT, ChatMessageRole.USER);
        assertThat(secondHistory)
                .extracting(ChatMessage::getContent)
                .containsExactly("我无法登录", "AI: 我无法登录", "还是提示密码错误");

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
        ResolvedChatModel resolvedModel = new ResolvedChatModel(100L, "failure-model", mock(ChatModel.class));
        when(defaultChatModelProvider.get()).thenReturn(resolvedModel);
        doThrow(new IllegalStateException("provider leaked sk-secret-value"))
                .when(modelClient).complete(any(), anyList());

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
