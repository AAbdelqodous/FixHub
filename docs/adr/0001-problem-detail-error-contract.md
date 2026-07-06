# ADR 0001: RFC 9457 ProblemDetail + stable string error code as the API error contract

- Status: Accepted
- Date: 2026-07-06

## Context

`ResponseEntityExceptionHandler` already renders framework-level failures (404s,
unsupported media type, etc.) as RFC 9457 (formerly RFC 7807 — same shape,
newer RFC number) `ProblemDetail` bodies. Our own domain exceptions
(`ApiException`) need an error shape that clients can parse the same way,
rather than a second, bespoke JSON envelope living next to the framework's.

Separately: the frontend needs to translate error messages, and error messages
get read out of logs by humans. Both want something stable and human-readable
to key off, not a number that means nothing without looking it up.

## Decision

`GlobalExceptionHandler` extends `ResponseEntityExceptionHandler` and builds every
response — domain and framework alike — as a `ProblemDetail`. Domain errors add one
extra property, `code`, which is the **string name of the `BusinessErrorCode`
constant** (e.g. `"RESOURCE_NOT_FOUND"`), emitted via `errorCode.name()`. The
enum also carries a default `message` and an `HttpStatus` per business error
case; `ApiException` carries the `BusinessErrorCode` it was raised with.

The frontend translates on this string code (its own `messages.json`/i18n
table keyed by `RESOURCE_NOT_FOUND`, `VALIDATION_ERROR`, etc.) rather than the
server sending pre-translated or multi-locale text. The server never emits
locale-specific fields (no `messageAr`/`messageEn` on the wire) — `detail` is a
developer-facing/log-facing default message, not something to show end users
directly, and the string `code` is the one and only translation key.

### Sample response

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "Maintenance center 42 not found",
  "instance": "/api/v1/centers/42",
  "code": "RESOURCE_NOT_FOUND"
}
```

## Alternatives considered

- A custom envelope, e.g. `{ "error": { "code": ..., "message": ... } }`.
  Rejected: it would duplicate what `ProblemDetail` already gives us for
  framework-raised errors, leaving two shapes on the wire depending on which
  layer failed.
- A numeric `code` (`BusinessErrorCode.code`, an `int`). Rejected for the wire:
  numbers aren't what the frontend keys translations on and aren't
  self-describing in logs. The enum still carries an internal `int code`
  field; it isn't serialized.

## Consequences

- Every error handler in `GlobalExceptionHandler` must produce a `ProblemDetail`.
- Every `BusinessErrorCode` constant must carry a real `HttpStatus`, not just an
  arbitrary integer.
- Renaming a `BusinessErrorCode` constant is a breaking change to the wire
  contract (it's also the frontend's translation key) — treat it like renaming
  a public API field.
