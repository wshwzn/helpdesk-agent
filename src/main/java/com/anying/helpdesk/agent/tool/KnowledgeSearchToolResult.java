package com.anying.helpdesk.agent.tool;

import com.anying.helpdesk.knowledge.dto.KnowledgeSearchResponse;

import java.util.List;

/**
 * 描述知识检索是否可用、是否命中，以及模型可用于回答的资料片段。
 */
public record KnowledgeSearchToolResult(
        String status,
        String message,
        String knowledgeBase,
        String query,
        List<KnowledgeSearchToolChunk> chunks
) {

    /**
     * 返回尚未配置默认知识库的可解释结果，避免模型调用直接失败。
     */
    public static KnowledgeSearchToolResult notConfigured(String query) {
        return new KnowledgeSearchToolResult(
                "NOT_CONFIGURED",
                "系统尚未配置默认知识库，无法查询可靠资料。",
                null,
                query,
                List.of()
        );
    }

    /**
     * 返回模型未提供有效查询文本时的结果。
     */
    public static KnowledgeSearchToolResult invalidQuery() {
        return new KnowledgeSearchToolResult(
                "INVALID_QUERY",
                "没有提供有效的知识检索问题。",
                null,
                "",
                List.of()
        );
    }

    /**
     * 将双路检索响应压缩为适合模型消费的知识资料。
     */
    public static KnowledgeSearchToolResult from(
            String knowledgeBase,
            KnowledgeSearchResponse response
    ) {
        List<KnowledgeSearchToolChunk> chunks = response.results().stream()
                .map(KnowledgeSearchToolChunk::from)
                .toList();
        return new KnowledgeSearchToolResult(
                chunks.isEmpty() ? "NO_MATCH" : "FOUND",
                chunks.isEmpty()
                        ? "没有找到当前已发布、生效且适用的可靠资料；缺少适用条件时请询问用户，不要编造答案。"
                        : "请仅依据以下资料回答，注明文件与业务版本。多个有效来源不等于结论一致，不能按版本标签或上传时间擅自裁决。",
                knowledgeBase,
                response.query(),
                chunks
        );
    }
}
