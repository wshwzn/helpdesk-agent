package com.anying.helpdesk.common.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "统一 API 响应")
public record ApiResponse<T>(
        @Schema(description = "请求是否成功", example = "true") boolean success,
        @Schema(description = "业务响应码", example = "OK") String code,
        @Schema(description = "响应说明", example = "success") String message,
        @Schema(description = "业务数据；失败时可能为空") T data,
        @Schema(description = "响应生成时间") Instant timestamp
) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, "OK", "success", data, Instant.now());
    }

    public static <T> ApiResponse<T> error(String code, String message, T data) {
        return new ApiResponse<>(false, code, message, data, Instant.now());
    }
}
