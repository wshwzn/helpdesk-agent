package com.anying.helpdesk.agent.tool;

import com.anying.helpdesk.agent.AgentExecutionContext;
import com.anying.helpdesk.knowledge.dto.KnowledgeBaseResponse;
import com.anying.helpdesk.knowledge.dto.KnowledgeSearchRequest;
import com.anying.helpdesk.knowledge.dto.KnowledgeSearchResponse;
import com.anying.helpdesk.knowledge.service.KnowledgeBaseService;
import com.anying.helpdesk.knowledge.service.KnowledgeRetrievalService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class KnowledgeSearchTools {

    private static final int MAX_QUERY_LENGTH = 2000;

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeRetrievalService knowledgeRetrievalService;

    /**
     * 使用后端配置的默认知识库检索客户问题，模型只能提供查询文本。
     */
    @Tool(
            name = "search_knowledge_base",
            description = "查询产品说明、使用方法、故障排查和服务规则等通用知识。回答此类事实问题前使用；不要用于查询客户个人工单。"
    )
    public KnowledgeSearchToolResult searchKnowledgeBase(
            @ToolParam(description = "根据客户问题整理出的完整、独立的自然语言检索句") String query,
            ToolContext toolContext
    ) {
        AgentExecutionContext.require(toolContext);
        if (query == null || query.isBlank() || query.length() > MAX_QUERY_LENGTH) {
            return KnowledgeSearchToolResult.invalidQuery();
        }
        String normalizedQuery = query.trim();
        Optional<KnowledgeBaseResponse> knowledgeBase = knowledgeBaseService.findDefault();
        if (knowledgeBase.isEmpty()) {
            return KnowledgeSearchToolResult.notConfigured(normalizedQuery);
        }
        KnowledgeBaseResponse selected = knowledgeBase.get();
        KnowledgeSearchResponse response = knowledgeRetrievalService.search(
                selected.id(),
                new KnowledgeSearchRequest(normalizedQuery)
        );
        return KnowledgeSearchToolResult.from(selected.name(), response);
    }
}
