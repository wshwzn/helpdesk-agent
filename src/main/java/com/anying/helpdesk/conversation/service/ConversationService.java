package com.anying.helpdesk.conversation.service;

import com.anying.helpdesk.common.response.PageResponse;
import com.anying.helpdesk.conversation.domain.ConversationStatus;
import com.anying.helpdesk.conversation.dto.ChatExchangeResponse;
import com.anying.helpdesk.conversation.dto.ChatMessageResponse;
import com.anying.helpdesk.conversation.dto.ChatStreamEvent;
import com.anying.helpdesk.conversation.dto.ConversationResponse;
import com.anying.helpdesk.conversation.dto.CreateConversationRequest;
import com.anying.helpdesk.conversation.dto.SendMessageRequest;
import reactor.core.publisher.Flux;

public interface ConversationService {

    ConversationResponse create(CreateConversationRequest request, Long customerId);

    ConversationResponse getById(Long id, Long customerId);

    /**
     * 删除客户自己的对话，业务实现保留历史数据并转为归档状态。
     */
    void delete(Long id, Long customerId);

    PageResponse<ConversationResponse> page(
            long page,
            long size,
            Long customerId,
            ConversationStatus status
    );

    PageResponse<ChatMessageResponse> pageMessages(long id, long page, long size, Long customerId);

    ChatExchangeResponse sendMessage(Long id, SendMessageRequest request, Long customerId);

    /**
     * 发送客户消息并以事件流形式持续返回 AI 回答。
     */
    Flux<ChatStreamEvent> streamMessage(Long id, SendMessageRequest request, Long customerId);
}
