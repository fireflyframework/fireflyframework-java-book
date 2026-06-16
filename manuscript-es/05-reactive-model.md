Everything else in this book stands on the idea in this chapter. A Firefly handler
returns a `Mono`. A repository returns a `Flux`. The command bus, the event
publisher, the resilient HTTP client — all of them speak Project Reactor. If the
reactive model is hazy, every later chapter feels like sleight of hand: values
appear from nowhere, methods return things you can't print, and a stray `.block()`
brings the whole service down. So before you build another service, you are going
to learn Reactor properly — operator by operator, signal by signal — until none of
it is magic.

The good news: you can learn it the way you learn any code, by running it and
watching it pass. The companion reactor ships a single self-contained test,
`ReactiveModelTest`, whose six methods are a tour of the model. There is no
database, no web server, no Firefly machinery — just `Mono`, `Flux`, and
`StepVerifier`. In the steps below you will read each method, understand exactly
what it asserts, and run the whole file green. Open a scratch buffer and type the
examples in as you go; reactive code rewards muscle memory.

The file lives at
`core-lending-loan-origination/src/test/java/com/firefly/lumen/core/ReactiveModelTest.java`.
Here is how it begins:

::: listing core-lending-loan-origination/src/test/java/com/firefly/lumen/core/ReactiveModelTest.java | Listing 5.1 — the imports that frame the whole chapter
package com.firefly.lumen.core;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
:::

Three imports carry the chapter. `Mono` and `Flux` are the publishers you compose.
`StepVerifier` from the `reactor-test` artifact is how you assert what a publisher
emits *without blocking* — it drives a subscription and checks each signal in turn.
`Duration` shows up only at the end, for the virtual-time example.

## Step 1 — Mono and Flux are lazy publishers

A `Mono<T>` is a publisher of **at most one** item: it will emit either one value
and complete, or complete with no value, or fail. Think of a single HTTP response,
a `findById`, a "save and return the saved row." A `Flux<T>` is a publisher of
**zero to many** items: a stream of rows, a page of results, a feed of events.

The word that matters most is **lazy**. A `Mono` or `Flux` is not a value; it is a
*recipe* for producing values. Building one runs nothing. The recipe executes only
when something **subscribes** — and not a moment before. This is the single biggest
shift coming from blocking Java, where calling a method *is* doing the work.

!!! note "Key term — publisher, subscriber, signals"
    A **publisher** (`Mono` or `Flux`) describes a stream of data. A **subscriber**
    consumes it. When you subscribe, the publisher pushes a sequence of **signals**:
    zero or more `onNext(value)` signals, then exactly one terminal signal —
    `onComplete()` (success) or `onError(throwable)` (failure). "Reactive testing"
    is really "asserting the exact sequence of signals," which is precisely what
    `StepVerifier` does.

Here is the simplest possible `Mono`, and the simplest possible assertion about it:

::: listing core-lending-loan-origination/src/test/java/com/firefly/lumen/core/ReactiveModelTest.java | Listing 5.2 — one value, then completion
    @Test
    void monoEmitsOneValueThenCompletes() {
        Mono<String> greeting = Mono.just("hello");

        StepVerifier.create(greeting)
                .expectNext("hello")
                .verifyComplete();
    }
:::

Read it as a sentence. `Mono.just("hello")` builds a recipe that, *when subscribed*,
emits `"hello"` and completes. Nothing has run yet — `greeting` is an inert
description. `StepVerifier.create(greeting)` subscribes. `.expectNext("hello")`
asserts the first signal is `onNext("hello")`. `.verifyComplete()` asserts the next
signal is `onComplete()` — and, crucially, *triggers the subscription* and blocks
the test thread until the verification finishes. Without that terminal call, nothing
would ever run.

A `Mono` need not carry a value at all. Emptiness is a first-class, expected outcome:

::: listing core-lending-loan-origination/src/test/java/com/firefly/lumen/core/ReactiveModelTest.java | Listing 5.3 — completion with no value at all
    @Test
    void emptyMonoCompletesWithoutAValue() {
        StepVerifier.create(Mono.empty())
                .verifyComplete();
    }
:::

`Mono.empty()` emits no `onNext` — it just completes. There is no `null` here, and
no exception; "nothing was found" is a normal signal, not an error. This is why a
Firefly repository's `findById` returns `Mono<LoanApplication>`: a missing row is an
empty `Mono`, and you handle it with an operator like `switchIfEmpty` rather than a
null check.

!!! tip "Checkpoint"
    Before going further, make sure the test file compiles and these first methods
    pass. From the `samples/lumen-lending` directory, run:

    ```text
    mvn -q -pl core-lending-loan-origination -Dtest=ReactiveModelTest#monoEmitsOneValueThenCompletes test
    ```

    You should see `Tests run: 1, Failures: 0`. If you see a compilation error,
    confirm `reactor-test` is on the test classpath — it ships with the Firefly core
    starter.

## Step 2 — Creating Mono and Flux

You rarely *write* a `Mono` from scratch in application code — operators and the
framework hand you one. But knowing the factory methods makes every later operator
legible, because they are how a stream begins.

A `Flux` of known values is `Flux.just(...)`:

::: listing core-lending-loan-origination/src/test/java/com/firefly/lumen/core/ReactiveModelTest.java | Listing 5.4 — a Flux emits each element, in order
    @Test
    void fluxEmitsEachElementInOrder() {
        Flux<Integer> numbers = Flux.just(1, 2, 3);

        StepVerifier.create(numbers)
                .expectNext(1, 2, 3)
                .verifyComplete();
    }
:::

`Flux.just(1, 2, 3)` emits `1`, then `2`, then `3`, then completes — and **order is
guaranteed**. `.expectNext(1, 2, 3)` is shorthand for three `onNext` expectations in
sequence; the test fails if the order differs or a value is missing.

Beyond `just`, the factories you will reach for most are:

```java
Mono.just(value);              // one known value
Mono.empty();                  // zero values, completes
Mono.error(new RuntimeException());   // fails immediately
Mono.fromCallable(() -> compute());   // run a (non-blocking) supplier lazily
Mono.defer(() -> buildMono());        // build the Mono fresh per subscription

Flux.just(a, b, c);            // known values
Flux.range(1, 6);              // 1, 2, 3, 4, 5, 6
Flux.fromIterable(list);       // from a collection
Flux.empty();                  // zero values, completes
```

The distinction between `Mono.just(compute())` and `Mono.fromCallable(compute)` is
worth burning in: `just` evaluates its argument **now**, eagerly, the moment you
build the recipe; `fromCallable` and `defer` postpone the work until subscription.
On the reactive stack you want the lazy form for anything with a side effect, so the
work happens at subscribe time, on the right thread, and re-runs on retry.

!!! warning "`Mono.just` captures its argument eagerly"
    `Mono.just(loadFromDb())` calls `loadFromDb()` immediately, *before* anyone
    subscribes — defeating laziness and, if that call blocks, stalling the event
    loop. When the value comes from real work, wrap the work: `Mono.fromCallable` or
    `Mono.defer`. Reserve `Mono.just` for values you already hold in hand.

!!! tip "Checkpoint"
    In your scratch buffer, replace `Flux.just(1, 2, 3)` with `Flux.range(1, 3)` and
    rerun the method. It still passes — `range(1, 3)` emits `1, 2, 3`. Now try
    `Flux.range(1, 3)` against `.expectNext(1, 2, 3, 4)` and read the failure
    message: `StepVerifier` tells you it expected a fourth `onNext` but got
    `onComplete`. That report — expected signal versus actual signal — is how you
    debug reactive code.

## Step 3 — Transforming and combining

Composition is the whole game. You almost never subscribe yourself; instead you
chain **operators** that return a new publisher describing the transformed stream.
The operators mirror the `Stream` API you already know, but each one returns a
`Mono` or `Flux` rather than a materialized collection.

The test demonstrates `filter` and `map` in one pipeline:

::: listing core-lending-loan-origination/src/test/java/com/firefly/lumen/core/ReactiveModelTest.java | Listing 5.5 — filter, then map, build a new stream
    @Test
    void operatorsTransformTheStream() {
        Flux<Integer> evensDoubled = Flux.range(1, 6)
                .filter(n -> n % 2 == 0)
                .map(n -> n * 10);

        StepVerifier.create(evensDoubled)
                .expectNext(20, 40, 60)
                .verifyComplete();
    }
:::

`Flux.range(1, 6)` emits `1` through `6`. `.filter(n -> n % 2 == 0)` lets only the
even values through — `2, 4, 6`. `.map(n -> n * 10)` transforms each — `20, 40, 60`.
None of this runs when you build `evensDoubled`; it is still a recipe. Only
`StepVerifier` subscribing pulls values through the chain, one at a time.

The four operators you will use constantly:

- **`map`** — transform each item synchronously, `T` to `U`. `Mono<Applicant>` to
  `Mono<String>` with `.map(Applicant::fullName)`.
- **`filter`** — drop items that fail a predicate. On a `Mono`, a filtered-out value
  becomes an *empty* `Mono`.
- **`flatMap`** — transform each item into *another publisher* and flatten the
  result. This is how you chain asynchronous calls.
- **`zip`** — combine the latest values from several publishers into one.

The critical distinction is `map` versus `flatMap`. Use `map` when the
transformation is a plain value (`n * 10`, `Applicant::fullName`). Use `flatMap`
when the transformation is itself asynchronous and returns a publisher — for
example, taking an application's ID and calling a repository:

```java
Mono<Decision> decision =
    repository.findById(id)              // Mono<LoanApplication>
        .flatMap(app -> scoringClient    // returns Mono<Score> — async!
            .score(app))                 // flatMap flattens Mono<Mono<Score>>
        .map(Score::toDecision);         // plain transform — map
```

If you reach for `map` where you needed `flatMap`, you end up with a
`Mono<Mono<Score>>` — a publisher of a publisher — that never does the inner work.
`flatMap` subscribes to the inner publisher for you and flattens one level. When two
independent calls feed one result, `zip` runs them and combines:

```java
Mono<Quote> quote = Mono.zip(
        pricingClient.rateFor(product),  // Mono<Rate>
        limitsClient.limitFor(customer)) // Mono<Limit>
    .map(both -> new Quote(both.getT1(), both.getT2()));
```

!!! spring "Spring parity"
    These operators are pure Project Reactor — Firefly adds nothing here, and a
    plain Spring WebFlux app composes the exact same way. If you came from Spring
    MVC and the Java `Stream` API, `map`/`filter` will feel familiar; the new idea is
    `flatMap` for *asynchronous* steps, which has no `Stream` equivalent because
    streams are synchronous. Think of `flatMap` as the reactive `await`-and-continue.

!!! tip "Checkpoint"
    Add a `.map(n -> n + 1)` to the end of the chain in `operatorsTransformTheStream`
    and update the expectation to `.expectNext(21, 41, 61)`. Rerun and watch it pass.
    Then delete the `.filter` line and predict the output before running — six values,
    each multiplied by ten: `10, 20, 30, 40, 50, 60`.

## Step 4 — Terminating and asserting with StepVerifier

You have been using `StepVerifier` all along; now name what it is. It is the
canonical way to test reactive code, because the alternative — calling `.block()` to
pull the value out — defeats the point and, in a real service, blocks the event
loop. `StepVerifier` subscribes, then lets you assert each signal in the order it
arrives, finishing with a terminal expectation that *runs* the verification.

The shape is always the same three parts:

```java
StepVerifier.create(publisher)   // subscribe
    .expectNext(...)             // assert onNext signals, in order
    .verifyComplete();           // assert terminal onComplete — and run it
```

The terminal call is mandatory and load-bearing. `.verifyComplete()` asserts the
stream ends with `onComplete`; `.verify()` asserts whatever terminal you described
just before it (you will use it for errors in the next step); `.expectComplete()`
followed by `.verify()` is the long form. Forget the terminal call and your "test"
builds a verifier and never subscribes — so it passes by doing nothing. That is the
most common reactive-testing bug, and it is silent.

!!! note "Key term — cold vs. hot publishers"
    Every publisher in this test is **cold**: it does no work until subscribed, and
    it produces the full sequence *afresh for each subscriber*. Subscribe twice,
    `Flux.range(1, 6)` runs twice. A **hot** publisher emits whether or not anyone is
    listening (a live event feed; a `Sinks.Many`), and late subscribers miss earlier
    items. Almost everything you build in Firefly — repository calls, client calls,
    handler results — is cold, which is why retrying simply re-runs the recipe.

!!! tip "Checkpoint"
    Comment out the `.verifyComplete()` line in any passing test, add a plain
    `;` to keep it compiling, and rerun. It still "passes" — because nothing
    subscribed. Restore the terminal. This is the single most important habit in
    reactive testing: a verifier without a terminal asserts nothing.

## Step 5 — Errors and retry

On the reactive stack, an error is not thrown up a call stack — it travels *down the
stream* as an `onError` signal, the same way values travel as `onNext`. It is a
terminal signal: once a publisher emits `onError`, it emits nothing more. You assert
it with `StepVerifier` just like a value.

::: listing core-lending-loan-origination/src/test/java/com/firefly/lumen/core/ReactiveModelTest.java | Listing 5.6 — an error is a terminal signal, asserted like any other
    @Test
    void errorsArePropagatedAsTerminalSignals() {
        Flux<Integer> failing = Flux.just(1, 2)
                .concatWith(Flux.error(new IllegalStateException("boom")));

        StepVerifier.create(failing)
                .expectNext(1, 2)
                .expectErrorMatches(e -> e instanceof IllegalStateException
                        && "boom".equals(e.getMessage()))
                .verify();
    }
:::

`Flux.just(1, 2)` emits two values, then `.concatWith(Flux.error(...))` appends a
stream that immediately fails. So the full signal sequence is `onNext(1)`,
`onNext(2)`, `onError(IllegalStateException("boom"))`. The verifier asserts the two
values, then `.expectErrorMatches(...)` inspects the terminal error's type and
message, and `.verify()` runs it. Note the terminal here is `.verify()`, not
`.verifyComplete()` — the stream does *not* complete, it fails, and asserting
completion would be wrong.

In real code you do not just observe errors; you *recover*. The recovery operators
are the reactive equivalents of `catch` and a retry loop:

```java
service.score(application)
    .onErrorResume(TimeoutException.class,
        ex -> Mono.just(Decision.deferred()))   // fallback value on a specific error
    .onErrorReturn(Decision.unavailable());      // last-resort constant fallback
```

`onErrorResume` swaps in a *new publisher* when the matched error occurs — a
fallback call, a cached value, a default. `onErrorReturn` swaps in a constant. To
re-attempt transient failures, use `retry`:

```java
pricingClient.rateFor(product)
    .retry(3);                                   // re-subscribe up to 3 times on error
```

Because the publisher is cold, `retry` simply re-runs the whole recipe. For anything
beyond a fixed count, `retryWhen` with a backoff strategy is the production-grade
form — it spaces attempts out and adds jitter so a struggling downstream is not
hammered:

```java
import reactor.util.retry.Retry;

pricingClient.rateFor(product)
    .retryWhen(Retry.backoff(3, Duration.ofMillis(200))   // 3 retries, exponential
        .filter(ex -> ex instanceof TimeoutException));    // only retry timeouts
```

!!! spring "Spring parity"
    None of this is Firefly-specific — `onErrorResume`, `retry`, and `retryWhen` are
    core Reactor, identical in any Spring WebFlux app. Where Firefly earns its keep
    is one layer up: its resilient HTTP clients ship with sane retry, timeout, and
    circuit-breaker defaults already wired (Chapter 14), so you write the `retryWhen`
    policy once, in the framework's configuration, rather than on every call site.

!!! tip "Checkpoint"
    Change the asserted message in `errorsArePropagatedAsTerminalSignals` from
    `"boom"` to `"bang"` and rerun. The test fails — but read the message: the
    verifier reports the actual error it received versus what you matched. Restore
    `"boom"`. Now you can debug an error pipeline by reading the signal report.

## Step 6 — Schedulers and threading

So far every example ran on the test thread, synchronously. Real services do I/O,
and *where* that work runs matters enormously on the reactive stack. By default a
reactive chain executes on whatever thread subscribed — for a Firefly HTTP handler,
that is a Netty event-loop thread, of which there are only a handful, shared across
*all* requests. Block one and you stall every request it was serving.

A **scheduler** is Reactor's abstraction for "which thread pool runs this work." You
shift execution with two operators:

- **`subscribeOn(scheduler)`** — controls the thread the *subscription and source*
  run on. It affects the whole chain upstream of it, and there is effectively one
  per chain.
- **`publishOn(scheduler)`** — switches threads for everything *downstream* of it,
  from that point on. Use it as many times as you need to move work between pools.

```java
Mono.fromCallable(() -> legacyBlockingLookup(id))   // a blocking call
    .subscribeOn(Schedulers.boundedElastic())       // ...run it OFF the event loop
    .map(this::toDto)                                // safe: not the event loop
    .publishOn(Schedulers.parallel());              // continue on a CPU-bound pool
```

The schedulers you will actually name:

- **`Schedulers.boundedElastic()`** — a growable pool capped to protect the host,
  meant exactly for wrapping *unavoidable blocking* calls (a legacy JDBC driver, a
  filesystem read) so they never touch the event loop.
- **`Schedulers.parallel()`** — a fixed pool sized to the CPUs, for CPU-bound work.
- **`Schedulers.immediate()`** — run on the current thread; the default behavior.

!!! warning "Don't block the event loop"
    The cardinal sin of reactive code is a blocking call on an event-loop thread —
    a JDBC query, `Thread.sleep`, a `.block()`, a synchronous SDK. The fix is never
    "make it faster"; it is `subscribeOn(Schedulers.boundedElastic())` to move the
    blocking work to a pool built to absorb it. Better still, use a non-blocking
    client (R2DBC, `WebClient`) and avoid the blocking call entirely. This is why
    the prelude insisted: never block.

!!! spring "Spring parity"
    Schedulers are pure Reactor and behave identically in plain Spring WebFlux.
    Firefly does not change the threading model — it inherits it — but its starters
    do configure the event loop and `boundedElastic` sizing through `firefly.*`
    properties, so the fleet shares one threading policy instead of each service
    guessing.

!!! tip "Checkpoint"
    There is no scheduler assertion in `ReactiveModelTest` — threading is a property
    of *where* work runs, not *what* it emits, so `StepVerifier` (which only checks
    signals) is the wrong tool. To *see* a thread switch, add a temporary
    `Flux.range(1, 3).publishOn(Schedulers.parallel()).doOnNext(n -> System.out.println(Thread.currentThread().getName())).blockLast();`
    in a throwaway `main` and watch the pool name in the output. Delete it after.

## Step 7 — Virtual time for time-based operators

Some operators are about *time*: `delayElement`, `timeout`, `retryWhen` with backoff,
`interval`. Testing them naively means your test *actually waits* — a one-hour delay
would take one hour. Reactor solves this with **virtual time**: `StepVerifier` swaps
in a clock you control, so you advance an hour instantly and assert what happens.

::: listing core-lending-loan-origination/src/test/java/com/firefly/lumen/core/ReactiveModelTest.java | Listing 5.7 — proving a one-hour delay in microseconds
    @Test
    void virtualTimeProvesDelayWithoutWaiting() {
        StepVerifier.withVirtualTime(() -> Mono.just("done").delayElement(Duration.ofHours(1)))
                .expectSubscription()
                .thenAwait(Duration.ofHours(1))
                .expectNext("done")
                .verifyComplete();
    }
:::

Three details make this work. First, you pass a **supplier** —
`() -> Mono.just("done").delayElement(...)` — not a built `Mono`. `withVirtualTime`
must install its virtual clock *before* the publisher is created, so it can only be
given a recipe to build later. Second, `.expectSubscription()` asserts the
subscription signal, the moment the virtual clock starts. Third, `.thenAwait(
Duration.ofHours(1))` advances that virtual clock a full hour *immediately* — no
real waiting — at which point the delayed element fires, so `.expectNext("done")`
and `.verifyComplete()` succeed. The test runs in microseconds yet proves an hour of
behavior.

!!! note "Key term — virtual time"
    **Virtual time** replaces the real scheduler clock with one the test advances by
    hand via `thenAwait`. It lets you assert *when* signals fire — that a timeout
    triggers at exactly 30 seconds, that a backoff waits 200 ms — deterministically
    and instantly. Any time-based operator should be tested this way; never with a
    real `sleep`.

!!! tip "Checkpoint"
    Change `.thenAwait(Duration.ofHours(1))` to `.thenAwait(Duration.ofMinutes(59))`
    and rerun. It now fails: at 59 virtual minutes the element has not fired, so the
    verifier sees no `onNext`. The delay is real, even though no real time passed.
    Restore the hour.

## Step 8 — How a Mono becomes an HTTP response

Everything so far has been a `StepVerifier` subscribing in a test. In a running
Firefly service, *who subscribes?* The framework does. When a request arrives, Spring
WebFlux invokes your handler, which returns a `Mono` — a recipe, not a value — and
WebFlux subscribes to it on the event loop. When the `Mono` emits `onNext`, the
framework serializes the value to JSON and writes the HTTP response; on `onComplete`
with no value it writes an empty body; on `onError` it maps the error to a status
code. You never call `.subscribe()` yourself.

That is the whole reason handlers return publishers. A blocking controller *holds* a
thread while the database answers; a reactive handler *describes* the response and
hands the recipe back, freeing the thread to serve other requests until the value is
ready. Conceptually:

```java
@GetMapping("/{id}")
public Mono<LoanApplicationDto> byId(@PathVariable UUID id) {
    return repository.findById(id)          // Mono<LoanApplication>
        .map(this::toDto)                   // Mono<LoanApplicationDto>
        .switchIfEmpty(Mono.error(
            new ResourceNotFoundException("LoanApplication", id)));
}                                           // the framework subscribes; you never do
```

The same operators you tested above — `map`, `flatMap`, `filter`, `onErrorResume` —
are the entire vocabulary of a real handler. An empty `Mono` (no row found) becomes a
404 via `switchIfEmpty` and Firefly's error model; an `onError` becomes an RFC 7807
problem response (Chapter 6); a `Flux` return becomes a JSON array or a streaming
response. The test you just ran and the production handler are *the same model* —
which is exactly why learning it on a six-method test transfers completely.

!!! spring "Spring parity"
    This is plain Spring WebFlux: returning `Mono<T>` or `Flux<T>` from a
    `@RestController` and letting the framework subscribe is identical with or
    without Firefly. What Firefly adds is the consistent edge behavior around it —
    the RFC 7807 error mapping, pagination envelope, and context propagation — so the
    `Mono` you return lands as a uniform response across the whole fleet.

## Step 9 — The production linchpin: context across operator boundaries

Here is the problem that bites every team that hand-rolls reactive code, and the one
thing Firefly most quietly saves you from. In blocking Java, a trace ID or tenant ID
lives in a `ThreadLocal` (the logging MDC is one), and because one thread serves one
request start to finish, every log line on that thread carries the right ID. On the
reactive stack that guarantee evaporates: a single request hops across many threads
as it crosses `flatMap`, `publishOn`, and scheduler boundaries, and `ThreadLocal`
does **not** follow it. Your logs end up blank — or worse, stamped with another
request's ID.

Reactor's own answer is the **Context**: an immutable, subscription-scoped map that
*does* travel with the subscription across every operator. You can read and write it
explicitly:

```java
Mono.deferContextual(ctx ->
        log.info("tenant={}", ctx.get("tenantId"))   // reads the subscription Context
    )
    .contextWrite(Context.of("tenantId", tenant));    // writes it, near the edge
```

That works, but threading it through by hand on every call is exactly the kind of
copy-paste boilerplate Chapter 1 called the enterprise tax. The bridge between the
old `ThreadLocal` world (which your logging, security, and tracing libraries still
use) and Reactor's `Context` is one line, installed once at startup:

```java
Hooks.enableAutomaticContextPropagation();
```

With that hook enabled, Reactor automatically restores registered `ThreadLocal`
values — the MDC, the trace context, the tenant — around every operator, on whatever
thread runs it. Your logs carry the right correlation ID across every `flatMap` and
`publishOn`, with no `contextWrite` on your part.

You will not find that call in `ReactiveModelTest` — the test is a deliberately
context-free tour of the operators. In a Firefly service you will not write it
either, and that is the point: the observability auto-configuration enables the hook
and registers the trace and tenant `ThreadLocal` accessors for you, so context
propagation simply *works* across the fleet. This is the capability the prelude and
Chapter 1 both flagged as the most valuable thing Firefly does on the reactive
stack, and now you know precisely what it fixes.

!!! warning "Without context propagation, reactive logs lie"
    A correlation ID that does not survive operator boundaries is worse than no ID:
    it silently attaches the *wrong* request's identity to a log line. If you ever
    build reactive code outside Firefly, enabling
    `Hooks.enableAutomaticContextPropagation()` and registering your `ThreadLocal`
    accessors is not optional — it is the difference between traceable and
    untraceable production.

!!! spring "Spring parity"
    This mechanism is Micrometer's `context-propagation` library plus Reactor's
    hook — available to any Spring Boot 3 / WebFlux app. The difference is wiring:
    in plain Spring you enable the hook and register each `ThreadLocalAccessor`
    yourself; Firefly's observability starter does it for the trace and tenant
    context out of the box, identically in every service.

## Run it

You have walked all six tests. Now run the whole file and watch it pass end to end.
From the `samples/lumen-lending` directory:

```text
mvn -q -pl core-lending-loan-origination -Dtest=ReactiveModelTest test
```

The expected result:

```text
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Six green tests — one per facet of the model: a `Mono` value, an empty `Mono`, a
`Flux` sequence, an operator pipeline, an error signal, and a virtual-time delay.
That is the entire reactive vocabulary the rest of the book uses.

## What you learned {.recap}

- A `Mono<T>` publishes **at most one** item; a `Flux<T>` publishes **zero to many**.
  Both are **lazy recipes** — nothing runs until something **subscribes**, and the
  framework subscribes for you at the HTTP edge.
- A subscription produces a sequence of **signals**: zero or more `onNext`, then one
  terminal `onComplete` or `onError`. Reactive testing with `StepVerifier` is
  asserting that exact sequence — and the terminal call (`verifyComplete`/`verify`)
  is what actually runs it.
- You **compose** with operators: `map`/`filter` for synchronous transforms,
  `flatMap` for chaining asynchronous calls, `zip` for combining, and
  `onErrorResume`/`retry`/`retryWhen` for recovery. Errors flow down the stream as a
  terminal signal, not up a call stack.
- **Schedulers** control which thread runs the work; you move unavoidable blocking
  calls off the event loop with `subscribeOn(Schedulers.boundedElastic())` and never
  block the loop. **Virtual time** lets you test time-based operators instantly.
- A handler returns a `Mono`/`Flux`; WebFlux subscribes and writes the response. The
  production linchpin is **automatic context propagation** —
  `Hooks.enableAutomaticContextPropagation()` — which keeps trace and tenant context
  alive across operator boundaries. Firefly enables it for you.

## Try it yourself {.exercises}

Each exercise extends the real test at
`core-lending-loan-origination/src/test/java/com/firefly/lumen/core/ReactiveModelTest.java`.
Add a method, run `mvn -q -pl core-lending-loan-origination -Dtest=ReactiveModelTest test`,
and keep it green.

1. **flatMap the depth away.** Write a test where `Flux.just(1, 2, 3)` is transformed
   with `.flatMap(n -> Flux.just(n, n))` and assert the six values it emits. Then
   change `flatMap` to `map` and read the compile error — you will have built a
   `Flux<Flux<Integer>>`. That error is the lesson.
2. **Recover from an error.** Take the `failing` flux from
   `errorsArePropagatedAsTerminalSignals`, append `.onErrorReturn(99)`, and assert the
   sequence is now `1, 2, 99` followed by `verifyComplete()` — the error became a
   value and the stream completed.
3. **Empty is not an error.** Write a test that `Mono.<String>empty()` followed by
   `.switchIfEmpty(Mono.just("fallback"))` emits `"fallback"`. This is the exact
   pattern a handler uses to turn a missing row into a default or a 404.
4. **Time out fast.** Using `StepVerifier.withVirtualTime`, build
   `Mono.just("late").delayElement(Duration.ofSeconds(10)).timeout(Duration.ofSeconds(2))`,
   advance virtual time, and assert it emits an `onError` of `TimeoutException` — a
   ten-second call cut off at two seconds, proven instantly.
5. **Watch the thread move.** In a throwaway `main` (not a test), subscribe to a
   `Flux.range(1, 3)` with a `.publishOn(Schedulers.parallel())` and print
   `Thread.currentThread().getName()` in a `doOnNext` before and after the
   `publishOn`. Confirm the name changes at the boundary — then delete it.

## Where to go next

You now read and write Reactor fluently, which means the rest of Lumen Lending is
just this model applied. Chapter 6 takes the very next step at the HTTP edge: when a
`Mono` emits `onError`, how does Firefly turn that terminal signal into a consistent
RFC 7807 problem response — automatically, identically, in every service?
