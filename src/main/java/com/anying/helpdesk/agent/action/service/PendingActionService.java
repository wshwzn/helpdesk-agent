package com.anying.helpdesk.agent.action.service;

import com.anying.helpdesk.agent.AgentExecutionContext;
import com.anying.helpdesk.agent.action.domain.AgentPendingAction;
import com.anying.helpdesk.agent.action.domain.PendingActionStatus;
import com.anying.helpdesk.agent.action.domain.PendingActionType;
import com.anying.helpdesk.agent.action.dto.PendingActionExecutionResponse;
import com.anying.helpdesk.agent.action.dto.PendingActionResponse;
import com.anying.helpdesk.agent.action.dto.PendingActionVersionRequest;
import com.anying.helpdesk.agent.action.dto.UpdatePendingActionRequest;
import com.anying.helpdesk.agent.action.exception.ConcurrentPendingActionUpdateException;
import com.anying.helpdesk.agent.action.exception.InvalidPendingActionStatusException;
import com.anying.helpdesk.agent.action.exception.PendingActionExpiredException;
import com.anying.helpdesk.agent.action.exception.PendingActionNotFoundException;
import com.anying.helpdesk.agent.action.mapper.AgentPendingActionMapper;
import com.anying.helpdesk.conversation.domain.ChatMessage;
import com.anying.helpdesk.conversation.domain.ChatMessageRole;
import com.anying.helpdesk.conversation.domain.Conversation;
import com.anying.helpdesk.conversation.mapper.ChatMessageMapper;
import com.anying.helpdesk.conversation.mapper.ConversationMapper;
import com.anying.helpdesk.ticket.domain.TicketPriority;
import com.anying.helpdesk.ticket.dto.CreateTicketRequest;
import com.anying.helpdesk.ticket.dto.TicketResponse;
import com.anying.helpdesk.ticket.service.TicketService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class PendingActionService {

    private static final Duration CONFIRMATION_TTL = Duration.ofHours(24);

    private final AgentPendingActionMapper pendingActionMapper;
    private final ConversationMapper conversationMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final TicketService ticketService;

    /**
     * 根据 AI 整理的内容创建工单草稿，同一条 AI 消息只保留一个创建工单操作。
     */
    @Transactional
    public PendingActionResponse prepareCreateTicket(
            AgentExecutionContext context,
            String title,
            String description,
            TicketPriority priority,
            String priorityReason
    ) {
        requireTrustedContext(context);
        AgentPendingAction existing = findByAssistantAndType(
                context.assistantMessageId(),
                PendingActionType.CREATE_TICKET
        );
        if (existing != null) {
            return PendingActionResponse.from(existing);
        }

        LocalDateTime now = LocalDateTime.now();
        AgentPendingAction action = new AgentPendingAction();
        action.setConversationId(context.conversationId());
        action.setAssistantMessageId(context.assistantMessageId());
        action.setCustomerId(context.customerId());
        action.setActionType(PendingActionType.CREATE_TICKET);
        action.setStatus(PendingActionStatus.PENDING);
        action.setTitle(normalizeRequired(title, 100, "title"));
        action.setDescription(normalizeRequired(description, 2000, "description"));
        action.setPriority(priority == null ? TicketPriority.MEDIUM : priority);
        action.setPriorityReason(normalizeOptional(priorityReason, 500, "priorityReason"));
        action.setExpiresAt(now.plus(CONFIRMATION_TTL));
        action.setVersion(0);
        action.setCreatedAt(now);
        action.setUpdatedAt(now);
        try {
            pendingActionMapper.insert(action);
            return PendingActionResponse.from(action);
        } catch (DuplicateKeyException exception) {
            AgentPendingAction duplicated = findByAssistantAndType(
                    context.assistantMessageId(),
                    PendingActionType.CREATE_TICKET
            );
            if (duplicated != null) {
                return PendingActionResponse.from(duplicated);
            }
            throw exception;
        }
    }

    /**
     * 查询一条 AI 消息生成且仍需客户确认的操作，用于同步响应和 SSE 卡片事件。
     */
    @Transactional
    public List<PendingActionResponse> listPendingByAssistantMessageId(
            Long assistantMessageId,
            Long customerId
    ) {
        expireOverdue(customerId, null);
        return pendingActionMapper.selectList(new LambdaQueryWrapper<AgentPendingAction>()
                        .eq(AgentPendingAction::getAssistantMessageId, assistantMessageId)
                        .eq(AgentPendingAction::getCustomerId, customerId)
                        .eq(AgentPendingAction::getStatus, PendingActionStatus.PENDING)
                        .orderByAsc(AgentPendingAction::getId))
                .stream()
                .map(PendingActionResponse::from)
                .toList();
    }

    /**
     * 查询客户在指定对话中的操作记录，供页面刷新后恢复确认卡片状态。
     */
    @Transactional
    public List<PendingActionResponse> listByConversation(
            Long conversationId,
            Long customerId,
            PendingActionStatus status
    ) {
        requireOwnedConversation(conversationId, customerId);
        expireOverdue(customerId, conversationId);
        return pendingActionMapper.selectList(new LambdaQueryWrapper<AgentPendingAction>()
                        .eq(AgentPendingAction::getConversationId, conversationId)
                        .eq(AgentPendingAction::getCustomerId, customerId)
                        .eq(status != null, AgentPendingAction::getStatus, status)
                        .orderByAsc(AgentPendingAction::getId))
                .stream()
                .map(PendingActionResponse::from)
                .toList();
    }

    /**
     * 保存客户对工单标题和描述的修改，优先级仍由 Agent 建议并由客服后续调整。
     */
    @Transactional(noRollbackFor = PendingActionExpiredException.class)
    public PendingActionResponse updateDraft(
            Long id,
            UpdatePendingActionRequest request,
            Long customerId
    ) {
        AgentPendingAction action = requireOwnedAction(id, customerId);
        requirePendingAndUnexpired(action, "update");
        requireVersion(action, request.version());
        action.setTitle(normalizeRequired(request.title(), 100, "title"));
        action.setDescription(normalizeRequired(request.description(), 2000, "description"));
        action.setUpdatedAt(LocalDateTime.now());
        updateWithOptimisticLock(action);
        return PendingActionResponse.from(action);
    }

    /**
     * 确认待处理草稿并在同一事务中创建真实工单，重复确认直接返回首次创建结果。
     */
    @Transactional(noRollbackFor = PendingActionExpiredException.class)
    public PendingActionExecutionResponse confirm(
            Long id,
            PendingActionVersionRequest request,
            Long customerId
    ) {
        AgentPendingAction action = requireOwnedAction(id, customerId);
        if (action.getStatus() == PendingActionStatus.EXECUTED) {
            return executedResponse(action, customerId);
        }
        requirePendingAndUnexpired(action, "confirm");
        requireVersion(action, request.version());

        TicketResponse ticket = ticketService.create(new CreateTicketRequest(
                action.getTitle(),
                action.getDescription(),
                action.getPriority()
        ), customerId);
        LocalDateTime now = LocalDateTime.now();
        action.setStatus(PendingActionStatus.EXECUTED);
        action.setTicketId(ticket.id());
        action.setExecutedAt(now);
        action.setUpdatedAt(now);
        updateWithOptimisticLock(action);
        return new PendingActionExecutionResponse(PendingActionResponse.from(action), ticket);
    }

    /**
     * 取消尚未执行的草稿，重复取消保持幂等并返回相同状态。
     */
    @Transactional(noRollbackFor = PendingActionExpiredException.class)
    public PendingActionResponse cancel(
            Long id,
            PendingActionVersionRequest request,
            Long customerId
    ) {
        AgentPendingAction action = requireOwnedAction(id, customerId);
        if (action.getStatus() == PendingActionStatus.CANCELLED) {
            return PendingActionResponse.from(action);
        }
        requirePendingAndUnexpired(action, "cancel");
        requireVersion(action, request.version());
        LocalDateTime now = LocalDateTime.now();
        action.setStatus(PendingActionStatus.CANCELLED);
        action.setCancelledAt(now);
        action.setUpdatedAt(now);
        updateWithOptimisticLock(action);
        return PendingActionResponse.from(action);
    }

    /**
     * 将模型生成失败或流被取消前创建的草稿作废，防止未完整展示的操作被继续确认。
     */
    @Transactional
    public void invalidateByAssistantMessageId(Long assistantMessageId) {
        List<AgentPendingAction> actions = pendingActionMapper.selectList(
                new LambdaQueryWrapper<AgentPendingAction>()
                        .eq(AgentPendingAction::getAssistantMessageId, assistantMessageId)
                        .eq(AgentPendingAction::getStatus, PendingActionStatus.PENDING)
        );
        LocalDateTime now = LocalDateTime.now();
        for (AgentPendingAction action : actions) {
            action.setStatus(PendingActionStatus.INVALIDATED);
            action.setUpdatedAt(now);
            updateWithOptimisticLock(action);
        }
    }

    /**
     * 验证工具上下文中的客户、对话和 AI 消息关联均由当前服务端流程创建。
     */
    private void requireTrustedContext(AgentExecutionContext context) {
        requireOwnedConversation(context.conversationId(), context.customerId());
        ChatMessage message = chatMessageMapper.selectById(context.assistantMessageId());
        if (message == null
                || !context.conversationId().equals(message.getConversationId())
                || message.getRole() != ChatMessageRole.ASSISTANT) {
            throw new IllegalStateException("assistant message does not belong to the current conversation");
        }
    }

    /**
     * 查询同一 AI 消息下指定类型的现有操作，用于保证工具调用幂等。
     */
    private AgentPendingAction findByAssistantAndType(
            Long assistantMessageId,
            PendingActionType actionType
    ) {
        return pendingActionMapper.selectOne(new LambdaQueryWrapper<AgentPendingAction>()
                .eq(AgentPendingAction::getAssistantMessageId, assistantMessageId)
                .eq(AgentPendingAction::getActionType, actionType));
    }

    /**
     * 加载当前客户拥有的待确认操作，不向其他客户泄露记录是否存在。
     */
    private AgentPendingAction requireOwnedAction(Long id, Long customerId) {
        AgentPendingAction action = pendingActionMapper.selectById(id);
        if (action == null || !customerId.equals(action.getCustomerId())) {
            throw new PendingActionNotFoundException(id);
        }
        return action;
    }

    /**
     * 校验对话归属，防止通过查询参数读取其他客户的卡片。
     */
    private void requireOwnedConversation(Long conversationId, Long customerId) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !customerId.equals(conversation.getCustomerId())) {
            throw new PendingActionNotFoundException(conversationId);
        }
    }

    /**
     * 校验操作仍可处理；发现超时草稿时先持久化过期状态再返回冲突。
     */
    private void requirePendingAndUnexpired(AgentPendingAction action, String operation) {
        if (action.getStatus() != PendingActionStatus.PENDING) {
            throw new InvalidPendingActionStatusException(operation, action.getStatus());
        }
        if (!action.getExpiresAt().isAfter(LocalDateTime.now())) {
            action.setStatus(PendingActionStatus.EXPIRED);
            action.setUpdatedAt(LocalDateTime.now());
            updateWithOptimisticLock(action);
            throw new PendingActionExpiredException(action.getId());
        }
    }

    /**
     * 校验客户端提交的版本，避免旧卡片覆盖较新的草稿状态。
     */
    private void requireVersion(AgentPendingAction action, Integer expectedVersion) {
        if (!Objects.equals(action.getVersion(), expectedVersion)) {
            throw new ConcurrentPendingActionUpdateException(action.getId());
        }
    }

    /**
     * 批量将指定客户范围内已超时的草稿转换为过期状态。
     */
    private void expireOverdue(Long customerId, Long conversationId) {
        List<AgentPendingAction> overdueActions = pendingActionMapper.selectList(
                new LambdaQueryWrapper<AgentPendingAction>()
                        .eq(AgentPendingAction::getCustomerId, customerId)
                        .eq(conversationId != null, AgentPendingAction::getConversationId, conversationId)
                        .eq(AgentPendingAction::getStatus, PendingActionStatus.PENDING)
                        .le(AgentPendingAction::getExpiresAt, LocalDateTime.now())
        );
        LocalDateTime now = LocalDateTime.now();
        for (AgentPendingAction action : overdueActions) {
            action.setStatus(PendingActionStatus.EXPIRED);
            action.setUpdatedAt(now);
            updateWithOptimisticLock(action);
        }
    }

    /**
     * 返回已经执行的操作及其唯一工单，实现确认接口的安全重试。
     */
    private PendingActionExecutionResponse executedResponse(
            AgentPendingAction action,
            Long customerId
    ) {
        TicketResponse ticket = ticketService.getById(action.getTicketId(), customerId);
        return new PendingActionExecutionResponse(PendingActionResponse.from(action), ticket);
    }

    /**
     * 使用 MyBatis-Plus 乐观锁更新操作状态或草稿内容。
     */
    private void updateWithOptimisticLock(AgentPendingAction action) {
        if (pendingActionMapper.updateById(action) == 0) {
            throw new ConcurrentPendingActionUpdateException(action.getId());
        }
    }

    /**
     * 清理并校验模型或客户提供的必填文本。
     */
    private String normalizeRequired(String value, int maxLength, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " exceeds maximum length " + maxLength);
        }
        return normalized;
    }

    /**
     * 清理并校验模型提供的可选文本，空白内容按未提供处理。
     */
    private String normalizeOptional(String value, int maxLength, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " exceeds maximum length " + maxLength);
        }
        return normalized;
    }
}
