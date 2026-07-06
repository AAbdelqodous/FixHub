package com.fixhub.platform.common.error;

import com.fixhub.platform.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.ControllerAdvice;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

// Needs the real application context: a standalone-MockMvc test wires GlobalExceptionHandler by
// hand, so it can never catch a second, rogue @ControllerAdvice fighting it in the real context.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ControllerAdviceGuardTest {

    private static final String OWN_BASE_PACKAGE = "com.fixhub.platform";

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void exactlyOneOwnControllerAdviceExists() {
        Map<String, Object> advices = applicationContext.getBeansWithAnnotation(ControllerAdvice.class);

        // Third-party starters (e.g. springdoc) register their own @ControllerAdvice for their own
        // concerns; this guard only cares about advices declared in our own codebase competing with
        // GlobalExceptionHandler for the same exception types.
        Set<String> ownAdvices = advices.entrySet().stream()
                .filter(entry -> entry.getValue().getClass().getName().startsWith(OWN_BASE_PACKAGE))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        assertThat(ownAdvices)
                .as("Expected exactly one @ControllerAdvice of ours (GlobalExceptionHandler) but found "
                        + "%s. A second advice makes @ExceptionHandler resolution between advices "
                        + "ambiguous and unpredictable across Spring versions.", ownAdvices)
                .hasSize(1);
    }
}
