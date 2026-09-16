package com.fixhub.platform.identity.internal.password;

import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdentityPasswordProperties.class)
class PasswordSecurityConfiguration {

    static final String ARGON2ID_IDENTIFIER = "argon2id";

    @Bean
    PasswordEncodingService passwordEncodingService(IdentityPasswordProperties properties) {
        return new PasswordEncodingService(
                createPasswordEncoder(properties),
                new Argon2AdmissionControl(properties.argon2().admission().maxConcurrency()));
    }

    static PasswordEncoder createPasswordEncoder(IdentityPasswordProperties properties) {
        IdentityPasswordProperties.Argon2 argon2 = properties.argon2();
        PasswordEncoder argon2id =
                new Argon2PasswordEncoder(
                        argon2.saltBytes(),
                        argon2.hashBytes(),
                        argon2.parallelism(),
                        argon2.memoryKib(),
                        argon2.iterations());
        return new DelegatingPasswordEncoder(
                ARGON2ID_IDENTIFIER, Map.of(ARGON2ID_IDENTIFIER, argon2id));
    }
}
