package com.anying.helpdesk.knowledge.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

@Schema(description = "更新知识库检索参数请求")
public record UpdateKnowledgeRetrievalSettingsRequest(
        @Schema(description = "BM25 和 KNN 各自送入 RRF 的候选数量上限", example = "50")
        @NotNull @Min(1) @Max(200) Integer candidateLimit,
        @Schema(description = "KNN 原始向量相似度下限，始终生效", example = "0.650")
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") @Digits(integer = 1, fraction = 3) BigDecimal vectorMinSimilarity,
        @Schema(description = "RRF 融合后最终返回的数量上限", example = "5")
        @NotNull @Min(1) @Max(50) Integer resultLimit
) {
}
