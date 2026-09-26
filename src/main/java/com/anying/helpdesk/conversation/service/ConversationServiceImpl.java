package com.anying.helpdesk.conversation.service;

import com.anying.helpdesk.common.response.PageResponse;
import com.anying.helpdesk.conversation.domain.ChatMessage;
import com.anying.helpdesk.conversation.domain.ConversationStatus;
import com.anying.helpdesk.conversation.dto.ChatExchangeResponse;
import com.anying.helpdesk.conversation.dto.ChatMessageResponse;
import com.anying.helpdesk.conversation.dto.ConversationResponse;
import com.anying.helpdesk.conversation.dto.CreateConversationRequest;
import com.anying.helpdesk.conversation.dto.SendMessageRequest;
import com.anying.helpdesk.conversation.exception.ChatGenerationException;
import com.anying.helpdesk.conversation.model.ChatCompletionResult;
import com.anying.helpdesk.conversation.model.ConversationModelClient;
import com.anying.helpdesk.modelconfig.factory.DefaultChatModelProvider;
import com.anying.helpdesk.modelconfig.factory.ResolvedChatModel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ConversationServiceImpl implements ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationServiceImpl.class);
    private static final String MODEL_CALL_FAILED = "MODEL_CALL_FAILED";

    private final ConversationPersistenceService persistenceService;
    private final DefaultChatModelProvider defaultChatModelProvider;
    private final ConversationModelClient modelClient;

    @Override
    public ConversationResponse create(CreateConversationRequest request, Long customerId) {
        return persistenceService.create(request, customerId);
    }

    @Override
    public ConversationResponse getById(Long id, Long customerId) {
        return persistenceService.getById(id, customerId);
    }

    @Override
    public PageResponse<ConversationResponse> page(
            long page,
            long size,
            Long customerId,
            ConversationStatus status
    ) {
        return persistenceService.page(page, size, customerId, status);
    }

    @Override
    public PageResponse<ChatMessageResponse> pageMessages(
            long id,
            long page,
            long size,
            Long customerId
    ) {
        return persistenceService.pageMessages(id, page, size, customerId);
    }

    @Override
    public ChatExchangeResponse sendMessage(Long id, SendMessageRequest request, Long customerId) {
        persistenceService.validateCanSend(id, customerId);
        ResolvedChatModel model = defaultChatModelProvider.get();
        PreparedChatExchange prepared = persistenceService.prepareExchange(
                id,
                customerId,
                request.content(),
                model
        );

        ChatCompletionResult completion;
        try {
            completion = modelClient.complete(model, prepared.history());
        } catch (RuntimeException exception) {
            markGenerationFailed(prepared.assistantMessageId(), id, exception);
            throw new ChatGenerationException();
        }

        ChatMessage assistantMessage = persistenceService.completeAssistant(
                prepared.assistantMessageId(),
                completion
        );
        return new ChatExchangeResponse(
                ChatMessageResponse.from(prepared.userMessage()),
                ChatMessageResponse.from(assistantMessage)
        );
    }

    private void markGenerationFailed(Long messageId, Long conversationId, RuntimeException cause) {
        try {
            persistenceService.failAssistant(messageId, MODEL_CALL_FAILED);
        } catch (RuntimeException persistenceException) {
            cause.addSuppressed(persistenceException);
        }
        log.warn(
                "AI response generation failed for conversation {} ({})",
                conversationId,
                cause.getClass().getSimpleName()
        );
    }
}
