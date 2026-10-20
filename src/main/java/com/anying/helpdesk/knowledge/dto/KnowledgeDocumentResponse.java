package com.anying.helpdesk.knowledge.dto;

import com.anying.helpdesk.knowledge.domain.KnowledgeDocument;
import com.anying.helpdesk.knowledge.domain.KnowledgeDocumentStatus;
import com.anying.helpdesk.knowledge.domain.KnowledgePublicationStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "知识库文档元数据")
public record KnowledgeDocumentResponse(
        @Schema(description = "文档主键", example = "1") Long id,
        @Schema(description = "所属知识库主键", example = "1") Long knowledgeBaseId,
        @Schema(description = "上传时的原始文件名") String originalName,
        @Schema(description = "文件 MIME 类型") String contentType,
        @Schema(description = "小写文件扩展名") String fileExtension,
        @Schema(description = "文件大小，单位字节") Long sizeBytes,
        @Schema(description = "文件内容 SHA-256") String sha256,
        @Schema(description = "文档处理状态") KnowledgeDocumentStatus status,
        @Schema(description = "已生成的分块数量") Integer chunkCount,
        @Schema(description = "失败时的脱敏错误码") String errorCode,
        @Schema(description = "乐观锁版本") Integer version,
        @Schema(description = "稳定来源标识；未配置时为空") String sourceKey,
        @Schema(description = "业务版本，与乐观锁版本无关") String businessVersion,
        @Schema(description = "适用范围代码，ALL 表示通用") String applicabilityScope,
        @Schema(description = "生效时间，Asia/Shanghai，含边界") LocalDateTime validFrom,
        @Schema(description = "失效时间，Asia/Shanghai，不含边界") LocalDateTime validUntil,
        @Schema(description = "业务发布状态") KnowledgePublicationStatus publicationStatus,
        @Schema(description = "创建时间") LocalDateTime createdAt,
        @Schema(description = "更新时间") LocalDateTime updatedAt,
        @Schema(description = "本候选明确接替的文档；普通上传为空") Long predecessorDocumentId,
        @Schema(description = "已审核的接替文档；尚未交接也会有值") Long successorDocumentId,
        @Schema(description = "审核确定的实际启用时间，含边界") LocalDateTime activatedAt,
        @Schema(description = "被新版接替的时间，不含边界") LocalDateTime supersededAt,
        @Schema(description = "外部来源对象 ID；非空时文件和业务元数据只能在来源端维护") Long sourceItemId
) {

    /**
     * 将文档实体转换为隐藏存储桶和对象键的接口响应。
     */
    public static KnowledgeDocumentResponse from(KnowledgeDocument document) {
        return new KnowledgeDocumentResponse(
                document.getId(),
                document.getKnowledgeBaseId(),
                document.getOriginalName(),
                document.getContentType(),
                document.getFileExtension(),
                document.getSizeBytes(),
                document.getSha256(),
                document.getStatus(),
                document.getChunkCount(),
                document.getErrorCode(),
                document.getVersion(),
                document.getSourceKey(),
                document.getBusinessVersion(),
                document.getApplicabilityScope(),
                document.getValidFrom(),
                document.getValidUntil(),
                document.getPublicationStatus(),
                document.getCreatedAt(),
                document.getUpdatedAt(),
                document.getPredecessorDocumentId(),
                document.getSuccessorDocumentId(),
                document.getActivatedAt(),
                document.getSupersededAt(),
                document.getSourceItemId()
        );
    }
}
