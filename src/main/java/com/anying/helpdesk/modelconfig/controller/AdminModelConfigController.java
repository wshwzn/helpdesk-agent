package com.anying.helpdesk.modelconfig.controller;

import com.anying.helpdesk.common.response.ApiResponse;
import com.anying.helpdesk.modelconfig.dto.CreateModelConfigRequest;
import com.anying.helpdesk.modelconfig.dto.ModelConfigResponse;
import com.anying.helpdesk.modelconfig.dto.ModelConnectionTestResponse;
import com.anying.helpdesk.modelconfig.dto.UpdateModelConfigRequest;
import com.anying.helpdesk.modelconfig.service.AiModelConfigService;
import com.anying.helpdesk.security.CurrentUserProvider;
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

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/admin/model-configs")
@RequiredArgsConstructor
public class AdminModelConfigController {

    private final AiModelConfigService modelConfigService;
    private final CurrentUserProvider currentUserProvider;

    @PostMapping
    public ResponseEntity<ApiResponse<ModelConfigResponse>> create(
            @Valid @RequestBody CreateModelConfigRequest request
    ) {
        ModelConfigResponse response = modelConfigService.create(request, currentUserProvider.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @GetMapping
    public ApiResponse<List<ModelConfigResponse>> list() {
        return ApiResponse.ok(modelConfigService.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<ModelConfigResponse> getById(@PathVariable @Min(1) Long id) {
        return ApiResponse.ok(modelConfigService.getById(id));
    }

    @PutMapping("/{id}")
    public ApiResponse<ModelConfigResponse> update(
            @PathVariable @Min(1) Long id,
            @Valid @RequestBody UpdateModelConfigRequest request
    ) {
        return ApiResponse.ok(modelConfigService.update(id, request, currentUserProvider.userId()));
    }

    @PostMapping("/{id}/test")
    public ApiResponse<ModelConnectionTestResponse> testConnection(@PathVariable @Min(1) Long id) {
        return ApiResponse.ok(modelConfigService.testConnection(id));
    }

    @PostMapping("/{id}/default")
    public ApiResponse<ModelConfigResponse> setDefault(@PathVariable @Min(1) Long id) {
        return ApiResponse.ok(modelConfigService.setDefault(id, currentUserProvider.userId()));
    }

    @PostMapping("/{id}/enable")
    public ApiResponse<ModelConfigResponse> enable(@PathVariable @Min(1) Long id) {
        return ApiResponse.ok(modelConfigService.enable(id, currentUserProvider.userId()));
    }

    @PostMapping("/{id}/disable")
    public ApiResponse<ModelConfigResponse> disable(@PathVariable @Min(1) Long id) {
        return ApiResponse.ok(modelConfigService.disable(id, currentUserProvider.userId()));
    }
}
