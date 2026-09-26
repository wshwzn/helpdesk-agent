package com.anying.helpdesk.modelconfig.factory;

import com.anying.helpdesk.modelconfig.crypto.SecretEncryptor;
import com.anying.helpdesk.modelconfig.domain.AiModelConfig;
import com.anying.helpdesk.modelconfig.domain.ModelProtocol;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OpenAiCompatibleChatModelFactory implements AiChatModelFactory {

    private final SecretEncryptor secretEncryptor;
    private final OpenAiCompatibleEndpointResolver endpointResolver;

    @Override
    public ModelProtocol protocol() {
        return ModelProtocol.OPENAI_COMPATIBLE;
    }

    @Override
    public ChatModel create(AiModelConfig config) {
        OpenAiCompatibleEndpoint endpoint = endpointResolver.resolve(config.getBaseUrl());
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(endpoint.apiRoot())
                .completionsPath(endpoint.chatCompletionsPath())
                .apiKey(secretEncryptor.decrypt(config.getApiKeyCiphertext()))
                .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(config.getModelName())
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .build();
    }
}
