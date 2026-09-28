package com.anying.helpdesk.modelconfig.factory;

import org.springframework.ai.embedding.EmbeddingModel;

public record ResolvedEmbeddingModel(
        Long configId,
        String modelName,
        int dimensions,
        EmbeddingModel embeddingModel
) {
}
