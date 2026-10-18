package com.anying.helpdesk.agent.tool;

import com.anying.helpdesk.agent.AgentExecutionContext;
import com.anying.helpdesk.knowledge.dto.KnowledgeBaseResponse;
import com.anying.helpdesk.knowledge.dto.KnowledgeSearchRequest;
import com.anying.helpdesk.knowledge.dto.KnowledgeSearchResponse;
import com.anying.helpdesk.knowledge.dto.KnowledgeSearchResultResponse;
import com.anying.helpdesk.knowledge.service.KnowledgeBaseService;
import com.anying.helpdesk.knowledge.service.KnowledgeRetrievalService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeSearchToolsTest {

    private final KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
    private final KnowledgeRetrievalService retrievalService = mock(KnowledgeRetrievalService.class);
    private final KnowledgeSearchTools tools = new KnowledgeSearchTools(
            knowledgeBaseService,
            retrievalService
    );

    /**
     * 验证模型只能填写查询文本和业务范围，知识库选择和客户身份由服务端控制。
     */
    @Test
    @DisplayName("知识检索工具只向模型暴露查询文本和业务范围")
    void toolSchemaOnlyExposesQuery() {
        ToolCallback callback = toolCallback();

        assertThat(callback.getToolDefinition().name()).isEqualTo("search_knowledge_base");
        assertThat(callback.getToolDefinition().inputSchema())
                .contains("query", "applicabilityScope")
                .doesNotContain("knowledgeBaseId", "customerId", "toolContext");
    }

    /**
     * 验证工具使用默认知识库检索，并只返回回答所需的来源和 Chunk 正文。
     */
    @Test
    @DisplayName("知识检索工具使用后端默认知识库")
    void searchesServerSelectedDefaultKnowledgeBase() {
        KnowledgeBaseResponse knowledgeBase = knowledgeBase(3L, "产品帮助中心");
        when(knowledgeBaseService.findDefault()).thenReturn(Optional.of(knowledgeBase));
        when(retrievalService.search(org.mockito.ArgumentMatchers.eq(3L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(searchResponse());

        KnowledgeSearchToolResult result = tools.searchKnowledgeBase(
                "  如何重置密码？  ",
                "CN_SH",
                toolContext()
        );

        assertThat(result.status()).isEqualTo("FOUND");
        assertThat(result.knowledgeBase()).isEqualTo("产品帮助中心");
        assertThat(result.chunks()).singleElement().satisfies(chunk -> {
            assertThat(chunk.source()).isEqualTo("guide.md");
            assertThat(chunk.content()).isEqualTo("进入设置页面后可以重置密码。");
        });
        ArgumentCaptor<KnowledgeSearchRequest> requestCaptor = ArgumentCaptor.forClass(
                KnowledgeSearchRequest.class
        );
        verify(retrievalService).search(org.mockito.ArgumentMatchers.eq(3L), requestCaptor.capture());
        assertThat(requestCaptor.getValue().query()).isEqualTo("如何重置密码？");
        assertThat(requestCaptor.getValue().applicabilityScope()).isEqualTo("CN_SH");
        assertThat(result.chunks().getFirst().documentId()).isEqualTo(8L);
    }

    /**
     * 验证尚未配置默认知识库时返回可解释状态而不是执行任意检索。
     */
    @Test
    @DisplayName("未配置默认知识库时工具返回明确状态")
    void reportsMissingDefaultKnowledgeBase() {
        when(knowledgeBaseService.findDefault()).thenReturn(Optional.empty());

        KnowledgeSearchToolResult result = tools.searchKnowledgeBase("如何登录？", null, toolContext());

        assertThat(result.status()).isEqualTo("NOT_CONFIGURED");
        assertThat(result.chunks()).isEmpty();
        verifyNoInteractions(retrievalService);
    }

    /**
     * 使用真实注解扫描生成知识检索工具定义。
     */
    private ToolCallback toolCallback() {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build()
                .getToolCallbacks()[0];
    }

    /**
     * 创建包含可信 Agent 执行上下文的工具上下文。
     */
    private ToolContext toolContext() {
        return new ToolContext(new AgentExecutionContext(7L, 11L, 13L).toToolContext());
    }

    /**
     * 构造测试使用的默认知识库响应。
     */
    private KnowledgeBaseResponse knowledgeBase(Long id, String name) {
        LocalDateTime now = LocalDateTime.now();
        return new KnowledgeBaseResponse(
                id,
                name,
                "测试知识库",
                50,
                BigDecimal.ZERO,
                5,
                true,
                1,
                0,
                now,
                now
        );
    }

    /**
     * 构造包含一个命中 Chunk 的双路检索响应。
     */
    private KnowledgeSearchResponse searchResponse() {
        return new KnowledgeSearchResponse(
                3L,
                "如何重置密码？",
                50,
                BigDecimal.ZERO,
                5,
                1,
                1,
                List.of(new KnowledgeSearchResultResponse(
                        1,
                        8L,
                        2,
                        "guide.md",
                        "进入设置页面后可以重置密码。",
                        0.032D,
                        1,
                        4.2D,
                        1,
                        0.91D
                ))
        );
    }
}
