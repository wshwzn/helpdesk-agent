package com.anying.helpdesk.knowledge.dto;

import com.anying.helpdesk.knowledge.domain.KnowledgeDocument;
import com.anying.helpdesk.knowledge.domain.KnowledgeDocumentStatus;
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
        @Schema(description = "创建时间") LocalDateTime createdAt,
        @Schema(description = "更新时间") LocalDateTime updatedAt
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
                document.getCreatedAt(),
                document.getUpdatedAt()
        );
    }
}
