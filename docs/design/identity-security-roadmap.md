# Identity Security Roadmap

- Status: **Approved**
- Approved: 2026-09-11
- Scope: FH-011 through FH-014
- Predecessor: FH-010 — Account and Credential Persistence

## Purpose

This roadmap assigns the Identity and security work deferred by FH-010 to four bounded tasks. It
removes ambiguity from the grouped FH-011 through FH-014 references in the legacy-to-new mapping
without approving implementation details that belong in later task specifications or architecture
decisions.

The task allocation in this document is approved. No task may begin implementation without its own
approved task specification and any required ADRs.

## Roadmap matrix

| Task | Title | Purpose | Primary dependency | Explicitly excluded |
|---|---|---|---|---|
| FH-011 | Registration and Email Verification | Create Accounts and PASSWORD Credentials through a verified registration lifecycle | FH-010 | Login, JWT, refresh sessions, recovery, and authorization |
| FH-012 | Authentication and Session Token Lifecycle | Authenticate eligible Accounts and manage access and refresh-session lifecycles | FH-011 | Password recovery and Provider authorization |
| FH-013 | Credential Maintenance and Account Recovery | Maintain PASSWORD Credentials and recover Account access | FH-011 and FH-012 as applicable | Registration, session-token implementation, and authorization |
| FH-014 | Authorization Foundation | Establish global and application authorization while preserving Provider ownership | FH-012 and FH-020 | Identity-owned Provider Membership or Provider/Branch roles |

## Password-policy ownership

| Task | Owns | Does not own |
|---|---|---|
| FH-011 | Selection and configuration of the password encoder used for newly registered passwords; the current password-writing policy and allowed newly written `{id}` identifier; registration password validation | Login-time matching or upgrade-on-successful-login; password change or reset policy |
| FH-012 | Credential matching during login; `PasswordEncoder.upgradeEncoding` or equivalent upgrade-on-successful-login behavior; authentication-time compatibility with previously approved encoded identifiers | Selection of the current password-writing policy; password change or reset policy |
| FH-013 | Password change and reset policy; encoding replacement passwords using the current approved encoder; password history, compromise, and reuse decisions if approved | Registration password validation; upgrade-on-login |

## Cross-task rules

- Each token purpose must use a distinct lifecycle and storage contract. Verification, recovery,
  access, and refresh/session tokens must not share one generic token lifecycle merely because they
  all carry security-sensitive values.
- No task may expose Identity entities or repositories outside the closed Identity module. Other
  modules collaborate through explicit APIs, immutable contract data, stable identifiers, or
  completed domain events approved by the task that introduces them.
- Identity owns global roles and their lifecycle.
- Provider Membership, Provider Roles, Provider Role grants, Provider/Branch scope, and the decision
  to allow or deny a Provider or Branch operation remain Provider-owned. Identity must not duplicate
  them as global roles or token claims that become an independent authorization authority.
- Implementation cannot begin without an approved task specification defining scope, contracts,
  security decisions, acceptance criteria, and verification evidence.
- Real passwords, credentials, and production-like secrets must never appear in source code,
  fixtures, logs, screenshots, exceptions, or documentation.
- Raw passwords may exist transiently at an approved application/API boundary.
- Tests may use unmistakably synthetic raw password values when required to verify password
  encoding and validation.
- Synthetic raw test passwords must never be persisted, logged, included in exceptions, or reused
  as operational credentials.
- Verification, recovery, refresh, and session token values should be generated during tests rather
  than committed as realistic secrets whenever practical.
- Authentication and authorization failures must use the existing `ProblemDetail` contract and its
  stable `UNAUTHORIZED` and `FORBIDDEN` codes where applicable.

## Dependency sequence

- FH-010 → FH-011.
- FH-011 → FH-012.
- FH-011 and FH-012 → FH-013 as applicable to the approved credential-maintenance and recovery
  design.
- FH-012 + FH-020 → FH-014.

FH-011 remains the immediate next task after FH-010. FH-014 depends on both FH-012 and FH-020 and
must not begin implementation until FH-020 exposes its approved Provider Membership and
Provider/Branch permission contract. FH-014 integrates with that explicit contract and never reads
Provider persistence directly. Numeric task order does not override this dependency.

## FH-011 — Registration and Email Verification

### Purpose

Introduce the first public Account lifecycle: register an Account with a PASSWORD Credential,
verify the required email attribute, and activate an eligible `PENDING_VERIFICATION` Account.

### In-scope functionality

- Register an Account and its PASSWORD Credential transactionally.
- Select and configure the password encoder used for newly registered passwords.
- Define the current password-writing policy and allowed newly written `{id}` identifier.
- Validate registration passwords under the approved FH-011 policy.
- Accept a registration password only at the application boundary, encode it there using the
  current approved encoder, and pass only encoded material into Credential persistence.
- Create, deliver, resend, and atomically consume purpose-specific email-verification tokens.
- Activate an eligible Account from `PENDING_VERIFICATION` after successful token consumption.
- Define the registration, verification, and resend application/API contracts and their stable
  errors in the FH-011 specification.
- Verify transactionality, uniqueness conflicts, token lifecycle behavior, concurrent consumption,
  error sanitization, and closed-module boundaries.

### Explicit exclusions

- Login or logout.
- JWT access-token issuance or validation.
- Refresh-session persistence or lifecycle behavior.
- Forgotten-password recovery or reset.
- Global, application, Provider, or Branch authorization.
- Phone verification.
- Legacy credential or token migration.

### Dependencies

- FH-010 Account and PASSWORD Credential persistence.
- The existing common API error and correlation contract.
- The closed Identity module boundary.

### Required specification and ADR work

- An approved FH-011 specification covering domain behavior, public API contracts, persistence,
  email delivery, failure behavior, tests, and acceptance criteria.
- An accepted security ADR defining password encoding and write policy and verification-token
  protection. FH-011 implementation must not begin until this ADR is accepted.
- Email delivery and outbox architecture may use a separate ADR if it establishes a reusable
  cross-module convention.

### Security decisions intentionally deferred to FH-011

- Registration password validation and password-encoding algorithm, parameters, current write
  policy, and allowed newly written `{id}` identifier.
- Verification-token entropy, protection mechanism, persistence schema, expiry, replacement,
  resend, revocation, cleanup, and atomic-consumption rules.
- Account-enumeration behavior and registration/verification/resend rate limits.
- Email-delivery port, template and locale behavior, link construction, retry policy, outbox policy,
  and provider adapter.
- Exact Account activation eligibility and repeated or expired verification behavior.

This roadmap makes none of those decisions.

### Definition of completion

- The FH-011 specification and every required ADR are approved before implementation begins.
- Registration persists Account and PASSWORD Credential atomically and never persists or logs a raw
  password.
- Verification tokens follow the approved protected, single-purpose lifecycle and can be consumed
  successfully only once under concurrent access.
- Successful verification performs only the approved Account status transition.
- Registration, verification, resend, failure, concurrency, persistence, and security-boundary
  tests pass.
- The canonical Maven verification lifecycle passes and completion documentation does not claim
  login, sessions, recovery, or authorization.

## FH-012 — Authentication and Session Token Lifecycle

### Purpose

Authenticate verified and otherwise eligible Accounts and establish the access-token and
refresh-session lifecycle used by protected application endpoints.

### In-scope functionality

- Login using Accounts that satisfy the approved verification, status, and eligibility rules.
- Spring Security authentication against the Identity-owned PASSWORD Credential.
- Credential matching during login.
- `PasswordEncoder.upgradeEncoding` or equivalent upgrade-on-successful-login behavior.
- Authentication-time compatibility with previously approved encoded identifiers.
- JWT access-token issuance and validation.
- Refresh-session persistence, rotation, reuse detection, logout, and revocation.
- Spring Security entry points required for authentication failures.
- Verification of credential matching, Account eligibility, access-token validation, rotation,
  replay/reuse handling, logout, revocation, concurrency, and error sanitization.

### Explicit exclusions

- Password recovery or forgotten-password reset.
- Provider Membership, Provider Roles, or Provider/Branch authorization.
- Registration and email-verification implementation owned by FH-011.
- Password-encoder selection, current password-writing policy, and registration password validation
  owned by FH-011.
- Password change and reset policy owned by FH-013.
- Global-role administration owned by FH-014.

### Dependencies

- FH-011 registration and email verification.
- FH-010 Account and Credential persistence.
- The existing common `ProblemDetail`, `UNAUTHORIZED`, and correlation contracts.

### Required specification and ADR work

- An approved FH-012 specification covering authentication, public API contracts, token/session
  persistence, Spring Security integration, revocation behavior, tests, and acceptance criteria.
- An accepted security ADR defining the cross-task access-token, refresh-session, signing-key, and
  Spring Security architecture before implementation.

### Security decisions intentionally deferred to FH-012

- Login identifier and exact Account/Credential eligibility rules.
- Credential-matching behavior, compatibility with previously approved encoded identifiers, and
  upgrade-on-successful-login behavior.
- JWT algorithm, signing and verification keys, key storage and rotation, issuer, audience, claims,
  lifetime, clock tolerance, and validation policy.
- Refresh-session storage schema, token protection, lifetime, rotation transaction, reuse response,
  revocation scope, cleanup, and concurrent-session policy.
- Bearer-token transport, filter-chain boundaries, stateless/session behavior, CORS, CSRF, and
  public-route policy.
- Login throttling, lockout behavior, failure auditing, and non-enumerating authentication errors.

This roadmap makes none of those decisions.

### Definition of completion

- The FH-012 specification and security ADR are approved before implementation begins.
- Eligible Accounts can authenticate and receive access and refresh material under the approved
  contracts; ineligible Accounts cannot authenticate.
- Access tokens are issued and validated according to the approved JWT policy.
- Refresh rotation, reuse detection, logout, and revocation are enforced transactionally and tested
  under replay and concurrency conditions.
- Spring Security authentication failures use the existing sanitized `ProblemDetail` contract.
- The canonical Maven verification lifecycle passes and completion documentation does not claim
  recovery or Provider authorization.

## FH-013 — Credential Maintenance and Account Recovery

### Purpose

Allow an authenticated Account to maintain its PASSWORD Credential and allow an Account that has
lost access to recover it through a separately protected recovery lifecycle.

### In-scope functionality

- Authenticated password change.
- Forgotten-password recovery and password reset.
- Define password change and reset validation policy.
- Encode replacement passwords using the current approved encoder and password-writing policy.
- Decide password history, compromise, and reuse rules if approved by FH-013.
- Purpose-specific recovery-token creation, delivery, validation, atomic consumption, expiry, and
  revocation under the approved FH-013 contract.
- Integration with existing authentication/session behavior only where the approved recovery or
  credential-change contract requires it.
- Verification of credential replacement, recovery-token lifecycle, concurrency, session effects,
  non-enumeration, and error sanitization.

### Explicit exclusions

- Account registration or email-verification implementation.
- Access-token or refresh-session implementation.
- Password-encoder selection, current password-writing policy, registration password validation,
  and upgrade-on-login.
- Global, application, Provider, or Branch authorization.
- Legacy password or recovery-token migration without a separate security review.

### Dependencies

- FH-011 for the registered Account, encoded PASSWORD Credential, email-delivery boundary, and
  purpose-specific verification precedent.
- FH-012 where authenticated password change, session revocation, or post-reset authentication
  behavior depends on the approved session lifecycle.

### Required specification and ADR work

- An approved FH-013 specification covering authenticated change, recovery, public API contracts,
  persistence, delivery, session effects, tests, and acceptance criteria.
- Any accepted security ADR required to refine password maintenance, recovery-token, and
  session-revocation architecture without contradicting FH-011 or FH-012.

### Security decisions intentionally deferred to FH-013

- Current-credential confirmation and recent-authentication requirements for password change.
- Recovery-token entropy, protection, storage, expiry, replacement, revocation, cleanup, and atomic
  consumption.
- Recovery request and reset non-enumeration, throttling, and audit behavior.
- Password change/reset validation, reuse/history, and compromise-check policy.
- Rules for encoding replacement passwords with the current approved encoder; upgrade-on-login
  remains owned by FH-012.
- Which existing sessions are revoked after password change or reset and whether any session may
  remain active.
- Recovery email templates, links, retry behavior, and use of the delivery/outbox boundary approved
  by FH-011.

This roadmap makes none of those decisions.

### Definition of completion

- The FH-013 specification and every required ADR refinement are approved before implementation.
- Authenticated password change and forgotten-password recovery obey their distinct approved
  security and transaction contracts.
- Replacement passwords are encoded with the current approved encoder; FH-013 does not implement
  upgrade-on-login.
- Recovery tokens cannot be confused with verification or refresh tokens and are protected,
  purpose-specific, expiring, and single-use.
- Credential replacement and any approved session effects are atomic and covered by concurrency,
  replay, enumeration, persistence, and security tests.
- The canonical Maven verification lifecycle passes and completion documentation does not claim
  registration, session-token implementation, or authorization work.

## FH-014 — Authorization Foundation

### Purpose

Establish platform-global and application-level authorization while preserving Provider ownership
of Membership, Provider Roles, Provider/Branch scope, and Provider authorization decisions.

### In-scope functionality

- Global roles and their application-level authorization behavior.
- Spring Security authorization integration for approved application-level policies.
- `UNAUTHORIZED` and `FORBIDDEN` handling using the existing `ProblemDetail` contract, including
  failures raised outside MVC controller advice.
- Integrate with the explicit FH-020 contract for Provider Membership and Provider/Branch permission
  resolution without reading Provider persistence directly.
- Authorization tests for global policy, denied access, exact Provider/Branch context,
  cross-provider isolation, and error sanitization as applicable to the approved scope.

### Explicit exclusions

- Registration, email verification, authentication, access-token, refresh-session, password-change,
  or recovery implementation owned by FH-011 through FH-013.
- Provider, Branch, Membership, Provider Role, or role-grant persistence in Identity.
- Inferring Provider or Branch authority from global roles, token possession, identifier possession,
  or the first Account association.
- Direct access from Identity or security adapters to Provider internal entities or repositories.

### Dependencies

- FH-012 authentication and session-token lifecycle and FH-020 Provider Membership and
  Provider/Branch permission contract. Both must be complete before FH-014 implementation begins.
- The existing common `UNAUTHORIZED`, `FORBIDDEN`, and `ProblemDetail` contract.
- ADR 0007 for the Identity/Provider authorization boundary.

### Required specification and ADR work

- An approved FH-014 specification covering global-role ownership, application-level policies,
  Spring Security authorization, Provider integration boundaries, errors, tests, and acceptance
  criteria.
- An accepted authorization ADR defining global authority representation and the cross-module
  permission-resolution contract without transferring Provider-owned decisions into Identity.

### Security decisions intentionally deferred to FH-014

- Global-role assignment, lifecycle, persistence, defaulting, and administrative controls.
- Mapping global roles to Spring Security authorities and choosing method, request, or policy-layer
  enforcement points.
- Whether global authority is loaded per request, represented in access tokens, or resolved through
  another approved freshness model.
- Authorization-cache behavior, invalidation, staleness limits, and fail-closed behavior.
- Trusted Provider/Branch context and the exact FH-020 permission-query contract.
- Resource-concealment policy where an approved module returns `404` instead of revealing a denied
  resource.

This roadmap makes none of those decisions.

### Definition of completion

- The FH-014 specification and authorization ADR are approved before implementation begins.
- FH-012 and FH-020 are complete before FH-014 implementation begins; numeric task order does not
  override this dependency.
- FH-014 completion includes both global/application authorization and Provider/Branch authorization
  integration.
- Global and application-level authorization follows the approved policy and cannot grant Provider
  or Branch authority by itself.
- Security-layer `UNAUTHORIZED` and `FORBIDDEN` responses match the existing public error contract.
- Provider/Branch authorization integration uses only the explicit FH-020 contract, never reads
  Provider persistence directly, and duplicates no Provider-owned model in Identity.
- Authorization tests cover applicable role, status, scope, context, denial, and isolation cases.
- The canonical Maven verification lifecycle passes.

## Approval effect

Approval of this roadmap assigns task ownership only. It does not approve a password algorithm,
token schema or lifetime, JWT design, email outbox or provider, rate limits, API shape, persistence
migration, or any other security-sensitive implementation choice. Those decisions remain subject
to the specification and ADR gates stated above.
