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
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

@Component
public class SpringAiConversationModelClient implements ConversationModelClient {

    private static final String SYSTEM_PROMPT = """
            你是智能工单助手，负责回答客户服务问题。
            请根据对话上下文给出准确、简洁的回答，不要声称已经执行实际上未执行的业务操作。
            """;

    /**
     * 使用 Spring AI 同步调用模型，并转换为项目内部的完成结果。
     */
    @Override
    public ChatCompletionResult complete(ResolvedChatModel model, List<ChatMessage> history) {
        ChatResponse response = model.chatModel().call(buildPrompt(history));
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new IllegalStateException("model returned no result");
        }
        String content = response.getResult().getOutput().getText();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("model returned empty content");
        }

        Usage usage = readUsage(response);
        return new ChatCompletionResult(
                content.trim(),
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens(),
                usage == null ? null : usage.getTotalTokens()
        );
    }

    /**
     * 使用 Spring AI 流式调用模型，并逐个转换厂商返回的数据块。
     */
    @Override
    public Flux<ChatStreamChunk> stream(ResolvedChatModel model, List<ChatMessage> history) {
        return model.chatModel()
                .stream(buildPrompt(history))
                .map(this::toStreamChunk);
    }

    /**
     * 将数据库中的对话历史转换为模型能够识别的提示消息。
     */
    private Prompt buildPrompt(List<ChatMessage> history) {
        List<Message> promptMessages = new ArrayList<>();
        promptMessages.add(new SystemMessage(SYSTEM_PROMPT));
        for (ChatMessage message : history) {
            if (message.getRole() == ChatMessageRole.USER) {
                promptMessages.add(new UserMessage(message.getContent()));
            } else if (message.getRole() == ChatMessageRole.ASSISTANT) {
                promptMessages.add(new AssistantMessage(message.getContent()));
            }
        }
        return new Prompt(promptMessages);
    }

    /**
     * 将 Spring AI 的单个流式响应转换为文本和 Token 用量数据块。
     */
    private ChatStreamChunk toStreamChunk(ChatResponse response) {
        String content = response.getResult() == null || response.getResult().getOutput() == null
                ? null
                : response.getResult().getOutput().getText();
        Usage usage = readUsage(response);
        return new ChatStreamChunk(
                content,
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens(),
                usage == null ? null : usage.getTotalTokens()
        );
    }

    /**
     * 安全读取模型响应中的 Token 用量；厂商未提供时返回空值。
     */
    private Usage readUsage(ChatResponse response) {
        ChatResponseMetadata metadata = response.getMetadata();
        return metadata == null ? null : metadata.getUsage();
    }
}
