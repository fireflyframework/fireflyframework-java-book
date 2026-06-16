Every tier in Lumen Lending leans on the tier below it. The experience tier composes
a channel response by calling the domain service; the domain service writes through
the core; the core reads enrichment from the data tier. Those calls cross the network,
and the network is where a fleet earns its reputation. A downstream service is slow,
or flapping, or briefly gone — and unless the *caller* is built for it, one sick
dependency drags its callers down with it, until a single timeout cascades into an
outage three tiers wide.

This chapter is about the caller's half of that contract: how a Firefly tier reaches
the tier below it *resiliently* and *configurably*. You will slice the experience
tier's domain client — the SDK seam from the BFF down to `domain-lending-loan-origination` —
and see the pattern Firefly uses everywhere a service calls another: a **port** the
caller depends on, a `@ConfigurationProperties` record that supplies the base URL, a
`ClientFactory`-style `@Configuration` that builds the production bean only when an
operator points it at a real service, and a `WebClient` adapter that does the HTTP.
Then we will look past REST entirely — at the unified, resilient `ServiceClient` that
speaks the same fluent grammar over SOAP, gRPC, GraphQL, and WebSocket, because a
real core-banking platform never gets to pretend everything is JSON over HTTP.

Everything you slice lives in the `exp-lending` module, and a nine-test suite proves
the tier boots and runs with **no domain service and no Docker** — because the
production client is conditional, and a test stub wins by default.

## The seam, restated: a port the caller owns

You met the SDK-seam idea in Chapter 10: the domain tier depends on a
`LoanOriginationClient` *interface*, not a concrete HTTP client, so a test can supply
an in-memory implementation while production supplies the generated SDK. The
experience tier uses the exact same move one tier up. Its port is
`LoanOriginationDomainClient` — the seam from the BFF down to the domain origination
service.

::: listing exp-lending/src/main/java/com/firefly/lumen/exp/client/LoanOriginationDomainClient.java | Listing 16.1 — the experience-to-domain port, a reactive seam
public interface LoanOriginationDomainClient {

    /**
     * Submits a new loan application to the domain origination service.
     *
     * @param request        the channel-shaped create request, already validated by the BFF
     * @param idempotencyKey deterministic key so retries of the same logical request dedupe
     * @return the created application's detail view
     */
    Mono<ApplicationDetailDTO> submitApplication(CreateApplicationRequest request, String idempotencyKey);

    /**
     * Fetches a single loan application by its identifier.
     *
     * @param applicationId  the application's server-assigned identifier
     * @param idempotencyKey deterministic key for safe read retries
     * @return the application's detail view, or an empty {@link Mono} if it does not exist
     */
    Mono<ApplicationDetailDTO> getApplication(UUID applicationId, String idempotencyKey);
}
:::

Two methods, both reactive, both taking an `idempotencyKey` alongside the payload.
The port says *what* the experience tier needs from the domain service and nothing
about *how*. That "how" — the base URL, the timeouts, the HTTP verbs, the retry
policy — is the configurable, swappable part, and the rest of the chapter fills it
in without the port ever changing.

The idempotency key is not decoration. The experience tier derives it
*deterministically* from stable business inputs, so a retried channel request — the
customer double-taps "Apply," the mobile network blips, a gateway replays — produces
the *same* key, and the domain tier dedupes instead of opening a second application.
The key rides down as the standard `Idempotency-Key` header, which you will see the
adapter set.

!!! note "Key term — the SDK seam"
    A **seam** is a port (an interface) at a tier boundary that the caller depends on
    instead of a concrete client. In production it is backed by the *generated SDK* —
    a `WebClient`-based client produced from the downstream service's OpenAPI contract.
    In tests it is backed by an in-memory stub. Because the caller programs to the
    interface, the same handler, service, and controller run unchanged against a real
    service or a stub. The book sample hand-rolls a trimmed port so it compiles and
    tests with no downstream service running; read it as "this is where the generated
    SDK plugs in."

## Config-driven base URLs with @ConfigurationProperties

A BFF that hard-codes `http://domain-service:8082` into its client is a BFF you
cannot promote from dev to staging to prod without recompiling. Firefly's answer is
the same one Spring Boot gives you: bind the address from configuration into a typed,
immutable record. Here is the experience tier's.

::: listing exp-lending/src/main/java/com/firefly/lumen/exp/config/LoanOriginationClientProperties.java | Listing 16.2 — the base URL and timeout, bound from configuration
@ConfigurationProperties(prefix = "lumen.exp.loan-origination")
public record LoanOriginationClientProperties(
        String basePath,
        Duration timeout
) {

    public LoanOriginationClientProperties {
        if (timeout == null) {
            timeout = Duration.ofSeconds(10);
        }
    }
}
:::

Everything under `lumen.exp.loan-origination` in `application.yml` (or an environment
variable, or a config server) binds into this record. `basePath` is the domain
service's URL; `timeout` is the read/connect budget, defaulting to ten seconds via
the compact constructor when nothing sets it. Because it is a `record`, the bound
values are immutable for the life of the application — no setter accidentally mutates
the base URL mid-flight.

The prefix is the whole point. Promoting the service between environments is a
property change:

```yaml
# dev
lumen:
  exp:
    loan-origination:
      base-path: http://localhost:8082
      timeout: 10s
# prod (same code, different config)
# lumen.exp.loan-origination.base-path: https://loan-origination.internal.lumen.bank
```

!!! note "Key term — relaxed binding"
    Spring Boot's **relaxed binding** maps a record component named `basePath` to the
    property key `base-path` (and `BASE_PATH` as an environment variable, and
    `base_path`, …). That is why the property files above write `base-path` while the
    record writes `basePath`, and why the `@ConditionalOnProperty` in the next listing
    names `base-path`. They are the same property; Spring normalizes the spelling.

!!! spring "Spring parity"
    `@ConfigurationProperties` on a `record`, activated with
    `@EnableConfigurationProperties`, is stock Spring Boot — no Firefly annotation in
    sight. Firefly's contribution is the *convention*: every cross-tier client in the
    fleet binds its address this way, under a predictable `*.loan-origination`-shaped
    prefix, so an operator configures the tenth service exactly like the first.

## The ClientFactory pattern: build the prod bean, conditionally

Now the wiring that turns the port and the properties into a live client. Firefly
calls this the **ClientFactory** pattern: a `@Configuration` that builds a `WebClient`
from the bound properties and exposes the port as a bean — but only under two
conditions, so it never gets in the way of a test or a misconfigured environment.

::: listing exp-lending/src/main/java/com/firefly/lumen/exp/config/LoanOriginationClientConfig.java | Listing 16.3 — the ClientFactory: a conditional @Configuration building the prod bean
@Configuration
@EnableConfigurationProperties(LoanOriginationClientProperties.class)
public class LoanOriginationClientConfig {

    private static final Logger log = LoggerFactory.getLogger(LoanOriginationClientConfig.class);
    private static final int MAX_IN_MEMORY_SIZE = 20 * 1024 * 1024;

    /**
     * Builds the {@link WebClient} the domain client adapter uses. Mirrors the codec sizing of the
     * real {@code LoanOriginationClientFactory}.
     */
    @Bean
    @ConditionalOnProperty(prefix = "lumen.exp.loan-origination", name = "base-path")
    @ConditionalOnMissingBean
    public WebClient loanOriginationWebClient(LoanOriginationClientProperties properties) {
        log.info("Building Loan Origination WebClient basePath={} timeout={}",
                properties.basePath(), properties.timeout());
        return WebClient.builder()
                .baseUrl(properties.basePath())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_SIZE))
                .build();
    }

    /**
     * Production {@link LoanOriginationDomainClient}. In the real service this wraps the generated
     * domain SDK {@code LoanOriginationApi}; here it is a {@link WebClient}-backed adapter so the
     * wiring is faithful and chapters can slice it verbatim. Only created when a base path is set
     * and no other client bean (e.g. a test stub) is present.
     */
    @Bean
    @ConditionalOnProperty(prefix = "lumen.exp.loan-origination", name = "base-path")
    @ConditionalOnMissingBean
    public LoanOriginationDomainClient loanOriginationDomainClient(WebClient loanOriginationWebClient) {
        return new WebClientLoanOriginationDomainClient(loanOriginationWebClient);
    }
}
:::

Read the two conditions on each `@Bean`, because together they make this
configuration polite.

`@ConditionalOnProperty(... name = "base-path")` means the bean only materializes when
an operator has actually pointed the experience tier at a real domain service. No base
path, no client — the configuration stays inert rather than building a `WebClient`
aimed at nothing. That is what lets the sample's tests run without any
`lumen.exp.loan-origination.base-path` set: the production beans simply never appear.

`@ConditionalOnMissingBean` means that even *with* a base path, this bean backs off
the instant some other `LoanOriginationDomainClient` is already defined — exactly the
back-off-to-your-bean rule from Chapter 1, applied to a client. A test registers an
in-memory stub, and the production bean yields to it without a profile or a flag.

The `WebClient` itself is built from the bound `basePath`, with its codec sized for
the largest payload the tier expects (here, a generous 20 MB ceiling, mirroring the
real service's factory). In production, the second bean would wrap the *generated*
domain SDK; the sample wraps a hand-written adapter so the wiring is faithful enough
to slice verbatim.

!!! note "Key term — the ClientFactory pattern"
    A **ClientFactory** is a `@Configuration` that assembles a downstream client from
    bound `@ConfigurationProperties` and exposes it behind a port, gated by
    `@ConditionalOnProperty` (only when configured) and `@ConditionalOnMissingBean`
    (only when not overridden). It is the standard Firefly shape for "wire a resilient
    client to the tier below," so every tier's outbound client is configured,
    conditional, and overridable the same way.

!!! spring "Spring parity"
    Every annotation here — `@Configuration`, `@Bean`, `@EnableConfigurationProperties`,
    `@ConditionalOnProperty`, `@ConditionalOnMissingBean` — is plain Spring Boot, the
    same conditionals Firefly's own auto-configurations use. Nothing is hidden. The
    pattern is a convention, not a new mechanism: you could write it by hand in any
    Spring app, and Firefly's value is that every service writes it identically.

## The WebClient adapter: the HTTP, and the idempotency header

The factory hands the port off to a `WebClient`-backed adapter. This is the only
class that knows the domain service speaks HTTP — paths, verbs, headers. Keeping it
behind the port means the experience tier's service, controller, and tests never
import `WebClient` at all.

::: listing exp-lending/src/main/java/com/firefly/lumen/exp/config/WebClientLoanOriginationDomainClient.java | Listing 16.4 — the WebClient adapter: HTTP behind the port, idempotency key on every call
class WebClientLoanOriginationDomainClient implements LoanOriginationDomainClient {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final String APPLICATIONS_PATH = "/api/v1/applications";

    private final WebClient webClient;

    WebClientLoanOriginationDomainClient(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public Mono<ApplicationDetailDTO> submitApplication(CreateApplicationRequest request, String idempotencyKey) {
        return webClient.post()
                .uri(APPLICATIONS_PATH)
                .header(IDEMPOTENCY_HEADER, idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(ApplicationDetailDTO.class);
    }

    @Override
    public Mono<ApplicationDetailDTO> getApplication(UUID applicationId, String idempotencyKey) {
        return webClient.get()
                .uri(APPLICATIONS_PATH + "/{id}", applicationId)
                .header(IDEMPOTENCY_HEADER, idempotencyKey)
                .retrieve()
                .bodyToMono(ApplicationDetailDTO.class);
    }
}
:::

The adapter is a textbook reactive `WebClient` call: `post()` the create request,
`get()` the detail by id, each returning a `Mono` of the channel DTO. The one detail
worth dwelling on is the header. Both methods set `Idempotency-Key` from the
`idempotencyKey` argument the service computed — so the dedupe guarantee travels with
every call, write *and* read. The experience tier derives that key deterministically
upstream (Chapter 6 introduced the idempotency filter that *honors* such a key on the
receiving side); here you see the *sending* side put it on the wire.

!!! note "Key term — X-Transaction-Id propagation"
    Distinct from the idempotency key is the **transaction id**. Chapter 6's
    `TransactionFilter` stamps every response with an `X-Transaction-Id`, minting one
    if the caller did not supply it. For that id to actually correlate a request across
    tiers, an outbound client must *forward* the inbound id on its downstream call.
    Firefly's resilient client does this automatically: the `X-Transaction-Id` carried
    on the inbound exchange is propagated onto the outbound request, so one logical
    operation shares a transaction id from the BFF through the domain tier into the
    core — and the JSON logs line up end to end. The slice's hand-rolled adapter sets
    the idempotency header explicitly; treat transaction-id propagation as a property
    of the generated SDK's resilient client, not something this trimmed adapter wires
    by hand.

## Resilience, applied through Reactor operators

A `WebClient` call is only half a resilient client. The other half is what happens
when the domain service is slow, erroring, or saturated. Firefly's production client
— the resilient `ServiceClient` the generated SDK is built on — wraps every call in a
**Resilience4j** stack, applied not as imperative `try`/`catch` but as *Reactor
operators* on the same `Mono` the adapter returns. Because they are operators, they
compose into the reactive chain without blocking a thread.

Three patterns do the heavy lifting:

- **Circuit breaker.** After a configured proportion of recent calls fail, the breaker
  *opens* and fails fast — every call returns immediately with an error instead of
  waiting on a dependency everyone already knows is sick. After a cooldown it lets a
  trickle of calls through (*half-open*); if they succeed it *closes* again. This is
  what stops one sick tier from cascading into the tiers above it.
- **Retry.** A transient failure — a dropped connection, a `503` during a rolling
  deploy — is retried a bounded number of times, ideally with backoff, so a blip does
  not surface to the customer. Retry is *only* safe because every call carries the
  deterministic idempotency key from Listing 16.4: a retried `submitApplication`
  dedupes downstream instead of opening a second loan.
- **Bulkhead.** A cap on concurrent in-flight calls to the dependency, so a slow
  downstream cannot consume every connection and starve the rest of the service. The
  bulkhead isolates the blast radius to the one dependency that is struggling.

Conceptually, the resilient client decorates the adapter's `Mono` with those
operators — this is illustrative, not a slice from the reactor:

```java
// Illustrative — how the resilient client layers Resilience4j onto the reactive call.
return webClient.post()
        .uri(APPLICATIONS_PATH)
        .header(IDEMPOTENCY_HEADER, idempotencyKey)
        .bodyValue(request)
        .retrieve()
        .bodyToMono(ApplicationDetailDTO.class)
        .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
        .transformDeferred(BulkheadOperator.of(bulkhead))
        .transformDeferred(RetryOperator.of(retry))
        .timeout(properties.timeout());
```

Each `transformDeferred` wraps the call in one Resilience4j decorator; `timeout`
enforces the budget from the bound `LoanOriginationClientProperties`. You tune every
threshold — failure rate, wait duration, max attempts, concurrency cap — through
properties, never code, so the SRE who runs the fleet adjusts a flapping dependency's
breaker without a redeploy.

!!! warning "Retry is only safe with idempotency"
    Retrying a non-idempotent write is how you create two loans from one tap. The
    reason Firefly's resilient client can retry a `POST` at all is that the experience
    tier derives a *deterministic* idempotency key and the adapter sends it on every
    call. Retry and idempotency are a pair — turn on one without the other and you have
    either fragility or duplicates. Never enable retry on a write that does not carry a
    stable idempotency key.

!!! spring "Spring parity"
    Resilience4j ships first-class Reactor operators —
    `CircuitBreakerOperator`, `RetryOperator`, `BulkheadOperator` — that you can apply
    to any `Mono` or `Flux` in a vanilla Spring app. Firefly does not replace them; it
    *pre-wires* them into the generated client with fleet-standard defaults and
    `firefly.*`-tunable thresholds, so every outbound call across the fleet is
    breakered, retried, and bulkheaded the same way instead of each team hand-rolling a
    slightly different `WebClient`.

## Beyond REST: one fluent client, many protocols

So far every call has been JSON over HTTP, because the tiers Lumen owns all speak it.
A real core-banking platform does not get that luxury. The system of record for
accounts is a twenty-year-old **SOAP** service. The fraud engine exposes **gRPC**.
The product catalog is behind a **GraphQL** gateway. A market-data feed is a
**WebSocket** stream. Integrating each with its own bespoke client — a JAX-WS stub
here, a gRPC channel there, an Apollo client somewhere else — is the enterprise tax
from Chapter 1, wearing a protocol costume.

Firefly's answer is a single, unified `ServiceClient` whose *fluent grammar is the
same* regardless of the wire protocol underneath. You select the protocol when you
build the client; the call site reads the same, and — crucially — the same
Resilience4j circuit breaker, retry, bulkhead, timeout, and `X-Transaction-Id`
propagation apply no matter which protocol carries the bytes. Resilience is a property
of the *client*, not of HTTP.

Picture the legacy core-banking SOAP call the domain tier must make to verify an
account. Instead of generating JAX-WS stubs and bolting resilience on by hand, you
reach for the same builder grammar:

```java
// Illustrative — the same fluent ServiceClient, pointed at a legacy SOAP core-banking service.
ServiceClient soap = ServiceClient.soap("core-banking-accounts")
        .baseUrl("https://core-banking.internal.lumen.bank/ws")
        .wsdl("classpath:wsdl/accounts.wsdl")
        .timeout(Duration.ofSeconds(8))
        .circuitBreaker(cb -> cb.failureRateThreshold(50))
        .retry(r -> r.maxAttempts(3))
        .build();

Mono<AccountStatus> status = soap.operation("VerifyAccount")
        .body(new VerifyAccountRequest(iban))
        .execute(AccountStatus.class);
```

Swap `ServiceClient.soap(...)` for `ServiceClient.grpc(...)`, `ServiceClient.graphql(...)`,
or `ServiceClient.websocket(...)` and the *shape* is unchanged — base configuration,
the same resilience knobs, an `execute` that returns a `Mono` (or a `Flux` for a
streaming protocol). The fraud-engine gRPC call, the catalog GraphQL query, and the
market-data WebSocket subscription all read like variations on one client, because
they are:

```java
// Illustrative — gRPC and a WebSocket stream through the same grammar and resilience stack.
Mono<FraudVerdict> verdict = ServiceClient.grpc("fraud-engine")
        .target("fraud.internal.lumen.bank:9090")
        .circuitBreaker(cb -> cb.failureRateThreshold(40))
        .build()
        .method("ScoreApplication")
        .body(applicationId)
        .execute(FraudVerdict.class);

Flux<PriceTick> ticks = ServiceClient.websocket("market-data")
        .url("wss://market.internal.lumen.bank/stream")
        .build()
        .subscribe("rates/EURUSD", PriceTick.class);
```

The win is the same one the whole book argues: a developer who learned the client for
the domain REST call already knows the client for the SOAP core, the gRPC fraud
engine, and the GraphQL catalog. One grammar, one resilience model, one place to tune
thresholds — across every protocol the platform is forced to speak.

!!! note "Key term — the unified ServiceClient"
    Firefly's **`ServiceClient`** is a protocol-agnostic outbound client: a single
    fluent builder and call grammar that targets REST, SOAP, gRPC, GraphQL, or
    WebSocket, chosen at build time. Every variant shares the same resilience stack
    (circuit breaker, retry, bulkhead, timeout) and the same context propagation
    (`X-Transaction-Id`), so cross-cutting behavior is identical regardless of wire
    format. It is the generalization of the REST seam you sliced in this chapter to
    every protocol a banking platform integrates with.

!!! warning "The multi-protocol clients are illustrative here"
    Lumen Lending's tiers integrate over REST, so the *verified* slices in this chapter
    are the REST seam, its properties, its ClientFactory, and its `WebClient` adapter.
    The SOAP, gRPC, GraphQL, and WebSocket snippets above are illustrative — they show
    the shape of the unified `ServiceClient` and where it plugs in, not code this
    chapter's build compiles. Read them as "this is how the same pattern extends beyond
    REST," and reach for the framework's `ServiceClient` reference when you wire a real
    non-HTTP dependency.

## Run it

The experience tier boots and runs without a domain service, and that is precisely
what the conditional client buys you. The test context never sets
`lumen.exp.loan-origination.base-path`, so the production `WebClient` and adapter from
Listing 16.3 never materialize; a `StubLoanOriginationDomainClient` is registered
instead, and `@ConditionalOnMissingBean` guarantees it wins. The controller and
service run their full reactive path against the stub — no HTTP, no Docker. From the
`samples/lumen-lending` directory:

```text
mvn -q -pl exp-lending test
```

The expected result:

```text
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Nine green tests confirm the chapter's claims about the seam: the experience tier
composes a create-and-read round trip through the port, derives a deterministic
idempotency key the stub can observe, and surfaces a missing application as a `404`
problem detail — all without the production client ever being built, because no base
path is configured.

!!! tip "Checkpoint"
    Note *why* the suite is green without a running domain service: the two production
    beans in `LoanOriginationClientConfig` are gated by
    `@ConditionalOnProperty(name = "base-path")` and `@ConditionalOnMissingBean`. With
    no `base-path` set and a test stub present, both conditions keep the prod client
    out of the context. Set `lumen.exp.loan-origination.base-path` in a test profile
    and the `WebClient` bean would try to build — proof that the condition, not luck,
    is what keeps the test hermetic.

## What you built {.recap}

- A reactive **port**, `LoanOriginationDomainClient`, that the experience tier depends
  on to reach the domain origination service — two `Mono`-returning methods, each
  carrying a deterministic idempotency key, with no HTTP detail leaking past the
  interface.
- A `@ConfigurationProperties` record, `LoanOriginationClientProperties`, that binds
  the downstream **base URL and timeout** from configuration under the
  `lumen.exp.loan-origination` prefix, so the same code promotes across environments by
  property change.
- A **ClientFactory** `@Configuration` that builds the production `WebClient` and
  client bean only when `@ConditionalOnProperty(base-path)` is satisfied and
  `@ConditionalOnMissingBean` confirms nothing overrides it — the back-off-to-your-bean
  rule applied to an outbound client.
- A `WebClient` **adapter** that does the actual HTTP behind the port and sets the
  `Idempotency-Key` header on every call, plus the conceptual picture of the
  **Resilience4j** circuit breaker, retry, and bulkhead applied through Reactor
  operators, and `X-Transaction-Id` propagation across the hop.
- The **unified `ServiceClient`** — the same fluent, resilient grammar over SOAP, gRPC,
  GraphQL, and WebSocket — and an honest line between what this REST slice verifies and
  what is illustrative.
- A passing nine-test suite (`Tests run: 9, Failures: 0`) that runs the whole tier
  against an in-memory stub, with the production client conditioned out — no domain
  service, no Docker.

## Try it yourself {.exercises}

1. **Configure the prod client into existence.** In an `exp-lending` test, add a
   property source that sets `lumen.exp.loan-origination.base-path` and *remove* the
   stub registration. Observe that `LoanOriginationClientConfig` now builds the
   `WebClient` bean (watch for the `Building Loan Origination WebClient` log line).
   Explain, in one sentence, which condition flipped.
2. **Override the client with your own bean.** Leave a `base-path` configured, then
   define a second `LoanOriginationDomainClient` `@Bean` in a test configuration.
   Confirm the production bean backs off and yours is injected — then name the single
   annotation that made that possible.
3. **Trace the idempotency key.** Open `StubLoanOriginationDomainClient` under
   `src/test/java` and find `idempotencyKeys()`. Write a test that submits the *same*
   logical request twice and asserts the recorded key is identical both times, proving
   the experience tier derives it deterministically rather than minting a random one.
4. **Change the default timeout.** The compact constructor of
   `LoanOriginationClientProperties` defaults `timeout` to ten seconds. Add a test that
   binds the properties with no `timeout` and asserts the default, then one that sets
   `timeout: 3s` and asserts the override — proving configuration, not code, owns the
   budget.
5. **Sketch a SOAP seam.** Without running it, write the `ServiceClient.soap(...)`
   builder you would use for the legacy core-banking account-verify call, including a
   circuit breaker and a retry. Name which two cross-cutting behaviors you get *for
   free* from the unified client that you would otherwise hand-wire into a JAX-WS stub.

## Where to go next

You now have the caller's half of every tier boundary: a port, config-driven
addressing, a conditional ClientFactory, a resilient adapter, and a unified client
that reaches beyond REST. The next chapters put these calls under load and under
watch — the observability that makes the `X-Transaction-Id` you propagated here
actually traceable across the fleet, and the tests that exercise a full request from
the experience tier down through the domain saga into the core system of record.
