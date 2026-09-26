package com.anying.helpdesk.conversation.model;

public record ChatCompletionResult(
        String content,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens
) {
}
