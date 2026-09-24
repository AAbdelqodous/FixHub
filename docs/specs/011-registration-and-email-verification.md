# FH-011 — Registration and Email Verification

- Status: Approved
- Date: 2026-09-14
- Target module: Identity
- ADR authorities: [ADR 0008](../adr/0008-translation-model.md) and
  [ADR 0011](../adr/0011-registration-password-and-email-verification-security.md)

## Purpose

FH-011 introduces FixHub's first public Account lifecycle. It accepts a registration request,
creates an Account and PASSWORD Credential atomically, proves control of the required email
address through a purpose-specific verification token, and activates an eligible
`PENDING_VERIFICATION` Account.

This specification is approved for implementation as of 2026-09-14. Design approval authorizes
implementation only within this specification and its accepted authorities; it does not establish
implementation acceptance or production-launch authorization.

## Authoritative references

- [ADR 0011](../adr/0011-registration-password-and-email-verification-security.md) defines the
  accepted password-write policy, verification-token protection, enumeration boundary,
  transaction boundary, delivery model, and abuse-control architecture.
- [Identity security roadmap](../design/identity-security-roadmap.md) assigns registration and
  email verification to FH-011 and preserves the FH-012 through FH-014 boundaries.
- [FH-010](010-account-credential-persistence.md) defines the existing Account and PASSWORD
  Credential model, normalization rules, persistence constraints, and closed repository boundary.
- [FH-006](006-api-error-conventions.md), [ADR 0010](../adr/0010-api-error-contract-and-ownership.md),
  [API conventions](../design/api-conventions.md), and the
  [error catalogue](../design/error-catalogue.md) define the public HTTP, RFC 9457 ProblemDetail,
  stable-code, safe-detail, and correlation contracts.
- [ADR 0008](../adr/0008-translation-model.md) defines the authoritative platform localization
  policy, including supported rendering locales, locale resolution, fallback, text direction,
  security, telemetry, translation ownership, and completeness requirements. FH-011 consumes that
  policy and does not establish an Identity-specific localization policy.

If this specification conflicts with ADR 0008 or ADR 0011, the relevant ADR takes precedence and
this specification must be corrected before implementation.

## Scope

FH-011 includes:

- Public registration, verification-token consumption, and verification-email resend APIs.
- Registration password validation, local compromised-password screening, and `{argon2id}`
  credential encoding.
- Transactional creation of Account, PASSWORD Credential, and verification-token state.
- Purpose-specific email-verification token generation, protected persistence, resend,
  consumption, invalidation, expiration, retention, and cleanup.
- Best-effort asynchronous verification-email delivery after transaction commit.
- PostgreSQL-backed abuse controls for all three public operations.
- Endpoint-scoped public access, CSRF, CORS, headers, error, correlation, privacy, and test
  contracts required to expose those operations safely.

## Greenfield password position

FixHub is a fresh application. It has no existing user passwords and no legacy credential hash
formats requiring compatibility or migration.

- FH-011 writes PASSWORD Credentials only as `{argon2id}<encoded-value>`.
- The explicit `DelegatingPasswordEncoder` registry contains only the approved `argon2id` mapping.
- Bcrypt, PBKDF2, scrypt, `noop`, plaintext, legacy, and fallback mappings are not registered.
- A stored credential with a missing or unknown identifier fails closed.
- `DelegatingPasswordEncoder` is retained solely to permit separately reviewed future algorithm
  evolution without changing the Credential storage shape.
- Adding another encoder identifier requires a separately reviewed security decision.
- No password migration, legacy compatibility, or conversion work is required by FH-011.
- FH-012 credential matching initially needs to support only `{argon2id}`.
- Upgrade-on-successful-login remains FH-012 architecture, but FixHub has no legacy input requiring
  an upgrade at launch.

## Public API contract

All FH-011 endpoints:

- Use the `/api/v1` public API prefix.
- Accept only `application/json` with no media-type parameters. JSON is exchanged as UTF-8;
  `charset` parameters, even when they name UTF-8, are rejected rather than interpreted differently
  by different HTTP stacks.
- Require `Content-Encoding` to be absent. Compressed, `identity`, or otherwise encoded request
  content is unsupported for these endpoints.
- Use this authoritative processing order:

  | Stage | Required action and result |
  | --- | --- |
  | 1. Header-only envelope | Handle route/method and applicable CORS preflight; validate `Content-Type`, `Content-Encoding`, declared `Content-Length` when present, and other header-only security checks. Unsupported or parameterized `Content-Type`, any charset parameter, or any `Content-Encoding` returns `415 UNSUPPORTED_MEDIA_TYPE`. A valid declared `Content-Length` over 4,096 returns `413 REQUEST_TOO_LARGE`. Malformed or contradictory framing follows the platform `400 MALFORMED_REQUEST` security handling. |
  | 2. Coarse abuse gate | After stage 1 and before consuming an unknown, chunked, or otherwise apparently acceptable body, evaluate the global/origin stage. An exhausted bucket returns `429` without consuming the body. |
  | 3. Streamed body | For an admitted request, count actual body octets after HTTP transfer framing; transfer-chunk metadata is not content and no content decoding occurs. Stop after byte 4,097. Exactly 4,096 octets may proceed; byte 4,097 returns `413 REQUEST_TOO_LARGE` before JSON parsing. |
  | 4. Representation | Validate UTF-8 without BOM, JSON structure, Unicode scalar structure, and the global unknown-field policy. A failure returns `400 MALFORMED_REQUEST`. |

- Missing, understated, false, or chunked `Content-Length` never bypasses stage 3. A fresh/admitted
  4,097-byte request returns `413`; a request with declared `Content-Length` over 4,096 returns
  `413` before limiting; and an oversized chunked or undeclared request may return `429` without
  body consumption when its independent coarse bucket is exhausted.
- Reject a UTF-8 BOM, malformed UTF-8, malformed JSON, malformed Unicode scalar structure, and
  unpaired UTF-16 surrogates as `400 MALFORMED_REQUEST`.
- Follow the documented global policy of rejecting unknown JSON fields. The repository does not
  yet configure that policy explicitly; establishing the global configuration is an implementation
  prerequisite and must not be implemented as FH-011-private deserialization behavior.
- Return no Account, Credential, persistence, token-record, or repository representation.

The status boundary is exact within the preceding order: envelope media failures use `415`, declared
or admitted-stream size failures use `413`, and an admitted within-limit body that cannot produce the
declared JSON request uses `400`. None depends on Account or token existence.

### Registration

```http
POST /api/v1/registrations
Content-Type: application/json
```

```json
{
  "email": "...",
  "password": "...",
  "preferredLocale": "..."
}
```

The request contains exactly:

| Field | Required | Contract |
|---|---|---|
| `email` | Yes | FH-010 conventional ASCII email syntax; maximum 320 characters; outer whitespace is removed and the complete result is lower-cased with `Locale.ROOT` for lookup |
| `password` | Yes | Raw registration password accepted transiently at this boundary; NFC-normalized and validated under the password policy below |
| `preferredLocale` | No | When present, a syntactically valid, normalized BCP 47 tag; maximum 35 characters |

Before Account creation, the rendering locale follows ADR 0008's registration precedence:

1. An explicit valid `preferredLocale` supplied by the user.
2. An explicit previously selected UI locale.
3. A supported `Accept-Language` match.
4. English.

Persistence and rendering are separate. A present, syntactically valid explicit value is persisted
in `Account.preferredLocale` using FH-010 canonicalization even when it is unsupported for rendering;
ADR 0008 then resolves that preference to an allowlisted rendering locale or English. When the
explicit field is absent, registration persists the canonical resolved supported locale selected by
the remaining precedence, or `en` when no other approved source produces a match. An invalid
explicit value fails validation. Registration never infers either value from nationality, network
origin, phone number, email address or domain, physical location, or another identity attribute.

Registration does not accept `phone`. Optional phone remains Account profile data inherited from
FH-010 and may be collected only through later approved profile functionality. Phone verification
is outside FH-011.

Every request that passes validation, password policy, abuse controls, and required-infrastructure
gates returns an empty `202 Accepted` response. It returns no Account ID, status, email, `Location`
header, monitor URL, or response body. The result is identical whether the Account exists, the
Account's status differs, persistence created a new Account, or an email-uniqueness race was lost.

### Email-verification consumption

```http
POST /api/v1/email-verifications
Content-Type: application/json
```

```json
{
  "token": "<43-character unpadded Base64url token>"
}
```

The token field is required and must contain exactly 43 unpadded Base64url characters representing
the approved 32 random bytes. A first valid consumption returns an empty `204 No Content` response.

A replay of the same consumed token also returns `204 No Content` while its consumed record remains
within the seven-day retention period. It performs no second Account transition. After cleanup,
the token is indistinguishable from an unknown token and receives the generic invalid-token error.

Expired, superseded, unknown, invalidated, and otherwise ineligible token attempts share the same
public error. The response never reveals the token state or Account status.

### Verification-email request and resend

```http
POST /api/v1/email-verification-requests
Content-Type: application/json
```

```json
{
  "email": "..."
}
```

Every request that passes validation, abuse-control, and required-infrastructure gates returns an
empty generic `202 Accepted` response. A missing Account and a `PENDING_VERIFICATION`, `ACTIVE`,
`SUSPENDED`, or `DISABLED` Account are indistinguishable through HTTP responses, headers,
requester-visible telemetry, and externally observable application work other than the intentional
mailbox-side behavior.

Only an eligible `PENDING_VERIFICATION` Account receives a replacement verification token and
email. Registration never implicitly invokes this resend operation.

## Validation and public errors

Every error uses `application/problem+json` and the existing RFC 9457 representation, including
`type`, `title`, `status`, `detail`, `instance`, stable `code`, and `correlationId`. Validation errors
also contain the ordered safe `errors` entries defined by FH-006. Details and validation messages
never echo passwords, tokens, digests, links, credentials, emails, rejected sensitive values,
Account state, or persistence outcomes.

The intended mappings are:

| Condition | HTTP status and stable code | Owner |
|---|---|---|
| Bean or method validation failure, including invalid email, locale, password length, password Unicode, or token shape | `400 VALIDATION_ERROR` | Common, existing |
| UTF-8 BOM, malformed UTF-8/Unicode/JSON, or unknown JSON field under the global rejection policy | `400 MALFORMED_REQUEST` | Common, existing |
| Request body exceeds 4,096 bytes | `413 REQUEST_TOO_LARGE` | Common, catalogue code |
| Unsupported or parameterized `Content-Type`, any charset parameter, or any `Content-Encoding` | `415 UNSUPPORTED_MEDIA_TYPE` | Common, existing |
| Complete normalized password is present in the approved blocklist | `400 IDENTITY_PASSWORD_BLOCKED` | Identity |
| Registration abuse limit is exceeded | `429 IDENTITY_REGISTRATION_RATE_LIMITED` | Identity |
| Registration cannot safely evaluate required infrastructure or Argon2id admission capacity is saturated | `503 IDENTITY_REGISTRATION_UNAVAILABLE` | Identity |
| Verification token is expired, superseded, unknown, invalidated, or ineligible | `400 IDENTITY_VERIFICATION_TOKEN_INVALID` | Identity |
| Resend or token-verification abuse limit is exceeded | `429 IDENTITY_VERIFICATION_RATE_LIMITED` | Identity |
| Resend or verification cannot safely evaluate required infrastructure | `503 IDENTITY_VERIFICATION_UNAVAILABLE` | Identity |

No separate public code is created for an existing email, Account status, duplicate phone,
email-uniqueness race, expired versus superseded versus unknown token, or `SUSPENDED` versus
`DISABLED` Account. Because phone is not accepted by registration, duplicate-phone behavior has no
FH-011 public API path.

Known temporary infrastructure failures use the appropriate Identity-owned `503` rather than the
generic `INTERNAL_ERROR`. An unexpected programming failure continues to use the existing safe
`500 INTERNAL_ERROR` boundary.

The authoritative error catalogue includes `REQUEST_TOO_LARGE` and the Identity-owned codes above.
Implementation must preserve their exact status, safe default detail, ownership, and RFC 9457
representation.

## Security and cache headers

Every FH-011 success and error response contains:

- `X-Correlation-ID` with the effective identifier resolved by the existing correlation filter.
- `Cache-Control: no-store`.
- `Pragma: no-cache`.
- `X-Content-Type-Options: nosniff`.
- `Referrer-Policy: no-referrer`.

HTTP Strict Transport Security remains a production HTTPS and platform responsibility rather than
an endpoint-specific FH-011 header policy.

## Registration processing and enumeration resistance

Registration first completes the public-contract stage-1 header-only envelope checks, then uses this
exact remaining order:

1. Apply the coarse network-origin and global abuse pre-gate.
2. Enforce request size and parse JSON.
3. Validate the request fields and reject unknown fields under the global policy.
4. Normalize email using the complete FH-010 rules.
5. Validate Unicode scalar structure and normalize the password to NFC.
6. Validate the normalized password's Unicode code-point length.
7. Compare the complete normalized password with the local blocklist.
8. Apply the normalized-email registration rate limit.
9. Attempt to acquire an Argon2id admission permit without queueing or retaining the raw password.
   If capacity is saturated, return the documented sanitized `503` before Account lookup or
   mutation.
10. Encode the normalized password with the approved Argon2id writer and release the permit on every
    success or failure path.
11. Generate one candidate verification token and digest for every request reaching this step.
12. Look up Account existence by the FH-010 normalized email.
13. Execute transactional persistence for a new Account or the bounded equivalent-work transaction
    for an existing Account; a uniqueness race performs and rolls back the attempted persistence.
14. Select the same generic accepted outcome for a new, existing, or uniqueness-race result;
    existing Accounts are never mutated.
15. Return the empty `202 Accepted` response.
16. For a newly committed registration only, hand the email work to the after-commit asynchronous
    delivery boundary.

New-email and existing-email paths both complete successful request validation, NFC normalization,
complete-value blocklist comparison, Argon2id encoding, and candidate token generation before
Account lookup. The encoded value, raw candidate token, and digest are discarded immediately when
the Account already exists and are never logged, returned, or passed to a repository.

Database uniqueness is authoritative. A losing normalized-email race is classified only after its
transaction rolls back, produces no delivery event, changes no existing Account or Credential, and
returns the same generic accepted result.

Artificial sleeps and random jitter are prohibited. Equivalent security and encoding work,
identical response generation, bounded database interaction, and the objective timing gate below
establish material equivalence without tying request latency to fragile sleep values.

HTTP status, response body, response headers, externally observable timing, and requester-visible
telemetry must not reveal Account existence or status. New registrations receive mail; existing
registrations do not. A mailbox controller's observation of that intentional behavior is the
exception already accepted by ADR 0011.

## Password policy and encoding

### Normalization and validation

- Reject malformed Unicode and unpaired UTF-16 surrogates before normalization.
- Normalize with `Normalizer.normalize(password, Normalizer.Form.NFC)`.
- Count the normalized value using Unicode code points, not UTF-16 code units or encoded bytes.
- Require 15 through 128 code points, inclusive.
- Preserve every normalized character, including leading, trailing, embedded, and repeated
  whitespace.
- Never trim, truncate, change case, or apply another transformation after NFC normalization.
- Apply no uppercase, lowercase, number, symbol, or other composition rule.
- Compare the complete normalized value with the blocklist; never reject a substring merely because
  it occurs within a longer password.
- Pass the identical normalized value to the encoder.
- Client contracts must continue to support password managers, paste, and autofill.

### Encoder configuration

Identity configures an explicit `DelegatingPasswordEncoder` whose write identifier and sole current
mapping are `argon2id`. The mapped encoder is exactly:

```java
new Argon2PasswordEncoder(16, 32, 1, 19_456, 2)
```

The arguments are salt length 16 bytes, hash length 32 bytes, parallelism 1, memory 19,456 KiB, and
iterations 2. This exact construction is the approved minimum baseline. The reviewed Spring
implementation emits Argon2id version 19. The complete stored Credential value uses the
`{argon2id}` prefix and remains within FH-010's 512-character limit.

Bouncy Castle is a required implementation dependency. No configured environment may lower any
security parameter below ADR 0011. A separately reviewed calibration may increase cost parameters.

### Benchmark evidence

Benchmarking uses Java 21 and a production-equivalent container, JVM, processor allocation, memory
limit, Spring Security version, and Bouncy Castle version.

- Execute at least 30 warm-up operations and 100 measured operations for every measured scenario.
- Measure concurrency 1, 2, 4, and each candidate supported maximum.
- Record p50, p95, processor utilization, and native-memory consumption without requiring an
  increased work factor merely to force p50 into a target range.
- Require p95 no greater than 1,500 milliseconds at supported concurrency.
- Select the production concurrency bound only from this evidence. At the selected maximum,
  aggregate Argon2 working memory, including measured native allocation rather than only the nominal
  parameter multiplication, must not exceed 25 percent of the container memory limit.
- Record runtime versions, container limits, processor allocation, concurrency, sample count,
  parameters, p50, p95, processor utilization, and peak heap and native-memory evidence in the
  implementation review.

Benchmark evidence is reviewed separately from the canonical automated suite. It is not a brittle
CI wall-clock gate. A result below a historical latency target does not require increasing the work
factor. Failure to meet the p95, memory, or capacity bounds requires capacity review and a lower
concurrency bound or additional deployment capacity; it never permits parameters below the accepted
baseline. Any upward calibration remains a separate reviewed decision.

### Argon2id admission control and saturation

Registration protects the memory-hard encoder with a bounded admission control whose maximum is the
production concurrency value approved from the benchmark evidence. Permit acquisition does not wait
in a queue: a raw or normalized password remains only on its request thread for the minimum policy
and encoding lifetime and is never captured in queued work.

When no permit is immediately available, processing stops before Argon2id encoding, Account lookup,
or any Account, Credential, token, or limiter mutation after the already completed abuse gates. The
response is a sanitized RFC 9457 `ProblemDetail` with HTTP `503 Service Unavailable`, stable code
`IDENTITY_REGISTRATION_UNAVAILABLE`, the standard safe fields and headers, and a positive
delta-seconds `Retry-After` derived from the reviewed saturation-recovery setting. It is never an
undocumented empty `503`. Permit release is guaranteed on every encoding success, rejection, or
exception path. Saturation metrics are aggregate and bounded and contain no email, password,
Account, or existence-sensitive labels.

## Password blocklist

Identity owns the password-policy/checker port and one offline-generated local digest artifact. Its
authoritative external input is one dated Have I Been Pwned Pwned Passwords SHA-1 count-corpus
release. The construction algorithm, exact 100,000 HIBP selection count, normalization, sorting,
format, integrity validation, and fail-closed behavior below are closed specification decisions. A
moving `latest` reference is insufficient for an implementation: its version-controlled manifest
must pin the concrete source URL, UTC retrieval date, and SHA-256 of the downloaded source bytes.

The construction contract is deterministic:

1. Parse the pinned SHA-1 count corpus as records containing one 40-character hexadecimal SHA-1
   hash and one non-negative decimal occurrence count. Normalize source hashes to uppercase; reject
   malformed or duplicate source hashes rather than guessing or combining counts.
2. Sort all source records by descending occurrence count and then ascending uppercase SHA-1 hash.
3. Select exactly the first 100,000 records after that complete ordering. A frequency tie at the
   selection boundary is therefore resolved solely by ascending SHA-1 hash.
4. Merge any separately approved digest-only FixHub-context complete values, remove exact duplicate
   hashes, and sort the final unique set by ascending uppercase SHA-1 hash. The manifest records the
   100,000 HIBP selections, supplemental count, duplicate-removal count, and final entry count
   separately.
5. Write only uppercase 40-character hexadecimal SHA-1 hashes, one per line, as ASCII-compatible
   UTF-8 with LF (`0x0A`) line endings and one final LF. Counts, plaintext values, comments, blank
   lines, a BOM, and CRLF line endings are prohibited in the runtime artifact.

The selected HIBP entry count is fixed at exactly 100,000. The final artifact entry count is the
selected HIBP count plus the net approved supplemental entries after duplicate removal and is
therefore constrained to the inclusive range 100,000..200,000. Configuration cannot reduce the
minimum or increase the maximum. Each record contributes exactly 40 uppercase hexadecimal digest
characters plus one LF byte, so the maximum artifact size is exactly 8,200,000 bytes. Increasing
the maximum requires an approved FH-011 specification change together with security and capacity
review.

Implementation clarification (2026-09-16; status Approved): synthetic unit-parser fixtures may use
smaller counts only through a non-runtime-reachable test seam. Spring context tests must exercise
the real production policy with a dynamically generated 100,000-entry artifact.

For a registration candidate, the lookup key is exactly:

```text
uppercase-hex(SHA-1(UTF-8(NFC(password))))
```

SHA-1 is used only for equality with the pinned HIBP representation; Credential persistence still
uses the approved Argon2id encoder. Request-derived lookup material is transient and is never
persisted, logged, traced, returned, or included in an exception. Comparison is equality against the
complete normalized password's lookup digest, never substring or word matching. No corpus download
or HIBP request occurs during build, startup, or registration, and no plaintext corpus or
FixHub-context password is committed.

The versioned manifest records the exact source URL and retrieval date, source-artifact SHA-256,
transformation procedure and artifact-format version, generator version or exact source commit,
ranking and tie-break rules, final-artifact SHA-256, HIBP selection count, supplemental and final
entry counts, and licence/provenance review result. Given the pinned source bytes, approved
supplemental digest set, manifest procedure, and generator version, two constructions must produce
byte-identical artifacts and the same final SHA-256.

The concrete dated release, retrieval date, source URL, source and final SHA-256 values,
transformation-tool version, final entry count, provenance/licence review, and any supplemental
digest set are mandatory implementation and deployment evidence, not permanently fixed values in
this long-lived specification. The implementation cannot be accepted and production startup cannot
be authorized without the complete reviewed manifest and its matching artifact. Every corpus
replacement must regenerate and review that manifest; it does not require rewriting this
specification when the construction contract remains unchanged.

License and usage rights must be verified and recorded before the corpus artifact is committed. A
reviewed quarterly security pull request updates the snapshot. An emergency reviewed security pull
request may update it sooner when material exposure warrants action. Updates regenerate and review
the complete artifact, manifest, checksum, and acceptance evidence; runtime code never mutates the
artifact.

Missing, unreadable, malformed, non-UTF-8, BOM-bearing, CRLF-bearing, unsorted, duplicate,
wrong-version, record-count-mismatched, source-checksum-invalid, or final-checksum-invalid material
prevents application startup. The public rejection is
`400 IDENTITY_PASSWORD_BLOCKED` with a safe diagnostic detail that does not expose the password,
digest, corpus source entry, occurrence count, or FixHub-specific rule.

Deterministic generator tests use synthetic hashes and counts to prove count-descending/hash-ascending
ordering, boundary ties, exact 100,000 selection, supplemental deduplication, LF output, final count,
and byte-identical repeated construction. Loader tests use synthetic artifacts and manifests to prove
successful lookup of `SHA-1(UTF-8(NFC(password)))` and fail-closed handling for every format,
version, count, ordering, duplication, encoding, and checksum defect without committing a real or
production-like password.

## Verification-token model

Each email-verification token uses 32 CSPRNG bytes and is transported as a 43-character unpadded
Base64url value. Identity calculates a SHA-256 digest and persists only the 32-byte digest. The raw
token exists only transiently for link construction and delivery.

A token is valid for 24 hours. At most one open verification token exists per Account. An open token
has null `terminalReason` and `terminalAt`; its current usability additionally requires
`expiresAt` to be later than the authoritative injected-clock instant and its Account to be eligible.
Terminal records have an exact seven-day policy retention period: they become deletion-eligible at
`terminal_at + seven days` and scheduled cleanup physically deletes them within the ADR-0011
one-hour healthy-operation SLO. Cleanup delay is operational execution tolerance, never an
extension or configuration of the seven-day retention policy. For this lifecycle, `terminal_at` is
the persisted effective terminal instant.

Implementation clarification (2026-09-16; status Approved): `terminal_at` is the canonical persisted
PostgreSQL column and `terminalAt` is its Java/JPA field. Earlier prose references to
`terminalized_at` were aliases for that same persisted timestamp; they do not describe or authorize
a second database column. The effective terminal time is written when a token enters `CONSUMED`,
`SUPERSEDED`, `EXPIRED`, or `INVALIDATED`. The seven-day retention policy, cleanup scheduling,
one-hour healthy-operation SLO, and 24-hour incident threshold remain unchanged, and no token
lifecycle behavior changes.

Allowed terminal reasons are:

- `CONSUMED` — successfully performed the eligible Account activation.
- `SUPERSEDED` — replaced atomically by a resend token.
- `EXPIRED` — passed its expiry without successful consumption.
- `INVALIDATED` — presented while linked to an `ACTIVE`, `SUSPENDED`, `DISABLED`, or otherwise
  ineligible Account.

No recovery, access, refresh, session, invitation, or generic-purpose token shares this model.

## V4 verification-token persistence

FH-011 adds:

```text
V4__create_identity_email_verification_tokens.sql
```

The migration creates `identity_email_verification_tokens` with one row per issued token and
retained terminal history.

| Conceptual field | Required database contract |
|---|---|
| `id` | `BIGINT GENERATED BY DEFAULT AS IDENTITY`, primary key |
| `account_id` | `BIGINT NOT NULL`, restrictive foreign key to `identity_accounts(id)` |
| `token_digest` | `BYTEA NOT NULL`, exactly 32 bytes, globally unique |
| `expires_at` | `TIMESTAMP(6) WITH TIME ZONE NOT NULL` |
| `terminal_reason` | Nullable `VARCHAR(16)`, limited to `CONSUMED`, `SUPERSEDED`, `EXPIRED`, or `INVALIDATED`; entity field `terminalReason` maps the four-value enum with `EnumType.STRING` and length 16 |
| `terminal_at` | Nullable `TIMESTAMP(6) WITH TIME ZONE` |
| `version` | `BIGINT NOT NULL`, JPA optimistic-lock value |
| `created_at` | `TIMESTAMP(6) WITH TIME ZONE NOT NULL`, inherited audit instant |
| `updated_at` | `TIMESTAMP(6) WITH TIME ZONE NOT NULL`, inherited audit instant |

The migration uses stable names for:

- Primary key `pk_identity_email_verification_tokens`.
- Foreign key `fk_identity_email_verification_tokens_account` with `ON DELETE RESTRICT`.
- Unique digest constraint `uq_identity_email_verification_tokens_digest`.
- Digest-length check `ck_identity_email_verification_tokens_digest_length`.
- Terminal-reason check `ck_identity_email_verification_tokens_terminal_reason`.
- Null-pair check `ck_identity_email_verification_tokens_terminal_pair`, requiring reason and time
  to be both null or both populated.
- Expiry-order check `ck_identity_email_verification_tokens_expiry_after_creation`.
- Version check `ck_identity_email_verification_tokens_version_non_negative`.
- Partial unique index `uq_identity_email_verification_tokens_open_account` on `account_id` where
  `terminal_reason IS NULL`.
- Partial expiry index `ix_identity_email_verification_tokens_open_expires_at` on
  `(expires_at, id)` where `terminal_reason IS NULL`, supporting ordered open-token expiration.
- Partial cleanup index `ix_identity_email_verification_tokens_terminal_at` on `(terminal_at, id)`
  where `terminal_reason IS NOT NULL`, supporting ordered terminal-retention deletion.

The entity maps to `identity_email_verification_tokens`; its lazy, required Account association maps
`account_id`, its digest maps a 32-byte array to PostgreSQL `BYTEA`, `expiresAt` and `terminalAt` map
to `Instant`, `terminalReason` uses the four-value string enum above, and `version` is the non-null
JPA `@Version`. Inherited `createdAt` and `updatedAt` retain the existing `Instant` audit mapping.
Flyway owns the physical schema and Hibernate validates rather than creates or updates it.

The table contains no raw token and no token-purpose discriminator. It is exclusively the
email-verification lifecycle. Account deletion remains unsupported, and neither the Account nor
verification-token repository exposes a general hard-delete operation.

## Registration, resend, consumption, and cleanup

### Registration transaction

Password normalization, validation, blocklist comparison, and Argon2id encoding complete at the
application boundary before the identity-persistence transaction begins. The transaction persists:

1. A new Account in `PENDING_VERIFICATION`.
2. Its one PASSWORD Credential containing the `{argon2id}` encoded value.
3. Its initial email-verification record containing only the digest.

Any persistence failure rolls back all three. A uniqueness exception is classified only after the
transaction has rolled back. A losing race returns the generic accepted outcome and creates no mail
event. SMTP is never called inside this transaction.

### Resend transaction

Resend first completes the public-contract stage-1 header-only envelope checks, then uses this exact
remaining order:

1. Apply and commit the coarse origin and global rate-limit stage.
2. Enforce media type and request size, parse JSON, validate the field set, and normalize email
   without Account-existence information.
3. Apply and commit the resend-email cooldown and 24-hour limit.
4. Generate a candidate 32-byte token and SHA-256 digest for every request reaching this step.
5. Look up the Account without exposing the result.
6. If an Account is found, lock it using the common lifecycle lock order, query its open token in the
   same fixed repository shape for every Account status, and recheck `PENDING_VERIFICATION`
   eligibility.
7. For an eligible Account, mark the current open token `SUPERSEDED` and insert the replacement
   digest and 24-hour expiry in the same transaction. For a missing or ineligible Account, perform
   the bounded equivalent-work transaction without mutating Account, Credential, or token state.
8. Commit before selecting the generic empty `202` response. Only an eligible commit publishes the
   after-commit delivery event.

Concurrent resend operations therefore serialize on the Account. A missing or ineligible Account
causes no Account, Credential, or token mutation and returns the same generic accepted response. Its
candidate raw token and digest are discarded immediately and never cross the delivery boundary.

### Consumption transaction

Verification consumption first completes the public-contract stage-1 header-only envelope checks,
then uses this exact remaining order:

1. Apply and commit the coarse origin and global rate-limit stage.
2. Enforce media type and request size, parse JSON, validate the exact 43-character transport shape,
   and decode it without consulting token or Account state.
3. Calculate the SHA-256 digest for every successfully decoded value.
4. Perform the purpose-specific unique-digest lookup.
5. Use constant-time byte comparison between the submitted digest and either the located stored
   digest or a fixed process-local random 32-byte dummy digest. The dummy is generated at startup,
   is never persisted or exposed, and cannot select an Account or token.
6. If a record exists, identify and lock its Account using the same Account-first lifecycle order as
   resend, then re-read or conditionally update the token and recheck expiry, terminal state, and
   Account eligibility using the injected clock. If no record exists, perform the bounded
   equivalent-work transaction without a fake Account or token.
7. For an eligible token, mark it `CONSUMED` and invoke the Account's explicit
   `PENDING_VERIFICATION` to `ACTIVE` behavior in the same transaction. Apply the already documented
   replay and generic-invalid results for the other states.

Exactly one concurrent request performs consumption and activation. A concurrent replay observes
the committed consumed record and returns `204` without another transition.

If an open token is presented for an `ACTIVE`, `SUSPENDED`, `DISABLED`, or otherwise ineligible
Account, the same transaction marks the token `INVALIDATED` and leaves Account status unchanged.
The caller receives `IDENTITY_VERIFICATION_TOKEN_INVALID`, which reveals no Account status.

### Equivalent work and timing evidence

The bounded equivalent-work transaction begins and commits through the same Identity transaction
manager and executes exactly one parameter-free PostgreSQL statement that returns
`transaction_timestamp()` and the length of a fixed 32-byte constant. It reads or writes no Identity
table, takes no Account or token identifier, persists no fake Account, Credential, or token, and
emits no event. Registration uses it for an existing Account after performing the same validation,
blocklist, Argon2id, and candidate-token work as a new Account. Resend uses it for missing and
ineligible Accounts after the same independent gates and candidate-token work. Verification uses it
for an unknown digest after the same decoding, digest, lookup, and constant-time comparison
structure.

Within each documented public result class, status, body, headers, response construction, logs,
metrics, traces, and requester-visible telemetry are identical: registration and resend use the
generic empty `202`; expired, superseded, unknown, invalidated, and otherwise ineligible verification
tokens use the generic invalid-token ProblemDetail; and a first valid consumption or retained
consumed-token replay uses the documented empty `204`. These already approved result classes are not
collapsed or expanded by timing equalization.

FH-011 introduces no artificial public-response jitter or sleep. Before production, timing
equivalence for registration is an objective production-equivalent gate:

1. Warm the application and database, then use the same application, container, database topology,
   and configuration planned for production.
2. Randomly interleave at least 500 new-Account and 500 existing-Account registration observations,
   using synthetic addresses and passwords only. Exclude routing outside the controlled test boundary
   while retaining the actual HTTP, application, Argon2id, and PostgreSQL paths.
3. Measure complete requester-observable response duration and compare p50 and p95 distributions.
   For each percentile, the absolute state-class difference must be no greater than
   `max(50 milliseconds, 10 percent of the slower state-class value)`. Cliff's delta must satisfy
   `|delta| <= 0.147`.
4. Record sample counts, environment, configuration, sanitized raw timing observations, and the
   calculation method as pre-production evidence. Evidence must not contain email addresses,
   passwords, tokens, raw locale values, or limiter identifiers.

If this gate fails, production deployment is blocked. First confirm identical rate-limit, Argon2id
admission, and measurement configuration; then add or adjust bounded state-independent work,
including a rollback-only equivalent database transaction if necessary. Such work must not persist
fake Identity records, consume production identifiers, send email, or use real user data. Repeat the
full measurement. If the requirement still cannot safely be met, reopen ADR 0011 through an explicit
amendment; do not add undocumented sleep or random jitter. CI may verify the harness and
calculations but ordinary CI wall-clock tests must not enforce this production threshold.

Public behavior never reveals Account existence or state, or distinguishes token existence or
terminal reason within a documented result class, through timing or diagnostics. The intentional
mailbox-side distinction—new or eligible pending work can send mail while existing, missing, or
ineligible work does not—remains the unavoidable exception accepted by ADR 0011 and is visible only
to the mailbox controller.

### Cleanup

Verification cleanup uses fixed-rate elapsed-duration scheduling, never calendar or cron scheduling.
An attempt begins within one minute after the application becomes ready; later attempts are scheduled
from their scheduled start times at the configured interval. Cadence is independent of JVM,
operating-system, server, container, and default timezones; UTC calendar boundaries; and daylight-
saving transitions. Database transaction time remains authoritative for row eligibility.

A single application instance never runs overlapping cleanup executions. If an execution remains
active at its next tick, it continues processing batches until exhaustion and the instance does not
start a second execution. Delayed, skipped, and failed attempts are observable; scheduler failure
does not alter the seven-day policy. Each execution captures one PostgreSQL transaction timestamp,
uses `FOR UPDATE SKIP LOCKED` so concurrent application instances cannot incorrectly process the
same row, selects eligible terminal rows in bounded batches of 100 through 10,000, and repeats
batches until no eligible row remains for that timestamp. No in-memory cluster-wide lock, Redis,
Quartz, distributed scheduler, table, or infrastructure component is introduced. Batch size controls
transaction size only, not run completeness. A row is eligible when its effective terminal instant
plus seven days is less than or equal to that execution's transaction timestamp; under healthy
application and database operation it must be physically deleted within one hour of eligibility. A
cleanup failure or eligible backlog older than one hour emits a bounded, sanitized warning/alert;
backlog older than 24 hours is a critical retention incident. Neither telemetry nor alerts may
include token digests, Accounts, or other sensitive identifiers.

Cleanup is idempotent and retry-safe. At execution start it captures one authoritative PostgreSQL
`transaction_timestamp()` value and reuses that immutable eligibility cutoff for every batch in that
execution. Each bounded batch may use a separate short database transaction; selecting and deleting
that batch is atomic. A batch selects eligible terminal rows with `FOR UPDATE SKIP LOCKED` and deletes
only selected rows. Failure before a batch commits rolls back that batch. If a batch has committed,
its rows remain deleted; a retry continues with remaining eligible rows, and a retry that encounters
an already deleted row safely affects zero rows. Reprocessing the same cutoff cannot create, restore,
mutate, or terminalize a token, and produces no per-token email, event, or external side effect.
Multiple instances and repeated executions are therefore safe without duplicate side effects.
Aggregate metrics use database affected-row counts only and never identify an Account or token digest.

- Open expiration selects through `ix_identity_email_verification_tokens_open_expires_at` in
  `(expires_at, id)` order. An open record whose expiry has passed becomes `EXPIRED` with
  `terminal_at = expires_at`.
- A consumed record's effective terminal time is its consumption time.
- A superseded record's effective terminal time is its supersession time.
- An invalidated record's effective terminal time is its invalidation time.
- Terminal deletion selects through `ix_identity_email_verification_tokens_terminal_at` in
  `(terminal_at, id)` order. A terminal record is eligible exactly at its effective terminal time
  plus seven days; every execution continues bounded batches until no eligible row remains.
- Cleanup never clears terminal fields or otherwise restores token validity.
- Repository deletion is limited to this terminal verification-record cleanup operation.

## Account behavior and repository boundaries

Account adds one explicit internal behavior for email verification. It transitions only
`PENDING_VERIFICATION` to `ACTIVE` and rejects any other source state. It does not gain a general
status setter.

Identity entities, repositories, token records, encoded Credentials, and mutable Account state
remain internal to the closed module. Public controllers map request DTOs into application commands
and return only the documented empty responses. Delivery adapters receive only minimum immutable
delivery data and never receive an Account, Credential, verification entity, or repository.

Existing FH-010 repository operations may be extended only with the internal lookup and locking
operations required by this specification. No repository may expose Account deletion, Credential
deletion, arbitrary bulk status changes, raw passwords, encoded-secret projections, or raw token
material.

## Email delivery

### Port and adapter

Identity owns `VerificationEmailPort`. Its immutable delivery command and preceding event contain
only the minimum recipient, canonical resolved locale, expiry, and secret-safe link data required
by the adapter. The command and event must not generate a `toString`, equality diagnostic,
exception, or log representation that reveals an email address, raw token, digest, or complete
link.

The implementation uses Spring Mail and `JavaMailSender`. Local development uses the existing
MailDev SMTP service. Production SMTP host, port, authentication, TLS, sender identity, and finite
timeouts remain externally configurable; FH-011 does not select a production provider or add a
provider-specific SDK.

### Trusted verification link

The link is built only as:

```text
{trustedFrontendOrigin}/verify-email#{token}
```

- The request `Host`, `Forwarded`, and `X-Forwarded-Host` headers never influence the link.
- Production and every non-loopback environment require an HTTPS frontend URL.
- An explicitly local profile may use plain HTTP only when the configured host is exactly
  `localhost` or `127.0.0.1`.
- Local HTTP links must not use production mail infrastructure.
- Startup validates scheme, host, path composition, profile, and the absence of embedded user
  information or an existing fragment.
- The accepted local default is `http://localhost:8081`. Another loopback local origin requires an
  explicit local configuration override and must not silently replace that documented default.
- The frontend extracts the fragment, removes it from the visible browser URL, and submits the token
  in the verification POST body.

### Templates and locale

Identity provides a localized subject, plain-text verification body, and HTML verification body for
each of ADR 0008's five platform launch rendering locales:

| Language | Locale | Direction |
| -------- | ------ | --------- |
| Arabic   | `ar`   | RTL       |
| English  | `en`   | LTR       |
| Hindi    | `hi`   | LTR       |
| Urdu     | `ur`   | RTL       |
| Bengali  | `bn`   | LTR       |

FH-011 references the authoritative platform configuration and does not own or redefine its
parsing or lifecycle:

```properties
fixhub.localization.supported-locales=ar,en,hi,ur,bn
fixhub.localization.default-locale=en
```

For a new registration before an Account exists, the canonical resolved locale follows ADR 0008's
registration and anonymous precedence:

1. An explicit validated locale selected or submitted by the user.
2. An explicit previously selected UI locale.
3. A supported `Accept-Language` match.
4. English.

When the optional registration `preferredLocale` is present, the request persists that canonical,
validated BCP 47 value under FH-010, including a valid unsupported preference. When it is absent,
registration persists the resolved supported UI or `Accept-Language` match, or `en`. Verification-
email rendering always uses the separate resolved allowlisted locale; a higher-precedence valid
unsupported preference resolves to English rather than becoming a resource name.

For resend and any other Account-associated asynchronous email, resolution uses the persisted
`Account.preferredLocale` and then English. A valid unsupported preference is not corruption and
resolves to English. A malformed persisted preference also resolves safely to English with the
sanitized integrity handling required by ADR 0008.

FH-011 delegates general parsing, alias handling, deterministic fallback, environmental fallback,
telemetry, and resource-selection security to ADR 0008. Missing, malformed, or unsupported
`Accept-Language` never causes `400` or `406` solely because of that header. Locale selection must
not change registration, resend, verification, rate-limit, enumeration-resistance, token, HTTP
status, stable error-code, or other domain behavior. It must never infer language from nationality,
IP address, location, email address, or phone number.

Every locale resource set contains a localized subject, UTF-8 plain-text body, and UTF-8 HTML body.
Each body contains exactly one verification-link placeholder, and the subject contains none. Every
HTML template declares the correct normalized `lang` value and `dir="rtl"` or `dir="ltr"` metadata.
Arabic and Urdu render RTL; English, Hindi, and Bengali render LTR. Dynamic content requires
contextual escaping, and email addresses, URLs, identifiers, and other LTR values embedded in
Arabic or Urdu require bidirectional isolation. Committed templates, fixtures, and assertions must
not contain passwords, token values, credentials, or production-like secrets.

At startup, Identity validates all five required locale resource sets for presence, UTF-8
correctness, subject/body and placeholder parity, direction and language metadata, safe HTML
rendering, and secret-safe content. A missing or invalid mandatory resource prevents application
startup rather than displaying a translation key.

Production translations for every required subject and body require the qualified human language
and security review defined by ADR 0008; automated translation or automated key validation alone
is insufficient. API error codes remain stable and language-neutral. Full UI translation and
localized API-message delivery remain outside FH-011. Additional language packs remain governed by
ADR 0008's deferred-expansion decision and are not FH-011 launch requirements.

The subject and body identify FixHub, explain that the link expires after 24 hours, state that only
the most recently issued link remains valid, and explain that an unexpected request may be ignored.
Templates contain no tracking links, tracking pixels, Account status, password information,
diagnostic data, or token outside the one approved fragment-bearing link.

### After-commit asynchronous delivery

Registration and eligible resend transactions publish an immutable internal event. A
`@TransactionalEventListener` bound to `AFTER_COMMIT` hands it to a separate bounded asynchronous
executor. The event captures the canonical resolved locale explicitly before crossing the
asynchronous boundary. Delivery must not depend on thread-local request locale state, JVM default
locale, operating-system or server locale, raw request locale input, or raw `Accept-Language`.
No fallback execution occurs without a successful transaction.

Default executor and retry settings are:

| Setting | Default |
|---|---:|
| Core threads | 2 |
| Maximum threads | 4 |
| Queue capacity | 100 |
| Rejection behavior | Reject; never caller-runs |
| Attempts | Initial attempt plus two retries |
| Retry delays | 250 milliseconds, then 1 second, each plus uniformly selected additional jitter from zero through 10 percent of that delay |
| Shutdown wait | 10 seconds |

SMTP connection, read, and write timeouts are finite and externally configurable. Queue saturation,
shutdown loss, or final delivery failure leaves the Account pending and its active token usable.
It produces only a sanitized log associated with the correlation identifier and aggregate metrics
without recipient, Account, token, link, status, or existence-sensitive labels. It does not change
the public registration or resend response. Recovery occurs through the throttled resend operation.

FH-011 introduces no transactional outbox, durable queue, delivery table, or durable retry store.
The accepted best-effort boundary retains the documented process-crash window.

## Rate limiting

FH-011 uses shared PostgreSQL fixed-window counters with atomic updates and canonical lock ordering.
All applicable buckets for one request are evaluated consistently; failure to evaluate them fails
closed before Account, Credential, verification-token, resend, or activation state is mutated.

### Initial policy

| Operation and dimension | Default limit | Window or cooldown |
|---|---:|---|
| Registration by normalized email | 5 | 1 hour |
| Registration by network origin | 100 | 1 hour |
| Resend by normalized email | 1, with a maximum of 5 | 5-minute cooldown and 24-hour window |
| Resend by network origin | 60 | 1 hour |
| Verification by network origin | 60 | 15 minutes |
| All FH-011 commands globally | 300 | 1 minute |

These values are externally configurable launch defaults, not permanent architectural invariants.
Operations may tighten them in response to capacity or abuse. Raising a value requires reviewed
security and capacity approval. Before an origin limit is enabled in production, deployment evidence
must exercise expected Gulf carrier-grade NAT, enterprise, campus, IPv4, and IPv6 sharing patterns
and confirm that the selected production value has acceptable denial and abuse characteristics. No
limit causes Account lockout or changes Account or Credential state.

### Identifier protection

Normalized-email and canonical network-origin keys are transformed with HMAC-SHA-256 using a
dedicated externally supplied secret of at least 32 random bytes and an explicit positive key
version. HMAC input uses this unambiguous byte sequence:

```text
UTF-8("FH011-RATE-V1")
|| uint32be(policy-byte-length) || UTF-8(policy)
|| uint32be(dimension-byte-length) || UTF-8(dimension)
|| uint32be(identifier-byte-length) || canonical-identifier-bytes
```

A normalized FH-010 ASCII email uses its UTF-8 bytes. A network origin uses one address-family byte
followed by the canonical 4-byte IPv4 or 16-byte IPv6 network representation; an IPv4-mapped IPv6
address is reduced to IPv4. Hostnames, ports, zone identifiers, and non-canonical textual address
forms never enter the HMAC. The global bucket uses the fixed canonical `FH011_GLOBAL` identifier
bytes and no request-derived value. Policy and dimension domain separation prevents cross-policy
digest linkage. The database never stores a raw email, normalized email, IP address, proxy chain, or
reversible limiting identifier. The HMAC key is never stored in source control, database rows, logs,
metrics, traces, exceptions, or public configuration output.

### Network-origin resolution

Each deployment selects and validates one ingress profile. A direct profile uses the socket peer and
does not consume forwarding headers. A proxied profile enables exactly one normalized forwarding
header family emitted by its ingress; FH-011 does not hard-code either RFC `Forwarded` or
`X-Forwarded-For` as the universal production choice.

- The ingress strips every client-supplied forwarding header before emitting the selected canonical
  representation. It must not pass through, append to, or combine an untrusted client chain.
- The application trusts forwarding data only when the immediate socket peer belongs to the
  profile's explicit configured ingress CIDR allowlist.
- The application ignores all forwarding headers from an untrusted direct peer and uses that socket
  peer as the origin.
- For a trusted ingress, exactly the selected header family must be present and unambiguous. A
  missing, malformed, duplicate, conflicting, or otherwise ambiguous required representation fails
  closed before identity-lifecycle mutation.
- The application walks a valid trusted chain from the application outward and selects the last
  untrusted hop as the client origin.
- The selected address is converted to the documented canonical IPv4 or IPv6 representation before
  the HMAC operation.

A trusted-peer forwarding-contract failure returns the endpoint-appropriate sanitized RFC 9457
`503` ProblemDetail: `IDENTITY_REGISTRATION_UNAVAILABLE` for registration or
`IDENTITY_VERIFICATION_UNAVAILABLE` for resend and consumption. It includes the configured
infrastructure-failure `Retry-After` and never reflects a forwarding value, address, chain, or parser
diagnostic.

This resolver remains scoped to the three FH-011 endpoints. It does not establish a reusable
platform-wide proxy convention. A later cross-module convention requires its own reviewed ADR.

### V5 persistence and fixed-window model

FH-011 adds:

```text
V5__create_identity_rate_limit_buckets.sql
```

The migration creates `identity_rate_limit_buckets` with this exact physical contract:

| Field | Required database contract |
|---|---|
| `id` | `BIGINT GENERATED BY DEFAULT AS IDENTITY`, primary key |
| `policy` | `VARCHAR(32) NOT NULL`; one of `REGISTRATION_EMAIL`, `REGISTRATION_ORIGIN`, `RESEND_EMAIL`, `RESEND_ORIGIN`, `VERIFICATION_ORIGIN`, or `FH011_GLOBAL` |
| `key_version` | `INTEGER NOT NULL`, positive HMAC-key version |
| `key_digest` | `BYTEA NOT NULL`, exactly 32 bytes |
| `window_start` | `TIMESTAMP(6) WITH TIME ZONE NOT NULL`, inclusive fixed-window start |
| `window_end` | `TIMESTAMP(6) WITH TIME ZONE NOT NULL`, exclusive fixed-window end |
| `request_count` | `BIGINT NOT NULL`, positive count of admitted requests in this bucket |
| `cooldown_until` | Nullable `TIMESTAMP(6) WITH TIME ZONE`; populated only for `RESEND_EMAIL` and exclusive |
| `retention_expires_at` | `TIMESTAMP(6) WITH TIME ZONE NOT NULL`, no earlier than 24 hours after all window and cooldown effects end |
| `version` | `BIGINT NOT NULL`, non-negative JPA optimistic-lock value |
| `created_at` | `TIMESTAMP(6) WITH TIME ZONE NOT NULL`, inherited audit instant |
| `updated_at` | `TIMESTAMP(6) WITH TIME ZONE NOT NULL`, inherited audit instant |

The migration uses these stable names:

- Primary key `pk_identity_rate_limit_buckets`.
- Policy check `ck_identity_rate_limit_buckets_policy`.
- Positive-key-version check `ck_identity_rate_limit_buckets_key_version_positive`.
- Digest-length check `ck_identity_rate_limit_buckets_digest_length`.
- Window-order check `ck_identity_rate_limit_buckets_window_order`.
- Positive-count check `ck_identity_rate_limit_buckets_count_positive`.
- Cooldown-policy and ordering check `ck_identity_rate_limit_buckets_cooldown`.
- Retention-order check `ck_identity_rate_limit_buckets_retention_order`.
- Version check `ck_identity_rate_limit_buckets_version_non_negative`.
- Unique constraint `uq_identity_rate_limit_buckets_key` on
  `(policy, key_version, key_digest, window_start)`.
- Cleanup index `ix_identity_rate_limit_buckets_retention_expires_at` on
  `(retention_expires_at, id)`.

The entity maps the same table and column names. `policy` uses a six-value `EnumType.STRING` mapping
of length 32; `keyVersion` is an integer; `keyDigest` is a 32-byte array mapped to PostgreSQL
`BYTEA`; all boundary fields map to `Instant`; `requestCount` and the JPA `@Version` map to `long`;
and inherited audit fields preserve the established mapping. Flyway owns the schema and Hibernate
validates it.

Every window is aligned to the UTC Unix epoch. For duration `D` seconds and the transaction instant
`T`, `window_start` is `floor(epochSeconds(T) / D) * D` and `window_end` is exactly
`window_start + D`. The limiter obtains PostgreSQL `transaction_timestamp()` once at the start of
each limiter transaction and uses that same instant for every window, cooldown, retention, and
`Retry-After` decision in that transaction. It never uses a JVM, host, or independently sampled
per-row clock.

### Atomic bucket consumption

For each applicable logical bucket and every active HMAC key version, the limiter performs a
PostgreSQL `INSERT ... ON CONFLICT ... DO UPDATE ... WHERE ... RETURNING` operation. First use inserts
`request_count = 1`. The conflict update increments by exactly one only when the stored count is
below the applicable threshold and, for `RESEND_EMAIL`, the transaction instant is not before
`cooldown_until`. An admitted resend sets `cooldown_until` to the transaction instant plus five
minutes. No returned row means that bucket is exhausted or cooling down.

All rows in one evaluation stage are processed in deterministic
`(policy, key_version, key_digest, window_start)` order inside one database transaction. If any
operation returns no row or fails, that stage rolls back every increment. Consequently, a rejected
attempt does not increment an exhausted bucket, no stored count exceeds its configured threshold,
and concurrent first use cannot create duplicate buckets. The transaction commits before any
Account, Credential, verification-token, resend, or activation mutation.

Registration and resend use two stages:

1. Evaluate and commit their coarse network-origin and `FH011_GLOBAL` buckets before request parsing
   or other expensive work.
2. After successful request, email, Unicode, password, and blocklist validation as applicable,
   evaluate and commit the normalized-email bucket before Argon2id work or Account lookup.

Verification consumption has no email dimension and evaluates its origin and global buckets in one
coarse stage before token lookup. A committed coarse reservation is never refunded when parsing,
validation, blocklist, email limiting, admission control, or later business processing rejects the
request. This preserves abuse accounting without consulting Account or token existence.

### HMAC key rotation

Limiter configuration identifies one current key and may identify one previous key during a
rotation overlap. The previous key remains available for at least 25 hours after the current version
changes, covering the longest approved window or cooldown with operational margin.

During overlap, each applicable policy derives and evaluates both the current-version bucket and the
previous-version bucket for the same canonical identifier and window. Exhaustion of either bucket
rejects the request. An allowed request increments both buckets by exactly one in the same database
transaction and deterministic lock order. The two counts are never added, summed, merged, or treated
as one combined allowance. This dual evaluation and update prevents rotation from resetting an
active limit.

If the required previous key or version is unavailable during overlap, limiter evaluation fails
closed before identity-lifecycle mutation and returns the endpoint-appropriate sanitized `503`.
After the overlap and every old enforcement effect have ended, evaluation uses only the current key;
retained old-version rows remain subject to ordinary cleanup and never restore allowance.

Startup validates that each required secret reference resolves, each decoded key is at least 32
bytes, active current and previous versions differ, and the configured overlap lifecycle is complete.
Startup cannot prove randomness. Secure key generation is deployment evidence: the approved secrets
mechanism must create at least 32 random bytes with a cryptographically secure random generator,
store them without revealing their values, and prohibit human-chosen passwords or predictable
strings. Operators record generation method, key version, creation time, and activation time without
recording a secret; rotation evidence confirms previous-key availability throughout overlap. Tests use
unmistakably synthetic keys and verify length, separation, lifecycle, and behavior only—not
statistical randomness.

### Rate-limit responses and cleanup

- A rejected registration returns `429 IDENTITY_REGISTRATION_RATE_LIMITED`.
- A rejected resend or verification returns `429 IDENTITY_VERIFICATION_RATE_LIMITED`.
- `Retry-After` is an integer delta-seconds value calculated by rounding up the longest positive
  remainder among every violated current-key or previous-key window and cooldown boundary. A rate
  rejection returns at least `1`; counters are never added to calculate this value.
- Limiter storage or evaluation failure returns the appropriate Identity `503`, with a configurable
  default `Retry-After` of 60 seconds.
- Every response is non-cacheable and contains the required security and correlation headers.

Cleanup runs hourly in `(retention_expires_at, id)` order, in batches of 500 selected through
`ix_identity_rate_limit_buckets_retention_expires_at` with `FOR UPDATE SKIP LOCKED`. It captures one
`transaction_timestamp()` and deletes only rows whose retention expiry is at or before that instant.
Each retention expiry is at least 24 hours after the later of `window_end` and `cooldown_until`, so
cleanup cannot affect an active window, cooldown, or key-overlap decision. Cleanup never changes an
Account, Credential, verification token, or public response.

## Public endpoint security

Unauthenticated access is permitted only to these exact routes and methods:

- `POST /api/v1/registrations`.
- `POST /api/v1/email-verifications`.
- `POST /api/v1/email-verification-requests`.

No broad `/api/v1/**`, `/api/v1/identity/**`, or wildcard registration path becomes public. These
operations are cookie-free JSON APIs. CSRF protection is exempted only for the three exact POST
routes; it is not globally disabled.

CORS is disabled by default. When explicitly enabled, the FH-011 CORS policy applies only to the
three exact endpoint paths above and only to origins in the configured trusted-frontend allowlist.
A valid unauthenticated preflight `OPTIONS` request is handled only for one of those exact paths when
its requested method is `POST`, its origin is allowlisted, and every requested header is in this
fixed set:

- `Content-Type`.
- `Accept`.
- `Accept-Language`.
- `X-Correlation-ID`.

The preflight response advertises only `POST`; actual endpoint requests remain JSON-only POSTs. The
only non-simple response headers exposed to browser code are `X-Correlation-ID` and `Retry-After`.
Credentials and cookies are disabled, wildcard origins are prohibited, and an allowlisted origin is
never combined with `Access-Control-Allow-Credentials: true`. An unapproved origin, path, requested
method, or requested header receives no permissive CORS response; an `OPTIONS` request is not routed
to an FH-011 command handler.

CORS processing does not bypass the exact-path security matchers, weaken the three scoped CSRF
exemptions, add authentication or authorization exceptions, skip rate limiting or content-type
validation, or change enumeration-resistant outcomes. FH-011 does not establish a reusable
platform-wide CORS, CSRF, or trusted-proxy convention. FH-012 may replace these scoped rules only
through its broader approved security policy.

## Observability and secret handling

- Real passwords, credentials, tokens, and production-like secrets never appear in source code,
  fixtures, logs, screenshots, exceptions, or documentation.
- Raw passwords exist only transiently at the approved registration boundary and during minimum
  normalization, policy, and encoding work.
- Raw tokens exist only transiently during generation, link construction, delivery, and public
  consumption handling.
- Logs and traces contain no raw or normalized email, Account status, duplicate outcome,
  uniqueness-race outcome, password, encoded Credential, token, digest, complete link, HMAC key, or
  privacy-protected limiter key.
- Metrics use aggregate route, safe result class, latency, executor, cleanup, and availability
  labels only. They contain no Account, email, token, correlation-derived identity, or per-Account
  existence outcome.
- Safe aggregate operational metrics are permitted.
- The effective `X-Correlation-ID` may connect sanitized request and asynchronous-delivery
  diagnostics. It never becomes authentication, authorization, idempotency, or business identity.
- Assertions, failure messages, DTOs, commands, events, and generated methods must not echo secret
  values.

Tests may use unmistakably synthetic raw passwords only where encoding or validation behavior
requires them. They never persist the raw value, log it, include it in an exception, or reuse it
operationally. Tests generate verification tokens whenever practical. Persisting a securely encoded
synthetic Credential for an approved test does not violate this rule.

## Configuration contract

This table is the authoritative FH-011 configuration-property inventory. Implementations bind and
validate every property at startup; `no default` means no safe development default exists. A `secret
reference` names an external secret mechanism and never supplies a secret value in source,
documentation, tests, or configuration examples. `Tighten` means an operational reduction is
allowed; `approval` means an increase or materially capacity-affecting change needs the stated
security and/or capacity approval. Platform-owned localization names are references to ADR 0008,
not Identity-owned settings. Unless a row names `fixhub.localization.*` or
`spring.messages.fallback-to-system-locale`, Identity owns the property; those three are owned by
the platform localization foundation under ADR 0008.

The following is the authoritative complete row for verification-token cleanup scheduling; this
property appears nowhere else in the configuration inventory.

| Property name | Type / unit | Required | Development default | Production default | Allowed range | Startup validation | Secret | Operational and approval rule |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `fixhub.identity.verification-token.cleanup.interval` | `java.time.Duration` / ISO-8601 duration | Yes | `PT30M` | `PT30M` | `PT1M..PT30M`, inclusive | Reject missing, malformed, zero, negative, shorter-than-`PT1M`, longer-than-`PT30M`, cron-like, and calendar-based values | No | Identity; operations may reduce; may not exceed `PT30M`; increasing the maximum requires an accepted security decision |

| Property name | Type / unit | Required | Development default | Production requirement / allowlist | Startup validation | Secret | Owner / operations |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `fixhub.identity.http.max-request-bytes` | integer / bytes | Yes | `4096` | Exactly `4096` | Equal to `4096` | No | Identity; no change without specification/security approval |
| `fixhub.identity.registration.cors.enabled` | boolean | No | `false` | `true` only with approved origins | Enabled requires origins | No | Identity; security approval to enable/change |
| `fixhub.identity.registration.cors.allowed-origins` | exact-origin list | Conditional | no default | HTTPS trusted frontend origins; no wildcard | Canonical origins; required if enabled | No | Identity; security approval |
| `fixhub.identity.verification.frontend-origin` | URI origin | Yes | `http://localhost:8081` | HTTPS except explicit loopback local profile | No user-info/query/fragment; local override explicit | No | Identity; security approval |
| `fixhub.identity.password.argon2.salt-bytes` | integer / bytes | Yes | `16` | `>=16` | Never below 16 | No | Identity; upward change needs security/capacity approval |
| `fixhub.identity.password.argon2.hash-bytes` | integer / bytes | Yes | `32` | `>=32` | Never below 32 | No | Identity; upward change needs security/capacity approval |
| `fixhub.identity.password.argon2.parallelism` | integer / lanes | Yes | `1` | `>=1` | Never below 1 | No | Identity; upward change needs security/capacity approval |
| `fixhub.identity.password.argon2.memory-kib` | integer / KiB | Yes | `19456` | `>=19456` | Never below baseline | No | Identity; upward change needs security/capacity approval |
| `fixhub.identity.password.argon2.iterations` | integer / iterations | Yes | `2` | `>=2` | Never below baseline | No | Identity; upward change needs security/capacity approval |
| `fixhub.identity.password.argon2.admission.max-concurrency` | integer / permits | Yes | `1` | Positive measured capacity bound | Positive; production evidence | No | Identity; tightening allowed, any change needs capacity approval |
| `fixhub.identity.password.argon2.admission.retry-after-seconds` | integer / seconds | Yes | `1` | `1..3600` | In range | No | Identity; tightening allowed, increase needs security approval |
| `fixhub.identity.password.blocklist.artifact-location` | resource URI | Yes | no default | Immutable deployed artifact | Resolves/readable | No | Identity; security approval |
| `fixhub.identity.password.blocklist.manifest-location` | resource URI | Yes | no default | Adjacent reviewed manifest | Resolves/readable | No | Identity; security approval |
| `fixhub.identity.password.blocklist.source-sha256` | hexadecimal / SHA-256 | Yes | no default | 64 hexadecimal characters | Matches manifest | No | Identity; security approval |
| `fixhub.identity.password.blocklist.artifact-sha256` | hexadecimal / SHA-256 | Yes | no default | 64 hexadecimal characters | Matches artifact/manifest | No | Identity; security approval |
| `fixhub.identity.password.blocklist.expected-hibp-entry-count` | integer / entries | Yes | `100000` | Exactly `100000` | Equal to constant/manifest | No | Identity; no operational change |
| `fixhub.identity.password.blocklist.expected-final-entry-count` | integer / entries | Yes | no default | `100000..200000` inclusive | Equals artifact/manifest | No | Identity; security/capacity approval for maximum change |
| `fixhub.identity.password.blocklist.version` | immutable string | Yes | no default | Dated release/transformation version | Matches manifest | No | Identity; security approval |
| `fixhub.identity.verification-token.lifetime` | duration | Yes | `PT24H` | Exactly `PT24H` | Equal to `PT24H` | No | Identity; ADR/specification amendment required |
| `fixhub.identity.verification-token.cleanup.batch-size` | integer / rows | Yes | `500` | `100..10000` | In range; execution repeats batches until exhaustion | No | Identity; capacity approval for change |
| `fixhub.identity.rate-limit.registration-email.limit` | integer / requests | Yes | `5` | Positive launch default | Positive | No | Identity; tighten allowed, increase needs security/capacity approval |
| `fixhub.identity.rate-limit.registration-email.window` | duration | Yes | `PT1H` | Positive whole seconds | Valid UTC window | No | Identity; tighten allowed, change needs security/capacity approval |
| `fixhub.identity.rate-limit.registration-origin.limit` | integer / requests | Yes | `100` | Positive; shared-NAT evidence before enforcement | Positive | No | Identity; tighten allowed, increase needs security/capacity approval |
| `fixhub.identity.rate-limit.registration-origin.window` | duration | Yes | `PT1H` | Positive whole seconds | Valid UTC window | No | Identity; tighten allowed, change needs security/capacity approval |
| `fixhub.identity.rate-limit.resend-email.limit` | integer / requests | Yes | `5` | Positive launch default | Positive | No | Identity; tighten allowed, increase needs security/capacity approval |
| `fixhub.identity.rate-limit.resend-email.window` | duration | Yes | `P1D` | Positive whole seconds | Valid UTC window | No | Identity; tighten allowed, change needs security/capacity approval |
| `fixhub.identity.rate-limit.resend-email.cooldown` | duration | Yes | `PT5M` | `0..window` | Not greater than email window | No | Identity; tighten allowed, increase needs security/capacity approval |
| `fixhub.identity.rate-limit.resend-origin.limit` | integer / requests | Yes | `60` | Positive; shared-NAT evidence before enforcement | Positive | No | Identity; tighten allowed, increase needs security/capacity approval |
| `fixhub.identity.rate-limit.resend-origin.window` | duration | Yes | `PT1H` | Positive whole seconds | Valid UTC window | No | Identity; tighten allowed, change needs security/capacity approval |
| `fixhub.identity.rate-limit.verification-origin.limit` | integer / requests | Yes | `60` | Positive; shared-NAT evidence before enforcement | Positive | No | Identity; tighten allowed, increase needs security/capacity approval |
| `fixhub.identity.rate-limit.verification-origin.window` | duration | Yes | `PT15M` | Positive whole seconds | Valid UTC window | No | Identity; tighten allowed, change needs security/capacity approval |
| `fixhub.identity.rate-limit.global.limit` | integer / requests | Yes | `300` | Positive launch default | Positive | No | Identity; tighten allowed, increase needs security/capacity approval |
| `fixhub.identity.rate-limit.global.window` | duration | Yes | `PT1M` | Positive whole seconds | Valid UTC window | No | Identity; tighten allowed, change needs security/capacity approval |
| `fixhub.identity.rate-limit.infrastructure-retry-after-seconds` | integer / seconds | Yes | `60` | `1..3600` | In range | No | Identity; tightening allowed, increase needs security approval |
| `fixhub.identity.rate-limit.cleanup.cron` | cron | Yes | `0 0 * * * *` | At least hourly | Valid schedule | No | Identity; capacity approval for change |
| `fixhub.identity.rate-limit.cleanup.batch-size` | integer / rows | Yes | `500` | `1..10000` | In range | No | Identity; capacity approval for change |
| `fixhub.identity.rate-limit.cleanup.retention` | duration | Yes | `P2D` | At least 24 hours after later window/cooldown | Meets configured effects | No | Identity; reduction needs security approval |
| `fixhub.identity.rate-limit.hmac.current-version` | integer | Yes | `1` | Positive | Valid positive format | No | Identity; security approval for rotation |
| `fixhub.identity.rate-limit.hmac.previous-version` | integer | Conditional | no default | Positive, distinct current; required in overlap | Lifecycle consistency | No | Identity; security approval for rotation |
| `fixhub.identity.rate-limit.hmac.current-secret-ref` | secret reference | Yes | no default | Resolves to >=32 bytes | Reference/decoded length | Yes | Identity; security approval for rotation |
| `fixhub.identity.rate-limit.hmac.previous-secret-ref` | secret reference | Conditional | no default | Resolves to >=32 bytes during overlap | Required/resolvable during overlap | Yes | Identity; security approval for rotation |
| `fixhub.identity.rate-limit.hmac.rotation-started-at` | UTC instant | Conditional | no default | Required only in overlap | Paired lifecycle fields | No | Identity; security approval for rotation |
| `fixhub.identity.rate-limit.hmac.overlap` | duration | Conditional | no default | `>=PT25H` | Paired lifecycle fields | No | Identity; security approval; no early removal |
| `fixhub.identity.ingress.mode` | enum | Yes | `DIRECT` | `DIRECT` or `PROXIED` | Proxied requires ingress fields | No | Identity; security/operations approval |
| `fixhub.identity.ingress.forwarding-header-family` | enum | Conditional | no default | `FORWARDED` or `X_FORWARDED` | Required only proxied | No | Identity; security/operations approval |
| `fixhub.identity.ingress.trusted-cidrs` | CIDR list | Conditional | no default | Non-empty canonical CIDRs | Required only proxied; no catch-all client ranges | No | Identity; security/operations approval |
| `fixhub.identity.mail.smtp.host` | hostname | Yes | `localhost` | Approved provider host | Host syntax | No | Identity; provider/security approval |
| `fixhub.identity.mail.smtp.port` | integer / port | Yes | `1025` | `1..65535` | In range | No | Identity; provider/security approval |
| `fixhub.identity.mail.smtp.auth-enabled` | boolean | Yes | `false` | Provider requirement | Credential reference when true | No | Identity; provider/security approval |
| `fixhub.identity.mail.smtp.username` | string | Conditional | no default | Provider requirement | Present when required | No | Identity; provider/security approval |
| `fixhub.identity.mail.smtp.password-secret-ref` | secret reference | Conditional | no default | Provider requirement | Resolves when auth enabled | Yes | Identity; provider/security approval |
| `fixhub.identity.mail.smtp.tls-required` | boolean | Yes | `false` | `true` in production | Production value true | No | Identity; provider/security approval |
| `fixhub.identity.mail.smtp.sender` | RFC 5322 mailbox | Yes | `no-reply@localhost` | Approved production sender | Mailbox syntax | No | Identity; provider/security approval |
| `fixhub.identity.mail.smtp.connect-timeout` | duration | Yes | `PT5S` | `>PT0S` and `<=PT60S` | In range | No | Identity; capacity/operations approval for increase |
| `fixhub.identity.mail.smtp.read-timeout` | duration | Yes | `PT5S` | `>PT0S` and `<=PT60S` | In range | No | Identity; capacity/operations approval for increase |
| `fixhub.identity.mail.smtp.write-timeout` | duration | Yes | `PT5S` | `>PT0S` and `<=PT60S` | In range | No | Identity; capacity/operations approval for increase |
| `fixhub.identity.mail.executor.core-threads` | integer / threads | Yes | `2` | Positive bounded executor | Positive; <= max | No | Identity; capacity approval |
| `fixhub.identity.mail.executor.max-threads` | integer / threads | Yes | `4` | Positive bounded executor | >= core | No | Identity; capacity approval |
| `fixhub.identity.mail.executor.queue-capacity` | integer / tasks | Yes | `100` | `0..10000` | In range | No | Identity; capacity approval |
| `fixhub.identity.mail.executor.shutdown-wait` | duration | Yes | `PT10S` | `PT0S..PT5M` | In range | No | Identity; capacity approval |
| `fixhub.identity.mail.retry.max-retries` | integer / retries | Yes | `2` | Exactly `2` | Equal to 2 | No | Identity; security/operations approval |
| `fixhub.identity.mail.retry.delays` | duration list | Yes | `PT0.25S,PT1S` | Two ordered non-negative delays | Exactly two values | No | Identity; security/operations approval |
| `fixhub.identity.mail.retry.jitter-percent` | integer / percent | Yes | `10` | `0..25` | In range | No | Identity; security/operations approval |
| `fixhub.localization.supported-locales` | locale list | Yes | `ar,en,hi,ur,bn` | Exactly ADR-0008 list | Exact ordered value | No | Platform localization; ADR-0008 owner only |
| `fixhub.localization.default-locale` | locale | Yes | `en` | Exactly `en` | Equal to `en` | No | Platform localization; ADR-0008 owner only |
| `spring.messages.fallback-to-system-locale` | boolean | Yes | `false` | Exactly `false` | Equal to `false` | No | Platform localization; ADR-0008 owner only |

The following are intentionally compile-time specification constants, not configurable properties:
the three endpoint paths and methods; the fixed `/verify-email` verification page path; the
4,096-byte boundary; the 32-byte verification-token and SHA-256-digest sizes; the 43-character
Base64url transport length; the exact seven-day terminal-token retention policy; the one-hour healthy
cleanup SLO and 24-hour critical-backlog threshold; the 100,000 HIBP selection count; the five
ADR-0008 rendering locales; and the four verification terminal reasons. Runtime properties may
validate an artifact's expected count but may not change these constants.

Production-required secrets and provider credentials have no committed default. Local values must be
unmistakably development-only and may not be used with production mail or production data.

## Test plan

### Unit tests

Unit tests cover:

- Unicode scalar validation, NFC normalization, 15/128 code-point boundaries, supplementary code
  points, whitespace preservation, and absence of composition rules.
- Explicit `DelegatingPasswordEncoder` registry, sole `argon2id` mapping, exact constructor
  parameters, encoded prefix, and fail-closed missing/unknown identifiers.
- Argon2id admission permit acquisition and release, immediate saturation rejection, positive
  `Retry-After`, safe `503` ProblemDetail, and absence of raw-password queueing or retention.
- Deterministic blocklist construction from synthetic SHA-1/count input: NFC UTF-8 SHA-1 lookup
  keys, descending-count/ascending-hash selection, exact 100,000 HIBP records, approved contextual
  merge, LF-only uppercase output, manifest provenance, generator version, checksums, and loader
  startup failure for every missing, malformed, non-UTF-8, BOM, CRLF, ordering, duplicate, count,
  or checksum violation. Fixtures contain only synthetic hashes and never plaintext passwords.
- Account's sole `PENDING_VERIFICATION` to `ACTIVE` behavior and rejection of all other source
  states.
- Token generation length/encoding/digest behavior and every terminal transition.
- Rate-window, cooldown, longest-wait, HMAC-key-version, current/previous-key overlap, dual-bucket
  evaluation without counter addition, and cleanup calculations using an injected clock.
- Trusted frontend and direct/proxied ingress-profile validation, including untrusted-peer ignoring
  and trusted-peer missing, malformed, duplicate, and ambiguous forwarding failures.
- Configuration binding tests accept only the table's valid development values and fail startup for
  every missing required production property, unsafe secret reference, invalid duration/range,
  baseline-lowering Argon2 setting, invalid ingress profile, invalid CORS origin, or localization
  system-fallback setting.
- Verification-cleanup scheduling tests bind
  `fixhub.identity.verification-token.cleanup.interval`: accept `PT1M` and `PT30M`; reject longer,
  zero, negative, malformed, cron-like, and calendar-based values; prove cadence is unaffected by
  JVM/server timezone or daylight-saving changes; invoke the initial attempt within one minute of
  readiness; prevent same-instance overlap; observe delayed or failed attempts; and retain the
  one-hour warning and 24-hour critical-backlog behavior.
- Equivalent-work orchestration for registration, resend, and verification; fixed candidate-token
  work, dummy-digest comparison, bounded transaction selection, absence of persistent fake state,
  no artificial jitter, and no Account/token-sensitive diagnostic collaborator.

### MVC and public-contract tests

MockMvc and servlet/integration tests cover all three endpoints and the precise wire contract:
only bare `application/json`, UTF-8 without BOM, absent `Content-Encoding`, malformed JSON and
Unicode, unknown fields, and every field constraint. They exercise fixed-length, absent/false
`Content-Length`, and chunked streams: declared oversize returns `413` before limiting; an admitted
4,097-octet stream returns `413`; an exhausted coarse bucket returns `429` without consuming an
unknown or chunked body; and exactly 4,096 octets can proceed to normal validation. Unsupported
media type, any charset parameter, or any `Content-Encoding` return their documented `415` codes;
structurally invalid admitted in-limit JSON returns `400 MALFORMED_REQUEST`. They also cover every
stable code/status, empty success bodies, content
types, required headers, correlation propagation, safe ProblemDetail members, and absence of
sensitive rejected values.

Security tests prove that only the three exact POST routes are unauthenticated and CSRF-exempt,
broader paths remain protected, and CORS is disabled by default. With CORS enabled, positive and
negative preflight tests cover every trusted/untrusted origin, exact/other path, `POST`/other
method, and allowed/disallowed requested header; credentials and wildcard origins are never
accepted. CORS cannot bypass CSRF, authentication, authorization, rate limits, content validation,
or enumeration resistance.

Registration capacity tests prove that admission never exceeds the configured benchmark-supported
concurrency, saturated requests perform no Account lookup or mutation, every permit is released,
and the sanitized `503` response cannot disclose request or Account data.

### PostgreSQL Testcontainers and Flyway tests

Integration tests reuse the project's PostgreSQL Testcontainer and verify:

- V4 and V5 apply after V1 through V3 on an empty database.
- Hibernate validates every entity mapping against the Flyway-owned schema.
- Every named constraint, foreign key, unique rule, partial index, and cleanup index exists and
  rejects invalid data as specified.
- V4 validates the `VARCHAR(16)` terminal-reason mapping, all four and only four terminal values,
  restrictive Account foreign key, optimistic version, one-open-token rule, expiry lookup index,
  and terminal cleanup index.
- Account deletion remains restricted and no repository exposes general hard deletion.
- Registration commits all three records or rolls back all three.
- Concurrent normalized-email registration yields one Account/Credential/token set and identical
  generic outcomes.
- Concurrent resend leaves exactly one open token and supersedes every displaced token.
- Concurrent consumption performs exactly one activation; replay behavior is stable.
- Expiry, supersession, invalidation, and exact seven-day terminal eligibility use a controlled
  PostgreSQL transaction timestamp. Cleanup preserves younger, open, and non-eligible terminal rows; selects a row
  exactly eligible at seven days; repeats multiple bounded batches to exhaustion; records bounded
  warning/critical backlog conditions at one and 24 hours without identifiers; and never deletes an
  incorrect row after cleanup failure. Concurrent application instances cannot incorrectly process
  the same row.
- Idempotent cleanup tests reuse one captured cutoff across multiple batch transactions: a second
  execution affects zero already-deleted rows; retry after rollback deletes a row once; retry after a
  committed batch continues only with remaining rows; concurrent instances never double-process a
  row; no email, event, or other per-row side effect occurs; and aggregate affected-row metrics are
  correct and secret-safe.
- Rate-limit updates use one database `transaction_timestamp()` for all dimensions, fixed UTC
  epoch boundaries, conditional insert/upsert, deterministic lock order, and atomic stage rollback.
  Concurrent first use and increments cannot exceed a configured threshold; rejection does not
  increment an exhausted bucket; the longest applicable `Retry-After` is returned; coarse capacity
  is not refunded after a later validation gate; and cleanup cannot affect active windows or
  cooldowns.
- HMAC rotation tests prove that both key-version buckets are evaluated and atomically incremented
  during overlap, either exhausted bucket rejects, counters are never combined, the previous key is
  retained for at least 25 hours, and a missing required key fails closed.
- Production-readiness evidence validates origin limits against representative carrier-grade NAT,
  enterprise, campus, IPv4, and IPv6 shared-origin scenarios before those limits are enabled.

### Email and asynchronous-delivery tests

- Port tests prove that no Identity entity or repository crosses the delivery boundary.
- A pinned MailDev Testcontainer test verifies SMTP delivery, localized subjects, plain-text and
  HTML parts for exactly `ar`, `en`, `hi`, `ur`, and `bn`; exact trusted-origin construction as
  `{trustedFrontendOrigin}/verify-email#{token}`; fragment placement; and absence of query-string
  tokens, tracking, or sensitive diagnostics.
- Parameterized resource tests cover all five locales and require UTF-8 subjects, plain-text
  bodies, and HTML bodies; subject/body and placeholder parity; exactly one verification-link
  placeholder in each body; no link placeholder in the subject; correct HTML `lang` and `dir`;
  safe contextual escaping; and no secret leakage.
- Direction tests prove that Arabic and Urdu render RTL, that English, Hindi, and Bengali render
  LTR, and that dynamic LTR values are bidirectionally isolated in Arabic and Urdu templates.
- FH-011 integration tests exercise ADR 0008 exact-locale and base-language resolution, English
  fallback for a valid unsupported preference, English fallback with sanitized integrity handling
  for a malformed persisted preference, optional registration `preferredLocale` persistence, and
  absent-preference persistence from the resolved UI/header locale or `en`.
- Request tests prove that missing, malformed, or unsupported `Accept-Language` follows ADR 0008,
  never causes `400` or `406` solely because of that header, and never consults system-locale
  fallback. The tests do not duplicate ADR 0008's complete header-parsing algorithm.
- Asynchronous tests prove that the canonical resolved locale is captured explicitly and propagated
  into execution without thread-local, environmental, raw-request, or raw-header locale state.
- Startup tests fail for any missing subject or body, non-UTF-8 resource, wrong locale/direction
  metadata, missing or repeated link placeholder, unsafe HTML template contract, or embedded
  secret-like content in any of the five resource sets.
- Executor tests cover bounded queueing, no caller-runs behavior, two retries, backoff ordering,
  final failure, saturation, and shutdown timeout.
- Delivery failure tests prove that the Account remains pending, the token remains usable, and the
  already selected public response is unchanged.
- Process-crash-loss behavior remains documented rather than disguised as durable delivery.

### Enumeration, leakage, and architecture tests

- New, existing, non-pending, and uniqueness-race registration paths invoke the same successful
  validators, blocklist checker, and Argon2id encoder before lookup and produce identical HTTP
  status, body, and headers.
- Resend and verification tests prove the specified independent validation/abuse-gate order,
  candidate-token or dummy-digest work, bounded equivalent transaction, identical public outcomes
  for missing and every Account/token state, and no mailbox-side effect except the documented
  eligible-pending case.
- The pre-production registration timing gate uses the specified warmed production topology,
  randomized 500-plus-500 synthetic observations, controlled real HTTP/Argon2id/PostgreSQL path,
  p50/p95 threshold, Cliff's delta bound, sanitized evidence, and safe remediation sequence. CI
  verifies the harness/calculations, operation structure, and response equality without enforcing
  production wall-clock thresholds or adding artificial jitter.
- Log, trace, exception, assertion, and generated-string tests detect raw/normalized email,
  passwords, encoded Credentials, tokens, digests, complete links, status, duplicate/race outcome,
  and limiter identifiers.
- Locale-leakage tests detect raw locale request values, raw `Accept-Language`, malformed persisted
  values, and arbitrary unsupported locale values in errors, logs, traces, metrics, and diagnostic
  output; locale telemetry is limited to ADR 0008's resolved values and bounded reason codes.
- Spring Modulith verification and structural guards prove that Identity remains closed and no
  entity or repository is exposed.

### Benchmark and quality evidence

The implementation review includes the separate production-equivalent Argon2id benchmark evidence
defined above. The canonical quality gate runs:

```text
./mvnw clean verify
```

Maven Enforcer, Spotless, all unit/MVC/integration/architecture tests, Flyway, Hibernate validation,
Spring Modulith verification, and JaCoCo thresholds of at least 85 percent instruction and 80
percent line coverage must pass. `git diff --check` must report no whitespace errors.

No future total test count is prescribed. The complete verification run must report zero failures,
zero errors, and zero skipped tests.

## Acceptance criteria

FH-011 is complete only when:

- This specification is approved and implementation remains consistent with accepted ADR 0011.
- All three endpoint contracts, validation precedence, errors, headers, public security rules, and
  enumeration protections are implemented and documented through OpenAPI.
- Registration writes only `{argon2id}` and no unsupported encoder or fallback is registered.
- Blocklist provenance, license/usage review, artifact integrity, startup validation, and update
  procedure are complete.
- Account, PASSWORD Credential, and initial verification state are atomic.
- Resend, consumption, invalidation, expiry, retention, and cleanup remain purpose-specific,
  concurrency-safe, and tested against PostgreSQL; terminal records become eligible exactly seven
  days after terminalization and, under healthy operation, are physically deleted within one hour;
  fixed-rate cleanup starts within one minute of readiness, repeats bounded batches to exhaustion,
  never overlaps within one application instance, and is idempotent and retry-safe across repeated
  and multi-instance execution.
- Only one concurrent request can activate an eligible Account, and no request can activate an
  ineligible Account.
- Email delivery begins only after commit, uses the closed Identity port, remains bounded and
  secret-safe, and preserves resend recovery after failure.
- Exactly `ar`, `en`, `hi`, `ur`, and `bn` provide complete, qualified-human-reviewed localized
  subjects and UTF-8 plain-text/HTML bodies; pass placeholder, language, direction, escaping,
  bidirectional-isolation, secret-safety, and startup validation; and integrate with ADR 0008
  resolution and deterministic English fallback.
- Rate limiting is shared, persistent, privacy-protected, fail-closed, multi-instance safe, and
  never locks an Account.
- No Identity entity, repository, raw password, encoded Credential, raw token, digest, complete
  link, or raw limiter identifier escapes its approved boundary.
- The separate Argon2id benchmark evidence satisfies the approved latency and memory criteria.
- Argon2id concurrency is bounded by the approved production-equivalent capacity evidence;
  saturation returns the documented safe `503` ProblemDetail and never queues a password.
- The resolved documentation prerequisites below remain reflected in their authoritative documents,
  and the remaining implementation obligations are completed in the implementation change.
- The canonical quality gate succeeds with zero failures, errors, or skips.

## Explicit exclusions

FH-011 does not design or implement:

- Phone collection during registration or phone verification.
- Login.
- Legacy-password compatibility or migration.
- Credential matching.
- Upgrade-on-successful-login implementation.
- JWT access tokens.
- Refresh or session tokens.
- Logout or session revocation.
- Global, application, Provider, or Branch roles or authorization.
- Account recovery.
- Password reset, change, history, or reuse policy.
- Direct or indirect Provider persistence access.
- Transactional-outbox infrastructure.
- Redis.
- Production email-provider selection.

## Consistency status and implementation obligations

The ADR 0008 platform localization prerequisite is satisfied by its accepted amendment and the
aligned API conventions, FH-006 specification, legacy mapping, and ERD. Approved FH-011 consumes
that policy and does not reopen those completed platform decisions.

As of 2026-09-14, the API conventions document the enumeration-resistant empty `202 Accepted`
exception, the global unknown-JSON-field rejection rule is authoritative, FH-006 references those
platform conventions, and the error catalogue owns `REQUEST_TOO_LARGE` plus the approved FH-011
Identity codes. Those documentation prerequisites are complete; implementation must not redefine
them privately.

The FH-011 implementation change must still:

1. Configure and test the global unknown-JSON-field rejection policy without an endpoint-private
   deserialization policy.
2. Implement the global catalogue-consistency tests for the newly catalogued common and Identity
   codes.
3. Preserve the existing FH-010 Account/Credential schema, encoded-secret format, restrictive
   foreign key, optimistic locking, no-delete repository surface, and closed-module boundaries.

These prerequisites do not authorize unrelated changes to common conventions or another module.

## Decision closure

This approved specification closes all FH-011 architectural design decisions; no FH-011 design
decision remains open. The concrete pinned blocklist artifact instance remains mandatory
implementation evidence, not an already-existing decision: its version-controlled manifest and
matching artifact must be complete and reviewed before implementation acceptance or production-startup
authorization. Design approval, implementation acceptance, and production launch are separate gates.
Implementation acceptance and production deployment also require the production-equivalent Argon2
benchmark/capacity evidence, timing-equivalence gate, shared-NAT origin-limit evidence, qualified
translation review, configuration validation, verified seven-day terminal-cleanup execution and
backlog observability, and all acceptance criteria. Those activities cannot select an unapproved
lower-security setting. Changes to API shapes, public outcomes, error codes, password/blocklist
policy, persistence contracts, delivery behavior, abuse thresholds, public-route security, exclusions,
or acceptance criteria require explicit review before implementation divergence.
