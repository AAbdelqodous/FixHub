# Session 2 — Common Module: Error Handling + JPA Auditing

## Scope

- A central API error contract usable by every future controller:
  `ApiException`, `BusinessErrorCode`, `GlobalExceptionHandler`.
- A shared identity + audit trail base for every future JPA entity, in
  `com.fixhub.platform.common.jpa`: `AuditableEntity` (`id`, `createdAt`,
  `updatedAt`) + `JpaAuditingConfig`.
- A permanent liveness endpoint: `GET /api/v1/ping`.
- Two structural guards that turn past incidents into a build failure instead
  of a review comment: exactly one `@ControllerAdvice` in the context, and
  Spring Modulith's module-boundary verification.

Out of scope for this session: entity-specific business logic, security
configuration for `/api/v1/ping` beyond what Spring Security defaults to,
`createdBy`/`updatedBy` auditing (no principal/user model exists yet), and
named/explicit Spring Modulith module boundaries for any module other than
`common` (see ADR 0005 — `common` is declared `OPEN` now since it's a shared
kernel by construction; every other module stays on Modulith's implicit,
package-based `CLOSED` reading until it needs otherwise).

The `AuditTrigger*` fixtures in `AuditableEntityContractTest` are test-scope
only and are meant to stay permanently as the contract test's fixture — "don't
leave throwaway endpoints lying around" applies to production code, not to
test fixtures that never ship.

## Decisions

1. [ADR 0001](../adr/0001-problem-detail-error-contract.md) — every error
   response, domain or framework, is an RFC 9457 `ProblemDetail` carrying an
   extra `code` property: the string name of the `BusinessErrorCode` constant,
   which is also the frontend's i18n translation key.
2. [ADR 0002](../adr/0002-method-argument-not-valid-hook.md) — `@Valid`
   validation failures are handled by overriding `handleMethodArgumentNotValid`,
   not `handleHandlerMethodValidationException`.
3. [ADR 0003](../adr/0003-instant-for-audit-timestamps.md) — audit timestamps
   are `Instant`, not `LocalDateTime`.
4. [ADR 0004](../adr/0004-long-identity-pks.md) — entities use `Long`
   `GenerationType.IDENTITY` primary keys, not `UUID`.
5. [ADR 0005](../adr/0005-adopt-spring-modulith.md) — adopt Spring Modulith
   now, verification-only, while there's nothing yet to untangle.

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
- [x] `ControllerAdviceGuardTest` — full-context guard asserting exactly one
      `@ControllerAdvice` bean exists. A standalone-`MockMvc` test wires
      `GlobalExceptionHandler` by hand and can never catch a second, rogue
      advice registered in the real context — only this test can.
- [x] `AuditableEntity` (`@MappedSuperclass`,
      `@EntityListeners(AuditingEntityListener.class)`) with `Long id`
      (`GenerationType.IDENTITY`) and `Instant` `createdAt`/`updatedAt`
- [x] `JpaAuditingConfig` (`@EnableJpaAuditing`)
- [x] `AuditableEntityGuardTest` — reflection guard against regressing either
      field back to `LocalDateTime`
- [x] `PingController` — permanent `GET /api/v1/ping`
- [x] `AuditableEntityContractTest` — `@SpringBootTest` +
      `@AutoConfigureMockMvc(addFilters = false)` against a real Testcontainers
      Postgres, using throwaway `AuditTrigger*` fixtures to drive
      `AuditableEntity` through a create + update cycle, plus a `/api/v1/ping`
      smoke check
- [x] `ModularityTests` — `ApplicationModules.of(FixhubCoreApplication.class).verify()`
      guard from `spring-modulith-starter-test`
- [x] `common/package-info.java` — `@ApplicationModule(type = Type.OPEN)`,
      declared now rather than deferred (see ADR 0005)

## Definition of Done

- `./mvnw verify` is green, including `GlobalExceptionHandlerTest`,
  `ControllerAdviceGuardTest`, `AuditableEntityGuardTest`,
  `AuditableEntityContractTest`, and `ModularityTests` — `verify`, not `test`,
  because that's what CI runs and where the JaCoCo gate will live.
- No future change to the error contract, to `AuditableEntity`, to the
  primary-key strategy, or to the module structure lands without a
  corresponding ADR update.
