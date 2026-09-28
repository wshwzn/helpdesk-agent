package com.anying.helpdesk.modelconfig.controller;

import com.anying.helpdesk.common.response.ApiResponse;
import com.anying.helpdesk.config.OpenApiConfig;
import com.anying.helpdesk.modelconfig.dto.CreateModelConfigRequest;
import com.anying.helpdesk.modelconfig.dto.ModelConfigResponse;
import com.anying.helpdesk.modelconfig.dto.ModelConnectionTestResponse;
import com.anying.helpdesk.modelconfig.dto.ModelVendorResponse;
import com.anying.helpdesk.modelconfig.dto.UpdateModelConfigRequest;
import com.anying.helpdesk.modelconfig.domain.ModelVendor;
import com.anying.helpdesk.modelconfig.domain.ModelType;
import com.anying.helpdesk.modelconfig.service.AiModelConfigService;
import com.anying.helpdesk.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

@Validated
@RestController
@RequestMapping("/api/admin/model-configs")
@RequiredArgsConstructor
@Tag(name = "模型配置", description = "ADMIN 管理 OpenAI 兼容模型配置")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class AdminModelConfigController {

    private final AiModelConfigService modelConfigService;
    private final CurrentUserProvider currentUserProvider;

    @PostMapping
    @Operation(summary = "创建模型配置")
    public ResponseEntity<ApiResponse<ModelConfigResponse>> create(
            @Valid @RequestBody CreateModelConfigRequest request
    ) {
        ModelConfigResponse response = modelConfigService.create(request, currentUserProvider.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @GetMapping
    @Operation(summary = "查询全部模型配置")
    public ApiResponse<List<ModelConfigResponse>> list() {
        return ApiResponse.ok(modelConfigService.list());
    }

    /**
     * 按模型用途过滤管理端可选择的厂商，避免向量模型误用聊天地址预设。
     */
    @GetMapping("/vendors")
    @Operation(summary = "按模型用途查询可选厂商", description = "CHAT 返回全部预设；EMBEDDING 只返回 CUSTOM")
    public ApiResponse<List<ModelVendorResponse>> listVendors(
            @Parameter(description = "模型用途；省略时为 CHAT", example = "CHAT")
            @RequestParam(defaultValue = "CHAT") ModelType modelType
    ) {
        return ApiResponse.ok(Arrays.stream(ModelVendor.values())
                .filter(vendor -> modelType == ModelType.CHAT || vendor == ModelVendor.CUSTOM)
                .map(ModelVendorResponse::from)
                .toList());
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询模型配置详情")
    public ApiResponse<ModelConfigResponse> getById(
            @Parameter(description = "模型配置主键", example = "1")
            @PathVariable @Min(1) Long id
    ) {
        return ApiResponse.ok(modelConfigService.getById(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新模型配置", description = "省略 apiKey 时保留原密钥")
    public ApiResponse<ModelConfigResponse> update(
            @Parameter(description = "模型配置主键", example = "1")
            @PathVariable @Min(1) Long id,
            @Valid @RequestBody UpdateModelConfigRequest request
    ) {
        return ApiResponse.ok(modelConfigService.update(id, request, currentUserProvider.userId()));
    }

    @PostMapping("/{id}/test")
    @Operation(summary = "测试模型连接", description = "按模型用途发送一次极短对话或向量请求，可能产生少量费用")
    public ApiResponse<ModelConnectionTestResponse> testConnection(
            @Parameter(description = "模型配置主键", example = "1")
            @PathVariable @Min(1) Long id
    ) {
        return ApiResponse.ok(modelConfigService.testConnection(id));
    }

    @PostMapping("/{id}/default")
    @Operation(summary = "设为默认模型", description = "只有启用的模型配置可以设为默认；CHAT 和 EMBEDDING 分别维护默认项")
    public ApiResponse<ModelConfigResponse> setDefault(
            @Parameter(description = "模型配置主键", example = "1")
            @PathVariable @Min(1) Long id
    ) {
        return ApiResponse.ok(modelConfigService.setDefault(id, currentUserProvider.userId()));
    }

    @PostMapping("/{id}/enable")
    @Operation(summary = "启用模型配置")
    public ApiResponse<ModelConfigResponse> enable(
            @Parameter(description = "模型配置主键", example = "1")
            @PathVariable @Min(1) Long id
    ) {
        return ApiResponse.ok(modelConfigService.enable(id, currentUserProvider.userId()));
    }

    @PostMapping("/{id}/disable")
    @Operation(summary = "禁用模型配置", description = "默认模型不能直接禁用")
    public ApiResponse<ModelConfigResponse> disable(
            @Parameter(description = "模型配置主键", example = "1")
            @PathVariable @Min(1) Long id
    ) {
        return ApiResponse.ok(modelConfigService.disable(id, currentUserProvider.userId()));
    }
}
