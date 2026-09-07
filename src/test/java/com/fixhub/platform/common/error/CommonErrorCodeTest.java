package com.fixhub.platform.common.error;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;

public class CommonErrorCodeTest {

    @ParameterizedTest
    @MethodSource("catalogue")
    void matchesApprovedCatalogue(Expected expected) {
        assertAll(
                () -> assertEquals(expected.code(), expected.errorCode().code()),
                () -> assertEquals(expected.defaultDetail(), expected.errorCode().defaultDetail()),
                () -> assertEquals(expected.status(), expected.errorCode().status()));
    }

    @Test
    void wireCodesAreUnique() {
        CommonErrorCode[] errorCodes = CommonErrorCode.values();

        Set<String> wireCodes =
                Arrays.stream(errorCodes).map(CommonErrorCode::code).collect(Collectors.toSet());

        assertEquals(errorCodes.length, wireCodes.size());
    }

    @Test
    void catalogueCoversEveryDeclaredCommonErrorCode() {
        Set<CommonErrorCode> declared =
                Arrays.stream(CommonErrorCode.values()).collect(Collectors.toSet());

        Set<CommonErrorCode> covered =
                catalogue().map(Expected::errorCode).collect(Collectors.toSet());

        assertEquals(declared, covered);
    }

    private static Stream<Expected> catalogue() {
        return Stream.of(
                new Expected(
                        CommonErrorCode.VALIDATION_ERROR,
                        "VALIDATION_ERROR",
                        "Validation failed",
                        HttpStatus.BAD_REQUEST),
                new Expected(
                        CommonErrorCode.MALFORMED_REQUEST,
                        "MALFORMED_REQUEST",
                        "Request body is malformed",
                        HttpStatus.BAD_REQUEST),
                new Expected(
                        CommonErrorCode.MISSING_PARAMETER,
                        "MISSING_PARAMETER",
                        "Required request parameter is missing",
                        HttpStatus.BAD_REQUEST),
                new Expected(
                        CommonErrorCode.TYPE_MISMATCH,
                        "TYPE_MISMATCH",
                        "Request value has an invalid type",
                        HttpStatus.BAD_REQUEST),
                new Expected(
                        CommonErrorCode.UNAUTHORIZED,
                        "UNAUTHORIZED",
                        "Authentication is required",
                        HttpStatus.UNAUTHORIZED),
                new Expected(
                        CommonErrorCode.FORBIDDEN,
                        "FORBIDDEN",
                        "Access is denied",
                        HttpStatus.FORBIDDEN),
                new Expected(
                        CommonErrorCode.ENDPOINT_NOT_FOUND,
                        "ENDPOINT_NOT_FOUND",
                        "API endpoint was not found",
                        HttpStatus.NOT_FOUND),
                new Expected(
                        CommonErrorCode.METHOD_NOT_ALLOWED,
                        "METHOD_NOT_ALLOWED",
                        "HTTP method is not allowed",
                        HttpStatus.METHOD_NOT_ALLOWED),
                new Expected(
                        CommonErrorCode.NOT_ACCEPTABLE,
                        "NOT_ACCEPTABLE",
                        "Requested response representation is not available",
                        HttpStatus.NOT_ACCEPTABLE),
                new Expected(
                        CommonErrorCode.UNSUPPORTED_MEDIA_TYPE,
                        "UNSUPPORTED_MEDIA_TYPE",
                        "Request media type is not supported",
                        HttpStatus.UNSUPPORTED_MEDIA_TYPE),
                new Expected(
                        CommonErrorCode.INTERNAL_ERROR,
                        "INTERNAL_ERROR",
                        "An unexpected error occurred",
                        HttpStatus.INTERNAL_SERVER_ERROR));
    }

    private record Expected(
            CommonErrorCode errorCode, String code, String defaultDetail, HttpStatus status) {}
}
