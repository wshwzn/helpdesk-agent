package com.anying.helpdesk.knowledge.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "知识库混合检索请求")
public record KnowledgeSearchRequest(
        @Schema(description = "待检索的自然语言问题", example = "如何配置模型接口？")
        @NotBlank @Size(max = 2000) String query
) {
}
