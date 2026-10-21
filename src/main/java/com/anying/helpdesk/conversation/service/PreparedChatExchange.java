package com.anying.helpdesk.conversation.service;

import com.anying.helpdesk.knowledge.answer.KnowledgeAnswerInput;

import com.anying.helpdesk.conversation.domain.ChatMessage;
import com.anying.helpdesk.conversation.model.ConversationModelHistory;

public record PreparedChatExchange(
        ChatMessage userMessage,
        Long assistantMessageId,
        ConversationModelHistory history,
        String knowledgeRunId,
        KnowledgeAnswerInput knowledgeInput
) {
    /** 兼容没有澄清来源的调用，知识问题仍来自本轮客户消息。 */
    public PreparedChatExchange(ChatMessage userMessage, Long assistantMessageId,
                                ConversationModelHistory history, String knowledgeRunId) {
        this(userMessage, assistantMessageId, history, knowledgeRunId,
                new KnowledgeAnswerInput(userMessage.getContent(),
                        userMessage.getApplicabilityScope(), null, null, null, userMessage.getApplicabilityScopes()));
    }
}
