package com.fixhub.platform.common.error;

import lombok.Getter;

@Getter
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;

    public ApiException(ErrorCode errorCode) {
        super(errorCode.defaultDetail());
        this.errorCode = errorCode;
    }

    public ApiException(ErrorCode errorCode, String detailedMessage) {
        super(detailedMessage);
        this.errorCode = errorCode;
    }
}
