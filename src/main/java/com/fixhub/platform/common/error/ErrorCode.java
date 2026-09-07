package com.fixhub.platform.common.error;

import org.springframework.http.HttpStatus;

public interface ErrorCode {

    String code();

    String defaultDetail();

    HttpStatus status();
}
