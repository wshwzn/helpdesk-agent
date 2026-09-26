package com.anying.helpdesk.ticket.controller;

import com.anying.helpdesk.common.response.ApiResponse;
import com.anying.helpdesk.common.response.PageResponse;
import com.anying.helpdesk.security.CurrentUserProvider;
import com.anying.helpdesk.ticket.domain.TicketStatus;
import com.anying.helpdesk.ticket.dto.ResolveTicketRequest;
import com.anying.helpdesk.ticket.dto.TicketResponse;
import com.anying.helpdesk.ticket.service.TicketService;
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
public class SupportTicketController {

    private final TicketService ticketService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping
    public ApiResponse<PageResponse<TicketResponse>> page(
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) long size,
            @RequestParam(required = false) TicketStatus status,
            @RequestParam(required = false) Long assigneeId
    ) {
        return ApiResponse.ok(ticketService.supportPage(page, size, status, assigneeId));
    }

    @PostMapping("/{id}/claim")
    public ApiResponse<TicketResponse> claim(@PathVariable @Min(1) Long id) {
        return ApiResponse.ok(ticketService.claim(id, currentUserProvider.userId()));
    }

    @PostMapping("/{id}/resolve")
    public ApiResponse<TicketResponse> resolve(
            @PathVariable @Min(1) Long id,
            @Valid @RequestBody ResolveTicketRequest request
    ) {
        return ApiResponse.ok(ticketService.resolve(id, request, currentUserProvider.userId()));
    }
}
