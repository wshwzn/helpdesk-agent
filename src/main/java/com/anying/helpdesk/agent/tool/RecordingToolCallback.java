package com.anying.helpdesk.agent.tool;

import com.anying.helpdesk.agent.AgentExecutionContext;
import com.anying.helpdesk.agent.domain.AgentToolCall;
import com.anying.helpdesk.agent.service.AgentToolCallService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * 包装真实工具回调，在不改变模型可见结果的前提下持久化参数、结果和状态。
 */
public final class RecordingToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final AgentToolCallService toolCallService;

    /**
     * 创建一个保留原工具定义和行为的记录型回调。
     */
    public RecordingToolCallback(ToolCallback delegate, AgentToolCallService toolCallService) {
        this.delegate = delegate;
        this.toolCallService = toolCallService;
    }

    /**
     * 返回原工具定义，保证模型看到的名称、说明和参数结构不变。
     */
    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    /**
     * 返回原工具元数据，保留直接返回等框架行为。
     */
    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    /**
     * 禁止缺少可信上下文的工具调用，避免出现无法归属的审计记录。
     */
    @Override
    public String call(String toolInput) {
        throw new IllegalStateException("agent tool execution context is required");
    }

    /**
     * 在真实工具执行前后更新同一条记录，失败时保存脱敏状态后继续抛出原异常。
     */
    @Override
    public String call(String toolInput, ToolContext toolContext) {
        AgentExecutionContext executionContext = AgentExecutionContext.require(toolContext);
        AgentToolCall toolCall = toolCallService.start(
                executionContext,
                getToolDefinition().name(),
                toolInput
        );
        try {
            String result = delegate.call(toolInput, toolContext);
            toolCallService.completeSuccess(toolCall.getId(), result);
            return result;
        } catch (RuntimeException exception) {
            recordFailure(toolCall.getId(), exception);
            throw exception;
        }
    }

    /**
     * 尽力保存失败状态；记录失败不会覆盖最初的工具异常。
     */
    private void recordFailure(Long toolCallId, RuntimeException toolException) {
        try {
            toolCallService.completeFailure(toolCallId);
        } catch (RuntimeException persistenceException) {
            toolException.addSuppressed(persistenceException);
        }
    }
}
