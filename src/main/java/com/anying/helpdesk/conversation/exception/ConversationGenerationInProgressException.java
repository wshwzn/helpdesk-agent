package com.anying.helpdesk.conversation.exception;

public class ConversationGenerationInProgressException extends RuntimeException {

    /**
     * 表示同一对话已有一条正在生成的 AI 回复。
     */
    public ConversationGenerationInProgressException(Long conversationId) {
        super("conversation already has an AI response in progress: " + conversationId);
    }
}
