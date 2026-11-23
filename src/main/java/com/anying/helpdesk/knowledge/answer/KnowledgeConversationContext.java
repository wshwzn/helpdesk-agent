package com.anying.helpdesk.knowledge.answer;

import java.util.List;

/** 最近对话仅用于理解追问，不作为本轮事实证据。 */
public record KnowledgeConversationContext(String question, String clarification) {
    public record Message(String role, String content, List<String> scopes) { }
}
