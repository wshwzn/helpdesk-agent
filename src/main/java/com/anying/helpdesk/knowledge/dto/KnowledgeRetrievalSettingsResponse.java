package com.anying.helpdesk.knowledge.dto;

import com.anying.helpdesk.knowledge.domain.KnowledgeBase;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(description = "知识库当前检索参数")
public record KnowledgeRetrievalSettingsResponse(
        @Schema(description = "知识库主键", example = "3") Long knowledgeBaseId,
        @Schema(description = "单路候选数量上限", example = "50") int candidateLimit,
        @Schema(description = "始终生效的向量相似度下限", example = "0.650") BigDecimal vectorMinSimilarity,
        @Schema(description = "最终结果数量上限", example = "5") int resultLimit,
        @Schema(description = "乐观锁版本", example = "1") Integer version
) {

    /**
     * 从知识库实体提取当前生效的检索参数。
     */
    public static KnowledgeRetrievalSettingsResponse from(KnowledgeBase knowledgeBase) {
        return new KnowledgeRetrievalSettingsResponse(
                knowledgeBase.getId(),
                knowledgeBase.getRetrievalCandidateLimit(),
                knowledgeBase.getVectorMinSimilarity(),
                knowledgeBase.getRetrievalResultLimit(),
                knowledgeBase.getVersion()
        );
    }
}
