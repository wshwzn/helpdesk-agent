package com.anying.helpdesk.knowledge.dto;

import com.anying.helpdesk.knowledge.processing.DocumentChunk;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "文档分块预览")
public record DocumentChunkResponse(
        @Schema(description = "从 1 开始的分块序号", example = "1") int sequence,
        @Schema(description = "在规范化文本中的起始偏移量", example = "0") int startOffset,
        @Schema(description = "在规范化文本中的结束偏移量", example = "986") int endOffset,
        @Schema(description = "分块字符数", example = "986") int characterCount,
        @Schema(description = "分块正文") String content
) {

    /**
     * 将内部文档分块转换为管理端预览响应。
     */
    public static DocumentChunkResponse from(DocumentChunk chunk) {
        return new DocumentChunkResponse(
                chunk.sequence(),
                chunk.startOffset(),
                chunk.endOffset(),
                chunk.content().length(),
                chunk.content()
        );
    }
}
