package com.anying.helpdesk.knowledge.evaluation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "评测用例期望命中的知识文档")
public record RagExpectedDocumentResponse(
        @Schema(description = "文档主键", example = "7") Long id,
        @Schema(description = "原始文件名", example = "账号安全说明.pdf") String originalName
) {
}
