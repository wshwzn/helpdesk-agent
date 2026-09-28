package com.anying.helpdesk.knowledge.processing;

public record DocumentChunk(
        int sequence,
        int startOffset,
        int endOffset,
        String content
) {
}
