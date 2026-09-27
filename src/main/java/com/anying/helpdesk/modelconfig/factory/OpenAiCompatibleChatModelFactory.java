package com.anying.helpdesk.modelconfig.factory;

import com.anying.helpdesk.conversation.config.ChatProperties;
import com.anying.helpdesk.modelconfig.crypto.SecretEncryptor;
import com.anying.helpdesk.modelconfig.domain.AiModelConfig;
import com.anying.helpdesk.modelconfig.domain.ModelProtocol;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ChatProperties chatProperties;
    private final ObjectMapper objectMapper;

    @Override
    public ModelProtocol protocol() {
        return ModelProtocol.OPENAI_COMPATIBLE;
    }

    @Override
    public ChatModel create(AiModelConfig config) {
        OpenAiCompatibleEndpoint endpoint = endpointResolver.resolve(config.getBaseUrl());
        OpenAiApi openAiApi = createOpenAiApi(config, endpoint);
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(config.getModelName())
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .build();
    }

    /**
     * 根据本地观察开关创建普通或带脱敏协议日志的 OpenAI 兼容客户端。
     */
    private OpenAiApi createOpenAiApi(AiModelConfig config, OpenAiCompatibleEndpoint endpoint) {
        String apiKey = secretEncryptor.decrypt(config.getApiKeyCiphertext());
        if (chatProperties.logModelProtocol()) {
            return new ProtocolLoggingOpenAiApi(
                    endpoint.apiRoot(),
                    endpoint.chatCompletionsPath(),
                    apiKey,
                    objectMapper
            );
        }
        return OpenAiApi.builder()
                .baseUrl(endpoint.apiRoot())
                .completionsPath(endpoint.chatCompletionsPath())
                .apiKey(apiKey)
                .build();
    }
}
