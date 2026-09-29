package com.anying.helpdesk.knowledge.controller;

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

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeDocumentService knowledgeDocumentService;
    private final KnowledgeDocumentPreviewService knowledgeDocumentPreviewService;
    private final KnowledgeDocumentIndexingService knowledgeDocumentIndexingService;
    private final KnowledgeRetrievalService knowledgeRetrievalService;
    private final CurrentUserProvider currentUserProvider;

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
     * 预检默认 Embedding 模型和 ES 索引后，异步处理或重新索引文档。
     */
    @PostMapping("/{knowledgeBaseId}/documents/{documentId}/index")
    @Operation(summary = "开始或重新索引文档", description = "请求成功后文档进入 PROCESSING，后台完成向量化和 ES 写入")
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
