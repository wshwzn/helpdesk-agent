package com.anying.helpdesk.agent.tool;

import com.anying.helpdesk.agent.service.AgentToolCallService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
@RequiredArgsConstructor
public class RecordingToolCallbackFactory {

    private final AgentToolCallService toolCallService;

    /**
     * 将一组业务工具转换为自动记录执行过程的工具回调。
     */
    public List<ToolCallback> wrap(ToolCallback[] callbacks) {
        return Arrays.stream(callbacks)
                .map(callback -> new RecordingToolCallback(callback, toolCallService))
                .map(ToolCallback.class::cast)
                .toList();
    }
}
