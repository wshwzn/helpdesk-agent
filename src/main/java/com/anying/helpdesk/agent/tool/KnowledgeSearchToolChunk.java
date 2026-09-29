package com.anying.helpdesk.agent.tool;

import com.anying.helpdesk.knowledge.dto.KnowledgeSearchResultResponse;

/**
 * 提供给模型的精简知识片段，保留回答引用所需的来源信息。
 */
public record KnowledgeSearchToolChunk(
        int rank,
        String source,
        int chunkSequence,
        String content
) {

    /**
     * 将管理端检索结果转换为模型需要的来源和正文。
     */
    public static KnowledgeSearchToolChunk from(KnowledgeSearchResultResponse result) {
        return new KnowledgeSearchToolChunk(
                result.rank(),
                result.originalName(),
                result.chunkSequence(),
                result.content()
        );
    }
}
