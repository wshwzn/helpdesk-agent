package com.anying.helpdesk.common.exception;

import com.anying.helpdesk.auth.exception.InvalidCredentialsException;
import com.anying.helpdesk.auth.exception.UsernameAlreadyExistsException;
import com.anying.helpdesk.common.response.ApiResponse;
import com.anying.helpdesk.conversation.exception.ChatGenerationException;
import com.anying.helpdesk.conversation.exception.ConcurrentConversationUpdateException;
import com.anying.helpdesk.conversation.exception.ConversationNotActiveException;
import com.anying.helpdesk.conversation.exception.ConversationNotFoundException;
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

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ConversationNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleConversationNotFound(ConversationNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("CONVERSATION_NOT_FOUND", exception.getMessage(), null));
    }

    @ExceptionHandler({ConversationNotActiveException.class, ConcurrentConversationUpdateException.class})
    public ResponseEntity<ApiResponse<Void>> handleConversationConflict(RuntimeException exception) {
        String code = exception instanceof ConversationNotActiveException
                ? "CONVERSATION_NOT_ACTIVE"
                : "CONCURRENT_CONVERSATION_UPDATE";
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(code, exception.getMessage(), null));
    }

    @ExceptionHandler(ChatGenerationException.class)
    public ResponseEntity<ApiResponse<Void>> handleChatGeneration(ChatGenerationException exception) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponse.error("CHAT_GENERATION_FAILED", exception.getMessage(), null));
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
