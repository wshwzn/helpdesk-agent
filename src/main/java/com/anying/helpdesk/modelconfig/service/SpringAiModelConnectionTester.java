package com.anying.helpdesk.modelconfig.service;

import com.anying.helpdesk.modelconfig.domain.AiModelConfig;
import com.anying.helpdesk.modelconfig.domain.ModelType;
import com.anying.helpdesk.modelconfig.factory.DynamicChatModelRegistry;
import com.anying.helpdesk.modelconfig.factory.DynamicEmbeddingModelRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SpringAiModelConnectionTester implements ModelConnectionTester {

    private final DynamicChatModelRegistry chatModelRegistry;
    private final DynamicEmbeddingModelRegistry embeddingModelRegistry;

    /**
     * 按模型用途执行最小请求，并验证返回内容或向量维度。
     */
    @Override
    public void test(AiModelConfig config) {
        if (config.getModelType() == ModelType.EMBEDDING) {
            testEmbedding(config);
            return;
        }
        ChatModel chatModel = chatModelRegistry.get(config);
        String response = chatModel.call("Reply with OK only.");
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("model returned an empty response");
        }
    }

    /**
     * 发送一条短文本并确认厂商返回的向量维度与配置一致。
     */
    private void testEmbedding(AiModelConfig config) {
        EmbeddingModel embeddingModel = embeddingModelRegistry.get(config);
        float[] vector = embeddingModel.embed("connection test");
        if (vector == null || vector.length != config.getEmbeddingDimensions()) {
            throw new IllegalStateException("embedding dimensions do not match the model configuration");
        }
    }
}
