package com.anying.helpdesk.conversation.dto;

import com.anying.helpdesk.conversation.domain.ChatMode;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "发送消息请求")
public record SendMessageRequest(
        @Schema(description = "员工提问内容", example = "差旅结束后多久内提交报销？")
        @NotBlank @Size(max = 2000) String content,
        @Schema(description = "统一可信问答；AGENT 仅为旧请求别名，与省略/null/KNOWLEDGE 一样规范为 KNOWLEDGE", defaultValue = "KNOWLEDGE") ChatMode mode,
        @Schema(description = "旧单范围字段；省略范围时按已配置名称识别或澄清", example = "CN_SH")
        @Pattern(regexp = "[A-Z][A-Z0-9_-]{0,39}") String applicabilityScope,
        @Schema(description = "续答的澄清助手消息 ID；必须显式选择其提供的范围")
        @jakarta.validation.constraints.Positive Long clarificationMessageId,
        @Schema(description = "适用范围列表，最多3项；不可与不同的单范围字段并用，不可混选ALL")
        java.util.List<String> applicabilityScopes
) {
    /** 所有请求统一为可信问答；AGENT 仅兼容旧客户端的字段值，不再选择另一条生成链路。 */
    public SendMessageRequest {
        mode = ChatMode.KNOWLEDGE;
        applicabilityScopes = com.anying.helpdesk.knowledge.answer.KnowledgeScopes.resolve(applicabilityScope, applicabilityScopes);
        applicabilityScope = com.anying.helpdesk.knowledge.answer.KnowledgeScopes.single(applicabilityScopes);
    }

    /** 保留旧客户端及单范围续答的构造入口。 */
    public SendMessageRequest(String content, ChatMode mode, String scope, Long clarificationId) {
        this(content, mode, scope, clarificationId, null);
    }

    /** 兼容未使用澄清续答的知识问答请求。 */
    public SendMessageRequest(String content, ChatMode mode, String applicabilityScope) {
        this(content, mode, applicabilityScope, null);
    }

    /** 仅传正文的内部调用同样进入可信问答，避免与 HTTP 默认行为不一致。 */
    public SendMessageRequest(String content) {
        this(content, null, null);
    }

    /** 澄清续答必须明确选择地区，不能用缺省通用范围绕过原选项检查。 */
    @JsonIgnore
    @AssertTrue(message = "clarification requires an explicit non-ALL scope selection")
    public boolean isKnowledgeInputValid() {
        return clarificationMessageId == null || !applicabilityScopes.contains("ALL");
    }
}
