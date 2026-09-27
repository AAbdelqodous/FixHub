package com.fixhub.platform.common.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixhub.platform.common.error.CommonErrorCode;
import com.fixhub.platform.common.error.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest
@AutoConfigureMockMvc
@Import({
    GlobalExceptionHandler.class,
    CorrelationIdFilter.class,
    JsonDeserializationPolicyIntegrationTest.FixtureConfiguration.class
})
class JsonDeserializationPolicyIntegrationTest {

    private static final String FIXTURE_PATH = "/test-only/json-deserialization-policy";
    private static final String CORRELATION_ID = "slice1-json-policy-123";

    @Autowired private MockMvc mockMvc;

    @Test
    void knownFieldsReachTheTestOnlyHandler() throws Exception {
        mockMvc.perform(
                        post(FIXTURE_PATH)
                                .with(user("test-user"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"known\":\"accepted\",\"nested\":{\"known\":\"nested\"}}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.known").value("accepted"));
    }

    @Test
    void unknownRootFieldUsesTheSafeMalformedRequestContract() throws Exception {
        String requestBody =
                "{\"known\":\"accepted\",\"unexpectedField\":\"unknown-submitted-value\"}";

        mockMvc.perform(
                        post(FIXTURE_PATH)
                                .with(user("test-user"))
                                .with(csrf())
                                .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value(CommonErrorCode.MALFORMED_REQUEST.code()))
                .andExpect(
                        jsonPath("$.detail")
                                .value(CommonErrorCode.MALFORMED_REQUEST.defaultDetail()))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION_ID))
                .andExpect(content().string(not(containsString("unexpectedField"))))
                .andExpect(content().string(not(containsString("unknown-submitted-value"))))
                .andExpect(content().string(not(containsString(requestBody))))
                .andExpect(content().string(not(containsString("UnrecognizedPropertyException"))))
                .andExpect(content().string(not(containsString("tools.jackson"))))
                .andExpect(content().string(not(containsString("com.fixhub"))));
    }

    @Test
    void malformedJsonRetainsTheSafeMalformedRequestContract() throws Exception {
        mockMvc.perform(
                        post(FIXTURE_PATH)
                                .with(user("test-user"))
                                .with(csrf())
                                .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"known\":"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID))
                .andExpect(jsonPath("$.code").value(CommonErrorCode.MALFORMED_REQUEST.code()))
                .andExpect(
                        jsonPath("$.detail")
                                .value(CommonErrorCode.MALFORMED_REQUEST.defaultDetail()))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION_ID));
    }

    @Test
    void unknownNestedFieldIsRejectedByTheGlobalPolicy() throws Exception {
        mockMvc.perform(
                        post(FIXTURE_PATH)
                                .with(user("test-user"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"known\":\"accepted\",\"nested\":{\"known\":\"nested\",\"unexpectedNestedField\":true}}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(CommonErrorCode.MALFORMED_REQUEST.code()))
                .andExpect(
                        jsonPath("$.detail")
                                .value(CommonErrorCode.MALFORMED_REQUEST.defaultDetail()))
                .andExpect(content().string(not(containsString("unexpectedNestedField"))));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureConfiguration {

        @Bean
        FixtureController jsonDeserializationPolicyFixtureController() {
            return new FixtureController();
        }
    }

    @RestController
    @RequestMapping(FIXTURE_PATH)
    static class FixtureController {

        @PostMapping
        ResponseEntity<Response> accept(@RequestBody Request request) {
            return ResponseEntity.ok(new Response(request.known()));
        }
    }

    private record Request(String known, Nested nested) {}

    private record Nested(String known) {}

    private record Response(String known) {}
}
