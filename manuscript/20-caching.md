A loan-origination service spends most of its time answering the same questions.
"What is the status of this application?" "What is the rate card for this product?"
"Is this applicant on the watch list?" The data behind those answers changes slowly,
but the questions arrive constantly — and every one that travels all the way to the
database, or worse to a downstream core service, costs a round trip you did not need
to pay. Caching is how you stop paying it. Resilience is how you survive the round
trips you *can't* avoid when the thing on the other end is slow or down.

Firefly treats both as cross-cutting capabilities, wired the same way as everything
else in this book: a port, a default adapter, and a property to swap it. The cache is
a reactive `CacheAdapter` with Caffeine built in and a distributed L2 one dependency
away. Resilience is a Resilience4j circuit breaker, retry, and bulkhead that the
unified `ServiceClient` (Chapter 16) applies for you — expressed through Reactor
operators, never a blocking `try`/`catch`. You opt into query caching with a single
annotation attribute, and you tune resilience with `firefly.*` properties.

One honest note before we start. **This chapter is conceptual.** The Lumen Lending
origination slice you have been building does *not* wire a cache — its read model is
deliberately trivial (the `GetApplicationStatusHandler` you will read below just
returns a constant), and its 33 tests run against in-process defaults with no Redis
and no circuit-breaker drama. So there is no companion test to run green here, and
this chapter does not slice verified production code the way Chapter 2 sliced the
core's entry point. Instead, every snippet below is *illustrative*: it shows the real
Firefly types and properties, grounded in the framework source, and points at exactly
where each one plugs into the service you already have. The one verbatim slice is the
*uncached* handler as it stands today — the starting point the exercises then build on.
Those exercises have you add a cache to a real hot query in the reactor —
`GetApplicationStatusHandler` in the domain module — so you finish the chapter with
working code even though the chapter itself ships none.

Here is the path we will walk. Sections 1 through 4 build the cache from the bottom
up: the port, the built-in L1, the distributed L2, and the runtime provider choice.
Section 5 connects it to the CQRS bus you already know, so a read becomes cacheable
with one attribute and self-heals through events. Sections 6 and 7 turn to resilience
— the patterns that protect the calls a cache *can't* eliminate, and why they are
Reactor operators rather than blocking guards. Section 8 is the honest accounting of
what the reactor does and does not wire. Each section ends where the previous one
left off, so the whole thing reads as one argument: remove the round trips you can,
survive the ones you can't, and watch the two meet when a cache becomes a fallback.

## The CacheAdapter port

Everything starts at one interface, `org.fireflyframework.cache.core.CacheAdapter`.
It is reactive to the core — every operation returns a `Mono`, so a cache lookup
composes into a handler's chain exactly like a repository call, and a cache *miss* is
just an empty `Optional`, never a blocking null check.

```java
public interface CacheAdapter {
    <K, V> Mono<Optional<V>> get(K key);
    <K, V> Mono<Optional<V>> get(K key, Class<V> valueType);
    <K, V> Mono<Void>        put(K key, V value);
    <K, V> Mono<Void>        put(K key, V value, Duration ttl);
    <K, V> Mono<Boolean>     putIfAbsent(K key, V value);
    <K>    Mono<Boolean>     evict(K key);
    // ...plus clear, evictByPrefix, exists, keys, size, getStats, getHealth, isAvailable
}
```

The shape matters, and it is worth reading deliberately. Three design choices in that
signature do real work:

1. **`get` returns `Mono<Optional<V>>`, not `Mono<V>`.** A present value and an absent
   one are both *normal* signals — a populated `Optional` or an empty one — not a value
   versus an error. You branch on them with ordinary operators, and an empty cache is
   never an `onError` you have to catch. (Contrast a `Mono<V>` that completes empty: you
   would have to distinguish "miss" from "the value really is empty," and `switchIfEmpty`
   would conflate them.)
2. **`put` returns `Mono<Void>`, so a write is a deferred, composable action**, not a
   fire-and-forget side effect. You chain it with `then`/`thenReturn` so the cache is
   populated *as part of* the same reactive pipeline that produced the value — and the
   write completes before the value flows downstream.
3. **Every operation is generic in both key and value (`<K, V>`).** The port does not
   force you to stringify keys or erase types at the boundary; the adapter handles
   serialization where it must (Redis) and skips it where it need not (Caffeine).

A read-through pattern — the one you reach for most — falls out of those choices
naturally:

```java
// Illustrative: look in the cache; on a miss, load and backfill.
cache.<String, RateCard>get(productId)
    .flatMap(cached -> cached
        .map(Mono::just)                              // hit: hand back the cached value
        .orElseGet(() -> loadRateCard(productId)      // miss: do the real work
            .flatMap(card -> cache
                .put(productId, card, Duration.ofMinutes(10))
                .thenReturn(card))));                 // ...and populate the cache
```

No thread blocks while the cache answers, because the cache *itself* answers with a
`Mono`. That is the whole reason the port is reactive rather than a plain `Map`-style
API: on the reactive stack a cache that blocks to fetch from Redis would stall the
event loop just as surely as a blocking query would. A blocked event-loop thread does
not just slow *this* request — it stalls every other request scheduled on the same
thread, so one slow cache lookup becomes a fleet-wide latency spike. The reactive port
makes that failure mode structurally impossible: there is no blocking call to make.

!!! note "Key term — CacheAdapter (the cache port)"
    `CacheAdapter` is the single reactive interface every cache provider implements —
    Caffeine, Redis, Hazelcast, JCache, Postgres. Your code depends on the port, never
    on a provider's client, so the provider is a deployment choice (a dependency plus a
    property), not a code choice. This is the same hexagonal pattern as the EDA
    `EventPublisher` in Chapter 11 and the IDP `IdpAdapter` in Chapter 19.

!!! note "Why not just inject a Caffeine `Cache`?"
    You could — Caffeine's own API is excellent. But injecting the concrete client
    welds your handler to Caffeine: the day you need a *shared* cache across instances,
    every read-through site is a rewrite. Depending on the `CacheAdapter` port instead
    means the swap from in-process to distributed is a property change, and the
    read-through code above does not move a line. The port costs you one extra interface
    today to buy you a free migration later. That is the recurring Firefly trade.

## Caffeine is the built-in L1

Add the cache capability and you get a working cache immediately, with no
infrastructure: **Caffeine**, a high-performance in-process cache, is the built-in
default. It lives inside your JVM, so a hit is a method call — nanoseconds, no network
— and it needs nothing running alongside the service. For a single instance, or for
data that each instance can cache independently, Caffeine alone is often the whole
answer.

```yaml
firefly:
  cache:
    type: CAFFEINE          # the built-in, in-process L1 — zero infrastructure
    caffeine:
      maximum-size: 10000   # bounded so it can never exhaust the heap
      expire-after-write: 10m
```

Caffeine is **bounded** by design — a maximum entry count and a time-to-live — so a
runaway key space can never eat the heap. The boundedness is not a footnote; it is the
single most important operational property of an in-process cache. An *unbounded* cache
is a memory leak with good intentions: every distinct key you ever look up stays
resident until the process dies, and a service that caches per-`productId` or per-`id`
will eventually OOM under a wide enough key space. `maximum-size` turns that latent
crash into a bounded working set with least-recently-used eviction — the cache forgets
the coldest entries to make room, and the heap stays flat.

That bound is also Caffeine's one limitation: it is per-instance and volatile. Restart
the service and the cache is cold; run three instances and each has its own copy, which
can disagree for as long as a TTL. When that matters, you add a distributed layer behind
it — which is the next section.

!!! note "Key term — TTL (time-to-live) and the staleness budget"
    A **TTL** is how long a cached entry is allowed to live before it expires and the
    next read recomputes it. Choosing it is choosing a *staleness budget*: a 10-minute
    TTL says "I am willing to serve an answer up to 10 minutes out of date in exchange
    for not recomputing it." Short TTLs cost more recomputation but bound staleness
    tightly; long TTLs are faster but riskier for data that changes. For data that
    *must* be fresh the instant it changes, a TTL is the wrong tool — you want
    event-driven invalidation instead (see "invalidation," later).

## A distributed L2, write-through via SmartCacheAdapter

Put a distributed cache — Redis or Hazelcast — *behind* Caffeine and you get the best
of both: the speed of an in-process L1 for hits, and a shared L2 that survives restarts
and is consistent across instances. Firefly composes the two for you with the
`SmartCacheAdapter`, which is itself a `CacheAdapter` (so your code never knows there
are two layers) wrapping an L1 and an L2.

Its policy is **write-through with read backfill**, and it is worth understanding
precisely because it determines what your code observes:

- On `put`, it writes to **both** layers at once — `Mono.when(l1.put(...),
  l2.put(...))` — so the shared L2 is updated the moment the local L1 is. A write is
  never only-local.
- On `get`, it reads L1 first; on an L1 miss it falls through to L2, and if L2 has the
  value it **backfills L1** so the next local read is a fast hit.

This is not a paraphrase — it is the adapter's actual implementation. The `get` method
reads `l1` first and only consults `l2` on an absent `Optional`, and `put` is literally
`Mono.when(l1.put(key, value), l2.put(key, value))`. Conceptually, that read path is
two `CacheAdapter` calls stitched with `flatMap`:

```java
// Illustrative: the SmartCacheAdapter read path — L1, then L2, then backfill L1.
l1.get(key)
    .flatMap(hit -> hit.isPresent()
        ? Mono.just(hit)                          // L1 hit — done, no network
        : l2.get(key)                             // L1 miss — try the distributed L2
            .flatMap(l2hit -> backfillL1(key, l2hit)));  // populate L1 for next time
```

Trace the three paths through that one expression, because they are the three things
that happen in production:

- **L1 hit** (the common case): one in-process lookup, no network, nanoseconds.
- **L1 miss, L2 hit** (cold local cache, warm shared cache — e.g. just after a restart):
  one network round trip to Redis, then a backfill so the *next* local read is an L1 hit.
- **L1 miss, L2 miss** (genuinely cold): an empty `Optional` flows out, and your
  read-through loads from source and `put`s — which write-through populates *both*
  layers, so every other instance's next L2 read also hits.

You enable all of this without touching that code. Choosing a distributed `type` (or
letting `AUTO` pick one) and having the adapter jar on the classpath is enough; the
auto-configuration assembles the `SmartCacheAdapter` with Caffeine as L1 and your
provider as L2.

```yaml
firefly:
  cache:
    type: REDIS             # distributed L2; Caffeine becomes the L1 in front of it
    redis:
      host: redis.internal
      port: 6379
    default-ttl: 10m
```

!!! note "Key term — write-through L1/L2 cache"
    A **two-level** (L1/L2) cache pairs a fast local cache (L1, Caffeine) with a shared
    distributed cache (L2, Redis or Hazelcast). **Write-through** means every write goes
    to *both* immediately, so the shared layer is always current; **read backfill** means
    a value found only in L2 is copied up into L1 so subsequent local reads are fast.
    The `SmartCacheAdapter` implements both, behind the same `CacheAdapter` port, so the
    two layers are invisible to your handler.

!!! warning "L1 entries can be briefly stale across instances"
    Write-through keeps the *shared* L2 consistent, but each instance's local L1 still
    expires on its own clock. After a write on instance A, instance B's L1 can serve the
    old value until its entry's TTL lapses. That is the deliberate trade for L1 speed —
    so keep L1 TTLs short for data that several instances cache and mutate, or evict on
    the event that changed it (see "invalidation," below). Never assume a sub-second
    write is instantly visible on every node.

## CacheType.AUTO picks the provider for you

You rarely want to hard-code `REDIS` in every service and every environment. Set
`type: AUTO` and Firefly selects the best provider actually available at runtime,
in a fixed priority order:

```yaml
firefly:
  cache:
    type: AUTO   # Redis > Hazelcast > JCache > Caffeine > No-Op, by what's on the classpath
```

`AUTO` resolves **Redis, then Hazelcast, then JCache, then Caffeine, then No-Op** — the
first one whose adapter is present and configured wins, and Caffeine is the floor so you
always get *a* cache even with no distributed infrastructure. (This is the order
documented on `CacheType.AUTO` itself.) This is what lets a service run on plain Caffeine
in a developer's tests and on Redis in production *with the same configuration value* —
the environment supplies the jar, and `AUTO` does the rest. The full provider matrix and
the dependency for each lives in Appendix B.

Two practical consequences are worth spelling out. First, **`No-Op` is a real, selectable
provider**, not a degenerate fallback to "no caching at all that breaks your code." A
`NOOP` adapter implements the full `CacheAdapter` port but always returns a miss — so a
read-through still works, it just never hits. That means you can disable caching
*entirely* in a test or a debugging session without removing a line of caching code: the
behavior is correct, only the optimization is gone. Second, `AUTO` is a *promotion*
mechanism, not a runtime negotiation — the provider is chosen once at startup based on
the classpath and configuration, then fixed. There is no per-request "is Redis up?"
check; if Redis was selected and later falls over, that is what the *resilience* half of
this chapter is for.

!!! spring "Spring parity"
    Spring's own `@Cacheable`/`CacheManager` abstraction is **blocking** — it was built
    for the servlet stack and wraps a synchronous `Cache`. On WebFlux that is a trap: a
    `@Cacheable` method that reaches out to Redis blocks the event loop. Firefly's
    `CacheAdapter` is the reactive replacement — `Mono`-returning end to end — plus the
    provider-selection (`AUTO`) and L1/L2 composition that plain Spring leaves you to
    assemble by hand. You are still free to use Spring's cache for blocking code paths;
    on the reactive path, use the port.

## CQRS query caching: opt in with one attribute

You have already met the read side of the bus in Chapter 10 — a
`@QueryHandlerComponent` that the `QueryBus` discovers and dispatches to. Caching a
query result does **not** mean writing any of the read-through plumbing from the first
section. The query bus does it for you; you just declare that a handler's results are
cacheable and how long they live.

The handler in the reactor today opts out — well, it caches with a *default* TTL but the
slice never wires a `CacheAdapter`, so the bus has nothing to cache *into*. Here is the
real, current handler, verbatim — your starting point for the exercises:

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/handler/GetApplicationStatusHandler.java | Listing 20.1 — the uncached query handler as it stands today
package com.firefly.lumen.domain.handler;

import com.firefly.lumen.domain.query.GetApplicationStatusQuery;
import org.fireflyframework.cqrs.annotations.QueryHandlerComponent;
import org.fireflyframework.cqrs.query.QueryHandler;
import reactor.core.publisher.Mono;

/**
 * Query handler for {@link GetApplicationStatusQuery}.
 *
 * <p>The read-side counterpart to the command handlers: discovered via
 * {@code @QueryHandlerComponent} and dispatched through the
 * {@link org.fireflyframework.cqrs.query.QueryBus}. The slice keeps the read model trivial —
 * once an application id exists it is reported as {@code REGISTERED} — to demonstrate the
 * command/query split without pulling in a projection store.
 */
@QueryHandlerComponent
public class GetApplicationStatusHandler extends QueryHandler<GetApplicationStatusQuery, String> {

    @Override
    protected Mono<String> doHandle(GetApplicationStatusQuery query) {
        return Mono.just("REGISTERED");
    }
}
:::

Read the annotation defaults precisely, because they are subtle. `@QueryHandlerComponent`
declares `cacheable()` defaulting to `true` and `cacheTtl()` defaulting to `-1`. A *bare*
annotation, then, says "this query is cacheable in principle, but with no TTL set." The
`QueryHandler` base class reads that back through `supportsCaching()` and computes the
effective TTL: it only caches when `cacheTtl() > 0`. So the handler above, with a default
`-1` TTL and no `CacheAdapter` configured in the slice, caches nothing — `doHandle` runs
on every dispatch. To genuinely turn caching on you set a positive TTL:

```java
// Illustrative: cache this query's results for five minutes.
@QueryHandlerComponent(cacheable = true, cacheTtl = 300)
public class GetApplicationStatusHandler
        extends QueryHandler<GetApplicationStatusQuery, String> {

    @Override
    protected Mono<String> doHandle(GetApplicationStatusQuery query) {
        return Mono.just("REGISTERED");   // doHandle runs only on a cache miss
    }
}
```

With that — *and* a `CacheAdapter` on the classpath — the bus consults the configured
cache *before* invoking `doHandle`. The gate inside `DefaultQueryBus` is exactly three
conditions, all of which must hold:

```java
// Illustrative: the bus's caching gate (from DefaultQueryBus).
if (cacheAdapter != null && query.isCacheable() && handler.supportsCaching()) {
    // ... consult the cache, then fall through to doHandle on a miss
}
```

- `cacheAdapter != null` — a cache provider is actually configured. (In the slice it is
  not, which is why the bare annotation above is harmless.)
- `query.isCacheable()` — the *query object* declares itself cacheable.
- `handler.supportsCaching()` — the *handler's* annotation enables it with a positive TTL.

On a hit it returns the cached value and your handler never runs; on a miss it runs
`doHandle`, stores the result under the query's cache key for `cacheTtl` seconds, and
returns it. The cache key comes from the query object itself — `query.getCacheKey()`,
derived from the query's parameters — so two queries with the same parameters share an
entry and two with different parameters do not.

This is the payoff of the CQRS split from Chapter 10: because reads flow through a bus,
the bus is the one place to add caching, and a single annotation attribute switches it
on for any read in the fleet. No handler grows cache code; the capability lives in the
bus. You also saw this exact line confirmed at boot in Chapter 2 — the
`CqrsAutoConfiguration` log told you the query bus was wired with cache support:

```text
{"timestamp":"2026-06-17T08:21:44.077+0000","message":"CQRS Query Bus configured with cache support via fireflyframework-cache","logger":"o.f.c.config.CqrsAutoConfiguration","level":"INFO"}
```

That line is the bus announcing that `cacheAdapter != null` — the first of the three gate
conditions is satisfied at startup. (When `fireflyframework-cache` is absent, the same
configuration logs `configured without cache support` instead, and the gate's first
condition can never hold.)

!!! note "Key term — query result caching"
    A `@QueryHandlerComponent(cacheable = true, cacheTtl = N)` (with `N > 0`) tells the
    `QueryBus` to cache the handler's result for `N` seconds, keyed by the query's
    parameters. The bus short-circuits to the cached value on a hit, so `doHandle` runs
    only on a miss. It is the declarative, read-side counterpart to the manual
    read-through pattern shown earlier — same cache, no plumbing.

!!! warning "Cacheable is a default; a positive TTL is the real switch"
    `cacheable` defaults to `true` and `cacheTtl` defaults to `-1`, so a *bare*
    `@QueryHandlerComponent` is "cacheable, no TTL" — which the bus treats as not
    caching, because `supportsCaching()` requires `cacheTtl > 0`. Do not read a bare
    annotation as "results are being cached." The visible switch is the positive
    `cacheTtl`; the third precondition (a configured `CacheAdapter`) is invisible in the
    code and supplied by the classpath.

### Keeping a cached read honest: invalidation

A cache is only as good as its eviction. A status cached for five minutes is wrong the
instant the write side changes it — unless something tells the cache to forget.
Chapter 11 showed the bridge that does exactly this: `@InvalidateCacheOn` ties a cached
query to the domain events that make it stale, so a write that publishes
`LoanApplicationRegisteredEvent` automatically evicts the matching cached entries.

```java
// Illustrative: evict this query's cache when the matching event fires.
@QueryHandlerComponent(cacheable = true, cacheTtl = 300)
@InvalidateCacheOn(eventTypes = "LoanApplicationRegisteredEvent")
public class GetApplicationStatusHandler
        extends QueryHandler<GetApplicationStatusQuery, String> {
    // cached results are evicted whenever a LoanApplicationRegisteredEvent arrives
}
```

The `eventTypes` attribute is a `String[]`, so a query that several events can stale
lists them all — `@InvalidateCacheOn(eventTypes = {"LoanApplicationRegisteredEvent",
"LoanApplicationWithdrawnEvent"})`. Either event arriving evicts the entry.

This is the marriage of TTL and event invalidation, and it is worth being explicit about
why you want *both*. The TTL is the *backstop*: even if an invalidation event is ever
missed or never modeled, the entry cannot be wrong for longer than `cacheTtl` seconds.
The event is the *precision*: in the common case the read model self-heals the instant
the write side changes something, long before the TTL would have lapsed. Together they
give you "fresh within milliseconds in practice, and provably never staler than `N`
seconds in the worst case" — a far stronger guarantee than either alone.

Remember the rule from Chapter 11 — `eventTypes` matches the payload's **simple class
name**, not the producer's dotted logical string — because `@InvalidateCacheOn` keys on
the very same EDA runtime and the very same matching rule. So it is
`"LoanApplicationRegisteredEvent"`, never `"loanApplication.registered"`.

!!! note "Key term — cache invalidation"
    **Invalidation** is removing (evicting) a cached entry so the next read recomputes
    it. Firefly offers two complementary triggers: *time* (the `cacheTtl`, which expires
    every entry on a clock) and *event* (`@InvalidateCacheOn`, which evicts the moment a
    matching domain event arrives). Time bounds the worst case; events deliver freshness
    in the common case. The hard problems in caching are nearly all invalidation
    problems — which is why the framework makes the event path declarative.

## Resilience: surviving the calls you can't cache

Caching removes round trips; resilience governs the ones that remain. When the
origination domain calls the core over a generated SDK (Chapter 7), or any tier calls
another through the unified `ServiceClient` (Chapter 16) — exactly the live
`exp → domain → core` path the README captures, where the saga's root step writes to the
core over HTTP — that call can be slow, flaky, or flat-out down. Firefly wraps every such
call in three Resilience4j patterns, applied for you and configured with `firefly.*`
properties:

- **Circuit breaker** — after a downstream's failure rate crosses a threshold, the
  breaker *opens* and fails fast for a cool-off window instead of piling thousands of
  requests onto a service that is already struggling. It then half-opens to test
  recovery with a few trial calls before closing again.
- **Retry** — transient failures (a dropped connection, a `503`) are re-attempted a
  bounded number of times with backoff, so a momentary blip does not surface as an
  error.
- **Bulkhead** — the number of concurrent in-flight calls to a downstream is capped, so
  one slow dependency cannot consume every thread and drag down calls to *healthy*
  services along with it.

Why all three, and not just retry? Because they defend against *different* failure
modes, and each makes the others safe. Retry alone, faced with a downstream that is
genuinely down, turns one failed request into three — it *amplifies* load on a sick
service at the worst possible moment. The circuit breaker is what stops that
amplification: once the failure rate is clearly bad, it opens and the retries never fire.
The bulkhead defends a third axis entirely — not the failure *rate* but the
*concurrency*: a downstream that is merely *slow* (not failing) will, without a bulkhead,
accumulate in-flight calls until every available slot is waiting on it, and a slow
dependency becomes a total outage. The bulkhead caps that in-flight count so the slowness
stays contained. Together they cover fail-fast, transient-recovery, and
slow-dependency-isolation — the three ways a downstream hurts you.

You tune all three under the client properties. The framework ships sane defaults — a
50% failure-rate threshold over a sliding window of 10 calls, a minimum of 5 calls before
the breaker can trip, a 60-second open state, 3 permitted calls in half-open, and 3 retry
attempts with 500 ms backoff — so the behavior is correct before you configure anything.
(These are the literal defaults on `CircuitBreakerConfig`: `failureRateThreshold = 50.0`,
`slidingWindowSize = 10`, `minimumNumberOfCalls = 5`, `waitDurationInOpenState = 60s`,
`permittedNumberOfCallsInHalfOpenState = 3`.)

```yaml
firefly:
  service-client:
    circuit-breaker:
      enabled: true
      failure-rate-threshold: 50          # open at a 50% failure rate
      sliding-window-size: 10             # measured over the last 10 calls
      minimum-number-of-calls: 5          # ...but only after at least 5
      wait-duration-in-open-state: 60s    # stay open this long, then half-open
      permitted-number-of-calls-in-half-open-state: 3
    retry:
      enabled: true
      max-attempts: 3                     # the call, plus up to 2 retries
      wait-duration: 500ms
      exponential-backoff-multiplier: 2.0
```

The `minimum-number-of-calls` deserves a second look, because it is the property that
keeps the breaker from being jumpy. Without it, a single early failure on a freshly
started service would read as a 100% failure rate and trip the breaker on one bad call.
The minimum says "do not even *evaluate* the failure rate until you have a statistically
meaningful sample" — five calls, by default — so a lone transient blip during warm-up
does not open the circuit. The threshold and the window decide *when* to trip; the
minimum decides when there is enough evidence to decide at all.

### Resilience as Reactor operators, not blocking guards

The crucial part — the reason this fits the reactive stack at all — is *how* those
patterns are applied. They are **not** a blocking `try`/`catch` around a synchronous
call. Each is an operator in the reactive chain: the breaker, retry, and bulkhead all
wrap a `Mono`-returning operation and return a `Mono`, so the protection composes into
the same non-blocking pipeline as everything else. Firefly's `CircuitBreakerManager`, for
instance, exposes `executeWithCircuitBreaker(name, Supplier<Mono<T>>)` — it *defers* the
guarded work behind a `Supplier` and substitutes an error signal when the breaker is
open, the reactive analogue of failing fast:

```java
// Illustrative: the breaker is a Mono operator — it defers the call and
// emits onError(CircuitBreakerOpenException) instead of blocking when open.
Mono<RateCard> guarded = circuitBreaker.executeWithCircuitBreaker(
        "pricing-core",
        () -> pricingClient.rateFor(productId));   // the guarded operation, a Supplier<Mono>
```

The `Supplier<Mono<T>>` is the load-bearing detail. A plain `Mono` argument would already
have *started* assembling the call by the time the breaker inspected its state; passing a
`Supplier` means the breaker decides *first* — "am I open?" — and only invokes the
supplier (and thus only touches the downstream) when the answer is "closed." When the
breaker is open, the supplier is never called and the downstream is never hit; the breaker
returns `Mono.error(new CircuitBreakerOpenException(...))` directly. That is what
"fail fast" means concretely on the reactive stack: not a blocked thread waiting for a
timeout, but an immediate error signal that never leaves the JVM.

Because the result is a `Mono`, *recovery* is just the Reactor error operators you
already learned in Chapter 5. When the breaker is open or every retry is exhausted, you
fall back with `onErrorResume` — a cached rate card, a conservative default — instead of
propagating the failure to the caller:

```java
// Illustrative: combine resilience with a graceful fallback, reactively.
pricingClient.rateFor(productId)                       // ServiceClient applies CB + retry + bulkhead
    .timeout(Duration.ofSeconds(2))                    // bound the wait
    .onErrorResume(CircuitBreakerOpenException.class,
        ex -> cache.<String, RateCard>get(productId)   // breaker open → serve last good value
            .flatMap(Mono::justOrEmpty)
            .switchIfEmpty(Mono.just(RateCard.conservativeDefault())));
```

Walk that chain top to bottom, because it is the whole chapter in five lines. The
`ServiceClient` call already carries the breaker, retry, and bulkhead. `timeout` bounds
how long you will wait at all — a downstream that hangs is just a slow failure, and
`timeout` converts it into an `onError` you can resume from. `onErrorResume` catches the
breaker-open signal and *resumes the stream* with a fallback rather than erroring out.
The fallback reads the cache; `Mono::justOrEmpty` collapses the `Optional` so a cache miss
becomes an empty signal; and `switchIfEmpty` supplies a conservative default when even the
cache is cold. Three operators, three graceful degradations, no blocking, no `try`/`catch`.

This is where caching and resilience meet: a cache is often the *fallback* a resilient
call resumes to. The breaker keeps you from hammering a sick downstream; the cache lets
you keep answering — with a slightly stale but safe value — while it recovers. The two
halves of this chapter are not separate topics that happen to share a page; they are two
ends of the same pipeline.

!!! spring "Spring parity"
    Resilience4j is a standard Spring Boot library, and you could annotate methods with
    `@CircuitBreaker`, `@Retry`, and `@Bulkhead` yourself in any Spring app. Firefly's
    contribution is twofold: it wires those patterns into the unified `ServiceClient`
    (Chapter 16) so *every* downstream call is protected without per-call annotations,
    and it tunes them through fleet-wide `firefly.service-client.*` properties with
    environment-aware defaults — so the tenth service breaks circuits and retries exactly
    like the first, rather than each team re-deriving thresholds.

!!! warning "Retry only what is safe to retry"
    Retries multiply load and can duplicate side effects. Re-issuing a `GET` is
    harmless; re-issuing a non-idempotent `POST` can create two loan applications. This
    is why the idempotency filter from Chapter 6 (`X-Idempotency-Key`) and retry are
    partners: retry the read freely, but make every retried write idempotent so a
    re-attempt deduplicates instead of double-booking. In the live reactor flow this is
    concrete — the saga's `registerLoanApplication` root step writes to the core over
    HTTP, and the core's `DELETE /api/v1/loan-applications/{id}` (the saga's compensation
    path) is deliberately idempotent so a retried compensation cannot fail on an
    already-deleted row. Configure retry to fire on transient, *safe* failures — timeouts
    and `5xx` — not on every error.

## No companion test here — and why that's fine

Earlier chapters ended with `mvn ... test` and a green count, because they sliced real,
verified code out of the reactor. This one does not, and that is deliberate honesty:
Lumen Lending's origination slice never wires a cache, and its 33 tests (core 18, domain
6, exp 9) run against in-process defaults with no Redis and no circuit breaker to
exercise. The one piece this chapter *does* slice verbatim — Listing 20.1 — is the
*uncached* handler, precisely to show you the honest starting point. Caching is a
production optimization the sample does not need, and bolting it on just to have something
to assert would teach you a cache you would never keep.

It is worth being precise about which of this chapter's claims are *verified runnable
code* and which are *where-it-plugs-in*. The `CacheAdapter` port, the `SmartCacheAdapter`
write-through/backfill behavior, the `CacheType` priority order, the
`cacheable`/`cacheTtl` annotation defaults, the three-condition bus gate, the
`@InvalidateCacheOn` `String[] eventTypes`, the `CircuitBreakerManager` signature and its
`CircuitBreakerOpenException`, and the Resilience4j config defaults are all real framework
types and behaviors, named exactly as the framework names them — you can open them on the
classpath today. What the *reactor* does not do is wire any of them into the lending slice:
no Redis, no tripped breaker in a test, no event-driven eviction of a real projection.
The live `exp → domain → core` flow proves the `ServiceClient` seam exists and carries
real HTTP calls; it does not exercise the breaker opening or a cache serving a fallback.

So treat this chapter as the map, not the territory — the real `CacheAdapter`,
`CacheType`, `SmartCacheAdapter`, the `cacheable`/`cacheTtl` attributes, and the
Resilience4j properties are all exactly as named here, ready in the framework the moment
your service needs them. The exercises below close the gap: you will add a cache to a
genuine hot query in the reactor and watch the read short-circuit, turning the map into
running code.

## What you learned {.recap}

- The reactive **`CacheAdapter`** port returns `Mono<Optional<V>>`, so a cache hit and a
  miss are ordinary signals and a read-through composes with `flatMap` — no blocking,
  no null checks. `put` returns `Mono<Void>` so a write is part of the same pipeline.
- **Caffeine** is the built-in, **bounded** (`maximum-size` + TTL), in-process **L1**,
  needing no infrastructure. A distributed **L2** (Redis or Hazelcast) sits behind it via
  the **`SmartCacheAdapter`**, which writes through to both layers with
  `Mono.when(l1.put, l2.put)` and backfills L1 on an L2 hit — all behind the same port.
- **`CacheType.AUTO`** picks the provider at runtime — Redis, then Hazelcast, then
  JCache, then Caffeine, then No-Op — so one config value runs on Caffeine in tests and
  Redis in production (provider matrix in Appendix B). `No-Op` is a real selectable
  adapter that always misses, not a broken fallback.
- CQRS **query caching** is the `@QueryHandlerComponent(cacheable = true, cacheTtl = N)`
  attribute pair, but the real switch is a **positive `cacheTtl`** (default `-1` caches
  nothing); the bus caches only when **all three** of `cacheAdapter != null`,
  `query.isCacheable()`, and `handler.supportsCaching()` hold. `@InvalidateCacheOn`
  (a `String[] eventTypes`) evicts on the matching domain event by **simple class name** —
  TTL as backstop, event as precision.
- **Resilience** — Resilience4j circuit breaker, retry, and bulkhead — is applied by the
  unified `ServiceClient` (Chapter 16) as **Reactor operators**, not blocking guards.
  `executeWithCircuitBreaker(name, Supplier<Mono<T>>)` defers the call behind a `Supplier`
  and emits `CircuitBreakerOpenException` when open, so recovery is just
  `onErrorResume`/`switchIfEmpty`/`timeout` — and a cache makes a natural fallback.

## Try it yourself {.exercises}

These exercises edit the real reactor under `samples/lumen-lending`, starting from the
uncached `GetApplicationStatusHandler` in Listing 20.1 (the domain module).

1. **Cache a hot query.** In `GetApplicationStatusHandler`, change the bare
   `@QueryHandlerComponent` to `@QueryHandlerComponent(cacheable = true, cacheTtl = 60)`.
   Run the domain module's tests (`mvn -q -pl domain-lending-loan-origination test`) and
   confirm all **6** still pass — caching a deterministic query changes the result for
   nobody, which is exactly the point: it is a transparent optimization. (Note that
   without a `CacheAdapter` on the classpath the bus's first gate condition still fails,
   so this proves the annotation is *harmless*, not yet *effective* — exercise 4 supplies
   the missing condition.)
2. **Prove the short-circuit.** Add a counter (an `AtomicInteger`) the handler increments
   inside `doHandle`, then write a test that dispatches the *same* query twice through the
   `QueryBus` and asserts the counter incremented only **once** — proof the second read
   was served from cache and `doHandle` never ran. (You will need a `CacheAdapter` bean in
   the test context for the gate's first condition to hold; the in-process Caffeine
   default is enough.)
3. **Wire the invalidation.** Add `@InvalidateCacheOn(eventTypes =
   "LoanApplicationRegisteredEvent")` to the now-cacheable handler. In a sentence, explain
   (using the Chapter 11 rule) why `eventTypes` must be the simple class name and not
   `"loanApplication.registered"` — and why you keep the `cacheTtl` even with the event
   wired (hint: the TTL is the backstop, the event is the precision).
4. **Choose a provider with AUTO.** In `application.yml`, set `firefly.cache.type: AUTO`
   and reason through which provider it resolves to in the test profile (no Redis jar on
   the classpath) versus a production profile that adds `fireflyframework-cache-redis`.
   Confirm your answer against the priority order on `CacheType.AUTO` and in Appendix B.
   What does `AUTO` resolve to if *nothing* is on the classpath, and why is that still
   safe for your read-through code?
5. **Design a resilient fallback.** Sketch (no need to run it) a `ServiceClient` call to
   the pricing core that, on `CircuitBreakerOpenException`, resumes to a cached
   `RateCard` and only then to a conservative default. Identify which operator handles
   each step — `timeout`, `onErrorResume`, `switchIfEmpty` — and where the cache read
   slots in. Then explain why the guarded operation must be passed as a
   `Supplier<Mono<T>>` and not a ready-built `Mono`.

## Where to go next

Caching and resilience keep a service fast and standing; the next thing you need is to
*see* it doing so. Chapter 21 turns to observability — the metrics, traces, and the
working Reactor context propagation (the `traceId`/`spanId` you watched decorate every
log line in Chapter 2) that make a cache hit rate, a tripped breaker, or a retry storm
visible across the fleet, so the behavior you configured here is something you can
actually watch in production.
</content>
</invoke>
