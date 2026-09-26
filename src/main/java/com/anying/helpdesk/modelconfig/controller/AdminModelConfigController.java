package com.anying.helpdesk.modelconfig.controller;

import com.anying.helpdesk.common.response.ApiResponse;
import com.anying.helpdesk.config.OpenApiConfig;
import com.anying.helpdesk.modelconfig.dto.CreateModelConfigRequest;
import com.anying.helpdesk.modelconfig.dto.ModelConfigResponse;
import com.anying.helpdesk.modelconfig.dto.ModelConnectionTestResponse;
import com.anying.helpdesk.modelconfig.dto.ModelVendorResponse;
import com.anying.helpdesk.modelconfig.dto.UpdateModelConfigRequest;
import com.anying.helpdesk.modelconfig.domain.ModelVendor;
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

    @GetMapping("/vendors")
    @Operation(summary = "查询可选模型厂商", description = "返回内置地址预设以及自定义地址填写规则")
    public ApiResponse<List<ModelVendorResponse>> listVendors() {
        return ApiResponse.ok(Arrays.stream(ModelVendor.values())
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
    @Operation(summary = "测试模型连接", description = "会向模型发送一次极短请求，可能产生少量费用")
    public ApiResponse<ModelConnectionTestResponse> testConnection(
            @Parameter(description = "模型配置主键", example = "1")
            @PathVariable @Min(1) Long id
    ) {
        return ApiResponse.ok(modelConfigService.testConnection(id));
    }

    @PostMapping("/{id}/default")
    @Operation(summary = "设为默认模型", description = "只有启用的模型配置可以设为默认")
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
