package com.anying.helpdesk.knowledge.source;

import com.anying.helpdesk.common.response.ApiResponse;
import com.anying.helpdesk.common.response.PageResponse;
import com.anying.helpdesk.knowledge.dto.KnowledgeDocumentVersionRequest;
import com.anying.helpdesk.security.CurrentUserProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/admin/knowledge-bases/{baseId}/sources")
public class AdminKnowledgeSourceController {
    private final KnowledgeSourceStore sources;
    private final KnowledgeSourceSyncService sync;
    private final KnowledgeSourceImportService imports;
    private final CurrentUserProvider currentUser;

    public record CreateSourceRequest(@NotBlank @Pattern(regexp = "external-knowledge/[a-z0-9][a-z0-9_-]{0,79}/") String objectPrefix) { }
    public record EnableSourceRequest(@NotNull Boolean enabled) { }

    /** 注册同一 OSS Bucket 的专用来源目录。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<KnowledgeSource> create(@PathVariable @Min(1) Long baseId, @Valid @RequestBody CreateSourceRequest request) {
        return ApiResponse.ok(sources.create(baseId, request.objectPrefix(), currentUser.userId()));
    }

    /** 查询已登记来源及最近扫描结果。 */
    @GetMapping
    public ApiResponse<List<KnowledgeSource>> list(@PathVariable @Min(1) Long baseId) {
        return ApiResponse.ok(sources.list(baseId));
    }

    /** 查询单个来源及最近扫描结果，失败报告可在扫描请求异常后读取。 */
    @GetMapping("/{sourceId}")
    public ApiResponse<KnowledgeSource> get(@PathVariable @Min(1) Long baseId, @PathVariable @Min(1) Long sourceId) {
        return ApiResponse.ok(sources.get(baseId, sourceId));
    }

    /** 暂停或恢复扫描，不改变已导入资料的发布状态。 */
    @PutMapping("/{sourceId}/enabled")
    public ApiResponse<KnowledgeSource> enabled(@PathVariable @Min(1) Long baseId, @PathVariable @Min(1) Long sourceId,
                                              @Valid @RequestBody EnableSourceRequest request) {
        return ApiResponse.ok(sources.setEnabled(baseId, sourceId, request.enabled()));
    }

    /** 同步执行一次有数量上限的扫描，索引由已有后台任务异步执行。 */
    @PostMapping("/{sourceId}/scan")
    public ApiResponse<KnowledgeSource> scan(@PathVariable @Min(1) Long baseId, @PathVariable @Min(1) Long sourceId) {
        return ApiResponse.ok(sync.scan(baseId, sourceId));
    }

    /** 分页查看对象与候选文档关联及失败、缺失提示。 */
    @GetMapping("/{sourceId}/items")
    public ApiResponse<PageResponse<KnowledgeSourceItem>> items(@PathVariable @Min(1) Long baseId, @PathVariable @Min(1) Long sourceId,
            @RequestParam(defaultValue = "1") @Min(1) long page, @RequestParam(defaultValue = "20") @Min(1) @Max(100) long size) {
        return ApiResponse.ok(sources.items(baseId, sourceId, page, size));
    }

    /** 拒绝未发布候选后允许来源的新业务版本再次进入审核。 */
    @PostMapping("/{sourceId}/items/{itemId}/reject-candidate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(@PathVariable @Min(1) Long baseId, @PathVariable @Min(1) Long sourceId, @PathVariable @Min(1) Long itemId,
                       @Valid @RequestBody KnowledgeDocumentVersionRequest request) {
        imports.reject(baseId, sourceId, itemId, request.version());
    }
}
