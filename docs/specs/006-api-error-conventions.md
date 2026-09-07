# FH-006: API and error conventions

- Status: In progress
- Date: 2026-08-18
- Branch: `feat/fh-006-api-error-conventions`

## Objective

Define the stable HTTP and error-handling conventions that every FixHub module must follow before
business endpoints are introduced. Harden the existing common error boundary so domain errors,
Spring MVC errors, validation failures, and unexpected failures use one predictable RFC 9457
contract.

FH-006 extends the common foundation established by `docs/specs/002-common-module.md` and aligns it
with ADRs 0001, 0002, 0008, and the module boundaries accepted in FH-005.

## Current baseline

The repository already provides:

- `ApiException`, `BusinessErrorCode`, and one `GlobalExceptionHandler`.
- RFC 9457 `ProblemDetail` responses for `ApiException`, unexpected exceptions, and invalid
  `@RequestBody` values.
- A guard requiring exactly one application-owned `@ControllerAdvice`.
- Spring MVC, Bean Validation, Spring Security, Springdoc OpenAPI, and MockMvc test support.
- One production endpoint: `GET /api/v1/ping`.

The current implementation does not yet provide:

- A complete API-conventions document or authoritative error catalogue.
- Module-owned domain error codes behind a shared error-code contract.
- Stable FixHub codes for all relevant Spring MVC failures.
- Method-parameter validation errors for query parameters, path variables, and headers.
- Correlation identifiers in responses and logs.
- Contract tests for malformed JSON, missing parameters, type mismatches, method/media errors, and
  correlation behavior.

## Deliverables

1. `docs/design/api-conventions.md`.
2. `docs/design/error-catalogue.md`.
3. An ADR defining error-code ownership and correlation behavior.
4. A shared error-code interface owned by `common`.
5. Common framework error codes owned by `common`; business-module codes remain owned by their
   modules.
6. A hardened `ApiException` and `GlobalExceptionHandler`.
7. Request correlation-ID propagation and logging support.
8. Expanded contract and structural tests.

## API conventions

### Versioning and resource paths

- Public business APIs use the `/api/v1` prefix.
- Paths use lowercase plural resource nouns and kebab-case only where more than one word is needed.
- Resource relationships use explicit nested paths only when the parent context is required for
  identity or authorization.
- API version changes are explicit. Breaking changes are not introduced silently within `v1`.
- Action-style paths are used only for business commands that cannot be represented clearly as a
  resource-state transition.

### JSON and media types

- Successful JSON responses use `application/json`.
- Error responses use `application/problem+json`.
- JSON property names use `lowerCamelCase`.
- Successful single-resource responses are returned as documented DTOs without a generic envelope.
- Empty successful commands use `204 No Content` when no response representation is required.
- Creation commands use `201 Created` and provide a `Location` header when a stable resource URI is
  available.

### Identifiers, dates, and money

- Identifiers are stable, language-independent, and treated as opaque by clients.
- Persistence identity remains governed by ADR 0004; API DTOs must document their identifier schema
  explicitly.
- Timestamps represent instants as ISO 8601 UTC values with a `Z` suffix.
- Date-only business values use ISO 8601 `yyyy-MM-dd` without an implied timestamp.
- Local operating times are paired with an explicit business timezone where interpretation could be
  ambiguous.
- Monetary values use decimal representations and an ISO 4217 currency code. Binary floating-point
  values are prohibited for money.

### Collections

- Standard administrative and catalogue lists use page-based pagination unless a module
  specification requires cursor pagination.
- Default page size is 20 and maximum page size is 100 unless a stricter endpoint limit is
  documented.
- Page numbering is zero-based when Spring `Pageable` is exposed through the application boundary.
- Sort fields are allow-listed per endpoint; clients cannot submit arbitrary persistence property
  names.
- Mutable ordered histories, including Message history, use stable cursor semantics defined by the
  owning module rather than offset pagination.

### Locale resolution

- Locale identifiers are normalized BCP 47 language tags as established by ADR 0008.
- A valid supported `Accept-Language` request preference takes precedence.
- When no supported request preference is supplied, an authenticated Account preference may be
  used after Identity defines it.
- Otherwise, the configured platform-default locale is used.
- Localized domain content then follows ADR 0008: exact locale, base language, platform default,
  and finally the field's documented missing-content policy.
- Error `code` values, identifiers, and machine-readable enum values are never translated.
- The server's `detail` and validation messages are diagnostic defaults; clients translate stable
  codes for presentation.

### Idempotency and concurrency

- Commands that can create duplicate financial or business effects must define an idempotency key
  and replay behavior in their module specification.
- An idempotency key is scoped to the authenticated actor and applicable business context.
- Messaging retains the client-generated Message identifier required by ADR 0009.
- Optimistic-concurrency or version-precondition behavior is defined by the owning module when the
  first mutable business endpoint is introduced.

## Error-code ownership

- `common` owns the error-code interface, ProblemDetail rendering, correlation infrastructure, and
  codes for framework or cross-cutting failures.
- Each business module owns its domain-specific error-code enum and HTTP mapping.
- A business module must not add its domain codes to one ever-growing enum in `common`.
- Error codes use uppercase snake case and are globally unique across the public API.
- Module codes use a clear module or aggregate prefix, for example `PROVIDER_BRANCH_NOT_FOUND`.
- Renaming or reusing a published code for different semantics is a breaking API change.
- Numeric error codes are not part of the public contract and are removed unless a separately
  approved integration requires them.
- Every code has one default diagnostic detail and one HTTP status.

The common catalogue initially covers:

| Code | Status | Purpose |
|---|---:|---|
| `VALIDATION_ERROR` | 400 | Bean or method-parameter validation failed |
| `MALFORMED_REQUEST` | 400 | The request body cannot be parsed |
| `MISSING_PARAMETER` | 400 | A required request parameter is absent |
| `TYPE_MISMATCH` | 400 | A path, query, or header value cannot be converted |
| `UNAUTHORIZED` | 401 | Authentication is required or invalid |
| `FORBIDDEN` | 403 | The authenticated actor lacks permission |
| `ENDPOINT_NOT_FOUND` | 404 | No API resource or route matches the request |
| `METHOD_NOT_ALLOWED` | 405 | The HTTP method is not supported for the resource |
| `NOT_ACCEPTABLE` | 406 | No acceptable response representation is available |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | The request media type is unsupported |
| `INTERNAL_ERROR` | 500 | An unexpected server failure occurred |

Authentication and access-denied rendering are implemented with Spring Security in its owning
task because those failures may occur before MVC controller advice. FH-006 reserves their stable
codes and documents the required contract.

## ProblemDetail contract

Every public error response contains the standard RFC 9457 fields:

- `type`
- `title`
- `status`
- `detail`
- `instance`

Every error also contains these FixHub extensions:

- `code`: stable machine-readable error code.
- `correlationId`: identifier shared by the response header and diagnostic logs.

Validation failures additionally contain:

- `errors`: an ordered list of objects containing `field` and diagnostic `message`.

The error boundary must not expose exception class names, stack traces, SQL details, credentials,
tokens, filesystem paths, or rejected sensitive values.

Example:

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Validation failed",
  "instance": "/api/v1/providers",
  "code": "VALIDATION_ERROR",
  "correlationId": "4d5e3213-55ad-49c5-86ef-50395782a956",
  "errors": [
    {
      "field": "name",
      "message": "must not be blank"
    }
  ]
}
```

## Correlation identifiers

- The canonical header is `X-Correlation-ID`.
- A valid client-supplied identifier may be retained when it contains 1 to 64 ASCII letters,
  digits, dots, underscores, or hyphens.
- A missing or invalid value is replaced with a server-generated UUID.
- Every HTTP response returns the effective identifier in `X-Correlation-ID`.
- Every ProblemDetail includes the same value as `correlationId`.
- Request processing places the value in the logging MDC under `correlationId` and removes it in a
  `finally` block.
- Raw invalid header values are not copied into logs or responses.
- Correlation identifiers support diagnostics only and never provide authentication,
  authorization, uniqueness, or idempotency guarantees.

## Implementation tasks

### Documentation and decisions

- [ ] Add `docs/design/api-conventions.md`.
- [ ] Add `docs/design/error-catalogue.md`.
- [ ] Add an ADR for shared error-code contracts, module ownership, and correlation behavior.
- [ ] Update ADR 0001 because the wire code is no longer tied directly to one common enum.
- [ ] Update ADR 0002 because method-parameter validation will no longer be deferred.
- [ ] Update `docs/specs/002-common-module.md` with a link to the FH-006 extension rather than
      rewriting its historical completed checklist.

### Error model

- [ ] Introduce a minimal common `ErrorCode` contract.
- [ ] Replace the generic common `BusinessErrorCode` model with common framework codes while
      preserving stable published codes that remain valid.
- [ ] Remove the unused numeric-code field from the public error model.
- [ ] Change `ApiException` to accept the shared error-code contract so future module-owned enums
      can use the same boundary.
- [ ] Keep default diagnostic details safe and deterministic.

### MVC exception boundary

- [ ] Keep exactly one application-owned `@RestControllerAdvice`.
- [ ] Render `ApiException` through the owning code and status.
- [ ] Render unexpected exceptions as generic `INTERNAL_ERROR` responses and log the original
      exception with the correlation identifier.
- [ ] Handle both `MethodArgumentNotValidException` and
      `HandlerMethodValidationException`.
- [ ] Map malformed bodies, missing parameters, type mismatches, unsupported methods, unacceptable
      representations, unsupported request media, and unknown API routes to stable common codes.
- [ ] Preserve required HTTP headers produced by Spring, such as `Allow` and supported media-type
      headers.
- [ ] Set the request path as the ProblemDetail `instance` without exposing query secrets.
- [ ] Centralize common enrichment so every MVC ProblemDetail receives `code` and
      `correlationId`.

### Correlation support

- [ ] Add one request filter for validation, generation, MDC setup, response-header propagation,
      and cleanup.
- [ ] Ensure the filter executes once per request and does not create a second exception boundary.
- [ ] Keep the correlation implementation independent of authentication and domain modules.

### Tests

- [ ] Test module-owned error-code compatibility through a test-only enum.
- [ ] Test custom and default `ApiException` details.
- [ ] Test generic unexpected-error sanitization.
- [ ] Test request-body field validation.
- [ ] Test query/path/header method-parameter validation.
- [ ] Test malformed JSON, missing parameters, and type mismatch.
- [ ] Test method-not-allowed and unsupported-media-type responses while preserving Spring headers.
- [ ] Test unknown API routes.
- [ ] Test correlation generation, valid propagation, invalid replacement, response-body equality,
      and MDC cleanup.
- [ ] Retain the exactly-one-controller-advice guard.
- [ ] Add a catalogue contract test for nonblank globally unique codes, safe default details, and
      valid HTTP mappings.

## Acceptance criteria

- Every tested MVC failure returns `application/problem+json` with `status`, `detail`, `code`,
  `instance`, and `correlationId`.
- Every HTTP response carries `X-Correlation-ID`.
- Domain modules can define their own error-code enums without changing `common`.
- Validation covers both request-body beans and direct controller parameters.
- Unexpected exceptions reveal no internal implementation details.
- Spring-generated protocol headers remain correct.
- The error catalogue and implementation agree exactly.
- No second application-owned controller advice exists.
- `./mvnw --batch-mode --no-transfer-progress verify` succeeds.
- Maven Enforcer, Spotless, tests, Spring Modulith verification, and JaCoCo gates pass.
- `git diff --check` reports no whitespace errors.

## Out of scope

- Business-module controllers, DTOs, entities, repositories, or migrations.
- Identity authentication and authorization implementation.
- OAuth2/JWT configuration and Spring Security entry-point implementation.
- Database-backed translations or localized server error messages.
- Payment, Booking, Work, or Messaging idempotency persistence.
- Distributed tracing infrastructure or vendor-specific observability agents.
- Rate limiting, API gateways, and external-service resilience policies.
- Resource-specific OpenAPI definitions beyond documenting the common conventions.
- Final pagination implementation before a real collection endpoint exists.

## Definition of done

FH-006 is complete when the documentation, shared error model, MVC boundary, correlation support,
and tests form one consistent contract; all local and CI verification passes; and the reviewed pull
request is merged into `main`.
