package com.anying.helpdesk.knowledge.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Schema(description = "发布或撤回文档时提交当前乐观锁版本，防止旧页面覆盖新状态")
public record KnowledgeDocumentVersionRequest(@NotNull @Min(0) Integer version) {
}
