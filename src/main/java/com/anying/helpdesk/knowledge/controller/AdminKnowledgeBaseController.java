package com.anying.helpdesk.knowledge.controller;

import com.anying.helpdesk.knowledge.answer.KnowledgeApplicabilityPolicy;

import com.anying.helpdesk.knowledge.answer.KnowledgeApplicabilityService;

import com.anying.helpdesk.common.response.ApiResponse;
import com.anying.helpdesk.common.response.PageResponse;
import com.anying.helpdesk.config.OpenApiConfig;
import com.anying.helpdesk.knowledge.dto.CreateKnowledgeBaseRequest;
import com.anying.helpdesk.knowledge.dto.DocumentChunkPreviewResponse;
import com.anying.helpdesk.knowledge.dto.KnowledgeBaseResponse;
import com.anying.helpdesk.knowledge.dto.KnowledgeDocumentResponse;
import com.anying.helpdesk.knowledge.dto.KnowledgeSearchRequest;
import com.anying.helpdesk.knowledge.dto.KnowledgeSearchResponse;
import com.anying.helpdesk.knowledge.dto.KnowledgeRetrievalSettingsResponse;
import com.anying.helpdesk.knowledge.dto.UpdateKnowledgeRetrievalSettingsRequest;
import com.anying.helpdesk.knowledge.dto.UpdateKnowledgeDocumentMetadataRequest;
import com.anying.helpdesk.knowledge.dto.KnowledgeDocumentVersionRequest;
import com.anying.helpdesk.knowledge.dto.UploadKnowledgeRevisionRequest;
import com.anying.helpdesk.knowledge.dto.ApproveKnowledgeRevisionRequest;
import com.anying.helpdesk.knowledge.dto.KnowledgeRevisionUploadResponse;
import com.anying.helpdesk.knowledge.dto.KnowledgeRevisionApprovalResponse;
import com.anying.helpdesk.knowledge.service.KnowledgeDocumentRevisionService;
import com.anying.helpdesk.knowledge.service.KnowledgeDocumentPublicationService;
import com.anying.helpdesk.knowledge.service.KnowledgeBaseService;
import com.anying.helpdesk.knowledge.service.KnowledgeDocumentContent;
import com.anying.helpdesk.knowledge.service.KnowledgeDocumentIndexingService;
import com.anying.helpdesk.knowledge.service.KnowledgeDocumentPreviewService;
import com.anying.helpdesk.knowledge.service.KnowledgeDocumentService;
import com.anying.helpdesk.knowledge.service.KnowledgeRetrievalService;
import com.anying.helpdesk.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Validated
@RestController
@RequestMapping("/api/admin/knowledge-bases")
@RequiredArgsConstructor
@Tag(name = "知识库管理", description = "ADMIN 管理知识库与原始文档")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class AdminKnowledgeBaseController {
    private final KnowledgeDocumentRevisionService revisionService;

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeDocumentService knowledgeDocumentService;
    private final KnowledgeDocumentPreviewService knowledgeDocumentPreviewService;
    private final KnowledgeDocumentIndexingService knowledgeDocumentIndexingService;
    private final KnowledgeRetrievalService knowledgeRetrievalService;
    private final KnowledgeDocumentPublicationService publicationService;
    private final CurrentUserProvider currentUserProvider;
    private final KnowledgeApplicabilityService applicabilityService;

    /** 查询知识库的主题和适用条件配置，空主题列表表示未启用。 */
    @GetMapping("/{id}/clarification-policy")
    public ApiResponse<KnowledgeApplicabilityPolicy> clarificationPolicy(
            @PathVariable @Min(1) Long id) {
        return ApiResponse.ok(applicabilityService.get(id));
    }

    /** 按修订号替换适用条件配置，空主题列表可关闭此功能。 */
    @PutMapping("/{id}/clarification-policy")
    public ApiResponse<KnowledgeApplicabilityPolicy> updateClarificationPolicy(
            @PathVariable @Min(1) Long id,
            @Valid @RequestBody KnowledgeApplicabilityPolicy policy) {
        return ApiResponse.ok(applicabilityService.update(id, policy));
    }


    /**
     * 创建一个新的知识库。
     */
    @PostMapping
    @Operation(summary = "创建知识库")
    public ResponseEntity<ApiResponse<KnowledgeBaseResponse>> create(
            @Valid @RequestBody CreateKnowledgeBaseRequest request
    ) {
        KnowledgeBaseResponse response = knowledgeBaseService.create(request, currentUserProvider.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    /**
     * 查询全部知识库及其文档数量。
     */
    @GetMapping
    @Operation(summary = "查询知识库列表")
    public ApiResponse<List<KnowledgeBaseResponse>> list() {
        return ApiResponse.ok(knowledgeBaseService.list());
    }

    /**
     * 查询一个知识库的基本信息。
     */
    @GetMapping("/{id}")
    @Operation(summary = "查询知识库详情")
    public ApiResponse<KnowledgeBaseResponse> getById(
            @Parameter(description = "知识库主键", example = "1")
            @PathVariable @Min(1) Long id
    ) {
        return ApiResponse.ok(knowledgeBaseService.getById(id));
    }

    /**
     * 将一个知识库设为客户对话 RAG 使用的默认知识库。
     */
    @PostMapping("/{id}/default")
    @Operation(summary = "设为默认知识库", description = "客户对话中的知识检索工具只查询当前默认知识库")
    public ApiResponse<KnowledgeBaseResponse> setDefault(
            @Parameter(description = "知识库主键", example = "1")
            @PathVariable @Min(1) Long id
    ) {
        return ApiResponse.ok(knowledgeBaseService.setDefault(id));
    }

    /**
     * 更新一个知识库的候选窗口、向量阈值和最终结果上限。
     */
    @PutMapping("/{id}/retrieval-settings")
    @Operation(summary = "更新知识库检索参数", description = "参数在下次检索时立即生效，不需要重新索引文档")
    public ApiResponse<KnowledgeRetrievalSettingsResponse> updateRetrievalSettings(
            @Parameter(description = "知识库主键", example = "1")
            @PathVariable @Min(1) Long id,
            @Valid @RequestBody UpdateKnowledgeRetrievalSettingsRequest request
    ) {
        return ApiResponse.ok(knowledgeBaseService.updateRetrievalSettings(id, request));
    }

    /**
     * 删除一个没有文档的知识库。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除空知识库", description = "知识库包含文档时必须先删除文档")
    public ResponseEntity<Void> delete(
            @Parameter(description = "知识库主键", example = "1")
            @PathVariable @Min(1) Long id
    ) {
        knowledgeBaseService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 上传原始文档并保存待解析元数据。
     */
    @PostMapping(path = "/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传知识库文档", description = "支持 txt、md、pdf、docx，单文件最大 20 MB")
    public ResponseEntity<ApiResponse<KnowledgeDocumentResponse>> uploadDocument(
            @Parameter(description = "知识库主键", example = "1")
            @PathVariable @Min(1) Long id,
            @Parameter(description = "待上传文档")
            @RequestPart("file") MultipartFile file
    ) {
        KnowledgeDocumentResponse response = knowledgeDocumentService.upload(
                id,
                file,
                currentUserProvider.userId()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    /** 针对已有版本上传独立候选并自动索引，相同内容和元数据重复提交返回已有结果。 */
    @PostMapping(path = "/{knowledgeBaseId}/documents/{documentId}/revisions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传文档新版", description = "file 为文件，metadata 为 JSON；新候选返回 202，无变化或已有候选返回 200")
    public ResponseEntity<ApiResponse<KnowledgeRevisionUploadResponse>> uploadRevision(
            @PathVariable @Min(1) Long knowledgeBaseId, @PathVariable @Min(1) Long documentId,
            @RequestPart("file") MultipartFile file,
            @Valid @RequestPart("metadata") UploadKnowledgeRevisionRequest request) {
        KnowledgeRevisionUploadResponse response = revisionService.upload(knowledgeBaseId, documentId, file,
                request, currentUserProvider.userId());
        return ResponseEntity.status("CREATED".equals(response.outcome()) ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(ApiResponse.ok(response));
    }

    /** 审核候选后原子安排双方时间边界，已到生效时间的新版立即接替旧版。 */
    @PostMapping("/{knowledgeBaseId}/documents/{documentId}/approve-replacement")
    @Operation(summary = "审核新版并接替旧版", description = "documentId 是候选新版，需同时提交候选及旧版当前乐观锁版本")
    public ApiResponse<KnowledgeRevisionApprovalResponse> approveReplacement(
            @PathVariable @Min(1) Long knowledgeBaseId, @PathVariable @Min(1) Long documentId,
            @Valid @RequestBody ApproveKnowledgeRevisionRequest request) {
        return ApiResponse.ok(publicationService.approveRevision(knowledgeBaseId, documentId, request));
    }

    /**
     * 分页查询一个知识库中的文档元数据。
     */
    @GetMapping("/{id}/documents")
    @Operation(summary = "分页查询知识库文档")
    public ApiResponse<PageResponse<KnowledgeDocumentResponse>> pageDocuments(
            @Parameter(description = "知识库主键", example = "1")
            @PathVariable @Min(1) Long id,
            @Parameter(description = "页码，从 1 开始", example = "1")
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @Parameter(description = "每页数量，最大 100", example = "20")
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) long size
    ) {
        return ApiResponse.ok(knowledgeDocumentService.page(id, page, size));
    }

    /**
     * 查询指定知识库中的单个文档元数据。
     */
    @GetMapping("/{knowledgeBaseId}/documents/{documentId}")
    @Operation(summary = "查询知识库文档详情")
    public ApiResponse<KnowledgeDocumentResponse> getDocument(
            @Parameter(description = "知识库主键", example = "1")
            @PathVariable @Min(1) Long knowledgeBaseId,
            @Parameter(description = "文档主键", example = "1")
            @PathVariable @Min(1) Long documentId
    ) {
        return ApiResponse.ok(knowledgeDocumentService.get(knowledgeBaseId, documentId));
    }

    /**
     * 配置草稿的业务版本、生效时间和适用范围，保存后重新建立索引。
     */
    @PutMapping("/{knowledgeBaseId}/documents/{documentId}/metadata")
    @Operation(summary = "配置文档业务元数据")
    public ApiResponse<KnowledgeDocumentResponse> updateDocumentMetadata(
            @PathVariable @Min(1) Long knowledgeBaseId,
            @PathVariable @Min(1) Long documentId,
            @Valid @RequestBody UpdateKnowledgeDocumentMetadataRequest request
    ) {
        return ApiResponse.ok(publicationService.updateMetadata(knowledgeBaseId, documentId, request));
    }

    /**
     * 发布已索引的草稿，未来生效的文档会等到生效时间再参与检索。
     */
    @PostMapping("/{knowledgeBaseId}/documents/{documentId}/publish")
    @Operation(summary = "发布知识文档")
    public ApiResponse<KnowledgeDocumentResponse> publishDocument(
            @PathVariable @Min(1) Long knowledgeBaseId,
            @PathVariable @Min(1) Long documentId,
            @Valid @RequestBody KnowledgeDocumentVersionRequest request
    ) {
        return ApiResponse.ok(publicationService.publish(knowledgeBaseId, documentId, request.version()));
    }

    /**
     * 撤回已发布文档，让后续知识检索排除该版本。
     */
    @PostMapping("/{knowledgeBaseId}/documents/{documentId}/withdraw")
    @Operation(summary = "撤回知识文档")
    public ApiResponse<KnowledgeDocumentResponse> withdrawDocument(
            @PathVariable @Min(1) Long knowledgeBaseId,
            @PathVariable @Min(1) Long documentId,
            @Valid @RequestBody KnowledgeDocumentVersionRequest request
    ) {
        return ApiResponse.ok(publicationService.withdraw(knowledgeBaseId, documentId, request.version()));
    }

    /**
     * 以内联响应方式输出原始文档，供管理端新标签页预览和下载。
     */
    @GetMapping("/{knowledgeBaseId}/documents/{documentId}/content")
    @Operation(summary = "读取知识库原始文档", description = "返回受 ADMIN 鉴权保护的原始文件流，不暴露对象存储地址")
    public ResponseEntity<InputStreamResource> readDocumentContent(
            @Parameter(description = "知识库主键", example = "1")
            @PathVariable @Min(1) Long knowledgeBaseId,
            @Parameter(description = "文档主键", example = "1")
            @PathVariable @Min(1) Long documentId
    ) {
        KnowledgeDocumentContent content = knowledgeDocumentService.openContent(knowledgeBaseId, documentId);
        MediaType mediaType = MediaType.parseMediaType(content.contentType());
        ContentDisposition disposition = ContentDisposition.inline()
                .filename(content.originalName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(content.contentLength())
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(content.inputStream()));
    }

    /**
     * 下载并解析文档，分页预览实际生成的文本块。
     */
    @PostMapping("/{knowledgeBaseId}/documents/{documentId}/chunk-preview")
    @Operation(summary = "预览文档分块", description = "仅执行下载、解析和分块，不修改文档状态")
    public ApiResponse<DocumentChunkPreviewResponse> previewDocumentChunks(
            @Parameter(description = "知识库主键", example = "1")
            @PathVariable @Min(1) Long knowledgeBaseId,
            @Parameter(description = "文档主键", example = "1")
            @PathVariable @Min(1) Long documentId,
            @Parameter(description = "分块页码，从 1 开始", example = "1")
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @Parameter(description = "每页分块数，最大 100", example = "20")
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) long size
    ) {
        return ApiResponse.ok(knowledgeDocumentPreviewService.preview(
                knowledgeBaseId,
                documentId,
                page,
                size
        ));
    }

    /**
     * 持久化索引任务，默认 Embedding 模型和 ES 的检查由后台执行。
     */
    @PostMapping("/{knowledgeBaseId}/documents/{documentId}/index")
    @Operation(summary = "开始或重新索引文档", description = "202 表示任务已入库；活动任务重复提交不重复入队，外部依赖故障在任务状态中体现")
    public ResponseEntity<ApiResponse<KnowledgeDocumentResponse>> indexDocument(
            @Parameter(description = "知识库主键", example = "1")
            @PathVariable @Min(1) Long knowledgeBaseId,
            @Parameter(description = "文档主键", example = "1")
            @PathVariable @Min(1) Long documentId
    ) {
        KnowledgeDocumentResponse response = knowledgeDocumentIndexingService.start(
                knowledgeBaseId,
                documentId
        );
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(response));
    }

    /** 保存完整业务元数据并提交索引，等待完成后仍需管理员发布。 */
    @PostMapping("/{knowledgeBaseId}/documents/{documentId}/submit")
    @Operation(summary = "保存元数据并提交索引")
    public ResponseEntity<ApiResponse<KnowledgeDocumentResponse>> submitDocument(
            @PathVariable @Min(1) Long knowledgeBaseId,
            @PathVariable @Min(1) Long documentId,
            @Valid @RequestBody UpdateKnowledgeDocumentMetadataRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(
                knowledgeDocumentIndexingService.submit(knowledgeBaseId, documentId, request)));
    }

    /** 查询最近一次任务的重试次数及失败原因，尚未提交返回 data 为 null。 */
    @GetMapping("/{knowledgeBaseId}/documents/{documentId}/index-task")
    @Operation(summary = "查询文档索引任务")
    public ApiResponse<com.anying.helpdesk.knowledge.dto.KnowledgeIndexTaskResponse> indexTask(
            @PathVariable @Min(1) Long knowledgeBaseId, @PathVariable @Min(1) Long documentId) {
        return ApiResponse.ok(knowledgeDocumentIndexingService.task(knowledgeBaseId, documentId));
    }

    /**
     * 执行 BM25、KNN 和 RRF 混合检索，供管理员调试知识库召回效果。
     */
    @PostMapping("/{id}/search")
    @Operation(summary = "调试知识库混合检索", description = "生成查询向量后执行 BM25 与 KNN，并在应用层使用 RRF 融合")
    public ApiResponse<KnowledgeSearchResponse> search(
            @Parameter(description = "知识库主键", example = "1")
            @PathVariable @Min(1) Long id,
            @Valid @RequestBody KnowledgeSearchRequest request
    ) {
        return ApiResponse.ok(knowledgeRetrievalService.search(id, request));
    }

    /**
     * 删除指定文档对象及其元数据。
     */
    @DeleteMapping("/{knowledgeBaseId}/documents/{documentId}")
    @Operation(summary = "删除知识库文档")
    public ResponseEntity<Void> deleteDocument(
            @Parameter(description = "知识库主键", example = "1")
            @PathVariable @Min(1) Long knowledgeBaseId,
            @Parameter(description = "文档主键", example = "1")
            @PathVariable @Min(1) Long documentId
    ) {
        knowledgeDocumentService.delete(knowledgeBaseId, documentId);
        return ResponseEntity.noContent().build();
    }
}
