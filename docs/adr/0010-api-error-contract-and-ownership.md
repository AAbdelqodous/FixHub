# ADR 0010: API error contract, ownership, and correlation

- Status: Accepted
- Date: 2026-08-19

## Context

FixHub needs one predictable public error contract before business modules introduce their APIs.
Clients must be able to handle validation failures, domain rule violations, Spring MVC failures,
and unexpected server failures without depending on Java exception types or module internals.

ADR 0001 established RFC 9457 `ProblemDetail` with a stable string `code` as the public error
shape. Its initial implementation binds `ApiException` directly to one common
`BusinessErrorCode` enum and derives the wire code from the enum constant name. That model is
adequate for the common foundation but does not scale to the module boundaries accepted by FH-005.
An ever-growing common enum would transfer ownership of Provider, Catalog, Booking, Payment, and
other domain failures into `common`.

ADR 0002 initially structured only `@Valid` request-body failures through
`handleMethodArgumentNotValid`. Direct constraints on request parameters, path variables, and
headers were deliberately deferred. Public business endpoints now require both validation paths
to produce the same predictable contract.

Framework-raised failures also need stable FixHub codes. Malformed JSON, missing parameters, type
mismatches, unsupported methods, unacceptable representations, unsupported media types, and
unknown API routes must not fall back to inconsistent or implementation-dependent bodies.

Operational diagnosis requires an identifier that connects an HTTP request, its response, and
server logs. A client-provided identifier cannot be trusted without validation, and diagnostic
correlation must remain separate from authentication, authorization, business identity, and
idempotency.

The error boundary must preserve useful protocol behavior supplied by Spring while preventing
exception class names, stack traces, SQL details, credentials, tokens, filesystem paths, and other
sensitive implementation data from reaching clients.

## Decision

FixHub retains RFC 9457 `ProblemDetail` as the only public HTTP error representation and introduces
a shared error-code contract with module-owned code catalogues.

This decision refines ADR 0001 by replacing its direct dependency on one common
`BusinessErrorCode` enum. It expands ADR 0002 by requiring structured handling for both request-body
validation and direct method-parameter validation. The remaining decisions in ADRs 0001 and 0002
continue to apply where they do not conflict with this ADR.

### Error-code ownership

The `common` module owns:

- A minimal error-code contract exposing a stable string code, safe default diagnostic detail,
  and HTTP status.
- Codes for framework and cross-cutting failures.
- `ApiException` and the common HTTP error-rendering boundary.
- Correlation-identifier infrastructure.

Each business module owns the codes for failures in its domain. A Provider error belongs to the
Provider module, a Booking error belongs to the Booking module, and so on. A business module must
not place its codes in a central enum merely to reuse the common renderer.

Public codes use uppercase snake case and are globally unique across FixHub. Domain codes use a
clear module or aggregate prefix when needed to preserve that uniqueness. A published code must not
be renamed or reused with different semantics because clients may use it for control flow,
translation, analytics, or support documentation.

The public code is an explicit value of the shared contract. It is not required to be derived from
a Java enum constant name. Numeric error identifiers are not part of the public contract.

Every error code defines one default diagnostic detail and one HTTP status. A caller may provide a
safe contextual detail through `ApiException`, but it must not change the code's meaning or expose
sensitive data.

The initial common catalogue contains:

| Code | HTTP status | Meaning |
|---|---:|---|
| `VALIDATION_ERROR` | 400 | Bean or method-parameter validation failed |
| `MALFORMED_REQUEST` | 400 | The request body could not be parsed |
| `MISSING_PARAMETER` | 400 | A required request parameter is absent |
| `TYPE_MISMATCH` | 400 | A path, query, or header value could not be converted |
| `UNAUTHORIZED` | 401 | Authentication is required or invalid |
| `FORBIDDEN` | 403 | The authenticated actor lacks permission |
| `ENDPOINT_NOT_FOUND` | 404 | No API resource or route matches the request |
| `METHOD_NOT_ALLOWED` | 405 | The HTTP method is unsupported for the resource |
| `NOT_ACCEPTABLE` | 406 | No acceptable response representation is available |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | The request media type is unsupported |
| `INTERNAL_ERROR` | 500 | An unexpected server failure occurred |

Business resource absence is expressed by a module-owned code, such as
`PROVIDER_BRANCH_NOT_FOUND`, rather than the framework-level `ENDPOINT_NOT_FOUND` code.

### ProblemDetail representation

Every public error response uses `application/problem+json` and contains the standard RFC 9457
members:

- `type`
- `title`
- `status`
- `detail`
- `instance`

Every response also contains these FixHub extensions:

- `code`, containing the stable public error code.
- `correlationId`, containing the effective request correlation identifier.

Validation failures additionally contain an ordered `errors` list. Each item contains a `field`
and a diagnostic `message`. The same representation is used for request-body field validation and
direct controller-parameter validation.

The `instance` identifies the request path without copying query-string secrets. Spring-provided
protocol headers, including `Allow` and supported-media-type information, are preserved when
applicable.

Server-provided `detail` and validation messages are safe diagnostic defaults. They are not the
stable localization contract. Clients translate and present errors from the stable `code`; codes,
identifiers, and machine-readable values are never translated.

Unexpected exceptions are logged with their original diagnostic context but rendered to the
client only as `INTERNAL_ERROR` with a generic safe detail.

### MVC error boundary

FixHub keeps exactly one application-owned `@RestControllerAdvice`. It extends Spring's
`ResponseEntityExceptionHandler` so domain and framework failures pass through one rendering and
enrichment boundary.

The boundary handles both `MethodArgumentNotValidException` and
`HandlerMethodValidationException`. It also maps the supported Spring MVC parsing, binding,
conversion, method, representation, media-type, and route failures to the common catalogue.

Authentication and access-denied failures may occur before MVC advice. Their stable
`UNAUTHORIZED` and `FORBIDDEN` codes are reserved here, while the Spring Security entry point and
access-denied handler will render the same public contract in the Identity and security work.

No transport adapter or business module may introduce a competing public error envelope or a
second general-purpose controller advice.

### Correlation identifiers

The canonical HTTP header is `X-Correlation-ID`.

A client-supplied value is retained only when it contains between 1 and 64 ASCII letters, digits,
dots, underscores, or hyphens. A missing or invalid value is replaced with a server-generated UUID.
The raw invalid value is not copied into responses or logs.

The effective identifier is:

- Returned in the `X-Correlation-ID` response header for every HTTP response.
- Included as `correlationId` in every public `ProblemDetail`.
- Placed in the logging MDC under `correlationId` during request processing.
- Removed from the MDC in a `finally` block so pooled request threads do not leak context.

Correlation identifiers are diagnostic metadata only. They do not establish identity, grant
permission, deduplicate a command, or replace a business or idempotency identifier.

This decision does not prescribe endpoint-specific domain codes, localized client wording,
security configuration, distributed-tracing vendors, database tables, or observability backends.

## Alternatives considered

Keep one global `BusinessErrorCode` enum in `common` — rejected. It would make `common` the owner of
every module's business failures, create a cross-team change hotspot, and weaken the module
boundaries accepted in FH-005.

Derive every public code from `Enum.name()` — rejected as a requirement. It couples a published API
value to a Java refactoring detail. Implementations may use matching enum names, but the stable code
is an explicit contract value.

Use a custom error envelope instead of `ProblemDetail` — rejected. It would duplicate the standard
framework representation and risk separate shapes for domain and Spring MVC failures.

Use numeric error codes — rejected. Numbers are not self-describing in logs or client code and
require an external lookup to understand their meaning.

Return localized server messages as the canonical contract — rejected. Locale-specific text is not
stable for programmatic handling. Clients localize stable codes, while server details remain safe
diagnostic defaults.

Handle only request-body validation — rejected. Query parameters, path variables, and headers are
part of the same public API and must not receive a weaker or inconsistent error response.

Trust any client-provided correlation identifier — rejected. Unbounded or control-character values
could pollute logs, responses, and monitoring systems.

Add correlation identifiers only to failures — rejected. Successful requests must also be
traceable, and middleware cannot know at request entry whether processing will fail.

Create separate controller advice classes per module — rejected. Ordering and overlap between
general advice classes could make the response depend on which handler intercepted the exception.
Modules contribute error codes; the common boundary owns HTTP rendering.

Expose raw exception messages for unexpected failures — rejected. Internal messages can reveal
implementation, infrastructure, or sensitive data and are not a stable public contract.

## Consequences

- Clients receive one predictable RFC 9457 shape for tested MVC and domain failures.
- Every public error has a stable string code and correlation identifier.
- Business modules can add domain errors without modifying a central common enum.
- Published code values require compatibility discipline and catalogue review.
- The common module must provide and test the shared error-code contract, framework catalogue,
  `ApiException`, renderer, and correlation filter.
- Existing `BusinessErrorCode` usages must migrate to the new common framework catalogue or a
  module-owned catalogue as appropriate.
- The unused numeric error-code field is removed from the public error model.
- Both request-body and direct method-parameter validation require structured field or parameter
  extraction and deterministic ordering.
- Framework exception mappings must preserve Spring-generated protocol headers.
- Unexpected exceptions remain fully available to server logs but are sanitized in responses.
- Every HTTP request incurs a small amount of correlation validation, UUID generation when needed,
  response-header work, and MDC lifecycle management.
- Security handlers implemented later must reuse the same codes and response contract even though
  they execute outside MVC controller advice.
- Tests must cover code uniqueness, module-owned compatibility, validation paths, framework
  mappings, sanitization, header preservation, correlation propagation and replacement, and MDC
  cleanup.
- ADR 0001 and ADR 0002 must be updated with references to this refining decision so readers do not
  implement their superseded limitations.
