Chapter 1 made the case; this chapter makes it move. In the next few minutes you
go from an empty folder to a Firefly service that boots, prints a banner that tells
you which tier you are running, reports its own health, serves an OpenAPI document
it generated for itself, and answers a real request — a loan application that comes
back stamped `SUBMITTED`. You will not understand every line yet, and that is the
point. The goal here is to see the *whole shape* once, fast, so the deep chapters
that follow have something concrete to deepen.

Everything you run in this chapter lives in the companion reactor under
`core-lending-loan-origination` — the **core** tier service, the system of record
for loan origination. It is the same module the rest of Part II grows organically.
Here we treat it as a finished thing and take it for a spin, top to bottom, in six
guided steps: scaffold, read the entry point, read the one dependency that matters,
boot it and *read every line the boot prints*, exercise the API by hand, and prove
the whole thing with a single test.

A word on honesty before we start, because this book lives or dies on it. The
commands that *scaffold* a brand-new project with the `flywork` CLI are shown
illustratively, because the reactor you are reading was generated once and then
committed — re-running the generator would just recreate what is already on disk.
Everything else is real. The banner is the framework's actual banner, extracted
from the starter on your classpath. The boot log lines are real captured output in
the framework's real JSON shape. The response bodies are exactly what the running
service returns, byte for byte. And the test that proves it all is the one you run
at the end. Illustrative blocks use plain code fences; the verified, verbatim
source slices use the file-tabbed listings you met on the conventions page.

## Step 1 — Scaffold a service with flywork

Firefly ships a companion CLI, `flywork`, that scaffolds a project from a tier
archetype and bootstraps the framework build. You pick the tier — `core`,
`domain`, `data`, or `application` — and `flywork` lays down a Maven module wired
to the matching starter, a `@SpringBootApplication` entry point, and the
conventional package layout. One command, and you have a service skeleton that
already inherits the entire opinionated baseline.

```text
$ flywork create \
    --archetype core \
    --group com.firefly.lumen \
    --artifact core-lending-loan-origination \
    --package com.firefly.lumen.core

  Firefly · flywork

  resolved archetype 'core' (Firefly starter-core)
  wrote pom.xml            (parent + 1 tier starter, no versions)
  wrote CoreLendingApplication.java
  created src/main/java/com/firefly/lumen/core
  created src/main/resources/application.yml

  Done. Next:
    cd core-lending-loan-origination
    mvn spring-boot:run
```

Notice what `flywork` did *not* write: a list of pinned dependency versions, a
hand-rolled error handler, a JSON-logging config, a health endpoint, a banner.
Those come from the tier starter, version-coherent and pre-wired. The scaffold is
deliberately thin — a real entry point and a real `pom.xml`, a near-empty
`application.yml`, and almost nothing else for you to maintain. The whole bet of
Firefly is that the *less* a scaffold writes, the *less* there is to drift, audit,
and keep in sync across a fleet. What you do not own, you cannot break.

The fourth file `flywork` wrote, `application.yml`, is worth a glance now because
it is so small. The entire committed config for this service is three lines:

```text
spring:
  application:
    name: core-lending-loan-origination
```

That one property — the application *name* — is the only configuration the service
needs to boot, and you will see it surface twice in this chapter: once in the
banner, once in the generated OpenAPI title. Everything else has a sensible default
baked into the starter.

!!! note "Key term — tier archetype"
    A **tier archetype** is the `flywork` template for one of Firefly's four
    service tiers. Choosing `core` selects `starter-core` and a system-of-record
    layout; choosing `application` selects `starter-application` and a stateless
    BFF layout. The archetype decides which starter you inherit and which defaults
    you boot with. Chapter 1's four tiers map one-to-one onto four archetypes.

!!! spring "Spring parity"
    `flywork create` is the Firefly counterpart to Spring Initializr (`start.spring.io`).
    Initializr asks you to tick individual starters; `flywork` asks you to pick a
    *tier*, then selects the right Firefly starter and the parent POM for you — so a
    fleet of services starts from the same opinionated baseline instead of a hundred
    slightly different checkboxes.

## Step 2 — One entry point

Open the class `flywork` generated. It is an ordinary Spring Boot application —
one annotation, one `main` method, no Firefly-specific code at all.

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/CoreLendingApplication.java | Listing 2.1 — the entire entry point
package com.firefly.lumen.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Boots the core (system-of-record) loan-origination service.
 *
 * <p>Skeleton entry point: later chapters add entities, repositories, and
 * REST controllers under {@code com.firefly.lumen.core}.
 */
@SpringBootApplication
public class CoreLendingApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreLendingApplication.class, args);
    }
}
:::

This is worth pausing on, because it is the whole thesis of Chapter 1 made
concrete. There is no `@EnableFirefly`, no custom bootstrap, no framework class to
extend, no base class to inherit. `@SpringBootApplication` and
`SpringApplication.run(...)` are *exactly* what you would write for any Spring Boot
service — copy this file into a vanilla project and it compiles unchanged.

So where does the framework hook in? Not here. `@SpringBootApplication` bundles
`@EnableAutoConfiguration`, and on boot Spring scans the classpath for
auto-configuration entries (the `META-INF/spring/...AutoConfiguration.imports`
files that every Firefly module ships). Each one is a conditional bean recipe:
"if this class is on the classpath and the user has not already defined this bean,
wire it up." Everything Firefly adds — the CQRS buses, the JSON logger, the global
error handler, the banner — arrives that way, *activated by the starter you are
about to read*, never by code you write here. That is why the entry point can stay
this empty: the behavior is on the classpath, not in the class.

!!! note "Key term — auto-configuration"
    **Auto-configuration** is Spring Boot's mechanism for wiring beans based on
    what is on the classpath. A library ships a class annotated with
    `@AutoConfiguration` plus `@Conditional...` guards and registers it in
    `META-INF`; Boot evaluates it at startup and applies it only when its
    conditions hold. Firefly is, at heart, a large coherent set of these — which is
    why "add a dependency" is the same as "turn on a capability."

## Step 3 — Add one tier starter

The behavior all lives in one place: the dependencies. Here is a contiguous slice
of the service's `pom.xml`, from the inherited parent through the framework
dependencies it pulls in.

::: listing core-lending-loan-origination/pom.xml | Listing 2.2 — the parent and the tier starter
    <parent>
        <groupId>com.firefly.lumen</groupId>
        <artifactId>lumen-lending</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <artifactId>core-lending-loan-origination</artifactId>
    <packaging>jar</packaging>

    <name>Lumen Lending - Core (Loan Origination)</name>
    <description>System-of-record tier: persistence + REST. Built on the Firefly core starter.</description>

    <dependencies>
        <!-- Core/infrastructure-layer microservice starter (WebFlux, EDA, CQRS, resilience). -->
        <dependency>
            <groupId>org.fireflyframework</groupId>
            <artifactId>fireflyframework-starter-core</artifactId>
        </dependency>
        <!-- Reactive persistence (R2DBC) abstractions. -->
        <dependency>
            <groupId>org.fireflyframework</groupId>
            <artifactId>fireflyframework-r2dbc</artifactId>
        </dependency>
        <!-- Reactive web layer helpers (controllers, error handling). -->
        <dependency>
            <groupId>org.fireflyframework</groupId>
            <artifactId>fireflyframework-web</artifactId>
        </dependency>
        <!-- Finance-aware Jakarta validation constraints (@ValidAmount, @ValidCurrencyCode). -->
        <dependency>
            <groupId>org.fireflyframework</groupId>
            <artifactId>fireflyframework-validators</artifactId>
        </dependency>
:::

Three things to read off this slice. First, the `<dependency>` entries carry **no
`<version>`** — the inherited parent (which imports Firefly's BOM) pins every
version centrally, the version-coherence story Chapter 3 unpacks in full. Second,
the headline line is `fireflyframework-starter-core`: that single starter is what
turns this from "a Spring Boot app" into "a Firefly core service," bundling
WebFlux, the CQRS command and query buses, event-driven plumbing, resilience,
JSON logging, idempotency filtering, and the startup banner. Third, the companion
modules — `r2dbc`, `web`, `validators` — layer on reactive persistence, the web
error model, and finance-aware validation constraints (`@ValidAmount`,
`@ValidCurrencyCode`) that the later chapters use.

You added behavior by adding a dependency. You will change behavior, when you need
to, by declaring a bean of your own — the auto-configuration steps aside the
moment you do. Nothing here is locked.

!!! spring "Spring parity"
    `fireflyframework-starter-core` is a Spring Boot starter like
    `spring-boot-starter-webflux` — a curated dependency that pulls in a coherent
    set and triggers auto-configuration. The difference is altitude: a vanilla
    starter wires *one* capability (the web stack); a Firefly tier starter wires the
    *whole* opinionated baseline for a kind of service. Same mechanism, more in the
    box.

## Step 4 — Boot it

With the parent installed, boot the service the ordinary Spring Boot way. From the
module directory:

```text
$ mvn spring-boot:run
```

The first thing it prints is the banner — and this is a real banner, not a
decoration. The starter ships a `banner.txt`, and Spring Boot renders it with the
live values of your application before a single business line runs. Here it is,
with the placeholders resolved to this service (a `core` tier service named
`core-lending-loan-origination`, on Spring Boot 3.5.10):

```text

  _____.__                _____.__
_/ ____\__|______   _____/ ____\  | ___.__.
\   __\|  \_  __ \_/ __ \   __\|  |<   |  |
 |  |  |  ||  | \/\  ___/|  |  |  |_\___  |
 |__|  |__||__|    \___  >__|  |____/ ____|
                       \/           \/
:: firefly-core ::               (v0.1.0-SNAPSHOT)

(c)2025 Firefly Software Foundation
Licensed under Apache 2.0

Spring Boot Version: 3.5.10
Application: core-lending-loan-origination
Application Description: Unknown
Application SwaggerUI: http://localhost:8080/swagger-ui.html
⇩⇩⇩ Logs start below ⇩⇩⇩
```

Read it line by line, because every line is telling you something. The figlet
lettering spells *firefly*. The `:: firefly-core ::` line is the starter announcing
which **tier baseline** booted — a `domain` service would say `firefly-domain`
here, an `application` service `firefly-application`; this is your at-a-glance proof
that the right starter is on the classpath. `Application:` echoes the one property
you set in `application.yml`. `Application SwaggerUI:` hands you a clickable URL to
the generated API docs before the service has finished starting. And the
`⇩⇩⇩ Logs start below ⇩⇩⇩` marker is a deliberate divider: everything above is the
banner, everything below is structured log output.

!!! note "Key term — the Firefly banner"
    The banner is `banner.txt` inside the tier starter, rendered by Spring Boot at
    startup with placeholders like `${spring.application.name}` and
    `${spring-boot.version}` substituted live. It is not cosmetic: the
    `:: firefly-<tier> ::` line is a one-glance assertion of which opinionated
    baseline this process is running, and the SwaggerUI line is a working link to
    the docs. You can override it with your own `banner.txt`, but every fleet
    service shows the same shape by default.

Below the divider come the framework's structured logs. Firefly logs as **JSON by
default** — one JSON object per line, ready for a log aggregator, with a
`timestamp`, the `message`, the abbreviated `logger`, the `level`, and (on request
threads) `traceId`/`spanId`. Here is a trimmed, ordered slice of the real startup
sequence:

```text
{"timestamp":"2026-06-17T08:21:43.069+0000","message":"FIREFLY EDA - EVENT-DRIVEN ARCHITECTURE LIBRARY","logger":"o.f.e.c.FireflyEdaAutoConfiguration","level":"INFO"}
{"timestamp":"2026-06-17T08:21:44.077+0000","message":"CQRS Query Bus configured with cache support via fireflyframework-cache","logger":"o.f.c.config.CqrsAutoConfiguration","level":"INFO"}
{"timestamp":"2026-06-17T08:21:44.112+0000","message":"Exposing 4 endpoints beneath base path '/actuator'","logger":"o.s.b.a.e.web.EndpointLinksResolver","level":"INFO"}
{"timestamp":"2026-06-17T08:21:44.319+0000","message":"Reactor automatic context propagation enabled — ThreadLocal/MDC values will automatically bridge to Reactor Context across thread boundaries","logger":"o.f.o.t.ReactiveContextPropagationAutoConfiguration","level":"INFO"}
{"timestamp":"2026-06-17T08:21:44.748+0000","message":"Netty started on port 8080 (http)","logger":"o.s.b.w.e.netty.NettyWebServer","level":"INFO"}
{"timestamp":"2026-06-17T08:21:44.772+0000","message":"DefaultCommandBus ready with 0 registered handlers","logger":"o.f.cqrs.command.DefaultCommandBus","level":"INFO"}
{"timestamp":"2026-06-17T08:21:44.772+0000","message":"Started CoreLendingApplication in 2.346 seconds","logger":"c.f.l.core.CoreLendingApplication","level":"INFO"}
```

That handful of lines *is* the auto-configuration story from Step 2, happening in
front of you. The EDA line is the event-driven library wiring itself up. The CQRS
line is the query bus configuring itself with cache support — note it found `0`
registered handlers, because this skeleton has not declared any yet; later chapters
will, and the count will climb. `Exposing 4 endpoints beneath base path '/actuator'`
is Actuator turning on. The Reactor context-propagation line is the framework making
sure your `traceId` survives across reactive thread hops (the foot-gun Chapter 1
warned about, handled for you). `Netty started on port 8080` means the reactive
server is listening. And `Started CoreLendingApplication` is the finish line — under
three seconds, with no code from you beyond the empty `main`.

!!! note "Key term — structured (JSON) logging"
    Firefly configures **JSON logging** out of the box: each log event is one JSON
    object with stable fields (`timestamp`, `level`, `logger`, `message`, and
    `traceId`/`spanId` on request threads). Machines parse it without regex, and a
    correlation ID threads through a whole request — including across the reactive
    boundaries that would lose it under naive logging. You did not configure any of
    this; the starter did.

Two endpoints come for free with the starter. **Actuator health** reports whether
the service *and each of its subsystems* are up. Firefly registers health
indicators for the pieces it auto-configured, so a single call rolls up the cache,
the CQRS buses, the EDA library, and the R2DBC connection (trimmed here for space):

```text
$ curl -s http://localhost:8080/actuator/health
{"status":"UP","groups":["liveness","readiness"],"components":{
  "cqrs":{"status":"UP","details":{"command_bus":"UP","query_bus":"UP","command_handlers":0,"query_handlers":0}},
  "eda":{"status":"UP","details":{"enabled":true,"message":"All EDA components are healthy"}},
  "r2dbc":{"status":"UP","details":{"database":"H2"}},
  "ping":{"status":"UP"}}}
```

And the **OpenAPI document** describes the HTTP API — generated from the
controllers, not hand-written — with a Swagger UI served alongside it at the URL the
banner printed. Note the `title`: the framework builds it from your
`spring.application.name`, so the one property in your three-line `application.yml`
surfaces a second time, suffixed with `API`:

```text
$ curl -s http://localhost:8080/v3/api-docs
{"openapi":"3.1.0","info":{"title":"core-lending-loan-origination API","description":"core-lending-loan-origination API Documentation","license":{"name":"Apache 2.0","url":"https://www.apache.org/licenses/LICENSE-2.0"},"version":"1.0.0"}, ... }
```

!!! note "Key term — Actuator health"
    **Actuator** is Spring Boot's set of production endpoints — `/actuator/health`,
    `/actuator/info`, metrics, and more. Firefly's starter turns the right ones on
    by default (the boot log counted four) and contributes health indicators for the
    subsystems it wired, so every service in the fleet is observable the same way. A
    green top-level `"status":"UP"` is your first proof of life.

## Step 5 — Exercise it: apply for a loan

A booting service that does nothing is not very convincing. The core service
exposes a loan-origination API; let's create an application and read it back. POST
a request body with the applicant, the amount, the currency, the term in months,
and the purpose:

```text
$ curl -s -X POST http://localhost:8080/api/v1/loan-applications \
    -H 'Content-Type: application/json' \
    -d '{
          "applicantId": "8b1d0d3c-1f2a-4f7e-9a3b-7d2c4e5f6a7b",
          "requestedAmount": "12500.00",
          "currency": "EUR",
          "termMonths": 36,
          "purpose": "HOME_IMPROVEMENT"
        }'
```

Before the controller method even runs, the request passes through the framework's
filters — and they log it. On the request thread you will see a fresh transaction
ID and the idempotency filter inspecting the call (these are real `DEBUG` lines,
showing the `traceId`/`spanId` that now decorate every log on this thread):

```text
{"timestamp":"2026-06-17T08:21:44.132+0000","message":"Generated new transaction ID: ce0c2ede-0e81-430f-9c99-7464a1613884","logger":"o.f.core.config.TransactionFilter","level":"DEBUG","traceId":"bfa32cdc5313c5951ec124b491f07687","spanId":"78466db40897c823"}
{"timestamp":"2026-06-17T08:21:44.134+0000","message":"IdempotencyWebFilter.filter: Processing request POST /api/v1/loan-applications","logger":"o.f.w.i.filter.IdempotencyWebFilter","level":"DEBUG","traceId":"bfa32cdc5313c5951ec124b491f07687","spanId":"78466db40897c823"}
```

The service validates the payload, persists the application, and submits it in one
step. It answers `201 Created` with the stored resource. Note the generated
`loanApplicationId` and `applicationNumber`, the lifecycle timestamps the service
stamped on, and — the headline — the `status`, which is `SUBMITTED`, not `DRAFT`,
because the service submits the application as part of creation:

```json
{
  "loanApplicationId": "6fb206b6-288f-4559-a403-460662a32329",
  "applicationNumber": "f2419b18-ae0c-4c32-9839-6c07ae424eda",
  "applicantId": "8b1d0d3c-1f2a-4f7e-9a3b-7d2c4e5f6a7b",
  "requestedAmount": 12500.00,
  "currency": "EUR",
  "termMonths": 36,
  "purpose": "HOME_IMPROVEMENT",
  "status": "SUBMITTED",
  "decisionReason": null,
  "createdAt": "2026-06-17T08:21:44.215",
  "updatedAt": "2026-06-17T08:21:44.215"
}
```

Now read it back by its id with a `GET` — using the `loanApplicationId` the create
call returned:

```text
$ curl -s http://localhost:8080/api/v1/loan-applications/6fb206b6-288f-4559-a403-460662a32329
```

```json
{
  "loanApplicationId": "6fb206b6-288f-4559-a403-460662a32329",
  "applicationNumber": "f2419b18-ae0c-4c32-9839-6c07ae424eda",
  "applicantId": "8b1d0d3c-1f2a-4f7e-9a3b-7d2c4e5f6a7b",
  "requestedAmount": 12500.00,
  "currency": "EUR",
  "termMonths": 36,
  "purpose": "HOME_IMPROVEMENT",
  "status": "SUBMITTED",
  "decisionReason": null,
  "createdAt": "2026-06-17T08:21:44.215",
  "updatedAt": "2026-06-17T08:21:44.215"
}
```

That round trip — POST creates and submits, GET reads back — is the spine of the
core service. Now exercise the unhappy paths, because how a framework *fails* tells
you more than how it succeeds.

Ask for an application that does not exist, and you do not get a stack trace or a
bespoke blob; you get a standard **RFC 7807 problem detail**, the same shape in
every Firefly service. The top-level members are the RFC's `type`, `title`,
`status`, `detail`, and `instance`; Firefly adds an `extensions` object carrying
the trace context and a remediation `suggestion`:

```text
$ curl -s http://localhost:8080/api/v1/loan-applications/00000000-0000-0000-0000-000000000000
```

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "Loan application not found: 00000000-0000-0000-0000-000000000000",
  "instance": "/api/v1/loan-applications/00000000-0000-0000-0000-000000000000?traceId=1efc25dec634992124f6a1520970dfef",
  "extensions": {
    "traceId": "1efc25dec634992124f6a1520970dfef",
    "spanId": "6709a187f331beb0",
    "severity": "LOW",
    "retryable": false,
    "path": "/api/v1/loan-applications/00000000-0000-0000-0000-000000000000",
    "suggestion": "Verify the resource identifier and ensure it exists.",
    "category": "RESOURCE"
  }
}
```

Send a bad payload — a negative `requestedAmount`, which violates `@ValidAmount` —
and the validation never reaches your service at all. The framework rejects it with
`400` and the same problem-detail shape, this time with a typed `type` URI and an
`errors` array under `extensions` pinpointing the offending field:

```text
$ curl -s -X POST http://localhost:8080/api/v1/loan-applications \
    -H 'Content-Type: application/json' \
    -d '{"applicantId":"8b1d0d3c-1f2a-4f7e-9a3b-7d2c4e5f6a7b","requestedAmount":"-5.00","currency":"EUR","termMonths":36,"purpose":"HOME_IMPROVEMENT"}'
```

```json
{
  "type": "https://api.firefly.com/errors/validation_error",
  "title": "Validation Failed",
  "status": 400,
  "detail": "Invalid request parameters",
  "instance": "/api/v1/loan-applications?traceId=62f4d701-8adc-49c7-bfea-5e325569e5d6",
  "extensions": {
    "code": "VALIDATION_ERROR",
    "suggestion": "Please check the validation errors and correct your request.",
    "errors": [
      {
        "field": "requestedAmount",
        "code": "ValidAmount",
        "message": "Requested amount must be a positive monetary value",
        "metadata": { "bindingFailure": false, "rejectedValue": "-5.00" }
      }
    ]
  }
}
```

You did not write that error handler, and you did not write either response shape.
The starter did, once, for the whole fleet — so a 404 from this service looks
exactly like a 404 from every other Firefly service, trace context and all.

!!! note "Key term — RFC 7807 problem detail"
    **RFC 7807** ("Problem Details for HTTP APIs") is the IETF standard for machine-
    readable error bodies, served as `application/problem+json`. Its members are
    `type` (a URI for the error kind), `title`, `status`, `detail`, and `instance`.
    Firefly's `GlobalExceptionHandler` emits this shape for every unhandled error
    and tucks framework extras — trace IDs, a retry hint, a remediation
    `suggestion` — into the standard `extensions` object, so clients can rely on one
    error contract everywhere.

!!! spring "Spring parity"
    Spring Framework 6 ships its own `ProblemDetail` and lets a
    `@ControllerAdvice` map exceptions to it — but you still write that advice in
    every service. Firefly registers one `GlobalExceptionHandler` in the starter, so
    the RFC 7807 contract is fleet-wide by default and enriched with trace context.
    Same standard, zero per-service wiring.

## Step 6 — Prove it

You do not need a running server or Docker to prove all of this — the reactor ships
a slice test that boots the full reactive context against in-memory H2 (R2DBC
runtime plus a Flyway migration), drives the real API with `WebTestClient`, and
asserts the create-then-read round trip, the RFC 7807 404, and validation rejection.
It is the *same* code paths you just exercised by hand, run headless. From
`samples/lumen-lending`, run:

```text
$ mvn -q -pl core-lending-loan-origination test
```

The whole module's tests pass — eighteen of them across the domain model, the
reactive helpers, and the web layer:

```text
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in com.firefly.lumen.core.web.LoanApplicationControllerTest
[INFO] Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

The three cases in `LoanApplicationControllerTest` are exactly the three things you
just did with `curl`: create-and-read-back (asserting the `SUBMITTED` status), the
missing-id 404, and the rejected bad payload. The other fifteen exercise the domain
model and the reactive plumbing you will meet in Chapters 5 and beyond.

!!! tip "Checkpoint"
    Run `mvn -q -pl core-lending-loan-origination test` from
    `samples/lumen-lending`. A green `Tests run: 18, Failures: 0` and a final
    `BUILD SUCCESS` mean the whole shape — boot, validate, persist, submit, read
    back, and RFC 7807 errors — works on your machine. That green line is the
    contract every listing in this book is checked against.

## What you built {.recap}

- You scaffolded a **core** service with `flywork` (illustratively), saw that the
  generated entry point is a plain `@SpringBootApplication` with a `main` method
  and a three-line `application.yml`, and that all the behavior arrives through one
  **tier starter** on the classpath via **auto-configuration**.
- You read the `pom.xml` slice and saw the move from Chapter 1 in practice:
  **inherit a parent, add `fireflyframework-starter-core`, omit versions.**
- You booted the service and read the boot, line by line: the real
  `:: firefly-core ::` banner, the JSON auto-configuration logs (EDA, CQRS, Reactor
  context propagation), `Netty started on port 8080`, four Actuator endpoints, the
  generated OpenAPI document — and a `Started ... in 2.346 seconds` finish.
- You exercised the loan-origination API: a POST that creates and **submits** an
  application (status `SUBMITTED`), a GET that reads it back, and two consistent
  **RFC 7807** errors — a 404 for a missing id and a 400 for a bad amount, both
  carrying trace context in `extensions`.
- You ran the reactor's tests and watched `Tests run: 18, Failures: 0` with
  `BUILD SUCCESS` — the same round trip, verified end to end against in-memory H2.

## Try it yourself {.exercises}

1. **Read the real test.** Open
   `core-lending-loan-origination/src/test/java/com/firefly/lumen/core/web/LoanApplicationControllerTest.java`
   and match each `@Test` to a `curl` from this chapter. Which assertion proves the
   `SUBMITTED` status? Which one proves the RFC 7807 shape (hint: it checks the
   `$.status` JSON path)?
2. **Break the payload.** That test's `rejectsAnInvalidPayload` case sends a
   `requestedAmount` of `-5.00` and expects `400 Bad Request`. Change it to a
   positive amount and rerun `mvn -q -pl core-lending-loan-origination test` — what
   fails, and what does that tell you about `@ValidAmount` and where validation runs
   in the request pipeline?
3. **Watch the banner change.** The `:: firefly-core ::` line is the *core* tier
   announcing itself. Look at the `domain` and `application` modules elsewhere in
   the reactor and predict what their banner's tier line says. Then confirm it from
   their starters.
4. **Count the free behavior.** Re-read the `pom.xml` slice in
   `core-lending-loan-origination/pom.xml` and the boot log. List every capability
   you got *without writing code* — JSON logging, the banner, error handling,
   validation, health, OpenAPI, CQRS buses, idempotency, trace propagation — and
   note which dependency or log line each one rides in on.
5. **Trace the entry point.** Open
   `core-lending-loan-origination/src/main/java/com/firefly/lumen/core/CoreLendingApplication.java`
   and confirm there is nothing Firefly-specific in it. Where, then, does the
   framework hook in? (Hint: the answer is `@EnableAutoConfiguration` and the
   classpath, not the class.)

## Where to go next

You have seen the whole shape; now the rest of the book slows down and builds it
properly. Chapter 3 explains the parent POM and BOM that made Listing 2.2's
version-free dependencies possible — the version-coherence story underneath this
quickstart. From there, Part II reconstructs this very service tier by tier, the
honest way, one verified slice at a time.
