package com.anying.helpdesk.agent.tool;

import com.anying.helpdesk.agent.AgentExecutionContext;
import com.anying.helpdesk.agent.domain.AgentToolCall;
import com.anying.helpdesk.agent.service.AgentToolCallService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecordingToolCallbackTest {

    private final ToolCallback delegate = mock(ToolCallback.class);
    private final AgentToolCallService toolCallService = mock(AgentToolCallService.class);

    /**
     * 验证成功工具调用会保存模型实际收到的返回字符串。
     */
    @Test
    @DisplayName("成功工具调用应保存参数和模型可见结果")
    void successfulCallPersistsExactResult() {
        ToolDefinition definition = definition("list_my_tickets");
        when(delegate.getToolDefinition()).thenReturn(definition);
        AgentExecutionContext executionContext = new AgentExecutionContext(7L, 8L, 9L);
        ToolContext toolContext = new ToolContext(executionContext.toToolContext());
        AgentToolCall toolCall = new AgentToolCall();
        toolCall.setId(21L);
        when(toolCallService.start(executionContext, "list_my_tickets", "{}"))
                .thenReturn(toolCall);
        when(delegate.call(anyString(), same(toolContext))).thenReturn("{\"total\":2}");
        RecordingToolCallback callback = new RecordingToolCallback(delegate, toolCallService);

        String result = callback.call("{}", toolContext);

        assertThat(result).isEqualTo("{\"total\":2}");
        verify(toolCallService).completeSuccess(21L, "{\"total\":2}");
    }

    /**
     * 验证工具异常会保存失败状态，同时向上继续抛出原始异常。
     */
    @Test
    @DisplayName("失败工具调用应记录失败并保留原异常")
    void failedCallPersistsFailureAndRethrows() {
        ToolDefinition definition = definition("get_my_ticket");
        when(delegate.getToolDefinition()).thenReturn(definition);
        AgentExecutionContext executionContext = new AgentExecutionContext(7L, 8L, 9L);
        ToolContext toolContext = new ToolContext(executionContext.toToolContext());
        AgentToolCall toolCall = new AgentToolCall();
        toolCall.setId(22L);
        when(toolCallService.start(executionContext, "get_my_ticket", "{\"ticketId\":1}"))
                .thenReturn(toolCall);
        IllegalStateException failure = new IllegalStateException("provider detail");
        when(delegate.call(anyString(), same(toolContext))).thenThrow(failure);
        RecordingToolCallback callback = new RecordingToolCallback(delegate, toolCallService);

        assertThatThrownBy(() -> callback.call("{\"ticketId\":1}", toolContext))
                .isSameAs(failure);
        verify(toolCallService).completeFailure(22L);
    }

    /**
     * 构造只包含名称的工具定义供包装器测试使用。
     */
    private ToolDefinition definition(String name) {
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        return definition;
    }
}
