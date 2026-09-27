package com.anying.helpdesk.conversation.service;

import com.anying.helpdesk.agent.domain.AgentToolCall;
import com.anying.helpdesk.agent.service.AgentToolCallService;
import com.anying.helpdesk.common.response.PageResponse;
import com.anying.helpdesk.conversation.config.ChatProperties;
import com.anying.helpdesk.conversation.domain.ChatMessage;
import com.anying.helpdesk.conversation.domain.ChatMessageRole;
import com.anying.helpdesk.conversation.domain.ChatMessageStatus;
import com.anying.helpdesk.conversation.domain.Conversation;
import com.anying.helpdesk.conversation.domain.ConversationStatus;
import com.anying.helpdesk.conversation.dto.ChatMessageResponse;
import com.anying.helpdesk.conversation.dto.ConversationResponse;
import com.anying.helpdesk.conversation.dto.CreateConversationRequest;
import com.anying.helpdesk.conversation.exception.ConcurrentConversationUpdateException;
import com.anying.helpdesk.conversation.exception.ConversationGenerationInProgressException;
import com.anying.helpdesk.conversation.exception.ConversationNotActiveException;
import com.anying.helpdesk.conversation.exception.ConversationNotFoundException;
import com.anying.helpdesk.conversation.mapper.ChatMessageMapper;
import com.anying.helpdesk.conversation.mapper.ConversationMapper;
import com.anying.helpdesk.conversation.model.ChatCompletionResult;
import com.anying.helpdesk.conversation.model.ConversationModelHistory;
import com.anying.helpdesk.modelconfig.factory.ResolvedChatModel;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ConversationPersistenceService {

    private final ConversationMapper conversationMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final ChatProperties chatProperties;
    private final AgentToolCallService toolCallService;

    @Transactional
    public ConversationResponse create(CreateConversationRequest request, Long customerId) {
        LocalDateTime now = LocalDateTime.now();
        Conversation conversation = new Conversation();
        conversation.setCustomerId(customerId);
        conversation.setTitle(request.title().trim());
        conversation.setStatus(ConversationStatus.ACTIVE);
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        conversationMapper.insert(conversation);
        return ConversationResponse.from(conversation);
    }

    @Transactional(readOnly = true)
    public ConversationResponse getById(Long id, Long customerId) {
        return ConversationResponse.from(requireOwnedConversation(id, customerId));
    }

    @Transactional(readOnly = true)
    public void validateCanSend(Long id, Long customerId) {
        Conversation conversation = requireOwnedConversation(id, customerId);
        if (conversation.getStatus() != ConversationStatus.ACTIVE) {
            throw new ConversationNotActiveException(id);
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<ConversationResponse> page(
            long page,
            long size,
            Long customerId,
            ConversationStatus status
    ) {
        Page<Conversation> result = conversationMapper.selectPage(
                Page.of(page, size),
                new LambdaQueryWrapper<Conversation>()
                        .eq(Conversation::getCustomerId, customerId)
                        .eq(status != null, Conversation::getStatus, status)
                        .orderByDesc(Conversation::getLastMessageAt)
                        .orderByDesc(Conversation::getUpdatedAt)
        );
        return new PageResponse<>(
                result.getCurrent(),
                result.getSize(),
                result.getTotal(),
                result.getPages(),
                result.getRecords().stream().map(ConversationResponse::from).toList()
        );
    }

    @Transactional(readOnly = true)
    public PageResponse<ChatMessageResponse> pageMessages(
            Long conversationId,
            long page,
            long size,
            Long customerId
    ) {
        requireOwnedConversation(conversationId, customerId);
        Page<ChatMessage> result = chatMessageMapper.selectPage(
                Page.of(page, size),
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getConversationId, conversationId)
                        .orderByAsc(ChatMessage::getId)
        );
        return new PageResponse<>(
                result.getCurrent(),
                result.getSize(),
                result.getTotal(),
                result.getPages(),
                result.getRecords().stream().map(ChatMessageResponse::from).toList()
        );
    }

    @Transactional
    public PreparedChatExchange prepareExchange(
            Long conversationId,
            Long customerId,
            String content,
            ResolvedChatModel model
    ) {
        Conversation conversation = requireOwnedConversation(conversationId, customerId);
        if (conversation.getStatus() != ConversationStatus.ACTIVE) {
            throw new ConversationNotActiveException(conversationId);
        }
        boolean generationInProgress = chatMessageMapper.selectCount(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getConversationId, conversationId)
                        .eq(ChatMessage::getRole, ChatMessageRole.ASSISTANT)
                        .eq(ChatMessage::getStatus, ChatMessageStatus.PENDING)
        ) > 0;
        if (generationInProgress) {
            throw new ConversationGenerationInProgressException(conversationId);
        }

        LocalDateTime now = LocalDateTime.now();
        ChatMessage userMessage = new ChatMessage();
        userMessage.setConversationId(conversationId);
        userMessage.setRole(ChatMessageRole.USER);
        userMessage.setContent(content.trim());
        userMessage.setStatus(ChatMessageStatus.COMPLETED);
        userMessage.setCreatedAt(now);
        userMessage.setUpdatedAt(now);
        chatMessageMapper.insert(userMessage);

        ChatMessage assistantMessage = new ChatMessage();
        assistantMessage.setConversationId(conversationId);
        assistantMessage.setRole(ChatMessageRole.ASSISTANT);
        assistantMessage.setStatus(ChatMessageStatus.PENDING);
        assistantMessage.setModelConfigId(model.configId());
        assistantMessage.setModelName(model.modelName());
        assistantMessage.setCreatedAt(now);
        assistantMessage.setUpdatedAt(now);
        chatMessageMapper.insert(assistantMessage);

        conversation.setLastMessageAt(now);
        conversation.setUpdatedAt(now);
        if (conversationMapper.updateById(conversation) == 0) {
            throw new ConcurrentConversationUpdateException(conversationId);
        }

        List<ChatMessage> history = loadRecentCompletedHistory(conversationId);
        return new PreparedChatExchange(
                userMessage,
                assistantMessage.getId(),
                new ConversationModelHistory(history, loadRecentToolCalls(history))
        );
    }

    @Transactional
    public ChatMessage completeAssistant(Long assistantMessageId, ChatCompletionResult result) {
        ChatMessage message = requirePendingAssistant(assistantMessageId);
        LocalDateTime now = LocalDateTime.now();
        message.setContent(result.content());
        message.setStatus(ChatMessageStatus.COMPLETED);
        message.setPromptTokens(result.promptTokens());
        message.setCompletionTokens(result.completionTokens());
        message.setTotalTokens(result.totalTokens());
        message.setUpdatedAt(now);
        if (chatMessageMapper.updateById(message) == 0) {
            throw new ConcurrentConversationUpdateException(message.getConversationId());
        }
        touchConversation(message.getConversationId(), now);
        return message;
    }

    @Transactional
    public void failAssistant(Long assistantMessageId, String errorCode) {
        ChatMessage message = requirePendingAssistant(assistantMessageId);
        LocalDateTime now = LocalDateTime.now();
        message.setStatus(ChatMessageStatus.FAILED);
        message.setErrorCode(errorCode);
        message.setUpdatedAt(now);
        if (chatMessageMapper.updateById(message) == 0) {
            throw new ConcurrentConversationUpdateException(message.getConversationId());
        }
        touchConversation(message.getConversationId(), now);
    }

    private List<ChatMessage> loadRecentCompletedHistory(Long conversationId) {
        Page<ChatMessage> result = chatMessageMapper.selectPage(
                new Page<>(1, chatProperties.historyLimit(), false),
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getConversationId, conversationId)
                        .eq(ChatMessage::getStatus, ChatMessageStatus.COMPLETED)
                        .in(ChatMessage::getRole, ChatMessageRole.USER, ChatMessageRole.ASSISTANT)
                        .orderByDesc(ChatMessage::getId)
        );
        List<ChatMessage> history = new ArrayList<>(result.getRecords());
        Collections.reverse(history);
        return List.copyOf(history);
    }

    /**
     * 仅加载紧邻当前客户消息的上一条 AI 回复所关联的成功工具调用。
     */
    private List<AgentToolCall> loadRecentToolCalls(List<ChatMessage> history) {
        if (history.size() < 2) {
            return List.of();
        }
        ChatMessage currentMessage = history.getLast();
        ChatMessage previousMessage = history.get(history.size() - 2);
        if (currentMessage.getRole() != ChatMessageRole.USER
                || previousMessage.getRole() != ChatMessageRole.ASSISTANT) {
            return List.of();
        }
        return toolCallService.listSuccessfulByAssistantMessageId(previousMessage.getId());
    }

    private Conversation requireOwnedConversation(Long id, Long customerId) {
        Conversation conversation = conversationMapper.selectById(id);
        if (conversation == null || !customerId.equals(conversation.getCustomerId())) {
            throw new ConversationNotFoundException(id);
        }
        return conversation;
    }

    private ChatMessage requirePendingAssistant(Long id) {
        ChatMessage message = chatMessageMapper.selectById(id);
        if (message == null
                || message.getRole() != ChatMessageRole.ASSISTANT
                || message.getStatus() != ChatMessageStatus.PENDING) {
            throw new ConcurrentConversationUpdateException(
                    message == null ? -1L : message.getConversationId()
            );
        }
        return message;
    }

    private void touchConversation(Long conversationId, LocalDateTime now) {
        conversationMapper.update(null, new LambdaUpdateWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .set(Conversation::getLastMessageAt, now)
                .set(Conversation::getUpdatedAt, now));
    }
}
