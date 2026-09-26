package com.anying.helpdesk.modelconfig.dto;

import com.anying.helpdesk.modelconfig.domain.AiModelConfig;
import com.anying.helpdesk.modelconfig.domain.ModelProtocol;
import com.anying.helpdesk.modelconfig.domain.ModelVendor;

import java.time.LocalDateTime;

public record ModelConfigResponse(
        Long id,
        String name,
        ModelProtocol protocol,
        ModelVendor vendor,
        String baseUrl,
        String modelName,
        String apiKeyMasked,
        Boolean supportsStreaming,
        Boolean supportsTools,
        Boolean enabled,
        Boolean isDefault,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static ModelConfigResponse from(AiModelConfig config) {
        return new ModelConfigResponse(
                config.getId(),
                config.getName(),
                config.getProtocol(),
                config.getVendor(),
                config.getBaseUrl(),
                config.getModelName(),
                config.getApiKeyHint(),
                config.getSupportsStreaming(),
                config.getSupportsTools(),
                config.getEnabled(),
                config.getIsDefault(),
                config.getCreatedAt(),
                config.getUpdatedAt()
        );
    }
}
