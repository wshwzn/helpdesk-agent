package com.anying.helpdesk.knowledge.evaluation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "RAG 检索评测运行详情")
public record RagEvaluationRunResponse(
        @Schema(description = "运行汇总指标") RagEvaluationRunSummaryResponse summary,
        @Schema(description = "逐题评测结果") List<RagEvaluationCaseResultResponse> results
) {

    public RagEvaluationRunResponse {
        results = results == null ? List.of() : List.copyOf(results);
    }
}
