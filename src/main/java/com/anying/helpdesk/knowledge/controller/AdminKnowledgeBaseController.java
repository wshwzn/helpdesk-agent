package com.anying.helpdesk.knowledge.controller;

import com.anying.helpdesk.common.response.ApiResponse;
import com.anying.helpdesk.common.response.PageResponse;
import com.anying.helpdesk.config.OpenApiConfig;
import com.anying.helpdesk.knowledge.dto.CreateKnowledgeBaseRequest;
import com.anying.helpdesk.knowledge.dto.DocumentChunkPreviewResponse;
import com.anying.helpdesk.knowledge.dto.KnowledgeBaseResponse;
import com.anying.helpdesk.knowledge.dto.KnowledgeDocumentResponse;
import com.anying.helpdesk.knowledge.service.KnowledgeBaseService;
import com.anying.helpdesk.knowledge.service.KnowledgeDocumentService;
import com.anying.helpdesk.knowledge.service.KnowledgeDocumentPreviewService;
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
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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
