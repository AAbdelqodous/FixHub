package com.fixhub.platform.identity.internal.registration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The bounded, state-independent database work required for an existing registration email. */
@Component
class ExistingAccountRegistrationWork {

    static final String STATEMENT =
            "SELECT transaction_timestamp(), octet_length(decode('"
                    + "0000000000000000000000000000000000000000000000000000000000000000"
                    + "', 'hex'))";

    private final JdbcTemplate jdbcTemplate;

    ExistingAccountRegistrationWork(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void execute() {
        Integer length =
                jdbcTemplate.queryForObject(
                        STATEMENT,
                        (resultSet, rowNumber) -> {
                            resultSet.getTimestamp(1);
                            return resultSet.getInt(2);
                        });
        if (length == null || length != 32) {
            throw new IllegalStateException("Registration equivalent work failed");
        }
    }
}
