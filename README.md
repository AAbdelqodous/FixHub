# FixHub

[![CI](https://github.com/AAbdelqodous/FixHub/actions/workflows/ci.yaml/badge.svg)](https://github.com/AAbdelqodous/FixHub/actions/workflows/ci.yaml)

FixHub is a modular Spring Boot backend for a maintenance-service marketplace. It is designed to connect customers, service providers, branches, and technicians across the full service journey—from discovery and quotation to booking, work execution, payment, reviews, and communication.

The repository contains the completed engineering foundation—module boundaries, database and migration infrastructure, quality gates, API conventions, structured error responses, JPA auditing, and request correlation—plus the first Identity persistence capability for Accounts and Credentials.

## Current status

**Phase 0 — Foundation and conventions: complete**

Implemented foundation:

- Java 21 and Spring Boot application baseline
- Modular-monolith architecture with Spring Modulith verification
- PostgreSQL, Flyway, and Testcontainers integration
- Shared JPA auditing primitives
- RFC 9457 `ProblemDetail` error responses
- Stable common and module-owned error-code contracts
- Spring MVC framework-exception mappings
- Validation error entries for request bodies and method parameters
- `X-Correlation-ID` validation, generation, response propagation, and MDC lifecycle management
- Maven Enforcer, Spotless, JaCoCo, and GitHub Actions quality gates
- Architecture decisions, domain model documentation, API conventions, and error catalogue

**FH-010 — Account and credential persistence: complete**

Implemented Identity persistence:

- Account persistence
- `PASSWORD` Credential persistence
- Closed Identity module
- PostgreSQL schema migrations V2 and V3, verified through Testcontainers

Registration, authentication, password hashing configuration, verification, recovery, global roles, sessions, and tokens are not implemented yet.

## Target domain modules

FixHub is organized as a modular monolith. Each business domain owns its data, behavior, invariants, and lifecycle.

| Module | Responsibility |
|---|---|
| Identity | Accounts, credentials, verification, sessions, and global roles |
| Provider | Providers, branches, memberships, provider roles, approvals, and operating hours |
| Catalog | Categories, services, translations, provider offerings, and pricing indications |
| Marketplace | Discovery, favorites, service requests, matching, quotes, revisions, and quote acceptance |
| Booking | Direct and accepted-quote bookings, slots, lifecycle transitions, and cancellation |
| Work | Assignment, diagnosis, progress, evidence, completion, and warranty |
| Trust | Verified reviews, responses, rating aggregates, and complaints |
| Payment | Payment intents, ledger entries, commissions, refunds, earnings, and reconciliation |
| Communication | Conversations, messages, notification outbox, and preferences |
| Administration | Platform configuration, approval worklists, moderation, and operational views |

The `common` module is a deliberately small shared kernel for cross-cutting technical concerns. Business modules remain closed by default and collaborate through explicit APIs or domain events.

## Technology stack

| Area | Technology |
|---|---|
| Language | Java 21 |
| Application framework | Spring Boot 4.1 |
| Modular architecture | Spring Modulith 2.1 |
| Persistence | Spring Data JPA and PostgreSQL |
| Database migrations | Flyway |
| Security foundation | Spring Security |
| API documentation | SpringDoc OpenAPI |
| Validation | Jakarta Bean Validation |
| Testing | JUnit 5, Spring Boot Test, MockMvc, and Testcontainers |
| Code quality | Maven Enforcer, Spotless, and JaCoCo |
| CI | GitHub Actions |

## Getting started

### Prerequisites

- JDK 21
- Docker with Docker Compose
- Git

The Maven Wrapper is included, so a separate Maven installation is not required.

### Clone the repository

```bash
git clone https://github.com/AAbdelqodous/FixHub.git
cd FixHub
```

### Start local infrastructure

Copy the environment template and start PostgreSQL and MailDev.

Windows PowerShell:

```powershell
Copy-Item .env.example .env
docker compose up -d
```

Linux or macOS:

```bash
cp .env.example .env
docker compose up -d
```

The Compose stack starts:

| Service | Default address |
|---|---|
| PostgreSQL | `localhost:5432` |
| MailDev SMTP | `localhost:1025` |
| MailDev web UI | `http://localhost:1080` |

The local Spring profile provides development defaults matching `.env`. Docker Compose reads `.env`; if you change application-facing values, export the corresponding environment variables before starting the application.

### Run the application

Windows PowerShell:

```powershell
$env:SPRING_PROFILES_ACTIVE = "local"
.\mvnw.cmd spring-boot:run
```

Linux or macOS:

```bash
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
```

Useful local endpoints:

| Endpoint | Purpose |
|---|---|
| `http://localhost:8080/api/v1/ping` | Basic API ping endpoint |
| `http://localhost:8080/swagger-ui/index.html` | OpenAPI UI |
| `http://localhost:1080` | MailDev inbox |

Spring Security currently uses its default development configuration. Protected endpoints require the generated development credentials printed at startup; production authentication belongs to the upcoming Identity module.

### Stop local infrastructure

```bash
docker compose down
```

Use `docker compose down -v` only when you intentionally want to remove the local PostgreSQL data volume.

## Verification

Run the complete local quality gate before opening a pull request.

Windows PowerShell:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress verify
```

Linux or macOS:

```bash
./mvnw --batch-mode --no-transfer-progress verify
```

The verification lifecycle checks:

- Java, Maven, and dependency-convergence requirements
- Java formatting and import rules
- Unit, MVC, integration, and module-boundary tests
- Flyway migrations against PostgreSQL through Testcontainers
- JaCoCo coverage thresholds of 85% instruction coverage and 80% line coverage

Apply Java formatting with:

```bash
./mvnw --batch-mode --no-transfer-progress spotless:apply
```

On Windows, replace `./mvnw` with `.\mvnw.cmd`.

## API error contract

FixHub exposes errors as `application/problem+json` using Spring's `ProblemDetail` model. Public error codes are explicit and stable; they are not derived from Java enum names.

Example validation response:

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Validation failed",
  "instance": "/api/v1/example",
  "code": "VALIDATION_ERROR",
  "correlationId": "4f777b53-c64d-47e7-9795-332bfb13417c",
  "errors": [
    {
      "field": "name",
      "message": "must not be blank"
    }
  ]
}
```

Clients may supply `X-Correlation-ID` using 1–64 ASCII letters, digits, dots, underscores, or hyphens. FixHub generates a UUID when the header is missing or invalid and returns the resolved identifier in both the response header and error body.

## Repository structure

```text
.
├── .github/workflows/       # Continuous integration
├── docs/
│   ├── adr/                 # Architecture decision records
│   ├── design/              # Domain and API design references
│   └── specs/               # Implementation specifications
├── src/main/java/com/fixhub/platform/
│   ├── common/              # Shared technical kernel
│   └── identity/            # Closed module: Account and Credential persistence
├── src/main/resources/
│   └── db/migration/        # Flyway migrations
├── src/test/                # Unit, MVC, integration, and architecture tests
├── docker-compose.yaml      # PostgreSQL and MailDev
└── pom.xml                  # Build and quality-gate configuration
```

## Architecture and design documentation

- [Domain glossary](docs/design/domain-glossary.md)
- [Domain map](docs/design/domain-map.md)
- [Entity relationship design](docs/design/erd.md)
- [API conventions](docs/design/api-conventions.md)
- [Error catalogue](docs/design/error-catalogue.md)
- [Legacy-to-new mapping](docs/design/legacy-to-new-mapping.md)
- [Common module specification](docs/specs/002-common-module.md)
- [API and error conventions specification](docs/specs/006-api-error-conventions.md)
- [Account and credential persistence specification](docs/specs/010-account-credential-persistence.md)
- [API error contract and ownership ADR](docs/adr/0010-api-error-contract-and-ownership.md)

## Development workflow

1. Branch from the latest `main`.
2. Keep changes scoped to one specification or review objective.
3. Run `verify` locally.
4. Use a Conventional Commit message.
5. Open a pull request and wait for all required checks to pass.

The repository's commit hook accepts messages in the form:

```text
type: description
```

Scoped Conventional Commits such as `feat(identity): add account persistence` are also valid.
