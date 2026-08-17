# ADR 0008: Translation records and keys

- Status: Accepted
- Date: 2026-08-10

## Context

FixHub launches in Kuwait with complete Arabic and English experiences. Categories, Services,
Provider Offerings, and authoritative Kuwait location names contain business content that must be
available in both MVP languages.

Additional locales are deferred until after the MVP translation workflow is stable, but the domain
model must allow them to be introduced without adding language-specific columns or redesigning
public contracts.

Columns such as `nameAr`, `nameEn`, and one future column for every supported language couple the
schema to the current locale list. They also duplicate validation and query logic and create
increasing numbers of nullable columns as language support expands.

Not all localized text has the same lifecycle:

- Domain content, such as Category and Service names, is stored and maintained by its owning
  business module.
- Application labels and validation messages belong to client or server resource bundles.
- Public API errors expose stable codes that clients translate.
- Notifications use stable translation keys and parameters.
- User-authored content, such as messages and reviews, remains in its original language unless a
  separate translation feature is explicitly introduced.

Translations must not become a shared mutable store that transfers ownership away from the domain
concept being translated. Historical bookings, quotes, and other transaction records must also
remain understandable when current translations are edited later.

## Decision

FixHub uses normalized locale identifiers and separate translation records for translatable domain
content.

Arabic and English are the required MVP locales, represented by the normalized language tags `ar`
and `en`. Locale identifiers follow BCP 47 conventions so future languages or regional variants
can be introduced consistently.

A translatable domain concept consists of:

- A language-independent parent with a stable identity and stable business code where applicable.
- One child translation record for each available locale.
- Localized fields appropriate to that concept, such as name, description, or display label.
- A uniqueness rule allowing at most one translation per parent and normalized locale.

Language-specific columns such as `nameAr`, `nameEn`, or `descriptionEn` are prohibited for
translatable domain content. Adding a future locale requires new translation data, not a schema
column.

The business module that owns the translated concept also owns its translation records and
lifecycle. In the current module map, Catalog owns Category, Service, and Provider Offering
translations. Authoritative Kuwait location translations remain with the module that owns that
reference data. The `common` module must not become a global translation repository.

A translation is maintained through the parent concept's application boundary. Other modules may
consume localized DTOs or stable identifiers through published contracts, but they must not read
or modify another module's translation records directly.

Content intended for active public MVP use must have complete Arabic and English translations.
Missing either required baseline translation prevents activation or publication of that content.
Fallback behavior is a resilience mechanism and must not hide incomplete baseline data.

After API conventions determine the effective requested locale, localized content is resolved in
this order:

1. An exact normalized locale match.
2. The base language when a regional locale was requested.
3. The configured platform-default locale.
4. No localized value when none of the above exists.

The resolver must not select an arbitrary available translation.

When no localized value exists after applying the fallback order:

- A required localized field causes the read operation to fail with a stable
  missing-translation error.
- An optional localized field is omitted or returned as null according to its documented public
  contract.

The exact error code and HTTP mapping belong to the FH-006 common error catalogue. Missing a
required Arabic or English translation for active public content is a data-integrity violation and
must be operationally visible. Machine-readable contracts retain their stable code or identifier,
but that code must not be presented as a translated display value.

API errors continue to expose a stable domain error code rather than database-backed translated
messages. Client-facing labels use resource keys. Notifications persist a translation key and
structured parameters as their canonical content and must not rely on rendered text in one
language as the sole durable record.

Edits to current translation records do not rewrite historical business facts. Later transaction
specifications must define the appropriate stable identifiers and snapshots needed for quotes,
bookings, payments, warranties, and other historical views.

This decision defines the domain translation strategy. It does not prescribe database table names,
JPA mappings, caching technology, locale transport headers, or client framework implementation.

## Alternatives considered

Use language-specific columns such as `nameAr` and `nameEn` — rejected. Every additional locale
would require schema migrations, entity and DTO changes, and repeated validation logic. Nullable
language columns would continue growing across every translated concept.

Store all translations as a JSON or JSONB value on the parent record — rejected as the default
domain model. Although flexible, it makes locale uniqueness, required-language validation,
administrative maintenance, indexing, and relational integrity less explicit. A later
implementation may use JSON only for a separately reviewed use case.

Create one global Translation aggregate or shared translation table in `common` — rejected.
Translations follow the lifecycle and authorization rules of their owning business concept. A
global mutable translation store would weaken module ownership and encourage cross-module data
access.

Persist only already-rendered localized text for API errors and notifications — rejected.
Rendered text loses the stable code, translation key, and structured parameters needed for
retries, later delivery in another locale, auditing, and client-specific presentation.

Translate missing content automatically at read time through a machine-translation provider —
rejected as canonical behavior. Availability, cost, quality, and non-deterministic output would
make business content unreliable. Machine translation may later assist an authorized translation
workflow, but approved translation records remain authoritative.

Require only one MVP language and rely on fallback for the other — rejected. Complete Arabic and
English experiences are an explicit MVP requirement, and fallback must not conceal incomplete
baseline content.

## Consequences

- Translatable domain concepts require separate child records and a parent-and-locale uniqueness
  rule.
- Publication and activation workflows must validate required Arabic and English content.
- Adding another locale creates translation data and configuration, not new language columns.
- Locale resolution is deterministic and never selects an arbitrary available language.
- Missing required localized content produces an operationally visible failure; optional fields
  follow their documented nullable or omission behavior.
- Translation records remain inside the owning module and are changed only through that module's
  application boundary.
- Queries may require additional joins, indexes, projections, and caching, but those performance
  choices belong to later implementation tasks.
- Stable business codes and identifiers remain language-independent and available for integrations,
  analytics, and diagnostics.
- API errors and notifications retain codes, keys, and structured parameters independently of
  rendered text.
- User-authored messages and reviews preserve their original language unless a future feature
  explicitly adds translated variants.
- Historical transaction snapshots are not rewritten when current translations change.
- Tests must cover locale normalization, parent-and-locale uniqueness, baseline-language
  completeness, regional-to-base fallback, platform-default fallback, missing required content,
  and optional-field behavior.
- Legacy `nameAr`, `nameEn`, and similar columns must be mapped into translation records during
  migration rather than copied into the new domain model.
- The effective-locale source, request headers, account-preference precedence, exact error code,
  and HTTP mapping remain defined by FH-006 API and error conventions.
