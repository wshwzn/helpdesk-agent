package com.anying.helpdesk.knowledge.dto;

import com.anying.helpdesk.knowledge.domain.KnowledgeBase;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description = "知识库信息")
public record KnowledgeBaseResponse(
        @Schema(description = "知识库主键", example = "1") Long id,
        @Schema(description = "知识库名称", example = "产品帮助中心") String name,
        @Schema(description = "知识库说明") String description,
        @Schema(description = "单路候选数量上限", example = "50") int retrievalCandidateLimit,
        @Schema(description = "始终生效的向量相似度下限", example = "0.650") BigDecimal vectorMinSimilarity,
        @Schema(description = "最终结果数量上限", example = "5") int retrievalResultLimit,
        @Schema(description = "是否为客户对话使用的默认知识库") boolean isDefault,
        @Schema(description = "已上传文档数量", example = "3") long documentCount,
        @Schema(description = "乐观锁版本", example = "0") Integer version,
        @Schema(description = "创建时间") LocalDateTime createdAt,
        @Schema(description = "更新时间") LocalDateTime updatedAt
) {

    /**
     * 将知识库实体转换为不暴露管理员身份的接口响应。
     */
    public static KnowledgeBaseResponse from(KnowledgeBase knowledgeBase, long documentCount) {
        return new KnowledgeBaseResponse(
                knowledgeBase.getId(),
                knowledgeBase.getName(),
                knowledgeBase.getDescription(),
                knowledgeBase.getRetrievalCandidateLimit(),
                knowledgeBase.getVectorMinSimilarity(),
                knowledgeBase.getRetrievalResultLimit(),
                Boolean.TRUE.equals(knowledgeBase.getIsDefault()),
                documentCount,
                knowledgeBase.getVersion(),
                knowledgeBase.getCreatedAt(),
                knowledgeBase.getUpdatedAt()
        );
    }
}
