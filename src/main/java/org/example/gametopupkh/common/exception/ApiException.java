package org.example.gametopupkh.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Business error that carries the HTTP status and result code to report back.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
