package com.anying.helpdesk.knowledge.index;

public record KnowledgeChunkSearchHit(
        String chunkId,
        Long documentId,
        int chunkSequence,
        String originalName,
        String content,
        double score
) {
}
