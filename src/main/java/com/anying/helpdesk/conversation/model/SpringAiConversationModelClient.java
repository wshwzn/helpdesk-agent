package com.anying.helpdesk.conversation.model;

import com.anying.helpdesk.agent.AgentExecutionContext;
import com.anying.helpdesk.agent.tool.CustomerTicketTools;
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
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.stereotype.Component;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

@Component
public class SpringAiConversationModelClient implements ConversationModelClient {

    private static final String SYSTEM_PROMPT = """
            你是智能工单助手，负责回答客户服务问题。
            当客户询问自己的工单列表、状态或详情时，必须调用对应工具获取真实数据，不要猜测工单信息。
            工具只允许读取当前登录客户的数据，不要要求客户提供用户 ID。
            请根据对话上下文给出准确、简洁的回答，不要声称已经执行实际上未执行的业务操作。
            """;

    private final List<ToolCallback> customerToolCallbacks;

    /**
     * 注册面向客户的只读业务工具，供同步和流式模型调用共同使用。
     */
    public SpringAiConversationModelClient(CustomerTicketTools customerTicketTools) {
        this.customerToolCallbacks = List.of(MethodToolCallbackProvider.builder()
                .toolObjects(customerTicketTools)
                .build()
                .getToolCallbacks());
    }

    /**
     * 使用 Spring AI 同步调用模型，并转换为项目内部的完成结果。
     */
    @Override
    public ChatCompletionResult complete(
            ResolvedChatModel model,
            List<ChatMessage> history,
            AgentExecutionContext executionContext
    ) {
        ChatResponse response = model.chatModel().call(buildPrompt(history, executionContext));
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
    public Flux<ChatStreamChunk> stream(
            ResolvedChatModel model,
            List<ChatMessage> history,
            AgentExecutionContext executionContext
    ) {
        return model.chatModel()
                .stream(buildPrompt(history, executionContext))
                .map(this::toStreamChunk);
    }

    /**
     * 将数据库中的对话历史转换为模型能够识别的提示消息。
     */
    private Prompt buildPrompt(List<ChatMessage> history, AgentExecutionContext executionContext) {
        List<Message> promptMessages = new ArrayList<>();
        promptMessages.add(new SystemMessage(SYSTEM_PROMPT));
        for (ChatMessage message : history) {
            if (message.getRole() == ChatMessageRole.USER) {
                promptMessages.add(new UserMessage(message.getContent()));
            } else if (message.getRole() == ChatMessageRole.ASSISTANT) {
                promptMessages.add(new AssistantMessage(message.getContent()));
            }
        }
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(customerToolCallbacks)
                .toolContext(executionContext.toToolContext())
                .build();
        return new Prompt(promptMessages, options);
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
