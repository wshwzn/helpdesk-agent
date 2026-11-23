package com.anying.helpdesk.conversation.service;

import com.anying.helpdesk.knowledge.answer.KnowledgeConversationContext;

import com.anying.helpdesk.agent.action.service.PendingActionService;
import com.anying.helpdesk.common.response.PageResponse;
import com.anying.helpdesk.conversation.domain.ChatMessage;
import com.anying.helpdesk.knowledge.answer.TrustedKnowledgeAnswerService;
import com.anying.helpdesk.knowledge.answer.KnowledgeAnswerModelClient;
import com.anying.helpdesk.knowledge.answer.KnowledgeAnswerInput;
import com.anying.helpdesk.knowledge.answer.KnowledgeAnswerReport;
import com.anying.helpdesk.knowledge.answer.KnowledgeAnswerDraft;
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
import com.anying.helpdesk.conversation.model.ChatCompletionResult;
import com.anying.helpdesk.modelconfig.factory.DefaultChatModelProvider;
import com.anying.helpdesk.modelconfig.factory.ResolvedChatModel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
public class ConversationServiceImpl implements ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationServiceImpl.class);
    private static final String STREAM_CANCELLED = "STREAM_CANCELLED";
    private static final String KNOWLEDGE_ANSWER_FAILED = "KNOWLEDGE_ANSWER_FAILED";

    private final ConversationPersistenceService persistenceService;
    private final DefaultChatModelProvider defaultChatModelProvider;
    private final PendingActionService pendingActionService;
    private final TrustedKnowledgeAnswerService trustedKnowledgeAnswerService;
    private final KnowledgeAnswerRunService knowledgeAnswerRunService;
    private final KnowledgeAnswerModelClient knowledgeModelClient;

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

    /** 所有新消息共用可信问答服务，失败时保留诊断，不能回退到旧自由回答。 */
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

        KnowledgeAnswerTrace trace = knowledgeAnswerRunService.start(prepared, model);
        ChatMessage assistantMessage;
        try {
            ChatCompletionResult completion = answerKnowledge(model, prepared, trace);
            assistantMessage = trace.stage(KnowledgeAnswerRunSnapshot.Stage.MESSAGE_PERSISTENCE,
                    () -> persistenceService.completeAssistant(prepared.assistantMessageId(), completion));
            trace.succeeded();
        } catch (RuntimeException exception) {
            trace.failed(exception);
            markGenerationFailed(prepared.assistantMessageId(), id, knowledgeErrorCode(exception), exception);
            throw new ChatGenerationException();
        }
        return new ChatExchangeResponse(
                ChatMessageResponse.from(prepared.userMessage()),
                ChatMessageResponse.from(assistantMessage),
                List.of()
        );
    }

    /**
     * 创建流式回答，按开始、文本增量和完成的顺序返回事件。
     */
    @Override
    public Flux<ChatStreamEvent> streamMessage(Long id, SendMessageRequest request, Long customerId) {
        persistenceService.validateCanSend(id, customerId);
        ResolvedChatModel model = defaultChatModelProvider.get();
        PreparedChatExchange prepared = persistenceService.prepareExchange(id, customerId, request, model);
        return streamKnowledge(id, model, prepared);
    }

    /** 知识问答先核验并保存，再通过 SSE 发送完整正文，禁止草案片段提前泄露。 */
    private Flux<ChatStreamEvent> streamKnowledge(Long id,
                                                 ResolvedChatModel model, PreparedChatExchange prepared) {
        AtomicBoolean finalized = new AtomicBoolean(false);
        KnowledgeAnswerTrace trace = knowledgeAnswerRunService.start(prepared, model);
        Flux<ChatStreamEvent> completed = Flux.create(sink -> {
            trace.onStage(stage -> sink.next(ChatStreamEvent.progress(prepared.assistantMessageId(), stage)));
            var generation = Schedulers.boundedElastic().schedule(() -> {
                if (sink.isCancelled()) return;
                try {
                    var completion = answerKnowledge(model, prepared, trace);
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
                    sink.next(ChatStreamEvent.delta(completion.content()));
                    sink.next(ChatStreamEvent.done(ChatMessageResponse.from(assistant)));
                    sink.complete();
                } catch (RuntimeException exception) {
                    // 取消后的迟到异常没有接收方，不再发送错误或改写取消结果。
                    if (!sink.isCancelled()) sink.error(exception);
                }
            });
            sink.onDispose(generation);
        });
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

    /** 普通追问先补全问题；范围卡片续答直接使用服务端恢复的原问题。 */
    private ChatCompletionResult answerKnowledge(ResolvedChatModel model, PreparedChatExchange prepared,
                                                KnowledgeAnswerTrace trace) {
        var input = prepared.knowledgeInput();
        var history = input.continuationOfMessageId() == null ? persistenceService.recentContext(prepared) : List.<KnowledgeConversationContext.Message>of();
        KnowledgeAnswerModelClient.ModelResult<KnowledgeConversationContext> context = null;
        if (!history.isEmpty()) {
            var original = input;
            context = trace.stage(KnowledgeAnswerRunSnapshot.Stage.CONTEXT, () -> {
                var resolved = knowledgeModelClient.contextualize(model, original.question(), original.scopes(), history);
                trace.contextualized(resolved);
                return resolved;
            });
            if (context.value().clarification() != null) {
                String question = context.value().clarification();
                var draft = new KnowledgeAnswerDraft(KnowledgeAnswerDraft.Status.NEEDS_CLARIFICATION,
                        List.of(), List.of(question), List.of());
                var report = new KnowledgeAnswerReport(3, KnowledgeAnswerReport.Reason.CONTEXT_REQUIRED,
                        null, null, input.scope(), java.time.LocalDateTime.now(), draft, List.of(), null, null, input.scopes());
                trace.answerReason(report.reason());
                trace.resolved(draft);
                return new ChatCompletionResult(question, context.promptTokens(), context.completionTokens(), context.totalTokens(), report);
            }
            input = new KnowledgeAnswerInput(context.value().question(), input.scope(), input.knowledgeBaseId(),
                    null, input.policyRevision(), input.scopes());
        }
        var result = input.continuationOfMessageId() == null && input.scopes().size() == 1
                ? trustedKnowledgeAnswerService.answer(model, input.question(), input.scope(), trace)
                : trustedKnowledgeAnswerService.answer(model, input, trace);
        return context == null ? result : new ChatCompletionResult(result.content(),
                sumTokens(result.promptTokens(), context.promptTokens()),
                sumTokens(result.completionTokens(), context.completionTokens()),
                sumTokens(result.totalTokens(), context.totalTokens()), result.knowledgeAnswer());
    }

    /** 未知用量保持未知，避免上下文调用未上报时低估本轮成本。 */
    private Integer sumTokens(Integer answer, Integer context) {
        return answer == null || context == null ? null : answer + context;
    }

    /** 将知识异常映射为安全的阶段码，未分类错误仍使用兼容的通用码。 */
    private String knowledgeErrorCode(Throwable exception) {
        return exception instanceof KnowledgeAnswerFailure failure ? failure.errorCode() : KNOWLEDGE_ANSWER_FAILED;
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

}
