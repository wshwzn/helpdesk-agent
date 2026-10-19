package com.anying.helpdesk.conversation;

import com.anying.helpdesk.agent.AgentExecutionContext;
import com.anying.helpdesk.agent.domain.AgentToolCall;
import com.anying.helpdesk.agent.domain.AgentToolCallStatus;
import com.anying.helpdesk.agent.service.AgentToolCallService;
import com.anying.helpdesk.agent.tool.KnowledgeSearchTools;
import com.anying.helpdesk.agent.tool.RecordingToolCallbackFactory;
import com.anying.helpdesk.conversation.domain.ChatMessage;
import com.anying.helpdesk.conversation.domain.ChatMessageRole;
import com.anying.helpdesk.conversation.model.ChatCompletionResult;
import com.anying.helpdesk.conversation.model.ChatStreamChunk;
import com.anying.helpdesk.conversation.model.ConversationModelHistory;
import com.anying.helpdesk.conversation.model.SpringAiConversationModelClient;
import com.anying.helpdesk.modelconfig.factory.ResolvedChatModel;
import com.anying.helpdesk.knowledge.service.KnowledgeBaseService;
import com.anying.helpdesk.knowledge.service.KnowledgeRetrievalService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringAiConversationModelClientTest {

    private static final AgentExecutionContext EXECUTION_CONTEXT = new AgentExecutionContext(7L, 11L, 13L);

    private final AgentToolCallService toolCallService = mock(AgentToolCallService.class);
    private final SpringAiConversationModelClient client = new SpringAiConversationModelClient(
            new KnowledgeSearchTools(
                    mock(KnowledgeBaseService.class),
                    mock(KnowledgeRetrievalService.class)
            ),
            new RecordingToolCallbackFactory(toolCallService)
    );

    /** 旧客户端仍能读取上下文和用量，但只获得知识检索能力。 */
    @Test
    @DisplayName("显式旧模式只注册知识工具并保留消息和用量")
    void convertsHistoryAndReadsTokenUsage() {
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse response = new ChatResponse(
                List.of(new Generation(new AssistantMessage("  可以重置密码  "))),
                ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(20, 8, 28))
                        .build()
        );
        when(chatModel.call(any(Prompt.class))).thenReturn(response);
        ResolvedChatModel model = new ResolvedChatModel(9L, "test-model", true, chatModel);

        ChatMessage user = message(ChatMessageRole.USER, "我忘记密码了");
        ChatMessage assistant = message(ChatMessageRole.ASSISTANT, "请确认登录名");
        ChatMessage latestUser = message(ChatMessageRole.USER, "登录名是 customer01");

        ChatCompletionResult result = client.complete(
                model,
                new ConversationModelHistory(List.of(user, assistant, latestUser), List.of()),
                EXECUTION_CONTEXT
        );

        assertThat(result.content()).isEqualTo("可以重置密码");
        assertThat(result.promptTokens()).isEqualTo(20);
        assertThat(result.completionTokens()).isEqualTo(8);
        assertThat(result.totalTokens()).isEqualTo(28);

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        assertThat(promptCaptor.getValue().getInstructions())
                .hasSize(4)
                .element(0).isInstanceOf(SystemMessage.class);
        assertThat(promptCaptor.getValue().getInstructions().get(1))
                .isInstanceOfSatisfying(UserMessage.class, message ->
                        assertThat(message.getText()).isEqualTo("我忘记密码了"));
        assertThat(promptCaptor.getValue().getInstructions().get(2))
                .isInstanceOfSatisfying(AssistantMessage.class, message ->
                        assertThat(message.getText()).isEqualTo("请确认登录名"));
        assertThat(promptCaptor.getValue().getSystemMessage().getText())
                .contains("企业知识助手", "旧客户端兼容", "不得声称回答已经通过可信核验")
                .doesNotContain("prepare_create_ticket", "list_my_tickets", "get_my_ticket");
        assertThat(promptCaptor.getValue().getOptions())
                .isInstanceOfSatisfying(ToolCallingChatOptions.class, options -> {
                    assertThat(options.getToolCallbacks()).extracting(callback ->
                            callback.getToolDefinition().name()
                    ).containsExactly("search_knowledge_base");
                    assertThat(options.getToolContext()).containsValue(EXECUTION_CONTEXT);
                });
    }

    @Test
    void rejectsBlankModelResponse() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("   ")))
        ));

        assertThatThrownBy(() -> client.complete(
                new ResolvedChatModel(9L, "test-model", true, chatModel),
                new ConversationModelHistory(
                        List.of(message(ChatMessageRole.USER, "测试")),
                        List.of()
                ),
                EXECUTION_CONTEXT
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("model returned empty content");
    }

    @Test
    @DisplayName("流式调用应按顺序转换文本片段并保留最终 Token 用量")
    void streamsTextChunksAndTokenUsage() {
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse firstResponse = new ChatResponse(
                List.of(new Generation(new AssistantMessage("可以")))
        );
        ChatResponse finalResponse = new ChatResponse(
                List.of(new Generation(new AssistantMessage("重置密码"))),
                ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(15, 5, 20))
                        .build()
        );
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(firstResponse, finalResponse));
        ResolvedChatModel model = new ResolvedChatModel(9L, "test-model", true, chatModel);

        List<ChatStreamChunk> chunks = client.stream(
                        model,
                        new ConversationModelHistory(
                                List.of(message(ChatMessageRole.USER, "我忘记密码了")),
                                List.of()
                        ),
                        EXECUTION_CONTEXT
                )
                .collectList()
                .block();

        assertThat(chunks).isNotNull().hasSize(2);
        assertThat(chunks).extracting(ChatStreamChunk::content)
                .containsExactly("可以", "重置密码");
        assertThat(chunks.getLast().totalTokens()).isEqualTo(20);

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).stream(promptCaptor.capture());
        assertThat(promptCaptor.getValue().getInstructions())
                .hasSize(2)
                .element(0).isInstanceOf(SystemMessage.class);
        assertThat(promptCaptor.getValue().getSystemMessage().getText())
                .contains("企业知识助手", "旧客户端兼容", "不得声称回答已经通过可信核验")
                .doesNotContain("prepare_create_ticket", "list_my_tickets", "get_my_ticket");
        assertThat(promptCaptor.getValue().getOptions())
                .isInstanceOfSatisfying(ToolCallingChatOptions.class, options ->
                        assertThat(options.getToolCallbacks()).extracting(callback ->
                                callback.getToolDefinition().name()
                        ).containsExactly("search_knowledge_base")
                );
    }

    /** 历史客服记录仍可读，但只将本轮仍注册的知识工具成对回放给模型。 */
    @Test
    @DisplayName("过滤已退出和未知工具的回放，保留知识调用顺序及旧消息正文")
    void replaysOnlyRegisteredKnowledgeTools() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("请查询当前制度")))));
        ChatMessage previousUser = message(40L, ChatMessageRole.USER, "旧问题");
        ChatMessage previousAssistant = message(41L, ChatMessageRole.ASSISTANT, "历史工单草稿");
        ChatMessage currentUser = message(42L, ChatMessageRole.USER, "现在的制度呢？");
        List<AgentToolCall> toolCalls = List.of(
                toolCall(41L, 1, "old-list", "list_my_tickets", "{}", "retired-list"),
                toolCall(41L, 2, "knowledge-1", "search_knowledge_base", "{}", "first-source"),
                toolCall(41L, 3, "old-create", "prepare_create_ticket", "{}", "retired-draft"),
                toolCall(41L, 4, "old-detail", "get_my_ticket", "{}", "retired-detail"),
                toolCall(41L, 5, "unknown", "unknown_tool", "{}", "unknown-result"),
                toolCall(41L, 6, "knowledge-2", "search_knowledge_base", "{}", "second-source"),
                toolCall(99L, 1, "other-message", "search_knowledge_base", "{}", "unrelated-result"));
        client.complete(new ResolvedChatModel(9L, "test-model", true, chatModel),
                new ConversationModelHistory(List.of(previousUser, previousAssistant, currentUser), toolCalls),
                EXECUTION_CONTEXT);

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        var instructions = captor.getValue().getInstructions();
        assertThat(instructions).hasSize(8);
        assertThat(instructions.get(2)).isInstanceOfSatisfying(AssistantMessage.class, message ->
                assertThat(message.getToolCalls()).singleElement().satisfies(call -> {
                    assertThat(call.id()).isEqualTo("knowledge-1");
                    assertThat(call.name()).isEqualTo("search_knowledge_base");
                }));
        assertThat(instructions.get(3)).isInstanceOfSatisfying(ToolResponseMessage.class, message ->
                assertThat(message.getResponses()).singleElement().satisfies(response ->
                        assertThat(response.responseData()).isEqualTo("first-source")));
        assertThat(instructions.get(4)).isInstanceOfSatisfying(AssistantMessage.class, message ->
                assertThat(message.getToolCalls()).singleElement().satisfies(call ->
                        assertThat(call.id()).isEqualTo("knowledge-2")));
        assertThat(instructions.get(5)).isInstanceOfSatisfying(ToolResponseMessage.class, message ->
                assertThat(message.getResponses()).singleElement().satisfies(response ->
                        assertThat(response.responseData()).isEqualTo("second-source")));
        assertThat(instructions.get(6).getText()).isEqualTo("历史工单草稿");
        assertThat(instructions.get(7).getText()).isEqualTo("现在的制度呢？");
    }

    /** 构造未持久化消息以校验普通正文的上下文拼接。 */
    private ChatMessage message(ChatMessageRole role, String content) {
        return message(null, role, content);
    }

    /**
     * 构造带数据库主键的历史消息，便于验证工具调用与 AI 回复的关联。
     */
    private ChatMessage message(Long id, ChatMessageRole role, String content) {
        ChatMessage message = new ChatMessage();
        message.setId(id);
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    /**
     * 构造一条已成功完成的工具调用记录。
     */
    private AgentToolCall toolCall(
            Long assistantMessageId,
            int sequenceNo,
            String callId,
            String toolName,
            String argumentsJson,
            String resultJson
    ) {
        AgentToolCall toolCall = new AgentToolCall();
        toolCall.setAssistantMessageId(assistantMessageId);
        toolCall.setSequenceNo(sequenceNo);
        toolCall.setCallId(callId);
        toolCall.setToolName(toolName);
        toolCall.setArgumentsJson(argumentsJson);
        toolCall.setResultJson(resultJson);
        toolCall.setStatus(AgentToolCallStatus.SUCCESS);
        return toolCall;
    }
}
