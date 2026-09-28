package com.anying.helpdesk.knowledge.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

@Schema(description = "知识库双路召回及 RRF 融合结果")
public record KnowledgeSearchResponse(
        @Schema(description = "知识库主键", example = "3") Long knowledgeBaseId,
        @Schema(description = "规范化后的查询文本") String query,
        @Schema(description = "单路候选数量上限", example = "50") int candidateLimit,
        @Schema(description = "本次检索使用的向量相似度下限", example = "0.650") BigDecimal vectorMinSimilarity,
        @Schema(description = "最终结果数量上限", example = "5") int resultLimit,
        @Schema(description = "BM25 实际候选数量", example = "11") int bm25CandidateCount,
        @Schema(description = "KNN 实际候选数量", example = "11") int vectorCandidateCount,
        List<KnowledgeSearchResultResponse> results
) {
}
