package com.anying.helpdesk.agent.service;

import com.anying.helpdesk.agent.AgentExecutionContext;
import com.anying.helpdesk.agent.domain.AgentToolCall;
import com.anying.helpdesk.agent.domain.AgentToolCallStatus;
import com.anying.helpdesk.agent.mapper.AgentToolCallMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AgentToolCallService {

    private static final String TOOL_EXECUTION_FAILED = "TOOL_EXECUTION_FAILED";

    private final AgentToolCallMapper toolCallMapper;

    /**
     * 在工具真正执行前创建运行中记录，并分配本次回答内的稳定调用顺序。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AgentToolCall start(
            AgentExecutionContext context,
            String toolName,
            String argumentsJson
    ) {
        AgentToolCall toolCall = new AgentToolCall();
        toolCall.setConversationId(context.conversationId());
        toolCall.setAssistantMessageId(context.assistantMessageId());
        toolCall.setCustomerId(context.customerId());
        toolCall.setCallId(UUID.randomUUID().toString());
        toolCall.setSequenceNo(context.nextToolSequence());
        toolCall.setToolName(toolName);
        toolCall.setArgumentsJson(argumentsJson == null ? "{}" : argumentsJson);
        toolCall.setStatus(AgentToolCallStatus.RUNNING);
        toolCall.setStartedAt(LocalDateTime.now());
        toolCallMapper.insert(toolCall);
        return toolCall;
    }

    /**
     * 保存模型实际接收到的工具结果，并将调用标记为成功。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeSuccess(Long id, String resultJson) {
        AgentToolCall toolCall = requireToolCall(id);
        LocalDateTime completedAt = LocalDateTime.now();
        toolCall.setResultJson(resultJson == null ? "null" : resultJson);
        toolCall.setStatus(AgentToolCallStatus.SUCCESS);
        toolCall.setCompletedAt(completedAt);
        toolCall.setDurationMs(durationMillis(toolCall.getStartedAt(), completedAt));
        updateToolCall(toolCall);
    }

    /**
     * 记录工具执行失败，数据库只保存固定脱敏错误码而不保存异常正文。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeFailure(Long id) {
        AgentToolCall toolCall = requireToolCall(id);
        LocalDateTime completedAt = LocalDateTime.now();
        toolCall.setStatus(AgentToolCallStatus.FAILED);
        toolCall.setErrorCode(TOOL_EXECUTION_FAILED);
        toolCall.setCompletedAt(completedAt);
        toolCall.setDurationMs(durationMillis(toolCall.getStartedAt(), completedAt));
        updateToolCall(toolCall);
    }

    /**
     * 按原始执行顺序读取某条 AI 消息关联的全部成功工具调用。
     */
    @Transactional(readOnly = true)
    public List<AgentToolCall> listSuccessfulByAssistantMessageId(Long assistantMessageId) {
        return toolCallMapper.selectList(new LambdaQueryWrapper<AgentToolCall>()
                .eq(AgentToolCall::getAssistantMessageId, assistantMessageId)
                .eq(AgentToolCall::getStatus, AgentToolCallStatus.SUCCESS)
                .orderByAsc(AgentToolCall::getSequenceNo));
    }

    /**
     * 批量读取多条 AI 消息关联的成功工具调用，供消息历史一次性组装附加信息。
     */
    @Transactional(readOnly = true)
    public List<AgentToolCall> listSuccessfulByAssistantMessageIds(
            Collection<Long> assistantMessageIds
    ) {
        if (assistantMessageIds == null || assistantMessageIds.isEmpty()) {
            return List.of();
        }
        return toolCallMapper.selectList(new LambdaQueryWrapper<AgentToolCall>()
                .in(AgentToolCall::getAssistantMessageId, assistantMessageIds)
                .eq(AgentToolCall::getStatus, AgentToolCallStatus.SUCCESS)
                .orderByAsc(AgentToolCall::getAssistantMessageId)
                .orderByAsc(AgentToolCall::getSequenceNo));
    }

    /**
     * 读取待更新的工具调用记录，不存在时视为内部状态错误。
     */
    private AgentToolCall requireToolCall(Long id) {
        AgentToolCall toolCall = toolCallMapper.selectById(id);
        if (toolCall == null) {
            throw new IllegalStateException("agent tool call not found: " + id);
        }
        return toolCall;
    }

    /**
     * 使用乐观锁更新工具调用状态，避免成功和失败结果互相覆盖。
     */
    private void updateToolCall(AgentToolCall toolCall) {
        if (toolCallMapper.updateById(toolCall) == 0) {
            throw new IllegalStateException("agent tool call was concurrently updated: " + toolCall.getId());
        }
    }

    /**
     * 计算工具执行耗时，系统时钟回拨时最低返回零毫秒。
     */
    private long durationMillis(LocalDateTime startedAt, LocalDateTime completedAt) {
        return Math.max(0, Duration.between(startedAt, completedAt).toMillis());
    }
}
