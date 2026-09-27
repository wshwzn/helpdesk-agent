package com.anying.helpdesk.agent.tool;

import com.anying.helpdesk.agent.AgentExecutionContext;
import com.anying.helpdesk.agent.action.dto.PendingActionResponse;
import com.anying.helpdesk.agent.action.service.PendingActionService;
import com.anying.helpdesk.ticket.domain.TicketPriority;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CustomerTicketActionTools {

    private final PendingActionService pendingActionService;

    /**
     * 生成需要客户确认的工单草稿，不在模型工具调用阶段直接创建真实工单。
     */
    @Tool(
            name = "prepare_create_ticket",
            description = "当客户明确要求人工处理，或问题无法继续自动解决时，生成待客户确认的工单草稿。此工具不会直接创建工单。"
    )
    public PrepareTicketToolResult prepareCreateTicket(
            @ToolParam(description = "简洁准确的工单标题，最多 100 字") String title,
            @ToolParam(description = "根据对话整理的问题描述，最多 2000 字") String description,
            @ToolParam(
                    description = "建议优先级：LOW 表示咨询建议，MEDIUM 表示一般功能异常，HIGH 表示无法登录、支付失败、安全或主要功能不可用",
                    required = false
            )
            TicketPriority priority,
            @ToolParam(description = "选择该优先级的简短理由，最多 500 字", required = false)
            String priorityReason,
            ToolContext toolContext
    ) {
        AgentExecutionContext context = AgentExecutionContext.require(toolContext);
        PendingActionResponse action = pendingActionService.prepareCreateTicket(
                context,
                title,
                description,
                priority,
                priorityReason
        );
        return PrepareTicketToolResult.from(action);
    }
}
