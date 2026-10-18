package com.anying.helpdesk.knowledge.index;

import com.anying.helpdesk.knowledge.dto.KnowledgeDocumentMetadata;
import java.time.Instant;

public record IndexedKnowledgeChunk(
        Long knowledgeBaseId,
        Long documentId,
        int chunkSequence,
        int startOffset,
        int endOffset,
        String content,
        float[] contentVector,
        String originalName,
        Long embeddingModelConfigId,
        String embeddingModelName,
        Instant indexedAt,
        KnowledgeDocumentMetadata metadata
) {
}
