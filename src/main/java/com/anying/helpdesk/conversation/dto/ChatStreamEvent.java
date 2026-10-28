package com.anying.helpdesk.conversation.dto;

import com.anying.helpdesk.agent.action.dto.PendingActionResponse;
import com.anying.helpdesk.knowledge.answer.trace.KnowledgeAnswerRunSnapshot.Stage;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "流式对话事件；不同事件类型只使用与其相关的字段")
public record ChatStreamEvent(
        @Schema(
                description = "SSE 事件类型",
                allowableValues = {"start", "progress", "delta", "action_required", "done", "error"}
        )
        String type,
        @Schema(description = "start 事件携带的已保存客户消息")
        ChatMessageResponse userMessage,
        @Schema(description = "start 和 progress 事件携带的待生成 AI 消息主键", example = "2")
        Long assistantMessageId,
        @Schema(description = "delta 事件携带的本次新增文本片段")
        String content,
        @Schema(description = "action_required 事件携带的待确认操作")
        PendingActionResponse pendingAction,
        @Schema(description = "done 事件携带的完整 AI 消息")
        ChatMessageResponse assistantMessage,
        @Schema(description = "error 事件携带的稳定错误码")
        String errorCode,
        @Schema(description = "error 的脱敏说明或 progress 的固定阶段提示")
        String message,
        @Schema(description = "progress 事件携带的正在执行的阶段；同一阶段可能重复出现")
        Stage stage
) {

    /**
     * 创建流式回答开始事件，告知客户端已保存的消息编号。
     */
    public static ChatStreamEvent start(ChatMessageResponse userMessage, Long assistantMessageId) {
        return new ChatStreamEvent(
                "start",
                userMessage,
                assistantMessageId,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    /**
     * 创建模型文本增量事件。
     */
    public static ChatStreamEvent delta(String content) {
        return new ChatStreamEvent("delta", null, null, content, null, null, null, null, null);
    }

    /**
     * 创建需要前端展示确认卡片的业务操作事件。
     */
    public static ChatStreamEvent actionRequired(PendingActionResponse pendingAction) {
        return new ChatStreamEvent(
                "action_required",
                null,
                null,
                null,
                pendingAction,
                null,
                null,
                null,
                null
        );
    }

    /**
     * 创建流式回答完成事件，并返回最终持久化的 AI 消息。
     */
    public static ChatStreamEvent done(ChatMessageResponse assistantMessage) {
        return new ChatStreamEvent("done", null, null, null, null, assistantMessage, null, null, null);
    }

    /**
     * 创建不包含厂商敏感信息的流式错误事件。
     */
    public static ChatStreamEvent error(String errorCode, String message) {
        return new ChatStreamEvent("error", null, null, null, null, null, errorCode, message, null);
    }

    /** 返回真实开始执行的阶段，提示使用固定文案，不包含模型草稿或内部异常。 */
    public static ChatStreamEvent progress(Long assistantMessageId, Stage stage) {
        String message = switch (stage) {
            case APPLICABILITY -> "正在识别问题的适用条件";
            case RETRIEVAL -> "正在检索知识库资料";
            case PLANNING -> "正在检查资料是否充分并规划补充检索";
            case DRAFT -> "正在根据资料生成答案";
            case VALIDATION -> "正在校验答案引用";
            case REVIEW -> "正在核验答案与资料是否一致";
            case REVISION -> "正在根据核验结果修订答案";
            case EVIDENCE_RECHECK -> "正在确认引用资料仍然有效";
            case MESSAGE_PERSISTENCE -> "正在保存回答";
        };
        return new ChatStreamEvent("progress", null, assistantMessageId, null, null, null, null, message, stage);
    }
}
