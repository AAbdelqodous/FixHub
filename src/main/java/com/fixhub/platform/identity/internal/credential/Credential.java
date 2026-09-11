package com.fixhub.platform.identity.internal.credential;

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
import lombok.Getter;

@Entity
@Table(name = "identity_credentials")
public class Credential extends AuditableEntity {

    private static final int MAX_SECRET_HASH_LENGTH = 512;

    @Getter
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Getter
    @Enumerated(EnumType.STRING)
    @Column(name = "credential_type", nullable = false, length = 32)
    private CredentialType credentialType;

    @Column(name = "secret_hash", nullable = false, length = MAX_SECRET_HASH_LENGTH)
    private String secretHash;

    @Getter
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Credential() {}

    private Credential(Account account, CredentialType credentialType, String secretHash) {
        this.account = requireAccount(account);
        this.credentialType = requireCredentialType(credentialType);
        this.secretHash = requireEncodedSecret(secretHash);
    }

    public static Credential create(
            Account account, CredentialType credentialType, String secretHash) {
        return new Credential(account, credentialType, secretHash);
    }

    public void replaceSecretHash(String secretHash) {
        this.secretHash = requireEncodedSecret(secretHash);
    }

    private static Account requireAccount(Account account) {
        if (account == null) {
            throw new IllegalArgumentException("Account is required");
        }
        return account;
    }

    private static CredentialType requireCredentialType(CredentialType credentialType) {
        if (credentialType == null) {
            throw new IllegalArgumentException("Credential type is required");
        }
        return credentialType;
    }

    private static String requireEncodedSecret(String secretHash) {
        if (secretHash == null || secretHash.length() > MAX_SECRET_HASH_LENGTH) {
            throw new IllegalArgumentException("Encoded secret must use the required format");
        }

        int identifierEnd = secretHash.indexOf('}');
        if (!secretHash.startsWith("{") || identifierEnd <= 1) {
            throw new IllegalArgumentException("Encoded secret must use the required format");
        }

        String identifier = secretHash.substring(1, identifierEnd);
        String encodedValue = secretHash.substring(identifierEnd + 1);
        if (identifier.isBlank() || identifier.indexOf('{') >= 0 || encodedValue.isBlank()) {
            throw new IllegalArgumentException("Encoded secret must use the required format");
        }

        return secretHash;
    }
}
