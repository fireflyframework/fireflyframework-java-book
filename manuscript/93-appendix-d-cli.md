`flywork` is Firefly's command-line companion — the tool that bootstraps the
framework on a new machine and scaffolds new services so they start life on the
right conventions. (It happens to be written in Go and shipped as a single binary;
that is an implementation detail — you never write Go to use it, and this remains a
book about the Java framework.)

This appendix is two things: a field guide to the CLI, and a troubleshooting log of
the *real* problems you will meet running the companion reactor — the same ones this
book's sample (`samples/lumen-lending`) hit and pinned down. The CLI sections are
illustrative; the troubleshooting section is grounded in what actually went wrong
and what fixed it.

## Bootstrapping the framework

The framework is many repositories, and they build in dependency order. `flywork
setup` clones them all and installs them to your local Maven repository
(`~/.m2`) in the right sequence, so that `fireflyframework-kernel` is built before
the modules that depend on it, and so on up the graph.

```text
$ flywork setup
Resolving framework dependency graph … 41 repositories, 7 layers
Layer 1/7  kernel, utils, validators …            installed
Layer 2/7  observability, cache, eda …            installed
Layer 3/7  r2dbc, web, cqrs …                     installed
…
Layer 7/7  starter-core, starter-domain …         installed
Done. Framework 26.06.01 available in ~/.m2.
```

The graph is a DAG, not a flat list. `flywork setup` topologically sorts the ~41
repositories into layers and installs each layer before the next, because a module
in layer 3 (say `fireflyframework-cqrs`) compiles against artifacts from layers 1
and 2. Within a layer the modules are independent and could build in parallel. The
last layer is the tier starters — `starter-core`, `starter-domain`,
`starter-application` — because they sit at the top of the graph and pull
everything below them together into one curated dependency.

!!! note "Most readers do not need `flywork setup`"
    The version this book targets — **26.06.01** — is published to **Maven
    Central**. A normal build resolves `org.fireflyframework:*` straight from
    Central (and caches it in `~/.m2`), so you can build and run the sample without
    ever cloning the framework. You only need `flywork setup` when you are working
    *on the framework itself*, or want a fully offline build of an unreleased
    `-SNAPSHOT`. If a build resolves `26.06.01` from Central, you are done — skip
    setup entirely.

Once the framework is resolvable — from Central or from a local `flywork setup` — a
project that inherits `fireflyframework-parent` or imports `fireflyframework-bom`
resolves its versions centrally and coherently, the version-coherence story Chapter
3 unpacks in full.

## Scaffolding a service

`flywork create` generates a new project from one of four archetypes, each aligned
to a tier (Chapter 14):

```text
$ flywork create \
    --archetype domain \
    --group com.firefly.lumen \
    --artifact domain-lending-loan-origination \
    --package com.firefly.lumen.domain
```

| Archetype | Produces | Starter |
|---|---|---|
| `core` | a system-of-record service (R2DBC, Flyway, reactive web) | `fireflyframework-starter-core` |
| `domain` | an orchestration service (CQRS, saga, EDA) | `fireflyframework-starter-domain` |
| `application` | an experience/BFF service (`@Secure`, SDK clients) | `fireflyframework-starter-application` |
| `library` | a shared library module (no starter, no `main`) | — |

These are the same four archetypes the sample's three runnable tiers were generated
from: `core-lending-loan-origination` (core, serves on **:8081**),
`domain-lending-loan-origination` (domain, **:8082**), and `exp-lending`
(application, **:8080**). The `library` archetype is the odd one out — it produces a
plain JAR module with the parent and conventions but *no* tier starter and no
`@SpringBootApplication`, for the shared code (DTOs, client interfaces) that sits
between tiers.

The generated project already has the right parent, the tier starter, a sensible
package layout, an `application.yml`, and a passing smoke test — the same shape as
the modules you grew across this book. What it deliberately does *not* write:
pinned dependency versions, an error handler, a JSON-logging config, a health
endpoint, or a banner. Those arrive through the tier starter via auto-configuration
(Chapter 2). The scaffold is thin on purpose: what you do not own, you cannot break.

!!! spring "Spring parity"
    `flywork create` is the Firefly counterpart to Spring Initializr
    (`start.spring.io`). Initializr asks you to tick individual starters; `flywork`
    asks you to pick a *tier*, then selects the right Firefly starter and the parent
    POM for you — so a fleet of services starts from the same opinionated baseline
    instead of a hundred slightly different checkboxes.

!!! note "The reactor was generated once, then committed"
    The `flywork create` commands in this book are shown illustratively. The sample
    reactor was scaffolded once and then committed and grown by hand — re-running
    the generator would just recreate what is already on disk. Everything you *run*
    against the sample (`mvn spring-boot:run`, the `curl` calls, the tests) is real.

## Running a generated service

Each generated service is an ordinary Spring Boot application, runnable two ways
from its module directory:

```text
# during development — the Maven plugin compiles and runs in one step
$ mvn spring-boot:run

# as a standalone fat JAR — build once, run anywhere with a JDK
$ mvn clean package
$ java -jar target/<artifact>-<version>.jar
```

The `java -jar` path only works because the build wires Spring Boot's **repackage**
goal — the step that rewrites the plain JAR into an executable fat JAR with an
embedded launcher and all dependencies inside. In the sample this is the
`spring-boot-maven-plugin`'s `repackage` execution; without it, `java -jar` fails
with `no main manifest attribute`. If you scaffold with `flywork` the execution is
present by default; if you hand-write a module, wiring it is the one thing that
turns `package` output into something runnable. See the troubleshooting note below.

The sample runs end to end with **no Docker, no external database, and no message
broker**: the core tier persists to in-memory **H2** (R2DBC at runtime, JDBC for
Flyway), and events flow over the in-JVM **`APPLICATION_EVENT`** transport. With all
three tiers up, the live path is a single POST to the BFF:

```text
$ curl -s -X POST localhost:8080/api/v1/experience/lending/applications \
    -H 'Content-Type: application/json' \
    -d '{"productId":"11111111-1111-1111-1111-111111111111","requestedAmount":25000.00,"term":36,"purpose":"HOME_IMPROVEMENT","simulationId":"22222222-2222-2222-2222-222222222222"}'
# 201 -> {"applicationId":"...","status":"SUBMITTED",...}
```

That `201 SUBMITTED` is the experience tier calling the domain over HTTP, the domain
running the `RegisterApplicationSaga`, and the saga's root step writing to the core
system of record over HTTP — the full **exp → domain → core** flow, all on H2.

## Troubleshooting

A short field guide to the issues you are most likely to meet, ordered roughly by
how early they bite. The first cluster is what the sample itself hit.

!!! tip "Checkpoint — is the framework resolvable?"
    If a build fails to resolve `org.fireflyframework:*`, first confirm you are
    online (Central has **26.06.01**) or that `~/.m2/repository/org/fireflyframework/`
    is populated by `flywork setup`. Every other problem below assumes the framework
    artifacts resolve.

- **`error: release version 25 not supported` (or `invalid target release: 25`).**
  The parent POM sets the language baseline to **Java 25** (Chapter 3), and your
  installed JDK is older — typically JDK 21. You have two honest fixes: install a
  JDK 25 toolchain, or build against the LTS with the parent's opt-down profile:

  ```text
  $ mvn -Pjava21 verify          # retargets source/target to Java 21
  $ mvn -Pjava21 spring-boot:run # run a single tier on Java 21
  ```

  `-Pjava21` lowers the *bytecode target and source level* only; it does not
  downgrade your JDK and cannot conjure Java 25 language features. It is the escape
  hatch for shops not yet on 25 — Java 25 remains the default. This is the single
  most common first-run failure, and it is purely a toolchain mismatch, not a
  framework problem.

- **`no main manifest attribute` running `java -jar target/...jar`.** The JAR was
  not repackaged into an executable fat JAR, so it has no launcher. Confirm the
  module's build wires the Spring Boot Maven plugin's `repackage` execution (it is
  present in every `flywork`-generated module and in the sample's three tiers). With
  it, `mvn clean package` produces a runnable JAR; without it, prefer `mvn
  spring-boot:run`, which does not need repackaging.

- **`@Secure` endpoints return `401`/`403` when you run locally.** The endpoint is
  genuinely secured — the experience tier's BFF guards its methods with `@Secure`
  (Chapter 19). In a local run with no identity provider, every call is anonymous
  and gets rejected. For the sample's local stack, security *enforcement* is turned
  off in the experience tier's runnable config:

  ```text
  firefly:
    application:
      security:
        enabled: false
  ```

  That is exactly the `firefly.application.security.enabled=false` key in
  `exp-lending`'s `src/main/resources/application.yml`. It disables enforcement for
  hands-on local use; it does *not* exist in the test profile, where the slice
  supplies a permissive test security context instead. Never ship a service with
  enforcement disabled — this is a local-development affordance only.

- **A core service will not boot without a database, or wants Docker you do not
  have.** The sample runs on in-memory **H2** with no container. Two things make
  that work: the **R2DBC H2** driver (`r2dbc-h2`) provides reactive runtime access,
  and the **H2 JDBC** driver runs **Flyway** migrations at startup. In the sample's
  core module these two drivers were moved from `test` to `runtime` scope so that
  `mvn spring-boot:run` and `java -jar` can boot on H2 without an external DB (test
  behaviour is unchanged — tests already had H2 on the test classpath). If your core
  service demands a real Postgres on boot, check those drivers are present at
  `runtime` and that your `application.yml` points R2DBC and Flyway at an H2 URL.

- **An `@EventListener` never fires.** The runtime matches `eventTypes` by the
  payload's **simple class name** (e.g. `LoanApplicationSubmitted`), not the
  fully-qualified name. A mismatch there is the usual cause. Also confirm EDA is
  enabled (`firefly.eda.enabled=true`) with a transport configured — the sample uses
  the in-JVM `APPLICATION_EVENT` transport, which needs no broker. Chapter 11 covers
  this in depth.

- **`BUILD FAILURE` resolving a framework artifact, or a confusing
  `NoSuchMethodError` at runtime.** The version you declared is not available, or you
  have mixed two framework versions. Align everything to a single release line —
  **26.06.01** for this book — by inheriting `fireflyframework-parent` or importing
  the matching `fireflyframework-bom`, and never pin a Firefly artifact's
  `<version>` by hand. Mixing two framework versions is the most common cause of a
  `NoSuchMethodError` that compiles cleanly but blows up at runtime.

- **A reactive endpoint blocks under load / occasional stalls.** Something on the
  request path is blocking the event loop — a JDBC call, a `.block()`, a synchronous
  third-party SDK. Move it off the event loop
  (`subscribeOn(Schedulers.boundedElastic())`) or replace it with a reactive client.
  Re-read Chapter 5's "never block" warning.

- **A correlation or trace ID is missing in a downstream log.** Confirm
  `fireflyframework-observability` is present — it enables automatic Reactor context
  propagation. Without it, `ThreadLocal`/MDC values do not follow operators across
  reactive thread hops, and your `traceId`/`spanId` fall out of the JSON logs.

- **A list endpoint ignores a filter parameter.** ID fields are excluded from the
  generic filter engine unless annotated `@FilterableId`. See Chapter 8.

- **The live exp → domain → core flow returns but core fields look like defaults.**
  In the sample, some core fields (`currency`, `termMonths`, `purpose`) show
  defaults on the saga write path because the trimmed write seam carries only the
  applicant and amount; richer mapping is left to the generated SDK in the real
  service. This is a property of the *sample's* trimmed seam, not a framework bug —
  the round trip itself (id assigned by core, read back through the BFF) is real.

- **Tests need Docker you do not have.** Prefer the in-process defaults — H2 for
  R2DBC, the in-JVM `APPLICATION_EVENT` EDA transport, Caffeine for cache — exactly
  as this book's reactor does. Reach for Testcontainers (Chapter 23) only when a
  chapter calls for a real backend.

## Where to go next

With `flywork setup` done once (or Central doing the work for you) and `flywork
create` for each new service, standing up a correct, consistent microservice is a
single command — and the troubleshooting log above is the short list of the few
places reality intrudes. That, minus the gotchas, is the whole promise of Chapter 1,
now at your fingertips.
