package com.fixhub.platform.identity.internal.registration;

import com.fixhub.platform.identity.internal.account.Account;
import com.fixhub.platform.identity.internal.account.AccountRepository;
import com.fixhub.platform.identity.internal.credential.CredentialRepository;
import com.fixhub.platform.identity.internal.verification.InitialEmailVerificationTokenPersistence;
import java.util.Arrays;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class TransactionalNewAccountRegistrationWriter implements NewAccountRegistrationWriter {

    private final AccountRepository accountRepository;
    private final CredentialRepository credentialRepository;
    private final InitialEmailVerificationTokenPersistence tokenPersistence;

    TransactionalNewAccountRegistrationWriter(
            AccountRepository accountRepository,
            CredentialRepository credentialRepository,
            InitialEmailVerificationTokenPersistence tokenPersistence) {
        this.accountRepository = accountRepository;
        this.credentialRepository = credentialRepository;
        this.tokenPersistence = tokenPersistence;
    }

    @Override
    @Transactional
    public void persist(RegistrationWrite write) {
        Account account = accountRepository.save(write.account());
        credentialRepository.save(write.credential());

        byte[] tokenDigest = write.tokenDigest();
        try {
            tokenPersistence.saveInitial(account, tokenDigest, write.tokenExpiresAt());
        } finally {
            Arrays.fill(tokenDigest, (byte) 0);
        }
    }
}
