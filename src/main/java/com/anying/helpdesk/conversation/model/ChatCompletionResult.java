package com.anying.helpdesk.conversation.model;

import com.anying.helpdesk.knowledge.answer.KnowledgeAnswerReport;

public record ChatCompletionResult(
        String content,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        KnowledgeAnswerReport knowledgeAnswer
) {
    /** 普通 Agent 回复不包含知识核验报告，保留既有调用方式。 */
    public ChatCompletionResult(String content, Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        this(content, promptTokens, completionTokens, totalTokens, null);
    }
}
