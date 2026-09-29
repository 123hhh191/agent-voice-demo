package com.example.agentvoice.common;

import org.springframework.http.HttpStatus;

/** 携带 HTTP 状态和稳定错误码的业务异常。 */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
}
