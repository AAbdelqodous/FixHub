package com.fixhub.platform.common.error;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@RequiredArgsConstructor
public enum CommonErrorCode implements ErrorCode {
    VALIDATION_ERROR("VALIDATION_ERROR", "Validation failed", HttpStatus.BAD_REQUEST),
    MALFORMED_REQUEST("MALFORMED_REQUEST", "Request body is malformed", HttpStatus.BAD_REQUEST),
    MISSING_PARAMETER(
            "MISSING_PARAMETER", "Required request parameter is missing", HttpStatus.BAD_REQUEST),
    TYPE_MISMATCH("TYPE_MISMATCH", "Request value has an invalid type", HttpStatus.BAD_REQUEST),
    UNAUTHORIZED("UNAUTHORIZED", "Authentication is required", HttpStatus.UNAUTHORIZED),
    FORBIDDEN("FORBIDDEN", "Access is denied", HttpStatus.FORBIDDEN),
    ENDPOINT_NOT_FOUND("ENDPOINT_NOT_FOUND", "API endpoint was not found", HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED(
            "METHOD_NOT_ALLOWED", "HTTP method is not allowed", HttpStatus.METHOD_NOT_ALLOWED),
    NOT_ACCEPTABLE(
            "NOT_ACCEPTABLE",
            "Requested response representation is not available",
            HttpStatus.NOT_ACCEPTABLE),
    UNSUPPORTED_MEDIA_TYPE(
            "UNSUPPORTED_MEDIA_TYPE",
            "Request media type is not supported",
            HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    INTERNAL_ERROR(
            "INTERNAL_ERROR", "An unexpected error occurred", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final String defaultDetail;
    private final HttpStatus status;

    @Override
    public String code() {
        return code;
    }

    @Override
    public String defaultDetail() {
        return defaultDetail;
    }

    @Override
    public HttpStatus status() {
        return status;
    }
}
