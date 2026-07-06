# ADR 0003: Instant, not LocalDateTime, for audit timestamps

- Status: Accepted
- Date: 2026-07-06

## Context

`AuditableEntity` is the base class every future JPA entity will extend, with
`createdAt`/`updatedAt` populated by Spring Data JPA auditing
(`@CreatedDate`/`@LastModifiedDate`).

## Decision

Both fields are typed `java.time.Instant` (a fixed point on the UTC timeline)
rather than `java.time.LocalDateTime` (no timezone/offset information).

## Alternatives considered

`LocalDateTime` — rejected because it silently depends on the JVM/DB session
timezone. That's invisible today but becomes a source of silently wrong audit
data the moment the deployment topology changes (multi-region, a DB session
timezone that differs from the app's).

## Consequences

- Postgres' `timestamp` column only keeps microsecond precision. An `Instant`
  round-tripped through the database will differ from the original in-memory
  value in the last few nanoseconds — expected, and tests/comparisons across a
  save-then-reload boundary should truncate to microseconds before asserting
  equality.
- Guarded by `AuditableEntityGuardTest`, which fails the build if either field's
  type or its `@CreatedDate`/`@LastModifiedDate` annotation regresses.
