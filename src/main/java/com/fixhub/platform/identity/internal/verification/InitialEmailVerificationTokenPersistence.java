package com.fixhub.platform.identity.internal.verification;

import com.fixhub.platform.identity.internal.account.Account;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public final class InitialEmailVerificationTokenPersistence {

    private final EmailVerificationTokenRepository tokenRepository;

    InitialEmailVerificationTokenPersistence(EmailVerificationTokenRepository tokenRepository) {
        this.tokenRepository = tokenRepository;
    }

    public void saveInitial(Account account, byte[] tokenDigest, Instant expiresAt) {
        tokenRepository.save(EmailVerificationToken.create(account, tokenDigest, expiresAt));
    }
}
