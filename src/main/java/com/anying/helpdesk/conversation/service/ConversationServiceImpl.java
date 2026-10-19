package com.anying.helpdesk.conversation.service;

import com.anying.helpdesk.agent.AgentExecutionContext;
import com.anying.helpdesk.agent.action.dto.PendingActionResponse;
import com.anying.helpdesk.agent.action.service.PendingActionService;
import com.anying.helpdesk.common.response.PageResponse;
import com.anying.helpdesk.conversation.domain.ChatMessage;
import com.anying.helpdesk.conversation.domain.ChatMode;
import com.anying.helpdesk.knowledge.answer.TrustedKnowledgeAnswerService;
import com.anying.helpdesk.knowledge.answer.KnowledgeAnswerFailure;
import com.anying.helpdesk.knowledge.answer.trace.KnowledgeAnswerRunService;
import com.anying.helpdesk.knowledge.answer.trace.KnowledgeAnswerTrace;
import com.anying.helpdesk.knowledge.answer.trace.KnowledgeAnswerRunSnapshot;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
public class ConversationServiceImpl implements ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationServiceImpl.class);
    private static final String MODEL_CALL_FAILED = "MODEL_CALL_FAILED";
    private static final String STREAM_CANCELLED = "STREAM_CANCELLED";
    private static final String KNOWLEDGE_ANSWER_FAILED = "KNOWLEDGE_ANSWER_FAILED";

    private final ConversationPersistenceService persistenceService;
    private final DefaultChatModelProvider defaultChatModelProvider;
    private final ConversationModelClient modelClient;
    private final PendingActionService pendingActionService;
    private final KnowledgeCitationService knowledgeCitationService;
    private final TrustedKnowledgeAnswerService trustedKnowledgeAnswerService;
    private final KnowledgeAnswerRunService knowledgeAnswerRunService;

    @Override
    public ConversationResponse create(CreateConversationRequest request, Long customerId) {
        return persistenceService.create(request, customerId);
    }

    @Override
    public ConversationResponse getById(Long id, Long customerId) {
        return persistenceService.getById(id, customerId);
    }

    /**
     * 删除客户自己的对话，归档后不再允许继续生成回答。
     */
    @Override
    public void delete(Long id, Long customerId) {
        persistenceService.archive(id, customerId);
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

    /** 根据本轮模式完成普通对话或证据约束问答，失败时保留可追踪的消息状态。 */
    @Override
    public ChatExchangeResponse sendMessage(Long id, SendMessageRequest request, Long customerId) {
        persistenceService.validateCanSend(id, customerId);
        ResolvedChatModel model = defaultChatModelProvider.get();
        PreparedChatExchange prepared = persistenceService.prepareExchange(
                id,
                customerId,
                request,
                model
        );

        KnowledgeAnswerTrace trace = request.mode() == ChatMode.KNOWLEDGE ? knowledgeAnswerRunService.start(prepared, model) : null;
        ChatMessage assistantMessage;
        try {
            ChatCompletionResult completion = request.mode() == ChatMode.KNOWLEDGE
                    ? trustedKnowledgeAnswerService.answer(model, request.content().trim(), request.applicabilityScope(), trace)
                    : modelClient.complete(
                    model,
                    prepared.history(),
                    new AgentExecutionContext(customerId, id, prepared.assistantMessageId())
            );
            assistantMessage = trace == null ? persistenceService.completeAssistant(prepared.assistantMessageId(), completion)
                    : trace.stage(KnowledgeAnswerRunSnapshot.Stage.MESSAGE_PERSISTENCE,
                    () -> persistenceService.completeAssistant(prepared.assistantMessageId(), completion));
            if (trace != null) trace.succeeded();
        } catch (RuntimeException exception) {
            if (trace != null) trace.failed(exception);
            markGenerationFailed(prepared.assistantMessageId(), id,
                    request.mode() == ChatMode.KNOWLEDGE ? knowledgeErrorCode(exception) : MODEL_CALL_FAILED, exception);
            throw new ChatGenerationException();
        }
        List<PendingActionResponse> pendingActions = pendingActionService.listPendingByAssistantMessageId(
                prepared.assistantMessageId(),
                customerId
        );
        return new ChatExchangeResponse(
                ChatMessageResponse.from(prepared.userMessage()),
                responseWithCitations(assistantMessage),
                pendingActions
        );
    }

    /**
     * 创建流式回答，按开始、文本增量和完成的顺序返回事件。
     */
    @Override
    public Flux<ChatStreamEvent> streamMessage(Long id, SendMessageRequest request, Long customerId) {
        persistenceService.validateCanSend(id, customerId);
        ResolvedChatModel model = defaultChatModelProvider.get();
        if (request.mode() == ChatMode.AGENT && !model.supportsStreaming()) {
            throw new ModelStreamingNotSupportedException();
        }
        PreparedChatExchange prepared = persistenceService.prepareExchange(
                id,
                customerId,
                request,
                model
        );

        if (request.mode() == ChatMode.KNOWLEDGE) {
            return streamKnowledge(id, request, model, prepared);
        }

        StreamingCompletionAccumulator accumulator = new StreamingCompletionAccumulator();
        AtomicBoolean finalized = new AtomicBoolean(false);
        AgentExecutionContext executionContext = new AgentExecutionContext(
                customerId,
                id,
                prepared.assistantMessageId()
        );
        Flux<ChatStreamEvent> modelEvents = Flux.defer(() -> modelClient.stream(
                        model,
                        prepared.history(),
                        executionContext
                ))
                .doOnNext(accumulator::append)
                .filter(chunk -> chunk.content() != null && !chunk.content().isEmpty())
                .map(chunk -> ChatStreamEvent.delta(chunk.content()));
        Flux<ChatStreamEvent> completionEvents = Mono.fromCallable(() -> {
                    ChatCompletionResult completion = accumulator.toCompletionResult();
                    ChatMessage assistantMessage = persistenceService.completeAssistant(
                            prepared.assistantMessageId(),
                            completion
                    );
                    List<PendingActionResponse> pendingActions =
                            pendingActionService.listPendingByAssistantMessageId(
                                    prepared.assistantMessageId(),
                                    customerId
                            );
                    List<ChatStreamEvent> events = new ArrayList<>();
                    pendingActions.stream()
                            .map(ChatStreamEvent::actionRequired)
                            .forEach(events::add);
                    events.add(ChatStreamEvent.done(responseWithCitations(assistantMessage)));
                    finalized.set(true);
                    return events;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable);

        return Flux.concat(
                        Mono.just(ChatStreamEvent.start(
                                ChatMessageResponse.from(prepared.userMessage()),
                                prepared.assistantMessageId()
                        )),
                        modelEvents,
                        completionEvents
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

    /** 知识问答先核验并保存，再通过 SSE 发送完整正文，禁止草案片段提前泄露。 */
    private Flux<ChatStreamEvent> streamKnowledge(Long id, SendMessageRequest request,
                                                 ResolvedChatModel model, PreparedChatExchange prepared) {
        AtomicBoolean finalized = new AtomicBoolean(false);
        KnowledgeAnswerTrace trace = knowledgeAnswerRunService.start(prepared, model);
        Flux<ChatStreamEvent> completed = Mono.fromCallable(() -> {
                    var completion = trustedKnowledgeAnswerService.answer(
                            model, request.content().trim(), request.applicabilityScope(), trace);
                    ChatMessage assistant;
                    // 取消与落库共用临界区，已取消的生成即使迟到也不能写入完成状态。
                    synchronized (finalized) {
                        if (finalized.get()) {
                            throw new CancellationException("knowledge stream already cancelled");
                        }
                        assistant = trace.stage(KnowledgeAnswerRunSnapshot.Stage.MESSAGE_PERSISTENCE,
                                () -> persistenceService.completeAssistant(prepared.assistantMessageId(), completion));
                        trace.succeeded();
                        finalized.set(true);
                    }
                    return List.of(ChatStreamEvent.delta(completion.content()),
                            ChatStreamEvent.done(ChatMessageResponse.from(assistant)));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable);
        return Flux.concat(Mono.just(ChatStreamEvent.start(ChatMessageResponse.from(prepared.userMessage()),
                        prepared.assistantMessageId())), completed)
                .onErrorResume(exception -> Mono.fromCallable(() -> {
                    trace.failed(exception);
                    markGenerationFailed(prepared.assistantMessageId(), id, knowledgeErrorCode(exception), exception);
                    finalized.set(true);
                    return ChatStreamEvent.error("CHAT_GENERATION_FAILED", ChatGenerationException.USER_MESSAGE);
                }).subscribeOn(Schedulers.boundedElastic()))
                .doOnCancel(() -> {
                    synchronized (finalized) {
                        if (!finalized.get()) trace.cancelled();
                        markCancelledStream(prepared.assistantMessageId(), id, finalized);
                    }
                });
    }

    /** 将知识异常映射为安全的阶段码，未分类错误仍使用兼容的通用码。 */
    private String knowledgeErrorCode(Throwable exception) {
        return exception instanceof KnowledgeAnswerFailure failure ? failure.errorCode() : KNOWLEDGE_ANSWER_FAILED;
    }

    /**
     * 为已完成的 AI 消息附加本轮知识检索产生的引用片段。
     */
    private ChatMessageResponse responseWithCitations(ChatMessage assistantMessage) {
        return ChatMessageResponse.from(
                assistantMessage,
                knowledgeCitationService.listByAssistantMessageId(assistantMessage.getId())
        );
    }

    /**
     * 在流式连接被客户端取消时，异步清理仍处于等待状态的 AI 消息。
     */
    private void markCancelledStream(Long messageId, Long conversationId, AtomicBoolean finalized) {
        synchronized (finalized) {
            if (!finalized.compareAndSet(false, true)) {
                return;
            }
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
        try {
            pendingActionService.invalidateByAssistantMessageId(messageId);
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
