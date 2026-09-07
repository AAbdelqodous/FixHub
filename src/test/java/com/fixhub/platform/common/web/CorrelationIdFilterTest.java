package com.fixhub.platform.common.web;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.servlet.ServletException;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private CorrelationIdFilter filter;

    @BeforeEach
    void setUp() {
        filter = new CorrelationIdFilter();
    }

    @AfterEach
    void clearMdc() {
        MDC.remove(CorrelationIdFilter.MDC_KEY);
    }

    @Test
    void validSuppliedIdentifierIsPropagatedAndAvailableDuringRequest() throws Exception {
        String suppliedValue = "client.request_123-ABC";
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader(CorrelationIdFilter.HEADER_NAME, suppliedValue);

        filter.doFilter(
                request,
                response,
                (servletRequest, servletResponse) ->
                        assertAll(
                                () ->
                                        assertEquals(
                                                suppliedValue,
                                                servletRequest.getAttribute(
                                                        CorrelationIdFilter.REQUEST_ATTRIBUTE)),
                                () ->
                                        assertEquals(
                                                suppliedValue,
                                                MDC.get(CorrelationIdFilter.MDC_KEY))));

        assertAll(
                () ->
                        assertEquals(
                                suppliedValue, response.getHeader(CorrelationIdFilter.HEADER_NAME)),
                () -> assertNull(MDC.get(CorrelationIdFilter.MDC_KEY)));
    }

    @Test
    void missingIdentifierIsReplacedWithGeneratedUuid() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {});

        String effectiveValue = response.getHeader(CorrelationIdFilter.HEADER_NAME);

        assertAll(
                () -> assertNotNull(effectiveValue),
                () ->
                        assertEquals(
                                effectiveValue,
                                request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE)),
                () -> assertDoesNotThrow(() -> UUID.fromString(effectiveValue)),
                () -> assertNull(MDC.get(CorrelationIdFilter.MDC_KEY)));
    }

    @ParameterizedTest
    @MethodSource("invalidCorrelationIds")
    void invalidIdentifierIsReplacedWithoutBeingReflected(String invalidValue) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader(CorrelationIdFilter.HEADER_NAME, invalidValue);

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {});

        String effectiveValue = response.getHeader(CorrelationIdFilter.HEADER_NAME);

        assertAll(
                () -> assertNotNull(effectiveValue),
                () -> assertNotEquals(invalidValue, effectiveValue),
                () ->
                        assertEquals(
                                effectiveValue,
                                request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE)),
                () -> assertDoesNotThrow(() -> UUID.fromString(effectiveValue)),
                () -> assertNull(MDC.get(CorrelationIdFilter.MDC_KEY)));
    }

    @Test
    void mdcIsClearedWhenDownstreamProcessingThrows() {
        String suppliedValue = "request-123";
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader(CorrelationIdFilter.HEADER_NAME, suppliedValue);

        ServletException exception =
                assertThrows(
                        ServletException.class,
                        () ->
                                filter.doFilter(
                                        request,
                                        response,
                                        (servletRequest, servletResponse) -> {
                                            assertEquals(
                                                    suppliedValue,
                                                    MDC.get(CorrelationIdFilter.MDC_KEY));
                                            throw new ServletException("downstream failure");
                                        }));

        assertAll(
                () -> assertEquals("downstream failure", exception.getMessage()),
                () -> assertNull(MDC.get(CorrelationIdFilter.MDC_KEY)));
    }

    private static Stream<String> invalidCorrelationIds() {
        return Stream.of("", "contains spaces", "contains/slash", "معرف", "a".repeat(65));
    }
}
