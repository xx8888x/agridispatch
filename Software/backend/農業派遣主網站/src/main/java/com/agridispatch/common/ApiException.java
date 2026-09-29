package com.agridispatch.common;

import org.springframework.http.HttpStatus;

/**
 * 業務例外：由 GlobalExceptionHandler 轉為 {success:false, error:...} 與對應 HTTP 狀態碼
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() { return status; }

    public static ApiException badRequest(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }

    public static ApiException notFound(String message) { return new ApiException(HttpStatus.NOT_FOUND, message); }

    public static ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT, message); }

    public static ApiException forbidden() { return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN"); }

    public static ApiException unauthorized() { return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED"); }
}
