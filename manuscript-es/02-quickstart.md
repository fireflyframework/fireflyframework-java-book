Chapter 1 made the case; this chapter makes it move. In the next few minutes you
go from an empty folder to a Firefly service that boots, reports its own health,
serves an OpenAPI document, and answers a real request — a loan application that
comes back stamped `SUBMITTED`. You will not understand every line yet, and that
is the point. The goal here is to see the *whole shape* once, fast, so the deep
chapters that follow have something concrete to deepen.

Everything you run in this chapter lives in the companion reactor under
`core-lending-loan-origination` — the **core** tier service, the system of record
for loan origination. It is the same module the rest of Part II grows organically.
Here we treat it as a finished thing and take it for a spin.

A word on honesty before we start. Some commands below — the ones that *scaffold*
a brand-new project with the `flywork` CLI — are shown illustratively, because the
reactor you are reading was generated once and then committed. The commands that
*boot and exercise* the service are real, and the test that proves it passes is
the one you will run at the end. Illustrative blocks use plain code fences; the
verified, verbatim slices use the file-tabbed listings you met in the conventions
page.

## Step 1 — Scaffold a service with flywork

Firefly ships a companion CLI, `flywork`, that scaffolds a project from a tier
archetype and bootstraps the framework build. You pick the tier — `core`,
`domain`, `data`, or `application` — and `flywork` lays down a Maven module wired
to the matching starter, a `@SpringBootApplication` entry point, and the
conventional package layout.

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
hand-rolled error handler, a JSON-logging config, a health endpoint. Those come
from the tier starter, version-coherent and pre-wired. The scaffold is
deliberately thin — a real entry point and a real `pom.xml`, and almost nothing
else for you to maintain.

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
extend. `@SpringBootApplication` and `SpringApplication.run(...)` are exactly what
you would write for any Spring Boot service. Everything Firefly adds arrives
through *auto-configuration* on the classpath, activated by the starter you are
about to read — not through code you write here.

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
WebFlux, the CQRS buses, event-driven plumbing, resilience, JSON logging, and the
startup banner. Third, the companion modules — `r2dbc`, `web`, `validators` —
layer on reactive persistence, the web error model, and finance-aware validation
constraints (`@ValidAmount`, `@ValidCurrencyCode`) that the later chapters use.

You added behavior by adding a dependency. You will change behavior, when you need
to, by declaring a bean. Nothing here is locked.

!!! spring "Spring parity"
    `fireflyframework-starter-core` is a Spring Boot starter like
    `spring-boot-starter-webflux` — a curated dependency that pulls in a coherent
    set and triggers auto-configuration. The difference is altitude: a vanilla
    starter wires *one* capability (the web stack); a Firefly tier starter wires the
    *whole* opinionated baseline for a kind of service. Same mechanism, more in the
    box.

## Step 4 — Boot it

With the parent installed, boot the service the ordinary Spring Boot way.

```text
$ mvn spring-boot:run

   _____ _           __ _
  |  ___(_)_ __ ___ / _| |_   _
  | |_  | | '__/ _ \ |_| | | | |
  |  _| | | | |  __/  _| | |_| |
  |_|   |_|_|  \___|_| |_|\__, |
                          |___/   Firefly Framework

  :: core-lending-loan-origination ::   (starter-core)

INFO  c.f.l.core.CoreLendingApplication        : Starting CoreLendingApplication
INFO  o.s.b.web.embedded.netty.NettyWebServer  : Netty started on port 8080
INFO  o.f.cqrs.command.DefaultCommandBus       : DefaultCommandBus ready with 0 registered handlers
INFO  c.f.l.core.CoreLendingApplication        : Started CoreLendingApplication in 2.5 seconds
```

That banner is not cosmetic — it is the starter announcing which tier baseline
booted, and the `DefaultCommandBus` line is the CQRS infrastructure
auto-configuring itself, ready for the handlers later chapters register. The
service is now listening on Netty's event loop.

Two endpoints come for free with the starter. **Actuator health** reports whether
the service and its dependencies are up:

```text
$ curl -s http://localhost:8080/actuator/health
{"status":"UP"}
```

And the **OpenAPI document** describes the HTTP API — generated, not hand-written —
with a Swagger UI served alongside it:

```text
$ curl -s http://localhost:8080/v3/api-docs
{"openapi":"3.0.1","info":{"title":"core-lending-loan-origination", ... }}
```

!!! note "Key term — Actuator health"
    **Actuator** is Spring Boot's set of production endpoints — `/actuator/health`,
    `/actuator/info`, metrics, and more. Firefly's starter turns the right ones on
    by default so every service in the fleet is observable the same way. A green
    `{"status":"UP"}` is your first proof of life.

## Step 5 — Exercise it: apply for a loan

A booting service that does nothing is not very convincing. The core service
exposes a loan-origination API; let's create an application and read it back. POST
a request body with the borrower, the amount, the term, and the purpose:

```text
$ curl -s -X POST http://localhost:8080/api/v1/loan-applications \
    -H 'Content-Type: application/json' \
    -d '{
          "borrowerPartyId": "8b1d0d3c-1f2a-4f7e-9a3b-7d2c4e5f6a7b",
          "requestedAmount": "12500.00",
          "currency": "EUR",
          "termInMonths": 36,
          "purpose": "HOME_IMPROVEMENT"
        }'
```

The service validates the payload, persists the application, and submits it in one
step. It answers `201 Created` with the stored resource — note the generated
`loanApplicationId` and the `SUBMITTED` status:

```json
{
  "loanApplicationId": "55ccb890-e344-4bcb-ba5c-0dfbdec05a95",
  "borrowerPartyId": "8b1d0d3c-1f2a-4f7e-9a3b-7d2c4e5f6a7b",
  "requestedAmount": "12500.00",
  "currency": "EUR",
  "termInMonths": 36,
  "purpose": "HOME_IMPROVEMENT",
  "status": "SUBMITTED"
}
```

Now read it back by its id with a `GET`:

```text
$ curl -s http://localhost:8080/api/v1/loan-applications/55ccb890-e344-4bcb-ba5c-0dfbdec05a95
```

```json
{
  "loanApplicationId": "55ccb890-e344-4bcb-ba5c-0dfbdec05a95",
  "currency": "EUR",
  "purpose": "HOME_IMPROVEMENT",
  "status": "SUBMITTED"
}
```

That round trip — POST creates and submits, GET reads back — is the spine of the
core service. Ask for something that does not exist and you do not get a stack
trace or a bespoke blob; you get a standard **RFC 7807** problem detail, the same
shape in every Firefly service:

```text
$ curl -s http://localhost:8080/api/v1/loan-applications/00000000-0000-0000-0000-000000000000
```

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "Loan application not found: 00000000-0000-0000-0000-000000000000"
}
```

You did not write that error handler. The starter did, once, for the whole fleet.

## Run it

You do not need a running server or Docker to prove all of this — the reactor ships
a slice test that boots the full reactive context against in-memory H2, drives the
real API with `WebTestClient`, and asserts the create-then-read round trip, the
404 problem detail, and validation rejection. From `samples/lumen-lending`, run:

```text
$ mvn -q -pl core-lending-loan-origination test
```

The module's tests pass, including the three web-layer cases you just exercised by
hand:

```text
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in com.firefly.lumen.core.web.LoanApplicationControllerTest
```

!!! tip "Checkpoint"
    Run `mvn -q -pl core-lending-loan-origination test` from
    `samples/lumen-lending`. A green `Tests run: 3, Failures: 0` on
    `LoanApplicationControllerTest` means the whole shape — boot, validate,
    persist, submit, read back, and RFC 7807 errors — works on your machine. That
    green line is the contract every listing in this book is checked against.

## What you built {.recap}

- You scaffolded a **core** service with `flywork` (illustratively), saw that the
  generated entry point is a plain `@SpringBootApplication` with a `main` method,
  and that all the behavior arrives through one **tier starter** on the classpath.
- You read the `pom.xml` slice and saw the move from Chapter 1 in practice:
  **inherit a parent, add `fireflyframework-starter-core`, omit versions.**
- You booted the service — banner, Actuator health, generated OpenAPI — and
  exercised its loan-origination API: a POST that creates and **submits** an
  application (status `SUBMITTED`), a GET that reads it back, and a consistent
  **RFC 7807** 404.
- You ran the reactor's slice test and watched `Tests run: 3, Failures: 0` —
  the same round trip, verified end to end against in-memory H2.

## Try it yourself {.exercises}

1. **Read the real test.** Open
   `core-lending-loan-origination/src/test/java/com/firefly/lumen/core/web/LoanApplicationControllerTest.java`
   and match each `@Test` to a `curl` from this chapter. Which assertion proves the
   `SUBMITTED` status? Which one proves the RFC 7807 shape?
2. **Break the payload.** That test's `rejectsAnInvalidPayload` case sends a
   `requestedAmount` of `-5.00` and expects `400 Bad Request`. Change it to a
   positive amount and rerun `mvn -q -pl core-lending-loan-origination test` — what
   fails, and what does that tell you about `@ValidAmount`?
3. **Count the free behavior.** Re-read the `pom.xml` slice in
   `core-lending-loan-origination/pom.xml`. List every capability you got *without
   writing code* — error handling, validation, logging, health, OpenAPI — and note
   which dependency each one rides in on.
4. **Trace the entry point.** Open
   `core-lending-loan-origination/src/main/java/com/firefly/lumen/core/CoreLendingApplication.java`
   and confirm there is nothing Firefly-specific in it. Where, then, does the
   framework hook in? (Hint: the answer is on the classpath, not in the class.)

## Where to go next

You have seen the whole shape; now the rest of the book slows down and builds it
properly. Chapter 3 explains the parent POM and BOM that made Listing 2.2's
version-free dependencies possible — the version-coherence story underneath this
quickstart. From there, Part II reconstructs this very service tier by tier, the
honest way, one verified slice at a time.
