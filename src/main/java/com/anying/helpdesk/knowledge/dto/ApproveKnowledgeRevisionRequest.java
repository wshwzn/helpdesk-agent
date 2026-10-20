package com.anying.helpdesk.knowledge.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 审核时同时确认候选和旧版，避免使用过期页面替换错误版本。 */
public record ApproveKnowledgeRevisionRequest(@NotNull @Min(0) Integer version,
                                              @NotNull @Min(0) Integer predecessorVersion) {
}
