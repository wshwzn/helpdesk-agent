package com.anying.helpdesk.conversation.service;

import com.anying.helpdesk.knowledge.answer.KnowledgeAnswerInput;
import com.anying.helpdesk.conversation.domain.ChatMessage;

/** 本轮可信问答上下文；旧消息与工具记录只供历史查询，不作为新回答事实重放。 */
public record PreparedChatExchange(
        ChatMessage userMessage,
        Long assistantMessageId,
        String knowledgeRunId,
        KnowledgeAnswerInput knowledgeInput
) { }
