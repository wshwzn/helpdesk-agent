package com.anying.helpdesk.modelconfig.dto;

import com.anying.helpdesk.modelconfig.domain.ModelVendor;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "模型厂商选项")
public record ModelVendorResponse(
        @Schema(description = "请求中使用的厂商编码", example = "DEEPSEEK") ModelVendor code,
        @Schema(description = "厂商显示名称", example = "DeepSeek") String displayName,
        @Schema(description = "后端预设地址；CUSTOM 时为空") String baseUrl,
        @Schema(description = "是否要求管理员填写自定义地址", example = "false") boolean customBaseUrlRequired
) {

    public static ModelVendorResponse from(ModelVendor vendor) {
        return new ModelVendorResponse(
                vendor,
                vendor.getDisplayName(),
                vendor.getPresetBaseUrl(),
                vendor.isCustomBaseUrlRequired()
        );
    }
}
