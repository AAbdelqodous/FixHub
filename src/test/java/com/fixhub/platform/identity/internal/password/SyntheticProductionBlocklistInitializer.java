package com.fixhub.platform.identity.internal.password;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/** Adds only test-generated production-sized blocklist locations before binding. */
public final class SyntheticProductionBlocklistInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        TestPropertyValues.of(SyntheticProductionBlocklistFixture.propertyValues())
                .applyTo(context.getEnvironment());
    }
}
