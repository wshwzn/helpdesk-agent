package com.anying.helpdesk.knowledge.processing;

public record ParsedDocument(
        String text,
        String detectedContentType
) {
}
