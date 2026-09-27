package com.anying.helpdesk.agent;

import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 保存一次 Agent 执行所需的可信身份信息，不向大模型暴露这些字段。
 */
public final class AgentExecutionContext {

    private static final String CONTEXT_KEY = AgentExecutionContext.class.getName();

    private final Long customerId;
    private final Long conversationId;
    private final Long assistantMessageId;
    private final AtomicInteger toolSequence = new AtomicInteger();

    /**
     * 使用后端已鉴权的客户、对话和 AI 占位消息创建执行上下文。
     */
    public AgentExecutionContext(Long customerId, Long conversationId, Long assistantMessageId) {
        this.customerId = Objects.requireNonNull(customerId, "customerId must not be null");
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId must not be null");
        this.assistantMessageId = Objects.requireNonNull(
                assistantMessageId,
                "assistantMessageId must not be null"
        );
    }

    /**
     * 返回当前已鉴权客户 ID。
     */
    public Long customerId() {
        return customerId;
    }

    /**
     * 返回本次模型调用所属的对话 ID。
     */
    public Long conversationId() {
        return conversationId;
    }

    /**
     * 返回用于关联工具调用的 AI 占位消息 ID。
     */
    public Long assistantMessageId() {
        return assistantMessageId;
    }

    /**
     * 为本次最终回答中的每一次工具执行分配从一开始的线程安全序号。
     */
    public int nextToolSequence() {
        return toolSequence.incrementAndGet();
    }

    /**
     * 转换为 Spring AI 工具调用上下文，供模型调用工具时由框架透传。
     */
    public Map<String, Object> toToolContext() {
        return Map.of(CONTEXT_KEY, this);
    }

    /**
     * 从工具上下文中读取后端注入的可信身份，缺失或类型错误时拒绝执行工具。
     */
    public static AgentExecutionContext require(ToolContext toolContext) {
        if (toolContext == null) {
            throw new IllegalStateException("agent execution context is missing");
        }
        Object context = toolContext.getContext().get(CONTEXT_KEY);
        if (context instanceof AgentExecutionContext executionContext) {
            return executionContext;
        }
        throw new IllegalStateException("agent execution context is invalid");
    }
}
