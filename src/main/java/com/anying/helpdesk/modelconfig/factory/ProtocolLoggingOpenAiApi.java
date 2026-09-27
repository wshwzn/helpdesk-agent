package com.anying.helpdesk.modelconfig.factory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.SimpleApiKey;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 记录 OpenAI 兼容聊天协议的请求和响应对象，不接触包含 API Key 的 HTTP 请求头。
 */
final class ProtocolLoggingOpenAiApi extends OpenAiApi {

    private static final Logger log = LoggerFactory.getLogger(ProtocolLoggingOpenAiApi.class);
    private static final String DEFAULT_EMBEDDINGS_PATH = "/v1/embeddings";

    private final ObjectMapper objectMapper;
    private final AtomicLong exchangeSequence = new AtomicLong();

    /**
     * 使用与 Spring AI 默认客户端一致的网络组件创建协议日志客户端。
     */
    ProtocolLoggingOpenAiApi(
            String baseUrl,
            String completionsPath,
            String apiKey,
            ObjectMapper objectMapper
    ) {
        super(
                baseUrl,
                new SimpleApiKey(apiKey),
                new LinkedMultiValueMap<>(),
                completionsPath,
                DEFAULT_EMBEDDINGS_PATH,
                RestClient.builder(),
                WebClient.builder(),
                RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER
        );
        this.objectMapper = objectMapper;
    }

    /**
     * 记录同步聊天请求以及厂商返回的完整 JSON 响应。
     */
    @Override
    public ResponseEntity<ChatCompletion> chatCompletionEntity(
            ChatCompletionRequest request,
            MultiValueMap<String, String> additionalHttpHeaders
    ) {
        long exchangeId = exchangeSequence.incrementAndGet();
        logJson(exchangeId, "REQUEST", request);
        ResponseEntity<ChatCompletion> response = super.chatCompletionEntity(request, additionalHttpHeaders);
        logJson(exchangeId, "RESPONSE", response.getBody());
        return response;
    }

    /**
     * 记录流式聊天请求，并逐条记录厂商返回的 SSE JSON 数据块。
     */
    @Override
    public Flux<ChatCompletionChunk> chatCompletionStream(
            ChatCompletionRequest request,
            MultiValueMap<String, String> additionalHttpHeaders
    ) {
        long exchangeId = exchangeSequence.incrementAndGet();
        logJson(exchangeId, "STREAM_REQUEST", request);
        return super.chatCompletionStream(request, additionalHttpHeaders)
                .doOnNext(chunk -> logJson(exchangeId, "STREAM_CHUNK", chunk))
                .doOnComplete(() -> log.info("[LLM_PROTOCOL][{}][STREAM_END]", exchangeId))
                .doOnError(error -> log.warn(
                        "[LLM_PROTOCOL][{}][STREAM_ERROR] {}",
                        exchangeId,
                        error.getClass().getSimpleName()
                ));
    }

    /**
     * 将协议对象格式化为 JSON；序列化异常时只记录对象类型，不影响正常模型调用。
     */
    private void logJson(long exchangeId, String direction, Object value) {
        try {
            log.info(
                    "[LLM_PROTOCOL][{}][{}]\n{}",
                    exchangeId,
                    direction,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value)
            );
        } catch (JsonProcessingException exception) {
            log.warn(
                    "[LLM_PROTOCOL][{}][{}] JSON serialization failed for {}",
                    exchangeId,
                    direction,
                    value == null ? "null" : value.getClass().getSimpleName()
            );
        }
    }
}
