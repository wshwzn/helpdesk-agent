package com.anying.helpdesk.knowledge.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "创建知识库请求")
public record CreateKnowledgeBaseRequest(
        @Schema(description = "知识库名称", example = "产品帮助中心")
        @NotBlank @Size(max = 100) String name,
        @Schema(description = "知识库用途说明", example = "保存产品使用说明和常见问题")
        @Size(max = 500) String description
) {
}
