package com.anying.helpdesk.conversation;

import com.anying.helpdesk.agent.AgentExecutionContext;
import com.anying.helpdesk.agent.tool.CustomerTicketTools;
import com.anying.helpdesk.conversation.domain.ChatMessage;
import com.anying.helpdesk.conversation.domain.ChatMessageRole;
import com.anying.helpdesk.conversation.model.ChatCompletionResult;
import com.anying.helpdesk.conversation.model.ChatStreamChunk;
import com.anying.helpdesk.conversation.model.SpringAiConversationModelClient;
import com.anying.helpdesk.modelconfig.factory.ResolvedChatModel;
import com.anying.helpdesk.ticket.service.TicketService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
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

    private static final AgentExecutionContext EXECUTION_CONTEXT = new AgentExecutionContext(7L, 11L);

    private final SpringAiConversationModelClient client = new SpringAiConversationModelClient(
            new CustomerTicketTools(mock(TicketService.class))
    );

    @Test
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
                List.of(user, assistant, latestUser),
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
        assertThat(promptCaptor.getValue().getOptions())
                .isInstanceOfSatisfying(ToolCallingChatOptions.class, options -> {
                    assertThat(options.getToolCallbacks()).extracting(callback ->
                            callback.getToolDefinition().name()
                    ).containsExactlyInAnyOrder("list_my_tickets", "get_my_ticket");
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
                List.of(message(ChatMessageRole.USER, "测试")),
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
                        List.of(message(ChatMessageRole.USER, "我忘记密码了")),
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
    }

    private ChatMessage message(ChatMessageRole role, String content) {
        ChatMessage message = new ChatMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}
