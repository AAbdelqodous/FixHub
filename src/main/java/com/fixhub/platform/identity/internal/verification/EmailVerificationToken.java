package com.fixhub.platform.identity.internal.verification;

import com.fixhub.platform.common.jpa.AuditableEntity;
import com.fixhub.platform.identity.internal.account.Account;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "identity_email_verification_tokens")
class EmailVerificationToken extends AuditableEntity {

    private static final int DIGEST_LENGTH = 32;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(name = "token_digest", nullable = false, columnDefinition = "BYTEA")
    private byte[] tokenDigest;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "terminal_reason", length = 16)
    private EmailVerificationTokenTerminalReason terminalReason;

    @Column(name = "terminal_at")
    private Instant terminalAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected EmailVerificationToken() {}

    private EmailVerificationToken(Account account, byte[] tokenDigest, Instant expiresAt) {
        this.account = requireAccount(account);
        this.tokenDigest = requireDigest(tokenDigest);
        this.expiresAt = requireExpiresAt(expiresAt);
    }

    static EmailVerificationToken create(Account account, byte[] tokenDigest, Instant expiresAt) {
        return new EmailVerificationToken(account, tokenDigest, expiresAt);
    }

    void consume(Instant terminalAt) {
        terminalize(EmailVerificationTokenTerminalReason.CONSUMED, terminalAt);
    }

    void supersede(Instant terminalAt) {
        terminalize(EmailVerificationTokenTerminalReason.SUPERSEDED, terminalAt);
    }

    void expire(Instant terminalAt) {
        terminalize(EmailVerificationTokenTerminalReason.EXPIRED, terminalAt);
    }

    void invalidate(Instant terminalAt) {
        terminalize(EmailVerificationTokenTerminalReason.INVALIDATED, terminalAt);
    }

    boolean isOpen() {
        return terminalReason == null && terminalAt == null;
    }

    EmailVerificationTokenTerminalReason terminalReason() {
        return terminalReason;
    }

    Instant terminalAt() {
        return terminalAt;
    }

    private void terminalize(
            EmailVerificationTokenTerminalReason reason, Instant effectiveTerminalAt) {
        if (effectiveTerminalAt == null) {
            throw new IllegalArgumentException("Terminal timestamp is required");
        }
        if (!isOpen()) {
            throw new IllegalStateException("Email verification token is already terminal");
        }
        terminalReason = reason;
        terminalAt = effectiveTerminalAt;
    }

    private static Account requireAccount(Account account) {
        if (account == null) {
            throw new IllegalArgumentException("Account is required");
        }
        return account;
    }

    private static byte[] requireDigest(byte[] tokenDigest) {
        if (tokenDigest == null) {
            throw new IllegalArgumentException("Token digest is required");
        }
        if (tokenDigest.length != DIGEST_LENGTH) {
            throw new IllegalArgumentException("Token digest must be exactly 32 bytes");
        }
        return tokenDigest.clone();
    }

    private static Instant requireExpiresAt(Instant expiresAt) {
        if (expiresAt == null) {
            throw new IllegalArgumentException("Expiry timestamp is required");
        }
        return expiresAt;
    }
}
