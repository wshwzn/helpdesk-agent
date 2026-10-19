package com.anying.helpdesk.knowledge.answer.trace;

import com.anying.helpdesk.common.response.ApiResponse;
import com.anying.helpdesk.common.response.PageResponse;
import com.anying.helpdesk.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 沿用 /api/admin/** 的管理员鉴权，客户即使知道运行编号也不能读取草案。 */
@RestController
@RequestMapping("/api/admin/knowledge-answer-runs")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RequiredArgsConstructor
@Validated
public class AdminKnowledgeAnswerRunController {
    private final KnowledgeAnswerRunStore store;

    /** 查询运行摘要，按会话或运行状态筛选，不在列表加载敏感正文。 */
    @GetMapping
    @Operation(summary = "分页查询知识问答运行摘要")
    public ApiResponse<PageResponse<KnowledgeAnswerRunStore.Summary>> page(
            @RequestParam(defaultValue = "1") @Min(1) @Max(1000000) long page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size,
            @RequestParam(required = false) @Min(1) Long conversationId,
            @RequestParam(required = false) KnowledgeAnswerRunSnapshot.Status status) {
        return ApiResponse.ok(store.page(page, size, conversationId, status));
    }

    /** 仅向管理员展示候选、草案和阶段结果，记录缺失时明确返回 404。 */
    @GetMapping("/{runId}")
    @Operation(summary = "查询知识问答运行诊断", description = "包含尚未通过核验的草案，仅供管理员诊断")
    public ResponseEntity<ApiResponse<KnowledgeAnswerRunSnapshot>> get(@PathVariable UUID runId) {
        return store.find(runId.toString()).map(value -> ResponseEntity.ok(ApiResponse.ok(value)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error("KNOWLEDGE_RUN_NOT_FOUND", "Knowledge diagnostic record not found", null)));
    }
}
