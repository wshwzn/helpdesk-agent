package com.anying.helpdesk.modelconfig.dto;

import com.anying.helpdesk.modelconfig.domain.ModelVendor;
import com.anying.helpdesk.modelconfig.domain.ModelType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "更新模型配置请求")
public record UpdateModelConfigRequest(
        @Schema(description = "配置显示名称", example = "DeepSeek 主模型")
        @NotBlank @Size(max = 100) String name,
        @Schema(description = "模型用途；省略时保留当前类型", example = "CHAT")
        ModelType modelType,
        @Schema(description = "模型厂商；CHAT 可使用预设，EMBEDDING 必须为 CUSTOM", example = "DEEPSEEK")
        @NotNull ModelVendor vendor,
        @Schema(description = "仅 CUSTOM 厂商需要的 SDK 基础地址", example = "https://proxy.example.com/v1")
        @Size(max = 500) String baseUrl,
        @Schema(description = "厂商侧模型名称", example = "deepseek-chat")
        @NotBlank @Size(max = 100) String modelName,
        @Schema(description = "新 API Key；省略时保留原密钥", example = "sk-example", format = "password", accessMode = Schema.AccessMode.WRITE_ONLY)
        @Size(min = 1, max = 500) String apiKey,
        @Schema(description = "是否支持流式输出", example = "true")
        @NotNull Boolean supportsStreaming,
        @Schema(description = "是否支持工具调用", example = "true")
        @NotNull Boolean supportsTools,
        @Schema(description = "Embedding 向量维度；更新向量模型时省略则保留，CHAT 模型必须省略", example = "1024")
        @Min(1) @Max(4096) Integer embeddingDimensions
) {
}
