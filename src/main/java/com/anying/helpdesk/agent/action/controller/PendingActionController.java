package com.anying.helpdesk.agent.action.controller;

import com.anying.helpdesk.agent.action.domain.PendingActionStatus;
import com.anying.helpdesk.agent.action.dto.PendingActionExecutionResponse;
import com.anying.helpdesk.agent.action.dto.PendingActionResponse;
import com.anying.helpdesk.agent.action.dto.PendingActionVersionRequest;
import com.anying.helpdesk.agent.action.dto.UpdatePendingActionRequest;
import com.anying.helpdesk.agent.action.service.PendingActionService;
import com.anying.helpdesk.common.response.ApiResponse;
import com.anying.helpdesk.config.OpenApiConfig;
import com.anying.helpdesk.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Validated
@RequestMapping("/api/agent/actions")
@RequiredArgsConstructor
@Tag(name = "Agent 待确认操作", description = "CUSTOMER 查询、修改、确认或取消 AI 生成的业务操作")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class PendingActionController {

    private final PendingActionService pendingActionService;
    private final CurrentUserProvider currentUserProvider;

    /**
     * 查询当前客户指定对话中的操作，用于页面刷新后恢复卡片。
     */
    @GetMapping
    @Operation(summary = "查询对话中的待确认操作")
    public ApiResponse<List<PendingActionResponse>> list(
            @Parameter(description = "对话主键", example = "1")
            @RequestParam @Min(1) Long conversationId,
            @Parameter(description = "可选的操作状态筛选")
            @RequestParam(required = false) PendingActionStatus status
    ) {
        return ApiResponse.ok(pendingActionService.listByConversation(
                conversationId,
                currentUserProvider.userId(),
                status
        ));
    }

    /**
     * 修改 AI 生成的工单标题和描述草稿。
     */
    @PatchMapping("/{id}")
    @Operation(summary = "修改工单草稿", description = "优先级由 Agent 建议，客户只修改标题和描述")
    public ApiResponse<PendingActionResponse> update(
            @PathVariable @Min(1) Long id,
            @Valid @RequestBody UpdatePendingActionRequest request
    ) {
        return ApiResponse.ok(pendingActionService.updateDraft(
                id,
                request,
                currentUserProvider.userId()
        ));
    }

    /**
     * 确认操作并创建真实工单，重复请求不会重复建单。
     */
    @PostMapping("/{id}/confirm")
    @Operation(summary = "确认并创建工单")
    public ApiResponse<PendingActionExecutionResponse> confirm(
            @PathVariable @Min(1) Long id,
            @Valid @RequestBody PendingActionVersionRequest request
    ) {
        return ApiResponse.ok(pendingActionService.confirm(
                id,
                request,
                currentUserProvider.userId()
        ));
    }

    /**
     * 取消尚未执行的工单草稿。
     */
    @PostMapping("/{id}/cancel")
    @Operation(summary = "取消工单草稿")
    public ApiResponse<PendingActionResponse> cancel(
            @PathVariable @Min(1) Long id,
            @Valid @RequestBody PendingActionVersionRequest request
    ) {
        return ApiResponse.ok(pendingActionService.cancel(
                id,
                request,
                currentUserProvider.userId()
        ));
    }
}
