package com.fixhub.platform.identity.internal.account;

import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface AccountRepository extends Repository<Account, Long> {

    Account save(Account account);

    Optional<Account> findById(Long id);

    Optional<Account> findByEmailNormalized(String emailNormalized);

    boolean existsByEmailNormalized(String emailNormalized);

    boolean existsByPhone(String phone);
}
