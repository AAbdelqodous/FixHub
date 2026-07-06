package com.fixhub.platform.common.jpa;

import com.fixhub.platform.TestcontainersConfiguration;
import com.fixhub.testsupport.audittrigger.AuditTriggerController;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// AuditTriggerEntity/Repository/Controller are throwaway fixtures living outside com.fixhub.platform
// so the default entity scan used by every other @SpringBootTest never sees them; they're opted in
// here explicitly, alongside a Hibernate-managed schema instead of a real Flyway migration.
@Import({TestcontainersConfiguration.class, AuditTriggerController.class})
@EntityScan(basePackages = {"com.fixhub.platform", "com.fixhub.testsupport.audittrigger"})
@EnableJpaRepositories(basePackages = {"com.fixhub.platform", "com.fixhub.testsupport.audittrigger"})
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class AuditableEntityContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void pingStaysReachable() throws Exception {
        mockMvc.perform(get("/api/v1/ping"))
                .andExpect(status().isOk());
    }

    @Test
    void createSetsCreatedAndUpdatedAtToTheSameInstant() throws Exception {
        String response = mockMvc.perform(post("/test/audit-trigger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"widget\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Instant createdAt = Instant.parse(JsonPath.<String>read(response, "$.createdAt"));
        Instant updatedAt = Instant.parse(JsonPath.<String>read(response, "$.updatedAt"));

        assertThat(createdAt).isEqualTo(updatedAt);
    }

    @Test
    void updateAdvancesUpdatedAtButNotCreatedAt() throws Exception {
        String created = mockMvc.perform(post("/test/audit-trigger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"widget\"}"))
                .andReturn().getResponse().getContentAsString();
        int id = JsonPath.read(created, "$.id");

        // Read back through a fresh SELECT (not the POST response's in-memory value) so createdAt
        // has already been rounded to Postgres' microsecond precision, matching what update() below
        // will also read — comparing a pre-insert in-memory Instant to a reloaded one would flake,
        // since Postgres rounds to the nearest microsecond rather than truncating.
        String reloaded = mockMvc.perform(get("/test/audit-trigger/{id}", id))
                .andReturn().getResponse().getContentAsString();
        Instant originalCreatedAt = Instant.parse(JsonPath.<String>read(reloaded, "$.createdAt"));

        Thread.sleep(5);

        String updated = mockMvc.perform(put("/test/audit-trigger/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"widget-renamed\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Instant createdAtAfterUpdate = Instant.parse(JsonPath.<String>read(updated, "$.createdAt"));
        Instant updatedAt = Instant.parse(JsonPath.<String>read(updated, "$.updatedAt"));

        assertThat(createdAtAfterUpdate).isEqualTo(originalCreatedAt);
        assertThat(updatedAt).isAfter(originalCreatedAt);
    }
}
