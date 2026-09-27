package com.anying.helpdesk.agent;

import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;
import java.util.Objects;

/**
 * 保存一次 Agent 执行所需的可信身份信息，不向大模型暴露这些字段。
 */
public record AgentExecutionContext(Long customerId, Long conversationId) {

    private static final String CONTEXT_KEY = AgentExecutionContext.class.getName();

    /**
     * 校验 Agent 执行所需的客户和对话身份均已由后端提供。
     */
    public AgentExecutionContext {
        Objects.requireNonNull(customerId, "customerId must not be null");
        Objects.requireNonNull(conversationId, "conversationId must not be null");
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
