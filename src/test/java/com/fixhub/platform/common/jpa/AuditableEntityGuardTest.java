package com.fixhub.platform.common.jpa;

import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;

import java.lang.reflect.Field;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AuditableEntityGuardTest {

    @Test
    void createdAtIsAnInstantMarkedCreatedDate() throws NoSuchFieldException {
        Field field = AuditableEntity.class.getDeclaredField("createdAt");
        assertThat(field.getType()).isEqualTo(Instant.class);
        assertThat(field.isAnnotationPresent(CreatedDate.class)).isTrue();
    }

    @Test
    void updatedAtIsAnInstantMarkedLastModifiedDate() throws NoSuchFieldException {
        Field field = AuditableEntity.class.getDeclaredField("updatedAt");
        assertThat(field.getType()).isEqualTo(Instant.class);
        assertThat(field.isAnnotationPresent(LastModifiedDate.class)).isTrue();
    }
}
