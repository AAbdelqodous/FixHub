# ADR 0005: Adopt Spring Modulith now, `common` declared open

- Status: Accepted
- Date: 2026-07-06

## Context

The codebase currently has a single `common` package. That's also the cheapest
point to start enforcing module boundaries: there's nothing yet to untangle,
unlike bolting this on after several packages have grown implicit
cross-dependencies.

## Decision

Add a `ModularityTests` guard
(`ApplicationModules.of(FixhubCoreApplication.class).verify()`) that fails the
build the moment a package reaches into another module's internals instead of
its public API. No package restructuring beyond `common` (see below) —
Modulith's default "one module per direct subpackage of the application
package" reading is already true today.

`common` itself gets one explicit declaration now, not deferred:
`package-info.java` marks it `@ApplicationModule(type = Type.OPEN)`. Every
future module depends on `common`'s subpackages (`common.error`,
`common.jpa`) directly, and Modulith's default reading is `CLOSED` — only a
module's top-level package is public. Left as `CLOSED`, `verify()` would pass
today (nothing else imports `common` yet) and then fail the instant the first
real module (e.g. `auth`) throws an `ApiException` or extends
`AuditableEntity`. Declaring `common` `OPEN` now, while it's a one-line change
with no other module to coordinate with, avoids that guaranteed break later.

That annotation lives in main source (`package-info.java`, not a test class),
so it needs `spring-modulith-starter-core` on **compile** scope — the
`spring-modulith-starter-test` dependency alone only puts the Modulith API on
the test classpath, which isn't enough to compile `common`'s package
declaration.

## Alternatives considered

Deferring adoption until a second business module exists — rejected. Waiting
means the first real boundary violation would already be baked into working
code and harder to unwind than adding one dependency and one test today.

Leaving `common` at Modulith's default `CLOSED` reading and deferring the
`package-info.java` declaration until a second module actually needs it —
rejected. `verify()` would stay green today for the wrong reason (no other
module exists yet to violate it), then fail the moment `auth` lands, turning
a one-line, no-coordination change today into a build failure someone has to
diagnose mid-Phase-2. `common` is a shared kernel by design, so `OPEN` isn't
a guess about the future — it's already the correct, known answer.

## Consequences

- Every new top-level package under `com.fixhub.platform` becomes a Modulith
  module automatically; `ModularityTests` will fail the build if one reaches
  into another module's non-API classes.
- `common` is the one named exception: it's declared `OPEN`, so nothing in it
  is subject to the internal-package check and it's excluded from cycle
  detection. Every other future module defaults to `CLOSED` and gets no
  special treatment — if a module needs to expose part of itself to others,
  that's a `NamedInterface`, decided when that need actually arises.
