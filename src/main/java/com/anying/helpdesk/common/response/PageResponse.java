package com.anying.helpdesk.common.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "分页数据")
public record PageResponse<T>(
        @Schema(description = "当前页码，从 1 开始", example = "1") long page,
        @Schema(description = "每页数量", example = "20") long size,
        @Schema(description = "记录总数", example = "42") long total,
        @Schema(description = "总页数", example = "3") long pages,
        @Schema(description = "当前页记录") List<T> records
) {
}
