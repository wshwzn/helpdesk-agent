package com.anying.helpdesk.ticket.controller;

import com.anying.helpdesk.common.response.ApiResponse;
import com.anying.helpdesk.common.response.PageResponse;
import com.anying.helpdesk.config.OpenApiConfig;
import com.anying.helpdesk.security.CurrentUserProvider;
import com.anying.helpdesk.ticket.domain.TicketStatus;
import com.anying.helpdesk.ticket.dto.CancelTicketRequest;
import com.anying.helpdesk.ticket.dto.CreateTicketRequest;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
@Tag(name = "客户工单", description = "CUSTOMER 创建、查询和取消自己的工单")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class TicketController {

    private final TicketService ticketService;
    private final CurrentUserProvider currentUserProvider;

    @PostMapping
    @Operation(summary = "创建工单")
    public ResponseEntity<ApiResponse<TicketResponse>> create(@Valid @RequestBody CreateTicketRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(ticketService.create(request, currentUserProvider.userId())));
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询工单详情", description = "客户只能查询自己创建的工单")
    public ApiResponse<TicketResponse> getById(
            @Parameter(description = "工单数据库主键", example = "1")
            @PathVariable @Min(1) Long id
    ) {
        return ApiResponse.ok(ticketService.getById(id, currentUserProvider.userId()));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "取消工单", description = "只允许取消待处理或处理中的本人工单")
    public ApiResponse<TicketResponse> cancel(
            @Parameter(description = "工单数据库主键", example = "1")
            @PathVariable @Min(1) Long id,
            @Valid @RequestBody CancelTicketRequest request
    ) {
        return ApiResponse.ok(ticketService.cancel(id, request, currentUserProvider.userId()));
    }

    @GetMapping
    @Operation(summary = "分页查询我的工单")
    public ApiResponse<PageResponse<TicketResponse>> page(
            @Parameter(description = "页码，从 1 开始", example = "1")
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @Parameter(description = "每页数量，最大 100", example = "20")
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) long size,
            @Parameter(description = "可选的工单状态筛选")
            @RequestParam(required = false) TicketStatus status
    ) {
        return ApiResponse.ok(ticketService.page(page, size, currentUserProvider.userId(), status));
    }
}
