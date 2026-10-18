package com.anying.helpdesk.knowledge.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;

@Schema(description = "知识库混合检索请求")
public record KnowledgeSearchRequest(
        @Schema(description = "待检索的自然语言问题", example = "如何配置模型接口？")
        @NotBlank @Size(max = 2000) String query,
        @Schema(description = "适用范围代码；省略或 ALL 仅检索通用资料，指定范围时同时检索该范围与 ALL")
        @Pattern(regexp = "[A-Z][A-Z0-9_-]{0,39}") String applicabilityScope
) {
    /**
     * 为未提供范围的查询使用通用资料，避免默认混用不同适用对象的规则。
     */
    public KnowledgeSearchRequest {
        applicabilityScope = applicabilityScope == null ? "ALL" : applicabilityScope;
    }

    /**
     * 保留已有通用知识查询入口，包括尚未增加范围字段的检索评测。
     */
    public KnowledgeSearchRequest(String query) {
        this(query, "ALL");
    }
}
