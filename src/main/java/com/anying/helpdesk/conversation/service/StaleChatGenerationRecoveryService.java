package com.anying.helpdesk.conversation.service;

import com.anying.helpdesk.agent.action.domain.AgentPendingAction;
import com.anying.helpdesk.agent.action.domain.PendingActionStatus;
import com.anying.helpdesk.agent.action.mapper.AgentPendingActionMapper;
import com.anying.helpdesk.agent.domain.AgentToolCall;
import com.anying.helpdesk.agent.domain.AgentToolCallStatus;
import com.anying.helpdesk.agent.mapper.AgentToolCallMapper;
import com.anying.helpdesk.conversation.config.ChatProperties;
import com.anying.helpdesk.conversation.domain.ChatMessage;
import com.anying.helpdesk.conversation.domain.ChatMessageRole;
import com.anying.helpdesk.conversation.domain.ChatMessageStatus;
import com.anying.helpdesk.conversation.mapper.ChatMessageMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StaleChatGenerationRecoveryService {

    public static final String GENERATION_INTERRUPTED = "GENERATION_INTERRUPTED";
    private static final String TOOL_EXECUTION_FAILED = "TOOL_EXECUTION_FAILED";
    private static final long RECOVERY_BATCH_SIZE = 200;

    private final ChatMessageMapper chatMessageMapper;
    private final AgentToolCallMapper toolCallMapper;
    private final AgentPendingActionMapper pendingActionMapper;
    private final ChatProperties chatProperties;

    /**
     * 在客户继续发送前回收当前对话中过期的生成任务，避免一次进程中断永久阻塞对话。
     */
    @Transactional
    public int recoverForConversation(Long conversationId) {
        return recover(conversationId);
    }

    /**
     * 批量回收全局过期生成任务，供后台定时清理数据库中的遗留状态。
     */
    @Transactional
    public int recoverAll() {
        return recover(null);
    }

    /**
     * 查询已超过阈值的助手消息，并逐条以条件更新方式竞争回收权。
     */
    private int recover(Long conversationId) {
        LocalDateTime recoveredAt = LocalDateTime.now();
        LocalDateTime expiredBefore = recoveredAt.minus(chatProperties.pendingTimeout());
        LambdaQueryWrapper<ChatMessage> query = new LambdaQueryWrapper<ChatMessage>()
                .eq(conversationId != null, ChatMessage::getConversationId, conversationId)
                .eq(ChatMessage::getRole, ChatMessageRole.ASSISTANT)
                .eq(ChatMessage::getStatus, ChatMessageStatus.PENDING)
                .lt(ChatMessage::getUpdatedAt, expiredBefore)
                .orderByAsc(ChatMessage::getId);
        List<ChatMessage> candidates = conversationId == null
                ? chatMessageMapper.selectPage(new Page<>(1, RECOVERY_BATCH_SIZE, false), query).getRecords()
                : chatMessageMapper.selectList(query);
        int recoveredCount = 0;
        for (ChatMessage candidate : candidates) {
            if (!recoverMessage(candidate.getId(), expiredBefore, recoveredAt)) {
                continue;
            }
            failRunningToolCalls(candidate.getId(), recoveredAt);
            invalidatePendingActions(candidate.getId(), recoveredAt);
            recoveredCount++;
        }
        return recoveredCount;
    }

    /**
     * 只回收仍为 PENDING 且仍然过期的消息，并递增版本阻止迟到结果覆盖失败状态。
     */
    private boolean recoverMessage(Long messageId, LocalDateTime expiredBefore, LocalDateTime recoveredAt) {
        return chatMessageMapper.update(null, new LambdaUpdateWrapper<ChatMessage>()
                .eq(ChatMessage::getId, messageId)
                .eq(ChatMessage::getRole, ChatMessageRole.ASSISTANT)
                .eq(ChatMessage::getStatus, ChatMessageStatus.PENDING)
                .lt(ChatMessage::getUpdatedAt, expiredBefore)
                .set(ChatMessage::getStatus, ChatMessageStatus.FAILED)
                .set(ChatMessage::getErrorCode, GENERATION_INTERRUPTED)
                .set(ChatMessage::getUpdatedAt, recoveredAt)
                .setSql("version = version + 1")) == 1;
    }

    /**
     * 将被中断回答下尚未完成的工具记录标记为失败，保留完整审计信息。
     */
    private void failRunningToolCalls(Long assistantMessageId, LocalDateTime recoveredAt) {
        List<AgentToolCall> toolCalls = toolCallMapper.selectList(
                new LambdaQueryWrapper<AgentToolCall>()
                        .eq(AgentToolCall::getAssistantMessageId, assistantMessageId)
                        .eq(AgentToolCall::getStatus, AgentToolCallStatus.RUNNING)
        );
        for (AgentToolCall toolCall : toolCalls) {
            toolCall.setStatus(AgentToolCallStatus.FAILED);
            toolCall.setErrorCode(TOOL_EXECUTION_FAILED);
            toolCall.setCompletedAt(recoveredAt);
            toolCall.setDurationMs(Math.max(0, Duration.between(toolCall.getStartedAt(), recoveredAt).toMillis()));
            toolCallMapper.updateById(toolCall);
        }
    }

    /**
     * 作废被中断回答产生但尚未确认的业务草稿，防止客户执行不完整的 Agent 决策。
     */
    private void invalidatePendingActions(Long assistantMessageId, LocalDateTime recoveredAt) {
        pendingActionMapper.update(null, new LambdaUpdateWrapper<AgentPendingAction>()
                .eq(AgentPendingAction::getAssistantMessageId, assistantMessageId)
                .eq(AgentPendingAction::getStatus, PendingActionStatus.PENDING)
                .set(AgentPendingAction::getStatus, PendingActionStatus.INVALIDATED)
                .set(AgentPendingAction::getUpdatedAt, recoveredAt)
                .setSql("version = version + 1"));
    }
}
