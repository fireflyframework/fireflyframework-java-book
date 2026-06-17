Terms are defined as this book uses them — in the context of the Java Firefly
Framework and reactive Spring Boot. Where a term has a broader meaning elsewhere,
the definition here is the practical one you need for Lumen Lending. Where a
capability is described but *not* wired into the runnable sample, the entry says
so plainly and points at where it would plug in.

**Actuator.** Spring Boot's set of production endpoints — `/actuator/health`,
`/actuator/info`, metrics, and more. Firefly's starter turns the right ones on by
default and contributes health indicators for the subsystems it wired (CQRS buses,
EDA, R2DBC), so every service in the fleet is observable the same way. A green
top-level `"status":"UP"` is the first proof of life; in the sample it is served at
`:8081/actuator/health` (core), `:8082` (domain), and `:8080` (experience).

**Aggregate.** In domain-driven design, a cluster of objects treated as a single
unit for data changes, with one *aggregate root* (here, `LoanApplication`) that
enforces the cluster's invariants. External code only ever touches the root.

**Auto-configuration.** A Spring Boot mechanism: configuration classes (annotated
`@AutoConfiguration`, registered in `META-INF/spring/...AutoConfiguration.imports`)
that activate based on what is on the classpath and what beans already exist,
applying sensible defaults and backing off when you define your own. Every Firefly
capability ships as auto-configuration gated by `@ConditionalOnProperty` and
`@ConditionalOnMissingBean` — which is why "add a dependency" equals "turn on a
capability," and why the sample's entry points stay empty.

**Backpressure.** A reactive stream's ability to signal that it cannot keep up, so
a fast producer does not overwhelm a slow consumer. Built into Project Reactor;
you rarely manage it by hand, but it is why `Flux` is safe to use over a network.

**Base-path.** A configuration property naming the root URL of a downstream
service, from which a tier builds its `WebClient`. In the sample the experience
tier reads `lumen.exp.loan-origination.base-path` to reach the domain at
`http://localhost:8082`, and the domain reads
`firefly.lumen.core.loan-origination.base-path` to reach the core at
`http://localhost:8081`. Setting (or omitting) a base path is what flips the live
HTTP client on or off via `@ConditionalOnProperty`.

**Bean.** An object created and managed by the Spring application context. You
declare beans with stereotypes (`@Component`, `@Service`, …) and receive them by
constructor injection. Defining your own bean is also how you *override* a Firefly
default, since the auto-configuration is gated by `@ConditionalOnMissingBean`.

**BFF (Backend-for-Frontend).** A service that exists to serve one class of client
(a mobile app, a web app), shaping and aggregating downstream data for it. In
Firefly this is the *experience* tier, built on `starter-application`; in the
sample it is `exp-lending` on port `8080`.

**BOM (Bill of Materials).** A POM that contains only dependency *version*
management. Importing the `fireflyframework-bom` lets your services declare
framework dependencies without versions, all guaranteed to agree. The sample pins
framework `26.06.01` this way.

**Command (CQRS).** An instruction to change state (e.g. `RegisterLoanApplication`).
Dispatched on the `CommandBus` to exactly one handler. Contrast with a *query*.

**Command/Query bus.** The Firefly component that routes a command or query to its
single registered handler, applying validation, authorization, metrics, and tracing
around it automatically. On boot the buses report how many handlers they found
(`DefaultCommandBus ready with N registered handlers`).

**Compensation.** In a saga, the action that undoes a previously completed step when
a later step fails — the distributed-systems substitute for a transaction rollback.
In the sample the core exposes an idempotent `DELETE /api/v1/loan-applications/{id}`
so the saga's root step can compensate a registered application.

**Correlation ID.** An identifier attached to a request and propagated through every
hop (logs, downstream calls, events) so one logical operation can be traced end to
end. Firefly carries it across reactive boundaries for you; in the JSON logs it
surfaces as `traceId`/`spanId`. See also *ExecutionContext* and *X-Transaction-Id*.

**Core tier.** The system-of-record services that own the database and expose plain
reactive CRUD and business APIs. Built on `starter-core` + `fireflyframework-r2dbc`;
in the sample, `core-lending-loan-origination` on port `8081`, persisting loan
applications to in-memory H2 over R2DBC with Flyway-managed schema.

**CQRS (Command Query Responsibility Segregation).** Separating the write path
(commands) from the read path (queries) so each can be modeled, scaled, and cached
independently. The query bus can be backed by `fireflyframework-cache`.

**Data tier.** Services dedicated to enrichment, data quality, and lineage (for
example, credit-bureau data). Built on `starter-data`. *Not wired in the sample* —
the lending reactor ships only core, domain, and experience; the data tier is
described as where bureau/enrichment services would plug in.

**Domain event.** A record that something meaningful happened in the domain
(`LoanApplicationRegistered`). Published through the EDA layer; consumed by listeners
and other services.

**Domain tier.** The orchestration services that turn coarse business operations into
commands, queries, and sagas, owning no database and calling core services over SDK
seams. Built on `starter-domain`; in the sample, `domain-lending-loan-origination`
on port `8082`, where `RegisterApplicationSaga` runs.

**EDA (Event-Driven Architecture).** A style in which services communicate by
publishing and consuming events rather than calling each other directly. Firefly's
EDA layer is transport-agnostic (in-JVM, Kafka, RabbitMQ, Postgres). The sample runs
the in-JVM `APPLICATION_EVENT` transport — no broker, no Docker; Kafka and the others
are how it would scale out, not what the sample uses.

**EventPublisher.** The Firefly abstraction a service injects to emit a domain event
without knowing the transport. The same `EventPublisher` call routes to in-JVM
delivery in the sample or to Kafka/RabbitMQ/Postgres once that transport is
configured — the publishing code does not change.

**Event sourcing.** Persisting an aggregate as the ordered sequence of events that
happened to it, and rebuilding its state by replaying them — rather than storing
only the latest snapshot. *Not wired in the sample*: the core persists current state
as rows in H2. Event sourcing is presented as how it works and where it would plug
in (an event store feeding *projections*).

**ExecutionContext.** The Firefly object that carries per-request identity and
multi-tenant data (user, tenant, organization, session, request IDs) through CQRS
handlers, so behavior and isolation are tenant-aware. Propagated across reactive
boundaries together with the correlation ID.

**Experience tier.** See *BFF*.

**Executable jar (repackage).** A self-contained "fat" jar produced by the Spring
Boot Maven plugin's `repackage` goal, runnable with `java -jar`. The sample wires
this so each module runs identically via `mvn spring-boot:run` or
`java -jar target/<module>.jar`.

**Flux.** A Project Reactor publisher of **zero to many** items, asynchronously and
with backpressure. The reactive analog of a stream of values.

**Flyway.** A database migration tool. Versioned SQL scripts (`V1__….sql`) bring any
database to a known schema. In the sample the core tier runs Flyway over JDBC against
H2 at startup, while runtime reads/writes go over R2DBC.

**Framework / metaframework.** A *framework* gives you building blocks and a place
for your code (Spring Boot). A *metaframework* is built on top of one, adding opinions
and pre-wired cross-cutting behavior so a fleet is consistent by default (Firefly).

**Hexagonal architecture (ports & adapters).** Designing a component around an
interface (the *port*) with interchangeable implementations (the *adapters*).
Firefly's integration cores (IDP, ECM, notifications) work this way: depend on the
port, choose an adapter by one property. The sample's `LoanOriginationClient` seam is
a port with two adapters — a live `WebClient` adapter and a test recording stub.

**Idempotency.** The property that performing an operation more than once has the same
effect as once. Firefly's web layer deduplicates retried writes by an
`X-Idempotency-Key` header; the sample logs the `IdempotencyWebFilter` inspecting each
write. The core's compensating `DELETE` is also idempotent by design.

**Mono.** A Project Reactor publisher of **at most one** item (or an error, or
nothing), asynchronously. The reactive analog of a single future value.

**PII masking.** Automatically redacting personally identifiable information (national
IDs, card numbers, tokens) from logs. Firefly applies it across the fleet so sensitive
fields never reach a log aggregator in the clear.

**Port.** See *hexagonal architecture*.

**Projection.** A read model built by consuming events — a query-optimized view of
state, kept up to date as events arrive. Pairs with *event sourcing*; *not wired in
the sample*, presented as how the read side would be built.

**Query (CQRS).** A request to read state without changing it. Dispatched on the
`QueryBus`; results may be cached.

**R2DBC (Reactive Relational Database Connectivity).** The non-blocking, reactive
alternative to JDBC. Repositories return `Mono`/`Flux`; required to keep the data
layer off the event loop's critical path. The sample's core tier uses the H2 R2DBC
driver at runtime (moved from `test` to `runtime` scope so it boots on H2 with no
external database), and the H2 JDBC driver only for Flyway.

**Reactive.** A programming model built on asynchronous streams (`Mono`/`Flux`) with
backpressure, where you describe *what to do when values arrive* rather than block
waiting for them.

**Reactor (Project Reactor).** The reactive-streams library underneath Spring WebFlux,
providing `Mono` and `Flux` and the operator vocabulary (`map`, `flatMap`, `zip`, …).
Firefly also enables Reactor automatic context propagation so `ThreadLocal`/MDC values
(including the trace context) bridge across thread boundaries.

**RFC 7807 (Problem Details).** The IETF standard JSON shape for HTTP error responses
(`type`, `title`, `status`, `detail`, `instance`), served as
`application/problem+json`. Firefly's `GlobalExceptionHandler` emits it automatically
and identically for every service, tucking framework extras — `traceId`/`spanId`, a
`retryable` hint, a remediation `suggestion`, a `category` — into the standard
`extensions` object. (Spring 6 ships `ProblemDetail`; Firefly's contribution is making
the contract fleet-wide with no per-service `@ControllerAdvice`.)

**Saga.** A sequence of local steps across services that together achieve a business
outcome, with *compensation* to undo completed steps if a later one fails. The
sample's `RegisterApplicationSaga` runs in the domain tier: its root step writes to
the core over HTTP, then two dependent steps complete in-process. Firefly's
orchestration engine also offers *workflows* and *TCC*.

**SDK seam / generated SDK.** The interface a tier uses to call the tier below over
HTTP — never a shared database. In a full Firefly service this is a typed, reactive
client *generated* from the downstream OpenAPI spec. The sample hand-writes a trimmed
seam (`LoanOriginationClient`, with a `WebClient`-backed live adapter) standing in for
that generated SDK; the richer field mapping is explicitly left to the real generated
client.

**StepVerifier.** A `reactor-test` utility that subscribes to a `Mono`/`Flux` in a
test and asserts the exact sequence of items, errors, and completion signals — the
reactive equivalent of asserting on a return value.

**Starter.** A curated dependency that brings everything for one capability or tier,
triggering its auto-configuration. Firefly's tier starters are `starter-core`,
`starter-domain`, `starter-data`, and `starter-application`; a Firefly starter wires a
whole opinionated baseline rather than a single capability.

**TCC (Try-Confirm-Cancel).** A distributed-transaction pattern with stronger
consistency than a saga: each participant first *tries* (reserves), then all *confirm*
or all *cancel*. Offered by Firefly's orchestration engine; *not exercised in the
sample*, which uses a saga.

**Testcontainers.** A library that spins up real services (databases, brokers) in
throwaway Docker containers for integration tests. The lending sample deliberately
does *not* use it — its tests run against in-memory H2 and the in-JVM EDA transport so
they need no Docker; Testcontainers is the path you reach for when a test must exercise
a real Postgres or Kafka.

**Tier.** One of the four architectural layers — experience, domain, core, data — each
backed by its own Firefly starter, integrating over contracts rather than a shared
schema. The sample wires three of the four (experience → domain → core).

**WebClient.** Spring WebFlux's non-blocking HTTP client. Firefly tiers use it to call
the tier below: the sample's experience tier builds a `WebClient` from its base-path to
call the domain, and the domain builds one to call the core. It is the concrete
adapter behind the SDK seam.

**WebFlux.** Spring's reactive web stack, the non-blocking counterpart to Spring MVC.
Controllers return `Mono`/`Flux` and run on a Netty event loop (`Netty started on
port …` in the boot log).

**Workflow (orchestration).** A fire-forward sequence of steps with no automatic
rollback — the simplest of Firefly's three orchestration patterns (workflow, saga,
TCC).

**X-Idempotency-Key.** The request header Firefly's `IdempotencyWebFilter` reads to
deduplicate retried writes, so a client that re-sends a `POST` after a timeout does not
create a second resource. See *idempotency*.

**X-Transaction-Id.** A header Firefly propagates across service calls to stitch a
multi-service operation together in logs and traces. Where none is supplied, the
`TransactionFilter` generates one (`Generated new transaction ID: …` in the boot log).
