package dev.gaurang.wallet.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException exception) {
        return respond(exception.code(), exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        String details = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return respond(ErrorCode.INVALID_REQUEST, details);
    }

    /** Covers malformed JSON and, deliberately, a decimal amount where paise are expected. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return respond(ErrorCode.INVALID_REQUEST, "Request body is malformed or has a field of the wrong type");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handlePathMismatch(MethodArgumentTypeMismatchException exception) {
        return respond(ErrorCode.INVALID_REQUEST, "Path parameter '" + exception.getName() + "' is not well formed");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleUnknownPath(NoResourceFoundException exception) {
        return respond(ErrorCode.NOT_FOUND, "No such endpoint");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleWrongMethod(HttpRequestMethodNotSupportedException exception) {
        return respond(ErrorCode.METHOD_NOT_ALLOWED, exception.getMethod() + " is not supported on this endpoint");
    }

    @ExceptionHandler({ConcurrencyFailureException.class, DataAccessResourceFailureException.class})
    public ResponseEntity<ErrorResponse> handleTransientDatabaseFailure(RuntimeException exception) {
        log.warn("database_transient_failure", exception);
        return respond(ErrorCode.TRANSIENT_CONFLICT, "Temporary database contention, retry the same request");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception exception) {
        log.error("unhandled_exception", exception);
        return respond(ErrorCode.INTERNAL_ERROR, "Unexpected server error");
    }

    private ResponseEntity<ErrorResponse> respond(ErrorCode code, String message) {
        ErrorResponse body = new ErrorResponse(code.name(), message, MDC.get(CorrelationIdFilter.MDC_KEY));
        return ResponseEntity.status(code.status()).body(body);
    }
}
