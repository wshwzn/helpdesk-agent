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
        @NotBlank @Size(max = 10000) String content,
        @Schema(description = "默认 KNOWLEDGE，强制检索和核验，问题最多 2000 字；显式 AGENT 仅为旧客户端兼容，不具备完整核验保证", defaultValue = "KNOWLEDGE") ChatMode mode,
        @Schema(description = "KNOWLEDGE 的适用范围代码，默认 ALL；不从自然语言猜测", example = "CN_SH")
        @Pattern(regexp = "[A-Z][A-Z0-9_-]{0,39}") String applicabilityScope,
        @Schema(description = "续答的澄清助手消息 ID；必须显式选择其提供的范围")
        @jakarta.validation.constraints.Positive Long clarificationMessageId
) {
    /** 新请求默认进入可信问答，显式旧模式保持兼容；知识问答省略范围时仅查通用资料。 */
    public SendMessageRequest {
        mode = mode == null ? ChatMode.KNOWLEDGE : mode;
        if (mode == ChatMode.KNOWLEDGE && applicabilityScope == null) {
            applicabilityScope = "ALL";
        }
    }

    /** 兼容未使用澄清续答的知识问答请求。 */
    public SendMessageRequest(String content, ChatMode mode, String applicabilityScope) {
        this(content, mode, applicabilityScope, null);
    }

    /** 仅传正文的内部调用同样进入可信问答，避免与 HTTP 默认行为不一致。 */
    public SendMessageRequest(String content) {
        this(content, null, null);
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
