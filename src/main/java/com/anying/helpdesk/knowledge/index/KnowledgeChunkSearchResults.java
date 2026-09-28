package com.anying.helpdesk.knowledge.index;

import java.util.List;

public record KnowledgeChunkSearchResults(
        List<KnowledgeChunkSearchHit> bm25Hits,
        List<KnowledgeChunkSearchHit> vectorHits
) {
}
