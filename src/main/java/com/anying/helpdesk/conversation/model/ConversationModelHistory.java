package com.anying.helpdesk.conversation.model;

import com.anying.helpdesk.agent.domain.AgentToolCall;
import com.anying.helpdesk.conversation.domain.ChatMessage;

import java.util.List;

/**
 * 保存普通聊天消息和仅用于连续追问的最近一轮成功工具调用。
 */
public record ConversationModelHistory(
        List<ChatMessage> messages,
        List<AgentToolCall> recentToolCalls
) {

    /**
     * 创建只读历史快照，避免模型调用期间集合被其他逻辑修改。
     */
    public ConversationModelHistory {
        messages = List.copyOf(messages);
        recentToolCalls = List.copyOf(recentToolCalls);
    }
}
