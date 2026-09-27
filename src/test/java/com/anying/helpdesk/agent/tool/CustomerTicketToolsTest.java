package com.anying.helpdesk.agent.tool;

import com.anying.helpdesk.agent.AgentExecutionContext;
import com.anying.helpdesk.common.response.PageResponse;
import com.anying.helpdesk.ticket.domain.TicketStatus;
import com.anying.helpdesk.ticket.domain.TicketPriority;
import com.anying.helpdesk.ticket.dto.TicketResponse;
import com.anying.helpdesk.ticket.service.TicketService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CustomerTicketToolsTest {

    private final TicketService ticketService = mock(TicketService.class);
    private final CustomerTicketTools ticketTools = new CustomerTicketTools(ticketService);

    /**
     * 验证提供给大模型的工具参数不包含可信身份字段。
     */
    @Test
    @DisplayName("工具参数结构不应暴露客户身份和对话身份")
    void toolSchemaDoesNotExposeTrustedIdentity() {
        ToolCallback[] callbacks = toolCallbacks();

        assertThat(callbacks).extracting(callback -> callback.getToolDefinition().name())
                .containsExactlyInAnyOrder("list_my_tickets", "get_my_ticket");
        assertThat(callbacks).allSatisfy(callback -> assertThat(callback.getToolDefinition().inputSchema())
                .doesNotContain("customerId", "conversationId", "toolContext"));
    }

    /**
     * 验证列表工具只使用后端上下文中的客户 ID，并限制单次查询数量。
     */
    @Test
    @DisplayName("工单列表工具应使用服务端注入的客户身份")
    void listToolUsesServerInjectedCustomerId() {
        when(ticketService.page(1, 20, 42L, TicketStatus.PROCESSING))
                .thenReturn(new PageResponse<>(1, 20, 0, 0, List.of()));
        ToolCallback callback = callback("list_my_tickets");
        ToolContext toolContext = new ToolContext(
                new AgentExecutionContext(42L, 9L, 100L).toToolContext()
        );

        String result = callback.call("{\"status\":\"PROCESSING\"}", toolContext);

        assertThat(result).contains("\"records\":[]");
        verify(ticketService).page(1, 20, 42L, TicketStatus.PROCESSING);
    }

    /**
     * 验证详情工具将服务端客户 ID 交给 Service 执行最终归属校验。
     */
    @Test
    @DisplayName("工单详情工具应把可信客户身份传给业务服务")
    void detailToolUsesServerInjectedCustomerId() {
        when(ticketService.getById(91L, 73L)).thenReturn(ticketResponse(91L, 73L, 88L));
        ToolCallback callback = callback("get_my_ticket");
        ToolContext toolContext = new ToolContext(
                new AgentExecutionContext(73L, 12L, 101L).toToolContext()
        );

        String result = callback.call("{\"ticketId\":91}", toolContext);

        assertThat(result).contains("HD91").doesNotContain("creatorId", "assigneeId", "73", "88");
        verify(ticketService).getById(91L, 73L);
    }

    /**
     * 验证缺少后端身份上下文时工具会拒绝访问工单服务。
     */
    @Test
    @DisplayName("缺少可信身份上下文时应拒绝执行工具")
    void missingExecutionContextIsRejected() {
        assertThatThrownBy(() -> ticketTools.getMyTicket(91L, new ToolContext(Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("agent execution context is invalid");
        verifyNoInteractions(ticketService);
    }

    /**
     * 根据名称取得用于测试的 Spring AI 工具回调。
     */
    private ToolCallback callback(String name) {
        return Arrays.stream(toolCallbacks())
                .filter(callback -> callback.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    /**
     * 使用真实注解扫描过程生成工单工具回调。
     */
    private ToolCallback[] toolCallbacks() {
        return MethodToolCallbackProvider.builder()
                .toolObjects(ticketTools)
                .build()
                .getToolCallbacks();
    }

    /**
     * 构造包含内部身份字段的工单响应，验证工具输出会主动移除这些字段。
     */
    private TicketResponse ticketResponse(Long id, Long creatorId, Long assigneeId) {
        return new TicketResponse(
                id,
                "HD" + id,
                "登录异常",
                "无法登录客户中心",
                TicketStatus.PROCESSING,
                TicketPriority.HIGH,
                creatorId,
                assigneeId,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
