package com.anying.helpdesk.modelconfig.factory;

import com.anying.helpdesk.modelconfig.crypto.SecretEncryptor;
import com.anying.helpdesk.modelconfig.domain.AiModelConfig;
import com.anying.helpdesk.modelconfig.domain.ModelProtocol;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OpenAiCompatibleEmbeddingModelFactory implements AiEmbeddingModelFactory {

    private final SecretEncryptor secretEncryptor;
    private final OpenAiCompatibleEndpointResolver endpointResolver;

    /**
     * 声明本工厂处理 OpenAI 兼容协议。
     */
    @Override
    public ModelProtocol protocol() {
        return ModelProtocol.OPENAI_COMPATIBLE;
    }

    /**
     * 创建调用标准 embeddings 端点的 Spring AI 模型，不强制向厂商传递维度参数。
     */
    @Override
    public EmbeddingModel create(AiModelConfig config) {
        OpenAiCompatibleEndpoint endpoint = endpointResolver.resolve(config.getBaseUrl());
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(endpoint.apiRoot())
                .embeddingsPath(endpoint.embeddingsPath())
                .apiKey(secretEncryptor.decrypt(config.getApiKeyCiphertext()))
                .build();
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(config.getModelName())
                .build();
        return new OpenAiEmbeddingModel(openAiApi, MetadataMode.EMBED, options);
    }
}
