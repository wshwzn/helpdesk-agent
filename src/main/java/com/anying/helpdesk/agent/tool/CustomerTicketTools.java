package com.anying.helpdesk.agent.tool;

import com.anying.helpdesk.agent.AgentExecutionContext;
import com.anying.helpdesk.common.response.PageResponse;
import com.anying.helpdesk.ticket.domain.TicketStatus;
import com.anying.helpdesk.ticket.dto.TicketResponse;
import com.anying.helpdesk.ticket.service.TicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CustomerTicketTools {

    private static final long TOOL_PAGE_SIZE = 20;

    private final TicketService ticketService;

    /**
     * 查询当前登录客户最近的工单，客户身份只能从服务端工具上下文取得。
     */
    @Tool(
            name = "list_my_tickets",
            description = "查询当前客户自己的最近工单。客户询问工单列表、处理进度或有哪些工单时使用；可按状态筛选。"
    )
    public PageResponse<CustomerTicketToolResult> listMyTickets(
            @ToolParam(description = "可选的工单状态：PENDING、PROCESSING、RESOLVED、CANCELLED", required = false)
            TicketStatus status,
            ToolContext toolContext
    ) {
        AgentExecutionContext context = AgentExecutionContext.require(toolContext);
        PageResponse<TicketResponse> page = ticketService.page(
                1,
                TOOL_PAGE_SIZE,
                context.customerId(),
                status
        );
        return new PageResponse<>(
                page.page(),
                page.size(),
                page.total(),
                page.pages(),
                page.records().stream().map(CustomerTicketToolResult::from).toList()
        );
    }

    /**
     * 查询当前登录客户的一张工单，Service 层会再次校验工单归属。
     */
    @Tool(
            name = "get_my_ticket",
            description = "按照客户可见的工单号查询当前客户自己的工单详情。需要了解某张工单的描述、状态或处理结果时使用。"
    )
    public CustomerTicketToolResult getMyTicket(
            @ToolParam(description = "完整工单号，例如 HD20260927012345AB12CD34") String ticketNo,
            ToolContext toolContext
    ) {
        AgentExecutionContext context = AgentExecutionContext.require(toolContext);
        return CustomerTicketToolResult.from(ticketService.getByTicketNo(ticketNo, context.customerId()));
    }
}
