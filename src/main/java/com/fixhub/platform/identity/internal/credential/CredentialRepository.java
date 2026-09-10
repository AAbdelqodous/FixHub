package com.fixhub.platform.identity.internal.credential;

import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface CredentialRepository extends Repository<Credential, Long> {

    Credential save(Credential credential);

    Optional<Credential> findByAccountIdAndCredentialType(
            Long accountId, CredentialType credentialType);

    boolean existsByAccountIdAndCredentialType(Long accountId, CredentialType credentialType);
}
