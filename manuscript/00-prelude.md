The chapters ahead assume you can read modern Spring Boot comfortably. This
prelude makes sure you can — even if your last Spring was MVC and JPA, or you
arrive from another Firefly port such as PyFly. It is **not** a Spring course.
It teaches exactly the slice of Spring Boot, WebFlux, Project Reactor, and R2DBC
that the later chapters lean on, in roughly the order they lean on it. Each
section ends with a short *Coming from…* note that translates the idea from where
you might be standing today.

If you already ship reactive Spring Boot daily — WebFlux controllers, R2DBC
repositories, `Mono`/`Flux` in your sleep — skip ahead to Chapter 1. Nothing here
will surprise you. If reactive is the unfamiliar part, read on; the on-ramp below
is built precisely for you, and the one rule that matters most ("never block") is
called out in its own box so you cannot miss it.

A note on depth before we begin. The reactive model is the one idea everything
else stands on, so this prelude introduces it just far enough to read the early
chapters, and Chapter 5 then teaches it properly, operator by operator. You will
meet `Mono` and `Flux` three times at increasing depth — here, in passing; in
Chapter 1, as a key term; and in Chapter 5, in full. That repetition is
deliberate: each pass assumes a little more than the last, so by Chapter 5 the
vocabulary is already familiar and the chapter can spend its budget on the hard
parts (schedulers, backpressure, context propagation) rather than on
re-introducing the types.

One more reassurance up front. The companion project this book is built on — the
**Lumen Lending** reactor under `samples/lumen-lending` — runs end to end on your
laptop with **no Docker, no external database, and no message broker**. The data
tier is in-memory H2, and events travel over an in-JVM transport. So every snippet
you are about to read in the abstract becomes a service you can actually boot a
chapter or two later, with a single `mvn spring-boot:run`. Keep that in mind as you
read: none of this is theory you have to take on faith.

## Maven and the POM

Firefly is built and consumed with **Maven**. A Maven project is described by a
`pom.xml` — the Project Object Model — which declares the project's coordinates
(group, artifact, version), its dependencies, and how it is built. You rarely run
`javac` yourself; you run `mvn verify`, and Maven compiles, runs tests, and
packages a runnable JAR.

Two ideas from Maven matter throughout this book. First, **dependency
management**: rather than pin a version on every dependency, a project inherits a
*parent* POM or imports a *BOM* (Bill of Materials) that pins versions centrally,
so your own `<dependency>` entries can omit `<version>` and still agree. Second,
**starters**: a starter is a curated dependency that pulls in everything needed
for one capability. Adding `spring-boot-starter-webflux` brings the reactive web
stack — server, JSON, validation — in a single line.

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>
```

Notice what is *missing*: no `<version>`. The version is resolved for you by an
inherited parent — for a Spring Boot project, `spring-boot-starter-parent`; for a
Firefly project, Firefly's own parent, which in turn imports Spring Boot's BOM
plus Firefly's. That is why a whole fleet of services can agree on one set of
library versions without anyone editing a hundred POMs: the versions live in one
place, upstream, and every service inherits them.

Chapter 3 is devoted to how Firefly's parent POM and BOM make an entire fleet of
services version-coherent. For now, just hold the shape: *inherit a parent, add a
starter, omit versions.* You will watch that exact shape pay off in Chapter 2,
where the quickstart service's `pom.xml` lists Firefly dependencies with no version
numbers at all and still builds.

!!! note "Coming from PyFly or Python"
    The `pom.xml` is the rough analog of `pyproject.toml`; a Maven *starter* is
    like an extras group that installs a coherent set of packages; the parent/BOM
    is the dependency-locking story (your `poetry.lock` or `requirements.txt`,
    but resolved by inheritance rather than a lockfile). `mvn verify` is your
    `pytest` plus a build — compile, test, and package in one command.

## Spring Boot in one breath

**Spring Boot** turns "assemble a Java service from parts" into "add a starter and
run." A Spring Boot application is an ordinary Java class with one annotation and a
`main` method:

```java
@SpringBootApplication
public class LumenApplication {
    public static void main(String[] args) {
        SpringApplication.run(LumenApplication.class, args);
    }
}
```

`SpringApplication.run(...)` boots the **application context** — Spring's container
of objects — starts an embedded web server, and wires everything together. Three
mechanisms do almost all the work:

- **Auto-configuration.** Spring Boot inspects the classpath and configures sensible
  defaults: see WebFlux on the classpath, get a reactive web server; see R2DBC, get
  a reactive `ConnectionFactory`. Every auto-configuration backs off the moment you
  define your own bean, so defaults are a starting point, never a cage.
- **Externalized configuration.** Settings live in `application.yml` (or
  `application.properties`), layered by *profile* (`dev`, `prod`, …) and overridable
  by environment variables — so the same JAR runs everywhere.
- **Actuator.** A set of production endpoints — health, metrics, info — that you get
  by adding a dependency.

It is worth being precise about *when* this happens, because it explains the empty
entry point you will keep seeing. `@SpringBootApplication` bundles
`@EnableAutoConfiguration`, and at startup Spring reads the auto-configuration
entries every dependency ships in `META-INF`, evaluates each one's conditions
against your classpath and your beans, and applies only the ones that fit. So
"adding a dependency" and "turning on a behavior" are the same act — a property of
Spring Boot that Firefly leans on heavily. You will see this in motion, line by
line, in Chapter 2's boot log.

You bind configuration to typed objects with `@ConfigurationProperties`, so
settings are read once into an immutable object rather than fished out of a map by
string key:

```java
@ConfigurationProperties(prefix = "lumen.lending")
public record LendingProperties(int maxTermMonths, String defaultCurrency) {}
```

```yaml
lumen:
  lending:
    max-term-months: 84
    default-currency: EUR
```

_(That record is an illustrative sketch of the pattern; the companion reactor uses
the same `@ConfigurationProperties` mechanism for its real settings — for example
the experience tier binds its downstream client's base URL and timeouts this way.)_

Firefly is, at bottom, *more Spring Boot* — more auto-configuration, more starters,
more conventions — and Chapter 1 explains exactly what it adds and why.

!!! spring "Coming from Spring MVC"
    Everything above is identical to what you know — `@SpringBootApplication`,
    `application.yml`, profiles, Actuator, `@ConfigurationProperties` all carry over
    unchanged. The one thing that differs is the web and data stack underneath,
    which is *reactive* rather than servlet-based. That difference is the subject
    of the next four sections.

## Beans and dependency injection

Spring builds your objects for you and hands them their collaborators. A class
annotated as a component — `@Component`, or the more specific `@Service`,
`@Repository`, `@RestController` — becomes a **bean** managed by the application
context. You declare what a bean needs as constructor parameters, and Spring
*injects* the matching beans:

```java
@Service
public class LoanApplicationService {
    private final LoanApplicationRepository repository;

    public LoanApplicationService(LoanApplicationRepository repository) {
        this.repository = repository;   // injected by Spring
    }
}
```

Prefer constructor injection (shown here) over field injection: it makes
dependencies explicit, keeps fields `final`, and makes the class trivial to unit
test by passing fakes directly — no Spring context required. In the companion
reactor you will often see this written even more tersely with Lombok's
`@RequiredArgsConstructor`, which generates exactly the constructor above from the
`final` fields; it is the same constructor-injection idea with the boilerplate
removed. Throughout this book, Firefly's own stereotypes —
`@CommandHandlerComponent`, `@QueryHandlerComponent`, and friends — are just
specialized Spring components, discovered and injected the same way.

!!! note "Coming from Spring MVC"
    Beans, the application context, and constructor injection are unchanged in
    the reactive world. A `@Service` is a `@Service`. Only what flows *through* the
    beans changes — from blocking values to reactive publishers.

## Modern Java the book uses

The listings use a few modern Java features without ceremony. None are exotic; if
you have written Java 17 or later, you have used all of them:

- **Records** — concise, immutable data carriers. `record Money(long minorUnits) {}`
  generates the constructor, accessors, `equals`, `hashCode`, and `toString`. Value
  objects and request/response DTOs are records throughout. (The reactor's `Money`
  is exactly this idea — money held as integer minor units to dodge floating-point
  rounding.)
- **Sealed types** — a closed set of subtypes, ideal for modeling a fixed set of
  states or events that the compiler can exhaustively check in a `switch`.
- **Enums with behavior** — a fixed set of named values that can carry methods. The
  reactor's `ApplicationStatus` (`DRAFT`, `SUBMITTED`, `UNDER_REVIEW`, `APPROVED`,
  `REJECTED`, `CANCELLED`) is one, with a small helper to ask whether a state is
  terminal.
- **`Optional<T>`** — an explicit "maybe a value" instead of a bare `null`.
- **Lambdas and method references** — passed to the reactive operators you will
  meet next, e.g. `.map(this::toResponse)`.

## From blocking to reactive

Classic Java I/O is **blocking**: a thread that calls a database or another service
*waits*, doing nothing, until the answer comes back. Under load that means one
parked thread per in-flight request, and threads are expensive. `CompletableFuture`
softened this by letting you describe work that completes *later*:

```java
CompletableFuture<Account> future = loadAccountAsync(id);
future.thenApply(Account::balance)
      .thenAccept(balance -> log.info("balance = {}", balance));
```

Notice the shape: you do not *get* the value, you *describe what to do when it
arrives*. Reactive programming generalizes exactly this idea — from "one value,
later" to "zero, one, or many values, later, with backpressure and cancellation."
*Backpressure* is the missing piece a bare `CompletableFuture` has no answer for:
when a fast producer outruns a slow consumer, a reactive stream lets the consumer
signal how much it can take, so memory does not balloon. That whole generalization
is **Project Reactor**, and its two types, `Mono` and `Flux`, are the vocabulary of
every Firefly service.

!!! warning "The one rule of reactive code: never block"
    On the reactive stack a small pool of event-loop threads serves *all* requests.
    If you call a blocking API (a JDBC query, `Thread.sleep`, `.block()`) on one of
    those threads, you stall every request it was serving — a handful of blocking
    calls can freeze the whole service. The entire point of the chapters ahead is
    to stay non-blocking end to end, which is exactly why the data layer is R2DBC,
    not JDBC, and why you will almost never see `.block()` in this book.

## Spring WebFlux

**Spring WebFlux** is the reactive counterpart to Spring MVC. A controller looks
almost identical — but instead of returning a value, it returns a *publisher* of
that value:

```java
@RestController
@RequestMapping("/api/v1/applications")
public class LoanApplicationController {

    private final LoanApplicationService service;

    public LoanApplicationController(LoanApplicationService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public Mono<LoanApplicationDto> byId(@PathVariable UUID id) {
        return service.findById(id);   // a Mono, not a value
    }
}
```

_This is a deliberately simplified sketch to show the shape. The real
`LoanApplicationController` you meet in Chapter 6 lives at
`/api/v1/loan-applications`, returns a `Mono<LoanApplicationResponse>` for a single
application and a `Flux<LoanApplicationResponse>` for the list, and runs in the
core tier on port `8081` — booted with `mvn spring-boot:run` and no Docker. Same
shape, real names._

Returning a `Mono<LoanApplicationDto>` instead of a `LoanApplicationDto` tells the
framework: *here is a recipe for one response; subscribe to it, and when the value
arrives, write it out* — without parking a thread in the meantime. A collection
endpoint returns `Flux<T>` (zero-to-many). WebFlux runs on Netty's event loop by
default; in Chapter 2's boot log you will see the exact line — `Netty started on
port 8081` — that proves the reactive server, not a servlet container, is what came
up.

!!! spring "Coming from Spring MVC"
    The annotations (`@RestController`, `@RequestMapping`, `@GetMapping`,
    `@PostMapping`, `@PathVariable`, `@RequestBody`, `@Valid`) are all the same. The
    change is the return type: `T` becomes `Mono<T>`, `List<T>` becomes `Flux<T>`,
    and you must never block inside the handler. If you have used `DeferredResult`
    or `CompletableFuture` return types in MVC, this is that idea taken all the way
    down — and the servlet container (Tomcat) is replaced by Netty.

## Project Reactor: Mono and Flux at a glance

`Mono<T>` is a publisher of **at most one** item (think: a single response, or
nothing). `Flux<T>` is a publisher of **zero to many** items (think: a stream of
rows or events). Both are **lazy**: nothing happens until something *subscribes*.
That laziness is the source of most reactive surprises for newcomers — a `Mono` you
build but never return (and so never subscribe to) simply does no work. In a
Firefly service the framework subscribes for you when it writes the HTTP response,
so you almost never call `.subscribe()` yourself — you *compose* a pipeline and
hand it back, and the edge of the framework drives it.

You transform reactive values with operators that mirror the `Stream` API:

```java
Mono<String> name =
    repository.findById(id)                // Mono<LoanApplication>
        .map(LoanApplication::applicant)   // Mono<Applicant>
        .map(Applicant::fullName)          // Mono<String>
        .defaultIfEmpty("unknown");
```

A quick orientation to the operators you will see most:

- `.map(fn)` — transform each item synchronously (value in, value out).
- `.flatMap(fn)` — transform each item into *another publisher* and flatten the
  result; this is how you chain one reactive call onto another (call the repository,
  then call a downstream service with the result).
- `.filter(pred)` — drop items that fail a predicate.
- `.defaultIfEmpty(x)` / `.switchIfEmpty(pub)` — supply a fallback when a `Mono`
  completes empty (the reactive way to handle "not found").

That is enough to read the early chapters: a method returns a `Mono` or `Flux`, and
you chain `.map`, `.flatMap`, `.filter`, and friends to describe the result. The
rule of thumb is "`map` for plain values, `flatMap` when the next step is itself
reactive." Chapter 5 — the reactive keystone — teaches the model properly: cold
versus hot publishers, error and retry operators, schedulers, backpressure, and how
a correlation ID survives across operator boundaries (a problem Firefly solves for
you, and a frequent source of bugs in hand-rolled reactive code).

!!! note "Coming from PyFly or Python"
    `Mono<T>` is the spiritual cousin of an `async def` returning one value, and
    `Flux<T>` of an async generator — but reactive, lazy, and with first-class
    backpressure and cancellation. Where you would `await`, here you `.map`/
    `.flatMap` and return the publisher so the framework awaits at the edge. The
    biggest mental shift: an `async def` runs when awaited, but a `Mono` runs only
    when *subscribed*, and in a controller it is the framework — not your code —
    that subscribes.

## R2DBC: reactive data access

If the web layer is non-blocking, the data layer must be too — otherwise a blocking
database call stalls the event loop. **R2DBC** (Reactive Relational Database
Connectivity) is the reactive answer to JDBC. With Spring Data R2DBC, a repository
returns publishers:

```java
public interface LoanApplicationRepository
        extends ReactiveCrudRepository<LoanApplication, UUID> {
    Flux<LoanApplication> findByStatus(ApplicationStatus status);
}
```

`findById` returns `Mono<LoanApplication>`; `findAll` and derived queries like
`findByStatus` return `Flux<LoanApplication>`. Spring Data still implements the
query *from the method name* — `findByStatus` becomes a `WHERE status = ?` — exactly
as in the blocking world; only the return type changed. This sketch is, in fact,
nearly the real repository you meet in Chapter 8, which extends the same
`ReactiveCrudRepository<LoanApplication, UUID>` and adds one more derived query
(`findByApplicationNumber`).

Schema is managed with **Flyway** migrations rather than auto-generated, and here is
a detail that matters for the companion: Flyway runs over JDBC while the runtime
data access runs over R2DBC, both pointed at the *same* in-memory H2 database. That
is how a slice can serve real, persisted requests on your laptop with no database to
install and no Docker — Flyway creates the table at startup, R2DBC reads and writes
it for the life of the JVM. Chapter 8 builds the real persistence layer of Lumen
Lending this way.

!!! warning "JPA/Hibernate is blocking — and not used here"
    Spring Data JPA, JDBC, and Hibernate are all blocking and have no place on the
    reactive stack. This book uses R2DBC throughout. If your instinct is to reach
    for `@Entity` and an `EntityManager`, that instinct belongs to the servlet
    world; the reactive equivalents are Spring Data R2DBC entities (`@Table`, `@Id`,
    `@Column`) and reactive repositories returning `Mono`/`Flux`.

!!! note "Coming from PyFly or Python"
    R2DBC is the closest analog to an async DB driver behind an async ORM —
    `asyncpg`/`databases` paired with an async SQLAlchemy session. The shape is the
    same: queries return awaitables you compose rather than values you block on.
    Flyway plays the role of Alembic — versioned, forward-only migrations checked
    into the repo.

## Where this leaves you

You now have the working vocabulary: Maven and starters; Spring Boot's
auto-configuration, externalized configuration, and beans; the reactive shift from
blocking to `Mono`/`Flux`; WebFlux controllers; and R2DBC repositories backed by
Flyway. That is the platform Firefly is built on, and — crucially — it is a platform
you can run locally in minutes, because the companion reactor needs nothing more
than a JDK and Maven.

The very next question is the one this whole book answers: if Spring Boot already
gives you all of this, *why build a metaframework on top of it?* Chapter 1 makes
the case; Chapter 2 then has you boot a real Firefly service and watch every piece
of this prelude show up in the log.
