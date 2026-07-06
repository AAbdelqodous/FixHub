# Session 2 — Common Module: Error Handling + JPA Auditing

## Scope

- A central API error contract usable by every future controller:
  `ApiException`, `BusinessErrorCode`, `GlobalExceptionHandler`.
- A shared audit trail base for every future JPA entity: `AuditableEntity` +
  `JpaAuditingConfig`.
- A permanent liveness endpoint: `GET /api/v1/ping`.

Out of scope for this session: entity-specific business logic, security
configuration for `/api/v1/ping` beyond what Spring Security defaults to, and
`createdBy`/`updatedBy` auditing (no principal/user model exists yet).

## Decisions

1. [ADR 0001](../adr/0001-problem-detail-error-contract.md) — every error
   response, domain or framework, is an RFC 7807 `ProblemDetail` carrying an
   extra `code` property from `BusinessErrorCode`.
2. [ADR 0002](../adr/0002-method-argument-not-valid-hook.md) — `@Valid`
   validation failures are handled by overriding `handleMethodArgumentNotValid`,
   not `handleHandlerMethodValidationException`.
3. [ADR 0003](../adr/0003-instant-for-audit-timestamps.md) — audit timestamps
   are `Instant`, not `LocalDateTime`.

## Tasks

- [x] `ApiException` carries a `BusinessErrorCode` (plus an overload for a
      custom detail message)
- [x] `BusinessErrorCode` maps each business code to a real `HttpStatus`
- [x] `GlobalExceptionHandler`: `ApiException` handler → `ProblemDetail` + `code`
- [x] `GlobalExceptionHandler`: catch-all `Exception` handler → logs at ERROR
      via the inherited `logger`, returns a generic `INTERNAL_ERROR` body
- [x] `GlobalExceptionHandler`: `handleMethodArgumentNotValid` override → `code`
      + `errors: [{ field, message }]`
- [x] `GlobalExceptionHandlerTest` — standalone `MockMvc` coverage of all three
      handlers
- [x] `AuditableEntity` (`@MappedSuperclass`,
      `@EntityListeners(AuditingEntityListener.class)`) with `Instant`
      `createdAt`/`updatedAt`
- [x] `JpaAuditingConfig` (`@EnableJpaAuditing`)
- [x] `AuditableEntityGuardTest` — reflection guard against regressing either
      field back to `LocalDateTime`
- [x] `PingController` — permanent `GET /api/v1/ping`
- [x] `AuditableEntityContractTest` — `@SpringBootTest` +
      `@AutoConfigureMockMvc(addFilters = false)` against a real Testcontainers
      Postgres, using throwaway `AuditTrigger*` fixtures to drive
      `AuditableEntity` through a create + update cycle, plus a `/api/v1/ping`
      smoke check

## Definition of Done

- `mvn test` is green for `GlobalExceptionHandlerTest`, `AuditableEntityGuardTest`,
  and `AuditableEntityContractTest`.
- No future change to the error contract or to `AuditableEntity` lands without
  a corresponding ADR update.
