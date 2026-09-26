package com.anying.helpdesk.conversation.service;

import com.anying.helpdesk.conversation.domain.ChatMessage;

import java.util.List;

public record PreparedChatExchange(
        ChatMessage userMessage,
        Long assistantMessageId,
        List<ChatMessage> history
) {
}
