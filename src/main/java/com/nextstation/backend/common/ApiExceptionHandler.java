package com.nextstation.backend.common;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
    public record ErrorResponse(String code, String message, Map<String, String> fields, Instant timestamp) {}

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
        return response(ex.getStatus(), ex.getCode(), ex.getMessage(), Map.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new TreeMap<>();
        ex.getBindingResult().getFieldErrors().forEach(e -> fields.put(e.getField(), e.getDefaultMessage()));
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "입력값을 확인해 주세요.", fields);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorResponse> handleMalformed(Exception ex) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "JSON, UUID, 시각 또는 필수 헤더가 올바르지 않습니다.", Map.of());
    }

    @ExceptionHandler({DataIntegrityViolationException.class, PessimisticLockingFailureException.class})
    ResponseEntity<ErrorResponse> handleConflict(Exception ex) {
        return response(HttpStatus.CONFLICT, "RESOURCE_CONFLICT", "중복 구독 또는 동시에 처리 중인 요청입니다. 다시 확인해 주세요.", Map.of());
    }

    private ResponseEntity<ErrorResponse> response(HttpStatus status, String code, String message,
                                                  Map<String, String> fields) {
        return ResponseEntity.status(status).body(new ErrorResponse(code, message, fields, Instant.now()));
    }
}
