# ADR 0009: Persisted simple messaging first

- Status: Accepted
- Date: 2026-08-10

## Context

FixHub requires customers and Providers to communicate during Service Request, quotation, Booking,
and Work lifecycles. Messages may later support complaints or other explicitly approved business
contexts.

Messaging contains transaction evidence and potentially sensitive information. It must therefore
provide durable history, participant authorization, stable ordering, idempotent sending,
moderation controls, and predictable unread counts.

The legacy application models a Conversation directly between one customer and one maintenance
center. That assumption does not support FixHub's unified `CENTER` and `INDIVIDUAL` Provider
model, multiple Branches, or branch-scoped Membership authorization.

The legacy application also provides separate REST and WebSocket message-writing paths. Those
paths perform different authorization and persistence behavior and can evolve inconsistently.
Real-time transport must not create an alternative message model or bypass the canonical send
operation.

FixHub's MVP does not require WebSockets, typing indicators, presence, or real-time delivery
infrastructure. Persisted HTTP-based messaging with polling or refresh is sufficient for the first
release, provided its ordering and cursor model allow real-time transport to be added later without
changing message ownership or history.

Conversation eligibility belongs to the business context that created the need to communicate.
Communication may reference a Service Request, Booking, Work Record, or other approved context,
but it must not change that context's lifecycle or infer access from an unrelated association.

Administrative access requires special care. Administrators are not ordinary Conversation
participants and must not receive unrestricted access to private messages merely because they have
a global administrative role.

## Decision

FixHub implements persisted simple messaging inside the Communication module before adding any
real-time transport.

The Communication module owns:

- Conversation identity and lifecycle.
- Message identity, content, sender facts, and ordering.
- Conversation participants or participant references.
- Participant read positions and unread-count projections.
- Message-attachment references and moderation state.
- Messaging persistence, queries, and delivery contracts.

The business module referenced by a Conversation remains the authority for its Service Request,
Booking, Work Record, Complaint, or other business context. Communication stores only the stable
context type, stable context identifier, and the minimum facts required for messaging. It must use
an exposed contract from the owning module when context eligibility or lifecycle validation is
required.

### Conversation context

Every MVP Conversation must have an explicit approved business context. General unsolicited
customer-to-Provider chat is not supported.

A Conversation identifies the relevant customer Account, Provider, and Branch where the business
context is branch-specific. It must not select an Account's first Provider, a Provider's first
Branch, or a default Branch as an authorization fallback.

The applicable business rules determine whether one Conversation is created for a Service Request,
Booking, or another approved context. A uniqueness rule prevents duplicate active Conversations
for the same conversation type and business context where only one is allowed.

Creating, reading, or sending within a Conversation requires authorization against the exact
business context:

- The customer side must be the Account authorized for that context.
- The Provider side must have an active Membership in the exact Provider.
- A branch-specific Conversation additionally requires a permitted role grant covering the exact
  Branch.
- A Membership in another Provider or Branch never grants access.
- Participant and context identifiers supplied by a client are never trusted without validation.

Removing or suspending a participant's current permission prevents new unauthorized operations but
does not erase previously persisted Messages or their sender history.

### Canonical message write path

A Message is persisted successfully before it is exposed through polling, notifications, or a
future real-time transport. Persistence is the source of truth; transport delivery is not.

REST is the initial command and query transport. Polling or explicit refresh retrieves newly
persisted Messages.

A future WebSocket or Server-Sent Events adapter must invoke the same canonical application command
used by REST. It must not maintain a separate send implementation, authorization path, Message
repository, identifier, or ordering model.

Each accepted Message receives:

- A stable server-assigned Message identifier.
- A server-assigned sequence within its Conversation.
- A server timestamp using the platform time conventions.
- The authenticated sender Account identifier and represented participant side.
- A client-generated message identifier used for retry idempotency.
- Its validated content type, content, and permitted attachment references.

The client-generated message identifier is unique for the sender within the Conversation. Repeating
the same send command returns the original accepted result and must not create another Message.

Messages are ordered by their server-assigned Conversation sequence, not by client time. Message
history uses cursor-based pagination built from this stable order so polling and a future real-time
transport converge on the same persisted sequence.

### Message history and read state

MVP Messages are append-only transaction records. Users cannot rewrite previously sent content or
physically delete Message history. Moderation may hide content from ordinary presentation while
retaining the original record, reason, actor, and timestamp for authorized audit.

Unread state is maintained per participant through a read position or equivalent cursor. It is not
represented only by one mutable `isRead` flag on each Message because multiple Provider members may
read the same Conversation independently.

An unread count is derived from Messages after the participant's acknowledged read position.
Updating one participant's read position must not mark Messages as read for another participant.

Attachments are stored through the approved object-storage boundary. Messages contain authorized
attachment references and metadata rather than local filesystem paths or unrestricted public URLs.
Allowed types, sizes, scanning, access, and retention are defined by later messaging and upload
specifications.

### Privacy, blocking, and administration

Messaging authorization is evaluated for every read and write operation. Knowing a Conversation
identifier or subscribing to a future transport destination never grants access.

Contact details, private documents, and other restricted information must not be exposed through
Conversation summaries or Message metadata before the owning workflow permits that exposure.

When the owning workflow prohibits direct contact exchange, the canonical send command applies the
applicable context-specific content and attachment policy before accepting a Message. It must not
enrich Conversation or Message responses with protected profile fields. Prohibited contact details,
private data, or attachments are rejected according to the documented policy rather than persisted
and then exposed. Exact validation and moderation techniques belong to later specifications.

A Conversation may be closed or archived when its owning business context no longer permits new
Messages. Closing or archiving prevents new participant Messages but preserves authorized access
to the existing history. Communication must not reopen a Conversation unless the owning business
module confirms that messaging is allowed again.

Conversation and Message history is retained according to an explicit platform retention policy;
it is not removed merely because a Conversation was closed, a Membership ended, or an Account was
blocked. An active complaint, investigation, audit requirement, or applicable legal hold prevents
purging. Any eventual purge must be authorized, auditable, and must not silently remove required
business evidence.

A blocked, suspended, or otherwise restricted Account cannot send new Messages when the applicable
Identity or business policy denies it. Existing history remains retained and readable only under
the applicable authorization and retention rules.

An administrator may access a Conversation only through an authorized moderation, complaint, or
investigation case. The access reason and administrator identity must be audited. Administrative
access does not make the administrator an ordinary participant and does not permit impersonating a
customer or Provider member.

Notification delivery is secondary to Message persistence. Failure to deliver an email, push
notification, or future real-time signal must not roll back or duplicate the persisted Message.
Notification reliability is defined separately by the notification-outbox work.

This decision does not prescribe database tables, JPA mappings, endpoint URIs, polling intervals,
retention periods, attachment limits, or WebSocket/SSE configuration.

## Alternatives considered

Implement WebSocket chat as part of the MVP — rejected. Real-time infrastructure would add
connection authentication, subscription authorization, reconnect handling, delivery recovery,
scaling, and operational complexity before the persisted messaging contract is proven.

Reuse the legacy customer-to-maintenance-center Conversation model — rejected. It assumes one
center, does not support individual Providers or multiple Branches, and cannot enforce the exact
Provider and Branch Membership rules established by ADR 0007.

Allow general customer-to-Provider conversations without a business context — rejected. This would
weaken participant authorization, enable unsolicited contact, and make privacy, retention, and
moderation rules difficult to connect to a legitimate platform transaction.

Maintain separate REST and WebSocket message-writing implementations — rejected. Separate paths
can apply different authorization, idempotency, validation, or persistence behavior and produce
inconsistent Message history.

Order Messages only by creation timestamp and use offset pagination — rejected. Concurrent writes
may share timestamps, and inserts can shift offset pages. A server-assigned Conversation sequence
and cursor provide stable ordering for polling and future real-time recovery.

Represent read state with one `isRead` flag per Message or one unread counter per customer and
Provider — rejected. Multiple Provider members can read independently, so shared flags or counters
cannot represent each participant's position correctly.

Allow users to edit or physically delete sent Messages — rejected for the MVP. Messaging may
contain transaction, complaint, or dispute evidence. Moderation and authorized retention actions
must preserve auditability rather than silently rewriting history.

Use a third-party chat platform as the source of truth — rejected. FixHub would lose authoritative
message history, ordering, idempotency, authorization control, and reliable linkage to its business
contexts. External delivery technology may later be used only behind an approved adapter.

## Consequences

- The Communication module becomes the authoritative owner of Conversations, Messages, participant
  read positions, attachment references, and moderation state.
- Service Request, Booking, Work, Complaint, and other source modules remain authoritative for
  context lifecycle and messaging eligibility.
- MVP clients send and retrieve persisted Messages through HTTP and polling or explicit refresh.
- Every read and write operation validates the exact customer, Provider, Branch, Membership,
  permission, and business context that apply.
- Message sending has one canonical application path regardless of whether REST, WebSocket, SSE, or
  another transport invokes it.
- Client-generated message identifiers make retries idempotent and prevent duplicate Messages.
- Server-assigned Conversation sequences provide stable cursor pagination and future reconnect
  recovery.
- Implementing sequence allocation safely under concurrent sends requires an explicit transactional
  and locking strategy in the later messaging specification.
- Per-participant read positions support accurate unread counts for customers and multiple Provider
  members but require more state than one shared Message flag.
- Messages remain append-only; moderation hides content through auditable state rather than
  rewriting or physically deleting ordinary history.
- Conversation creation and sending require additional calls or contracts with the owning business
  module and Provider authorization boundary.
- Context-specific content policy can reject prohibited contact information, private data, or
  attachments before persistence.
- Closed or archived Conversations retain authorized history, while reopening depends on renewed
  permission from the owning business module.
- Retention, complaint holds, investigations, and authorized purge operations require explicit
  policies and audit records.
- Attachments require protected object-storage references, authorization checks, validation, and
  later malware-scanning rules.
- Notification or future real-time delivery failure does not invalidate or duplicate a successfully
  persisted Message.
- Polling is operationally simpler for the MVP but may create additional read load; indexes,
  projections, caching, and polling intervals are decided from measured implementation needs.
- A future real-time transport can be introduced without migrating Message identity, history,
  ordering, authorization, or idempotency semantics.
- Tests must cover participant isolation, cross-Provider and cross-Branch denial, blocked Accounts,
  duplicate client message identifiers, concurrent ordering, cursor pagination, independent read
  positions, context closure, moderation, retention holds, and audited administrative access.
- Legacy chat entities and WebSocket code may inform scenarios, but they are not copied into the new
  domain without conforming to this decision.
