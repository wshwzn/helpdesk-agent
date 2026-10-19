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
     * 使用后端默认知识库检索问题，模型只提供查询文本和用户明确说明的适用范围。
     */
    @Tool(
            name = "search_knowledge_base",
            description = "查询企业制度、产品资料和操作流程。只返回检索证据，不代表答案已经完成语义核验；不能用于执行业务操作。"
    )
    public KnowledgeSearchToolResult searchKnowledgeBase(
            @ToolParam(description = "根据员工问题整理出的完整、独立的自然语言检索句") String query,
            @ToolParam(required = false, description = "用户明确说明的适用范围代码，如 CN_SH；未知时省略，仅检索 ALL 通用资料，不能猜测范围。范围不是访问权限。") String applicabilityScope,
            ToolContext toolContext
    ) {
        AgentExecutionContext.require(toolContext);
        if (query == null || query.isBlank() || query.length() > MAX_QUERY_LENGTH) {
            return KnowledgeSearchToolResult.invalidQuery();
        }
        if (applicabilityScope != null && !applicabilityScope.matches("[A-Z][A-Z0-9_-]{0,39}")) {
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
                new KnowledgeSearchRequest(normalizedQuery, applicabilityScope)
        );
        return KnowledgeSearchToolResult.from(selected.name(), response);
    }
}
