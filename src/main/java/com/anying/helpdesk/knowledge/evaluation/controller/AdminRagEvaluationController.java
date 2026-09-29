package com.anying.helpdesk.knowledge.evaluation.controller;

import com.anying.helpdesk.common.response.ApiResponse;
import com.anying.helpdesk.config.OpenApiConfig;
import com.anying.helpdesk.knowledge.evaluation.dto.CreateRagEvaluationCaseRequest;
import com.anying.helpdesk.knowledge.evaluation.dto.RagEvaluationCaseResponse;
import com.anying.helpdesk.knowledge.evaluation.dto.RagEvaluationRunResponse;
import com.anying.helpdesk.knowledge.evaluation.dto.RagEvaluationRunSummaryResponse;
import com.anying.helpdesk.knowledge.evaluation.dto.UpdateRagEvaluationCaseRequest;
import com.anying.helpdesk.knowledge.evaluation.service.RagEvaluationService;
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
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/admin/knowledge-bases/{knowledgeBaseId}/evaluation")
@RequiredArgsConstructor
@Tag(name = "RAG 检索评测", description = "ADMIN 维护评测集并比较知识库检索效果")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class AdminRagEvaluationController {

    private final RagEvaluationService evaluationService;
    private final CurrentUserProvider currentUserProvider;

    /**
     * 创建一条包含多个期望文档的评测用例。
     */
    @PostMapping("/cases")
    @Operation(summary = "创建 RAG 评测用例")
    public ResponseEntity<ApiResponse<RagEvaluationCaseResponse>> createCase(
            @Parameter(description = "知识库主键", example = "1")
            @PathVariable @Min(1) Long knowledgeBaseId,
            @Valid @RequestBody CreateRagEvaluationCaseRequest request
    ) {
        RagEvaluationCaseResponse response = evaluationService.createCase(
                knowledgeBaseId,
                request,
                currentUserProvider.userId()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    /**
     * 查询知识库当前维护的全部评测用例。
     */
    @GetMapping("/cases")
    @Operation(summary = "查询 RAG 评测用例")
    public ApiResponse<List<RagEvaluationCaseResponse>> listCases(
            @Parameter(description = "知识库主键", example = "1")
            @PathVariable @Min(1) Long knowledgeBaseId
    ) {
        return ApiResponse.ok(evaluationService.listCases(knowledgeBaseId));
    }

    /**
     * 修改评测问题及其期望文档列表。
     */
    @PutMapping("/cases/{caseId}")
    @Operation(summary = "修改 RAG 评测用例")
    public ApiResponse<RagEvaluationCaseResponse> updateCase(
            @PathVariable @Min(1) Long knowledgeBaseId,
            @PathVariable @Min(1) Long caseId,
            @Valid @RequestBody UpdateRagEvaluationCaseRequest request
    ) {
        return ApiResponse.ok(evaluationService.updateCase(knowledgeBaseId, caseId, request));
    }

    /**
     * 删除当前评测用例，历史运行结果保持不变。
     */
    @DeleteMapping("/cases/{caseId}")
    @Operation(summary = "删除 RAG 评测用例")
    public ResponseEntity<Void> deleteCase(
            @PathVariable @Min(1) Long knowledgeBaseId,
            @PathVariable @Min(1) Long caseId
    ) {
        evaluationService.deleteCase(knowledgeBaseId, caseId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 使用当前检索参数批量运行全部评测用例并保存结果。
     */
    @PostMapping("/runs")
    @Operation(summary = "运行 RAG 检索评测", description = "单次最多运行 100 条用例")
    public ResponseEntity<ApiResponse<RagEvaluationRunResponse>> run(
            @PathVariable @Min(1) Long knowledgeBaseId
    ) {
        RagEvaluationRunResponse response = evaluationService.run(
                knowledgeBaseId,
                currentUserProvider.userId()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    /**
     * 查询最近的评测运行汇总，用于对比不同检索参数。
     */
    @GetMapping("/runs")
    @Operation(summary = "查询 RAG 评测运行历史")
    public ApiResponse<List<RagEvaluationRunSummaryResponse>> listRuns(
            @PathVariable @Min(1) Long knowledgeBaseId,
            @RequestParam(defaultValue = "10") @Min(1) @Max(20) int limit
    ) {
        return ApiResponse.ok(evaluationService.listRuns(knowledgeBaseId, limit));
    }

    /**
     * 查询一次运行的逐题命中情况与实际召回 Chunk。
     */
    @GetMapping("/runs/{runId}")
    @Operation(summary = "查询 RAG 评测运行详情")
    public ApiResponse<RagEvaluationRunResponse> getRun(
            @PathVariable @Min(1) Long knowledgeBaseId,
            @PathVariable @Min(1) Long runId
    ) {
        return ApiResponse.ok(evaluationService.getRun(knowledgeBaseId, runId));
    }
}
