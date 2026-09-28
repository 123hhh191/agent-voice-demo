package com.example.agentvoice.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log=LoggerFactory.getLogger(ApiExceptionHandler.class);
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> known(ApiException ex, HttpServletRequest request) {
        return ResponseEntity.status(ex.status()).body(body(ex.code(), ex.getMessage(), request));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class, HttpMessageNotReadableException.class})
    ResponseEntity<?> invalid(Exception ex, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(body("INVALID_REQUEST", "请求参数无效", request));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled request failure, traceId={}", request.getAttribute("traceId"), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body("INTERNAL_ERROR", "服务暂时不可用", request));
    }
    private Map<String, Object> body(String code, String message, HttpServletRequest request) {
        return Map.of("code", code, "message", message, "traceId", String.valueOf(request.getAttribute("traceId")));
    }
}
