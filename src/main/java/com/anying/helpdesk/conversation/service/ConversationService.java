package com.anying.helpdesk.conversation.service;

import com.anying.helpdesk.common.response.PageResponse;
import com.anying.helpdesk.conversation.domain.ConversationStatus;
import com.anying.helpdesk.conversation.dto.ChatExchangeResponse;
import com.anying.helpdesk.conversation.dto.ChatMessageResponse;
import com.anying.helpdesk.conversation.dto.ConversationResponse;
import com.anying.helpdesk.conversation.dto.CreateConversationRequest;
import com.anying.helpdesk.conversation.dto.SendMessageRequest;

public interface ConversationService {

    ConversationResponse create(CreateConversationRequest request, Long customerId);

    ConversationResponse getById(Long id, Long customerId);

    PageResponse<ConversationResponse> page(
            long page,
            long size,
            Long customerId,
            ConversationStatus status
    );

    PageResponse<ChatMessageResponse> pageMessages(long id, long page, long size, Long customerId);

    ChatExchangeResponse sendMessage(Long id, SendMessageRequest request, Long customerId);
}
