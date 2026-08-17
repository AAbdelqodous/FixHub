# ADR 0007: Branch and Membership model

- Status: Accepted
- Date: 2026-08-10

## Context

ADR 0006 establishes one canonical Provider model with `CENTER` and `INDIVIDUAL`
Provider types. Both types require an operational context for location, contact details,
operating hours, booking eligibility, work execution, and staff authorization.

A center may operate through one or several locations. An individual professional normally
operates through one location, mobile-service area, or equivalent operating context.

Making Branch optional for individual Providers would introduce nullable Branch references and
separate individual-only paths throughout Catalog, Marketplace, Booking, Work, Trust, Payment,
and Communication. It would also weaken authorization because some operations would be scoped
to a Branch while others would rely only on a Provider.

Account identity is separate from Provider participation. One Account may own or work for
multiple Providers, while one Provider may be operated by multiple Accounts with different
responsibilities.

Global roles owned by Identity are insufficient for Provider authorization. Provider access must
be based on an explicit Membership and must be evaluated against the Provider and, where
applicable, the Branch involved in the requested operation.

Selecting the first Provider or Branch associated with an Account is ambiguous and creates a
cross-provider authorization risk. Provider and Branch context must therefore be explicit.

## Decision

Every Provider has at least one Branch owned by the Provider module.

A `CENTER` Provider has one or more Branches. It may begin with one Branch and add additional
Branches without changing its Provider identity.

An `INDIVIDUAL` Provider has exactly one canonical Branch. This Branch represents the
individual's operating location, mobile-service area, or equivalent service context. It does not
turn the individual into a maintenance center or imply that the individual operates a separate
organization.

The canonical Branch is a required part of individual-provider onboarding. Downstream modules
use the same Provider-and-Branch contracts for both Provider types and must not introduce a
nullable or individual-only Branch alternative.

Every Branch:

- Has its own stable identity.
- Belongs to exactly one Provider.
- Cannot be transferred silently to another Provider.
- Maintains its Provider-owned operational details and lifecycle.
- Must be validated against its Provider whenever both identifiers are supplied.

Branch-relevant operations must identify the intended Provider and Branch explicitly. A query,
command, or authorization rule must not select the first associated Provider or Branch, and a
center's default Branch must not be used as an implicit authorization fallback.

A Membership is the Provider-owned association between one Account and one Provider. An
Account may hold Memberships in multiple Providers, and a Provider may have multiple members.
Membership does not transfer ownership of the Account from Identity to Provider.

A Membership contains explicit Provider Role grants. Each grant has an explicit scope:

- Provider scope applies to the Provider and, when the role's permissions include Branch
  operations, to every Branch belonging to that Provider.
- Branch scope applies only to one or more explicitly identified Branches belonging to that
  Provider.

Scope must be represented explicitly and must not be inferred from a null Branch identifier.
The `OWNER` role is Provider-scoped. Its permitted Branch operations cover every Branch belonging
to the Provider. Other Provider Roles may use only the scopes allowed by their role definition
and authorization policy.

Authorization for a Provider operation requires an active Membership in the exact Provider and a
role grant that permits the requested operation.

Authorization for a Branch operation additionally requires the grant's scope to cover the exact
requested Branch. A Provider-scoped grant covers the Branch only when its role permits the
requested Branch operation. A Branch-scoped grant covers only the explicitly identified
Branches. A Membership or role grant for one Provider never authorizes access to another
Provider.

Identity remains responsible for authentication, Account status, and global roles. The Provider
module remains responsible for Membership lifecycle, Provider Roles, scope validation, and the
decision to allow or deny Provider and Branch operations.

This decision defines the domain and authorization boundaries but does not prescribe database
tables, join-table structures, JPA mappings, or Spring Security implementation details.

## Alternatives considered

Make Branch optional for `INDIVIDUAL` Providers — rejected. This would create nullable Branch
references and separate workflow and authorization paths across downstream modules. The same
service-supply concepts would then have inconsistent contracts depending on Provider type.

Represent an individual's operating context directly on Provider instead of using Branch —
rejected. This would duplicate location, service-area, operating-hours, booking, and authorization
concepts and would prevent downstream modules from using one Provider-and-Branch contract.

Create one Membership for every Account-and-Branch association — rejected. This would duplicate
membership lifecycle and Provider Roles when an Account works across several Branches, and it
would make Provider-scoped ownership difficult to represent. One Provider-level Membership with
explicitly scoped grants preserves both Provider-wide and Branch-limited access.

Use Identity global roles for Provider access — rejected. Global roles cannot express
participation in multiple Providers, Provider-specific responsibilities, or Branch scope, and
would transfer Provider authorization decisions into the Identity module.

Automatically choose the first or default Provider or Branch associated with an Account —
rejected. Association order may change and does not express user intent. An implicit selection
could execute an operation against the wrong Provider and create a cross-provider security issue.

## Consequences

- Every Provider onboarding flow must create at least one Branch.
- A `CENTER` Provider may have multiple Branches, while an `INDIVIDUAL` Provider has exactly one
  canonical Branch.
- Common downstream contracts can require Provider and Branch context without a nullable or
  individual-only alternative.
- Commands, queries, and authorization checks must receive or resolve from trusted context the
  intended Provider and, for Branch operations, the intended Branch.
- The Provider module must validate that every supplied Branch belongs to the supplied Provider
  before applying business or authorization rules.
- Memberships remain Provider-level, while role grants represent Provider or Branch scope
  explicitly.
- The `OWNER` role can perform its permitted Branch operations across every Branch of its Provider
  but gains no authority in another Provider.
- Identity and Provider authorization remain separate: authentication and global roles do not
  replace Membership and scoped Provider Role checks.
- Legacy-data migration and new onboarding must create a canonical Branch for each individual
  Provider and at least one Branch for each center Provider.
- Supporting a canonical Branch for individuals adds a small amount of data and lifecycle work
  but removes special cases from all downstream workflows.
- Authorization tests must cover inactive Memberships, insufficient permissions, Branch scope,
  Provider-wide scope, mismatched Provider-and-Branch identifiers, and cross-provider isolation.
- A future requirement for an individual Provider to operate through multiple Branches requires
  an explicit domain and architecture review rather than silently violating this cardinality.
- This decision does not prescribe persistence tables, entity mappings, or Spring Security
  implementation details.
