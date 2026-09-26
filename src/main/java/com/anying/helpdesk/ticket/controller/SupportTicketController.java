package com.anying.helpdesk.ticket.controller;

import com.anying.helpdesk.common.response.ApiResponse;
import com.anying.helpdesk.common.response.PageResponse;
import com.anying.helpdesk.config.OpenApiConfig;
import com.anying.helpdesk.security.CurrentUserProvider;
import com.anying.helpdesk.ticket.domain.TicketStatus;
import com.anying.helpdesk.ticket.dto.ResolveTicketRequest;
import com.anying.helpdesk.ticket.dto.TicketResponse;
import com.anying.helpdesk.ticket.service.TicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/support/tickets")
@RequiredArgsConstructor
@Tag(name = "客服工单", description = "SUPPORT 查询、领取和解决工单")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class SupportTicketController {

    private final TicketService ticketService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping
    @Operation(summary = "分页查询客服工单队列")
    public ApiResponse<PageResponse<TicketResponse>> page(
            @Parameter(description = "页码，从 1 开始", example = "1")
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @Parameter(description = "每页数量，最大 100", example = "20")
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) long size,
            @Parameter(description = "可选的工单状态筛选")
            @RequestParam(required = false) TicketStatus status,
            @Parameter(description = "可选的客服用户 ID", example = "2")
            @RequestParam(required = false) Long assigneeId
    ) {
        return ApiResponse.ok(ticketService.supportPage(page, size, status, assigneeId));
    }

    @PostMapping("/{id}/claim")
    @Operation(summary = "领取待处理工单")
    public ApiResponse<TicketResponse> claim(
            @Parameter(description = "工单数据库主键", example = "1")
            @PathVariable @Min(1) Long id
    ) {
        return ApiResponse.ok(ticketService.claim(id, currentUserProvider.userId()));
    }

    @PostMapping("/{id}/resolve")
    @Operation(summary = "解决已领取工单", description = "只有当前领取该工单的客服可以提交处理结果")
    public ApiResponse<TicketResponse> resolve(
            @Parameter(description = "工单数据库主键", example = "1")
            @PathVariable @Min(1) Long id,
            @Valid @RequestBody ResolveTicketRequest request
    ) {
        return ApiResponse.ok(ticketService.resolve(id, request, currentUserProvider.userId()));
    }
}
