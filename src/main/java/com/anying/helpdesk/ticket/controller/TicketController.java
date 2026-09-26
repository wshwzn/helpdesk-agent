package com.anying.helpdesk.ticket.controller;

import com.anying.helpdesk.common.response.ApiResponse;
import com.anying.helpdesk.common.response.PageResponse;
import com.anying.helpdesk.security.CurrentUserProvider;
import com.anying.helpdesk.ticket.domain.TicketStatus;
import com.anying.helpdesk.ticket.dto.CancelTicketRequest;
import com.anying.helpdesk.ticket.dto.CreateTicketRequest;
import com.anying.helpdesk.ticket.dto.TicketResponse;
import com.anying.helpdesk.ticket.service.TicketService;
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
public class TicketController {

    private final TicketService ticketService;
    private final CurrentUserProvider currentUserProvider;

    @PostMapping
    public ResponseEntity<ApiResponse<TicketResponse>> create(@Valid @RequestBody CreateTicketRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(ticketService.create(request, currentUserProvider.userId())));
    }

    @GetMapping("/{id}")
    public ApiResponse<TicketResponse> getById(@PathVariable @Min(1) Long id) {
        return ApiResponse.ok(ticketService.getById(id, currentUserProvider.userId()));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<TicketResponse> cancel(
            @PathVariable @Min(1) Long id,
            @Valid @RequestBody CancelTicketRequest request
    ) {
        return ApiResponse.ok(ticketService.cancel(id, request, currentUserProvider.userId()));
    }

    @GetMapping
    public ApiResponse<PageResponse<TicketResponse>> page(
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) long size,
            @RequestParam(required = false) TicketStatus status
    ) {
        return ApiResponse.ok(ticketService.page(page, size, currentUserProvider.userId(), status));
    }
}
