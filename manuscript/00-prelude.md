The chapters ahead assume you can read modern Spring Boot comfortably. This
prelude makes sure you can — even if your last Spring was MVC and JPA, or you
arrive from another Firefly port such as PyFly. It is **not** a Spring course.
It teaches exactly the slice of Spring Boot, WebFlux, Project Reactor, and R2DBC
that the later chapters lean on, in roughly the order they lean on it. Each
section ends with a short *Coming from…* note that translates the idea from where
you might be standing today.

If you already ship reactive Spring Boot daily — WebFlux controllers, R2DBC
repositories, `Mono`/`Flux` in your sleep — skip ahead to Chapter 1. Nothing here
will surprise you.

A note on depth: the reactive model is the one idea everything else stands on, so
this prelude introduces it just far enough to read the early chapters, and
Chapter 5 then teaches it properly, operator by operator. You will meet `Mono`
and `Flux` three times at increasing depth — here, in passing; in Chapter 1, as a
key term; and in Chapter 5, in full. That repetition is deliberate.

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

Chapter 3 is devoted to how Firefly's parent POM and BOM make an entire fleet of
services version-coherent. For now, just hold the shape: *inherit a parent, add a
starter, omit versions.*

!!! note "Coming from PyFly or Python"
    The `pom.xml` is the rough analog of `pyproject.toml`; a Maven *starter* is
    like an extras group that installs a coherent set of packages; the parent/BOM
    is the dependency-locking story. `mvn verify` is your `pytest` plus a build.

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

You bind configuration to typed objects with `@ConfigurationProperties`:

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

Firefly is, at bottom, *more Spring Boot* — more auto-configuration, more starters,
more conventions — and Chapter 1 explains exactly what it adds and why.

!!! spring "Coming from Spring MVC"
    Everything above is identical to what you know — `@SpringBootApplication`,
    `application.yml`, Actuator, `@ConfigurationProperties` all carry over
    unchanged. The one thing that differs is the web and data stack underneath,
    which is *reactive* rather than servlet-based. That difference is the subject
    of the next three sections.

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
test by passing fakes directly. Throughout this book, Firefly's own stereotypes —
`@CommandHandlerComponent`, `@QueryHandlerComponent`, and friends — are just
specialized Spring components discovered the same way.

!!! note "Coming from Spring MVC"
    Beans, the application context, and constructor injection are unchanged in
    the reactive world. A `@Service` is a `@Service`. Only what flows *through* the
    beans changes — from blocking values to reactive publishers.

## Modern Java the book uses

The listings use a few modern Java features without ceremony:

- **Records** — concise, immutable data carriers. `record Money(long minorUnits) {}`
  generates the constructor, accessors, `equals`, `hashCode`, and `toString`. Value
  objects and DTOs are records throughout.
- **Sealed types** — a closed set of subtypes, ideal for modeling a fixed set of
  states or events that the compiler can exhaustively check in a `switch`.
- **`Optional<T>`** — an explicit "maybe a value" instead of a bare `null`.
- **Lambdas and method references** — passed to the reactive operators you will
  meet next, e.g. `.map(this::toDto)`.

None of these are exotic; if you have written Java 17 or later, you have used them.

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
That generalization is **Project Reactor**, and its two types, `Mono` and `Flux`,
are the vocabulary of every Firefly service.

!!! warning "The one rule of reactive code: never block"
    On the reactive stack a small pool of event-loop threads serves *all* requests.
    If you call a blocking API (a JDBC query, `Thread.sleep`, `.block()`) on one of
    those threads, you stall every request it was serving. The whole point of the
    chapters ahead is to stay non-blocking end to end — which is exactly why the
    data layer is R2DBC, not JDBC.

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

_This is a deliberately simplified sketch to show the shape; the real `LoanApplicationController` you meet in Chapter 6 lives at `/api/v1/loan-applications` and returns a `LoanApplicationResponse`._

Returning a `Mono<LoanApplicationDto>` instead of a `LoanApplicationDto` tells the
framework: *here is a recipe for one response; subscribe to it, and when the value
arrives, write it out* — without parking a thread in the meantime. A collection
endpoint returns `Flux<T>` (zero-to-many). WebFlux runs on Netty's event loop by
default.

!!! spring "Coming from Spring MVC"
    The annotations (`@RestController`, `@GetMapping`, `@PathVariable`,
    `@RequestBody`) are the same. The change is the return type: `T` becomes
    `Mono<T>`, `List<T>` becomes `Flux<T>`, and you must never block inside the
    handler. If you have used `DeferredResult` or `CompletableFuture` return types
    in MVC, this is that idea taken all the way down.

## Project Reactor: Mono and Flux at a glance

`Mono<T>` is a publisher of **at most one** item (think: a single response, or
nothing). `Flux<T>` is a publisher of **zero to many** items (think: a stream of
rows or events). Both are **lazy**: nothing happens until something *subscribes*.
In a Firefly service, the framework subscribes for you when it writes the HTTP
response, so you almost never call `.subscribe()` yourself — you *compose*.

You transform reactive values with operators that mirror the `Stream` API:

```java
Mono<String> name =
    repository.findById(id)                // Mono<LoanApplication>
        .map(LoanApplication::applicant)   // Mono<Applicant>
        .map(Applicant::fullName)          // Mono<String>
        .defaultIfEmpty("unknown");
```

That is enough to read the early chapters: a method returns a `Mono` or `Flux`, and
you chain `.map`, `.flatMap`, `.filter`, and friends to describe the result.
Chapter 5 — the reactive keystone — teaches the model properly: cold versus hot
publishers, error and retry operators, schedulers, backpressure, and how a
correlation ID survives across operator boundaries (a problem Firefly solves for
you, and a frequent source of bugs in hand-rolled reactive code).

!!! note "Coming from PyFly or Python"
    `Mono<T>` is the spiritual cousin of an `async def` returning one value, and
    `Flux<T>` of an async generator — but reactive, lazy, and with first-class
    backpressure and cancellation. Where you would `await`, here you `.map`/
    `.flatMap` and return the publisher so the framework awaits at the edge.

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

`findById` returns `Mono<LoanApplication>`; `findAll` and derived queries return
`Flux<LoanApplication>`. Schema is managed with Flyway migrations, and Chapter 8
builds the real persistence layer of Lumen Lending this way.

!!! warning "JPA/Hibernate is blocking — and not used here"
    Spring Data JPA, JDBC, and Hibernate are all blocking and have no place on the
    reactive stack. This book uses R2DBC throughout. If your instinct is to reach
    for `@Entity` and an `EntityManager`, that instinct belongs to the servlet
    world; the reactive equivalents are R2DBC entities and reactive repositories.

## Where this leaves you

You now have the working vocabulary: Maven and starters; Spring Boot's
auto-configuration, configuration, and beans; the reactive shift from blocking to
`Mono`/`Flux`; WebFlux controllers; and R2DBC repositories. That is the platform
Firefly is built on.

The very next question is the one this whole book answers: if Spring Boot already
gives you all of this, *why build a metaframework on top of it?* Chapter 1 makes
the case.
