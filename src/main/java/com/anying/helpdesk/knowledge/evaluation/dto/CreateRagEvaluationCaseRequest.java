package com.anying.helpdesk.knowledge.evaluation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = "创建 RAG 检索评测用例")
public record CreateRagEvaluationCaseRequest(
        @Schema(description = "客户可能提出的测试问题", example = "连续输错密码会怎么样？")
        @NotBlank @Size(max = 2000) String question,
        @Schema(description = "一个或多个期望命中的文档主键")
        @NotEmpty @Size(max = 20) List<@Min(1) Long> expectedDocumentIds
) {
}
