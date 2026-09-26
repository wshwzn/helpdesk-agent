package com.anying.helpdesk.modelconfig.dto;

import com.anying.helpdesk.modelconfig.domain.ModelVendor;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "创建模型配置请求")
public record CreateModelConfigRequest(
        @Schema(description = "配置显示名称", example = "DeepSeek 主模型")
        @NotBlank @Size(max = 100) String name,
        @Schema(description = "模型厂商；内置厂商使用后端预设地址", example = "DEEPSEEK")
        @NotNull ModelVendor vendor,
        @Schema(description = "CUSTOM 厂商必填的 SDK 基础地址，不能包含 /chat/completions", example = "https://proxy.example.com/v1")
        @Size(max = 500) String baseUrl,
        @Schema(description = "厂商侧模型名称", example = "deepseek-chat")
        @NotBlank @Size(max = 100) String modelName,
        @Schema(description = "模型 API Key，将加密存储", example = "sk-example", format = "password", accessMode = Schema.AccessMode.WRITE_ONLY)
        @NotBlank @Size(max = 500) String apiKey,
        @Schema(description = "是否支持流式输出", example = "true")
        @NotNull Boolean supportsStreaming,
        @Schema(description = "是否支持工具调用", example = "true")
        @NotNull Boolean supportsTools,
        @Schema(description = "创建后是否立即启用", example = "true")
        @NotNull Boolean enabled
) {
}
