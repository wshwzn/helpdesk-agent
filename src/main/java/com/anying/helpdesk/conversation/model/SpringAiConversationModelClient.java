package com.anying.helpdesk.conversation.model;

import com.anying.helpdesk.agent.AgentExecutionContext;
import com.anying.helpdesk.agent.domain.AgentToolCall;
import com.anying.helpdesk.agent.tool.CustomerTicketActionTools;
import com.anying.helpdesk.agent.tool.CustomerTicketTools;
import com.anying.helpdesk.agent.tool.KnowledgeSearchTools;
import com.anying.helpdesk.agent.tool.RecordingToolCallbackFactory;
import com.anying.helpdesk.conversation.domain.ChatMessage;
import com.anying.helpdesk.conversation.domain.ChatMessageRole;
import com.anying.helpdesk.modelconfig.factory.ResolvedChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
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
            当客户询问产品说明、使用方法、故障排查或服务规则等通用事实时，必须先调用 search_knowledge_base 查询知识库，不要凭记忆编造。
            知识库命中时仅依据工具返回的资料作答，并在相关结论后使用“来源：文件名”注明依据；没有命中或尚未配置时，要明确说明未找到可靠资料。
            当客户询问自己的工单列表、状态或详情时，必须调用对应工具获取真实数据，不要猜测工单信息。
            工具只允许读取当前登录客户的数据，不要要求客户提供用户 ID。
            当客户明确要求人工处理、要求创建工单，或者问题无法继续自动解决时，调用 prepare_create_ticket 生成工单草稿。
            工单草稿需要客户在页面确认，绝不能把草稿描述为已经创建成功的工单。
            优先级规则：咨询建议使用 LOW；一般功能异常或信息不足使用 MEDIUM；无法登录、无法支付、安全风险或主要功能完全不可用使用 HIGH。
            历史工具结果只是上一轮快照；客户询问当前、现在或最新状态时，必须重新调用工具查询。
            请根据对话上下文给出准确、简洁的回答，不要声称已经执行实际上未执行的业务操作。
            """;

    private final List<ToolCallback> customerToolCallbacks;

    /**
     * 注册面向客户的查询与待确认操作工具，供同步和流式模型调用共同使用。
     */
    public SpringAiConversationModelClient(
            CustomerTicketTools customerTicketTools,
            CustomerTicketActionTools customerTicketActionTools,
            KnowledgeSearchTools knowledgeSearchTools,
            RecordingToolCallbackFactory callbackFactory
    ) {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(customerTicketTools, customerTicketActionTools, knowledgeSearchTools)
                .build()
                .getToolCallbacks();
        this.customerToolCallbacks = callbackFactory.wrap(callbacks);
    }

    /**
     * 使用 Spring AI 同步调用模型，并转换为项目内部的完成结果。
     */
    @Override
    public ChatCompletionResult complete(
            ResolvedChatModel model,
            ConversationModelHistory history,
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
            ConversationModelHistory history,
            AgentExecutionContext executionContext
    ) {
        return model.chatModel()
                .stream(buildPrompt(history, executionContext))
                .map(this::toStreamChunk);
    }

    /**
     * 将数据库中的对话历史转换为模型能够识别的提示消息。
     */
    private Prompt buildPrompt(
            ConversationModelHistory history,
            AgentExecutionContext executionContext
    ) {
        List<Message> promptMessages = new ArrayList<>();
        promptMessages.add(new SystemMessage(SYSTEM_PROMPT));
        for (ChatMessage message : history.messages()) {
            if (message.getRole() == ChatMessageRole.USER) {
                promptMessages.add(new UserMessage(message.getContent()));
            } else if (message.getRole() == ChatMessageRole.ASSISTANT) {
                appendToolTrace(promptMessages, message, history.recentToolCalls());
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
     * 在上一条最终 AI 回复之前还原其成功工具调用，支持客户继续引用上一轮结果。
     */
    private void appendToolTrace(
            List<Message> promptMessages,
            ChatMessage assistantMessage,
            List<AgentToolCall> toolCalls
    ) {
        for (AgentToolCall toolCall : toolCalls) {
            if (!assistantMessage.getId().equals(toolCall.getAssistantMessageId())) {
                continue;
            }
            promptMessages.add(AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                            toolCall.getCallId(),
                            "function",
                            toolCall.getToolName(),
                            toolCall.getArgumentsJson()
                    )))
                    .build());
            promptMessages.add(ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            toolCall.getCallId(),
                            toolCall.getToolName(),
                            toolCall.getResultJson()
                    )))
                    .build());
        }
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
