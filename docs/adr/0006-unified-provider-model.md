# ADR 0006: Unified Provider model

- Status: Accepted
- Date: 2026-08-10

## Context

FixHub's supply side includes both maintenance centers and individual professionals or
technicians.

Although these provider types differ operationally, they participate in the same marketplace:
they publish service offerings, appear in discovery results, receive service requests, provide
quotes, accept bookings, perform work, receive reviews, and earn payments.

Modeling maintenance centers and individual professionals as separate business roots would
duplicate these workflows and create parallel public contracts. Their behavior could then
diverge even though customers interact with both through the same FixHub marketplace.

A Provider is also different from an Account. An Account represents platform identity and
authentication, while a Provider represents an approved business presence offering services.
A Provider must therefore not be modeled as merely the first maintenance center associated
with an Account.

Branch organization and branch-scoped Membership permissions require a separate decision and
are defined by the following ADR under FH005-T05.

## Decision

FixHub uses one canonical `Provider` domain concept owned by the Provider module.

Every Provider has an explicit `ProviderType`:

- `CENTER` represents an organization or maintenance center.
- `INDIVIDUAL` represents an individual professional or technician offering services directly.

`ProviderType` classifies the Provider's operating model. It does not create separate provider
aggregates, modules, or public APIs.

Both types use the same Provider identity, approval lifecycle, public profile contract, and
integration boundary. Type-specific invariants may be enforced internally by the Provider
module without exposing separate center and individual domain models.

Discovery, quotation, booking, work, trust, payment, and communication workflows reference the
same stable Provider identity regardless of type. They may use `ProviderType` when a legitimate
business rule requires it, but they must not implement separate center-only and
individual-only workflow families.

An Account may be associated with one or more Providers through Membership. Account ownership,
authentication, or registration order must not determine Provider identity, and no design may
assume that the relevant Provider is the first maintenance center associated with the Account.

## Alternatives considered

Model maintenance centers and individual professionals as separate domain roots — rejected.
They participate in the same marketplace workflows, so separate roots would duplicate
discovery, quotation, booking, work, review, payment, and communication contracts. The two
models could then evolve inconsistently.

Define a shared abstract provider type with public `CenterProvider` and `IndividualProvider`
subtypes — rejected. Although this would share some implementation, downstream modules would
still depend on provider-specific types and would tend to recreate separate workflow branches.
Type-specific rules belong behind the Provider module's unified contract.

Model an individual professional directly as an Account and use Provider only for maintenance
centers — rejected. Account represents identity and authentication, while Provider represents
an approved service-supplying business presence. Combining them would prevent a clean
Account-to-Provider relationship and would make authorization, approval, and future
multi-provider participation ambiguous.

Support only maintenance centers initially and introduce individuals later — rejected. The
individual-provider requirement is already known. Deferring it would allow center-specific
assumptions to become embedded in APIs, events, persistence, and downstream workflows.

## Consequences

- All supply-side workflows use one stable Provider identity and one shared Provider contract.
- Downstream modules must treat Providers uniformly unless an explicit business rule genuinely
  depends on `ProviderType`.
- Type-specific validation remains internal to the Provider module and must not leak into
  parallel center-only or individual-only public APIs.
- Account identity and Provider business identity remain separate, allowing an Account to
  participate in more than one Provider through Membership.
- Shared workflows become easier to maintain and test, but Provider-module tests must cover
  both common invariants and legitimate type-specific behavior.
- Adding another Provider type requires an explicit domain and architecture review; downstream
  modules must not invent provider types independently.
- Legacy maintenance-center and professional records must eventually map to the canonical
  Provider concept while preserving their original identity and traceability.
- This decision does not prescribe a database inheritance strategy, table structure, or entity
  mapping. Those implementation choices belong to later persistence work.
- Branch cardinality, individual-provider branch behavior, and branch-scoped Membership
  authorization remain deliberately unresolved here and are decided by ADR 0007 under
  FH005-T05.
