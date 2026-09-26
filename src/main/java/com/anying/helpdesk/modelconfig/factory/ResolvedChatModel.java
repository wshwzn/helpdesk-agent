package com.anying.helpdesk.modelconfig.factory;

import org.springframework.ai.chat.model.ChatModel;

public record ResolvedChatModel(
        Long configId,
        String modelName,
        boolean supportsStreaming,
        ChatModel chatModel
) {
}
