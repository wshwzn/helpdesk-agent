package com.anying.helpdesk.knowledge.evaluation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "RAG 检索评测用例")
public record RagEvaluationCaseResponse(
        @Schema(description = "用例主键", example = "1") Long id,
        @Schema(description = "所属知识库主键", example = "3") Long knowledgeBaseId,
        @Schema(description = "测试问题") String question,
        @Schema(description = "一个或多个期望命中的文档") List<RagExpectedDocumentResponse> expectedDocuments,
        @Schema(description = "乐观锁版本", example = "0") Integer version,
        @Schema(description = "创建时间") LocalDateTime createdAt,
        @Schema(description = "最后更新时间") LocalDateTime updatedAt
) {

    public RagEvaluationCaseResponse {
        expectedDocuments = expectedDocuments == null ? List.of() : List.copyOf(expectedDocuments);
    }
}
