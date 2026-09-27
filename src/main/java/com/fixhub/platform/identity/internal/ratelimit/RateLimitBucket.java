package com.fixhub.platform.identity.internal.ratelimit;

import com.fixhub.platform.common.jpa.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "identity_rate_limit_buckets")
class RateLimitBucket extends AuditableEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "policy", nullable = false, length = 32)
    private RateLimitPolicy policy;

    @Column(name = "key_version", nullable = false)
    private int keyVersion;

    @Column(name = "key_digest", nullable = false, columnDefinition = "BYTEA")
    private byte[] keyDigest;

    @Column(name = "window_start", nullable = false)
    private Instant windowStart;

    @Column(name = "window_end", nullable = false)
    private Instant windowEnd;

    @Column(name = "request_count", nullable = false)
    private long requestCount;

    @Column(name = "cooldown_until")
    private Instant cooldownUntil;

    @Column(name = "retention_expires_at", nullable = false)
    private Instant retentionExpiresAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected RateLimitBucket() {}
}
