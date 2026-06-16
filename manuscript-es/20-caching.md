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
deliberately trivial, and its tests run against in-process defaults with no Redis and
no circuit-breaker drama. So there is no companion test to run green here. Instead,
every snippet below is *illustrative*: it shows the real Firefly types and properties,
grounded in the framework source, and points at exactly where each one plugs into the
service you already have. The exercises then have you add a cache to a real hot query
in the reactor — `GetApplicationStatusHandler` — so you finish the chapter with
working code even though the chapter itself ships none.

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
    <K, V> Mono<Boolean>     evict(K key);
    // ...plus clear, exists, keys, stats, health
}
```

The shape matters. Because `get` returns `Mono<Optional<V>>`, a present value and an
absent one are both *normal* signals — a populated `Optional` or an empty one — and
you branch on them with ordinary operators. A read-through pattern, the one you reach
for most, falls out naturally:

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
event loop just as surely as a blocking query would.

!!! note "Key term — CacheAdapter (the cache port)"
    `CacheAdapter` is the single reactive interface every cache provider implements —
    Caffeine, Redis, Hazelcast, JCache, Postgres. Your code depends on the port, never
    on a provider's client, so the provider is a deployment choice (a dependency plus a
    property), not a code choice. This is the same hexagonal pattern as the EDA
    `EventPublisher` in Chapter 11 and the IDP `IdpAdapter` in Chapter 19.

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
runaway key space can never eat the heap. That bound is also Caffeine's one limitation:
it is per-instance and volatile. Restart the service and the cache is cold; run three
instances and each has its own copy, which can disagree for as long as a TTL. When that
matters, you add a distributed layer behind it.

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

Conceptually, that read path is just two `CacheAdapter` calls stitched with `flatMap`:

```java
// Illustrative: the SmartCacheAdapter read path — L1, then L2, then backfill L1.
l1.get(key)
    .flatMap(hit -> hit.isPresent()
        ? Mono.just(hit)                          // L1 hit — done, no network
        : l2.get(key)                             // L1 miss — try the distributed L2
            .flatMap(l2hit -> backfillL1(key, l2hit)));  // populate L1 for next time
```

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
    the event that changed it (see "Invalidation," below). Never assume a sub-second
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
always get *a* cache even with no distributed infrastructure. This is what lets a service
run on plain Caffeine in a developer's tests and on Redis in production *with the same
configuration value* — the environment supplies the jar, and `AUTO` does the rest. The
full provider matrix and the dependency for each lives in Appendix B.

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

The handler in the reactor today opts out — it is a bare annotation:

```java
// In the reactor today: caching is not enabled on this handler.
@QueryHandlerComponent
public class GetApplicationStatusHandler
        extends QueryHandler<GetApplicationStatusQuery, String> {

    @Override
    protected Mono<String> doHandle(GetApplicationStatusQuery query) {
        return Mono.just("REGISTERED");
    }
}
```

Turning on caching is two attributes — `cacheable` and `cacheTtl` (in seconds):

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

With that, the bus consults the configured `CacheAdapter` *before* invoking
`doHandle`. On a hit it returns the cached value and your handler never runs; on a miss
it runs `doHandle`, stores the result under the query's cache key for `cacheTtl`
seconds, and returns it. The cache key comes from the query object itself — the bus
checks `query.isCacheable()` and the handler's `supportsCaching()`, both derived from
that annotation, before it caches anything — so two queries with the same parameters
share an entry and two with different parameters do not.

This is the payoff of the CQRS split from Chapter 10: because reads flow through a bus,
the bus is the one place to add caching, and a single annotation attribute switches it
on for any read in the fleet. No handler grows cache code; the capability lives in the
bus.

!!! note "Key term — query result caching"
    A `@QueryHandlerComponent(cacheable = true, cacheTtl = N)` tells the `QueryBus` to
    cache the handler's result for `N` seconds, keyed by the query's parameters. The bus
    short-circuits to the cached value on a hit, so `doHandle` runs only on a miss. It is
    the declarative, read-side counterpart to the manual read-through pattern shown
    earlier — same cache, no plumbing.

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

The read model self-heals: it serves fast cached answers until the write side changes
something, at which point the event invalidates the entry and the next read recomputes.
Remember the rule from Chapter 11 — `eventTypes` matches the payload's **simple class
name**, not the producer's dotted logical string — because `@InvalidateCacheOn` keys on
the very same EDA runtime and the very same matching rule.

## Resilience: surviving the calls you can't cache

Caching removes round trips; resilience governs the ones that remain. When the
origination domain calls the core over a generated SDK (Chapter 7), or any tier calls
another through the unified `ServiceClient` (Chapter 16), that call can be slow, flaky,
or flat-out down. Firefly wraps every such call in three Resilience4j patterns, applied
for you and configured with `firefly.*` properties:

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

You tune all three under the client properties. The framework ships sane defaults — a
50% failure-rate threshold over a sliding window of 10 calls, a 60-second open state,
and three retry attempts with 500 ms backoff — so the behavior is correct before you
configure anything:

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

### Resilience as Reactor operators, not blocking guards

The crucial part — the reason this fits the reactive stack at all — is *how* those
patterns are applied. They are **not** a blocking `try`/`catch` around a synchronous
call. Each is an operator in the reactive chain: the breaker, retry, and bulkhead all
wrap a `Mono`-returning operation and return a `Mono`, so the protection composes into
the same non-blocking pipeline as everything else. Firefly's circuit breaker, for
instance, defers the guarded work and substitutes an error signal when the breaker is
open — the reactive analogue of failing fast:

```java
// Illustrative: the breaker is a Mono operator — it defers the call and
// emits onError(CircuitBreakerOpenException) instead of blocking when open.
Mono<RateCard> guarded = circuitBreaker.executeWithCircuitBreaker(
        "pricing-core",
        () -> pricingClient.rateFor(productId));   // the guarded operation, a Supplier<Mono>
```

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

This is where caching and resilience meet: a cache is often the *fallback* a resilient
call resumes to. The breaker keeps you from hammering a sick downstream; the cache lets
you keep answering — with a slightly stale but safe value — while it recovers.

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
    re-attempt deduplicates instead of double-booking. Configure retry to fire on
    transient, *safe* failures — timeouts and `5xx` — not on every error.

## No companion test here — and why that's fine

Earlier chapters ended with `mvn ... test` and a green count, because they sliced real,
verified code out of the reactor. This one does not, and that is deliberate honesty:
Lumen Lending's origination slice never wires a cache, and its tests run against
in-process defaults with no Redis and no circuit breaker to exercise. Caching is a
production optimization the sample does not need, and bolting it on just to have
something to assert would teach you a cache you would never keep.

So treat this chapter as the map, not the territory — the real `CacheAdapter`,
`CacheType`, `SmartCacheAdapter`, the `cacheable`/`cacheTtl` attributes, and the
Resilience4j properties are all exactly as named here, ready in the framework the moment
your service needs them. The exercises below close the gap: you will add a cache to a
genuine hot query in the reactor and watch the read short-circuit, turning the map into
running code.

## What you learned {.recap}

- The reactive **`CacheAdapter`** port returns `Mono<Optional<V>>`, so a cache hit and a
  miss are ordinary signals and a read-through composes with `flatMap` — no blocking,
  no null checks.
- **Caffeine** is the built-in, bounded, in-process **L1**, needing no infrastructure.
  A distributed **L2** (Redis or Hazelcast) sits behind it via the **`SmartCacheAdapter`**,
  which writes through to both layers and backfills L1 on an L2 hit — all behind the same
  port.
- **`CacheType.AUTO`** picks the provider at runtime — Redis, then Hazelcast, then
  JCache, then Caffeine, then No-Op — so one config value runs on Caffeine in tests and
  Redis in production (provider matrix in Appendix B).
- CQRS **query caching** is one attribute: `@QueryHandlerComponent(cacheable = true,
  cacheTtl = N)` makes the `QueryBus` cache results by query parameters and run
  `doHandle` only on a miss; `@InvalidateCacheOn` evicts on the matching domain event.
- **Resilience** — Resilience4j circuit breaker, retry, and bulkhead — is applied by the
  unified `ServiceClient` (Chapter 16) as **Reactor operators**, not blocking guards, so
  recovery is just `onErrorResume`/`timeout`, and a cache makes a natural fallback.

## Try it yourself {.exercises}

These exercises edit the real reactor under `samples/lumen-lending`, starting from the
uncached `GetApplicationStatusHandler` in the domain module.

1. **Cache a hot query.** In `GetApplicationStatusHandler`, change the bare
   `@QueryHandlerComponent` to `@QueryHandlerComponent(cacheable = true, cacheTtl = 60)`.
   Run the domain module's tests (`mvn -q -pl domain-lending-loan-origination test`) and
   confirm they still pass — caching a deterministic query changes the result for nobody,
   which is exactly the point: it is a transparent optimization.
2. **Prove the short-circuit.** Add a counter (an `AtomicInteger`) the handler increments
   inside `doHandle`, then write a test that dispatches the *same* query twice through the
   `QueryBus` and asserts the counter incremented only **once** — proof the second read
   was served from cache and `doHandle` never ran.
3. **Wire the invalidation.** Add `@InvalidateCacheOn(eventTypes =
   "LoanApplicationRegisteredEvent")` to the now-cacheable handler. In a sentence, explain
   (using the Chapter 11 rule) why `eventTypes` must be the simple class name and not
   `"loanApplication.registered"`.
4. **Choose a provider with AUTO.** In `application.yml`, set `firefly.cache.type: AUTO`
   and reason through which provider it resolves to in the test profile (no Redis jar on
   the classpath) versus a production profile that adds `fireflyframework-cache-redis`.
   Confirm your answer against the priority order in Appendix B.
5. **Design a resilient fallback.** Sketch (no need to run it) a `ServiceClient` call to
   the pricing core that, on `CircuitBreakerOpenException`, resumes to a cached
   `RateCard` and only then to a conservative default. Identify which operator handles
   each step — `timeout`, `onErrorResume`, `switchIfEmpty` — and where the cache read
   slots in.

## Where to go next

Caching and resilience keep a service fast and standing; the next thing you need is to
*see* it doing so. Chapter 21 turns to observability — the metrics, traces, and the
working Reactor context propagation that make a cache hit rate, a tripped breaker, or a
retry storm visible across the fleet, so the behavior you configured here is something
you can actually watch in production.
