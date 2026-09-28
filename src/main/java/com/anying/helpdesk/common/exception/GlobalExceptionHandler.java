package com.anying.helpdesk.common.exception;

import com.anying.helpdesk.agent.action.exception.ConcurrentPendingActionUpdateException;
import com.anying.helpdesk.agent.action.exception.InvalidPendingActionStatusException;
import com.anying.helpdesk.agent.action.exception.PendingActionExpiredException;
import com.anying.helpdesk.agent.action.exception.PendingActionNotFoundException;
import com.anying.helpdesk.auth.exception.InvalidCredentialsException;
import com.anying.helpdesk.auth.exception.UsernameAlreadyExistsException;
import com.anying.helpdesk.common.response.ApiResponse;
import com.anying.helpdesk.conversation.exception.ChatGenerationException;
import com.anying.helpdesk.conversation.exception.ConcurrentConversationUpdateException;
import com.anying.helpdesk.conversation.exception.ConversationGenerationInProgressException;
import com.anying.helpdesk.conversation.exception.ConversationNotActiveException;
import com.anying.helpdesk.conversation.exception.ConversationNotFoundException;
import com.anying.helpdesk.conversation.exception.ModelStreamingNotSupportedException;
import com.anying.helpdesk.knowledge.exception.InvalidKnowledgeDocumentException;
import com.anying.helpdesk.knowledge.exception.InvalidKnowledgeRetrievalSettingsException;
import com.anying.helpdesk.knowledge.exception.KnowledgeBaseConflictException;
import com.anying.helpdesk.knowledge.exception.KnowledgeBaseNotFoundException;
import com.anying.helpdesk.knowledge.exception.KnowledgeDocumentNotFoundException;
import com.anying.helpdesk.knowledge.exception.KnowledgeDocumentConflictException;
import com.anying.helpdesk.knowledge.exception.KnowledgeDocumentParsingException;
import com.anying.helpdesk.knowledge.exception.KnowledgeIndexException;
import com.anying.helpdesk.knowledge.exception.KnowledgeRetrievalException;
import com.anying.helpdesk.knowledge.exception.KnowledgeStorageException;
import com.anying.helpdesk.modelconfig.exception.InvalidModelConfigException;
import com.anying.helpdesk.modelconfig.exception.ModelConfigConflictException;
import com.anying.helpdesk.modelconfig.exception.ModelConfigNotFoundException;
import com.anying.helpdesk.ticket.exception.ConcurrentTicketUpdateException;
import com.anying.helpdesk.ticket.exception.InvalidTicketStatusException;
import com.anying.helpdesk.ticket.exception.TicketAccessDeniedException;
import com.anying.helpdesk.ticket.exception.TicketNotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 将知识库或知识文档不存在转换为统一的未找到响应。
     */
    @ExceptionHandler({KnowledgeBaseNotFoundException.class, KnowledgeDocumentNotFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleKnowledgeNotFound(RuntimeException exception) {
        String code = exception instanceof KnowledgeBaseNotFoundException
                ? "KNOWLEDGE_BASE_NOT_FOUND"
                : "KNOWLEDGE_DOCUMENT_NOT_FOUND";
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(code, exception.getMessage(), null));
    }

    /**
     * 将知识库名称冲突或非空删除转换为冲突响应。
     */
    @ExceptionHandler(KnowledgeBaseConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleKnowledgeBaseConflict(KnowledgeBaseConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("KNOWLEDGE_BASE_CONFLICT", exception.getMessage(), null));
    }

    /**
     * 将检索参数之间不合法的大小关系转换为参数错误响应。
     */
    @ExceptionHandler(InvalidKnowledgeRetrievalSettingsException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidKnowledgeRetrievalSettings(
            InvalidKnowledgeRetrievalSettingsException exception
    ) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("INVALID_KNOWLEDGE_RETRIEVAL_SETTINGS", exception.getMessage(), null));
    }

    /**
     * 将文档处理状态或乐观锁冲突转换为可重试的冲突响应。
     */
    @ExceptionHandler(KnowledgeDocumentConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleKnowledgeDocumentConflict(
            KnowledgeDocumentConflictException exception
    ) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("KNOWLEDGE_DOCUMENT_CONFLICT", exception.getMessage(), null));
    }

    /**
     * 将 ES 连接、映射或批量写入故障转换为脱敏的上游服务错误。
     */
    @ExceptionHandler(KnowledgeIndexException.class)
    public ResponseEntity<ApiResponse<Void>> handleKnowledgeIndex(KnowledgeIndexException exception) {
        log.error("Knowledge index operation failed: {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponse.error("KNOWLEDGE_INDEX_FAILED", exception.getMessage(), null));
    }

    /**
     * 将查询向量生成或 ES 检索故障转换为脱敏的上游服务错误。
     */
    @ExceptionHandler(KnowledgeRetrievalException.class)
    public ResponseEntity<ApiResponse<Void>> handleKnowledgeRetrieval(KnowledgeRetrievalException exception) {
        log.error("Knowledge retrieval failed: {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponse.error("KNOWLEDGE_RETRIEVAL_FAILED", exception.getMessage(), null));
    }

    /**
     * 将非法文件及超出上传大小限制转换为参数错误响应。
     */
    @ExceptionHandler({InvalidKnowledgeDocumentException.class, MaxUploadSizeExceededException.class})
    public ResponseEntity<ApiResponse<Void>> handleInvalidKnowledgeDocument(RuntimeException exception) {
        String message = exception instanceof MaxUploadSizeExceededException
                ? "document file must not exceed 20 MB"
                : exception.getMessage();
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("INVALID_KNOWLEDGE_DOCUMENT", message, null));
    }

    /**
     * 将对象存储故障转换为脱敏的上游服务错误响应。
     */
    @ExceptionHandler(KnowledgeStorageException.class)
    public ResponseEntity<ApiResponse<Void>> handleKnowledgeStorage(KnowledgeStorageException exception) {
        log.error("Knowledge object storage operation failed: {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponse.error("KNOWLEDGE_STORAGE_FAILED", exception.getMessage(), null));
    }

    /**
     * 将文档损坏、加密或无可提取文本等解析失败转换为明确响应。
     */
    @ExceptionHandler(KnowledgeDocumentParsingException.class)
    public ResponseEntity<ApiResponse<Void>> handleKnowledgeDocumentParsing(
            KnowledgeDocumentParsingException exception
    ) {
        log.warn("Knowledge document parsing failed: {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiResponse.error("KNOWLEDGE_DOCUMENT_PARSE_FAILED", exception.getMessage(), null));
    }

    /**
     * 将不存在或不属于当前客户的待确认操作转换为统一的未找到响应。
     */
    @ExceptionHandler(PendingActionNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handlePendingActionNotFound(
            PendingActionNotFoundException exception
    ) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("PENDING_ACTION_NOT_FOUND", exception.getMessage(), null));
    }

    /**
     * 将过期、状态冲突和并发修改转换为可供确认卡片处理的冲突响应。
     */
    @ExceptionHandler({
            PendingActionExpiredException.class,
            InvalidPendingActionStatusException.class,
            ConcurrentPendingActionUpdateException.class
    })
    public ResponseEntity<ApiResponse<Void>> handlePendingActionConflict(RuntimeException exception) {
        String code;
        if (exception instanceof PendingActionExpiredException) {
            code = "PENDING_ACTION_EXPIRED";
        } else if (exception instanceof ConcurrentPendingActionUpdateException) {
            code = "CONCURRENT_PENDING_ACTION_UPDATE";
        } else {
            code = "INVALID_PENDING_ACTION_STATUS";
        }
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(code, exception.getMessage(), null));
    }

    @ExceptionHandler(ConversationNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleConversationNotFound(ConversationNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("CONVERSATION_NOT_FOUND", exception.getMessage(), null));
    }

    @ExceptionHandler({
            ConversationNotActiveException.class,
            ConversationGenerationInProgressException.class,
            ConcurrentConversationUpdateException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleConversationConflict(RuntimeException exception) {
        String code;
        if (exception instanceof ConversationNotActiveException) {
            code = "CONVERSATION_NOT_ACTIVE";
        } else if (exception instanceof ConversationGenerationInProgressException) {
            code = "CONVERSATION_GENERATION_IN_PROGRESS";
        } else {
            code = "CONCURRENT_CONVERSATION_UPDATE";
        }
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(code, exception.getMessage(), null));
    }

    @ExceptionHandler(ChatGenerationException.class)
    public ResponseEntity<ApiResponse<Void>> handleChatGeneration(ChatGenerationException exception) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponse.error("CHAT_GENERATION_FAILED", exception.getMessage(), null));
    }

    /**
     * 将默认模型不支持流式输出的情况转换为明确的冲突响应。
     */
    @ExceptionHandler(ModelStreamingNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleModelStreamingNotSupported(
            ModelStreamingNotSupportedException exception
    ) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("MODEL_STREAMING_NOT_SUPPORTED", exception.getMessage(), null));
    }

    @ExceptionHandler(ModelConfigNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleModelConfigNotFound(ModelConfigNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("MODEL_CONFIG_NOT_FOUND", exception.getMessage(), null));
    }

    @ExceptionHandler(InvalidModelConfigException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidModelConfig(InvalidModelConfigException exception) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("INVALID_MODEL_CONFIG", exception.getMessage(), null));
    }

    @ExceptionHandler(ModelConfigConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleModelConfigConflict(ModelConfigConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("MODEL_CONFIG_CONFLICT", exception.getMessage(), null));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidCredentials(InvalidCredentialsException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("INVALID_CREDENTIALS", exception.getMessage(), null));
    }

    @ExceptionHandler(UsernameAlreadyExistsException.class)
    public ResponseEntity<ApiResponse<Void>> handleUsernameAlreadyExists(
            UsernameAlreadyExistsException exception
    ) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("USERNAME_ALREADY_EXISTS", exception.getMessage(), null));
    }

    @ExceptionHandler(TicketNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleTicketNotFound(TicketNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("TICKET_NOT_FOUND", exception.getMessage(), null));
    }

    @ExceptionHandler(TicketAccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleTicketAccessDenied(TicketAccessDeniedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("TICKET_ACCESS_DENIED", exception.getMessage(), null));
    }

    @ExceptionHandler({InvalidTicketStatusException.class, ConcurrentTicketUpdateException.class})
    public ResponseEntity<ApiResponse<Void>> handleTicketConflict(RuntimeException exception) {
        String code = exception instanceof InvalidTicketStatusException
                ? "INVALID_TICKET_STATUS"
                : "CONCURRENT_TICKET_UPDATE";
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(code, exception.getMessage(), null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidation(
            MethodArgumentNotValidException exception
    ) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("VALIDATION_ERROR", "request validation failed", errors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException exception) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("VALIDATION_ERROR", exception.getMessage(), null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception) {
        log.error("Unexpected request failure", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("INTERNAL_ERROR", "unexpected server error", null));
    }
}
