package com.anying.helpdesk.conversation.model;

import com.anying.helpdesk.conversation.domain.ChatMessage;
import com.anying.helpdesk.modelconfig.factory.ResolvedChatModel;

import java.util.List;

public interface ConversationModelClient {

    ChatCompletionResult complete(ResolvedChatModel model, List<ChatMessage> history);
}
