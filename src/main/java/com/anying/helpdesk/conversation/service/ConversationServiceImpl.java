package com.anying.helpdesk.conversation.service;

import com.anying.helpdesk.common.response.PageResponse;
import com.anying.helpdesk.conversation.domain.ChatMessage;
import com.anying.helpdesk.conversation.domain.ConversationStatus;
import com.anying.helpdesk.conversation.dto.ChatExchangeResponse;
import com.anying.helpdesk.conversation.dto.ChatMessageResponse;
import com.anying.helpdesk.conversation.dto.ChatStreamEvent;
import com.anying.helpdesk.conversation.dto.ConversationResponse;
import com.anying.helpdesk.conversation.dto.CreateConversationRequest;
import com.anying.helpdesk.conversation.dto.SendMessageRequest;
import com.anying.helpdesk.conversation.exception.ChatGenerationException;
import com.anying.helpdesk.conversation.exception.ModelStreamingNotSupportedException;
import com.anying.helpdesk.conversation.model.ChatCompletionResult;
import com.anying.helpdesk.conversation.model.ChatStreamChunk;
import com.anying.helpdesk.conversation.model.ConversationModelClient;
import com.anying.helpdesk.modelconfig.factory.DefaultChatModelProvider;
import com.anying.helpdesk.modelconfig.factory.ResolvedChatModel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
public class ConversationServiceImpl implements ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationServiceImpl.class);
    private static final String MODEL_CALL_FAILED = "MODEL_CALL_FAILED";
    private static final String STREAM_CANCELLED = "STREAM_CANCELLED";

    private final ConversationPersistenceService persistenceService;
    private final DefaultChatModelProvider defaultChatModelProvider;
    private final ConversationModelClient modelClient;

    @Override
    public ConversationResponse create(CreateConversationRequest request, Long customerId) {
        return persistenceService.create(request, customerId);
    }

    @Override
    public ConversationResponse getById(Long id, Long customerId) {
        return persistenceService.getById(id, customerId);
    }

    @Override
    public PageResponse<ConversationResponse> page(
            long page,
            long size,
            Long customerId,
            ConversationStatus status
    ) {
        return persistenceService.page(page, size, customerId, status);
    }

    @Override
    public PageResponse<ChatMessageResponse> pageMessages(
            long id,
            long page,
            long size,
            Long customerId
    ) {
        return persistenceService.pageMessages(id, page, size, customerId);
    }

    @Override
    public ChatExchangeResponse sendMessage(Long id, SendMessageRequest request, Long customerId) {
        persistenceService.validateCanSend(id, customerId);
        ResolvedChatModel model = defaultChatModelProvider.get();
        PreparedChatExchange prepared = persistenceService.prepareExchange(
                id,
                customerId,
                request.content(),
                model
        );

        ChatCompletionResult completion;
        try {
            completion = modelClient.complete(model, prepared.history());
        } catch (RuntimeException exception) {
            markGenerationFailed(prepared.assistantMessageId(), id, MODEL_CALL_FAILED, exception);
            throw new ChatGenerationException();
        }

        ChatMessage assistantMessage = persistenceService.completeAssistant(
                prepared.assistantMessageId(),
                completion
        );
        return new ChatExchangeResponse(
                ChatMessageResponse.from(prepared.userMessage()),
                ChatMessageResponse.from(assistantMessage)
        );
    }

    /**
     * 创建流式回答，按开始、文本增量和完成的顺序返回事件。
     */
    @Override
    public Flux<ChatStreamEvent> streamMessage(Long id, SendMessageRequest request, Long customerId) {
        persistenceService.validateCanSend(id, customerId);
        ResolvedChatModel model = defaultChatModelProvider.get();
        if (!model.supportsStreaming()) {
            throw new ModelStreamingNotSupportedException();
        }
        PreparedChatExchange prepared = persistenceService.prepareExchange(
                id,
                customerId,
                request.content(),
                model
        );

        StreamingCompletionAccumulator accumulator = new StreamingCompletionAccumulator();
        AtomicBoolean finalized = new AtomicBoolean(false);
        Flux<ChatStreamEvent> modelEvents = Flux.defer(() -> modelClient.stream(model, prepared.history()))
                .doOnNext(accumulator::append)
                .filter(chunk -> chunk.content() != null && !chunk.content().isEmpty())
                .map(chunk -> ChatStreamEvent.delta(chunk.content()));
        Mono<ChatStreamEvent> completionEvent = Mono.fromCallable(() -> {
                    ChatMessage assistantMessage = persistenceService.completeAssistant(
                            prepared.assistantMessageId(),
                            accumulator.toCompletionResult()
                    );
                    finalized.set(true);
                    return ChatStreamEvent.done(ChatMessageResponse.from(assistantMessage));
                })
                .subscribeOn(Schedulers.boundedElastic());

        return Flux.concat(
                        Mono.just(ChatStreamEvent.start(
                                ChatMessageResponse.from(prepared.userMessage()),
                                prepared.assistantMessageId()
                        )),
                        modelEvents,
                        completionEvent
                )
                .onErrorResume(exception -> Mono.fromCallable(() -> {
                            markGenerationFailed(
                                    prepared.assistantMessageId(),
                                    id,
                                    MODEL_CALL_FAILED,
                                    exception
                            );
                            finalized.set(true);
                            return ChatStreamEvent.error(
                                    "CHAT_GENERATION_FAILED",
                                    ChatGenerationException.USER_MESSAGE
                            );
                        })
                        .subscribeOn(Schedulers.boundedElastic()))
                .doOnCancel(() -> markCancelledStream(prepared.assistantMessageId(), id, finalized));
    }

    /**
     * 在流式连接被客户端取消时，异步清理仍处于等待状态的 AI 消息。
     */
    private void markCancelledStream(Long messageId, Long conversationId, AtomicBoolean finalized) {
        if (!finalized.compareAndSet(false, true)) {
            return;
        }
        Schedulers.boundedElastic().schedule(() -> markGenerationFailed(
                messageId,
                conversationId,
                STREAM_CANCELLED,
                new CancellationException("client cancelled streaming response")
        ));
    }

    /**
     * 将等待中的 AI 消息标记为失败，同时保证厂商异常不会返回给客户端。
     */
    private void markGenerationFailed(
            Long messageId,
            Long conversationId,
            String errorCode,
            Throwable cause
    ) {
        try {
            persistenceService.failAssistant(messageId, errorCode);
        } catch (RuntimeException persistenceException) {
            cause.addSuppressed(persistenceException);
        }
        log.warn(
                "AI response generation failed for conversation {} ({})",
                conversationId,
                cause.getClass().getSimpleName()
        );
    }

    private static final class StreamingCompletionAccumulator {

        private final StringBuilder content = new StringBuilder();
        private Integer promptTokens;
        private Integer completionTokens;
        private Integer totalTokens;

        /**
         * 累加模型返回的文本，并保留最后一次出现的 Token 用量。
         */
        private void append(ChatStreamChunk chunk) {
            if (chunk.content() != null) {
                content.append(chunk.content());
            }
            if (chunk.promptTokens() != null) {
                promptTokens = chunk.promptTokens();
            }
            if (chunk.completionTokens() != null) {
                completionTokens = chunk.completionTokens();
            }
            if (chunk.totalTokens() != null) {
                totalTokens = chunk.totalTokens();
            }
        }

        /**
         * 将全部流式数据合并为可持久化的完整模型回答。
         */
        private ChatCompletionResult toCompletionResult() {
            String completedContent = content.toString();
            if (completedContent.isBlank()) {
                throw new IllegalStateException("model returned empty content");
            }
            return new ChatCompletionResult(
                    completedContent,
                    promptTokens,
                    completionTokens,
                    totalTokens
            );
        }
    }
}
