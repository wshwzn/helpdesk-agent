package com.anying.helpdesk.conversation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "AI 回答引用的知识库片段")
public record KnowledgeCitationResponse(
        @Schema(description = "来源知识库名称", example = "产品帮助中心") String knowledgeBaseName,
        @Schema(description = "来源文件名称", example = "账号安全说明.pdf") String sourceName,
        @Schema(description = "Chunk 在文档中的序号，从 1 开始", example = "8") int chunkSequence,
        @Schema(description = "完整的 Chunk 文本内容") String content
) {
}
