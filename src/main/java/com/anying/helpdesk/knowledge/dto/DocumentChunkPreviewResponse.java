package com.anying.helpdesk.knowledge.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "文档解析与分块预览结果")
public record DocumentChunkPreviewResponse(
        @Schema(description = "文档主键", example = "1") Long documentId,
        @Schema(description = "原始文件名") String originalName,
        @Schema(description = "解析器识别的 MIME 类型") String detectedContentType,
        @Schema(description = "规范化后的正文字符数") int extractedCharacters,
        @Schema(description = "配置的最大分块字符数") int chunkSize,
        @Schema(description = "配置的相邻分块重叠字符数") int chunkOverlap,
        @Schema(description = "分块总数") long totalChunks,
        @Schema(description = "当前页码，从 1 开始") long page,
        @Schema(description = "每页分块数") long size,
        @Schema(description = "总页数") long pages,
        @Schema(description = "当前页分块") List<DocumentChunkResponse> chunks
) {
}
