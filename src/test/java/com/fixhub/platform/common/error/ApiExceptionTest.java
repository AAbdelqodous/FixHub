package com.fixhub.platform.common.error;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

public class ApiExceptionTest {
    @Test
    void usesErrorCodeDefaultDetailWhenCustomDetailIsNotProvided() {
        ErrorCode errorCode = CommonErrorCode.FORBIDDEN;

        ApiException exception = new ApiException(errorCode);

        assertAll(
                () -> assertSame(errorCode, exception.getErrorCode()),
                () -> assertEquals(errorCode.defaultDetail(), exception.getMessage()));
    }

    @Test
    void usesProvidedDetailWithoutChangingErrorCode() {
        ErrorCode errorCode = CommonErrorCode.FORBIDDEN;
        String detail = "This operation is not permitted";

        ApiException exception = new ApiException(errorCode, detail);

        assertAll(
                () -> assertSame(errorCode, exception.getErrorCode()),
                () -> assertEquals(detail, exception.getMessage()));
    }
}
