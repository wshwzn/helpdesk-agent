package com.anying.helpdesk.conversation.model;

public record ChatStreamChunk(
        String content,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens
) {
}
