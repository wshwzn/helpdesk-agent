package com.anying.helpdesk.knowledge.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDateTime;

@Schema(description = "配置草稿文档的业务版本和适用条件；保存后需要重新索引")
public record UpdateKnowledgeDocumentMetadataRequest(
        @NotNull @Min(0) Integer version,
        @Schema(description = "同一来源跨版本保持不变的标识", example = "travel-policy")
        @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9._:/-]{0,119}") String sourceKey,
        @Schema(description = "业务版本标签，不用于自动比较新旧", example = "2026.2")
        @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9._-]{0,39}") String businessVersion,
        @Schema(description = "单个适用范围代码；ALL 表示通用资料，不代表访问权限", example = "CN_SH")
        @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_-]{0,39}") String applicabilityScope,
        @Schema(description = "生效时间，含边界；统一使用 Asia/Shanghai 本地时间")
        @NotNull LocalDateTime validFrom,
        @Schema(description = "失效时间，不含边界；空表示不设截止时间")
        LocalDateTime validUntil
) {
}
