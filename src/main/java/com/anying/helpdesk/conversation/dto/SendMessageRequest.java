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
        @Schema(description = "客户消息内容", example = "为什么我无法登录控制台？")
        @NotBlank @Size(max = 10000) String content,
        @Schema(description = "对话模式，默认 AGENT；KNOWLEDGE 强制检索和核验，问题最多 2000 字") ChatMode mode,
        @Schema(description = "KNOWLEDGE 的适用范围代码，默认 ALL；不从自然语言猜测", example = "SH")
        @Pattern(regexp = "[A-Z][A-Z0-9_-]{0,39}") String applicabilityScope,
        @Schema(description = "续答的澄清助手消息 ID；必须显式选择其提供的范围")
        @jakarta.validation.constraints.Positive Long clarificationMessageId
) {
    /** 为旧客户端保留普通 Agent 模式，知识问答未指定范围时仅使用通用资料。 */
    public SendMessageRequest {
        mode = mode == null ? ChatMode.AGENT : mode;
        if (mode == ChatMode.KNOWLEDGE && applicabilityScope == null) {
            applicabilityScope = "ALL";
        }
    }

    /** 兼容未使用澄清续答的知识问答请求。 */
    public SendMessageRequest(String content, ChatMode mode, String applicabilityScope) {
        this(content, mode, applicabilityScope, null);
    }

    /** 兼容仅传入正文的内部调用。 */
    public SendMessageRequest(String content) {
        this(content, ChatMode.AGENT, null);
    }

    /** 校验知识检索的问题长度，并避免在普通模式静默忽略范围参数。 */
    @JsonIgnore
    @AssertTrue(message = "KNOWLEDGE content must be at most 2000 characters; scope is only supported in KNOWLEDGE mode")
    public boolean isKnowledgeInputValid() {
        return mode == ChatMode.KNOWLEDGE
                ? (content == null || content.length() <= 2000)
                    && (clarificationMessageId == null || (applicabilityScope != null && !"ALL".equals(applicabilityScope)))
                : applicabilityScope == null && clarificationMessageId == null;
    }
}
