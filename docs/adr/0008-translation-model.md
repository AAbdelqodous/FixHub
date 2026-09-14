# ADR 0008: Translation records and keys

- Status: Accepted
- Date: 2026-08-10

## Amendment — 2026-09-13

This amendment expands ADR 0008 from the original domain-translation model into FixHub's
platform-wide localization policy. It supersedes the original statements that limit mandatory
locales and translation completeness to Arabic and English, defer all additional locales, or defer
effective-locale precedence to FH-006. The rest of the original decision remains in force,
including normalized translation records, module ownership, stable machine-readable codes and
identifiers, deterministic fallback, original-language user content, and historical-data rules.

The ADR remains **Accepted**. FH-011 and every later module specification consume this platform
policy and may define implementation details only within its boundaries.

### Platform launch locales and configuration

FixHub's platform-wide launch locales are:

| Language | Locale | Direction |
| -------- | ------ | --------- |
| Arabic   | `ar`   | RTL       |
| English  | `en`   | LTR       |
| Hindi    | `hi`   | LTR       |
| Urdu     | `ur`   | RTL       |
| Bengali  | `bn`   | LTR       |

The platform configuration contract is:

```properties
fixhub.localization.supported-locales=ar,en,hi,ur,bn
fixhub.localization.default-locale=en
```

These five languages apply to every platform-owned user-facing capability as that capability is
implemented, including:

- Web and mobile UI.
- Forms and user-facing validation.
- Transactional email.
- Push and SMS notifications.
- Platform-managed categories and descriptions.
- User-facing operational messages.

The policy does not require localization of:

- Stable API error codes.
- Database identifiers or enum values.
- Logs, metrics, or developer diagnostics.
- Source code or technical documentation.
- User-generated reviews, descriptions, or chat messages.

### Architectural ownership

A shared localization foundation owns these cross-cutting capabilities:

- The supported-locale registry.
- BCP 47 parsing and canonicalization.
- Deterministic fallback.
- Text-direction lookup.
- Localization configuration validation.
- Translation-completeness validation.

The foundation supplies consistent policy and primitives; it is not a central repository for all
translated business text. Business translations remain owned by their respective Spring Modulith
modules and follow those modules' lifecycles and application boundaries. In particular:

- Common owns shared technical messages.
- Identity owns registration and authentication messages.
- Booking owns booking messages.
- Payments owns payment messages.
- The frontend owns interface navigation and page text.

Other modules follow the same ownership rule for their business vocabulary. No global bundle may
collect every module's business messages. Domain exceptions and APIs expose stable error codes,
and application behavior must never branch on, compare, or otherwise depend on translated text.

### Locale resolution

Locale input is parsed as BCP 47, canonicalized, and matched only against the supported-locale
registry. For any candidate locale, resolution tries an exact supported match first, then a
supported base-language match, then English. It never selects an arbitrary installed translation.
The configured platform default, `en`, is the only terminal fallback. Localization must never fall
back to the JVM default locale, operating-system locale, server locale, container locale, or a
framework default locale. Implementation must disable system-locale fallback. Spring localization
configuration must enforce the equivalent of:

```properties
spring.messages.fallback-to-system-locale=false
```

For authenticated requests, precedence is:

1. The canonical supported value resolved from `Account.preferredLocale`.
2. English fallback.

For registration and explicit anonymous operations, precedence is:

1. An explicit, validated locale submitted by the user.
2. An explicit, previously selected UI locale.
3. A supported `Accept-Language` match.
4. English fallback.

A higher-precedence explicit preference is authoritative: after it is syntactically validated, it
is resolved by exact match, base-language match, then English rather than replaced with a
lower-precedence inferred preference. Invalid explicit tags fail validation. Valid but unsupported
BCP 47 values permitted by FH-010 remain valid in `Account.preferredLocale` and resolve to English;
they are not rewritten to pretend that English was the stored preference.

Although FH-010 normally guarantees a syntactically valid, normalized stored tag, localization
must treat persisted data as potentially corrupted. A malformed `Account.preferredLocale` must not
be used for resource or template lookup. It resolves safely to English and records only the bounded
sanitized reason code `CORRUPTED_PERSISTED_VALUE`. The malformed value must not appear in logs,
metrics, traces, exception messages, or requester-visible responses. The user-facing operation
must not fail solely because the persisted localization value is malformed. A syntactically valid
but unsupported stored tag also resolves to English, but is not necessarily a data-integrity
failure.

Language must never be inferred from nationality, IP address, location, email address, or phone
number. `Accept-Language` is an untrusted presentation preference, not evidence of identity or
location. It cannot influence authentication, authorization, validation rules, Account-existence
handling, rate limiting, token validation, or any other domain behavior. Changes to a persisted
preference require the normal authentication, authorization, and CSRF protections applicable to
that operation.

Resolution examples are:

| Input                | Result             |
| -------------------- | ------------------ |
| `ar-KW`              | `ar`               |
| `en-GB`              | `en`               |
| `hi-IN`              | `hi`               |
| `ur-PK`              | `ur`               |
| `bn-BD`              | `bn`               |
| `ml-IN`              | `en`               |
| Invalid explicit tag | Validation failure |
| Missing preference   | `en`               |

#### `Accept-Language` handling

The following algorithm applies when `Accept-Language` is reached under the precedence above:

1. A missing or blank header resolves to English.
2. Parse the header as an ordered list of language ranges and quality weights.
3. If any range or quality parameter makes the header syntactically malformed, ignore the complete
   header and resolve to English. Do not partially use any remaining range.
4. Do not return `400` or `406` because of a missing, malformed, or unsupported
   `Accept-Language` value.
5. Exclude ranges with `q=0` from matching.
6. Process the remaining ranges by descending quality weight.
7. Preserve original header order when quality weights are equal.
8. For each range, try an exact supported locale and then its supported base language.
9. The first supported match wins.
10. A wildcard or exhaustion without a supported match resolves to English.
11. Duplicate occurrences of the same canonical range are treated as one candidate at the
    position and quality weight of its first occurrence; later duplicates are ignored and cannot
    change the result.
12. Parsing, ordering, canonicalization, case handling, and locale matching must not depend on JVM
    default-locale behavior.

A quality value follows the RFC 9110 quality-value grammar: it is between `0` and `1`, inclusive,
and has no more than three digits after the decimal point. Valid examples include `0`, `0.5`,
`0.125`, `1`, `1.0`, and `1.000`. Negative values, values greater than `1`, nonnumeric values,
more than three fractional digits, missing values, and duplicate `q` parameters on one range are
invalid. Any invalid range or quality value makes the complete effective `Accept-Language` field
malformed. The complete header is ignored, resolution returns English without `400` or `406`, and
only the bounded reason code `MALFORMED` may be recorded if an operational indicator is needed.

Examples are:

| `Accept-Language`              | Result |
| ------------------------------ | ------ |
| Missing                        | `en`   |
| `ar-KW`                        | `ar`   |
| `ur-PK, en;q=0.8`              | `ur`   |
| `ml-IN, hi-IN;q=0.9, en;q=0.5` | `hi`   |
| `bn-BD;q=0.8, ar-KW;q=0.8`     | `bn`   |
| `hi;q=0, en;q=0.5`             | `en`   |
| `*`                            | `en`   |
| Unsupported ranges only        | `en`   |
| Malformed header               | `en`   |

Representative quality-value examples are:

| `Accept-Language`      | Result                                        |
| ---------------------- | --------------------------------------------- |
| `ar;q=1.0, en;q=0.8`   | `ar`                                          |
| `hi;q=0.125`           | `hi`                                          |
| `ur;q=1.001, en;q=0.8` | `en` because the complete header is malformed |
| `bn;q=abc, ar`         | `en` because the complete header is malformed |
| `ar;q=-1`              | `en` because the complete header is malformed |
| `ar;q=`                | `en` because the complete header is malformed |

#### Locale alias policy

Launch resolution performs BCP 47 syntactic validation and normalizes ASCII case according to the
tag structure. It does not expand, substitute, or accept deprecated, legacy, grandfathered, or
implementation-specific aliases. JVM, CLDR, framework, locale-library, and operating-system alias
mappings must not be used to reach a supported locale. Only a documented exact supported tag or
its explicit primary-language fallback may select a supported locale. Three-letter language
identifiers must not be treated as aliases for the approved two-letter values. Any future alias
requires explicit addition to this platform policy and deterministic tests.

Examples are:

| Input                                   | Result                                                                      |
| --------------------------------------- | --------------------------------------------------------------------------- |
| `AR-kw`                                 | `ar`                                                                        |
| `en-GB`                                 | `en`                                                                        |
| `eng`                                   | `en` fallback only because it is unsupported—not because it aliases to `en` |
| Library-specific alias                  | `en` fallback                                                               |
| Deprecated tag not explicitly approved | `en` fallback                                                               |

### Security requirements

1. Locale input is untrusted and must be strictly parsed, canonicalized, and resolved through the
   supported-locale allowlist.
2. Raw locale input must never construct resource names, template paths, or filesystem paths.
3. Localization must never change authentication, authorization, rate limiting, token validation,
   password policy, HTTP status, or stable error codes.
4. Enumeration-resistant responses must remain equivalent in every language.
5. Translated HTML and dynamic template values require contextual escaping.
6. Arabic and Urdu require RTL rendering. Dynamic email addresses, URLs, identifiers, and other
   LTR values require bidirectional isolation when embedded in RTL content.
7. Logs remain language-neutral and structured. Raw or unvalidated locale request fields, complete
   or partial `Accept-Language` values, malformed values, and unvalidated unsupported values must
   never be reflected in error responses, `ProblemDetail`, diagnostic response fields, response
   headers, logs, distributed traces or tracing attributes, metrics or metric labels, or exception
   messages. They must not be echoed in a success response; the sole response-body exception is the
   authenticated persisted-Account representation defined below. Correlation identifiers may
   remain in logs but must not be introduced as metric labels.
8. Only the resolved allowlisted values `ar`, `en`, `hi`, `ur`, and `bn` may drive resource or
   template selection, `Content-Language`, localization-related telemetry, or rendering behavior.
   Bounded sanitized reason codes `MISSING`, `MALFORMED`, `UNSUPPORTED`, and
   `CORRUPTED_PERSISTED_VALUE` remain permitted as non-locale diagnostic categories.
9. Responses varying by `Accept-Language` must use appropriate cache controls, including
   `Vary: Accept-Language`.
10. Translation files must never contain real passwords, tokens, credentials, or other
    production-like secrets.
11. Security-sensitive translations require qualified human review. The reviewer must be competent
    in both the target language and the security meaning, or a target-language reviewer must be
    paired with a security reviewer. Automated translation or automated key validation alone is
    insufficient. Review must verify that a translation does not reveal Account existence, change
    required security instructions, weaken warnings, alter token or password meaning, introduce
    unsafe HTML, or change the semantics of stable error codes.
12. Asynchronous jobs must carry a canonical resolved locale explicitly and must not depend on
    thread-local request-locale state.

#### Persisted Account preference representation

The stored preference and effective rendering locale are separate values:

```text
preferredLocale = canonical persisted user preference
resolvedLocale  = one of ar, en, hi, ur, bn used for rendering
```

A canonical, syntactically validated, persisted `Account.preferredLocale` may be returned even when
it is not one of the five rendering locales, but only when all of these conditions hold:

1. An approved API specification explicitly includes `preferredLocale`.
2. The response is authenticated and properly authorized to access that Account data.
3. The returned value comes from the validated persisted Account field, not directly from raw
   request input.
4. It is returned as Account profile data, not as an error, diagnostic, header, telemetry value, or
   rendering decision.
5. Resource and template selection still use the separately resolved allowlisted locale.
6. Existing privacy, authorization, and response-contract rules are satisfied.

For example:

```text
Stored preferredLocale: ml-IN
Resolved rendering locale: en
Authorized Account response may expose preferredLocale: ml-IN
Template/resource selection uses: en
```

This exception does not permit echoing raw input; returning raw `Accept-Language`; exposing the
value in `ProblemDetail`, another error or diagnostic, or a generic registration response; using an
unsupported value as a resource or template name; logging or tracing the persisted unsupported
value; or adding arbitrary locale values to metrics.

### Translation quality gates

Automated validation is mandatory for each implemented platform-owned user-facing capability and
must cover:

- Key parity across all five mandatory languages.
- Required template presence.
- UTF-8 correctness.
- Placeholder parity.
- Missing and unknown keys.
- Correct `lang` and `dir` metadata.
- Deterministic English fallback.
- Safe HTML rendering.
- Secret-safe fixtures and assertions.

A missing mandatory translation must fail the appropriate build or startup validation rather than
silently displaying a translation key. Module-local validation may enforce additional
domain-specific quality rules but may not weaken these platform gates.

### Localized domain content

Future platform-managed multilingual content uses normalized translation records keyed by entity
and locale. It must not add one database column per language. The specification for the first
module that needs localized persisted content will define the exact tables, constraints, and
migrations while retaining ownership in that module.

User-generated content remains in its original language. Automatic translation is outside this
decision.

### Deferred expansion

Additional language packs for the wider Middle East, South Africa, other GCC communities, or any
other audience require measured demand, identified translation ownership, and explicit approval.
They are not launch requirements.

### Superseded text and documentation follow-up

The original Context, Decision, Alternatives considered, and Consequences sections below are
retained as decision history. Where they say that only Arabic and English are required, that
additional locales are deferred, or that FH-006 determines effective-locale precedence, this
dated amendment governs instead.

Documentation alignment status:

- API conventions were aligned with ADR 0008 on 2026-09-14.
- FH-006 was aligned with ADR 0008 on 2026-09-14.
- The legacy mapping was aligned with ADR 0008 on 2026-09-14.
- The ERD was aligned with ADR 0008 on 2026-09-14.
- The proposed `docs/specs/011-registration-and-email-verification.md` remains pending and must be
  reconciled with this five-language platform policy before approval.

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
