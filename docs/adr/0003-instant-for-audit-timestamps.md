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

- Postgres' `timestamp` column only keeps microsecond precision, and *rounds*
  to it rather than truncating. An `Instant` fresh out of `Instant.now()` (the
  value returned by the very insert that set it) will therefore not
  necessarily equal the same row's value read back from the database.
  Tests/comparisons across a save-then-reload boundary should compare two
  reloaded reads against each other, not a pre-insert in-memory value against
  a reloaded one — truncating one side to microseconds isn't enough, since
  rounding can carry into the next microsecond.
- Guarded by `AuditableEntityGuardTest`, which fails the build if either field's
  type or its `@CreatedDate`/`@LastModifiedDate` annotation regresses.
