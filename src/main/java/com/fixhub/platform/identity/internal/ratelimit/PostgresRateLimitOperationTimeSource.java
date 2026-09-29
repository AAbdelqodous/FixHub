package com.fixhub.platform.identity.internal.ratelimit;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** PostgreSQL operation-time acquisition. No runtime request flow calls this facade yet. */
@Component
final class PostgresRateLimitOperationTimeSource {

    private static final String UNAVAILABLE = "Rate-limit operation time is unavailable";

    private final TransactionalRateLimitOperationTimeReader reader;

    PostgresRateLimitOperationTimeSource(TransactionalRateLimitOperationTimeReader reader) {
        this.reader = reader;
    }

    RateLimitOperationTime acquire() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw unavailable();
        }
        try {
            return new RateLimitOperationTime(reader.read());
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException(UNAVAILABLE);
    }

    @Override
    public String toString() {
        return "PostgresRateLimitOperationTimeSource[REDACTED]";
    }
}

/** Invoked through a distinct Spring proxy so commit precedes facade return. */
@Component
class TransactionalRateLimitOperationTimeReader {

    static final String TIMESTAMP_SQL = "SELECT transaction_timestamp()";

    private final JdbcTemplate jdbcTemplate;

    TransactionalRateLimitOperationTimeReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public Instant read() {
        return jdbcTemplate.queryForObject(
                TIMESTAMP_SQL,
                (resultSet, rowNumber) -> {
                    Timestamp value = resultSet.getTimestamp(1);
                    return value == null ? null : value.toInstant();
                });
    }
}
