Spring Boot solved a real problem, and solved it well. Before it, standing up a
Java service meant hand-assembling a web container, a JSON mapper, a validation
provider, a data layer, and a dozen other parts — every team a little differently.
Spring Boot replaced that ceremony with *convention over configuration*: add a
starter, get a working slice. A single service has never been easier to start.

But a bank is not a single service. It is a *fleet* — dozens, then hundreds, of
reactive microservices that must agree with one another and behave the same way in
production. And about that, Spring Boot is deliberately silent. It gives you superb
building blocks and no opinion about how to assemble them consistently across a
fleet. That silence is where teams bleed time, and it is the problem this book — and
the Firefly Framework — exists to solve.

## The enterprise tax

Watch what happens when the same organization builds its tenth Spring Boot service.
Each one re-implements, slightly differently, the same cross-cutting plumbing:

- **Error responses.** One service returns a stack trace, another a bespoke JSON
  blob, a third a bare 500. None agree on status codes or shape, so every client
  writes per-service error handling.
- **Idempotency.** Payment and write endpoints need to dedupe retries. Each team
  rolls its own header and cache, or — more often — forgets.
- **PII redaction.** Logs leak national IDs, card numbers, and tokens until an
  auditor notices, and then every service patches its logging by hand.
- **Correlation across reactive boundaries.** A request's trace and tenant context
  must follow it through every `Mono`/`Flux` hop. On the reactive stack this is
  notoriously broken, because `ThreadLocal` and the logging MDC do **not** follow
  Reactor's operators. Teams discover this the hard way, in production, when a log
  line shows the wrong customer's ID.
- **Pagination and filtering.** Every list endpoint reinvents page/size/sort DTOs
  and ad-hoc query parameters.
- **Domain validation.** IBANs, BICs, tax IDs, card numbers — validated by
  copy-pasted regexes that are subtly wrong in three places.
- **Event publishing.** Code is welded to one broker's client, so moving from
  RabbitMQ to Kafka means a rewrite.
- **Distributed transactions.** Multi-step operations need compensation when a step
  fails; each team hand-rolls a saga, usually without recovery or a dead-letter path.
- **Resilient clients.** Every service grows its own slightly different `WebClient`
  with its own retry and circuit-breaker settings.

Now multiply that by dependency drift: dozens of independently versioned libraries
across dozens of services, no two quite aligned. The result is the **enterprise
tax** — inconsistent APIs, copy-paste boilerplate, subtle production bugs, slow
onboarding, and a fleet that is hard to reason about precisely because every member
is a little different.

The tax is insidious because no single instance of it is expensive. Hand-rolling a
404 handler costs an afternoon; so does the next team's, and the next. The cost is
in the *aggregate and the drift* — a hundred almost-identical solutions that no one
can change at once, that disagree at the edges, and that each new hire must learn
service by service. You can pay this tax forever, one service at a time. Or you can
encode the answers *once*, in a layer every service inherits. That layer is a
metaframework.

!!! note "Key term — framework vs. metaframework"
    A **framework** gives you building blocks and a place to put your code (Spring
    Boot is a framework). A **metaframework** is a framework built *on top of*
    another, adding opinions, conventions, and pre-wired cross-cutting behavior so
    that an entire fleet is consistent by default. Firefly is a metaframework on
    Spring Boot: it does not replace Spring Boot, it concentrates a fleet's worth
    of hard-won decisions into a layer you add in one line.

## What Firefly adds

Firefly answers the enterprise tax with five moves. You will spend the rest of the
book using each in anger; here is the shape of the whole.

**1 — Version coherence in one line.** A parent POM and a calendar-versioned BOM
pin Spring Boot, Spring Cloud, and ~70 framework modules into one conflict-free
set. Your services declare framework dependencies with *no version* and never
fight a dependency-convergence error again. Chapter 3 is devoted to this — and you
will see it firsthand in Chapter 2, where the quickstart's `pom.xml` lists Firefly
dependencies with the `<version>` element conspicuously absent.

**2 — One error model, everywhere.** A tiny kernel defines a single exception
hierarchy with a typed error code and an immutable context. Every module throws
into it, and the web layer turns it into a standard **RFC 7807** problem-detail
response — automatically, identically, in every service. The contrast is stark:

```java
// Vanilla Spring Boot: every service invents its own error shape, by hand.
@ExceptionHandler(LoanNotFoundException.class)
public ResponseEntity<Map<String, Object>> handle(LoanNotFoundException ex) {
    var body = Map.of("error", "not_found", "message", ex.getMessage());
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body); // shape varies per team
}
```

```java
// Firefly: throw a semantic exception; the framework emits RFC 7807 consistently.
throw new ResourceNotFoundException("LoanApplication", id);
```

The payoff is concrete and you will see it in the very next chapter: an unknown id
comes back as a 404 problem detail with a `type`, `title`, `status`, and `detail`,
plus an `extensions` object carrying the trace IDs and a remediation `suggestion` —
the *same* body shape, byte for byte, that every other Firefly service emits.

**3 — Capabilities as toggleable auto-configuration.** The hard cross-cutting
concerns — CQRS command/query buses, transport-agnostic event publishing,
Saga/TCC/Workflow orchestration, event sourcing, provider-agnostic caching,
observability with *working* Reactor context propagation — ship as Spring Boot
auto-configuration. Each capability activates when its jar is on the classpath,
is tuned by `firefly.*` properties, and **backs off the instant you define your
own bean**. You opt in by adding a dependency, and you override anything by
declaring a bean — nothing is hidden, nothing is locked.

**4 — Vendors behind ports.** Identity, content management, e-signature,
notifications, and inbound/outbound webhooks are *hexagonal* cores: you depend on
a port (an interface) and drop in a provider adapter chosen by a single property.
Swapping Keycloak for Cognito, or DocuSign for Adobe Sign, is a one-line change
instead of an SDK rewrite.

**5 — Correct services in one dependency.** Four tier-aligned **starters** —
`core`, `domain`, `data`, and `application` — bundle the right capabilities and
production-grade defaults (resilient clients, idempotency, PII masking,
`X-Transaction-Id` propagation, JSON logging, a startup banner) for each kind of
service. A companion CLI, `flywork`, scaffolds projects and bootstraps the whole
framework build. "Stand up a correct microservice" becomes "add one starter."

!!! note "Key term — reactive (Mono/Flux)"
    Throughout, Firefly is reactive end to end: handlers, repositories, buses, and
    clients all speak Project Reactor's `Mono` (zero-or-one) and `Flux`
    (zero-to-many). The prelude introduced them; Chapter 5 teaches them in full.
    The single most valuable thing Firefly does on the reactive stack is make trace
    and tenant context survive across operator boundaries — the correlation problem
    from the previous section — by enabling automatic context propagation for you.

## A superset, never a fork

It would be easy to misread all of this as "a new framework that hides Spring
Boot." It is the opposite. Firefly is a strict **superset** that *depends on,
configures, and exposes* Spring Boot — and never replaces or forks it.

- The parent POM imports the Spring Boot and Spring Cloud BOMs rather than
  extending `spring-boot-starter-parent`, so Firefly coexists with a corporate
  parent.
- Every Firefly capability is a real Spring Boot auto-configuration, gated with
  `@ConditionalOnProperty` and `@ConditionalOnMissingBean`. It activates by
  classpath presence and yields to any bean you define.
- You still write `@RestController`, `@SpringBootApplication`, `@Service`,
  `@ConfigurationProperties`; you still use Actuator, Spring Security, Spring Cloud,
  and Micrometer. Firefly's own annotations are meta-annotated Spring stereotypes
  or processed by ordinary Spring beans.
- Everything is overridable, and adoption is **additive and reversible**: add a
  starter to gain behavior, declare a bean to change it, remove the dependency to
  drop it.

That "yields to any bean you define" clause is the whole contract in miniature, and
it is not theoretical — the companion reactor leans on it directly. The domain tier
ships an `@AutoConfiguration` that registers a default in-JVM client so the service
boots standalone, *and* a live `WebClient`-backed client that takes over the moment
a core base path is configured; both step aside for the slice tests' own stub. Each
is gated with exactly the `@ConditionalOnProperty` / `@ConditionalOnMissingBean`
guards described above. You will read that wiring in Part II — for now, the point is
that "overridable by a bean" is how the framework configures *itself*, not a
courtesy bolted on for users.

In short: Firefly depends on Spring Boot, auto-configures it opinionatedly, and
exposes it transparently. You are always writing Spring Boot — just never the same
boilerplate twice.

!!! spring "Spring parity"
    Hold on to this lens for the whole book: for almost every Firefly feature there
    is a plain-Spring answer to "how would I do this myself?" — and a **Spring
    parity** callout that names it. Firefly's value is not novelty; it is that the
    answer is already wired, identical across the fleet, and reactive-correct.

## The territory: four tiers

The application you build, **Lumen Lending**, is a slice of a real core-banking
platform, and like that platform it is organized into four tiers, each backed by
one of Firefly's starters:

- **Experience (`exp`)** — the channel-facing Backend-for-Frontend. Stateless
  composition: shape requests for an app or web client, call downstream domain
  services, return lightweight DTOs. Built on `fireflyframework-starter-application`.
- **Domain** — business orchestration. Translates coarse commands into CQRS
  commands and queries, runs compensating sagas, and emits domain events. Owns no
  database; calls core services over reactive clients. Built on
  `fireflyframework-starter-domain`.
- **Core** — the system of record. Owns the schema and the data, exposes plain
  reactive CRUD and business APIs over R2DBC. Built on
  `fireflyframework-starter-core`.
- **Data** — enrichment, data quality, and lineage (for example, credit-bureau
  data). Built on `fireflyframework-starter-data`. The lending slice does not need a
  data tier, so we meet it on its own in Chapter 15, where it plugs in.

Tiers never share a database; they talk over HTTP through reactive client seams.
That single rule — *integrate over contracts, not over a shared schema* — is what
lets a fleet evolve without every change rippling everywhere. The dependency
direction is strictly one-way, **exp → domain → core**: the experience tier knows
the domain, the domain knows the core, and nothing points back upstream.

!!! note "Key term — tier and starter"
    A **tier** is a role in the architecture — experience, domain, core, or data —
    and each tier has exactly one matching Firefly **starter** that bundles the
    capabilities that role needs. Picking a starter is picking a tier: a `core`
    service inherits persistence and CRUD defaults, a `domain` service inherits CQRS
    and orchestration, an `application` (experience) service inherits the BFF stack.
    Chapter 2 scaffolds one of these from the matching `flywork` archetype.

!!! spring "Spring parity"
    A Firefly tier starter is a Spring Boot starter, the same mechanism as
    `spring-boot-starter-webflux` — a curated dependency that pulls in a coherent set
    and triggers auto-configuration. The difference is altitude: a vanilla starter
    wires *one* capability (the web stack); a Firefly tier starter wires the *whole*
    opinionated baseline for a kind of service. Same machinery, more in the box.

## What you will build

Lumen Lending is not a diagram in this book — it is a running reactor you boot on
your own machine, with **no Docker, no external database, and no message broker**.
All three tiers exist today and run end to end: persistence is in-memory **H2**
(with a Flyway migration), and events flow over the in-JVM `APPLICATION_EVENT`
transport. Each tier runs with `mvn spring-boot:run` from its module directory, or
as a self-contained executable `java -jar` (Spring Boot repackaging is wired) — the
**core** on port 8081, the **domain** on 8082, and the **experience** BFF on 8080.
The exact commands live in `samples/lumen-lending/README.md`.

The proof that the tiers compose is a single live request. A channel `POST` to the
experience BFF —

```text
POST http://localhost:8080/api/v1/experience/lending/applications
```

— returns `201 Created` with a `status` of `SUBMITTED`, having flowed the whole way
down: the experience tier calls the domain over HTTP, the domain runs the
`RegisterApplicationSaga`, the saga's root step writes to the core system of record
over HTTP, and the core-assigned id comes back through both seams. You can then read
the very same record straight out of core on port 8081 and watch the saga's steps
log on the domain tier. That end-to-end submit is the spine of everything that
follows; Chapter 2 takes you to it one tier at a time.

By the last page, Lumen Lending lets a customer **apply** for a personal loan, get
**scored**, receive a **decision**, review **offers**, and **accept** one — flowing
from the experience tier, through a domain saga, into the core system of record,
emitting events along the way. You will build it tier by tier, and every line you
read is a verbatim slice of the companion reactor, verified by the build (33 tests
across the reactor — 18 in core, 6 in domain, 9 in experience — all green).

!!! note "Honesty — what the lending slice does and does not exercise"
    The book builds a deliberately *minimal but real* vertical, and it says so each
    time. The live domain → core mapping is intentionally thin — the trimmed write
    seam carries the applicant and amount, so a few core fields (currency, term,
    purpose) land as defaults; richer mapping is the job of the generated SDK
    (Chapter 7). Several heavyweight capabilities are taught *where they plug in*
    rather than forced into the slice: the rule engine (Chapter 13), the data tier
    (Chapter 15), and event sourcing (Chapter 12). When a feature is illustrative
    rather than wired into the running sample, the text will say so plainly.

But first you need it running. Chapter 2 takes you from an empty folder to a
booting Firefly service in a few minutes — so the rest of the book has something to
grow.

## What you learned {.recap}

- Spring Boot makes one service easy; a *fleet* of consistent reactive services is
  a different, unsolved problem — the **enterprise tax** of re-implemented
  cross-cutting plumbing and dependency drift, whose cost lives in the aggregate and
  the drift, not in any single instance.
- Firefly answers it as a **metaframework**: version coherence via parent + BOM, one
  RFC 7807 error model, capabilities as toggleable auto-configuration, vendors
  behind one-property ports, and correct services from a single tier starter.
- Firefly is a **strict superset** of Spring Boot — depends on it, configures it,
  exposes it; every bean overridable; adoption additive and reversible. The
  framework even configures *itself* this way, with `@ConditionalOnProperty` /
  `@ConditionalOnMissingBean` guards.
- The book builds **Lumen Lending** across four tiers — experience, domain, core,
  data — that integrate over contracts, never a shared database, in the strict
  direction **exp → domain → core**.
- All three lending tiers run today with **no Docker**: core on 8081, domain on
  8082, experience on 8080, with a proven live `exp → domain (saga) → core` submit
  that returns `201 SUBMITTED` and persists in the core system of record.

## Try it yourself {.exercises}

1. **Audit your own tax.** List the cross-cutting concerns from "The enterprise
   tax" that your current services each implement separately. For how many do all
   your services agree on the exact behavior?
2. **Find the disagreement.** Pick two services you work on and compare the JSON
   shape of a 404 and a validation error. Are they identical? Would a client need
   per-service handling?
3. **Spot the leak.** Search a recent log file for anything that should have been
   masked — an email, an ID, a token. How is masking enforced today?
4. **Trace a request.** Does a correlation or trace ID in your services survive
   across an async or reactive boundary into a downstream call's logs? Try to follow
   one end to end.
5. **Map the tiers.** Open `samples/lumen-lending/README.md` and find which port
   each tier serves on and which starter it is built on. Predict, before Chapter 2,
   what the dependency direction `exp → domain → core` means for which tier can boot
   without the others running.

## Where to go next

Chapter 2 scaffolds and boots your first Firefly service — the **core** tier on port
8081 — then drives its loan-origination API by hand and proves it with the reactor's
own tests. If the reactive `Mono`/`Flux` references above felt fast, that is by
design — Chapter 5 is the keystone that teaches the reactive model in full, and the
prelude has enough to carry you until then.
