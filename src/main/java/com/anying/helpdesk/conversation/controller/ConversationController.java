package com.anying.helpdesk.conversation.controller;

import com.anying.helpdesk.common.response.ApiResponse;
import com.anying.helpdesk.common.response.PageResponse;
import com.anying.helpdesk.config.OpenApiConfig;
import com.anying.helpdesk.conversation.domain.ConversationStatus;
import com.anying.helpdesk.conversation.dto.ChatExchangeResponse;
import com.anying.helpdesk.conversation.dto.ChatMessageResponse;
import com.anying.helpdesk.conversation.dto.ChatStreamEvent;
import com.anying.helpdesk.conversation.dto.ConversationResponse;
import com.anying.helpdesk.conversation.dto.CreateConversationRequest;
import com.anying.helpdesk.conversation.dto.SendMessageRequest;
import com.anying.helpdesk.conversation.service.ConversationService;
import com.anying.helpdesk.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@Validated
@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
@Tag(name = "客户对话", description = "CUSTOMER 创建对话、查询历史并向 AI 发送消息")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class ConversationController {

    private final ConversationService conversationService;
    private final CurrentUserProvider currentUserProvider;

    @PostMapping
    @Operation(summary = "创建对话")
    public ResponseEntity<ApiResponse<ConversationResponse>> create(
            @Valid @RequestBody CreateConversationRequest request
    ) {
        ConversationResponse response = conversationService.create(request, currentUserProvider.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @GetMapping
    @Operation(summary = "分页查询我的对话")
    public ApiResponse<PageResponse<ConversationResponse>> page(
            @Parameter(description = "页码，从 1 开始", example = "1")
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @Parameter(description = "每页数量，最大 100", example = "20")
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) long size,
            @Parameter(description = "可选的对话状态筛选")
            @RequestParam(required = false) ConversationStatus status
    ) {
        return ApiResponse.ok(conversationService.page(
                page,
                size,
                currentUserProvider.userId(),
                status
        ));
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询对话详情", description = "客户只能查询自己的对话")
    public ApiResponse<ConversationResponse> getById(
            @Parameter(description = "对话主键", example = "1")
            @PathVariable @Min(1) Long id
    ) {
        return ApiResponse.ok(conversationService.getById(id, currentUserProvider.userId()));
    }

    @GetMapping("/{id}/messages")
    @Operation(summary = "分页查询对话消息", description = "消息按照创建顺序升序返回")
    public ApiResponse<PageResponse<ChatMessageResponse>> pageMessages(
            @Parameter(description = "对话主键", example = "1")
            @PathVariable @Min(1) Long id,
            @Parameter(description = "页码，从 1 开始", example = "1")
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @Parameter(description = "每页数量，最大 100", example = "50")
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) long size
    ) {
        return ApiResponse.ok(conversationService.pageMessages(
                id,
                page,
                size,
                currentUserProvider.userId()
        ));
    }

    @PostMapping("/{id}/messages")
    @Operation(
            summary = "发送消息并获取 AI 回答",
            description = "当前为非流式响应；模型失败时客户消息和失败的 AI 消息仍会保留"
    )
    public ApiResponse<ChatExchangeResponse> sendMessage(
            @Parameter(description = "对话主键", example = "1")
            @PathVariable @Min(1) Long id,
            @Valid @RequestBody SendMessageRequest request
    ) {
        return ApiResponse.ok(conversationService.sendMessage(
                id,
                request,
                currentUserProvider.userId()
        ));
    }

    /**
     * 发送客户消息，并通过 SSE 持续推送模型生成的回答。
     */
    @PostMapping(value = "/{id}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
            summary = "发送消息并流式获取 AI 回答",
            description = "依次返回 start、delta、done 事件；模型失败时返回脱敏的 error 事件"
    )
    public Flux<ServerSentEvent<ChatStreamEvent>> streamMessage(
            @Parameter(description = "对话主键", example = "1")
            @PathVariable @Min(1) Long id,
            @Valid @RequestBody SendMessageRequest request
    ) {
        return conversationService.streamMessage(id, request, currentUserProvider.userId())
                .map(event -> ServerSentEvent.builder(event)
                        .event(event.type())
                        .build());
    }
}
