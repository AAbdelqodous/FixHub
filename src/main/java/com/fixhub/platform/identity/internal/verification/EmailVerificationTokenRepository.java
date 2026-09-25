package com.fixhub.platform.identity.internal.verification;

import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

interface EmailVerificationTokenRepository extends Repository<EmailVerificationToken, Long> {

    EmailVerificationToken save(EmailVerificationToken token);

    @Query(
            "select token from EmailVerificationToken token "
                    + "where token.account.id = :accountId "
                    + "and token.terminalReason is null")
    Optional<EmailVerificationToken> findOpenByAccountId(@Param("accountId") Long accountId);

    @Query(
            "select token from EmailVerificationToken token "
                    + "where token.tokenDigest = :tokenDigest")
    Optional<EmailVerificationToken> findByTokenDigest(@Param("tokenDigest") byte[] tokenDigest);
}
