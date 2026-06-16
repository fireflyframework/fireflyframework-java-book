Terms are defined as this book uses them — in the context of the Java Firefly
Framework and reactive Spring Boot. Where a term has a broader meaning elsewhere,
the definition here is the practical one you need for Lumen Lending.

**Aggregate.** In domain-driven design, a cluster of objects treated as a single
unit for data changes, with one *aggregate root* (here, `LoanApplication`) that
enforces the cluster's invariants. External code only ever touches the root.

**Auto-configuration.** A Spring Boot mechanism: configuration classes that
activate based on what is on the classpath and what beans already exist, applying
sensible defaults and backing off when you define your own. Every Firefly
capability ships as auto-configuration gated by `@ConditionalOnProperty` and
`@ConditionalOnMissingBean`.

**Backpressure.** A reactive stream's ability to signal that it cannot keep up,
so a fast producer does not overwhelm a slow consumer. Built into Project Reactor;
you rarely manage it by hand, but it is why `Flux` is safe over a network.

**Bean.** An object created and managed by the Spring application context. You
declare beans with stereotypes (`@Component`, `@Service`, …) and receive them by
constructor injection.

**BFF (Backend-for-Frontend).** A service that exists to serve one class of client
(a mobile app, a web app), shaping and aggregating downstream data for it. In
Firefly this is the *experience* tier, built on `starter-application`.

**BOM (Bill of Materials).** A POM that contains only dependency *version*
management. Importing the `fireflyframework-bom` lets your services declare
framework dependencies without versions, all guaranteed to agree.

**Command (CQRS).** An instruction to change state (e.g. `RegisterLoanApplication`).
Dispatched on the `CommandBus` to exactly one handler. Contrast with a *query*.

**Command/Query bus.** The Firefly component that routes a command or query to its
single registered handler, applying validation, authorization, metrics, and
tracing around it automatically.

**Compensation.** In a saga, the action that undoes a previously completed step
when a later step fails — the distributed-systems substitute for a transaction
rollback (e.g. `removeLoanApplication` compensating `registerLoanApplication`).

**CQRS (Command Query Responsibility Segregation).** Separating the write path
(commands) from the read path (queries) so each can be modeled, scaled, and cached
independently.

**Core tier.** The system-of-record services that own the database and expose plain
reactive CRUD and business APIs. Built on `starter-core` + `fireflyframework-r2dbc`.

**Correlation ID.** An identifier attached to a request and propagated through every
hop (logs, downstream calls, events) so one logical operation can be traced
end to end. Firefly carries it across reactive boundaries for you.

**Data tier.** Services dedicated to enrichment, data quality, and lineage (for
example, credit-bureau data). Built on `starter-data`.

**Domain event.** A record that something meaningful happened in the domain
(`LoanApplicationRegistered`). Published through the EDA layer; consumed by
listeners and other services.

**Domain tier.** The orchestration services that turn coarse business operations
into commands, queries, and sagas, owning no database and calling core services
over SDKs. Built on `starter-domain`.

**EDA (Event-Driven Architecture).** A style in which services communicate by
publishing and consuming events rather than calling each other directly. Firefly's
EDA layer is transport-agnostic (in-JVM, Kafka, RabbitMQ, Postgres).

**ExecutionContext.** The Firefly object that carries per-request identity and
multi-tenant data (user, tenant, organization, session, request IDs) through CQRS
handlers, so behavior and isolation are tenant-aware.

**Event sourcing.** Persisting an aggregate as the ordered sequence of events that
happened to it, and rebuilding its state by replaying them — rather than storing
only the latest snapshot.

**Experience tier.** See *BFF*.

**Flux.** A Project Reactor publisher of **zero to many** items, asynchronously and
with backpressure. The reactive analog of a stream of values.

**Flyway.** A database migration tool. Versioned SQL scripts (`V1__…sql`) bring any
database to a known schema; Firefly's R2DBC layer bundles it.

**Framework / metaframework.** A *framework* gives you building blocks and a place
for your code (Spring Boot). A *metaframework* is built on top of one, adding
opinions and pre-wired cross-cutting behavior so a fleet is consistent by default
(Firefly).

**Hexagonal architecture (ports & adapters).** Designing a component around an
interface (the *port*) with interchangeable implementations (the *adapters*).
Firefly's integration cores (IDP, ECM, notifications) work this way: depend on the
port, choose an adapter by one property.

**Idempotency.** The property that performing an operation more than once has the
same effect as once. Firefly's web layer deduplicates retried writes by an
`X-Idempotency-Key`.

**Mono.** A Project Reactor publisher of **at most one** item (or an error, or
nothing), asynchronously. The reactive analog of a single future value.

**PII masking.** Automatically redacting personally identifiable information
(national IDs, card numbers, tokens) from logs. Firefly applies it across the fleet.

**Port.** See *hexagonal architecture*.

**Projection.** A read model built by consuming events — a query-optimized view of
state, kept up to date as events arrive.

**Query (CQRS).** A request to read state without changing it. Dispatched on the
`QueryBus`; results may be cached.

**R2DBC (Reactive Relational Database Connectivity).** The non-blocking, reactive
alternative to JDBC. Repositories return `Mono`/`Flux`; required to keep the data
layer off the event loop's critical path.

**Reactive.** A programming model built on asynchronous streams (`Mono`/`Flux`) with
backpressure, where you describe *what to do when values arrive* rather than block
waiting for them.

**RFC 7807 (Problem Details).** A standard JSON shape for HTTP error responses
(`type`, `title`, `status`, `detail`, …). Firefly emits it automatically and
identically for every service.

**Saga.** A sequence of local steps across services that together achieve a
business outcome, with *compensation* to undo completed steps if a later one fails.
Firefly's orchestration engine also offers *workflows* and *TCC*.

**SDK (generated).** A typed, reactive client generated from a service's OpenAPI
spec, used by the tier above to call it over HTTP — never a shared database.

**StepVerifier.** A `reactor-test` utility that subscribes to a `Mono`/`Flux` in a
test and asserts the exact sequence of items, errors, and completion signals.

**Starter.** A curated dependency that brings everything for one capability or tier.
Firefly's tier starters are `starter-core`, `starter-domain`, `starter-data`, and
`starter-application`.

**TCC (Try-Confirm-Cancel).** A distributed-transaction pattern with stronger
consistency than a saga: each participant first *tries* (reserves), then all
*confirm* or all *cancel*.

**Tier.** One of the four architectural layers — experience, domain, core, data —
each backed by its own Firefly starter, integrating over contracts rather than a
shared schema.

**WebFlux.** Spring's reactive web stack, the non-blocking counterpart to Spring
MVC. Controllers return `Mono`/`Flux` and run on an event loop.

**Workflow (orchestration).** A fire-forward sequence of steps with no automatic
rollback — the simplest of Firefly's three orchestration patterns (workflow, saga,
TCC).

**X-Transaction-Id.** A header Firefly propagates across service calls to stitch a
multi-service operation together in logs and traces.
