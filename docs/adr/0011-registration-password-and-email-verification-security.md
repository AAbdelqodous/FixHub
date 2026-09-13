# ADR 0011: Registration password and email-verification security

- Status: Accepted
- Date: 2026-09-13

## Context

FH-010 introduced the closed Identity module's Account and PASSWORD Credential persistence model.
It deliberately deferred registration, password encoding, email verification, tokens, and Account
status transitions. The approved Identity security roadmap assigns those concerns to FH-011 and
requires an accepted security ADR defining the password write policy and verification-token
protection before implementation begins.

FH-011 needs a single security boundary for accepting a raw registration password, persisting a
self-describing encoded credential, proving control of an email address, and activating an Account.
That boundary must resist offline password attacks, user enumeration, token disclosure and replay,
concurrent token consumption, email flooding, and partial persistence. It must also preserve the
closed Identity module: Identity entities and repositories are not exposed to delivery adapters or
other modules.

The decisions below are based on these authoritative sources:

- [Spring Security password storage](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html),
  including its guidance for adaptive password encoders, `DelegatingPasswordEncoder`, identifier
  handling, and target-environment calibration.
- [Spring Security `Argon2PasswordEncoder`](https://docs.spring.io/spring-security/reference/api/java/org/springframework/security/crypto/argon2/Argon2PasswordEncoder.html),
  including its configurable salt, hash, memory, iteration, and parallelism parameters and its
  Bouncy Castle runtime requirement.
- [OWASP Password Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html),
  including the Argon2id baseline and password-storage guidance.
- [OWASP Authentication Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html),
  including password usability, compromised-password screening, enumeration resistance, and
  automated-attack protections.
- [OWASP Forgot Password Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html),
  applied where its guidance for random, securely stored, expiring, single-use, side-channel tokens
  also protects email-verification tokens.
- [NIST SP 800-63B-4](https://pages.nist.gov/800-63-4/sp800-63b.html), including password length,
  Unicode normalization, composition-rule, blocklist, protected-channel, and rate-limiting
  guidance.

## Decision

### Password encoder and credential write policy

FH-011 will configure an explicit `DelegatingPasswordEncoder`. Its encoder used for new writes will
have the identifier `argon2id`, and FH-011 will write PASSWORD Credentials only in this form:

```text
{argon2id}<Argon2id encoded value>
```

The mapped implementation will be Spring Security's `Argon2PasswordEncoder` backed by Bouncy
Castle, configured as follows:

| Parameter | Value |
| --- | ---: |
| Variant | Argon2id |
| Version | 19 (`0x13`) |
| Salt length | 16 random bytes |
| Hash length | 32 bytes |
| Memory | 19,456 KiB |
| Iterations | 2 |
| Parallelism | 1 |

The exact Spring construction mapping is:

```java
new Argon2PasswordEncoder(16, 32, 1, 19_456, 2)
```

The positional arguments defined by the
[official Spring API](https://docs.spring.io/spring-security/reference/api/java/org/springframework/security/crypto/argon2/Argon2PasswordEncoder.html)
are:

1. `saltLength = 16` bytes.
2. `hashLength = 32` bytes.
3. `parallelism = 1`.
4. `memory = 19_456` KiB.
5. `iterations = 2`.

Argon2id version 19 is not a sixth constructor argument. The reviewed
[Spring Security implementation](https://github.com/spring-projects/spring-security/blob/main/crypto/src/main/java/org/springframework/security/crypto/argon2/Argon2PasswordEncoder.java)
selects Argon2id internally and emits version 19 encoded values.

The encoder must be benchmarked on the target runtime under representative concurrency. Parameters
may be calibrated upward when the environment can sustain the additional cost, but no environment
may configure a value below this baseline. The resulting encoded value, including the
`{argon2id}` prefix, remains within FH-010's 512-character Credential limit.

The encoder registry will not contain plaintext, `noop`, or weak legacy encoders. A missing or
unknown `{id}` fails closed and must never select a fallback that treats the stored value as
plaintext. Authentication-time compatibility with any previously approved identifier, sanitized
handling of an unsupported identifier during login, credential matching, and
`PasswordEncoder.upgradeEncoding` or equivalent upgrade-on-successful-login behavior belong to
FH-012.

FH-010's Credential entity and database constraint remain structurally compatible with
`{id}encodedSecret`; the FH-011 application writer, rather than a narrowed persistence invariant,
enforces the currently approved write identifier. This retains the format's future migration
capability.

### Registration password policy

A registration password is accepted only at the approved application/API boundary. Before
blocklist comparison or encoding, it is normalized with Unicode Normalization Form Canonical
Composition (NFC). Its length after normalization must be from 15 through 128 Unicode code points,
inclusive. Unicode code points, not UTF-16 code units or encoded bytes, determine this limit.

All characters are preserved, including leading, trailing, embedded, and repeated whitespace. The
application must never trim, silently truncate, change case, or otherwise transform the normalized
password. No uppercase, lowercase, number, symbol, or other character-composition rule is imposed.
The client boundary must support password managers, paste, and autofill.

Real passwords, credentials, tokens, and production-like secrets must never appear in source code,
fixtures, logs, screenshots, exceptions, or documentation. Raw passwords may exist only transiently
at an approved application/API boundary and during the minimum processing required for validation
and encoding.

Tests may use unmistakably synthetic raw passwords only when required to verify encoding and
validation behavior. A synthetic test password must never be persisted as a raw value, logged,
included in an exception, or reused as an operational credential. Tests may create securely encoded
synthetic Credential values transiently and may persist the encoded output when an approved
persistence test requires it; this does not permit persistence or diagnostic exposure of the raw
test password. Test verification tokens should be generated rather than committed as realistic
static secrets whenever practical.

Raw tokens, token digests, complete verification links, and encoded Credentials must not appear in
logs or exceptions. Assertions and failure messages must not echo password, credential, token,
digest, or complete-link values.

### Compromised-password protection

Identity will own a password-policy/checker port. FH-011 registration must compare the complete NFC
normalized password against a mandatory local, versioned blocklist. It will contain common,
compromised, and FixHub-context password values. Comparison applies to the complete password;
substrings and individual words inside a longer password are not rejected merely because they
appear in the blocklist.

The blocklist is mandatory startup security material. Missing, unreadable, or integrity-invalid
data prevents application startup. FH-011 will not require a live external breach service, so
registration security and availability do not depend on transmitting password-derived data to a
third party at request time.

The exact corpus, licensing, checksum, version metadata, update procedure, and public rejection
response will be defined by the FH-011 specification. Password history and password-reuse policy
remain owned by FH-013.

### Existing-email and enumeration behavior

Registration uses a layered public-response rule:

1. Request-schema, email-format, password-length, password-normalization, and
   compromised-password-policy checks are evaluated without using Account-existence information.
2. A request that fails validation or password policy receives the applicable validation or policy
   response.
3. Abuse-control rejection receives the applicable rate-limit response.
4. Failure of required infrastructure receives a temporary-failure response and performs no state
   mutation.
5. Every request that passes validation, password-policy, abuse-control, and required-infrastructure
   gates returns the same generic `202 Accepted` response regardless of whether the Account exists,
   the Account's status, whether persistence created a new Account, or whether a unique-email race
   occurred.

Exact error codes, HTTP statuses, representations, and retry headers for validation,
password-policy, abuse-control, and temporary-failure responses remain FH-011 specification
decisions consistent with the existing API-error conventions. The ADR does not require those
failure paths to return `202 Accepted`.

New-email and existing-email paths must perform materially equivalent password-policy and
password-encoding work and have materially equivalent externally observable timing before returning
the generic accepted result. A unique-email race is handled as the same generic accepted outcome
and must not expose a persistence conflict.

Registration never replaces an existing Credential and never changes an existing Account's status.
It also never implicitly resends a verification email. An existing `PENDING_VERIFICATION` Account
uses the separately throttled resend flow. `ACTIVE`, `SUSPENDED`, and `DISABLED` Accounts remain
unchanged.

HTTP status, response body, response headers, externally observable timing, and requester-visible
telemetry must not reveal whether an Account exists. Logs must not record a raw or normalized email,
Account status, duplicate-email outcome, or uniqueness-race outcome. Metrics must not contain email
or Account identifiers or labels that reveal per-Account existence outcomes. Aggregate operational
metrics without Account-identifying labels are permitted.

New Accounts receive a verification email. Existing Accounts do not receive an implicit resend from
the registration operation. Mailbox-side observation by a party who controls the registered mailbox
is therefore an intentional and unavoidable exception to HTTP-side indistinguishability. A
legitimate owner of a pending Account must use the separately throttled resend operation. This
exception does not permit any requester-visible response or telemetry to reveal Account existence.

### Verification-token generation and protection

Email verification uses its own purpose-specific token lifecycle and persistence contract. It does
not reuse recovery, access, refresh, session, invitation, or other token models.

For each token, Identity will:

1. Generate 32 random bytes using a cryptographically secure pseudorandom number generator.
2. Encode the raw bearer token as unpadded Base64url, producing a transport value without reserved
   Base64 characters.
3. Calculate its SHA-256 digest.
4. Persist only the unique digest linked to the target Account; the raw token is never persisted.

SHA-256 is used here as a lookup digest for an independently generated 256-bit random bearer token,
not as a password encoder. The raw token, its digest, a complete verification link, raw passwords,
and encoded Credentials must never appear in logs, metrics, traces, screenshots, exceptions, or
public error details.

Verification links must use HTTPS in production and every non-loopback environment. They are built
only from the trusted configured frontend URL; the request `Host` header must not influence the
link. Plain HTTP is permitted only for an explicitly local development profile whose configured
host is a loopback host such as `localhost` or `127.0.0.1`. Local-development links must never be
sent through a production mail provider or treated as production configuration.

FH-011 implementation must validate the configured frontend URL according to the active profile.
The current `http://localhost:8081` local default is an approved loopback-only development
exception. The raw token is placed in the frontend URL fragment; the frontend extracts it and
submits it to the verification API in a POST body. Exact frontend and API endpoint shapes belong to
the FH-011 specification.

### Verification-token lifecycle and Account activation

A verification token is valid for 24 hours. At most one active verification token may exist for an
Account. Resend atomically invalidates or supersedes the prior token before establishing the new
active token. Supersession makes the prior token effectively expired immediately. Expired,
superseded, and consumed tokens are unusable.

Consumption uses a database locking or conditional-write strategy that checks the token purpose,
digest, expiry, supersession, and consumption state. Exactly one concurrent request may consume a
token successfully. Token consumption and the Account transition from `PENDING_VERIFICATION` to
`ACTIVE` occur in the same database transaction.

Verification can activate only a `PENDING_VERIFICATION` Account. It never activates or otherwise
changes an `ACTIVE`, `SUSPENDED`, or `DISABLED` Account. Repeated, expired, superseded, invalid, and
ineligible verification attempts use a non-sensitive public result defined by the specification.

Consumed and expired verification records may be retained for seven days from their terminal
timestamp and must then be permanently deleted. Because supersession establishes immediate
effective expiry, the same maximum retention window applies to a superseded record. Cleanup may
remove terminal records but must never change one back to an active or consumable state. Exact
cleanup scheduling, batching, retries, and operational reporting belong to the specification.

### Registration transaction boundary

Password validation, blocklist comparison, and password encoding occur at the application boundary
before persistence begins. Account, PASSWORD Credential, and verification state are then persisted
atomically in one database transaction. No externally visible partial combination may remain if the
transaction fails.

Database uniqueness remains authoritative for concurrent normalized-email registration. A losing
unique-email race produces the same generic accepted business outcome as any other existing-email
request and does not replace or mutate the winning Account or Credential.

SMTP or any other email transport must not be called inside the database transaction.

### Email-delivery boundary

Identity will define and own a verification-email delivery port. The port accepts only the minimum
immutable delivery data required by the use case and exposes no Identity entity or repository.
Delivery is asynchronous and best-effort, and it starts only after the registration or resend
transaction commits successfully.

A delivery failure leaves the Account in `PENDING_VERIFICATION` and leaves its active verification
token usable. The public registration response does not change based on SMTP availability or
delivery outcome. The separately throttled resend flow provides user recovery.

Mail-delivery logs remain sanitized and must not contain a token, token digest, complete
verification link, raw or normalized email address, Account status, or existence-sensitive outcome.
Aggregate delivery metrics without email, Account, token, or per-Account outcome labels are
permitted.

FH-011 will not introduce a transactional outbox. If FixHub later adopts a reusable durable outbox
or cross-module delivery convention, that architecture requires a separate ADR.

### Abuse controls and rate limiting

FH-011 owns the abuse policy for registration, verification-email resend, and token verification.
Limits use normalized-email and network-origin dimensions, together with global protection. These
dimensions must be evaluated without disclosing whether an Account or token exists. Account
lockout is not used for these flows.

The limiter uses a shared, persistent backing model suitable for multiple application instances and
process restarts. FH-011 will use PostgreSQL rather than introduce Redis solely for this capability.
If the application cannot evaluate the applicable limit, it fails closed before Account,
Credential, token, resend, or activation state is mutated.

Exact numeric thresholds, windows, HTTP behavior, retention, privacy-preserving representations of
limiting identifiers, configuration, cleanup, and operational observability belong to the FH-011
specification.

### Explicit exclusions

FH-011 and this ADR do not design or implement:

- Login or credential matching.
- Upgrade-on-successful-login.
- JWT access tokens or refresh/session tokens.
- Logout or session revocation.
- Global, application, Provider, or Branch roles and authorization.
- Account recovery, password reset, password change, password history, or password reuse policy.
- Phone verification. An optional phone number may remain Account profile data inherited from
  FH-010, but verifying that phone number is outside FH-011.
- Legacy password or token migration.
- Direct or indirect access to Provider persistence.
- Transactional-outbox infrastructure.

No ADR-level FH-011 password or email-verification security decision remains open. The values and
contracts explicitly assigned above to the FH-011 specification remain intentionally deferred; an
approved specification is still required before implementation begins.

## Alternatives considered

### Use a single encoder or Spring's default encoder factory

A bare Argon2 encoder would omit the self-describing `{id}` contract required by FH-010. Spring's
default delegating factory also includes mappings and a default write choice not explicitly
approved for FixHub. An explicit registry makes the current writer and accepted algorithms
deliberate and reviewable.

### Use bcrypt, scrypt, PBKDF2, Password4j, or a plaintext-compatible fallback

Argon2id is selected for new FixHub passwords because it is memory-hard and meets the current OWASP
baseline. PBKDF2-HMAC-SHA256 would be the leading alternative if a FIPS-validated deployment were a
requirement, but that is not the selected FH-011 policy. Password4j would introduce a different
implementation dependency. Plaintext, `noop`, fast hashes, and silent fallbacks are not acceptable.

### Trim passwords or impose composition rules

Silent truncation and character-composition requirements conflict with the cited guidance. NIST
permits limited whitespace allowances in some circumstances. FixHub nevertheless deliberately
chooses never to trim passwords so registration and later authentication use predictable
exact-secret handling. Whitespace preservation is a FixHub architectural decision, not a mandatory
NIST requirement. ASCII-only rules are rejected so the approved Unicode policy remains available.
NFC normalization makes canonically equivalent Unicode input stable before comparison and encoding.

### Defer compromised-password screening or require a live breach service

Deferral would leave registration without the blocklist control required by the selected password
policy. A mandatory live service would introduce request-time privacy, latency, and availability
dependencies. A startup-validated local corpus provides deterministic enforcement; its maintenance
contract remains specification work.

### Reveal duplicate email or resend implicitly during registration

Returning a different status or representation based on Account existence, or taking observably
shorter existence-dependent paths, would enable Account enumeration. Requests that pass validation,
password-policy, abuse-control, and required-infrastructure gates therefore receive one generic
accepted result. Validation, password-policy, rate-limit, and temporary-infrastructure failures use
their applicable existence-independent responses instead of being mislabeled as accepted.

Implicit resend would permit registration requests to flood an existing user's inbox. The separate
throttled resend flow avoids that behavior. Consequently, a party controlling the registered
mailbox can observe the intentional mailbox-side difference between a new registration and an
existing Account; that exception does not extend to the HTTP response, timing, logs, or
requester-visible telemetry.

### Persist raw verification tokens or use multiple active tokens

Plaintext persistence would turn a database disclosure into immediately usable verification
credentials. Multiple active tokens would enlarge the replay window and make revocation semantics
unclear. Digest-only storage and latest-token-only validity provide deterministic lookup and
revocation.

### Put the token in a query string or build links from the request host

Query strings commonly reach browser history, referrers, access logs, and intermediaries. An
untrusted `Host` value can produce a malicious verification link. Trusted HTTPS frontend
configuration plus fragment-to-POST transport reduces those disclosure and injection paths.

### Send email inside the transaction

Calling SMTP before commit can deliver a link for data that later rolls back, while waiting for SMTP
inside the transaction extends locks and couples database availability to the email provider.
After-commit delivery avoids those failure modes.

### Introduce a transactional outbox now

A durable outbox would reduce the crash-loss window of best-effort asynchronous delivery, but it
would establish infrastructure and conventions beyond this bounded Identity task. That reusable
decision requires its own future ADR rather than being introduced implicitly by FH-011.

### Use only in-memory, gateway, or Redis-backed rate limits

In-memory counters do not survive restarts or coordinate replicas. Gateway-only limits cannot
enforce Identity-aware normalized-email policy at the authoritative mutation boundary. Redis would
provide distributed counters but is not otherwise required by FH-011. PostgreSQL supplies durable,
shared enforcement without adding an infrastructure service solely for rate limiting.

### Lock the Account after excessive verification activity

Account lockout would allow an attacker to deny service to a known user and would mutate Account
state for unauthenticated activity. Request throttling limits abuse without changing Account
eligibility.

## Consequences

- FH-011 gains one explicit, reviewable password write format and a future-compatible
  `DelegatingPasswordEncoder` boundary.
- The implementation will require Bouncy Castle in addition to the existing Spring Security
  dependency. The dependency is not added by this ADR-only change.
- Argon2id consumes meaningful memory and CPU per registration attempt. Target-environment
  benchmarks and concurrency tests are mandatory, and rate limiting is part of the security
  boundary.
- Unicode normalization and code-point counting must be identical across validation, blocklist
  checks, and encoding. Raw values require careful diagnostic and test controls.
- Application startup becomes dependent on the availability and verified integrity of the local
  password blocklist.
- Registration validation, password-policy, rate-limit, and temporary-infrastructure failures use
  their applicable existence-independent responses. Every request that passes those gates returns
  the same generic `202 Accepted` result regardless of Account existence, status, persistence
  outcome, or uniqueness race and never overwrites existing identity state.
- HTTP responses, externally observable timing, logs, metrics, and requester-visible telemetry do
  not disclose Account existence. The deliberate no-implicit-resend rule means that a party who
  controls the registered mailbox may observe the documented mailbox-side exception.
- Verification requires purpose-specific persistence, uniqueness and concurrency constraints, and
  terminal-record cleanup. Their concrete schema and repository operations will be specified before
  implementation.
- SHA-256 digest lookup protects high-entropy verification tokens at rest without applying a slow
  password hash to an unguessable 256-bit value.
- PostgreSQL-backed abuse controls add database operations and cleanup requirements but remain
  consistent across instances and restarts without a new Redis service.
- Best-effort after-commit email delivery has a process-crash loss window. A pending Account and the
  separately throttled resend flow provide recovery; stronger durable delivery remains future ADR
  work.
- Production and non-loopback verification links require HTTPS. The existing local HTTP frontend
  default remains valid only for the explicit loopback development exception and cannot be used as
  production configuration or with a production mail provider.
- FH-012 remains responsible for credential matching, compatibility with previously approved
  identifiers, unsupported-identifier handling at login, and upgrade-on-login. FH-013 remains
  responsible for password change, reset, history, and reuse.
- The FH-011 specification must still define the public API, error codes, HTTP statuses,
  representations, and retry headers consistent with the existing API-error conventions; exact
  blocklist corpus, licensing, checksum, versioning, update and rejection behavior; timing
  equalization; persistence schema and indexes; email templates, locale behavior, retry and
  observability details; rate-limit values, windows, responses, identifier protection, retention,
  configuration and cleanup; token cleanup scheduling; and benchmark evidence for any upward
  Argon2id calibration.
- This Accepted ADR does not authorize implementation. It must be accepted, and the FH-011
  specification must be approved, before code, configuration, dependency, migration, or test work
  begins.
