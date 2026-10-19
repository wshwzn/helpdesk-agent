package com.anying.helpdesk.conversation.dto;

import com.anying.helpdesk.conversation.domain.ChatMessage;
import com.anying.helpdesk.conversation.domain.ChatMessageRole;
import com.anying.helpdesk.conversation.domain.ChatMessageStatus;
import com.anying.helpdesk.conversation.domain.ChatMode;
import com.anying.helpdesk.knowledge.answer.KnowledgeAnswerReport;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "对话消息")
public record ChatMessageResponse(
        @Schema(description = "消息主键", example = "1") Long id,
        @Schema(description = "所属对话主键", example = "1") Long conversationId,
        @Schema(description = "消息角色") ChatMessageRole role,
        @Schema(description = "消息内容；生成中或失败时可能为空") String content,
        @Schema(description = "消息生成状态") ChatMessageStatus status,
        @Schema(description = "AI 消息使用的模型配置主键") Long modelConfigId,
        @Schema(description = "AI 消息使用的模型名称") String modelName,
        @Schema(description = "输入 Token 数；厂商不返回时为空") Integer promptTokens,
        @Schema(description = "输出 Token 数；厂商不返回时为空") Integer completionTokens,
        @Schema(description = "总 Token 数；厂商不返回时为空") Integer totalTokens,
        @Schema(description = "失败错误码；成功时为空") String errorCode,
        @Schema(description = "AI 回答引用的知识库片段；未检索知识库时为空数组")
        List<KnowledgeCitationResponse> citations,
        @Schema(description = "本条消息的对话模式；AGENT 为旧兼容模式，其检索引用不等于已完成可信核验") ChatMode mode,
        @Schema(description = "知识问答显式选择的适用范围") String applicabilityScope,
        @Schema(description = "知识问答核验报告；普通对话、生成中或失败时为空") KnowledgeAnswerReport knowledgeAnswer,
        @Schema(description = "知识助手消息的运行编号；仅管理员可查询诊断，旧消息为空") String knowledgeRunId,
        @Schema(description = "创建时间") LocalDateTime createdAt,
        @Schema(description = "最后更新时间") LocalDateTime updatedAt
) {

    public ChatMessageResponse {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    /**
     * 将消息实体转换为不附带知识引用的接口响应。
     */
    public static ChatMessageResponse from(ChatMessage message) {
        return from(message, List.of());
    }

    /**
     * 将消息实体及其知识引用转换为接口响应。
     */
    public static ChatMessageResponse from(
            ChatMessage message,
            List<KnowledgeCitationResponse> citations
    ) {
        return new ChatMessageResponse(
                message.getId(),
                message.getConversationId(),
                message.getRole(),
                message.getContent(),
                message.getStatus(),
                message.getModelConfigId(),
                message.getModelName(),
                message.getPromptTokens(),
                message.getCompletionTokens(),
                message.getTotalTokens(),
                message.getErrorCode(),
                message.getKnowledgeAnswer() == null ? citations : message.getKnowledgeAnswer().citations(),
                message.getMode(),
                message.getApplicabilityScope(),
                message.getKnowledgeAnswer(),
                message.getKnowledgeRunId(),
                message.getCreatedAt(),
                message.getUpdatedAt()
        );
    }
}
