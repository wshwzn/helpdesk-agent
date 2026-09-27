package com.anying.helpdesk.conversation.model;

import com.anying.helpdesk.agent.AgentExecutionContext;
import com.anying.helpdesk.conversation.domain.ChatMessage;
import com.anying.helpdesk.modelconfig.factory.ResolvedChatModel;
import reactor.core.publisher.Flux;

import java.util.List;

public interface ConversationModelClient {

    /**
     * 调用模型并在生成完成后一次性返回完整回答。
     */
    ChatCompletionResult complete(
            ResolvedChatModel model,
            List<ChatMessage> history,
            AgentExecutionContext executionContext
    );

    /**
     * 调用模型并持续返回文本增量及厂商提供的 Token 用量。
     */
    Flux<ChatStreamChunk> stream(
            ResolvedChatModel model,
            List<ChatMessage> history,
            AgentExecutionContext executionContext
    );
}
