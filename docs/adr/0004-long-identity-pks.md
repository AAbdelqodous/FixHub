# ADR 0004: Long identity primary keys

- Status: Accepted
- Date: 2026-07-06

## Context

Every JPA entity needs a primary key strategy. The two common contenders are a
database-generated numeric surrogate key and an application- or
database-generated UUID.

## Decision

Entities use `Long` primary keys generated via `GenerationType.IDENTITY`
(Postgres `bigserial`/`identity` column), not `UUID`. The `id` field itself
lives directly on `AuditableEntity` — every entity in this system needs both
an identity and an audit trail, so there's no case yet for a separate
`BaseEntity`/`AuditableEntity` split. `AuditTriggerEntity` (the throwaway
fixture in `AuditableEntityContractTest`) inherits it and is the reference
example until the first real domain entity lands.

## Alternatives considered

`UUID` primary keys — rejected for now. This is a single Postgres-backed
monolith, not a distributed system stitching together IDs generated offline
across services, so UUID's main advantage (collision-free generation without a
central authority) buys us nothing today. In exchange we'd pay for it: larger
index entries, worse b-tree insert locality than a monotonically increasing
key, and less readable identifiers in logs and URLs during debugging.

## Consequences

- If a public-facing identifier needs to be unguessable or exposed outside the
  system (e.g. an API resource shared with third parties), add a separate
  `UUID`/slug column rather than switching the primary key.
- Revisit if/when the system splits into services that need to generate IDs
  independently before a row exists in a shared database.
