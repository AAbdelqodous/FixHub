# Legacy-to-new feature mapping

## Purpose

This document maps legacy FixHub features and implementation concepts to the approved target domain
model. Its purpose is to preserve useful business knowledge without allowing legacy class names,
package boundaries, data relationships, or implementation shortcuts to define the new
architecture.

The legacy applications are evidence sources, not architectural authorities. Accepted ADRs, the
domain glossary, domain map, and conceptual ERD define the target model.

No legacy class is copied into the new implementation merely because it already exists. Every
legacy area receives an explicit decision and rationale before its behavior or data may influence a
later implementation task.

## Sources in scope

| Legacy source | Analysis use | Authority |
|---|---|---|
| `life-experience-app/service-center` | Primary evidence for existing backend entities, services, controllers, repositories, security, and workflows | Reference only |
| `maintenance-center-app` | Evidence for Provider-side workflows, operational screens, and earlier feature specifications | Reference only |
| `maintenance-customer-app` | Evidence for customer journeys, expected API behavior, and client terminology | Reference only |
| Existing legacy data | Potential source for later controlled data migration | Reference only |
| Accepted FixHub ADRs and design documents | Target terminology, ownership, boundaries, and business invariants | Authoritative |

A legacy specification or client assumption does not override an accepted FixHub architecture
decision. Conflicts are resolved in favor of the target model and recorded in this mapping.

## Decision vocabulary

| Decision | Meaning |
|---|---|
| `RETAIN CONCEPT` | The business capability remains valuable, but its implementation is rebuilt inside the target model |
| `REDESIGN` | The capability remains, but its boundaries, invariants, authorization, or lifecycle require material change |
| `SPLIT` | One legacy concept contains responsibilities that belong to multiple target modules |
| `MERGE` | Several legacy concepts represent one target business concept or capability |
| `DEFER` | The capability is intentionally postponed to a later roadmap task or post-MVP phase |
| `RETIRE` | The behavior or model is incompatible, unsafe, duplicated, or no longer required |
| `REFERENCE ONLY` | The artifact may inform scenarios or tests but is not migrated as production design or code |

There is deliberately no general `COPY` decision. Any exceptional code reuse must identify the
exact source, prove that it conforms to current conventions and module boundaries, and receive a
separate review.

## Class reuse policy

- Legacy Java entities, controllers, services, repositories, DTOs, security classes, events, and
  configuration classes are not copied into the new codebase during FH-005.
- A legacy name may be retained only when it matches the canonical glossary meaning.
- Target aggregates and value objects are implemented from approved specifications and invariants,
  not reconstructed mechanically from legacy JPA mappings.
- Legacy endpoints and DTO fields may inform compatibility analysis, but they do not automatically
  become new public API contracts.
- Legacy database tables and columns may inform a later migration plan, but they do not prescribe
  the target schema.
- Legacy tests and client workflows may be converted into target scenarios only after their
  expected behavior is checked against the accepted domain decisions.
- A supporting class follows the decision for its mapped feature family unless this document
  records a specific exception.
- Future implementation work must cite the applicable mapping decision when reusing a legacy
  behavior, migration rule, or business scenario.

## Analysis method

Mapping is performed by business capability and related class family rather than by treating every
request DTO, response DTO, repository, enum, and controller as an independent feature.

Each mapping records:

- The legacy package or representative classes.
- The useful existing behavior.
- The target concept and owning module.
- The migration decision.
- The reason for that decision.
- The later roadmap task or specification that owns implementation detail.

A representative class list establishes traceability for its class family. A class with a
different lifecycle, security boundary, or target owner receives a separate mapping row.

## Structural findings from the legacy backend

| Legacy evidence | Architectural problem | Target rule |
|---|---|---|
| `User` directly owns or references bookings, reviews, centers, conversations, favorites, notifications, searches, and complaints | Identity is coupled to most business modules | Identity owns Account and authentication facts; other modules reference stable Account identifiers |
| `User.userType`, global roles, owned centers, and staff relationships overlap | Account identity and Provider participation are conflated | Provider participation uses Membership and explicitly scoped Provider Role Grants |
| `MaintenanceCenter` represents the only supply-side provider type | Individual professionals cannot use the same discovery and booking model | One Provider model supports `CENTER` and `INDIVIDUAL` types |
| `MaintenanceCenter` contains one address and one operating schedule | Multiple Branches and Branch-specific operations cannot be represented consistently | Every Provider owns at least one Branch; operational location details belong to Branch |
| `CenterResolverService.resolveCenter` selects the first owned or active center | Association order silently chooses an authorization context | Provider and Branch context must be explicit; first/default selection is prohibited |
| `nameAr`, `nameEn`, `descriptionAr`, and `descriptionEn` occur across entities | The schema and contracts are coupled to a fixed locale list | Translatable concepts use parent-owned translation records and normalized locales |
| `Booking` contains scheduling, payment, fulfillment, work-stage, assignment, completion, and cancellation data | One large persistence entity crosses several lifecycle authorities | Booking, Work, Payment, Provider, and related modules own separate concepts and collaborate through contracts |
| Chat provides separate REST and WebSocket write behavior | Authorization and persistence can diverge by transport | Communication has one canonical persisted Message command; real-time transport is deferred |
| Message read state is represented through shared flags and sender-side counters | Multiple Provider participants cannot maintain independent read positions | Read position is tracked independently per Conversation participant |
| Legacy packages import and navigate mutable entities from other business areas | Package placement does not enforce module ownership | Cross-module access uses stable identifiers, snapshots, exposed contracts, and completed events |
| Payment status and cost fields appear directly on Booking while separate payment records also exist | Competing financial authorities can develop | Payment exclusively owns financial state, ledger facts, Commission, Refund, earnings, and Payout |
| Local file paths and unrestricted media URLs appear in several workflows | Storage and access control are not represented as a protected boundary | Business records retain authorized object-storage references and metadata |

These findings justify redesigning the target model rather than renaming or relocating legacy
classes.

## Identity and access mapping

Legacy identity behavior is separated from Provider participation and from business records owned
by other modules.

The [Identity security roadmap](identity-security-roadmap.md) is the approved ownership and
sequencing reference for FH-011 through FH-014. It does not authorize implementation without
approved task specifications and required ADRs.

| Legacy area or representative classes | Useful behavior | Target owner and concept | Decision | Rationale and follow-up |
|---|---|---|---|---|
| `user.User`, `UserService`, `UserController`, and their request/response DTOs | Account profile, contact, status, verification, and preference behavior | Identity — Account | `SPLIT`, `REDESIGN` | Retain identity facts but remove owned centers, bookings, reviews, conversations, favorites, complaints, notification records, and stored business counters. Implement through FH-010 |
| `auth.AuthenticationService`, `AuthenticationController`, registration, login, verification, and password requests | Authentication and account-recovery use cases | Identity — registration, authentication, and recovery | `RETAIN CONCEPT`, `REDESIGN` | FH-011 owns registration and email verification; FH-012 owns authentication and session-token lifecycle; FH-013 owns credential maintenance and recovery. Each task requires an approved specification and must retain normalized identity, non-enumerating responses, throttling decisions, and stable errors within its scope |
| `user.Token` and `TokenRepository` | Expiring verification or recovery token behavior | Identity — purpose-specific security tokens and sessions | `SPLIT`, `REDESIGN` | FH-011 owns email-verification tokens, FH-012 owns access and refresh/session lifecycle, and FH-013 owns recovery tokens. Store only protected token material; every purpose has a distinct lifecycle and storage contract defined by its approved task specification |
| `security.JwtService`, `JwtFilter`, `SecurityConfig`, and `UserDetailsServiceImpl` | Existing authentication flow and protected-route scenarios | Identity/security adapters | `REFERENCE ONLY` | Do not copy the custom JWT implementation. FH-012 owns Spring Security authentication, JWT access tokens, and refresh sessions. FH-014 owns the authorization foundation and integrates Provider permission resolution only through the Provider-owned FH-020 contract |
| `role.Role`, `RoleRepository`, `User.roles`, and `UserType` | Distinction between customer, administrator, owner, and staff behavior | Identity — Global Role; Provider — Membership and Provider Role Grant | `SPLIT`, `REDESIGN` | FH-014 owns global roles and application-level authorization. FH-020 owns `OWNER`, `BRANCH_MANAGER`, `RECEPTIONIST`, `TECHNICIAN`, and `ACCOUNTANT` as Provider participation roles; FH-014 must not duplicate them in Identity |
| `user.Language` and `User.preferredLanguage` | Remembering an Account's preferred presentation language | Identity — preferred normalized locale | `REDESIGN` | Replace fixed language enums with normalized BCP 47 locale identifiers while retaining `ar` and `en` as MVP locales. Apply ADR 0008 and FH-006/FH-010 |
| Notification flags, FCM token, and push token stored on `User` | Per-account communication choices and delivery destinations | Identity — Account reference; Communication — Notification Preference and delivery registration | `SPLIT`, `REDESIGN` | Identity retains the Account and preferred locale; Communication owns notification preferences and delivery state. Detailed channels and device registration are specified later |
| `User.totalBookings`, `totalReviews`, and `helpfulReviews` | Profile statistics | Owning business modules and derived projections | `RETIRE` as Account state | These values must be derived from authoritative Booking and Trust facts or maintained as rebuildable projections, not mutable Account counters |
| `email.EmailService` and `EmailTemplateName` | Email delivery scenarios | Identity use cases through a delivery port; Communication delivery records where applicable | `REDESIGN` | FH-011 owns verification-email delivery and FH-013 owns recovery-email delivery. Each specification must separate business commands, templates, outbox reliability, and provider adapters without preselecting those designs here |

A Customer remains a role played by an Account in a customer interaction. Provider ownership or
employment is never inferred from `UserType`, a global role, or the first related Provider.

## Provider and operational-structure mapping

The target Provider module replaces the legacy assumption that every supply-side participant is one
maintenance center with one location.

| Legacy area or representative classes | Useful behavior | Target owner and concept | Decision | Rationale and follow-up |
|---|---|---|---|---|
| `center.MaintenanceCenter` and center CRUD classes | Supply-side profile, contact, lifecycle, and public presentation | Provider — Provider and Branch | `SPLIT`, `REDESIGN` | Provider holds supply-side identity and type; Branch holds operational location and contact context. Support both `CENTER` and `INDIVIDUAL` through FH-020 |
| `MaintenanceCenter.owner` and `User.ownedCenters` | Provider ownership | Provider — Membership with Provider-scoped `OWNER` grant | `REDESIGN` | Ownership is explicit Provider participation. One Account may own multiple Providers, and an active Provider must retain an owner according to ADR 0007 |
| `CenterResolverService.resolveCenter` and first-result repository queries | Convenience selection of an owned or staffed center | No target equivalent | `RETIRE` | Provider and Branch context must be explicit. Association order or a default Branch cannot grant authority |
| `staff.CenterMembership`, repository, service, and staff DTOs | Staff participation, lifecycle, and center roles | Provider — Membership and Provider Role Grant | `REDESIGN` | Membership belongs to one Account and Provider; grants carry explicit Provider or Branch scope. Implement in FH-020 |
| `StaffInvitation` and invitation controllers | Inviting an Account to join Provider operations | Provider — Membership invitation workflow | `RETAIN CONCEPT`, `REDESIGN` | Preserve invitation scenarios but validate inviter permission, exact Provider, allowed Branch scope, expiry, acceptance, and duplicate membership rules in the later Provider specification |
| `CenterRole`, `CenterPermission`, and `CenterSecurityService` | Named operational roles and capability checks | Provider — role definitions, scoped grants, and authorization policy | `REDESIGN` | Retain useful capability names only after business review. Authorization must check active Membership, requested Provider, exact Branch, and operation through FH-014 and FH-020 |
| `department.Department`, department membership, and diagnostic-department behavior | Staff grouping, specialties, routing, and assignment eligibility | Provider and Work — department/specialty references and Assignment rules | `DEFER` | Departments are not part of the initial Provider aggregate. FH-050 evaluates them without forcing individual Providers to create artificial departments |
| Opening time, closing time, and working days on `MaintenanceCenter` | Basic operating schedule | Provider — Branch Operating Hours and availability rules | `SPLIT`, `REDESIGN` | Hours belong to Branch and must support weekly schedules, split shifts, closures, capacity, horizon, and Kuwait time conventions in FH-025 |
| Embedded legacy address, coordinates, and service-area fields | Provider location and geographic service eligibility | Provider — Branch location; location reference data and Maps adapter | `REDESIGN` | Use authoritative Kuwait governorate/area identifiers, validated coordinates, optional Google place reference, and service radius through FH-022 |
| Logos, image URLs, certifications, and approval documents | Public Provider media and verification evidence | Provider — media metadata, verification documents, and Approval Record | `SPLIT`, `REDESIGN` | Separate public media from private documents and replace local/unrestricted URLs with protected object-storage references in FH-021 and FH-026 |
| `User.approvalStatus`, rejection reason, `MaintenanceCenter.isVerified`, and admin approval methods | Provider onboarding review | Provider — Provider lifecycle and immutable Approval Record | `SPLIT`, `REDESIGN` | Approval belongs to Provider rather than Account identity. Model draft, submission, approval, rejection, correction, suspension, and reactivation in FH-021 |
| Center-category and specialization collections | Description of work a center claims it can perform | Catalog — Branch-specific Provider Offering | `MERGE`, `REDESIGN` | Provider capability is established by validated Offerings, not duplicated category or free-text specialization lists |

An individual Provider receives one canonical Branch-equivalent operating context but does not
receive artificial center departments or staff structures. Center-only operational features must
remain optional unless their business rules also apply meaningfully to individuals.

## Catalog, offerings, and fulfillment mapping

Legacy taxonomy and pricing concepts contain useful starting data, but their relationships and
translation representation require redesign.

| Legacy area or representative classes | Useful behavior | Target owner and concept | Decision | Rationale and follow-up |
|---|---|---|---|---|
| `category.ServiceCategory` and category CRUD classes | Stable category codes, hierarchy, display order, icons, and lifecycle | Catalog — Category and Category Translation | `RETAIN CONCEPT`, `REDESIGN` | Preserve hierarchy and stable codes, remove direct Provider relationships, and replace Arabic/English columns with translation records in FH-023 |
| `service.Service` and service CRUD classes | Stable service codes and lifecycle | Catalog — Service and Service Translation | `RETAIN CONCEPT`, `REDESIGN` | Each Service belongs to its authoritative Category and uses translation records. Implement activation and historical-read rules in FH-023 |
| Legacy many-to-many Category-to-Service links and generic codes such as `REPAIR` | Reusing broad actions across several categories | Catalog — curated category-specific taxonomy | `REDESIGN` | The target model assigns each Service to exactly one Category. Migration must create approved category-specific meanings or otherwise resolve each ambiguous legacy link |
| `service.CenterService` and `pricing.CenterServicePricing` | Provider-specific services, descriptions, price ranges, and duration | Catalog — Provider Offering, Offering Translation, and Pricing Indication | `MERGE`, `REDESIGN` | Replace duplicate center-service models with one Branch-specific Offering. Express `FIXED`, `STARTING_AT`, `HOURLY`, or `QUOTE_REQUIRED` pricing explicitly in FH-024 |
| `fulfillment.FulfillmentCapability` | Supported delivery modes, geographic coverage, and fulfillment fees | Catalog — Offering fulfillment modes; Provider/location boundary — Branch compatibility and service area | `SPLIT`, `REDESIGN` | Fulfillment must be explicit and compatible with the exact Branch and Offering. Geographic rules use FH-022; Offering rules use FH-024 |
| `booking.ServiceType`, category allowed-type collections, and `CategoryAllowedTypesSeeder` | Broad service-action filtering | Catalog taxonomy and Offering eligibility | `MERGE`, `RETIRE` legacy enum coupling | Replace duplicated enum-based selection with stable Category, Service, and Offering identifiers |
| `CategorySeeder` and `ServiceCatalogSeeder` | Candidate catalogue codes and Arabic/English seed content | Catalog — versioned reference data | `REFERENCE ONLY` | Review business scope and translations before migration. Approved reference data is loaded through controlled Flyway migrations in FH-023, not mutable runtime seeders |
| Legacy `RESTAURANT`, `HOTEL`, `BUYING`, `SELLING`, and catch-all catalogue entries | Earlier experimental scope | No automatic target entry | `DEFER` or `RETIRE` | These entries are outside the approved maintenance MVP unless later product evidence and taxonomy review explicitly introduce them |
| `ServiceTypeBackfillRunner` and similar runtime repair runners | Legacy data correction | Later controlled migration scripts | `RETIRE` as runtime behavior | Required transformations belong in reviewed, repeatable Flyway or migration tooling rather than application-startup mutation |
| `offer.CenterOffer` and offer services/controllers | Discounts, validity periods, and redemption limits | Future promotion capability | `DEFER` | Offers, coupons, loyalty, concurrent redemption, refund, and Commission effects belong to post-MVP FH-100 |
| Free-text specialization, service-name, and pricing fields duplicated across center packages | Searchable descriptions of Provider capabilities | Catalog — Provider Offering and translations | `MERGE`, `REDESIGN` | One authoritative Offering prevents conflicting capability, translation, and price representations |

Current legacy catalogue rows are candidate migration input only. Before import, each code,
translation, parent relationship, and active status must be reviewed against the approved Kuwait
maintenance taxonomy.

## Discovery and Marketplace mapping

Legacy discovery and quotation behavior is retained only where it supports the approved Provider,
Offering, Service Request, and Provider Quote model.

| Legacy area or representative classes | Useful behavior | Target owner and concept | Decision | Rationale and follow-up |
|---|---|---|---|---|
| `search.SearchService`, `SearchController`, and search DTOs | Searching by category, location, rating, distance, and verification | Marketplace — Discovery and search projection | `RETAIN CONCEPT`, `REDESIGN` | Search uses approved Providers, Branches, Offerings, localized data, and Trust summaries through module contracts. Deterministic filtering, distance, ranking, and pagination belong to FH-031 |
| `search.SearchHistory` and repository | Recent searches and selected-result information | Marketplace — optional privacy-limited recent discovery record | `DEFER`, `REDESIGN` | FH-032 decides whether history remains client-side or is stored with explicit retention. Raw personal coordinates must not be retained without a justified requirement |
| `favorite.UserFavorite` and favorite CRUD classes | A customer remembers a preferred center | Marketplace — Favorite referencing Account and Provider | `RETAIN CONCEPT`, `REDESIGN` | Replace center reference with Provider, enforce Account-and-Provider uniqueness, make add/remove idempotent, and define suspended Provider behavior in FH-032 |
| `quoterequest.QuoteRequest` and customer request APIs | Customer problem description, category/service hint, fulfillment preference, attachments, expiry, and request status | Marketplace — Service Request | `RETAIN CONCEPT`, `REDESIGN` | Use stable Account, Service, location, and attachment references; validate lifecycle, privacy, ownership, expiry, and idempotency through FH-040 |
| `QuoteRequest.reachCount` and broadcast behavior | Delivering requests to possible centers | Marketplace — match/invitation records and provider quote inbox | `SPLIT`, `REDESIGN` | Requests are not broadcast globally. FH-041 records deterministic matches to eligible Provider Branches and prevents unmatched access or duplicate invitations |
| `quoterequest.QuoteResponse` and center inbox APIs | One center submits commercial terms for a request | Marketplace — Provider Quote and Quote Revision | `RETAIN CONCEPT`, `REDESIGN` | Identify the exact Provider and Branch, preserve versions, calculate KWD totals server-side, enforce validity and status transitions, and hide competitor details through FH-042 |
| Mutable minimum/maximum price fields on `QuoteResponse` | Approximate quote presentation | Marketplace — structured Provider Quote terms | `REDESIGN` | Replace ambiguous ranges with versioned labor, parts, fees, discount, duration, warranty, validity, and server-calculated total semantics |
| `QuoteRequest.acceptedBookingId`, `Booking.originRequestId`, and quote-acceptance service behavior | Converting a selected response into a Booking | Marketplace — Quote Acceptance; Booking — Booking creation | `SPLIT`, `REDESIGN` | FH-044 atomically accepts one valid quote, closes competitors, creates one Booking and snapshot, and makes retries idempotent |
| Provider profile and search response assembly from mutable center entities | Public Provider discovery data | Marketplace projection using Provider, Catalog, Trust, and availability contracts | `REDESIGN` | FH-030 and FH-031 assemble public data without cross-module repository access or exposure of owner, staff, private documents, or restricted contact details |

`BookingQuote` is not mapped to Marketplace Provider Quote. It represents revised commercial terms
after a Booking and diagnosis exist, so it maps to the Work workflow under FH-052.

## Booking mapping

The legacy `Booking` entity contains several valid business facts but combines responsibilities
owned by Booking, Work, Payment, Provider, and Communication.

| Legacy area or representative classes | Useful behavior | Target owner and concept | Decision | Rationale and follow-up |
|---|---|---|---|---|
| `booking.Booking`, repository, service, controller, and DTOs | Customer engagement with Provider, Branch, Service, schedule, and lifecycle | Booking — Booking and Agreed Terms Snapshot | `SPLIT`, `REDESIGN` | Retain only engagement identity, origin, lifecycle, references, and immutable agreed terms. Move work, assignment, payment, and notification concerns to their owners |
| Direct creation fields and booking request APIs | Creating an appointment from a selected service | Booking — Direct Booking origin and Slot reservation | `REDESIGN` | FH-043 requires an active direct-bookable Offering, exact Branch, available Slot, immutable snapshot, idempotency, and atomic capacity reservation |
| Quote-origin fields and accepted-response creation | Creating work from an accepted quotation | Booking — Accepted-Quote Booking origin | `REDESIGN` | Creation occurs only through the atomic Marketplace hand-off in FH-044 and must not produce an accepted quote without exactly one Booking |
| `BookingStatus`, mutable status updates, and `BookingStatusHistory` | Engagement status and historical transition evidence | Booking — controlled lifecycle and append-only transition history | `RETAIN CONCEPT`, `REDESIGN` | FH-045 defines actor-specific transitions, reasons, correlation identifiers, ordering, and stable errors. Work stages remain outside Booking status |
| Booking date/time and estimated end fields without transactional capacity ownership | Appointment scheduling | Booking — Slot and reservation; Provider — availability rules | `REDESIGN` | Availability is calculated from Branch hours and capacity in FH-025, while FH-043 reserves the selected capacity atomically |
| Cancellation timestamp, reason, actor, and related request classes | Recording cancellation | Booking — Cancellation and policy snapshot | `SPLIT`, `REDESIGN` | FH-046 models cancellation, rescheduling, expiry, and no-show explicitly, preserves actor/reason, releases capacity, and requests financial consequences from Payment |
| Customer phone, address, fulfillment fee, and agreed service fields copied into Booking | Preserving transaction-time customer-visible terms | Booking — Agreed Terms Snapshot | `RETAIN CONCEPT`, `REDESIGN` | Snapshot only the facts required for history and fulfillment. Current Account, Provider, or Catalog records remain authoritative for current state |
| `Booking.paymentStatus`, payment method, deposit, estimated cost, final cost, and paid time | Displaying financial progress | Payment-owned facts or Booking snapshot references | `SPLIT` | Booking must not become a competing payment ledger. Payment behavior is mapped separately and linked through stable Booking identifiers |
| `Booking.workStage`, assigned membership, department, diagnostic fee, completion fields, and work media | Execution progress and staff responsibility | Work — Work Record, Assignment, Progress, Diagnosis, and Completion | `SPLIT` | These facts leave the Booking aggregate and are rebuilt through FH-050 through FH-053 |
| `BookingStatsResponse` and booking dashboard counts | Operational summaries | Booking-owned or cross-module read projections | `REFERENCE ONLY` | Rebuild projections from authoritative facts after query requirements are defined; do not store competing counters on Booking or Account |
| `BookingCreatedEvent`, `BookingCancelledEvent`, and `BookingCompletedEvent` | Informing other workflows after transitions | Booking — completed business events | `RETAIN CONCEPT`, `REDESIGN` | Publish immutable facts only after successful state changes. Event schemas, idempotency, and delivery reliability are specified with their consuming workflows |

Booking remains the sole authority for the commercial engagement lifecycle. Work may request valid
Booking transitions, but it does not update Booking persistence directly.

## Work execution mapping

Legacy progress, assignment, reroute, quote-revision, completion, and media behavior becomes an
auditable Work model tied to an eligible Booking.

| Legacy area or representative classes | Useful behavior | Target owner and concept | Decision | Rationale and follow-up |
|---|---|---|---|---|
| `progress.BookingWorkProgress`, `WorkStage`, services, controllers, and DTOs | Stage history, customer notes, internal notes, and estimated remaining time | Work — Work Record and Progress Entry | `RETAIN CONCEPT`, `REDESIGN` | FH-051 creates an ordered append-only timeline, controlled stage transitions, role-filtered notes, concurrency rules, and authenticated actors |
| `progress.BookingMedia` and `BookingMediaService` | Photos and videos associated with execution | Work — Work Media | `REDESIGN` | Store authorized protected-object references and metadata, separate presentation from storage, and validate access against the exact Booking and Work Record |
| Assigned membership, department, `BookingClaimAudit`, and assignment APIs | Manual assignment and technician self-claim | Work — Assignment; Provider — Membership eligibility | `SPLIT`, `REDESIGN` | FH-050 validates active Membership, Provider, Branch, specialty/department, and Booking state; preserves reassignment history; and handles concurrent claims |
| `reroute.RerouteAudit`, reroute service, reasons, and DTOs | Append-only diagnostic and operational rerouting evidence | Work — Diagnosis, routing decision, and Assignment history | `RETAIN CONCEPT`, `REDESIGN` | FH-051 permits reroute only through explicit compatible workflow rules and records actor, reason, source, destination, and time |
| `quote.BookingQuote`, `QuoteLineItem`, version, totals, and customer response | Revised price after inspection or diagnosis | Work — revised repair estimate and customer approval | `RETAIN CONCEPT`, `REDESIGN` | FH-052 preserves every version, calculates totals server-side, requires explicit idempotent customer approval, and blocks protected work until approval |
| Inventory identifiers and ad-hoc part fields embedded in `QuoteLineItem` | Linking estimated parts to repair work | Future inventory capability and Work estimate snapshot | `SPLIT`, `DEFER` | Approved estimate lines preserve commercial facts. Stock reservation and consumption belong to post-MVP FH-101 and must not be activated by legacy entity callbacks |
| Completion notes, completion images, and completed time stored on `Booking` | Evidence that service work finished | Work — Completion and handover evidence | `SPLIT`, `REDESIGN` | FH-053 defines completion eligibility, evidence, customer handover, Booking transition request, and immutable completion history |
| Legacy warranty service type or free-text warranty indication | Limited warranty presentation | Work — Warranty | `REDESIGN` | FH-053 introduces an explicit Warranty record with covered work, duration, terms, start/end facts, Provider, Branch, and historical evidence |
| Direct mutable references from Work records to Booking, User, Membership, and Department entities | Convenient object navigation | Stable identifiers and exposed authorization contracts | `RETIRE` | Work owns its records but validates Booking and Provider eligibility through module boundaries instead of navigating foreign mutable aggregates |

An individual Provider is the implicit assignee where appropriate and must not be forced through
center-only department or staff setup.

## Trust mapping

Legacy review, reputation, badge, and complaint behavior moves into one Trust boundary while
transaction eligibility remains owned by Booking, Work, and Payment.

| Legacy area or representative classes | Useful behavior | Target owner and concept | Decision | Rationale and follow-up |
|---|---|---|---|---|
| `review.Review`, repository, service, controller, and DTOs | Ratings, comments, rating dimensions, recommendation, media, and moderation | Trust — Review | `RETAIN CONCEPT`, `REDESIGN` | FH-054 permits one Review for the exact eligible completed Booking and customer, rather than one Review per Account and center |
| Optional Booking link and mutable `isVerified` flag | Marking reviews as transaction-verified | Trust eligibility validated through Booking and Work contracts | `REDESIGN` | Verification is derived from an eligible completed transaction and cannot be asserted by a client or represented only by a mutable boolean |
| `Review.centerResponse` and response timestamp | Provider reply to a customer Review | Trust — Review Response | `SPLIT`, `REDESIGN` | Model one authorized response with its own actor, lifecycle, moderation, and edit policy through FH-054 |
| `MaintenanceCenter.averageRating`, `totalReviews`, and review statistics DTOs | Public reputation summaries | Trust — Rating Aggregate | `SPLIT`, `REDESIGN` | Trust calculates or reliably projects count, average, dimensions, and distribution from eligible visible Reviews; Provider does not own mutable rating totals |
| Review approval, flag fields, media, and helpful counters | Moderation and customer interaction | Trust — Review moderation state and protected media references | `REDESIGN` | Preserve audit history, validate media authorization, and define any helpful-vote feature separately rather than trusting mutable counters |
| `trust.CenterTrustBadge` and badge calculation services | Displaying evidence-backed Provider trust indicators | Trust — derived trust indicators or future badge policy | `DEFER`, `REDESIGN` | A badge must have documented eligibility, source facts, revocation, and display rules. It cannot be granted merely through a mutable center record |
| `complaint.Complaint`, repository, service, controller, and DTOs | Complaint description, evidence, priority, status, resolution, and Provider context | Trust — Complaint | `RETAIN CONCEPT`, `REDESIGN` | FH-055 links the Complaint to an authorized Booking, Review, Payment, or other approved context and preserves submission evidence and immutable history |
| Complaint admin notes, resolution fields, and direct status mutation | Investigation and dispute handling | Trust — controlled Complaint workflow; Administration — authorized case/action | `SPLIT`, `REDESIGN` | Separate customer-visible updates from internal notes, validate actor-specific transitions, audit every action, and request financial review through Payment contracts |

Trust never changes Booking, Work, Provider, or Payment records directly. It verifies eligibility
or requests an authorized action through the owning module.

## Payment mapping

The legacy payment package contains useful scenarios but does not provide the append-only,
reconcilable accounting model required for real KNET-capable operation.

| Legacy area or representative classes | Useful behavior | Target owner and concept | Decision | Rationale and follow-up |
|---|---|---|---|---|
| `payment.Payment`, repository, services, controllers, and views | Payment attempt, Booking reference, amount, method, gateway reference, status, and idempotency key | Payment — Payment Intent and financial records | `SPLIT`, `REDESIGN` | FH-060 separates payment attempt state from immutable Ledger Entries, Commission, Provider Earning, Refund, and policy snapshots |
| Gross, wallet, commission, net, refunded, captured, and released fields mutated on one `Payment` row | Financial calculation and settlement progress | Payment — immutable Ledger Entries and transaction snapshots | `REDESIGN` | Every financial movement requires a unique reference, KWD scale 3, reason, source, time, and balancing invariant. Corrections use compensating entries |
| `Wallet` and mutable `Wallet.balance` | Customer stored-value balance | No approved MVP aggregate | `DEFER` | A customer wallet requires separate regulatory, safeguarding, expiry, refund, and accounting review. A mutable balance cannot be introduced as financial truth |
| `WalletTransaction` | Signed customer wallet movement history | Payment — possible future ledger input | `REFERENCE ONLY` | The record is not a complete balanced platform ledger. FH-060 designs the authoritative ledger independently |
| `DepositConfig`, deposit amount, and cancellation policy | Provider deposit and cancellation behavior | Payment and Booking — effective-dated policy and transaction-time snapshot | `RETAIN CONCEPT`, `REDESIGN` | FH-060 defines policy snapshots; FH-043/FH-046 preserve the applicable terms and request resulting financial actions without recalculation |
| `PaymentGateway`, `StubPaymentGateway`, `MockMyFatoorahGateway`, and gateway mock controller | External gateway abstraction and simulated payment outcomes | Payment — provider-neutral port and deterministic test adapter | `RETAIN CONCEPT`, `REDESIGN` | FH-061 rebuilds the port and mock scenarios. Test controllers and legacy provider-specific assumptions are not copied |
| MyFatoorah-specific names or behavior | Earlier external-provider experiment | Payment adapter only after provider selection | `REFERENCE ONLY` | FH-062 keeps the KNET-capable provider as an explicit external decision and prevents its data model from entering the domain |
| Payment listeners reacting to Booking creation, completion, or cancellation | Cross-module financial reactions | Payment — idempotent command/event consumers | `REDESIGN` | Consumers validate unique business references and create ledger effects once. Booking events never authorize blind status mutation |
| Refund amounts and direct payment-status changes | Full or partial refund scenarios | Payment — Refund and immutable ledger consequences | `REDESIGN` | FH-063 enforces eligibility, captured refundable limits, webhook idempotency, reconciliation, and compensating entries |
| `Payout`, `PayoutAccount`, payout services, and center payment views | Provider settlement destination, request, status, failure, and history | Payment — Provider Earning, Payout, and Reconciliation Record | `RETAIN CONCEPT`, `REDESIGN` | FH-064 derives available amounts from the ledger, protects destination data, prevents overpayment, and preserves failure/reversal history |
| Saved payment methods and provider tokens | Customer payment convenience | External provider token references if later approved | `DEFER`, `REDESIGN` | FixHub must not store card or bank credentials. Any future saved method uses provider-approved opaque tokens and explicit privacy rules |

The Payment module is the only authority for monetary state and balances. Booking, Trust,
Administration, and Provider may request or display authorized financial information but cannot
mutate it directly.

## Communication mapping

Legacy chat and notifications provide useful user scenarios, but authorization, ordering,
idempotency, translation, and delivery reliability require redesign.

| Legacy area or representative classes | Useful behavior | Target owner and concept | Decision | Rationale and follow-up |
|---|---|---|---|---|
| `chat.Conversation`, repository, response DTOs, and conversation creation | Durable customer-to-center message history | Communication — Conversation, participants, and Business Context Reference | `RETAIN CONCEPT`, `REDESIGN` | FH-070 requires an approved Service Request, Booking, Work, Complaint, or other context and exact Account, Provider, and Branch authorization |
| Direct customer and center relationships on `Conversation` | Identifying the two communication sides | Communication — Conversation Participant references | `REDESIGN` | Provider-side access is evaluated per active Membership and Branch grant; the model must support multiple independently reading Provider members |
| `chat.Message`, repository, DTOs, and REST send operations | Persisted sender, content, type, timestamp, and attachment metadata | Communication — Message and Message Attachment Reference | `RETAIN CONCEPT`, `REDESIGN` | Add server sequence, client-generated idempotency identifier, stable cursor ordering, represented participant side, validated content, and protected attachments |
| Shared `isRead`, `readAt`, and conversation unread counters | Basic unread behavior | Communication — Participant Read Position | `RETIRE`, `REDESIGN` | FH-070 maintains an independent cursor per participant so one Provider member cannot mark messages read for every other member |
| Message edit/delete fields and original content | Correcting or hiding messages | Communication — append-only Message and auditable moderation state | `REDESIGN` | MVP participants cannot rewrite or physically delete transaction evidence. Moderation hides content while retaining actor, reason, time, and original record |
| `ChatService.sendMessage` and `sendMessageViaWebSocket` | REST and real-time message delivery | Communication — one canonical send command | `MERGE`, `REDESIGN` | FH-070 uses persisted HTTP messaging and polling first. Any later transport invokes the same command and persistence path |
| `WebSocketConfig`, `WebSocketAuthInterceptor`, `ChatWebSocketController`, topics, and subscriptions | Real-time delivery experiment | Future Communication transport adapter | `DEFER` | Do not copy into MVP. WebSocket/SSE, reconnect recovery, typing, and presence belong to post-MVP FH-103 |
| Message media URLs and filenames | Message attachments | Communication — protected Message Attachment Reference | `REDESIGN` | Store generated object references and validated metadata, not local paths or unrestricted public URLs |
| `notification.Notification`, repository, service, controller, and delivery flags | In-app notification, email/push status, scheduling, references, and read state | Communication — Notification Outbox and delivery attempts | `SPLIT`, `REDESIGN` | FH-071 persists reliable outbox work, idempotent delivery keys, retries, dead-letter visibility, and separate presentation/read projections |
| `titleAr`, `titleEn`, `bodyAr`, and `bodyEn` stored as canonical notification content | Bilingual rendered notifications | Communication — translation key and structured parameters | `REDESIGN` | Follow ADR 0008: persist stable key and parameters; render for the effective locale without making one rendered language the sole durable record |
| Notification flags on both `User` and `Notification` | Channel preferences | Communication — Notification Preference | `MERGE`, `REDESIGN` | Maintain one Account preference set, distinguish mandatory notices, and keep delivery destinations and attempts separate |

Persistence of the Message or originating business transaction succeeds independently of email,
push, or future real-time delivery.

## Administration and operational-view mapping

Administration coordinates authorized platform operations but does not become the owner of every
record visible to an administrator.

| Legacy area or representative classes | Useful behavior | Target owner and concept | Decision | Rationale and follow-up |
|---|---|---|---|---|
| `admin.AdminController`, `AdminService`, and admin DTOs | Provider approval, account operations, complaint handling, catalogue changes, and operational listing | Administration — authorized case/action; target business modules — authoritative commands | `SPLIT`, `REDESIGN` | FH-072/FH-073 expose bounded admin operations while Identity, Provider, Trust, Catalog, and Payment validate and perform their own changes |
| Direct injection of User, center, Booking, Complaint, Review, and Category repositories into `AdminService` | Convenient cross-domain administration | No target equivalent | `RETIRE` | Administration must call exposed module contracts and must not mutate another module's repository |
| Owner approval and rejection performed on `User.approvalStatus` | Provider onboarding decision | Provider — Approval Record and Provider lifecycle | `SPLIT`, `REDESIGN` | FH-021 and FH-072 operate on the Provider, require reason and actor, retain audit history, and preserve Account identity separately |
| Direct center enable/disable and user status changes | Suspension and reactivation | Owning module lifecycle commands plus Administrative Action | `REDESIGN` | The target module enforces state transitions; Administration records case, actor, reason, correlation identifier, and outcome |
| Complaint status and priority mutations from admin services | Complaint operations | Trust — Complaint workflow; Administration — case/action | `SPLIT`, `REDESIGN` | Trust owns status and invariants. Administration supplies authorized action context without taking ownership |
| Category CRUD implemented inside `AdminService` | Catalogue maintenance | Catalog — administrative application boundary | `SPLIT`, `REDESIGN` | Catalog validates codes, translations, hierarchy, activation, and historical-use rules; Administration supplies platform authorization |
| `AdminAnalyticsService`, `analytics` package, dashboard and statistics DTOs | Platform and Provider operational summaries | Administration or owning-module read projections | `DEFER`, `REDESIGN` | FH-074 adds minimal controlled metrics; advanced funnels, revenue, trends, and staff performance wait for post-MVP FH-102 |
| Approval, complaint, notification-failure, and payment-exception lists | Operational work queues | Administration — projections referencing owner-module records | `RETAIN CONCEPT`, `REDESIGN` | Queues expose ownership, status, age, and permitted actions but are not competing source aggregates |
| Generic `lookup.Lookup` and mutable shared lookup administration | Reusable reference values | Owning business modules or controlled Platform Configuration | `SPLIT`, `RETIRE` generic ownership | Category, status, location, and other authoritative values stay with their owner. Only genuinely platform-wide configuration belongs to Administration |
| `AdminSeeder` and runtime default administrator creation | Local bootstrap convenience | Identity/bootstrap process | `RETIRE` | Production must not create predictable credentials or depend on runtime seeders. Bootstrap is explicit, environment-controlled, auditable, and documented |
| Administrative access to private messages or sensitive records by global role alone | Support visibility | Administration — authorized case and audited access | `REDESIGN` | ADR 0009 requires a moderation, complaint, or investigation case, recorded reason, least privilege, and no participant impersonation |

Administrative queues, dashboards, and summaries are projections. They never transfer lifecycle or
mutation authority from the module that owns the underlying business record.

## Cross-cutting and infrastructure mapping

Legacy technical helpers may inform conventions, but they do not become shared domain ownership or
bypass the modular boundaries.

| Legacy area or representative classes | Useful behavior | Target owner or convention | Decision | Rationale and follow-up |
|---|---|---|---|---|
| `common.PageResponse` | Common paginated response shape | FH-006 API pagination conventions | `REFERENCE ONLY`, `REDESIGN` | Define one documented cursor or page contract, bounds, metadata, and stable serialization before implementing module endpoints |
| `handler.BusinessErrorCodes`, business exceptions, exception responses, and duplicate global handlers | Central API error handling | Common error catalogue and web exception boundary | `MERGE`, `REDESIGN` | FH-006 defines stable domain error codes, HTTP mappings, correlation identifiers, validation errors, and one authoritative exception handler |
| `config.WebMvcConfig` and `LocalTimeDeserializer` | HTTP and time parsing customization | FH-006 API, locale, and time conventions | `REFERENCE ONLY` | Rebuild only after defining ISO formats, `Asia/Kuwait`, offset/instant rules, validation, and consistent serialization |
| `address.Address`, `fulfillment.SavedAddress`, `booking.ServiceAddress`, and free-text address fields | Reusing location data across profiles and transactions | Provider, Marketplace, Booking, and Kuwait location contracts | `SPLIT`, `REDESIGN` | Current address, service location, saved customer address, and immutable transaction snapshot have different owners and privacy rules; they are not one shared mutable entity |
| `config.FileStorageService` and `StorageProperties` | Local upload storage and generated file references | Technical object-storage adapter behind owning-module ports | `REDESIGN` | Provider, Marketplace, Work, Trust, and Communication own their media references. Infrastructure supplies local and S3-compatible adapters without owning business metadata |
| `config.BeansConfig` and package-level framework configuration | Framework wiring | Module-local or platform infrastructure configuration | `REFERENCE ONLY` | Recreate only required beans with explicit ownership, validated properties, and test substitutes; do not copy incidental legacy wiring |
| Runtime seeders and backfill runners | Initial and corrective data loading | Flyway migrations, versioned reference data, or non-production fixtures | `RETIRE` as production startup behavior | Production startup must not silently mutate business data. Each migration is explicit, ordered, repeatable where appropriate, reviewed, and tested |
| Entity listeners that perform cross-module work | Reacting to Booking or payment changes | Completed business events and idempotent consumers | `REDESIGN` | Persistence callbacks must not hide cross-module business operations. Publish facts after commit and make consumers independently retryable |
| Direct `LocalDateTime.now()` and implicit server-default time use | Recording action times | Platform clock and time conventions | `REDESIGN` | Time-sensitive rules use an injected clock and explicit Kuwait/UTC conventions so expiry, payment, booking, and tests are deterministic |
| Broad JPA cascades and bidirectional relationships across feature packages | Convenient persistence navigation | Module-owned aggregates and stable cross-module identifiers | `RETIRE` | Cascade and orphan rules stop at aggregate/module boundaries. Cross-module deletion or mutation is never inferred from object navigation |
| Lombok-generated mutable entities exposed through controllers | Reduced boilerplate | Explicit domain behavior and public DTO contracts | `RETIRE` as design pattern | New aggregates protect invariants through behavior; persistence entities are not serialized as public responses and sensitive fields are excluded from logs |

Technical reuse is allowed only after the target convention exists and a reviewer confirms that the
exact code is generic, secure, tested, and independent of legacy domain assumptions.

## Deferred and retired capability register

| Legacy capability | Decision | Re-entry condition or roadmap owner |
|---|---|---|
| `inventory` package, parts catalogue, stock movements, and cancellation listener | `DEFER` | Post-MVP FH-101 defines branch stock ledger, concurrency, reservation, consumption, reversal, and audit |
| `offer` package, promotions, redemption counters, and discounts | `DEFER` | Post-MVP FH-100 defines funding, eligibility, stacking, limits, refund, Commission, and concurrent redemption |
| Advanced analytics, revenue trends, staff performance, and dashboard pipelines | `DEFER` | Minimal operations metrics begin in FH-074; validated advanced requirements belong to FH-102 |
| WebSocket chat, topics, presence, typing, and real-time delivery | `DEFER` | FH-103 may add transport after persisted messaging, cursor recovery, and authorization are proven |
| Customer wallet and stored-value balance | `DEFER` | Requires an explicit product, regulatory, safeguarding, accounting, refund, expiry, and security decision |
| Server-side personal search history | `DEFER` | FH-032 must justify business value, fields, privacy, retention, deletion, and whether client-side history is sufficient |
| Provider trust badges | `DEFER` | A later Trust specification must define evidence, calculation, display, expiry, revocation, and auditability |
| Center departments and diagnostic routing | `DEFER` from Provider foundation | FH-050/FH-051 introduce them only where meaningful and preserve a simple path for individual Providers |
| Generic shared lookup tables | `RETIRE` | Each authoritative value belongs to its business module; only genuinely platform-wide effective-dated settings become Platform Configuration |
| First/default center resolution | `RETIRE` | No re-entry: every Provider and Branch operation requires explicit trusted context |
| Runtime default administrator credentials | `RETIRE` | No production re-entry: bootstrap must be explicit, secret-managed, auditable, and environment-controlled |
| Restaurant, hotel, buying, selling, and unrestricted catch-all catalogue scope | `RETIRE` from MVP | Requires a future product-scope and taxonomy decision |
| Legacy MyFatoorah coupling | `RETIRE` as domain assumption | A selected KNET-capable provider may receive an adapter after the FH-062 ADR and commercial review |

A deferred capability cannot enter an MVP implementation indirectly through a copied entity,
listener, DTO field, client mock, database column, or hidden dependency.

## Data migration rules

FH-005 does not execute a production data migration. A later migration specification must apply
these rules:

- Profile source tables and data quality before defining target migrations.
- Maintain an explicit legacy-to-target identifier map for every split or merged concept.
- Do not copy a legacy row directly when one source entity maps to several target modules.
- Normalize and validate email, Kuwait phone, locale, currency, coordinates, and timestamps before
  acceptance.
- Convert each valid legacy maintenance center into a `CENTER` Provider, at least one Branch, and
  explicit owner Membership. Do not infer individual Providers from incomplete center data.
- Convert `nameAr`, `nameEn`, and similar fields into normalized `ar` and `en` translation records.
  Incomplete required translations prevent public activation.
- Convert center services and pricing only after the target Category, Service, Branch, Offering,
  fulfillment, and price semantics are resolved.
- Preserve legacy Booking and quote identifiers as migration references while generating target
  identities and explicit origin links.
- Reconstruct historical snapshots from available evidence and mark uncertainty; never silently
  invent accepted terms.
- Quarantine invalid lifecycle combinations, orphan relationships, duplicate memberships,
  ambiguous category-service links, and mismatched Provider/Branch records for review.
- Reconcile all financial amounts and references before creating any opening ledger facts. A
  mutable status or balance is not sufficient accounting evidence.
- Do not migrate active password, verification, recovery, refresh, or device tokens without a
  specific security review. Force invalidation or reset when compatibility cannot be proven.
- Inventory media references, validate ownership and availability, and import approved objects
  through the protected storage boundary.
- Document the assumed timezone for legacy timestamps that contain no offset.
- Perform repeatable dry runs with source counts, target counts, rejection counts, reconciliation,
  and deterministic verification queries.
- Keep real customer data, credentials, tokens, documents, and payment payloads out of the
  repository, fixtures, logs, screenshots, and portfolio demonstration.
- Require backup, rollback, audit evidence, and business sign-off before a production cutover.

## Legacy package coverage

The primary legacy backend contains 34 top-level feature packages. Each is covered by a mapping
decision in this document.

| Legacy packages | Mapping destination |
|---|---|
| `auth`, `role`, `security`, `user`, `email` | Identity and access; email delivery also crosses to Communication |
| `address`, `center`, `department`, `staff` | Provider, location, authorization, and Work assignment |
| `category`, `fulfillment`, `offer`, `pricing`, `service` | Catalog, Provider Offering, fulfillment, or explicit deferral |
| `favorite`, `quoterequest`, `search` | Discovery and Marketplace |
| `booking` | Booking, with embedded concerns split to their authoritative owners |
| `progress`, `quote`, `reroute` | Work execution and revised repair estimates |
| `complaint`, `review`, `trust` | Trust |
| `payment` | Payment |
| `chat`, `notification` | Communication |
| `admin`, `analytics`, `lookup` | Administration, owner-module contracts, projections, or retirement |
| `inventory` | Explicit post-MVP deferral |
| `common`, `config`, `handler` | Cross-cutting conventions and infrastructure adapters |

Request/response DTOs, repositories, enums, services, controllers, events, listeners, configuration,
and tests inside these packages inherit their feature-family decision unless a separate row above
states otherwise.

## Implementation reuse gate

Before any legacy artifact influences production implementation, the responsible later task must
confirm:

- Its business capability is still approved for that roadmap phase.
- Its terminology matches the domain glossary.
- Its target concept has exactly one owning module.
- Its authorization uses exact Account, Provider, Branch, and context identifiers where applicable.
- Its persistence does not introduce cross-module aggregate ownership.
- Its API follows FH-006 rather than legacy endpoint or DTO conventions.
- Its translation, time, money, privacy, storage, and error behavior follows the accepted decisions.
- Its tests describe approved behavior rather than preserving a legacy bug or shortcut.
- Its mapping decision and rationale remain accurate.

If any check fails, the artifact is redesigned or rejected. Silence is never permission to copy it.

This mapping completes architectural traceability from the legacy feature families to the target
modular model. Detailed schemas, application services, endpoints, migrations, and tests remain the
responsibility of their later roadmap specifications.
