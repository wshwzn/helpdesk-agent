package com.anying.helpdesk.modelconfig.dto;

import com.anying.helpdesk.modelconfig.domain.AiModelConfig;
import com.anying.helpdesk.modelconfig.domain.ModelProtocol;
import com.anying.helpdesk.modelconfig.domain.ModelVendor;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "模型配置详情，不包含 API Key 明文或密文")
public record ModelConfigResponse(
        @Schema(description = "配置主键", example = "1") Long id,
        @Schema(description = "配置显示名称") String name,
        @Schema(description = "调用协议") ModelProtocol protocol,
        @Schema(description = "模型厂商") ModelVendor vendor,
        @Schema(description = "最终使用的 SDK 基础地址") String baseUrl,
        @Schema(description = "厂商侧模型名称") String modelName,
        @Schema(description = "API Key 脱敏尾号", example = "****1234") String apiKeyMasked,
        @Schema(description = "是否支持流式输出") Boolean supportsStreaming,
        @Schema(description = "是否支持工具调用") Boolean supportsTools,
        @Schema(description = "是否启用") Boolean enabled,
        @Schema(description = "是否为默认模型") Boolean isDefault,
        @Schema(description = "创建时间") LocalDateTime createdAt,
        @Schema(description = "最后更新时间") LocalDateTime updatedAt
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
