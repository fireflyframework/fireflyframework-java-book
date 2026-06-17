# Lumen Lending

A trimmed three-tier lending reactor on the **Firefly Framework**, mirroring the
`firefly-oss` loan-origination vertical. It runs end to end with **no Docker, no
external database, and no message broker**: persistence is in-memory **H2**, and
events flow over the **in-JVM `APPLICATION_EVENT`** transport.

## The three tiers

| Module | Tier | Port | Owns |
| ------ | ---- | ---- | ---- |
| `core-lending-loan-origination` | Core (system of record) | **8081** | Reactive REST + R2DBC persistence of loan applications; Flyway migrations; RFC 7807 error handling. Built on `fireflyframework-starter-core`. |
| `domain-lending-loan-origination` | Domain (orchestration) | **8082** | CQRS commands/queries, a saga (`RegisterApplicationSaga`) and EDA events that compose the core into a business flow. Built on `fireflyframework-starter-domain`. |
| `exp-lending` | Experience (BFF) | **8080** | Client-facing API that aggregates the domain service; method-level `@Secure`. Built on `fireflyframework-starter-application`. |

The dependency direction is **exp → domain → core**. Each tier talks to the next
through an SDK *seam* (a reactive client interface) so the tiers stay decoupled.

## Build and test

```bash
mvn clean verify
```

- **33 tests**, all green: core **18**, domain **6**, exp **9**.
- No Docker and no external services: tests use in-memory **H2** (R2DBC for
  runtime access, JDBC for Flyway) and the in-JVM **`APPLICATION_EVENT`** EDA
  transport. Each module's `src/test/resources/application.yml` carries the test
  profile; `src/main/resources/application.yml` carries the runnable profile.

## Run each tier

Each module is an independent Spring Boot app. Run with the Maven plugin from the
module directory:

```bash
# Core — system of record, H2 + Flyway, serves on 8081
( cd core-lending-loan-origination && mvn spring-boot:run )

# Domain — orchestration, in-JVM EDA, serves on 8082
( cd domain-lending-loan-origination && mvn spring-boot:run )

# Experience (BFF) — serves on 8080, security enforcement disabled for local use
( cd exp-lending && mvn spring-boot:run )
```

### Full local stack

Start all three (each in its own terminal, or background them) — order does not
matter, the tiers do not fail-fast on a missing downstream:

```bash
( cd core-lending-loan-origination   && mvn spring-boot:run ) &
( cd domain-lending-loan-origination && mvn spring-boot:run ) &
( cd exp-lending                     && mvn spring-boot:run ) &
```

Each app logs `Started …Application in …` and `Netty started on port …` when
ready. Health is exposed on every tier:

```bash
curl -s localhost:8081/actuator/health   # core  -> {"status":"UP",...}
curl -s localhost:8082/actuator/health   # domain-> {"status":"UP",...}
curl -s localhost:8080/actuator/health   # exp   -> {"status":"UP",...}
```

Clean up when done:

```bash
lsof -ti:8080,8081,8082 | xargs kill
```

## Verified requests

All of the following were captured against the running stack.

### Core (8081) — the system of record serves real CRUD

Health:

```bash
curl -s localhost:8081/actuator/health
# {"status":"UP",...,"r2dbc":{"status":"UP","details":{"database":"H2",...}},"eda":{"status":"UP",...}}
```

Create a loan application (**201 Created**, returns the submitted DTO):

```bash
curl -s -X POST localhost:8081/api/v1/loan-applications \
  -H 'Content-Type: application/json' \
  -d '{"applicantId":"11111111-1111-1111-1111-111111111111","requestedAmount":250000.00,"currency":"EUR","termMonths":60,"purpose":"Home improvement"}'
# 201:
# {"loanApplicationId":"16d94afc-...","applicationNumber":"fb049375-...","applicantId":"11111111-...",
#  "requestedAmount":250000.00,"currency":"EUR","termMonths":60,"purpose":"Home improvement",
#  "status":"SUBMITTED","decisionReason":null,"createdAt":"...","updatedAt":"..."}
```

Fetch it back (**200 OK**):

```bash
curl -s localhost:8081/api/v1/loan-applications/<loanApplicationId>
# 200: same DTO as above
```

Validation failure — negative amount (**400**, `application/problem+json`):

```bash
curl -s -X POST localhost:8081/api/v1/loan-applications \
  -H 'Content-Type: application/json' \
  -d '{"applicantId":"11111111-1111-1111-1111-111111111111","requestedAmount":-5,"currency":"EUR","termMonths":60,"purpose":"Bad"}'
# 400 application/problem+json:
# {"type":"https://api.firefly.com/errors/validation_error","title":"Validation Failed","status":400,
#  "detail":"Invalid request parameters","extensions":{"code":"VALIDATION_ERROR",
#  "errors":[{"field":"requestedAmount","code":"ValidAmount",
#  "message":"Requested amount must be a positive monetary value","metadata":{"rejectedValue":"-5"}}],...}}
```

Unknown id (**404**, `application/problem+json`):

```bash
curl -s localhost:8081/api/v1/loan-applications/00000000-0000-0000-0000-000000000000
# 404 application/problem+json:
# {"type":"about:blank","title":"Not Found","status":404,
#  "detail":"Loan application not found: 00000000-0000-0000-0000-000000000000",
#  "extensions":{"category":"RESOURCE","retryable":false,...}}
```

### Experience BFF (8080) — reaches the domain over HTTP

```bash
curl -s -X POST localhost:8080/api/v1/experience/lending/applications \
  -H 'Content-Type: application/json' \
  -d '{"productId":"22222222-2222-2222-2222-222222222222","requestedAmount":150000.00,"term":48,"purpose":"Vehicle purchase"}'
# 502 application/problem+json:
# {"type":"https://api.firefly.com/errors/upstream_error","title":"Bad Gateway","status":502,
#  "detail":"domain loan-origination call failed: 404 Not Found from POST http://localhost:8082/api/v1/applications",...}
```

This proves the **exp → domain** seam is wired and live: the BFF builds a
`WebClient` from `lumen.exp.loan-origination.base-path`, calls the domain at
`http://localhost:8082/api/v1/applications`, and faithfully maps the upstream
response into an RFC 7807 problem detail. In this book sample the **domain tier
intentionally exposes no REST controller** — it is saga/CQRS/event-driven and is
driven from tests, not over HTTP — so the call returns `404` upstream and the BFF
surfaces it as `502`. The core's own HTTP API (above) is the fully-served REST
surface in this reactor.

## Notes on the runnable wiring

These changes make the apps boot and serve while keeping all 33 tests green
(tests use their own `src/test/resources/application.yml`):

- **`src/main/resources/application.yml`** in each module: server port, plus the
  H2/R2DBC/Flyway keys (core), the CQRS/orchestration/EDA + `APPLICATION_EVENT`
  switches (domain), and `lumen.exp.loan-origination.base-path` +
  `firefly.application.security.enabled=false` (exp).
- **core `pom.xml`**: the H2 R2DBC and JDBC drivers were moved from `test` to
  `runtime` scope so `mvn spring-boot:run` / `java -jar` can boot on H2 without an
  external DB (test behaviour is unchanged).
- **domain `LoanOriginationClientConfig`**: an `@AutoConfiguration` provides a
  default in-JVM `LoanOriginationClient` bean so the domain boots standalone (the
  command handlers and saga require that seam, whose only other implementation is
  a test-side stub). It is `@ConditionalOnMissingBean`, so the slice tests'
  recording stub still takes precedence and no test behaviour changes.
