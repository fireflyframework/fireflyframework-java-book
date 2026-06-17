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
health and a Prometheus scrape; and already keeps that correlation context alive
across every `flatMap` and `publishOn` — without a single line of observability code
in any controller, handler, or service you wrote. None of it is in the sample's
source, because all of it ships in the `fireflyframework-observability` module that
every tier starter pulls in. You already saw the proof in Chapter 2: the core
service's boot log printed `Reactor automatic context propagation enabled`, and a
loan request came back stamped with a `traceId` and `spanId` it never asked for.
This chapter shows you exactly what that module wires, why the reactive correlation
actually works, and where you plug in when you need more.

There is no companion test for this chapter — observability is about what a *running*
service emits to logs, a metrics scrape, and a trace collector, not about a value a
`StepVerifier` can assert. So everything below is illustrative: real configuration
and real framework code, read to understand how the wiring behaves, rather than a
slice a build verifies. Where a claim rests on framework source, the prose names the
class so you can open it yourself; where it rests on configuration, it names the
property in the module's `application-firefly-observability.yml`.

## What the starter already gives you

Lumen's three services declare `fireflyframework-starter-core` (the core tier on
`:8081`), `-starter-domain` (the domain tier on `:8082`), and
`-starter-application` (the experience BFF on `:8080`). Each of those starters
depends, transitively, on `fireflyframework-observability` — so the moment a Lumen
service is on the classpath, a full observability stack auto-configures itself. You
opted into none of it per service; it arrived with the starter, exactly the way
Chapter 1 promised cross-cutting concerns would.

Concretely, with no code and no configuration in the sample, every Lumen service
gets:

- **Structured JSON logs** to the console — Spring Boot 3's native structured
  logging set to the `logstash` format — with the trace and transaction context
  promoted to top-level fields.
- **Micrometer metrics** under a single `firefly.{module}.{metric}` namespace,
  exported to Prometheus by default.
- **Distributed tracing** bridged to **OpenTelemetry** by default, using W3C
  TraceContext propagation, with `X-Transaction-Id` carried as trace baggage.
- **Actuator endpoints** for health, info, metrics, and a Prometheus scrape,
  exposed and configured consistently — including Kubernetes liveness/readiness
  probe groups and graceful shutdown.
- **Reactive context propagation** — `Hooks.enableAutomaticContextPropagation()`
  called once at startup — which is *why* all of the above survive a reactive
  request's thread hops.

All of that is driven by one profile the module contributes,
`application-firefly-observability.yml`, included automatically and overridable by
your own `application.yml`. The rest of the chapter walks each capability, grounded
in the framework class or property that implements it.

!!! spring "Spring parity"
    Every piece here is a standard Spring Boot 3 / Micrometer mechanism: Spring
    Boot's structured logging (`logging.structured.format.console`), Micrometer
    `MeterRegistry`, Micrometer Tracing over an OpenTelemetry bridge, Spring Boot
    Actuator, and Micrometer's `context-propagation` library. You could assemble all
    of it by hand in a plain WebFlux app. Firefly's contribution is that it is
    assembled *once*, tuned by `firefly.observability.*` properties, and identical
    across the fleet — so the tenth service correlates its logs exactly like the
    first.

## A real JSON log line

Start with the artifact you will stare at most during an incident: a log line. In a
dev profile you might see a pretty, colorized console line (the module ships that
appender too, gated on a `dev` profile), but by default every Lumen service emits
**structured JSON**, one object per event. That is not an application choice — it is
the observability profile setting Spring Boot's native structured console format to
`logstash`:

```yaml
# From application-firefly-observability.yml (the module's default profile).
firefly:
  observability:
    logging:
      enabled: true
      structured-format: logstash
logging:
  structured:
    format:
      console: ${firefly.observability.logging.structured-format:logstash}
```

You saw the *startup* form of these lines in Chapter 2 — `FIREFLY EDA …`,
`CQRS Query Bus configured …`, `Netty started on port 8081`. The lines that matter
in an incident are the ones written *on a request thread*, because those carry the
correlation context. When the loan-origination core logs while handling a request,
the line on the wire looks like this:

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
the same one the web module's `TransactionFilter` stamps onto every request and
response (Chapter 6) — the very value you watched it mint in the Chapter 2 boot
sequence, `Generated new transaction ID: ce0c2ede-…`. An operator can take an
`X-Transaction-Id` off a response header, paste it into a log query, and pull back
every line the request produced across every tier.

Those exact field names are not incidental. They are constants the whole framework
shares, defined in `org.fireflyframework.observability.logging.MdcConstants`:
`TRACE_ID = "traceId"`, `SPAN_ID = "spanId"`, and
`TRANSACTION_ID = "X-Transaction-Id"`, alongside `userId`, `correlationId`,
`requestId`, and a few event-sourcing keys. The same set is mirrored in the module's
`logback-firefly.xml`, where the Logstash encoder is told to lift exactly those MDC
keys to top-level fields:

```xml
<!-- From fireflyframework-observability logback-firefly.xml (illustrative). -->
<includeMdcKeyName>traceId</includeMdcKeyName>
<includeMdcKeyName>spanId</includeMdcKeyName>
<includeMdcKeyName>X-Transaction-Id</includeMdcKeyName>
<includeMdcKeyName>userId</includeMdcKeyName>
<includeMdcKeyName>correlationId</includeMdcKeyName>
<includeMdcKeyName>requestId</includeMdcKeyName>
```

Because every module logs through the same keys, log aggregation across a fleet is
uniform — one query shape works everywhere. A `traceId` filter in your log backend
matches the core, the domain, and the experience tier identically, with no
per-service field-mapping to maintain.

!!! note "Key term — MDC (Mapped Diagnostic Context)"
    The **MDC** is SLF4J's per-context key/value map that a logging encoder can read
    and attach to every line. In classic blocking Java, you put a `traceId` into the
    MDC at the start of a request and every log line on that thread carries it for
    free. On the reactive stack that breaks — and fixing it is the linchpin of this
    chapter (see *Why correlation survives*, below). Firefly's encoder lifts the MDC
    keys in `MdcConstants` to first-class JSON fields, so once the context is
    *present* on the running thread, it is *logged* without any per-statement work.

!!! warning "PII never reaches these logs by accident"
    A log line that carries a `traceId` must never carry a national ID or a card
    number. Chapter 6's PII masking is the other half of this story: the web module
    redacts personally identifiable data — emails, national IDs, card numbers —
    *before* it is logged. Structured logging makes lines queryable; PII masking
    keeps them safe to query. Treat the two as a pair: you want every request
    traceable and no request leaking. The correlation id is your handle on a request;
    it is never the applicant's identity.

## Metrics: one namespace for the whole fleet

Logs tell you about one request; metrics tell you about all of them at once — rates,
durations, error ratios, queue depths. Firefly emits its framework metrics through
Micrometer and imposes a single naming convention so that a dashboard built for one
service reads the same on the next: **`firefly.{module}.{metric}`**.

That convention is enforced in code, not by hand. The class
`org.fireflyframework.observability.metrics.MetricNaming` builds every framework
metric name from a module and a metric, and rejects a module that is not lowercase
alphanumeric:

```java
// From org.fireflyframework.observability.metrics.MetricNaming (illustrative).
public static final String FIREFLY_PREFIX = "firefly";

public static String prefix(String module) {            // e.g. "cqrs" -> "firefly.cqrs"
    if (module == null || !module.matches("[a-z][a-z0-9]*")) {
        throw new IllegalArgumentException(
                "Module must be lowercase alphanumeric starting with a letter: " + module);
    }
    return FIREFLY_PREFIX + "." + module;
}

public static String name(String prefix, String metric) {   // "firefly.cqrs" + "command.processed"
    if (metric == null || metric.isBlank()) {
        throw new IllegalArgumentException("Metric name must not be blank");
    }
    return prefix + "." + metric;                            //  -> "firefly.cqrs.command.processed"
}
```

Module metrics extend a shared base, `FireflyMetricsSupport`, which prefixes every
counter, timer, summary, and gauge automatically and caches them in a
`ConcurrentHashMap`. A module's metrics class names only its own short metric — the
`firefly.{module}.` prefix is supplied for it, and the tag keys come from the shared
`MetricTags` constants (`command.type`, `event.type`, `status`, `error.type`, …):

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
R2DBC pool gauges), all from the one registry, and every meter carries an
`application` tag set from `spring.application.name` so a shared dashboard can split
by service.

One detail worth internalizing: `FireflyMetricsSupport` is **null-safe**. Read its
`counter(...)` method and the first line is `if (meterRegistry == null) return
NOOP_COUNTER;`. When no `MeterRegistry` is on the classpath — say a slice test with
Actuator absent — every counter, timer, summary, and gauge becomes a pre-allocated
no-op rather than a `NullPointerException`. Metrics instrumentation never changes
whether your business logic runs; it only observes it. Its timers also publish p50,
p95, and p99 percentiles by default, so a latency panel works the moment a meter
appears.

!!! note "Key term — Micrometer Observation"
    Micrometer's **Observation** API is the unifying idea beneath all of this: you
    "observe" a unit of work *once*, and Micrometer can fan that single observation
    out into a metric (a timer/counter) *and* a trace span — through whatever
    backends are registered. Firefly's `FireflyMetricsSupport` records timers and
    counters against the Micrometer registry, and its tracing bridge turns request
    boundaries into spans; both feed off the same registry, which is why a metric's
    timing and a trace's span agree about the same operation.

!!! tip "Checkpoint"
    There is no metric assertion to run here — a metric is a property of a *running*
    registry, not a signal `StepVerifier` checks. To see the convention live, boot
    the core service (Chapter 2) and `curl` its Prometheus endpoint at
    `http://localhost:8081/actuator/prometheus`. Search the output for `firefly_`
    (Prometheus renders the dots as underscores, so `firefly.cqrs.command.processed`
    appears as `firefly_cqrs_command_processed`) and you will find the framework's
    meters namespaced exactly as `MetricNaming` builds them.

## Tracing, bridged to OpenTelemetry

A `traceId` in a log line is only half the value; the other half is a **trace** — the
tree of spans, one per service hop, that reconstructs a request's whole journey
through the fleet. Firefly configures Micrometer Tracing with an **OpenTelemetry**
bridge by default, the vendor-neutral standard that virtually every trace collector
(Jaeger, Tempo, Honeycomb, a cloud APM) speaks.

You can read the defaults straight off the observability profile — the prefix is
`firefly.observability`, so they are overridable per service or fleet-wide:

```yaml
# From application-firefly-observability.yml (firefly.observability.*).
firefly:
  observability:
    metrics:
      enabled: true
      prefix: firefly
      exporter: PROMETHEUS        # PROMETHEUS (default), OTLP, or BOTH — no POM changes
    tracing:
      enabled: true
      bridge: OTEL                # OpenTelemetry (default); BRAVE for Zipkin/B3 estates
      sampling-probability: 1.0   # sample every request; lower it in production
      propagation-type: W3C       # W3C TraceContext (composite propagator also reads B3)
      baggage-fields:
        - X-Transaction-Id        # carry the transaction id as trace baggage
```

Four choices here are load-bearing. The **`OTEL` bridge** means spans export over
OTLP to any OpenTelemetry-compatible collector (the profile targets
`otel/opentelemetry-collector-contrib` on the standard ports — gRPC `4317`, HTTP
`4318`); switch the `bridge` property to `BRAVE` and you get B3 propagation for a
legacy Zipkin estate, with no code or POM change. The **`PROMETHEUS` exporter** is
the default for metrics, switchable to `OTLP` or `BOTH` by the same kind of property
flip. **`W3C` propagation** means the standard `traceparent`/`tracestate` headers
carry the trace across an HTTP hop — and because the underlying propagator is a
composite (`W3C,B3`), a hop from a B3-only neighbor is still read correctly. So when
Lumen's experience tier calls the domain tier, the same `traceId` continues, and the
two services' spans nest into one trace. And listing **`X-Transaction-Id` as a
baggage field** means Firefly's own correlation id rides along inside the trace
context, keeping the log identifier and the trace identifier joined end to end.

There is one Firefly-specific seam on outbound calls. Spring Boot already
auto-configures an `ObservationWebClientCustomizer` that propagates standard trace
context (the `traceparent` header) on every `WebClient` request when
`micrometer-tracing` is present. Firefly's `TracingWebClientCustomizer` adds *only*
the `X-Transaction-Id` header on top of that — and it reads the value from the
Reactor `Context` first, falling back to the MDC for non-reactive callers:

```java
// From org.fireflyframework.observability.tracing.TracingWebClientCustomizer (illustrative).
builder.filter((request, next) ->
        Mono.deferContextual(ctx -> {
            String txId = ctx.getOrDefault(MdcConstants.TRANSACTION_ID, (String) null);
            if (txId == null) {
                txId = MDC.get(MdcConstants.TRANSACTION_ID);
            }
            if (txId != null) {
                return next.exchange(ClientRequest.from(request)
                        .header(MdcConstants.TRANSACTION_ID_HEADER, txId)
                        .build());
            }
            return next.exchange(request);
        }));
```

That is why the live `exp → domain → core` submit you ran in Chapter 2 — the BFF's
`WebClient` calling the domain, the saga's root step calling the core — carries both
the standard trace *and* Firefly's transaction id forward across each HTTP hop,
without you wiring a single header. (Reading the transaction id from the Reactor
`Context` is the same propagation trick the next section explains in full.)

!!! spring "Spring parity"
    This is Micrometer Tracing — the same library a plain Spring Boot 3 app uses —
    over `micrometer-tracing-bridge-otel`, with Spring Boot's own
    `ObservationWebClientCustomizer` doing the standard W3C propagation. In a
    hand-rolled service you choose the bridge artifact, set the propagation format,
    register baggage, and decide on each `WebClient` whether to carry your own
    correlation header. Firefly picks sane defaults (`OTEL`, `W3C`/B3-composite,
    sample-everything, transaction-id baggage) and adds the `X-Transaction-Id`
    customizer uniformly, so the fleet traces consistently instead of each team
    deciding differently.

## Health and the Actuator surface

Operations needs a flat answer to "is this service alive and ready?", and a metrics
target to scrape. Spring Boot Actuator provides both; Firefly's
`FireflyActuatorAutoConfiguration` ensures the same endpoints are exposed
consistently in every service, driven by the module's default profile:

```yaml
# From application-firefly-observability.yml: Actuator exposure (overridable).
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: always
      show-components: always
      probes:
        enabled: true                 # Kubernetes liveness/readiness probe groups
      group:
        liveness:
          include: livenessState
        readiness:
          include: readinessState,db,diskSpace
server:
  shutdown: graceful                  # drain in-flight requests before exit
```

So out of the box each Lumen service answers `/actuator/health`, `/actuator/info`,
`/actuator/metrics`, and `/actuator/prometheus`. Because `show-components: always`
and `show-details: always` are set, a single health call rolls up the framework's
subsystems, not just the datasource. You saw the exact shape in Chapter 2 against
the core service — `cqrs`, `eda`, `r2dbc`, and `ping`, with details:

```text
$ curl -s http://localhost:8081/actuator/health
{"status":"UP","groups":["liveness","readiness"],"components":{
  "cqrs":{"status":"UP","details":{"command_bus":"UP","query_bus":"UP","command_handlers":0,"query_handlers":0}},
  "eda":{"status":"UP","details":{"enabled":true,"message":"All EDA components are healthy"}},
  "r2dbc":{"status":"UP","details":{"database":"H2"}},
  "ping":{"status":"UP"}}}
```

Those subsystem components are not built in to Actuator — framework modules
contribute them by extending `FireflyHealthIndicator`, a base that gives every
indicator a component name and standard detail helpers. Its own Javadoc shows the
pattern an EDA indicator follows: report active publishers, then fold an error rate
against a threshold so the component flips to `DOWN` if it is breached.

```java
// From org.fireflyframework.observability.health.FireflyHealthIndicator (illustrative).
public class EdaHealthIndicator extends FireflyHealthIndicator {
    public EdaHealthIndicator() {
        super("eda");
    }
    @Override
    protected void doHealthCheck(Health.Builder builder) throws Exception {
        builder.up().withDetail("publishers.active", getActivePublishers());
        addErrorRate(builder, getErrorRate(), 0.05);   // DOWN if error rate > 5%
    }
}
```

So a Lumen service's health rolls up the framework's own subsystems with consistent
detail shapes — `error.rate`, `pool.active`, `latency.p99.ms` — across every module
that ships an indicator.

!!! note "Key term — liveness vs. readiness"
    A **liveness** probe answers "is this process healthy, or should the orchestrator
    restart it?" A **readiness** probe answers "can this instance take traffic right
    now?" — a service may be alive but not ready while it warms a connection pool.
    Spring Boot exposes both as health probe groups; Firefly turns them on by
    default (the `readiness` group folds in `db` and `diskSpace`) and pairs them with
    `server.shutdown: graceful`, so a Lumen service drops out of a load balancer
    cleanly during startup and shutdown instead of dropping requests.

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
it once in a `@PostConstruct` at startup — and logs the very line you watched scroll
past in the Chapter 2 boot sequence:

```java
// From org.fireflyframework.observability.tracing
//   .ReactiveContextPropagationAutoConfiguration (illustrative).
@AutoConfiguration
@ConditionalOnClass({Hooks.class, ContextSnapshot.class})
@ConditionalOnProperty(prefix = "firefly.observability.context-propagation",
        name = "reactor-hooks-enabled", havingValue = "true", matchIfMissing = true)
public class ReactiveContextPropagationAutoConfiguration {

    private static final Logger log =
            LoggerFactory.getLogger(ReactiveContextPropagationAutoConfiguration.class);

    @PostConstruct
    void enableAutomaticContextPropagation() {
        Hooks.enableAutomaticContextPropagation();
        log.info("Reactor automatic context propagation enabled — ThreadLocal/MDC values " +
                "will automatically bridge to Reactor Context across thread boundaries");
    }
}
```

That `@PostConstruct` is why the core service's boot log carried this line, byte for
byte, between the Actuator and Netty lines in Chapter 2:

```text
{"timestamp":"2026-06-17T08:21:44.319+0000","message":"Reactor automatic context propagation enabled — ThreadLocal/MDC values will automatically bridge to Reactor Context across thread boundaries","logger":"o.f.o.t.ReactiveContextPropagationAutoConfiguration","level":"INFO"}
```

With that hook enabled, Reactor automatically captures registered `ThreadLocal`
values — the MDC, the OpenTelemetry context, the tenant — into the subscription's
Reactor `Context`, and **restores them around every operator, on whatever thread runs
it**, via Micrometer's `ContextSnapshot` bridge. (That bridge is the very reason the
auto-configuration is `@ConditionalOnClass({Hooks.class, ContextSnapshot.class})` —
it activates only when `io.micrometer:context-propagation` is present to do the
restoring.) The trace and transaction context ride the subscription, not the thread.
So when the loan-origination core's `flatMap` chain crosses from one
`reactor-http-nio` thread to another, the `traceId` follows, and the JSON log line
written deep in the pipeline carries the correct correlation — with no `doOnEach`, no
manual `contextWrite`, and no MDC bookkeeping in your code.

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
namespace convention, the null-safety, the percentile-publishing timers, and the
caching, and your metric sits beside the framework's on the same Prometheus scrape.

```java
// Sketch: a domain-specific metric on the shared base (illustrative).
public class LoanMetrics extends FireflyMetricsSupport {
    public LoanMetrics(@Nullable MeterRegistry registry) {
        super(registry, "loan");                    // module = "loan"
    }
    public void created() {
        counter("created").increment();             // -> firefly.loan.created
    }
}
```

**Your own log context.** To add a field to every log line within a unit of work —
an `applicantId`, say — put it in the MDC near the edge and let the encoder lift it,
or write it into the Reactor `Context` and rely on the propagation hook to restore
it. Because the hook is already enabled, a value you place in context survives the
operator hops just like the `traceId` does. (Add the key to the encoder's
`includeMdcKeyName` list if you want it as a top-level JSON field rather than a
nested one.)

What you should *not* do is reach for manual MDC management inside a reactive chain —
the `doOnEach`/`doFinally` MDC dance that pre-dates the propagation hook. With
automatic context propagation on, that pattern is obsolete and error-prone; the
`ReactiveContextPropagationAutoConfiguration` Javadoc says as much in so many words.
Let the framework move the context for you.

## What you learned {.recap}

- Every Lumen service inherits a full observability stack from
  `fireflyframework-observability`, pulled in by each tier starter and configured by
  one profile (`application-firefly-observability.yml`) — **no observability code
  appears in the sample's source**, yet logs, metrics, traces, health, and reactive
  correlation all work.
- Logs are **structured JSON** — Spring Boot's native `logstash` console format —
  with `traceId`, `spanId`, and `X-Transaction-Id` promoted to top-level fields from
  the shared `MdcConstants` keys; PII is masked (Chapter 6) before it is ever
  written.
- Framework metrics follow one convention, **`firefly.{module}.{metric}`**, enforced
  by `MetricNaming` and applied through the null-safe `FireflyMetricsSupport` base
  (cached, percentile-publishing); CQRS and EDA meters land under `firefly.cqrs.*`
  and `firefly.eda.*`, tagged with the `application` name and exported to Prometheus.
- Tracing bridges to **OpenTelemetry** by default with **W3C** (B3-composite)
  propagation, carrying `X-Transaction-Id` as baggage — added on outbound
  `WebClient` calls by `TracingWebClientCustomizer` — so the log id and the trace id
  stay joined; Actuator exposes health (with Kubernetes probes and graceful
  shutdown), info, metrics, and a Prometheus scrape, and modules contribute health
  components via `FireflyHealthIndicator`.
- The linchpin is **`Hooks.enableAutomaticContextPropagation()`**, called once by
  `ReactiveContextPropagationAutoConfiguration` (the boot line you saw in Chapter 2),
  which is *why* trace and tenant context survive Reactor's thread hops — concluding
  the correlation thread the book has carried since Chapter 1.

## Try it yourself {.exercises}

1. **Read your own log shape.** Boot the core service (Chapter 2) on `:8081`, make
   one request, and copy a JSON log line written on the request thread. Identify the
   `traceId`, `spanId`, and `X-Transaction-Id` fields, then confirm the
   `X-Transaction-Id` matches the `Generated new transaction ID` value from
   `TransactionFilter`. You have just done, by hand, what an operator does during an
   incident.
2. **Find the framework meters.** `curl` the core service's
   `http://localhost:8081/actuator/prometheus` and grep for `firefly_`. List every
   `firefly.{module}.{metric}` you find and name which chapter's capability produced
   it. Confirm each meter carries an `application` tag.
3. **Trace a hop.** With all three tiers up, make the live BFF submit from Chapter 2
   (`POST /api/v1/experience/lending/applications` on `:8080`) that fans out
   `exp → domain → core`, then confirm all three services log the *same* `traceId`.
   That shared id is W3C propagation doing its job across two HTTP hops, with the
   `X-Transaction-Id` riding along via `TracingWebClientCustomizer`.
4. **Prove the hook matters.** As a thought experiment, set
   `firefly.observability.context-propagation.reactor-hooks-enabled=false` in a
   scratch profile and predict what the `traceId` field in a log line written after a
   `publishOn` would show. (Answer: blank or wrong — which is exactly the bug the
   default `true` prevents.) Note that the boot log would also lose the
   `Reactor automatic context propagation enabled` line, because the
   `@PostConstruct` would no longer run. Restore the default.
5. **Add a business metric.** Take the `LoanMetrics` sketch above, with module name
   `"loan"` and a `counter("created")`. Name the fully qualified metric it would emit
   (`firefly.loan.created`), its Prometheus rendering (`firefly_loan_created_total`),
   and confirm it would sit under the same `firefly.` namespace as the framework's
   own — because `MetricNaming.prefix("loan")` builds it the same way it builds
   `firefly.cqrs`.

## Where to go next

You can now see a running Lumen service from the outside — its logs, its metrics, its
traces, all correlated. That visibility is the precondition for everything an
operator does next: alerting on a metric, following a trace to a slow downstream,
querying a transaction id across tiers. With the fleet observable, the remaining
chapters turn to running it — packaging, configuration, and the operational shape of
a Firefly service in production.
