package com.sora.evidence.common;

import org.springframework.http.HttpStatus;

/** 사용자에게 그대로 보여 줄 수 있는 메시지를 담은 업무 예외. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public static ApiException badRequest(String m) {
        return new ApiException(HttpStatus.BAD_REQUEST, m);
    }

    public static ApiException notFound(String m) {
        return new ApiException(HttpStatus.NOT_FOUND, m);
    }

    public static ApiException forbidden(String m) {
        return new ApiException(HttpStatus.FORBIDDEN, m);
    }

    public static ApiException conflict(String m) {
        return new ApiException(HttpStatus.CONFLICT, m);
    }

    public static ApiException unavailable(String m) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, m);
    }
}
