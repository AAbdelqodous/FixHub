package com.fixhub.platform.common.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixhub.platform.common.web.CorrelationIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc =
                MockMvcBuilders.standaloneSetup(new TestController())
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .addFilters(new CorrelationIdFilter())
                        .build();
    }

    @Test
    void apiExceptionIsMappedToItsErrorCode() throws Exception {
        String correlationId = "test-api-exception-123";

        mockMvc.perform(
                        get("/test/api-exception")
                                .header(CorrelationIdFilter.HEADER_NAME, correlationId))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(CorrelationIdFilter.HEADER_NAME, correlationId))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("no such thing"))
                .andExpect(jsonPath("$.code").value(TestErrorCode.THING_NOT_FOUND.code()));
    }

    @Test
    void unhandledExceptionFallsBackToGenericInternalError() throws Exception {
        mockMvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.code").value(CommonErrorCode.INTERNAL_ERROR.code()));
    }

    @Test
    void methodArgumentNotValidListsFieldErrors() throws Exception {
        mockMvc.perform(
                        post("/test/validate")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(CommonErrorCode.VALIDATION_ERROR.code()))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].message").exists());
    }

    @Test
    void handlerMethodValidationListsParameterErrors() throws Exception {
        mockMvc.perform(get("/test/validate-parameter").param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(CommonErrorCode.VALIDATION_ERROR.code()))
                .andExpect(jsonPath("$.errors[0].field").value("page"))
                .andExpect(jsonPath("$.errors[0].message").exists());
    }

    @Test
    void successfulResponseReturnsSuppliedCorrelationId() throws Exception {
        String correlationId = "client-request-123";

        mockMvc.perform(
                        get("/test/representation")
                                .header(CorrelationIdFilter.HEADER_NAME, correlationId))
                .andExpect(status().isOk())
                .andExpect(header().string(CorrelationIdFilter.HEADER_NAME, correlationId));
    }

    @RestController
    @RequestMapping("/test")
    private static class TestController {

        @GetMapping("/api-exception")
        void apiException() {
            throw new ApiException(TestErrorCode.THING_NOT_FOUND, "no such thing");
        }

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("boom");
        }

        @PostMapping("/validate")
        void validate(@Valid @RequestBody Payload payload) {}

        @GetMapping("/validate-parameter")
        void validateParameter(@RequestParam(name = "page") @Min(1) int page) {}

        @GetMapping(value = "/representation", produces = MediaType.APPLICATION_JSON_VALUE)
        Payload representation() {
            return new Payload("ok");
        }
    }

    private enum TestErrorCode implements ErrorCode {
        THING_NOT_FOUND("TEST_THING_NOT_FOUND", "Test thing was not found", HttpStatus.NOT_FOUND);

        private final String code;
        private final String defaultDetail;
        private final HttpStatus status;

        TestErrorCode(String code, String defaultDetail, HttpStatus status) {
            this.code = code;
            this.defaultDetail = defaultDetail;
            this.status = status;
        }

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

    private record Payload(@NotBlank String name) {}

    @Test
    void malformedRequestBodyUsesStableErrorCode() throws Exception {
        String correlationId = "test-malformed-request-123";

        mockMvc.perform(
                        post("/test/validate")
                                .header(CorrelationIdFilter.HEADER_NAME, correlationId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(CorrelationIdFilter.HEADER_NAME, correlationId))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.instance").value("/test/validate"))
                .andExpect(
                        jsonPath("$.detail")
                                .value(CommonErrorCode.MALFORMED_REQUEST.defaultDetail()))
                .andExpect(jsonPath("$.code").value(CommonErrorCode.MALFORMED_REQUEST.code()))
                .andExpect(jsonPath("$.correlationId").value(correlationId));
    }

    @Test
    void missingRequiredRequestParameterUsesStableErrorCode() throws Exception {
        mockMvc.perform(get("/test/validate-parameter"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(
                        jsonPath("$.detail")
                                .value(CommonErrorCode.MISSING_PARAMETER.defaultDetail()))
                .andExpect(jsonPath("$.code").value(CommonErrorCode.MISSING_PARAMETER.code()));
    }

    @Test
    void requestParameterTypeMismatchUsesStableErrorCode() throws Exception {
        mockMvc.perform(get("/test/validate-parameter").param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(
                        jsonPath("$.detail").value(CommonErrorCode.TYPE_MISMATCH.defaultDetail()))
                .andExpect(jsonPath("$.code").value(CommonErrorCode.TYPE_MISMATCH.code()));
    }

    @Test
    void unsupportedHttpMethodUsesStableErrorCode() throws Exception {
        mockMvc.perform(post("/test/validate-parameter").param("page", "1"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(
                        jsonPath("$.detail")
                                .value(CommonErrorCode.METHOD_NOT_ALLOWED.defaultDetail()))
                .andExpect(jsonPath("$.code").value(CommonErrorCode.METHOD_NOT_ALLOWED.code()));
    }

    @Test
    void unsupportedRequestMediaTypeUsesStableErrorCode() throws Exception {
        mockMvc.perform(
                        post("/test/validate")
                                .contentType(MediaType.TEXT_PLAIN)
                                .content("name=test"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(
                        jsonPath("$.detail")
                                .value(CommonErrorCode.UNSUPPORTED_MEDIA_TYPE.defaultDetail()))
                .andExpect(jsonPath("$.code").value(CommonErrorCode.UNSUPPORTED_MEDIA_TYPE.code()));
    }

    @Test
    void unacceptableResponseMediaTypeUsesStableErrorCode() throws Exception {
        mockMvc.perform(get("/test/representation").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(406))
                .andExpect(
                        jsonPath("$.detail").value(CommonErrorCode.NOT_ACCEPTABLE.defaultDetail()))
                .andExpect(jsonPath("$.code").value(CommonErrorCode.NOT_ACCEPTABLE.code()));
    }

    @Test
    void unknownEndpointUsesStableErrorCode() throws Exception {
        mockMvc.perform(get("/test/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(
                        jsonPath("$.detail")
                                .value(CommonErrorCode.ENDPOINT_NOT_FOUND.defaultDetail()))
                .andExpect(jsonPath("$.code").value(CommonErrorCode.ENDPOINT_NOT_FOUND.code()));
    }
}
