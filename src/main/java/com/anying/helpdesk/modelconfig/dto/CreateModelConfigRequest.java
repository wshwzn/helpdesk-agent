package com.anying.helpdesk.modelconfig.dto;

import com.anying.helpdesk.modelconfig.domain.ModelProviderType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateModelConfigRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull ModelProviderType providerType,
        @NotBlank @Size(max = 500) String baseUrl,
        @NotBlank @Size(max = 100) String modelName,
        @NotBlank @Size(max = 500) String apiKey,
        @NotNull Boolean supportsStreaming,
        @NotNull Boolean supportsTools,
        @NotNull Boolean enabled
) {
}
