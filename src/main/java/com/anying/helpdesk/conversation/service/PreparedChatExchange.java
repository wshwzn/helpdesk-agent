package com.anying.helpdesk.conversation.service;

import com.anying.helpdesk.conversation.domain.ChatMessage;
import com.anying.helpdesk.conversation.model.ConversationModelHistory;

public record PreparedChatExchange(
        ChatMessage userMessage,
        Long assistantMessageId,
        ConversationModelHistory history
) {
}
