package com.anying.helpdesk.knowledge.evaluation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description = "一次 RAG 检索评测运行的汇总指标")
public record RagEvaluationRunSummaryResponse(
        @Schema(description = "运行主键", example = "5") Long id,
        @Schema(description = "知识库主键", example = "3") Long knowledgeBaseId,
        @Schema(description = "单路候选数量快照") int candidateLimit,
        @Schema(description = "向量相似度下限快照") BigDecimal vectorMinSimilarity,
        @Schema(description = "最终结果数量快照，即指标中的 K") int resultLimit,
        @Schema(description = "评测用例总数") int totalCases,
        @Schema(description = "至少命中一个期望文档的用例数") int hitCases,
        @Schema(description = "Hit@K，取值 0 至 1") BigDecimal hitRate,
        @Schema(description = "平均 Recall@K，取值 0 至 1") BigDecimal recallAtK,
        @Schema(description = "平均倒数排名 MRR，取值 0 至 1") BigDecimal meanReciprocalRank,
        @Schema(description = "每道问题的平均检索耗时，单位毫秒") long averageLatencyMs,
        @Schema(description = "运行时间") LocalDateTime createdAt
) {
}
