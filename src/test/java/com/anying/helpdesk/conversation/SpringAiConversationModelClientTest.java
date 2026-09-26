package com.anying.helpdesk.conversation;

import com.anying.helpdesk.conversation.domain.ChatMessage;
import com.anying.helpdesk.conversation.domain.ChatMessageRole;
import com.anying.helpdesk.conversation.model.ChatCompletionResult;
import com.anying.helpdesk.conversation.model.SpringAiConversationModelClient;
import com.anying.helpdesk.modelconfig.factory.ResolvedChatModel;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringAiConversationModelClientTest {

    private final SpringAiConversationModelClient client = new SpringAiConversationModelClient();

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
        ResolvedChatModel model = new ResolvedChatModel(9L, "test-model", chatModel);

        ChatMessage user = message(ChatMessageRole.USER, "我忘记密码了");
        ChatMessage assistant = message(ChatMessageRole.ASSISTANT, "请确认登录名");
        ChatMessage latestUser = message(ChatMessageRole.USER, "登录名是 customer01");

        ChatCompletionResult result = client.complete(model, List.of(user, assistant, latestUser));

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
    }

    @Test
    void rejectsBlankModelResponse() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("   ")))
        ));

        assertThatThrownBy(() -> client.complete(
                new ResolvedChatModel(9L, "test-model", chatModel),
                List.of(message(ChatMessageRole.USER, "测试"))
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("model returned empty content");
    }

    private ChatMessage message(ChatMessageRole role, String content) {
        ChatMessage message = new ChatMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}
