A loan application crosses three tiers and a dozen reactive operators before a
client sees a decision. When something goes wrong at two in the morning, the only
thing standing between you and a blind production incident is what the service
*emitted while it ran* — its logs, its metrics, its traces. Observability is the
discipline of making a running system explain itself, and on the reactive stack it
is harder than it looks: the thread that started a request is rarely the thread
that finishes it, so the correlation IDs that should tie a story together tend to
fall on the floor.

This is the chapter where the thread we have been pulling since Chapter 1 ties off.
Every Lumen service you have built already emits **structured JSON logs** stamped
with a `traceId`, a `spanId`, and an `X-Transaction-Id`; already exposes Actuator
health and Prometheus metrics; and already keeps that correlation context alive
across every `flatMap` and `publishOn` — without a single line of observability code
in any controller, handler, or service you wrote. None of it is in the sample's
source, because all of it ships in the `fireflyframework-observability` module that
every tier starter pulls in. This chapter shows you exactly what that module wires,
why the reactive correlation actually works, and where you plug in when you need
more.

There is no companion test for this chapter — observability is about what a *running*
service emits to logs, a metrics scrape, and a trace collector, not about a value a
`StepVerifier` can assert. So everything below is illustrative: real configuration
and real framework code, read to understand how the wiring behaves, rather than a
slice a build verifies. Where a claim rests on framework source, the prose names the
class so you can open it yourself.

## What the starter already gives you

Lumen's three services declare `fireflyframework-starter-core`,
`-starter-domain`, and `-starter-application`. Each of those starters depends,
transitively, on `fireflyframework-observability` — so the moment a Lumen service is
on the classpath, a full observability stack auto-configures itself. You opted into
none of it per service; it arrived with the starter, exactly the way Chapter 1
promised cross-cutting concerns would.

Concretely, with no code and no configuration in the sample, every Lumen service
gets:

- **Structured JSON logs** to the console, encoded by Logstash's `LogstashEncoder`,
  with the trace and transaction context promoted to top-level fields.
- **Micrometer metrics** under a single `firefly.{module}.{metric}` namespace,
  exported to Prometheus by default.
- **Distributed tracing** bridged to **OpenTelemetry** by default, using W3C
  TraceContext propagation, with `X-Transaction-Id` carried as trace baggage.
- **Actuator endpoints** for health, info, metrics, and a Prometheus scrape,
  exposed and configured consistently.
- **Reactive context propagation** — `Hooks.enableAutomaticContextPropagation()`
  called once at startup — which is *why* all of the above survive a reactive
  request's thread hops.

The rest of the chapter walks each of these, grounded in the framework class that
implements it.

!!! spring "Spring parity"
    Every piece here is a standard Spring Boot 3 / Micrometer mechanism: Logback with
    a JSON encoder, Micrometer `MeterRegistry`, Micrometer Tracing over an
    OpenTelemetry bridge, Spring Boot Actuator, and Micrometer's
    `context-propagation` library. You could assemble all of it by hand in a plain
    WebFlux app. Firefly's contribution is that it is assembled *once*, tuned by
    `firefly.observability.*` properties, and identical across the fleet — so the
    tenth service correlates its logs exactly like the first.

## A real JSON log line

Start with the artifact you will stare at most during an incident: a log line. In
development you might see a pretty, colorized console line, but in production every
Lumen service emits **structured JSON**, one object per event. That is not an
application choice — it is the framework's `logback-firefly.xml`, included by the
observability module's logging auto-configuration, wiring a `LogstashEncoder` onto
the console appender.

The encoder is configured to promote a fixed set of MDC keys to top-level JSON
fields. When the loan-origination core logs while handling a request, the line on
the wire looks like this:

```json
{
  "timestamp": "2026-06-17T09:14:22.481Z",
  "level": "INFO",
  "logger": "com.firefly.lumen.core.service.LoanApplicationService",
  "thread": "reactor-http-nio-3",
  "message": "Created loan application",
  "traceId": "8a3f1c92b47e5d016f0a2c7d9e114b23",
  "spanId": "6f0a2c7d9e114b23",
  "X-Transaction-Id": "4f2c9e10-7b3a-4f6e-9c21-2a1d5b8e0c33"
}
```

Three fields make this line *useful* rather than merely present. The `traceId` ties
this log to every other log — in this service and downstream ones — that belongs to
the same distributed trace. The `spanId` identifies this specific unit of work
within that trace. The `X-Transaction-Id` is Firefly's own correlation identifier,
the same one the web module's `TransactionFilter` stamps onto every HTTP response
(Chapter 6) — so an operator can take an `X-Transaction-Id` off a response header,
paste it into a log query, and pull back every line the request produced across
every tier.

Those exact field names are not incidental. They are constants the whole framework
shares, defined in `org.fireflyframework.observability.logging.MdcConstants`:
`TRACE_ID = "traceId"`, `SPAN_ID = "spanId"`, and
`TRANSACTION_ID = "X-Transaction-Id"`, alongside `userId`, `correlationId`, and
`requestId`. The JSON encoder is configured to include exactly those keys, so any
module that logs through them sees its context lifted to a top-level field. Because
every module logs through the same keys, log aggregation across a fleet is uniform —
one query shape works everywhere.

!!! note "Key term — MDC (Mapped Diagnostic Context)"
    The **MDC** is SLF4J's per-context key/value map that a logging encoder can read
    and attach to every line. In classic blocking Java, you put a `traceId` into the
    MDC at the start of a request and every log line on that thread carries it for
    free. On the reactive stack that breaks — and fixing it is the linchpin of this
    chapter (see *Why correlation survives*, below). Firefly's JSON encoder lifts the
    MDC keys in `MdcConstants` to first-class JSON fields.

!!! warning "PII never reaches these logs by accident"
    A log line that carries a `traceId` must never carry a national ID or a card
    number. Chapter 6's PII masking is the other half of this story: the web module
    redacts personally identifiable data — emails, national IDs, card numbers —
    *before* it is logged. Structured logging makes lines queryable; PII masking
    keeps them safe to query. Treat the two as a pair: you want every request
    traceable and no request leaking.

## Metrics: one namespace for the whole fleet

Logs tell you about one request; metrics tell you about all of them at once — rates,
durations, error ratios, queue depths. Firefly emits its framework metrics through
Micrometer, and imposes a single naming convention so that a dashboard built for one
service reads the same on the next: **`firefly.{module}.{metric}`**.

That convention is enforced in code, not by hand. The class
`org.fireflyframework.observability.metrics.MetricNaming` builds every framework
metric name from a module and a metric, and rejects a module that is not lowercase
alphanumeric:

```java
// From org.fireflyframework.observability.metrics.MetricNaming (illustrative).
public static String prefix(String module) {            // e.g. "cqrs" -> "firefly.cqrs"
    if (module == null || !module.matches("[a-z][a-z0-9]*")) {
        throw new IllegalArgumentException(
                "Module must be lowercase alphanumeric starting with a letter: " + module);
    }
    return FIREFLY_PREFIX + "." + module;               // FIREFLY_PREFIX = "firefly"
}

public static String name(String prefix, String metric) {   // "firefly.cqrs" + "command.processed"
    return prefix + "." + metric;                            //  -> "firefly.cqrs.command.processed"
}
```

Module metrics extend a shared base, `FireflyMetricsSupport`, which prefixes every
counter and timer automatically and caches them. A module's metrics class names only
its own short metric — the `firefly.{module}.` prefix is supplied for it:

```java
// Illustrative: how a module declares its own metrics on the shared base.
public class CqrsMetrics extends FireflyMetricsSupport {
    public CqrsMetrics(@Nullable MeterRegistry registry) {
        super(registry, "cqrs");                        // module = "cqrs"
    }
    public void commandProcessed(String commandType) {
        counter("command.processed",                    // becomes firefly.cqrs.command.processed
                MetricTags.COMMAND_TYPE, commandType).increment();
    }
}
```

So when Lumen's domain tier dispatches a command through the CQRS bus (Chapter 10)
or publishes an event through the EDA runtime (Chapter 11), the resulting meters
land under `firefly.cqrs.*` and `firefly.eda.*` — predictable names you can graph
without reading any service's source. A Prometheus scrape of the core service shows
the framework's meters beside Spring Boot's own (`http.server.requests`, JVM and
R2DBC pool gauges), all from the one registry.

One detail worth internalizing: `FireflyMetricsSupport` is **null-safe**. When no
`MeterRegistry` is on the classpath — say a slice test with Actuator absent — every
counter and timer becomes a no-op rather than a `NullPointerException`. Metrics
instrumentation never changes whether your business logic runs; it only observes it.

!!! note "Key term — Micrometer Observation"
    Micrometer's **Observation** API is the unifying abstraction beneath all of this:
    you "observe" a unit of work *once*, and Micrometer fans that single observation
    out into a metric (a timer/counter) *and* a trace span — through whatever backends
    are registered. Firefly's metrics support records timers and counters against the
    Micrometer registry, and its tracing bridge turns the same request boundaries into
    spans; both feed off that one registry, which is why a metric's timing and a
    trace's span agree about the same operation.

!!! tip "Checkpoint"
    There is no metric assertion to run here — a metric is a property of a *running*
    registry, not a signal `StepVerifier` checks. To see the convention live, boot
    the core service (Chapter 2) and `curl` its Prometheus endpoint at
    `/actuator/prometheus`. Search the output for `firefly_` (Prometheus renders the
    dots as underscores) and you will find the framework's meters namespaced exactly
    as `MetricNaming` builds them.

## Tracing, bridged to OpenTelemetry

A `traceId` in a log line is only half the value; the other half is a **trace** — the
tree of spans, one per service hop, that reconstructs a request's whole journey
through the fleet. Firefly configures Micrometer Tracing with an **OpenTelemetry**
bridge by default, the vendor-neutral standard that virtually every trace collector
(Jaeger, Tempo, Honeycomb, a cloud APM) speaks.

You can read the defaults straight off the observability properties — the prefix is
`firefly.observability`, so they are overridable per service or fleet-wide:

```yaml
# Firefly observability defaults (firefly.observability.*), overridable per service.
firefly:
  observability:
    tracing:
      enabled: true
      bridge: OTEL              # OpenTelemetry (default); BRAVE for Zipkin/B3 estates
      sampling-probability: 1.0 # sample every request; lower it under heavy load
      propagation-type: W3C     # W3C TraceContext headers (traceparent/tracestate)
      baggage-fields:
        - X-Transaction-Id      # carry the transaction id as trace baggage
```

Three choices here are load-bearing. The **`OTEL` bridge** means spans export over
OTLP to any OpenTelemetry-compatible collector; switch a single property to `BRAVE`
and you get B3 propagation for a legacy Zipkin estate, with no code change.
**`W3C` propagation** means the standard `traceparent`/`tracestate` headers carry
the trace across an HTTP hop — so when Lumen's experience tier calls the domain
tier, the same `traceId` continues, and the two services' spans nest into one trace.
And listing **`X-Transaction-Id` as a baggage field** means Firefly's own
correlation id rides along inside the trace context, keeping the log identifier and
the trace identifier joined end to end.

The bridge from a Firefly `WebClient` call to a propagated trace is itself
auto-configured — `TracingWebClientCustomizer` in the observability module installs
the propagation onto outbound clients, so the resilient SDK calls from Chapter 14
carry the trace forward without you wiring a header.

!!! spring "Spring parity"
    This is Micrometer Tracing — the same library a plain Spring Boot 3 app uses —
    over `micrometer-tracing-bridge-otel`. In a hand-rolled service you choose the
    bridge artifact, set the propagation format, register baggage, and wire the
    `WebClient` customizer yourself, in each service. Firefly picks sane defaults
    (`OTEL`, `W3C`, sample-everything, transaction-id baggage) and applies them
    uniformly, so the fleet traces consistently instead of each team deciding
    differently.

## Health and the Actuator surface

Operations needs a flat answer to "is this service alive and ready?", and a metrics
target to scrape. Spring Boot Actuator provides both; Firefly's
`FireflyActuatorAutoConfiguration` ensures the same endpoints are exposed
consistently in every service, driven by the module's default properties:

```yaml
# Firefly observability defaults: Actuator exposure (overridable per service).
management:
  endpoints:
    web:
      exposure:
        include: health, info, metrics, prometheus
  endpoint:
    health:
      probes:
        enabled: true          # Kubernetes liveness/readiness probe groups
  prometheus:
    metrics:
      export:
        enabled: true
```

So out of the box each Lumen service answers `/actuator/health` (with Kubernetes
liveness and readiness probe groups), `/actuator/info`, `/actuator/metrics`, and
`/actuator/prometheus`. Framework modules can contribute their own health detail by
extending `FireflyHealthIndicator` — for example an EDA indicator that reports active
publishers and an error rate — so a service's health rolls up the framework's own
subsystems, not just the datasource.

!!! note "Key term — liveness vs. readiness"
    A **liveness** probe answers "is this process healthy, or should the orchestrator
    restart it?" A **readiness** probe answers "can this instance take traffic right
    now?" — a service may be alive but not ready while it warms a connection pool.
    Spring Boot exposes both as health probe groups; Firefly turns them on by default
    so a Lumen service drops out of a load balancer cleanly during startup and
    shutdown instead of dropping requests.

## Why correlation survives: the reactive linchpin

Now the keystone — the capability the prelude and Chapters 1 and 5 all flagged as
the single most valuable thing Firefly does on the reactive stack, and the reason
every JSON log line above actually carries the *right* `traceId`.

Recall the problem from Chapter 5. A `traceId` lives in a `ThreadLocal` (the MDC is
one). In blocking Java that is fine: one thread serves a request start to finish, so
every log line on that thread carries the correct id. On the reactive stack the
guarantee evaporates — a single request hops across many threads as it crosses
`flatMap`, `publishOn`, and scheduler boundaries, and `ThreadLocal` does **not**
follow it. Left alone, your reactive logs come back blank in the `traceId` field, or
worse, stamped with a *different* request's id.

The fix is one line, and you have seen it before:

```java
Hooks.enableAutomaticContextPropagation();
```

What you have *not* seen is where it lives. It is not in any Lumen source. It is in
the observability module's `ReactiveContextPropagationAutoConfiguration`, which calls
it once in a `@PostConstruct` at startup:

```java
// From org.fireflyframework.observability.tracing
//   .ReactiveContextPropagationAutoConfiguration (illustrative).
@AutoConfiguration
@ConditionalOnClass({Hooks.class, ContextSnapshot.class})
@ConditionalOnProperty(prefix = "firefly.observability.context-propagation",
        name = "reactor-hooks-enabled", havingValue = "true", matchIfMissing = true)
public class ReactiveContextPropagationAutoConfiguration {

    @PostConstruct
    void enableAutomaticContextPropagation() {
        Hooks.enableAutomaticContextPropagation();
    }
}
```

With that hook enabled, Reactor automatically captures registered `ThreadLocal`
values — the MDC, the OpenTelemetry context, the tenant — into the subscription's
Reactor `Context`, and **restores them around every operator, on whatever thread runs
it**, via Micrometer's `ContextSnapshot` bridge. The trace and transaction context
ride the subscription, not the thread. So when the loan-origination core's
`flatMap` chain crosses from one `reactor-http-nio` thread to another, the `traceId`
follows, and the JSON log line written deep in the pipeline carries the correct
correlation — with no `doOnEach`, no manual `contextWrite`, and no MDC bookkeeping in
your code.

This is the concrete payoff of a thread that has run through the whole book. The
multi-tenant `ExecutionContext`, the trace id, the `X-Transaction-Id` — all of them
are `ThreadLocal`-backed values, and all of them survive Lumen's reactive operators
for exactly one reason: the observability module enabled the hook and registered
their accessors for you. It is also why this auto-configuration is conditional and
overridable — `firefly.observability.context-propagation.reactor-hooks-enabled` is
`true` by default (`matchIfMissing = true`), but it is a real property you can read,
audit, and (in the rare case you must) turn off.

!!! warning "Without context propagation, reactive logs lie"
    A correlation id that does not survive operator boundaries is worse than no id:
    it silently attaches the *wrong* request's identity to a log line, and an
    incident investigation chases a ghost. If you ever build reactive code outside
    Firefly, enabling `Hooks.enableAutomaticContextPropagation()` and registering
    your `ThreadLocal` accessors is not optional — it is the line between traceable
    and untraceable production. Inside Firefly, the observability starter has already
    drawn it for you.

!!! spring "Spring parity"
    The mechanism is Micrometer's `context-propagation` library plus Reactor's hook —
    available to any Spring Boot 3 / WebFlux app. In plain Spring you call the hook
    yourself and register each `ThreadLocalAccessor` (for the MDC, for the trace
    context, for your tenant) by hand, in every service, and a service that forgets
    logs blanks. Firefly's `ReactiveContextPropagationAutoConfiguration` does it once,
    gated on the right classes being present, identically across the fleet.

## Where you plug in

Almost everything in this chapter is free and automatic, but two seams are yours to
use when an incident or a dashboard demands it.

**Your own business metrics.** To count or time something domain-specific — say,
loan applications above a threshold — extend `FireflyMetricsSupport` with your
service's module name and let the base prefix it. You inherit the `firefly.`
namespace convention, the null-safety, and the caching, and your metric sits beside
the framework's on the same Prometheus scrape.

**Your own log context.** To add a field to every log line within a unit of work —
an `applicantId`, say — put it in the MDC near the edge and let the encoder lift it,
or write it into the Reactor `Context` and rely on the propagation hook to restore
it. Because the hook is already enabled, a value you place in context survives the
operator hops just like the `traceId` does.

What you should *not* do is reach for manual MDC management inside a reactive chain —
the `doOnEach`/`doFinally` MDC dance that pre-dates the propagation hook. With
automatic context propagation on, that pattern is obsolete and error-prone; let the
framework move the context for you.

## What you learned {.recap}

- Every Lumen service inherits a full observability stack from
  `fireflyframework-observability`, pulled in by each tier starter — **no
  observability code appears in the sample's source**, yet logs, metrics, traces,
  health, and reactive correlation all work.
- Logs are **structured JSON** via a Logstash encoder, with `traceId`, `spanId`, and
  `X-Transaction-Id` promoted to top-level fields from the shared `MdcConstants`
  keys — and PII is masked (Chapter 6) before it is ever written.
- Framework metrics follow one convention, **`firefly.{module}.{metric}`**, enforced
  by `MetricNaming` and applied through the null-safe `FireflyMetricsSupport` base;
  CQRS and EDA meters land under `firefly.cqrs.*` and `firefly.eda.*`, exported to
  Prometheus.
- Tracing bridges to **OpenTelemetry** by default with **W3C** propagation, carrying
  `X-Transaction-Id` as baggage so the log id and the trace id stay joined; Actuator
  exposes health (with Kubernetes probes), info, metrics, and a Prometheus scrape.
- The linchpin is **`Hooks.enableAutomaticContextPropagation()`**, called once by
  `ReactiveContextPropagationAutoConfiguration`, which is *why* trace and tenant
  context survive Reactor's thread hops — concluding the correlation thread the book
  has carried since Chapter 1.

## Try it yourself {.exercises}

1. **Read your own log shape.** Boot the core service (Chapter 2), make one request,
   and copy a JSON log line. Identify the `traceId`, `spanId`, and `X-Transaction-Id`
   fields, then confirm the `X-Transaction-Id` matches the header on the HTTP
   response. You have just done, by hand, what an operator does during an incident.
2. **Find the framework meters.** `curl` the core service's `/actuator/prometheus`
   and grep for `firefly_`. List every `firefly.{module}.{metric}` you find and name
   which chapter's capability produced it.
3. **Trace a hop.** With the experience and domain tiers running, make one BFF call
   that fans out to a downstream service, then confirm both services log the *same*
   `traceId`. That shared id is W3C propagation doing its job across the HTTP hop.
4. **Prove the hook matters.** As a thought experiment, set
   `firefly.observability.context-propagation.reactor-hooks-enabled=false` in a
   scratch profile and predict what the `traceId` field in a log line written after a
   `publishOn` would show. (Answer: blank or wrong — which is exactly the bug the
   default `true` prevents.) Restore the default.
5. **Add a business metric.** Sketch a `LoanMetrics` class extending
   `FireflyMetricsSupport` with module name `"loan"` and a `counter("created")`. Name
   the fully qualified metric it would emit, and confirm it would sit under the same
   `firefly.` namespace as the framework's own.

## Where to go next

You can now see a running Lumen service from the outside — its logs, its metrics, its
traces, all correlated. That visibility is the precondition for everything an
operator does next: alerting on a metric, following a trace to a slow downstream,
querying a transaction id across tiers. With the fleet observable, the remaining
chapters turn to running it — packaging, configuration, and the operational shape of
a Firefly service in production.
