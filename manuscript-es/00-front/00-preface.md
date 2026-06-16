## Preface

Spring Boot solved a real problem. Before it, standing up a Java service meant
hand-assembling a servlet container, a JSON mapper, a validation provider, a
data layer, and a dozen other moving parts — every team a little differently.
Spring Boot replaced that ceremony with *convention over configuration*: add a
starter, get a working slice. But Spring Boot is deliberately unopinionated
about the next problem — building a **fleet** of consistent, production-grade,
*reactive* microservices. Each service still re-invents the same cross-cutting
plumbing: error shapes that never quite agree, idempotency, PII redaction,
correlation IDs that vanish across reactive thread boundaries, pagination and
filtering DTOs, validation of IBANs and tax IDs, event publishing welded to one
broker, sagas hand-rolled per project, and N subtly different copies of
`WebClient` resilience config. Multiply that by dependency drift across dozens
of independently versioned libraries and you get the enterprise tax: inconsistent
APIs, copy-paste boilerplate, subtle production bugs, and slow onboarding.

**The Firefly Framework** is the answer to *that* problem. It is a metaframework
*on top of* Spring Boot — a curated, opinionated, batteries-included superset for
reactive microservices. A parent POM and a calendar-versioned BOM pin Spring Boot,
Spring Cloud, and ~70 framework modules into one conflict-free set. A shared
kernel gives every service one error model, surfaced as RFC 7807 everywhere.
Capability modules — CQRS, event-driven messaging, Saga/TCC orchestration, event
sourcing, caching, observability with *working* Reactor context propagation —
ship as toggleable, overridable Spring auto-configuration. Hexagonal integration
cores turn a vendor swap (Kafka↔RabbitMQ, Keycloak↔Cognito, DocuSign↔Adobe Sign)
into a one-property change. Four tier-aligned starters turn "stand up a correct
microservice" into adding a single dependency. Crucially, Firefly never forks
Spring Boot or hides it: you still write `@RestController`, `@SpringBootApplication`,
`@ConfigurationProperties`; every Firefly bean is overridable; adoption is additive
and reversible.

This book teaches Firefly **by doing**. You build one real application from an
empty folder to a secured, observable, event-driven, three-tier system. And the
code in these pages is not illustrative pseudocode: every listing is a **verbatim
slice** of a companion Maven reactor that compiles, boots, and passes its tests
in continuous integration. When prose drifts from the source, the build fails.
What you read is what actually works.

### Who This Book Is For

This book is for Java developers who want to build serious backend systems and
want one coherent way to do it. You should be comfortable with modern Java
(records, generics, lambdas) and the basics of HTTP services. You need *no* prior
Firefly experience, and you do not need to be a reactive expert — a front-matter
prelude brings you up to speed on Spring Boot, WebFlux, Project Reactor, and
R2DBC, and a dedicated keystone chapter teaches the reactive model from first
principles before any framework feature relies on it.

If your last Spring was MVC and JPA, you are welcome here; *Coming from Spring MVC*
notes ease the jump to the reactive stack. If you arrive from another Firefly port
such as PyFly, you will recognize the shape and can move quickly.

### What You Will Build

Every chapter advances **Lumen Lending**, a personal-loan origination service
modeled on a real core-banking platform. The narrative follows one natural user
story — *apply → get scored → get a decision → review offers → accept an offer* —
and the journey follows a deliberate arc:

- **Part I — Foundations.** You learn *why* a metaframework on Spring Boot exists,
  scaffold and run your first service, make the whole stack version-coherent with
  the parent and BOM, bind typed configuration, and master the reactive
  Mono/Flux model that everything else stands on.
- **Part II — Modeling & Persisting.** You expose your first reactive HTTP API
  with finance-grade validation and RFC 7807 errors, generate a typed SDK,
  persist the loan application with R2DBC and Flyway, and model a rich domain
  aggregate with a `Money` value object.
- **Part III — CQRS, EDA & Decisioning.** You split writes from reads with a
  command/query bus, raise and route domain events (in-JVM, then over Kafka),
  optionally event-source a ledger, and meet the reactive rule engine where
  automated credit decisioning *belongs*.
- **Part IV — The Four Tiers.** You split the system into experience, domain,
  core, and data tiers communicating over generated SDKs; wire resilient,
  multi-protocol service clients; build the BFF; and orchestrate the headline
  **registration saga** with parallel fan-out and automatic compensation.
- **Part V — Secure · Observe · Ship.** You secure the endpoints, cache and
  harden them, make the system observable, connect it to the outside world with
  documents, scheduling, notifications, webhooks and callbacks, test the whole
  stack, and extend and ship it to production.

By the last page you have a working, tested, observable, secured, multi-tier
service — and the mental model to extend it.

### How to Use This Book

**Read sequentially.** Each chapter builds on the one before, and the Lumen
Lending codebase grows incrementally; skipping ahead leaves gaps.

**Type every listing yourself.** Reading and typing at the same time is how the
patterns stick. Resist copy-pasting until you have written each listing once.

**Run it.** Lumen Lending really runs. Whenever a chapter adds a feature, start
the service or its tests and watch it work — `mvn verify` boots the reactor and
exercises it. Seeing real JSON come back from a real endpoint is worth a hundred
diagrams.

Each chapter closes with a **Recap** of what changed and a set of **Exercises**
that push one step further. The exercises are optional but recommended for
anything you intend to apply immediately.

### Conventions in Brief

Typographic and structural conventions — code-listing captions, the callout types
(including the **Spring parity** callout that maps each Firefly idea back to plain
Spring Boot), and figure numbering — are demonstrated with live examples in the
**Conventions** section that follows.

### The Companion Code

The complete, runnable project lives in this repository's `samples/lumen-lending`
directory: a layered Maven reactor — `core-lending-loan-origination`,
`domain-lending-loan-origination`, `exp-lending` — that you grow chapter by
chapter. The finished source there is the destination this book walks you to.
Build it once, and use it to compare your work, catch up if you fall behind, or
simply run the parts you are reading about.

### What This Book Is *Not*

This is a book about the **Java** Firefly Framework. The framework has sibling
ports in Python (PyFly), Rust, Go, and .NET, a Python agentic metaframework with
its `agentic-bridge`, and frontend frameworks — these are parallel projects with
their own documentation and are **out of scope** here. Where Firefly relies on
Spring Boot, Spring Cloud, or Project Reactor, this book teaches just enough to
use it well; it is not a comprehensive reference for those platforms or for every
one of the framework's ~70 modules. It is a guided path from zero to a real,
multi-tier reactive service — and the foundation to go anywhere from there.
