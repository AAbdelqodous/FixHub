# FixHub Domain Glossary

## Purpose

This glossary defines the canonical business terminology used throughout FixHub.

It establishes a shared vocabulary for requirements, architecture, source code, APIs,
database design, and documentation. Legacy terminology from previous maintenance
applications must not be reused unless it matches the definitions documented here.

The glossary describes business concepts and does not prescribe database tables,
JPA entities, or API representations.

## Identity

### Account

Represents a person who can authenticate with FixHub.

An Account owns authentication and platform-level identity information. Business
permissions related to a provider or branch are assigned through Membership rather
than being stored directly on the Account.

Legacy generic `User` terminology should not be used as the primary FixHub domain
term.

### Credentials

Authentication information associated with an Account.

Credentials prove the identity of an Account and must be treated separately from
business profile and authorization information.

### Verification

The process of confirming an Account identity attribute or required evidence, such
as an email address or phone number.

Verification does not by itself grant provider-level permissions.

### Session / Refresh Token

Authentication lifecycle concepts that allow an Account to maintain or renew an
authenticated session.

They belong to the identity/security boundary rather than the business domains.

### Global Role

A platform-wide authorization role assigned to an Account.

Initial global roles are:

- `CUSTOMER` — allows an Account to use FixHub customer capabilities.
- `PLATFORM_ADMIN` — allows authorized administration of the FixHub platform.

Global Roles must not be used to represent a person's responsibilities within a
Provider or Branch. Those responsibilities are represented by Provider Roles through
Membership.

## Provider

### Provider

The business party that offers maintenance or repair services through FixHub.

A Provider has one of the following types:

- `CENTER` — an organization or maintenance center providing services.
- `INDIVIDUAL` — an individual professional or technician providing services.

`MaintenanceCenter` is therefore not the generic supply-side concept in FixHub.
It is represented by a Provider of type `CENTER`.

### Branch

A business or service location associated with a Provider.

Branches allow FixHub to represent provider operations at distinct locations without
duplicating the Provider itself.

A Branch may later own operational information such as location, contact details,
operating hours, offerings, staff assignments, and booking availability.

### Membership

Represents an Account's authorized relationship with a Provider and, when applicable,
a Branch.

Membership separates authentication identity from provider-specific responsibilities.
An Account can therefore participate in provider operations without requiring a
special platform-wide user type.

### Provider Role

Defines the responsibilities granted through a Membership.

Initial Provider Roles are:

- `OWNER`
- `BRANCH_MANAGER`
- `RECEPTIONIST`
- `TECHNICIAN`
- `ACCOUNTANT`

Provider Roles are scoped to provider operations and must not be confused with
Global Roles such as `CUSTOMER` or `PLATFORM_ADMIN`.

### Approval

The platform-controlled process that determines whether a Provider is authorized to
operate on FixHub.

Approval is distinct from Account verification and provider Membership.

### Operating Hours

The periods during which a Provider or Branch is normally available for business.

Operating Hours describe general availability. They are distinct from bookable
appointment Slots, which belong to the booking domain.

## Catalog

### Category

A classification used to organize the types of maintenance and repair services available
through FixHub.

Categories help customers navigate and discover relevant Services. They describe what
kind of need is being addressed and are independent of any particular Provider.

### Service

A canonical definition of a maintenance or repair capability recognized by FixHub.

A Service belongs to the platform catalog and is independent of the Provider that
performs it.

A Service must not be confused with a Provider Offering. The Service describes what
can be provided; a Provider Offering describes a specific Provider's ability to provide
that Service.

### Translation

A localized representation of catalog content for a supported FixHub language.

Translations allow canonical concepts such as Categories and Services to be presented
in multiple languages without creating separate business concepts for each language.

### Provider Offering

Represents a Provider's declaration that a particular Service is available through its
operations.

A Provider Offering connects the shared FixHub catalog to the supply side of the
marketplace. Provider-specific information such as availability and pricing indications
may be associated with the offering.

A Provider Offering must not redefine the canonical Service itself.

### Pricing Indication

Non-binding pricing information associated with a Provider Offering.

A Pricing Indication helps customers understand an expected price, starting price, or
price range before requesting or booking work.

It is not a Provider Quote and does not represent a final financial commitment.

## Marketplace

### Discovery

The process through which customers find suitable Providers, Branches, Services, and
Provider Offerings.

Discovery may consider criteria such as category, service, location, availability,
provider type, and reputation.

Discovery identifies possible options; it does not create a Service Request or Booking.

### Favorite

A customer's saved reference to a marketplace item for convenient future access.

Favorites represent customer preference only. Saving an item does not create a
Booking, Service Request, or commercial commitment.

The exact types of marketplace items that can be favorited are defined by the
marketplace requirements.

### Service Request

A customer's structured expression of a maintenance or repair need for which provider
proposals may be requested.

A Service Request describes the customer's need rather than representing a Booking.

Legacy `QuoteRequest` terminology maps to Service Request because the customer's
primary action is expressing a service need, while the Provider creates the Quote.

### Matching

The process of identifying Providers that may be suitable and eligible to respond to a
Service Request.

Matching may consider the requested service, location, provider capabilities, and other
marketplace rules.

Being matched does not guarantee that a Provider will submit a Quote.

### Provider Quote

A Provider's commercial proposal in response to a Service Request.

A Provider Quote may contain proposed pricing, scope, timing, and other relevant terms.

A Quote is not a Booking. A Booking created through the quotation path occurs only
after a valid Quote is accepted.

Legacy `QuoteResponse` and `BookingQuote` concepts map to Provider Quote where they
represent this commercial proposal.

### Quote Revision

A newer version of a Provider Quote created when the proposed commercial terms are
changed.

Quote revisions preserve the distinction between the original proposal and subsequent
changes so that the quotation lifecycle remains traceable.

### Quote Acceptance

The customer's explicit acceptance of a valid Provider Quote.

Quote Acceptance completes the quotation decision and initiates the accepted-quote
Booking path.

Quote Acceptance is not itself payment and must not be treated as confirmation that
money has been collected.

## Booking

### Booking

Represents a customer's scheduled service engagement with a Provider.

FixHub supports two paths that can create a Booking:

- Direct Booking — created through the direct appointment-booking path.
- Accepted-Quote Booking — created after the customer accepts a valid Provider Quote.

Both paths ultimately enter the same Booking lifecycle. The creation path must not result
in separate and incompatible booking models.

A Booking coordinates the service engagement but must not be treated as the owner of
payment, work execution, or review data.

### Direct Booking

A Booking created when a customer selects an available service option and books it
without first completing the Service Request and Provider Quote process.

Direct Booking is appropriate when the Provider Offering and availability are sufficient
for the customer to proceed directly.

### Accepted-Quote Booking

A Booking created from an accepted Provider Quote.

The accepted Quote establishes the agreed proposal that led to the Booking. The Booking
then follows the standard Booking lifecycle rather than maintaining a separate lifecycle
for quoted work.

### Slot

A discrete period of availability that may be offered for appointment booking.

Slots are derived from operational availability but are distinct from Operating Hours.
Operating Hours describe when a Provider or Branch normally operates; Slots represent
specific bookable appointment opportunities.

### Booking Status Lifecycle

The controlled progression of a Booking through its business states.

The lifecycle defines which state transitions are valid and prevents arbitrary or
contradictory Booking state changes.

The exact statuses and transition rules belong to the Booking specification rather than
this glossary.

### Cancellation

The explicit termination of a Booking before normal completion.

Cancellation is a business event rather than deletion of the Booking. Relevant
information such as who cancelled, when cancellation occurred, and the applicable reason
or policy must remain traceable.

Cancellation may have consequences for payment or provider operations, but those effects
belong to their respective domain boundaries.

## Work

### Assignment

The allocation of responsibility for performing booked work to an eligible Provider
member, typically a Membership with the `TECHNICIAN` Provider Role.

Assignment describes operational responsibility and does not change Account or
Membership authorization.

### Diagnosis

The professional assessment of the customer's maintenance or repair need after work has
entered the Provider's operational process.

Diagnosis records findings about the problem and may influence the work that is required.

A Diagnosis does not by itself change an accepted commercial agreement. Changes that
affect agreed commercial terms must follow the appropriate business process.

### Progress

The traceable operational advancement of work being performed for a Booking.

Progress belongs to work execution and must not be confused with the Booking Status
Lifecycle, although changes in work state may cause valid Booking lifecycle transitions.

### Media

Photos, images, documents, or other evidence associated with work execution.

Media may document condition, diagnosis, progress, or completion. Media storage and
access-control mechanisms are implementation concerns and are not defined by this
glossary.

### Completion

The business event indicating that the Provider has finished the agreed work.

Completion may cause corresponding Booking lifecycle changes, but it does not by itself
mean that payment has been settled, a Review has been submitted, or a Warranty claim has
been resolved.

### Warranty

The documented coverage offered for completed maintenance or repair work.

A Warranty may define a coverage period, applicable work or parts, and relevant
conditions.

Warranty coverage is associated with completed work and must not be interpreted as a
general FixHub platform guarantee.

## Trust

### Review

A customer's assessment of a Provider based on an eligible, verified FixHub transaction.

Reviews contribute to marketplace trust and must be associated with genuine service
activity rather than allowing arbitrary unverified ratings.

The detailed rules governing review eligibility, timing, editing, and moderation belong
to the Trust specification rather than this glossary.

### Review Response

A Provider's response to a customer Review.

A Review Response allows the Provider to provide relevant context without changing or
replacing the customer's Review.

### Rating Aggregate

A derived summary of eligible Review ratings for a Provider or other supported
marketplace subject.

Rating Aggregates are calculated from authoritative Review data rather than being
independent ratings that can be edited directly.

The exact aggregation rules belong to the Trust specification.

### Complaint

A formally recorded customer concern or dispute requiring investigation or moderation.

A Complaint is distinct from a Review. Reviews express customer experience and contribute
to reputation, while Complaints initiate an operational trust or moderation process.

A Complaint may affect other domains, including Payment or Work, but those consequences
remain the responsibility of the relevant domain.

## Payment

### Payment Intent

Represents an attempt or intention to collect payment for an eligible FixHub transaction.

A Payment Intent coordinates the payment lifecycle with an external payment provider but
is not itself the accounting source of truth.

FixHub initially targets KWD payments using KNET through a payment-service provider.
Gateway-specific concepts must remain behind the payment integration boundary.

### Ledger

The append-only accounting record of financial movements within FixHub.

Ledger entries represent financial events such as:

- customer charges
- Provider earnings
- platform commissions
- refunds
- adjustments
- payouts

Recorded Ledger entries are immutable. Corrections must be represented by additional
entries rather than modifying historical financial records.

Financial balances are derived from Ledger entries rather than stored as independently
mutable balances.

### Commission

The amount retained by FixHub from an eligible transaction according to the applicable
commission policy.

The commission policy used for a transaction must be captured at transaction time so
later policy changes do not alter historical financial results.

### Refund

A financial reversal returning some or all of a captured customer payment.

A Refund may be full or partial but must not exceed the amount that remains refundable.

Refunds produce corresponding immutable Ledger entries and must not be implemented by
rewriting the original payment history.

### Earnings

The Provider's financial entitlement resulting from completed eligible transactions,
after applying commissions, refunds, adjustments, and relevant business rules.

Earnings are derived from the Ledger rather than maintained as an independently editable
balance.

The availability of earnings for payout may depend on completion, dispute, warranty, or
other applicable policies.

### Reconciliation

The process of resolving differences or uncertainty between FixHub's recorded payment
state and the external payment provider's authoritative transaction information.

Reconciliation is especially important when payment events are delayed, duplicated,
delivered out of order, or remain in an ambiguous state.

A customer redirect or client-side payment result must not by itself establish successful
payment.

## Communication

### Conversation

A communication context that groups Messages exchanged between authorized FixHub
participants.

A Conversation may be associated with business context such as a Service Request or
Booking, allowing communication to remain traceable to the relevant interaction.

Conversation participation and access must follow the authorization rules of the related
business context.

### Message

A communication item sent by an authorized participant within a Conversation.

A Message contains the communicated content together with information required to identify
its sender and ordering within the Conversation.

Rules for message editing, deletion, retention, attachments, and delivery state belong to
the Communication specification rather than this glossary.

### Notification Outbox

A durable record of notifications that FixHub intends to deliver through supported
notification channels.

The Notification Outbox separates business operations from external notification delivery.
A successful business transaction must not depend on an external email, SMS, push, or
other notification service being immediately available.

Failed notification deliveries may therefore be retried without repeating the originating
business operation.

### Notification Preference

An Account's configurable choices about which optional notifications it wishes to receive
and through which supported channels.

Notification Preferences influence delivery behavior but must not disable mandatory
security, legal, or critical transactional communications where FixHub requires them.

## Administration

### Platform Configuration

Administratively managed business settings that control configurable FixHub behavior.

Platform Configuration may include values such as business policies, limits, or other
operational parameters that should not require source-code changes.

Configuration changes must not silently rewrite the historical meaning of transactions
that were created under earlier business rules.

### Approval Queue

An administrative worklist of Providers or other subjects awaiting a platform approval
decision.

The Approval Queue provides an operational view of approval work. It does not replace the
underlying Provider, Approval, or other authoritative domain records.

### Moderation

The administrative process of investigating and acting on content, behavior, Reviews,
Complaints, Providers, Accounts, or other activity that may violate FixHub policies.

Moderation actions must be authorized and traceable.

Moderation may affect records owned by other domains, but the Administration domain must
not become the source of truth for those records.

### Operational View

A read-oriented administrative representation of FixHub activity used for monitoring,
support, investigation, and platform operations.

An Operational View may combine information from several domains for administrative
purposes.

It is a derived operational perspective rather than an independent source of business
truth and must not take ownership of the underlying domain data.
