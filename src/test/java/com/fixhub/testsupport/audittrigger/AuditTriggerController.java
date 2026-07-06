package com.fixhub.testsupport.audittrigger;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Throwaway endpoints used only by AuditableEntityContractTest to trigger AuditableEntity's
// persistence lifecycle; explicitly @Import-ed rather than component-scanned.
@RestController
@RequestMapping("/test/audit-trigger")
public class AuditTriggerController {

    private final AuditTriggerRepository repository;

    public AuditTriggerController(AuditTriggerRepository repository) {
        this.repository = repository;
    }

    public record Request(String name) {
    }

    @PostMapping
    AuditTriggerEntity create(@RequestBody Request request) {
        AuditTriggerEntity entity = new AuditTriggerEntity();
        entity.setName(request.name());
        return repository.save(entity);
    }

    @PutMapping("/{id}")
    AuditTriggerEntity update(@PathVariable Long id, @RequestBody Request request) {
        AuditTriggerEntity entity = repository.findById(id).orElseThrow();
        entity.setName(request.name());
        return repository.save(entity);
    }

    @GetMapping("/{id}")
    AuditTriggerEntity get(@PathVariable Long id) {
        return repository.findById(id).orElseThrow();
    }
}
