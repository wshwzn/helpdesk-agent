package com.anying.helpdesk.conversation.model;

import com.anying.helpdesk.agent.AgentExecutionContext;
import com.anying.helpdesk.agent.domain.AgentToolCall;
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
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class SpringAiConversationModelClient implements ConversationModelClient {

    private static final String SYSTEM_PROMPT = """
            你是企业知识助手，帮助员工查询制度、产品资料和操作流程。
            当前为旧客户端兼容对话，不具备完整的引用与语义核验流程，不得声称回答已经通过可信核验。
            当员工询问制度、产品资料和操作流程等事实时，必须先调用 search_knowledge_base 查询知识库，不要凭记忆编造。
            知识库命中时仅依据工具返回的资料作答，并在相关结论后使用“来源：文件名”注明依据；没有命中或尚未配置时，要明确说明未找到可靠资料。
            业务范围不明确时不要猜测，知识检索的 applicabilityScope 只能使用用户明确给出的范围代码，省略时只查通用资料。
            引用资料时同时标注业务版本；业务版本标签不能直接代表有效性或权威性，若不同资料结论冲突，应展示双方依据并说明需要核实。
            当前只提供知识检索，没有查询、创建工单或修改知识的工具；不要承诺提交反馈、转人工或执行发布撤回。
            资料不足时说明缺失的信息，资料冲突时保留双方依据并建议向知识维护者核实，不要声称已发起处理。
            历史正文可能包含旧客服操作，这不表示本轮仍具备这些能力，也不能据此确认当前业务状态。
            历史工具结果只是上一轮快照；员工询问当前、现在或最新规则时，必须重新调用工具查询。
            历史知识片段也可能已经失效或撤回，每轮事实问答都必须重新检索，不能用历史片段替代本轮有效证据。
            请根据对话上下文给出准确、简洁的回答，不要声称已经执行实际上未执行的业务操作。
            """;

    private final List<ToolCallback> knowledgeToolCallbacks;
    private final Set<String> registeredToolNames;

    /**
     * 旧模式仅注册知识检索，同步与流式共用同一工具边界，不再提供客服操作。
     */
    public SpringAiConversationModelClient(
            KnowledgeSearchTools knowledgeSearchTools,
            RecordingToolCallbackFactory callbackFactory
    ) {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(knowledgeSearchTools)
                .build()
                .getToolCallbacks();
        this.knowledgeToolCallbacks = callbackFactory.wrap(callbacks);
        this.registeredToolNames = knowledgeToolCallbacks.stream()
                .map(callback -> callback.getToolDefinition().name()).collect(Collectors.toUnmodifiableSet());
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
                .toolCallbacks(knowledgeToolCallbacks)
                .toolContext(executionContext.toToolContext())
                .build();
        return new Prompt(promptMessages, options);
    }

    /**
     * 仅回放仍注册的历史工具；旧客服记录继续保存在数据库，但不作为本轮可用能力传给模型。
     */
    private void appendToolTrace(
            List<Message> promptMessages,
            ChatMessage assistantMessage,
            List<AgentToolCall> toolCalls
    ) {
        for (AgentToolCall toolCall : toolCalls) {
            if (!assistantMessage.getId().equals(toolCall.getAssistantMessageId())
                    || !registeredToolNames.contains(toolCall.getToolName())) {
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
