package com.anying.helpdesk.modelconfig.service;

import com.anying.helpdesk.modelconfig.domain.AiModelConfig;
import com.anying.helpdesk.modelconfig.factory.DynamicChatModelRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SpringAiModelConnectionTester implements ModelConnectionTester {

    private final DynamicChatModelRegistry chatModelRegistry;

    @Override
    public void test(AiModelConfig config) {
        ChatModel chatModel = chatModelRegistry.get(config);
        String response = chatModel.call("Reply with OK only.");
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("model returned an empty response");
        }
    }
}
