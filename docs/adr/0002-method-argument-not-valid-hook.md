# ADR 0002: Use handleMethodArgumentNotValid for field-level validation errors

- Status: Accepted
- Date: 2026-07-06

## Context

`ResponseEntityExceptionHandler` exposes two extension points for validation
failures:

- `handleMethodArgumentNotValid` — bean validation failures on `@Valid`-annotated
  method arguments (typically `@RequestBody`), carrying a `BindingResult` with
  `FieldError`s (`field` + `defaultMessage`).
- `handleHandlerMethodValidationException` (Spring 6.1+) — constraint annotations
  placed directly on individual method parameters (e.g. `@RequestParam @Min(1)`),
  carrying per-parameter `ParameterValidationResult`s rather than per-field errors.

## Decision

Override `handleMethodArgumentNotValid` to add `code` (`BusinessErrorCode.VALIDATION_ERROR`)
and an `errors` list of `{ field, message }`, built directly from
`ex.getBindingResult().getFieldErrors()`.

## Alternatives considered

`handleHandlerMethodValidationException` — rejected for now. Its errors are keyed
per method parameter, not per bean field, so they don't map onto a flat
`{ field, message }` contract without an extra translation step.

## Consequences

Only `@Valid`-annotated body/bean argument failures get the structured `errors`
list today. Constraint violations on individual `@RequestParam`/`@PathVariable`
parameters will fall through to the framework default `ProblemDetail` until
`handleHandlerMethodValidationException` is also overridden.
