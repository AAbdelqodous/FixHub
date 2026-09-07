# FixHub API conventions

## Purpose

This document defines the public HTTP conventions shared by FixHub modules. It gives clients a
predictable contract and prevents Provider, Catalog, Marketplace, Booking, Work, Trust, Payment,
Communication, and Administration APIs from developing incompatible styles.

These conventions apply to public business APIs unless an accepted ADR or an endpoint-specific
specification defines a stricter rule. They do not authorize one module to access another module's
repository or mutable entities.

## Authority and related decisions

- [ADR 0007](../adr/0007-branch-membership-model.md) requires explicit Provider and Branch context
  for branch-relevant operations.
- [ADR 0008](../adr/0008-translation-model.md) defines locale identifiers, localized content, and
  fallback behavior.
- [ADR 0009](../adr/0009-simple-messaging-first.md) requires cursor-based Message history and one
  canonical persisted send operation.
- [ADR 0010](../adr/0010-api-error-contract-and-ownership.md) defines the public error contract,
  error-code ownership, and correlation behavior.
- [FH-006](../specs/006-api-error-conventions.md) defines the implementation and acceptance scope
  for establishing these conventions.

If this document conflicts with an accepted ADR, the ADR takes precedence and this document must
be corrected.

## General principles

- Public contracts use business terminology from the domain glossary.
- APIs expose DTOs and stable identifiers rather than persistence entities or internal package
  structures.
- Clients treat identifiers as opaque values even when the current representation is numeric.
- Commands and queries identify the intended business context explicitly.
- The server never selects the first associated Provider or Branch as an authorization fallback.
- Cross-module data is obtained through published contracts, completed events, or approved
  snapshots rather than direct repository access.
- New endpoints must be documented through OpenAPI and tested as public contracts.

## Base path and versioning

Public business endpoints use the following base path:

```text
/api/v1
```

The URI version identifies the public contract generation, not the application release. Compatible
changes are introduced within `v1`; breaking changes require an explicit migration and versioning
decision.

Breaking changes include:

- Removing or renaming a public field, error code, resource, or operation.
- Changing a field's meaning, type, format, or required status incompatibly.
- Changing an identifier or enum value that clients persist or compare.
- Tightening accepted input in a way that rejects previously valid requests without a migration
  policy.
- Changing pagination, ordering, idempotency, or authorization semantics incompatibly.

Adding an optional response field is normally compatible. Adding a required request field is not.

## Resource paths

- Paths use lowercase plural resource nouns.
- Multiword path segments use kebab-case.
- Paths do not expose Java class, table, repository, or package names.
- Canonical resource paths do not end with a trailing slash.
- Nested resources are used only when the parent is required to identify, authorize, or understand
  the child.
- Deep nesting is avoided; stable identifiers are preferred once a resource has an independent
  identity.
- Action-style suffixes are reserved for business commands that cannot be expressed clearly as a
  resource-state operation.

Examples:

```text
GET  /api/v1/providers/{providerId}
GET  /api/v1/providers/{providerId}/branches/{branchId}
POST /api/v1/service-requests
POST /api/v1/provider-quotes/{quoteId}/acceptance
```

Branch-relevant operations include both the intended Provider and Branch when both are needed to
validate ownership or authorization. A supplied Branch must belong to the supplied Provider.

## HTTP methods

| Method | Intended use |
|---|---|
| `GET` | Retrieve a resource or collection without changing business state |
| `POST` | Create a resource or execute an explicitly modeled business command |
| `PUT` | Replace a complete resource representation when the endpoint defines replacement semantics |
| `PATCH` | Apply a documented partial update |
| `DELETE` | Request deletion only when the domain lifecycle and retention policy permit it |

The owning module remains responsible for lifecycle invariants. Supporting an HTTP method does not
imply that every domain state permits the operation.

## Status codes

| Status | Use |
|---:|---|
| `200 OK` | Successful query or command returning a representation |
| `201 Created` | A resource was created; include `Location` when a stable URI is available |
| `202 Accepted` | Processing was accepted asynchronously and its status can be observed |
| `204 No Content` | A successful operation has no response representation |
| `400 Bad Request` | Validation, parsing, missing-input, or type-conversion failure |
| `401 Unauthorized` | Authentication is missing or invalid |
| `403 Forbidden` | The authenticated actor lacks permission |
| `404 Not Found` | The route is unknown or a module-owned resource lookup failed |
| `405 Method Not Allowed` | The resource does not support the HTTP method |
| `406 Not Acceptable` | No acceptable response representation is available |
| `409 Conflict` | A module-owned business conflict prevents the operation |
| `415 Unsupported Media Type` | The request representation is unsupported |
| `500 Internal Server Error` | An unexpected server failure occurred |

An endpoint must not return `200 OK` with an error object. Errors use the appropriate non-success
status and the ProblemDetail contract.

## Media types and JSON

- Successful JSON representations use `application/json`.
- Public error representations use `application/problem+json`.
- JSON property names use `lowerCamelCase`.
- A single successful resource is returned as its documented DTO without a generic success
  envelope.
- A `204 No Content` response has no body.
- Nullability and omission behavior are documented for every optional public field.
- Internal entity fields, audit implementation details, and bidirectional persistence graphs are
  not serialized automatically.
- Unknown request fields follow the configured platform policy consistently; endpoints must not
  silently implement different policies.

## Identifiers and stable values

- Resource identifiers are stable and language-independent.
- Clients do not infer resource type, ownership, authorization, chronology, or geography from an
  identifier.
- API specifications document the representation and validation rules for each identifier.
- Enum-like wire values are explicit, documented, and treated as compatibility-sensitive.
- Display labels are separate from stable codes and identifiers.
- Provider, Branch, Account, Service, Booking, and other identifiers supplied by clients are
  validated against the exact requested context.

## Dates, times, and durations

- Instants use ISO 8601 UTC values with a `Z` suffix, for example
  `2026-08-19T15:42:30Z`.
- Date-only business values use ISO 8601 `yyyy-MM-dd`.
- A date-only value must not be interpreted as midnight in an undocumented timezone.
- Local operating times are paired with an explicit business timezone when interpretation could
  otherwise be ambiguous.
- Durations use an explicitly documented unit or an ISO 8601 duration representation.
- Client-supplied timestamps do not replace authoritative server timestamps for audit, ordering,
  payment, or lifecycle facts.

## Money

- Monetary amounts use decimal values; binary floating-point values are prohibited.
- Currency uses an ISO 4217 code such as `KWD`.
- Amount and currency are transmitted together whenever currency is not fixed unambiguously by the
  contract.
- APIs document allowed scale, rounding, minimum, maximum, and sign behavior.
- Clients must not calculate authoritative Commission, earnings, Refund, Payout, or ledger state.

Example:

```json
{
  "amount": 12.500,
  "currency": "KWD"
}
```

## Collections, pagination, filtering, and sorting

Administrative and catalogue collections use page-based pagination unless the owning module
requires a stable cursor.

Page-based requests use:

- `page`: zero-based page index.
- `size`: requested page size, defaulting to 20 and limited to 100 unless the endpoint documents a
  stricter maximum.
- `sort`: an allow-listed public sort field and direction.

Endpoints reject unsupported sort fields rather than passing arbitrary property names to the
persistence layer. Filters use documented public parameter names and types; persistence expression
syntax is never exposed.

Each collection endpoint documents its default ordering and tie-breaker. Without deterministic
ordering, pagination behavior is not considered stable.

Mutable ordered histories, including Message history, use an opaque cursor and stable server-owned
order defined by the owning module. Cursor tokens are not interpreted or modified by clients.
Exact cursor request and response fields are defined in the applicable module specification.

The shared page-response DTO is deferred until the first real collection endpoint establishes its
contract. Modules must not independently create incompatible page envelopes in the meantime.

## Locale and localized content

- Locale identifiers are normalized BCP 47 language tags.
- A valid supported `Accept-Language` preference takes precedence.
- An authenticated Account preference may be used when Identity defines it and no supported
  request preference was supplied.
- Otherwise, FixHub uses the configured platform-default locale.
- Localized domain content follows ADR 0008: exact locale, base language, platform default, then
  the field's documented missing-content behavior.
- Error codes, identifiers, enum values, and other machine-readable values are never translated.
- Server error details are safe diagnostic defaults; clients translate stable error codes for
  presentation.

The response contract does not add parallel fields such as `nameAr` and `nameEn` for every locale.
An endpoint that intentionally returns multiple translations must define that administrative
representation explicitly.

## Validation

- Request bodies are validated after parsing and before business execution.
- Path variables, query parameters, and headers receive the same validation discipline as request
  bodies.
- Validation failures use `VALIDATION_ERROR` and contain an ordered `errors` list.
- A validation `field` uses the public JSON property or request-parameter name rather than an
  internal Java or persistence name.
- Malformed JSON uses `MALFORMED_REQUEST` rather than `VALIDATION_ERROR` because no valid request
  object was produced.
- Missing parameters and conversion failures use their specific common codes.
- Rejected sensitive values are not repeated in error details or logs.

Input validation does not replace authorization or domain-invariant checks. A syntactically valid
Provider and Branch pair can still be forbidden or inconsistent with domain state.

## Error responses

All public errors follow ADR 0010 and the [error catalogue](error-catalogue.md).

Required RFC 9457 members:

- `type`
- `title`
- `status`
- `detail`
- `instance`

Required FixHub extensions:

- `code`
- `correlationId`

Validation errors additionally include `errors`, with `field` and `message` for every item.

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

The response never exposes exception class names, stack traces, SQL or infrastructure details,
credentials, tokens, filesystem paths, or unrestricted rejected values.

## Correlation identifiers

- The canonical header is `X-Correlation-ID`.
- A valid supplied identifier contains 1 to 64 ASCII letters, digits, dots, underscores, or
  hyphens.
- Missing or invalid values are replaced with a server-generated UUID.
- Every response returns the effective value in `X-Correlation-ID`.
- Every ProblemDetail contains the same value as `correlationId`.
- The effective value is available to server logs during request processing and is removed from
  thread-local logging context afterward.
- Correlation identifiers do not authenticate, authorize, identify a business resource, or make a
  command idempotent.

## Idempotency and concurrency

- Commands capable of duplicating financial or business effects define an idempotency key in their
  owning module specification.
- An idempotency key is scoped to the authenticated actor and applicable business context.
- Retrying the same accepted command returns the documented original or equivalent result without
  duplicating the effect.
- Reusing a key for materially different input is rejected by the owning module.
- Messaging retains the client-generated Message identifier required by ADR 0009.
- Optimistic locking, version fields, `ETag`, and precondition headers are introduced only through
  an explicit endpoint contract; clients must not assume them before they are documented.

## Headers

| Header | Direction | Purpose |
|---|---|---|
| `Accept` | Request | Select an acceptable response representation |
| `Content-Type` | Request and response | Identify the representation media type |
| `Accept-Language` | Request | Express preferred supported locales |
| `Location` | Response | Identify a newly created resource when available |
| `X-Correlation-ID` | Request and response | Propagate or return diagnostic correlation |
| `Allow` | Response | List supported methods for a method-not-allowed response |

Business context is not selected from an Account's first association. When an endpoint uses a
Provider, Branch, idempotency, version, or other custom header, its specification defines the
trust, validation, and precedence rules explicitly.

## Security and privacy

- Authentication and authorization are evaluated independently from input validation.
- Provider operations require authorization in the exact Provider.
- Branch operations additionally require authorization covering the exact Branch.
- Identifiers supplied by a client never prove access.
- A response includes only fields the authenticated actor is permitted to view in the current
  business context.
- Contact information, private documents, object-storage references, and administrative evidence
  follow their owning workflow's disclosure policy.
- `UNAUTHORIZED` and `FORBIDDEN` use the common public contract even when Spring Security renders
  them before MVC controller advice.
- Error sanitization applies to logs and responses according to their separate audiences; secrets
  are not intentionally logged merely because they are hidden from the response.

## OpenAPI documentation

Every public endpoint documents:

- Purpose and owning module.
- Authentication and Provider or Branch authorization context.
- Request path, parameters, headers, body, and validation constraints.
- Success statuses and response schemas.
- Applicable error codes and statuses.
- Pagination, sorting, filtering, ordering, and cursor behavior when relevant.
- Locale behavior and localized fields when relevant.
- Idempotency and concurrency behavior when relevant.
- Fields that are snapshots, derived values, or current references.

OpenAPI output is a description of the reviewed contract. Generated documentation does not make an
accidental controller signature an approved public API.

## Contract verification

Tests for each endpoint cover, as applicable:

- Successful response status, media type, headers, and body.
- Request-body and direct parameter validation.
- Malformed input and type conversion.
- Authentication, permission, Provider, and Branch isolation.
- Resource absence and business conflicts.
- Stable error codes and sanitized details.
- Correlation propagation or generation.
- Pagination boundaries, deterministic ordering, allow-listed sorting, and cursor behavior.
- Locale selection and missing-content behavior.
- Idempotent retries and concurrency conflicts.
- OpenAPI-visible response and validation expectations.

## Change control

A new endpoint may introduce a stricter module rule but must not silently contradict this document.
A proposed exception records its business reason and compatibility impact in the owning
specification. Cross-module or platform-wide exceptions require architecture review and, when the
decision is durable or costly to reverse, an ADR.
