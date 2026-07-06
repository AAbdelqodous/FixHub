# ADR 0005: Adopt Spring Modulith now, verification only

- Status: Accepted
- Date: 2026-07-06

## Context

The codebase currently has a single `common` package. That's also the cheapest
point to start enforcing module boundaries: there's nothing yet to untangle,
unlike bolting this on after several packages have grown implicit
cross-dependencies.

## Decision

Add `spring-modulith-starter-test` now and a `ModularityTests` guard
(`ApplicationModules.of(FixhubCoreApplication.class).verify()`) that fails the
build the moment a package reaches into another module's internals instead of
its public API. No package restructuring or explicit `package-info.java`
module declarations yet — `common` is verified as Modulith's default
"one module per direct subpackage of the application package" reading, which
is already true today.

## Alternatives considered

Deferring adoption until a second business module exists — rejected. Waiting
means the first real boundary violation would already be baked into working
code and harder to unwind than adding one dependency and one test today.

## Consequences

- Every new top-level package under `com.fixhub.platform` becomes a Modulith
  module automatically; `ModularityTests` will fail the build if one reaches
  into another module's non-API classes.
- No named/explicit module metadata yet — expect to add
  `package-info.java`/`ApplicationModule` annotations once the implicit,
  package-based reading of module boundaries stops being sufficient (e.g. once
  a module needs to expose an explicit public API surface).
