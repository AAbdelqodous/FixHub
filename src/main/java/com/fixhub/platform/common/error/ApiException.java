package com.fixhub.platform.common.error;

import lombok.Getter;

@Getter
public class ApiException extends RuntimeException{

    private final BusinessErrorCode errorCode;

    public ApiException(BusinessErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public ApiException(BusinessErrorCode errorCode, String detailedMessage) {
        super(detailedMessage);
        this.errorCode = errorCode;
    }
}