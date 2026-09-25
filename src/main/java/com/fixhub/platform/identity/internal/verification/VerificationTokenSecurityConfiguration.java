package com.fixhub.platform.identity.internal.verification;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class VerificationTokenSecurityConfiguration {

    @Bean
    VerificationTokenCryptography verificationTokenCryptography() {
        return new VerificationTokenCryptography(new SecureRandomByteSource());
    }
}
