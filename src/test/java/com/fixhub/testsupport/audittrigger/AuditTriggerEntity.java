package com.fixhub.testsupport.audittrigger;

import com.fixhub.platform.common.jpa.AuditableEntity;
import jakarta.persistence.Entity;

// Throwaway fixture used only by AuditableEntityContractTest to trigger AuditableEntity's
// persistence lifecycle. Lives outside com.fixhub.platform so the default entity scan used by
// every other @SpringBootTest never sees it (and never needs its own Flyway migration).
@Entity
public class AuditTriggerEntity extends AuditableEntity {

    private String name;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
