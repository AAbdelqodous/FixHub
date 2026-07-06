package com.fixhub.platform.common.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum BusinessErrorCode {
    VALIDATION_ERROR(400, "Invalid", HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(401, "Un authorized", HttpStatus.UNAUTHORIZED),
    FORBIDDEN(403, "Forbidden", HttpStatus.FORBIDDEN),
    RESOURCE_NOT_FOUND(404, "Resource not found", HttpStatus.NOT_FOUND),
    CONFLICT(409, "Conflict", HttpStatus.CONFLICT),
    INTERNAL_ERROR(500, "Internal error", HttpStatus.INTERNAL_SERVER_ERROR);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

}