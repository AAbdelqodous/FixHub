# FixHub error catalogue

## Purpose

This document is the authoritative catalogue for common public FixHub error codes. It defines
their exact spelling, HTTP status, default diagnostic detail, ownership, and intended use so the
implementation, tests, OpenAPI documentation, and clients share one stable contract.

Business-module catalogues are added to this document as their public APIs are designed. A module
owns its domain errors; `common` owns only framework and cross-cutting errors.

## Authority and related documents

- [ADR 0001](../adr/0001-problem-detail-error-contract.md) establishes RFC 9457
  `ProblemDetail` and stable string codes.
- [ADR 0002](../adr/0002-method-argument-not-valid-hook.md) records the original validation hook
  and its later expansion.
- [ADR 0010](../adr/0010-api-error-contract-and-ownership.md) defines error-code ownership,
  rendering, and correlation identifiers.
- [API conventions](api-conventions.md) define the shared public HTTP rules.
- [FH-006](../specs/006-api-error-conventions.md) defines the implementation and acceptance scope.
- [ADR 0011](../adr/0011-registration-password-and-email-verification-security.md) defines the
  accepted registration and email-verification security boundary.
- The proposed [FH-011 specification](../specs/011-registration-and-email-verification.md) defines
  the owning endpoint semantics for its reserved Identity codes without authorizing implementation.

If this catalogue conflicts with ADR 0010, ADR 0010 takes precedence and this document must be
corrected.

## Public error-code contract

Every error code provides:

- `code`: a stable, globally unique public string.
- `defaultDetail`: a safe, deterministic diagnostic detail.
- `status`: the HTTP status associated with the error semantics.

The common Java contract is:

```java
public interface ErrorCode {
    String code();

    String defaultDetail();

    HttpStatus status();
}
```

The method names are part of the shared internal module contract. The returned `code` is part of
the public API. Java enum constant names, class names, and package names are not public API values.

`ApiException` accepts any implementation of `ErrorCode`. This permits a module-owned enum to use
the common renderer without moving its domain codes into `common`.

## Naming rules

- Codes use uppercase ASCII snake case.
- A code is globally unique across all public FixHub APIs.
- Framework and cross-cutting codes use concise unprefixed names reserved by `common`.
- Business codes use a module or aggregate prefix when necessary, for example
  `PROVIDER_BRANCH_NOT_FOUND` or `BOOKING_SLOT_UNAVAILABLE`.
- A code describes one stable semantic condition rather than only repeating an HTTP status.
- Published codes are never renamed, reused for another meaning, or silently merged.
- Numeric error codes are not part of the public contract.
- Display text and translated client messages are not embedded in the code.

Avoid generic business codes such as `RESOURCE_NOT_FOUND` or `CONFLICT`. They do not tell a client
which resource or rule failed and would centralize unrelated module semantics.

## Ownership

| Owner | Responsibilities |
|---|---|
| `common` | Shared `ErrorCode` contract, framework catalogue, `ApiException`, ProblemDetail rendering, correlation infrastructure, and catalogue validation |
| Business module | Domain-specific enum, code meaning, status choice, safe default detail, lifecycle compatibility, and endpoint documentation |
| Client | Presentation translation keyed by stable code; must not parse diagnostic detail for control flow |

One module must not publish another module's domain code as if it owned that failure. When a
cross-module call fails, the consuming module either preserves an explicitly published failure or
maps it to one of its own documented semantics at its application boundary.

## Common catalogue summary

| Code | HTTP status | Default detail |
|---|---:|---|
| `VALIDATION_ERROR` | `400 Bad Request` | `Validation failed` |
| `MALFORMED_REQUEST` | `400 Bad Request` | `Request body is malformed` |
| `MISSING_PARAMETER` | `400 Bad Request` | `Required request parameter is missing` |
| `TYPE_MISMATCH` | `400 Bad Request` | `Request value has an invalid type` |
| `UNAUTHORIZED` | `401 Unauthorized` | `Authentication is required` |
| `FORBIDDEN` | `403 Forbidden` | `Access is denied` |
| `ENDPOINT_NOT_FOUND` | `404 Not Found` | `API endpoint was not found` |
| `METHOD_NOT_ALLOWED` | `405 Method Not Allowed` | `HTTP method is not allowed` |
| `NOT_ACCEPTABLE` | `406 Not Acceptable` | `Requested response representation is not available` |
| `REQUEST_TOO_LARGE` | `413 Content Too Large` | `Request body exceeds the allowed size` |
| `UNSUPPORTED_MEDIA_TYPE` | `415 Unsupported Media Type` | `Request media type is not supported` |
| `INTERNAL_ERROR` | `500 Internal Server Error` | `An unexpected error occurred` |

The spelling and capitalization in this table are exact. Tests compare the implementation with
these values.

## Common code definitions

### `VALIDATION_ERROR`

- Owner: `common`
- Status: `400 Bad Request`
- Default detail: `Validation failed`
- Use when: Bean Validation rejects a parsed request body or a constrained controller parameter,
  path variable, or header.
- Additional property: ordered `errors` entries containing `field` and diagnostic `message`.
- Do not use when: JSON cannot be parsed, a required request parameter is absent, conversion fails,
  or a domain invariant rejects otherwise valid input.

### `MALFORMED_REQUEST`

- Owner: `common`
- Status: `400 Bad Request`
- Default detail: `Request body is malformed`
- Use when: The request representation cannot be parsed into the declared input structure, such as
  syntactically invalid JSON or an unreadable value.
- Do not use when: Parsing succeeded and Bean Validation or a domain rule rejected the input.
- Safety: Parser exception messages and rejected sensitive content are not returned.

### `REQUEST_TOO_LARGE`

- Owner: `common`
- Status: `413 Content Too Large`
- Default detail: `Request body exceeds the allowed size`
- Use when: The actual request body exceeds the owning endpoint's documented byte limit, including
  streamed or chunked input whose declared length is absent or inaccurate.
- Safety: Do not return the body, its measured content, parser diagnostics, or rejected values.
- Do not use when: The media type is unsupported or the body is within the byte limit but malformed.

### `MISSING_PARAMETER`

- Owner: `common`
- Status: `400 Bad Request`
- Default detail: `Required request parameter is missing`
- Use when: A required query parameter or equivalent required MVC request value is absent before
  controller execution.
- Do not use when: The value is present but blank, malformed, or outside a validation constraint.

### `TYPE_MISMATCH`

- Owner: `common`
- Status: `400 Bad Request`
- Default detail: `Request value has an invalid type`
- Use when: Spring cannot convert a supplied path, query, or header value to the declared public
  type.
- Do not use when: Conversion succeeds but Bean Validation or a business rule rejects the value.
- Safety: Internal Java type names and conversion stack traces are not returned.

### `UNAUTHORIZED`

- Owner: `common`
- Status: `401 Unauthorized`
- Default detail: `Authentication is required`
- Use when: Authentication credentials are missing, invalid, expired, or otherwise not accepted.
- Rendering: Reserved by FH-006; the Spring Security authentication entry point implements the
  ProblemDetail contract in the owning Identity and security task because this failure may occur
  before MVC advice.
- Do not use when: Authentication succeeded but permission is insufficient.

### `FORBIDDEN`

- Owner: `common`
- Status: `403 Forbidden`
- Default detail: `Access is denied`
- Use when: The actor is authenticated but lacks the required global role, Membership, Provider
  permission, or Branch scope.
- Rendering: Reserved by FH-006; the Spring Security access-denied handler implements the same
  contract in the owning task when denial occurs outside MVC.
- Do not use when: Authentication is missing or invalid.

An owning module may intentionally return a module-owned `404` instead of revealing resource
existence when its reviewed security policy requires that behavior. That choice must be documented
and tested; it is not achieved by relabeling `FORBIDDEN`.

### `ENDPOINT_NOT_FOUND`

- Owner: `common`
- Status: `404 Not Found`
- Default detail: `API endpoint was not found`
- Use when: No public `/api/**` route or static API resource matches the request.
- Do not use when: A valid route cannot find a Provider, Branch, Booking, or other domain resource.
  The owning module supplies a precise domain code for that condition.

### `METHOD_NOT_ALLOWED`

- Owner: `common`
- Status: `405 Method Not Allowed`
- Default detail: `HTTP method is not allowed`
- Use when: A route exists but does not support the requested HTTP method.
- Required protocol behavior: Preserve Spring's `Allow` header.
- Do not use when: The method is supported but the current business state prohibits the command;
  that is a module-owned conflict or lifecycle error.

### `NOT_ACCEPTABLE`

- Owner: `common`
- Status: `406 Not Acceptable`
- Default detail: `Requested response representation is not available`
- Use when: Content negotiation cannot produce a representation accepted by the request.
- Do not use when: The request body's media type is unsupported.

### `UNSUPPORTED_MEDIA_TYPE`

- Owner: `common`
- Status: `415 Unsupported Media Type`
- Default detail: `Request media type is not supported`
- Use when: The endpoint does not support the supplied request `Content-Type`.
- Required protocol behavior: Preserve applicable Spring-generated supported-media headers.
- Do not use when: The media type is supported but its content is malformed.

### `INTERNAL_ERROR`

- Owner: `common`
- Status: `500 Internal Server Error`
- Default detail: `An unexpected error occurred`
- Use when: An unexpected exception reaches the public boundary and no reviewed, more specific
  error mapping applies.
- Logging: Log the original exception with the effective correlation identifier.
- Safety: Return only the generic default detail; never return the raw exception message.
- Do not use as: A shortcut for known domain, infrastructure, or validation failures that require a
  documented mapping.

## Identity FH-011 catalogue summary

These codes are owned by the closed Identity module and are introduced by the proposed FH-011
registration and email-verification contract. Their presence in this catalogue does not approve
FH-011 or authorize implementation.

| Code | HTTP status | Default detail | Owner |
|---|---:|---|---|
| `IDENTITY_PASSWORD_BLOCKED` | `400 Bad Request` | `Password is not permitted` | `identity` |
| `IDENTITY_REGISTRATION_RATE_LIMITED` | `429 Too Many Requests` | `Registration rate limit exceeded` | `identity` |
| `IDENTITY_REGISTRATION_UNAVAILABLE` | `503 Service Unavailable` | `Registration is temporarily unavailable` | `identity` |
| `IDENTITY_VERIFICATION_TOKEN_INVALID` | `400 Bad Request` | `Verification token is invalid` | `identity` |
| `IDENTITY_VERIFICATION_RATE_LIMITED` | `429 Too Many Requests` | `Verification rate limit exceeded` | `identity` |
| `IDENTITY_VERIFICATION_UNAVAILABLE` | `503 Service Unavailable` | `Verification is temporarily unavailable` | `identity` |

### `IDENTITY_PASSWORD_BLOCKED`

- Owner: `identity`
- Status: `400 Bad Request`
- Default detail: `Password is not permitted`
- Use when: FH-011 rejects the complete NFC-normalized registration password under its approved
  local compromised-password policy before Account lookup.
- Safety: Do not expose the password, lookup digest, matching source entry, occurrence count, or
  Account-existence information.

### `IDENTITY_REGISTRATION_RATE_LIMITED`

- Owner: `identity`
- Status: `429 Too Many Requests`
- Default detail: `Registration rate limit exceeded`
- Use when: An FH-011 registration email, origin, or global abuse bucket rejects the request.
- Required protocol behavior: Include the FH-011 `Retry-After` value.
- Safety: Do not reveal the limiting identifier, bucket, count, Account existence, or Account state.

### `IDENTITY_REGISTRATION_UNAVAILABLE`

- Owner: `identity`
- Status: `503 Service Unavailable`
- Default detail: `Registration is temporarily unavailable`
- Use when: FH-011 registration cannot safely evaluate required infrastructure, trusted-origin
  input, or bounded Argon2id admission capacity.
- Required protocol behavior: Include the FH-011 `Retry-After` value.
- Safety: The ProblemDetail remains sanitized and must not reveal the password, email, Account
  existence, capacity value, forwarding data, or internal failure.

### `IDENTITY_VERIFICATION_TOKEN_INVALID`

- Owner: `identity`
- Status: `400 Bad Request`
- Default detail: `Verification token is invalid`
- Use when: An FH-011 verification token is unknown, expired, superseded, invalidated, otherwise
  ineligible, or no longer retained after cleanup.
- Safety: Do not distinguish the token's terminal reason, linked Account existence, or Account state.

### `IDENTITY_VERIFICATION_RATE_LIMITED`

- Owner: `identity`
- Status: `429 Too Many Requests`
- Default detail: `Verification rate limit exceeded`
- Use when: An FH-011 verification-email resend or token-consumption abuse bucket rejects the
  request.
- Required protocol behavior: Include the FH-011 `Retry-After` value.
- Safety: Do not reveal the limiting identifier, bucket, count, token state, Account existence, or
  Account state.

### `IDENTITY_VERIFICATION_UNAVAILABLE`

- Owner: `identity`
- Status: `503 Service Unavailable`
- Default detail: `Verification is temporarily unavailable`
- Use when: FH-011 resend or token consumption cannot safely evaluate required infrastructure or
  trusted-origin input.
- Required protocol behavior: Include the FH-011 `Retry-After` value.
- Safety: The ProblemDetail remains sanitized and must not reveal an email, token, Account state,
  forwarding data, delivery outcome, or internal failure.

## ProblemDetail representation

Every public error contains the RFC 9457 members `type`, `title`, `status`, `detail`, and `instance`,
plus the FixHub properties `code` and `correlationId`.

Until FixHub adopts documented problem-type URIs, `type` remains `about:blank` and `title` follows
the HTTP status. A future nonblank type URI must be stable, documented, and introduced through a
compatibility review.

`instance` contains the request path without its query string. It must not copy query secrets into
the response.

Example framework error:

```json
{
  "type": "about:blank",
  "title": "Method Not Allowed",
  "status": 405,
  "detail": "HTTP method is not allowed",
  "instance": "/api/v1/ping",
  "code": "METHOD_NOT_ALLOWED",
  "correlationId": "4d5e3213-55ad-49c5-86ef-50395782a956"
}
```

## Validation error entries

Validation errors additionally contain an `errors` array:

```json
{
  "field": "name",
  "message": "must not be blank"
}
```

Rules:

- `field` uses the public request-body property or controller parameter name.
- Nested and indexed fields use a deterministic path such as `items[0].quantity`.
- `message` is a safe diagnostic default and is not a stable translation key.
- Entries use deterministic ordering so repeated requests and contract tests are predictable.
- Rejected values are not included.
- Object-level violations use a documented public object or request name rather than an internal
  Java class name.

## Safe detail policy

A default or contextual detail may describe the failed business condition, but it must not contain:

- Exception class names or stack traces.
- SQL, table, column, repository, package, or filesystem details.
- Credentials, tokens, session identifiers, or authorization headers.
- Private contact information or document contents.
- Unrestricted rejected request values.
- Internal hostnames, ports, service credentials, or vendor payloads.

Clients use `code`, structured fields, and documented resource identifiers for programmatic
handling. They do not parse `detail` or validation `message` text.

## Module-owned catalogue pattern

A module implements the shared contract without editing `CommonErrorCode`. For example:

```java
public enum ProviderErrorCode implements ErrorCode {
    BRANCH_NOT_FOUND(
            "PROVIDER_BRANCH_NOT_FOUND",
            "Provider branch was not found",
            HttpStatus.NOT_FOUND);

    private final String code;
    private final String defaultDetail;
    private final HttpStatus status;

    // Constructor and ErrorCode method implementations omitted.
}
```

The enum constant may remain concise inside its owning module while the explicit public code
preserves global uniqueness. The module documents the code's exact meaning, relevant identifiers,
state preconditions, and endpoints before publication.

## Existing-code migration

FH-006 replaces the original `BusinessErrorCode` enum as follows:

| Existing constant | FH-006 result |
|---|---|
| `VALIDATION_ERROR` | Retained in `CommonErrorCode`; default detail becomes `Validation failed` |
| `UNAUTHORIZED` | Retained and reserved in `CommonErrorCode`; rendered by later security work |
| `FORBIDDEN` | Retained and reserved in `CommonErrorCode`; rendered by later security work |
| `RESOURCE_NOT_FOUND` | Removed as a generic common domain code; valid routes use module-owned resource codes |
| `CONFLICT` | Removed as a generic common domain code; modules define precise `409` codes |
| `INTERNAL_ERROR` | Retained in `CommonErrorCode` with sanitized default detail |

The numeric `code` field is removed. The old `message` and `httpStatus` properties become the
shared contract's `defaultDetail()` and `status()` methods. Public string codes are returned by
`code()` rather than relying on `Enum.name()`.

The initial repository has no production business endpoint depending on `RESOURCE_NOT_FOUND` or
`CONFLICT`, so FH-006 does not create placeholder module codes solely to preserve unused generic
constants.

## Catalogue governance

Before a new public code is accepted, its owner verifies that:

- No existing code already expresses the same condition.
- The spelling is globally unique and follows the naming rules.
- The HTTP status matches the semantics rather than implementation convenience.
- The default detail is safe, deterministic, and useful for diagnostics.
- The owning endpoints and OpenAPI responses document it.
- Client presentation can translate the code without parsing server text.
- Tests cover its default and contextual detail behavior.

Removing, renaming, or reinterpreting a published code is a breaking API change. A deprecated code
remains documented until its supported migration window ends.

## Verification requirements

FH-006 tests verify:

- Every common code is nonblank and uppercase snake case.
- Common codes are unique.
- Default details are nonblank and satisfy the safe-detail policy.
- Every code has the documented HTTP status and exact public value.
- `ApiException` accepts both common and module-owned implementations.
- Default and contextual details render correctly.
- Every tested ProblemDetail contains `code`, `correlationId`, and the request-path `instance`.
- Validation entries are structured and deterministic.
- Unexpected exceptions use `INTERNAL_ERROR` without exposing the original message.
- Protocol headers survive framework exception mapping.
- Correlation identifiers match across the response header, ProblemDetail, and diagnostic context.

A repository-wide automated catalogue-consistency test must include every implemented common and
module-owned public code. It fails for a missing, unknown, duplicate, renamed, or re-owned code and
for any status or default-detail difference between the implementation and this catalogue. Proposed
codes that are catalogued before implementation, including FH-011's Identity codes, become mandatory
in that test when their owning specification authorizes implementation.

Each module's tests add its implemented catalogue to the global uniqueness and documentation checks
when the module publishes its first public errors.

## Change control

Changes to common codes require review by the common boundary owner. Changes to module codes require
review by the owning module and any affected client contract. A code that would be shared by
unrelated modules is not added to `common` merely for convenience; cross-module meaning and
ownership must be established first.
