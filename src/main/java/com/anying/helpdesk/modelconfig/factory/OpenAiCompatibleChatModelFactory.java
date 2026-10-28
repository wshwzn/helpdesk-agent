package com.anying.helpdesk.modelconfig.factory;

import com.anying.helpdesk.conversation.config.ChatProperties;
import com.anying.helpdesk.modelconfig.crypto.SecretEncryptor;
import com.anying.helpdesk.modelconfig.domain.AiModelConfig;
import com.anying.helpdesk.modelconfig.domain.ModelProtocol;
import com.anying.helpdesk.modelconfig.domain.ModelVendor;
import java.util.Map;
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
    private final DeepSeekThinkingProperties deepSeekThinking;

    /** 返回本工厂支持的兼容协议。 */
    @Override
    public ModelProtocol protocol() {
        return ModelProtocol.OPENAI_COMPATIBLE;
    }

    /** 创建模型客户端，并仅对 DeepSeek 应用部署配置中的思考参数。 */
    @Override
    public ChatModel create(AiModelConfig config) {
        OpenAiCompatibleEndpoint endpoint = endpointResolver.resolve(config.getBaseUrl());
        OpenAiApi openAiApi = createOpenAiApi(config, endpoint);
        var options = OpenAiChatOptions.builder().model(config.getModelName());
        if (config.getVendor() == ModelVendor.DEEPSEEK) {
            options.extraBody(Map.of("thinking", Map.of("type", deepSeekThinking.thinkingType())));
            if ("enabled".equals(deepSeekThinking.thinkingType())) {
                options.reasoningEffort(deepSeekThinking.reasoningEffort());
            }
        }
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options.build())
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
