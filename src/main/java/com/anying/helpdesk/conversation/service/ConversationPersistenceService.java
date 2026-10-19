package com.anying.helpdesk.conversation.service;

import com.anying.helpdesk.knowledge.answer.KnowledgeAnswerReport;

import com.anying.helpdesk.knowledge.answer.KnowledgeClarificationException;

import com.anying.helpdesk.knowledge.answer.KnowledgeAnswerInput;

import com.anying.helpdesk.knowledge.answer.KnowledgeApplicabilityService;

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
import com.anying.helpdesk.conversation.dto.KnowledgeCitationResponse;
import com.anying.helpdesk.conversation.exception.ConcurrentConversationUpdateException;
import com.anying.helpdesk.conversation.exception.ConversationGenerationInProgressException;
import com.anying.helpdesk.conversation.exception.ConversationNotActiveException;
import com.anying.helpdesk.conversation.exception.ConversationNotFoundException;
import com.anying.helpdesk.conversation.mapper.ChatMessageMapper;
import com.anying.helpdesk.conversation.mapper.ConversationMapper;
import com.anying.helpdesk.conversation.model.ChatCompletionResult;
import com.anying.helpdesk.conversation.dto.SendMessageRequest;
import com.anying.helpdesk.conversation.domain.ChatMode;
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
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ConversationPersistenceService {

    private final ConversationMapper conversationMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final ChatProperties chatProperties;
    private final AgentToolCallService toolCallService;
    private final KnowledgeCitationService knowledgeCitationService;
    private final StaleChatGenerationRecoveryService staleGenerationRecoveryService;
    private final KnowledgeApplicabilityService applicabilityService;

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

    /**
     * 将客户自己的活动对话归档；存在未超时生成任务时拒绝删除，避免后台继续写入。
     */
    @Transactional
    public void archive(Long id, Long customerId) {
        Conversation conversation = requireOwnedConversation(id, customerId);
        if (conversation.getStatus() == ConversationStatus.ARCHIVED) {
            return;
        }
        staleGenerationRecoveryService.recoverForConversation(id);
        boolean generationInProgress = chatMessageMapper.selectCount(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getConversationId, id)
                        .eq(ChatMessage::getRole, ChatMessageRole.ASSISTANT)
                        .eq(ChatMessage::getStatus, ChatMessageStatus.PENDING)
        ) > 0;
        if (generationInProgress) {
            throw new ConversationGenerationInProgressException(id);
        }
        conversation.setStatus(ConversationStatus.ARCHIVED);
        conversation.setUpdatedAt(LocalDateTime.now());
        if (conversationMapper.updateById(conversation) == 0) {
            throw new ConcurrentConversationUpdateException(id);
        }
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
        Map<Long, List<KnowledgeCitationResponse>> citationsByMessage =
                knowledgeCitationService.listByAssistantMessageIds(
                        result.getRecords().stream()
                                .filter(message -> message.getRole() == ChatMessageRole.ASSISTANT)
                                .map(ChatMessage::getId)
                                .toList()
                );
        return new PageResponse<>(
                result.getCurrent(),
                result.getSize(),
                result.getTotal(),
                result.getPages(),
                result.getRecords().stream()
                        .map(message -> ChatMessageResponse.from(
                                message,
                                citationsByMessage.getOrDefault(message.getId(), List.of())
                        ))
                        .toList()
        );
    }

    /** 兼容普通 Agent 调用，使用默认消息模式创建本轮记录。 */
    @Transactional
    public PreparedChatExchange prepareExchange(Long conversationId, Long customerId, String content,
                                                 ResolvedChatModel model) {
        return prepareExchange(conversationId, customerId, new SendMessageRequest(content), model);
    }

    /** 在短事务内保存问题和待生成消息，同时快照本轮模式及适用范围。 */
    @Transactional
    public PreparedChatExchange prepareExchange(
            Long conversationId,
            Long customerId,
            SendMessageRequest request,
            ResolvedChatModel model
    ) {
        Conversation conversation = requireOwnedConversation(conversationId, customerId);
        if (conversation.getStatus() != ConversationStatus.ACTIVE) {
            throw new ConversationNotActiveException(conversationId);
        }
        staleGenerationRecoveryService.recoverForConversation(conversationId);
        boolean generationInProgress = chatMessageMapper.selectCount(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getConversationId, conversationId)
                        .eq(ChatMessage::getRole, ChatMessageRole.ASSISTANT)
                        .eq(ChatMessage::getStatus, ChatMessageStatus.PENDING)
        ) > 0;
        if (generationInProgress) {
            throw new ConversationGenerationInProgressException(conversationId);
        }

        var knowledgeInput = resolveKnowledgeInput(conversationId, request);
        LocalDateTime now = LocalDateTime.now();
        ChatMessage userMessage = new ChatMessage();
        userMessage.setConversationId(conversationId);
        userMessage.setRole(ChatMessageRole.USER);
        userMessage.setContent(request.content().trim());
        userMessage.setMode(request.mode());
        userMessage.setApplicabilityScope(request.applicabilityScope());
        userMessage.setStatus(ChatMessageStatus.COMPLETED);
        userMessage.setCreatedAt(now);
        userMessage.setUpdatedAt(now);
        chatMessageMapper.insert(userMessage);

        ChatMessage assistantMessage = new ChatMessage();
        assistantMessage.setConversationId(conversationId);
        assistantMessage.setRole(ChatMessageRole.ASSISTANT);
        assistantMessage.setMode(request.mode());
        assistantMessage.setApplicabilityScope(request.applicabilityScope());
        if (request.mode() == ChatMode.KNOWLEDGE) {
            assistantMessage.setKnowledgeRunId(java.util.UUID.randomUUID().toString());
        }
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
                new ConversationModelHistory(history, loadRecentToolCalls(history)),
                assistantMessage.getKnowledgeRunId(), knowledgeInput
        );
    }

    /** 只允许续答当前对话最后一条结构化澄清，原问题和知识库均由服务端恢复。 */
    private KnowledgeAnswerInput resolveKnowledgeInput(
            Long conversationId, SendMessageRequest request) {
        if (request.clarificationMessageId() == null)
            return new KnowledgeAnswerInput(
                    request.content().trim(), request.applicabilityScope(), null, null, null);
        var latest = chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId).orderByDesc(ChatMessage::getId).last("LIMIT 1"));
        var source = latest.isEmpty() ? null : latest.getFirst();
        if (source == null || !source.getId().equals(request.clarificationMessageId())
                || source.getRole() != ChatMessageRole.ASSISTANT || source.getStatus() != ChatMessageStatus.COMPLETED
                || source.getMode() != ChatMode.KNOWLEDGE || source.getKnowledgeAnswer() == null
                || source.getKnowledgeAnswer().reason() != KnowledgeAnswerReport.Reason.APPLICABILITY_REQUIRED
                || source.getKnowledgeAnswer().clarification() == null)
            throw new KnowledgeClarificationException(true, "clarification is no longer available; ask the original question again");
        var report = source.getKnowledgeAnswer();
        var clarification = report.clarification();
        if (request.mode() != ChatMode.KNOWLEDGE || clarification.scopeOptions().stream()
                .noneMatch(option -> option.value().equals(request.applicabilityScope())))
            throw new KnowledgeClarificationException(false, "select a scope offered by the clarification");
        if (applicabilityService.get(report.knowledgeBaseId()).revision() != clarification.policyRevision())
            throw new KnowledgeClarificationException(true, "applicability policy has changed; ask the original question again");
        return new KnowledgeAnswerInput(clarification.originalQuestion(),
                request.applicabilityScope(), report.knowledgeBaseId(), source.getId(), clarification.policyRevision());
    }

    /** 原子保存最终正文、Token 用量和核验报告，拒绝覆盖已结束的生成任务。 */
    @Transactional
    public ChatMessage completeAssistant(Long assistantMessageId, ChatCompletionResult result) {
        ChatMessage message = requirePendingAssistant(assistantMessageId);
        if ((message.getMode() == ChatMode.KNOWLEDGE) != (result.knowledgeAnswer() != null)
                || (result.knowledgeAnswer() != null && !java.util.Objects.equals(
                message.getApplicabilityScope(), result.knowledgeAnswer().applicabilityScope()))) {
            throw new IllegalStateException("knowledge answer does not match message mode or scope");
        }
        LocalDateTime now = LocalDateTime.now();
        message.setContent(result.content());
        message.setStatus(ChatMessageStatus.COMPLETED);
        message.setPromptTokens(result.promptTokens());
        message.setCompletionTokens(result.completionTokens());
        message.setTotalTokens(result.totalTokens());
        message.setKnowledgeAnswer(result.knowledgeAnswer());
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
