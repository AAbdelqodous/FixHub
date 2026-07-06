# ADR 0001: RFC 7807 ProblemDetail + business error code as the API error contract

- Status: Accepted
- Date: 2026-07-06

## Context

`ResponseEntityExceptionHandler` already renders framework-level failures (404s,
unsupported media type, etc.) as RFC 7807 `ProblemDetail` bodies. Our own domain
exceptions (`ApiException`) need an error shape that clients can parse the same
way, rather than a second, bespoke JSON envelope living next to the framework's.

## Decision

`GlobalExceptionHandler` extends `ResponseEntityExceptionHandler` and builds every
response — domain and framework alike — as a `ProblemDetail`. Domain errors add one
extra property, `code`, sourced from a `BusinessErrorCode` enum that pairs a stable
numeric code, a default message, and an `HttpStatus` per business error case.
`ApiException` carries the `BusinessErrorCode` it was raised with.

## Alternatives considered

A custom envelope, e.g. `{ "error": { "code": ..., "message": ... } }`. Rejected: it
would duplicate what `ProblemDetail` already gives us for framework-raised errors,
leaving two shapes on the wire depending on which layer failed.

## Consequences

- Every error handler in `GlobalExceptionHandler` must produce a `ProblemDetail`.
- Every `BusinessErrorCode` constant must carry a real `HttpStatus`, not just an
  arbitrary integer.
