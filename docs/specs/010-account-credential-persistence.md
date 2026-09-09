# FH-010 — Account and Credential Persistence

- Status: Approved for implementation
- Approved: 2026-09-09
- Target module: Identity
- Target branch: `feat/fh-010-account-credential-persistence`

## Purpose

FH-010 introduces the first Identity-module persistence model: an Account representing a person
who can authenticate with FixHub and a separate Credential record containing authentication secret
material for that Account.

This specification is intentionally limited to domain and persistence foundations. It does not
implement registration, authentication, authorization, verification, password encoding, or token
lifecycle behavior.

## Authoritative references

- [Phase 0 gate review](../reviews/phase-0-gate.md) approves FH-010 as the first business-module
  delivery and requires an approved specification before persistence implementation.
- [Domain glossary](../design/domain-glossary.md) defines Account, Credentials, Verification,
  Session/Refresh Token, and Global Role as distinct Identity concepts.
- [Domain map](../design/domain-map.md) assigns Account and credential ownership to the closed
  Identity module and prohibits other modules from accessing Identity repositories or mutable
  internal entities.
- [Conceptual ERD](../design/erd.md) establishes Account as the stable identity referenced by other
  modules without prescribing a physical schema.
- [Legacy-to-new mapping](../design/legacy-to-new-mapping.md) assigns normalized Account identity,
  contact, status, and preferred-locale persistence to FH-010 while deferring authentication,
  verification, recovery, tokens, sessions, and authorization.
- [ADR 0003](../adr/0003-instant-for-audit-timestamps.md) requires `Instant` audit timestamps.
- [ADR 0004](../adr/0004-long-identity-pks.md) requires `Long` identity-generated primary keys on
  `AuditableEntity`.
- [ADR 0005](../adr/0005-adopt-spring-modulith.md) makes `identity` a closed Spring Modulith module.
- [ADR 0007](../adr/0007-branch-membership-model.md) keeps Account status and authentication in
  Identity while Provider Membership and Provider/Branch roles remain Provider-owned.
- [ADR 0008](../adr/0008-translation-model.md) establishes normalized BCP 47 locale identifiers.

## Scope

FH-010 includes:

- Create the closed `com.fixhub.platform.identity` application module.
- Define and persist the Account aggregate root.
- Require one unique email address for every Account.
- Support an optional phone number stored in normalized international E.164 form and unique when
  present. Email remains required; phone-only Accounts are not supported.
- Require a normalized BCP 47 `preferredLocale` without restricting the value to Arabic or English
  and without assigning a database default.
- Define the Account statuses `PENDING_VERIFICATION`, `ACTIVE`, `SUSPENDED`, and `DISABLED`, with
  `PENDING_VERIFICATION` as the initial status for newly created Accounts.
- Define and persist a separate Credential entity with a CredentialType.
- Support only the `PASSWORD` credential type initially and enforce at most one credential for each
  Account and credential type.
- Store only encoded secret material compatible with Spring Security's
  `DelegatingPasswordEncoder` storage format.
- Add optimistic locking to Account and Credential.
- Add internal Spring Data repositories that do not expose hard-delete operations.
- Add Flyway migrations for the Account and Credential schema.
- Add focused unit and PostgreSQL Testcontainers integration tests.

## Out of scope

FH-010 does not include:

- Registration, login, logout, or authenticated HTTP endpoints.
- Password hashing, password-policy selection, a `PasswordEncoder` bean, Argon2, Bouncy Castle, or
  any other hashing-algorithm dependency or configuration.
- Password change, reset, recovery, history, expiry, or compromise workflows.
- Email or phone verification behavior and verification state beyond the Account's initial
  `PENDING_VERIFICATION` status.
- Verification, recovery, access, refresh, session, device, or invitation tokens.
- JWT creation or validation, authentication filters, or production Spring Security configuration.
- Login throttling, lockout counters, failed-attempt tracking, or account-enumeration responses.
- Global roles or global-role assignments.
- Provider Membership, Provider Roles, Provider ownership, or Branch authorization.
- Name, display-name, avatar, address, or other profile fields.
- Notification preferences, delivery destinations, or push tokens.
- Business-domain relationships, collections, counters, or projections on Account.
- Account or Credential hard deletion, retention, anonymization, or restoration workflows.
- Runtime administrator seeding or changes to the existing local administrator properties.
- Legacy data migration or compatibility with legacy password/token formats.
- Public Identity-module APIs, DTOs, controllers, services, or domain events.

## Module and domain model

### Module boundary

`com.fixhub.platform.identity` is a closed Spring Modulith module. Its JPA entities, repositories,
normalization logic, and persistence services remain in internal subpackages. No other module may
import an Identity repository or mutable Identity entity.

FH-010 does not expose a named interface. A future module needing Account information must use an
explicit Identity API based on stable Account identifiers or immutable contract data, introduced by
the task that first requires it.

### Account

Account is the aggregate root representing one person who can authenticate with FixHub. It extends
`AuditableEntity` and therefore inherits:

- `Long id`, generated with `GenerationType.IDENTITY`.
- `Instant createdAt`, populated by Spring Data JPA auditing and immutable after insertion.
- `Instant updatedAt`, populated by Spring Data JPA auditing.

Account adds:

- `email`: the retained email address value.
- `emailNormalized`: the deterministic, case-insensitive lookup and uniqueness key derived from
  `email`.
- `phone`: an optional canonical international E.164 number. No second display/raw phone value is
  persisted.
- `preferredLocale`: a normalized, syntactically valid BCP 47 language tag.
- `status`: one of the approved AccountStatus values.
- `version`: the JPA optimistic-lock version.

Account must not contain a credential hash, Provider association, provider role, global role,
verification token, session, notification preference, profile field, or business counter.

### Account invariants

- Email is required, syntactically valid, and globally unique by `emailNormalized`.
- Leading and trailing whitespace is not part of a valid persisted email value.
- Email comparison must not implement provider-specific transformations such as removing dots or
  plus-address suffixes.
- Phone is optional. When present, it is stored only in canonical E.164 form: `+`, followed by a
  non-zero first digit and at most fifteen digits in total.
- Phone is globally unique when present.
- `preferredLocale` is required, has no persistence default, and must equal its normalized BCP 47
  representation. Well-formed tags are not limited to the current MVP content locales.
- A newly created Account always starts as `PENDING_VERIFICATION`.
- FH-010 persists all approved statuses but defines no status-transition use cases. Those
  transitions belong to later Identity tasks.
- `version` is non-negative and is advanced by JPA when concurrent updates succeed.
- Account has no public hard-delete operation.

### Credential

Credential is a separate Identity-owned entity rather than a field on Account. It extends
`AuditableEntity` and adds:

- A required, lazy, unidirectional association to exactly one Account.
- `credentialType`, initially limited to `PASSWORD`.
- `secretHash`, containing only encoded secret material.
- `version`, the JPA optimistic-lock version.

Account does not maintain a bidirectional collection of Credential entities. Credential lifecycle
is accessed through its own internal repository.

### Credential invariants

- Credential requires an existing Account.
- CredentialType is required and only `PASSWORD` is supported in FH-010.
- An Account may have at most one Credential for a given CredentialType.
- `secretHash` is required and must use the `DelegatingPasswordEncoder` self-describing format
  `{id}encodedSecret`, with a nonblank identifier and nonblank encoded value.
- FH-010 accepts already encoded material only. It does not accept, hash, compare, or retain a raw
  password.
- The algorithm identifier is not restricted in FH-010 because algorithm selection belongs to the
  authentication task. That task must decide which identifiers may be newly written.
- Credential has no public hard-delete operation.

## Database design

Both tables live in PostgreSQL's default schema and use the approved module-prefixed names.
Database identifiers and constraint names use snake case.

### `identity_accounts`

| Column | PostgreSQL type | Nullability | JPA/domain meaning |
|---|---|---|---|
| `id` | `BIGINT GENERATED BY DEFAULT AS IDENTITY` | Not null | Primary key inherited from `AuditableEntity` |
| `email` | `VARCHAR(320)` | Not null | Retained email address |
| `email_normalized` | `VARCHAR(320)` | Not null | Case-insensitive lookup and uniqueness key |
| `phone` | `VARCHAR(16)` | Nullable | Canonical E.164 number including leading `+` |
| `preferred_locale` | `VARCHAR(35)` | Not null | Normalized BCP 47 language tag; no database default |
| `status` | `VARCHAR(32)` | Not null | String-mapped AccountStatus |
| `version` | `BIGINT` | Not null | JPA optimistic-lock version; no database default |
| `created_at` | `TIMESTAMP(6) WITH TIME ZONE` | Not null | Audited creation Instant |
| `updated_at` | `TIMESTAMP(6) WITH TIME ZONE` | Not null | Audited update Instant |

Constraints and indexes:

- Primary key `pk_identity_accounts` on `id`.
- Unique constraint `uq_identity_accounts_email_normalized` on `email_normalized`.
- Partial unique index `uq_identity_accounts_phone` on `phone WHERE phone IS NOT NULL`.
- Check `ck_identity_accounts_email_not_blank` rejects blank `email`.
- Check `ck_identity_accounts_email_normalized_not_blank` rejects blank `email_normalized`.
- Check `ck_identity_accounts_phone_e164` accepts null or the approved E.164 canonical shape.
- Check `ck_identity_accounts_preferred_locale_not_blank` rejects a blank locale. Full BCP 47
  parsing and normalization remain application-level because a SQL regular expression cannot
  reliably implement BCP 47.
- Check `ck_identity_accounts_status` permits only `PENDING_VERIFICATION`, `ACTIVE`, `SUSPENDED`,
  and `DISABLED`.
- Check `ck_identity_accounts_version_non_negative` requires `version >= 0`.
- No separate indexes on status or audit timestamps are added without a documented query.

### `identity_credentials`

| Column | PostgreSQL type | Nullability | JPA/domain meaning |
|---|---|---|---|
| `id` | `BIGINT GENERATED BY DEFAULT AS IDENTITY` | Not null | Primary key inherited from `AuditableEntity` |
| `account_id` | `BIGINT` | Not null | Owning Account foreign key |
| `credential_type` | `VARCHAR(32)` | Not null | String-mapped CredentialType |
| `secret_hash` | `VARCHAR(512)` | Not null | DelegatingPasswordEncoder-compatible encoded secret |
| `version` | `BIGINT` | Not null | JPA optimistic-lock version; no database default |
| `created_at` | `TIMESTAMP(6) WITH TIME ZONE` | Not null | Audited creation Instant |
| `updated_at` | `TIMESTAMP(6) WITH TIME ZONE` | Not null | Audited update Instant |

Constraints and indexes:

- Primary key `pk_identity_credentials` on `id`.
- Foreign key `fk_identity_credentials_account` from `account_id` to `identity_accounts(id)` with
  `ON DELETE RESTRICT`.
- Unique constraint `uq_identity_credentials_account_type` on
  `(account_id, credential_type)`.
- Check `ck_identity_credentials_type` permits only `PASSWORD`.
- Check `ck_identity_credentials_secret_format` requires a nonblank `{id}` prefix and a nonblank
  encoded value after it.
- Check `ck_identity_credentials_version_non_negative` requires `version >= 0`.
- No additional `account_id` index is required: the unique `(account_id, credential_type)` index
  has `account_id` as its leading column.

### Deletion and referential behavior

Hard deletion is unsupported at both repository and application boundaries. The restrictive
foreign key supplies defense in depth if an account deletion is attempted through SQL. FH-010 does
not introduce a soft-delete flag because retention, anonymization, and restoration behavior have
not been specified.

## Repository boundaries

Repositories reside below `com.fixhub.platform.identity` and are internal to the closed module.
They extend Spring Data's marker `Repository` and declare only approved operations rather than
extending `CrudRepository` or `JpaRepository`, whose inherited API would expose delete methods.

The Account repository may expose only:

- Save an Account.
- Find an Account by ID.
- Find an Account by normalized email.
- Determine whether normalized email already exists.
- Determine whether canonical phone already exists.

The Credential repository may expose only:

- Save a Credential.
- Find a Credential by Account ID and CredentialType.
- Determine whether a Credential exists for an Account ID and CredentialType.

Repositories must not expose raw SQL updates, bulk status changes, delete operations, credential
hash projections, or cross-module entity relationships. Repository methods are infrastructure for
later Identity use cases, not a public module API.

## Flyway migration plan

The existing `V1__baseline.sql` remains unchanged.

1. `V2__create_identity_accounts.sql`
   - Create `identity_accounts` with all Account columns, named checks, primary key, unique email
     constraint, and partial unique phone index.
2. `V3__create_identity_credentials.sql`
   - Create `identity_credentials` with all Credential columns, checks, primary key, restrictive
     Account foreign key, and Account/type uniqueness constraint.

The migrations are additive and contain no seed data, default Account, administrator Account,
plaintext or encoded password fixture, legacy-data conversion, or destructive statement. Hibernate
continues to validate the Flyway-owned schema and must not create or update production tables.

## Test plan

### Unit tests

Account tests cover:

- Required email and deterministic normalized lookup key.
- Rejection of blank or syntactically invalid email.
- Rejection of provider-specific email rewriting.
- Optional phone behavior and valid/invalid E.164 canonical values.
- Required, syntactically valid, normalized BCP 47 locale values, including a value other than `ar`
  or `en`.
- Initial `PENDING_VERIFICATION` status.
- Availability of all four persisted status values without implementing transitions.
- Absence of name/profile and business-domain state.

Credential tests cover:

- Required Account and CredentialType.
- `PASSWORD` as the only accepted type.
- Acceptance of representative `{id}encodedSecret` values without interpreting the algorithm.
- Rejection of plaintext, missing identifiers, empty encoded values, and blank secrets.
- Replacement of encoded material without handling a raw password.
- Credential string representations and generated methods do not reveal `secretHash`.

### PostgreSQL Testcontainers integration tests

Integration tests reuse the project's shared PostgreSQL Testcontainer and run against the real
Flyway migrations. They cover:

- Flyway applies V2 and V3 successfully to an empty PostgreSQL database.
- Hibernate validates both entity mappings against the migrated schema.
- Account and Credential receive generated `Long` identifiers.
- Create and update operations populate auditing fields.
- Audit comparisons across database boundaries follow ADR 0003's reload-before-comparison rule for
  PostgreSQL microsecond precision.
- Optimistic locking rejects stale concurrent Account and Credential updates.
- Duplicate normalized email is rejected by PostgreSQL.
- Duplicate non-null phone is rejected while multiple null phones are accepted.
- Invalid status, phone shape, negative version, credential type, and encoded-secret shape are
  rejected by database checks.
- A second `PASSWORD` Credential for the same Account is rejected.
- The same CredentialType may be used by different Accounts.
- A Credential cannot reference a missing Account.
- The restrictive foreign key prevents Account deletion while a Credential exists.
- Repository lookup and existence methods use normalized identifiers and Account/type keys.
- No repository exposes a hard-delete operation.
- The global Spring Modulith verification still passes with Identity closed.

Tests and fixtures must use unmistakably synthetic encoded values and must never contain a real
password, credential, token, or customer identifier.

## Security requirements

- Persist only encoded secret material in `secret_hash`.
- Require the self-describing `DelegatingPasswordEncoder` form `{id}encodedSecret` so a later
  authentication task can select, verify, and upgrade algorithms without changing this schema.
- Do not instantiate or configure a PasswordEncoder in FH-010.
- Do not add Argon2, Bouncy Castle, or another hashing implementation dependency in FH-010.
- Do not accept a raw password in an entity constructor, repository method, migration, test
  fixture, log statement, exception, DTO, or configuration property introduced by this task.
- Do not expose `secretHash` through Account, repository projections, serialization, `toString`,
  equality diagnostics, or cross-module contracts.
- Keep Credential mappings lazy and unidirectional so ordinary Account reads cannot accidentally
  fetch secret material.
- Do not store salts in separate columns; algorithm-specific encoded values own their salt and
  parameters.
- Do not introduce a pepper. If later approved, it must be managed outside PostgreSQL by an
  appropriate secret-management facility.
- Do not use or change the existing local administrator email/password properties in FH-010.
- Legacy password, verification, recovery, refresh, and device-token data must not be migrated
  without the separate security review required by the legacy mapping.

## Acceptance criteria

FH-010 implementation is complete only when:

- `identity` is recognized as a closed Spring Modulith module and the existing modularity test
  passes.
- Account and Credential extend `AuditableEntity` and use inherited `Long` identity keys and
  `Instant` auditing.
- Both entities have `@Version` optimistic-lock fields mapped to non-null `version` columns.
- New Account creation always uses `PENDING_VERIFICATION`.
- Email is required and unique by its normalized lookup key.
- Phone is optional, canonical E.164, and unique when present; email remains required.
- Preferred locale is required, normalized BCP 47, not limited to `ar`/`en`, and has no database
  default.
- Credential is separate from Account, supports only `PASSWORD`, and is unique per Account/type.
- Only `{id}encodedSecret` material can be persisted; no password hashing algorithm is selected or
  configured.
- No name/profile fields, global roles, tokens, sessions, verification workflow, authentication
  workflow, public API, or administrator bootstrap behavior is added.
- Repositories are internal and expose no hard-delete operations.
- `V1__baseline.sql` remains unchanged; V2 and V3 own the new schema.
- PostgreSQL constraints, repository queries, auditing, optimistic locking, and module boundaries
  are covered by unit and Testcontainers integration tests.
- The canonical Maven `verify` lifecycle passes, including Spotless, unit and integration tests,
  Modulith verification, Hibernate schema validation, and JaCoCo thresholds.

## Implementation sequence

Implement FH-010 in small, reviewable vertical changes:

1. **Account persistence**
   - Add Account, AccountStatus, normalization/validation support, the restricted Account
     repository, V2 migration, and Account unit and Testcontainers tests in one green change.
2. **Credential persistence**
   - Add Credential, CredentialType, the restricted Credential repository, V3 migration, and
     Credential unit and Testcontainers tests in one green change.
3. **Persistence-contract hardening**
   - Add focused schema-constraint, optimistic-lock, repository-surface, and module-boundary guards
     not already covered by the vertical tests.
4. **Completion documentation**
   - Update project status only after the full verification lifecycle passes. Do not claim that
     authentication, verification, sessions, global roles, or production security are complete.

Each implementation change must keep Flyway migrations immutable after merge and must pass the
canonical Maven `verify` lifecycle before review.

## Email normalization policy

FH-010 uses the following approved email policy:

- Email input must use conventional ASCII mailbox and domain syntax.
- Leading and trailing whitespace is trimmed before persistence.
- The trimmed email value is preserved in the `email` column.
- `emailNormalized` is derived by lower-casing the complete trimmed email using `Locale.ROOT` and is
  persisted in the `email_normalized` column.
- Non-ASCII email addresses are rejected.
- Dots and plus-address suffixes are preserved. No provider-specific rewriting is performed.
- Internationalized email and IDN normalization are outside FH-010 and may be considered in a
  later specification.

This policy is deterministic for persistence, lookup, and uniqueness. No FH-010 design decisions
remain open.
