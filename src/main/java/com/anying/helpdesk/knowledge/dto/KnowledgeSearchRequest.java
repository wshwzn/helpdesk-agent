package com.anying.helpdesk.knowledge.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "知识库混合检索请求")
public record KnowledgeSearchRequest(
        @Schema(description = "待检索的自然语言问题", example = "如何配置模型接口？")
        @NotBlank @Size(max = 2000) String query,
        @Schema(description = "最终返回数量；省略时为 5", example = "5")
        @Min(1) @Max(20) Integer topK
) {
}
