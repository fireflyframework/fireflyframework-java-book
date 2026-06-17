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

### Experience BFF (8080) — the live exp → domain → core submit flow

With all three tiers up, a single channel `POST` to the BFF flows end to end:
the experience tier calls the domain over HTTP, the domain runs the
`RegisterApplicationSaga`, the saga's root step writes to the core system of
record over HTTP, and the core-assigned id is returned all the way back.

Submit an application (**201 Created**, the application flowed exp → domain
(saga) → core):

```bash
curl -s -X POST localhost:8080/api/v1/experience/lending/applications \
  -H 'Content-Type: application/json' \
  -d '{"productId":"11111111-1111-1111-1111-111111111111","requestedAmount":25000.00,"term":36,"purpose":"HOME_IMPROVEMENT","simulationId":"22222222-2222-2222-2222-222222222222"}'
# 201:
# {"applicationId":"786544c7-2f10-4110-95fe-682d63edbace",
#  "simulationId":"22222222-2222-2222-2222-222222222222","status":"SUBMITTED",
#  "requestedAmount":25000.00,"term":36,"purpose":"HOME_IMPROVEMENT",
#  "createdAt":"2026-06-17T11:46:21.186231","updatedAt":"2026-06-17T11:46:21.186231"}
```

Verify it really landed in **core** (the `applicationId` above is the id the core
system of record assigned):

```bash
curl -s localhost:8081/api/v1/loan-applications/786544c7-2f10-4110-95fe-682d63edbace
# 200:
# {"loanApplicationId":"786544c7-2f10-4110-95fe-682d63edbace",
#  "applicationNumber":"9d2e8b8c-fc64-4aae-8578-c77558a3ec4b",
#  "applicantId":"8db1c7ab-5d74-44fd-a4c7-d9433f0ddaba",
#  "requestedAmount":25000.00,"currency":"EUR","termMonths":12,"purpose":"GENERAL",
#  "status":"SUBMITTED","decisionReason":null,
#  "createdAt":"2026-06-17T11:46:21.145765","updatedAt":"2026-06-17T11:46:21.145781"}

curl -s localhost:8081/api/v1/loan-applications
# 200: [ { the same application as above } ]
```

Read it back through the BFF (**200 OK**, exp → domain → core round-trip — the
domain GET fetches it from core):

```bash
curl -s localhost:8080/api/v1/experience/lending/applications/786544c7-2f10-4110-95fe-682d63edbace
# 200:
# {"applicationId":"786544c7-2f10-4110-95fe-682d63edbace","simulationId":null,
#  "status":"SUBMITTED","requestedAmount":25000.00,"term":12,"purpose":"GENERAL",
#  "createdAt":"2026-06-17T11:46:21.145765","updatedAt":"2026-06-17T11:46:21.145781"}
```

On the domain tier you can see the saga drive the write (root step writes to core
over HTTP, then the two dependent steps complete in-process):

```text
[orchestration] started   name=RegisterApplicationSaga ... pattern=SAGA
[orchestration] step.success ... stepId=registerLoanApplication latencyMs=94
[orchestration] step.success ... stepId=proposeOffer
[orchestration] step.success ... stepId=registerApplicant
[orchestration] completed name=RegisterApplicationSaga ... success=true
```

This proves the **exp → domain → core** path is wired and live: the BFF builds a
`WebClient` from `lumen.exp.loan-origination.base-path` and calls the domain at
`http://localhost:8082/api/v1/applications`; the domain's
`LoanOriginationController` runs the saga; the saga's root step calls the core
`WebClient` client (`firefly.lumen.core.loan-origination.base-path`) at
`http://localhost:8081/api/v1/loan-applications`; and the core's system-of-record
DTO comes back through both seams. (Some core fields — `currency`, `termMonths`,
`purpose` — show defaults because the trimmed write seam carries only the
applicant name and amount; richer mapping is left to the generated SDK in the
real service.)

## Notes on the runnable wiring

These changes make the apps boot and serve the live three-tier flow while keeping
all 33 tests green (tests use their own `src/test/resources/application.yml`):

- **`src/main/resources/application.yml`** in each module: server port, plus the
  H2/R2DBC/Flyway keys (core), the CQRS/orchestration/EDA + `APPLICATION_EVENT`
  switches and `firefly.lumen.core.loan-origination.base-path` (domain), and
  `lumen.exp.loan-origination.base-path` +
  `firefly.application.security.enabled=false` (exp).
- **core `pom.xml`**: the H2 R2DBC and JDBC drivers were moved from `test` to
  `runtime` scope so `mvn spring-boot:run` / `java -jar` can boot on H2 without an
  external DB (test behaviour is unchanged).
- **core `LoanApplicationDeleteController`**: a small second `@RestController` adds
  `DELETE /api/v1/loan-applications/{id}` (idempotent) for the saga's compensation
  path, kept separate so the primary controller sliced in the book stays verbatim.
- **domain `LoanOriginationController`**: the orchestration tier's REST face —
  `POST`/`GET /api/v1/applications` — that the BFF calls; it drives the
  `RegisterApplicationSaga` and maps the channel request/response.
- **domain `WebClientLoanOriginationClient` + `LiveLoanOriginationClientConfig`**:
  the live core SDK seam, a `WebClient`-backed `LoanOriginationClient` that writes
  to core over HTTP (and a `CoreLoanApplicationReader` for GET-by-id). It is
  `@ConditionalOnProperty(firefly.lumen.core.loan-origination.base-path)` and
  `@ConditionalOnMissingBean`, so it only activates when a core base path is set
  and never displaces the slice tests' recording stub.
- **domain `LoanOriginationClientConfig`**: an `@AutoConfiguration` provides a
  default in-JVM `LoanOriginationClient` bean so the domain still boots standalone
  when no core base path is set. It is `@ConditionalOnMissingBean`, so both the
  live client (when a base path is configured) and the slice tests' recording stub
  take precedence over it and no test behaviour changes.
