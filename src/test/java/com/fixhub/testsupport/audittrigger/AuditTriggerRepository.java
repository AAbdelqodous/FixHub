package com.fixhub.testsupport.audittrigger;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditTriggerRepository extends JpaRepository<AuditTriggerEntity, Long> {
}
