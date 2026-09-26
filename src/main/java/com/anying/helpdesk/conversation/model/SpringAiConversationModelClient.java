package com.anying.helpdesk.conversation.model;

import com.anying.helpdesk.conversation.domain.ChatMessage;
import com.anying.helpdesk.conversation.domain.ChatMessageRole;
import com.anying.helpdesk.modelconfig.factory.ResolvedChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class SpringAiConversationModelClient implements ConversationModelClient {

    private static final String SYSTEM_PROMPT = """
            你是智能工单助手，负责回答客户服务问题。
            请根据对话上下文给出准确、简洁的回答，不要声称已经执行实际上未执行的业务操作。
            """;

    @Override
    public ChatCompletionResult complete(ResolvedChatModel model, List<ChatMessage> history) {
        List<Message> promptMessages = new ArrayList<>();
        promptMessages.add(new SystemMessage(SYSTEM_PROMPT));
        for (ChatMessage message : history) {
            if (message.getRole() == ChatMessageRole.USER) {
                promptMessages.add(new UserMessage(message.getContent()));
            } else if (message.getRole() == ChatMessageRole.ASSISTANT) {
                promptMessages.add(new AssistantMessage(message.getContent()));
            }
        }

        ChatResponse response = model.chatModel().call(new Prompt(promptMessages));
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new IllegalStateException("model returned no result");
        }
        String content = response.getResult().getOutput().getText();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("model returned empty content");
        }

        ChatResponseMetadata metadata = response.getMetadata();
        Usage usage = metadata == null ? null : metadata.getUsage();
        return new ChatCompletionResult(
                content.trim(),
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens(),
                usage == null ? null : usage.getTotalTokens()
        );
    }
}
