package org.example.gametopupkh.common.exception;

import java.time.Instant;

import org.example.gametopupkh.common.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> handleApiException(ApiException e) {
        return ResponseEntity.status(e.getStatus())
                .body(ApiResponse.error(e.getCode(), e.getMessage()));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .findFirst()
                .orElse("Validation failed");
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("VALIDATION_FAILED", message));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex,
                                                              Object body,
                                                              HttpHeaders headers,
                                                              HttpStatusCode status,
                                                              WebRequest request) {
        if (body instanceof ApiResponse<?> apiResponse) {
            return new ResponseEntity<>(apiResponse, headers, status);
        }
        if (status.is5xxServerError()) {
            log.error("Unhandled exception at {}", Instant.now(), ex);
        }
        String message = ex.getMessage() == null ? status.toString() : ex.getMessage();
        return new ResponseEntity<>(ApiResponse.error("HTTP_" + status.value(), message), headers, status);
    }
}
