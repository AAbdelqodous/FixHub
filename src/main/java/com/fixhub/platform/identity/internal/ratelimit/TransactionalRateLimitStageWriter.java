package com.fixhub.platform.identity.internal.ratelimit;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class TransactionalRateLimitStageWriter implements RateLimitStageWriter {

    static final String TIMESTAMP_SQL = "SELECT transaction_timestamp()";
    static final String UPSERT_SQL =
            """
            INSERT INTO identity_rate_limit_buckets AS bucket
                (policy, key_version, key_digest, window_start, window_end,
                 request_count, cooldown_until, retention_expires_at, version, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, 1, ?, ?, 0, ?, ?)
            ON CONFLICT (policy, key_version, key_digest, window_start)
            DO UPDATE SET
                request_count = bucket.request_count + 1,
                cooldown_until = EXCLUDED.cooldown_until,
                retention_expires_at =
                    GREATEST(bucket.retention_expires_at, EXCLUDED.retention_expires_at),
                version = bucket.version + 1,
                updated_at = EXCLUDED.updated_at
            WHERE bucket.request_count < ?
                AND (bucket.cooldown_until IS NULL OR ? >= bucket.cooldown_until)
            RETURNING request_count
            """;
    private static final String REJECTION_SQL =
            """
            SELECT request_count, window_end, cooldown_until
            FROM identity_rate_limit_buckets
            WHERE policy = ? AND key_version = ? AND key_digest = ? AND window_start = ?
            """;

    private static final Comparator<Attempt> LOCK_ORDER =
            Comparator.comparing((Attempt item) -> item.bucket.policy.name())
                    .thenComparingInt(item -> item.bucket.keyVersion)
                    .thenComparing(
                            (left, right) ->
                                    Arrays.compareUnsigned(
                                            left.bucket.keyDigest, right.bucket.keyDigest))
                    .thenComparing(item -> item.windowStart);

    private final JdbcTemplate jdbcTemplate;

    TransactionalRateLimitStageWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void execute(
            RateLimitStagePersistence.StageWork work, RateLimitOperationTime operationTime) {
        if (operationTime == null) {
            throw RateLimitOperationTime.invalidStageTime();
        }
        Instant transactionInstant =
                jdbcTemplate.queryForObject(
                        TIMESTAMP_SQL,
                        (resultSet, rowNumber) -> resultSet.getTimestamp(1).toInstant());
        if (transactionInstant == null) {
            throw new IllegalStateException("Rate-limit transaction clock is unavailable");
        }
        operationTime.requireValidStageInstant(transactionInstant);
        List<Attempt> attempts = new ArrayList<>(work.buckets().size());
        for (RateLimitStagePersistence.BucketWork bucket : work.buckets()) {
            attempts.add(new Attempt(bucket, transactionInstant));
        }
        attempts.sort(LOCK_ORDER);

        long longestWait = 0;
        for (Attempt attempt : attempts) {
            if (!upsert(attempt, transactionInstant)) {
                longestWait = Math.max(longestWait, rejectionWait(attempt, transactionInstant));
            }
        }
        if (longestWait > 0) {
            throw new StageRejected(longestWait);
        }
    }

    private boolean upsert(Attempt attempt, Instant transactionInstant) {
        List<Long> returned =
                jdbcTemplate.query(
                        UPSERT_SQL,
                        statement -> {
                            statement.setString(1, attempt.bucket.policy.name());
                            statement.setInt(2, attempt.bucket.keyVersion);
                            statement.setBytes(3, attempt.bucket.keyDigest);
                            statement.setTimestamp(4, Timestamp.from(attempt.windowStart));
                            statement.setTimestamp(5, Timestamp.from(attempt.windowEnd));
                            if (attempt.cooldownUntil == null) {
                                statement.setNull(6, Types.TIMESTAMP_WITH_TIMEZONE);
                            } else {
                                statement.setTimestamp(6, Timestamp.from(attempt.cooldownUntil));
                            }
                            statement.setTimestamp(7, Timestamp.from(attempt.retentionExpiresAt));
                            statement.setTimestamp(8, Timestamp.from(transactionInstant));
                            statement.setTimestamp(9, Timestamp.from(transactionInstant));
                            statement.setLong(10, attempt.bucket.limit);
                            statement.setTimestamp(11, Timestamp.from(transactionInstant));
                        },
                        (resultSet, rowNumber) -> resultSet.getLong(1));
        if (returned.size() > 1) {
            throw new IllegalStateException("Rate-limit stage returned multiple buckets");
        }
        return !returned.isEmpty();
    }

    private long rejectionWait(Attempt attempt, Instant transactionInstant) {
        List<RejectedRow> rows =
                jdbcTemplate.query(
                        REJECTION_SQL,
                        statement -> {
                            statement.setString(1, attempt.bucket.policy.name());
                            statement.setInt(2, attempt.bucket.keyVersion);
                            statement.setBytes(3, attempt.bucket.keyDigest);
                            statement.setTimestamp(4, Timestamp.from(attempt.windowStart));
                        },
                        (resultSet, rowNumber) -> {
                            Timestamp cooldown = resultSet.getTimestamp(3);
                            return new RejectedRow(
                                    resultSet.getLong(1),
                                    resultSet.getTimestamp(2).toInstant(),
                                    cooldown == null ? null : cooldown.toInstant());
                        });
        if (rows.size() != 1) {
            throw new IllegalStateException("Rate-limit rejection row is unavailable");
        }
        RejectedRow row = rows.getFirst();
        long windowWait =
                row.count >= attempt.bucket.limit
                        ? positiveCeilingSeconds(transactionInstant, row.windowEnd)
                        : 0;
        long cooldownWait =
                row.cooldownUntil == null
                        ? 0
                        : positiveCeilingSeconds(transactionInstant, row.cooldownUntil);
        long wait = Math.max(windowWait, cooldownWait);
        if (wait <= 0) {
            throw new IllegalStateException("Rate-limit rejection had no positive boundary");
        }
        return wait;
    }

    private static long positiveCeilingSeconds(Instant now, Instant boundary) {
        Duration difference = Duration.between(now, boundary);
        if (difference.isNegative() || difference.isZero()) {
            return 0;
        }
        return difference.getSeconds() + (difference.getNano() == 0 ? 0 : 1);
    }

    private static final class Attempt {

        final RateLimitStagePersistence.BucketWork bucket;
        final Instant windowStart;
        final Instant windowEnd;
        final Instant cooldownUntil;
        final Instant retentionExpiresAt;

        Attempt(RateLimitStagePersistence.BucketWork bucket, Instant transactionInstant) {
            this.bucket = bucket;
            long windowSeconds = bucket.window.getSeconds();
            long startSeconds =
                    Math.multiplyExact(
                            Math.floorDiv(transactionInstant.getEpochSecond(), windowSeconds),
                            windowSeconds);
            this.windowStart = Instant.ofEpochSecond(startSeconds);
            this.windowEnd = windowStart.plus(bucket.window);
            this.cooldownUntil =
                    bucket.cooldown == null ? null : transactionInstant.plus(bucket.cooldown);
            Instant later =
                    cooldownUntil != null && cooldownUntil.isAfter(windowEnd)
                            ? cooldownUntil
                            : windowEnd;
            this.retentionExpiresAt = later.plus(bucket.retention);
        }
    }

    private static final class RejectedRow {

        final long count;
        final Instant windowEnd;
        final Instant cooldownUntil;

        RejectedRow(long count, Instant windowEnd, Instant cooldownUntil) {
            this.count = count;
            this.windowEnd = windowEnd;
            this.cooldownUntil = cooldownUntil;
        }
    }
}
