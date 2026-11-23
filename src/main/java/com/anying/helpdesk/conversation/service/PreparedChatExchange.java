package com.anying.helpdesk.conversation.service;

import com.anying.helpdesk.knowledge.answer.KnowledgeAnswerInput;
import com.anying.helpdesk.conversation.domain.ChatMessage;

/** 本轮可信问答上下文；历史文字可辅助理解追问，工具记录和历史回答不作为新回答事实重放。 */
public record PreparedChatExchange(
        ChatMessage userMessage,
        Long assistantMessageId,
        String knowledgeRunId,
        KnowledgeAnswerInput knowledgeInput
) { }
