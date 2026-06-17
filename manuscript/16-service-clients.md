Every tier in Lumen Lending leans on the tier below it. The experience tier composes
a channel response by calling the domain service; the domain service writes through
the core; the core reads enrichment from the data tier. Those calls cross the network,
and the network is where a fleet earns its reputation. A downstream service is slow,
or flapping, or briefly gone — and unless the *caller* is built for it, one sick
dependency drags its callers down with it, until a single timeout cascades into an
outage three tiers wide.

This chapter is about the caller's half of that contract: how a Firefly tier reaches
the tier below it *resiliently* and *configurably*. Lumen now runs **end to end** —
a single channel `POST` to the BFF flows `exp → domain → core` and lands a row in the
core system of record — so this chapter slices **two** live seams, one per hop. First
the experience tier's domain client: the SDK seam from the BFF down to
`domain-lending-loan-origination`. Then the freshly-live **domain-to-core** seam: a
`WebClient` the orchestration tier's saga uses to write to the core service over HTTP,
and to *delete* over HTTP when it has to compensate. Both seams use the identical
pattern Firefly uses everywhere a service calls another: a **port** the caller depends
on, a `@ConfigurationProperties` record that supplies the base URL, a
`ClientFactory`-style `@Configuration` that builds the production bean *only* when an
operator points it at a real service, and a `WebClient` adapter that does the HTTP.
Then we will look past REST entirely — at the unified, resilient `ServiceClient` that
speaks the same fluent grammar over SOAP, gRPC, GraphQL, and WebSocket, because a
real core-banking platform never gets to pretend everything is JSON over HTTP.

Everything you slice is live in the reactor, and the test suites prove each tier boots
and runs **with no Docker** and, by default, **no live downstream** — because the
production clients are conditional, and a test stub wins by default. The experience
tier's nine tests run against an in-memory domain stub; the domain tier's six run
against an in-memory core stub; the live HTTP clients only materialize when an operator
configures a base path.

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
property change. The reactor's shipped `exp-lending` `src/main/resources/application.yml`
points the BFF at the locally-running domain tier — which is exactly how a single
channel `POST` reaches the domain service when you run the full stack:

```yaml
# exp-lending — runnable profile
lumen:
  exp:
    loan-origination:
      base-path: http://localhost:8082
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

The adapter is a textbook reactive `WebClient` call: `post()` the create request to
`/api/v1/applications`, `get()` the detail by id, each returning a `Mono` of the
channel DTO. And `/api/v1/applications` is not an arbitrary string — it is precisely
the path the domain tier serves, which is how this hop actually connects when the
full stack is up. The one detail worth dwelling on is the header: both methods set
`Idempotency-Key` from the `idempotencyKey` argument the service computed — so the
dedupe guarantee travels with every call, write *and* read. The experience tier
derives that key deterministically upstream (Chapter 6 introduced the idempotency
filter that *honors* such a key on the receiving side); here you see the *sending*
side put it on the wire.

!!! note "Key term — X-Transaction-Id propagation"
    Distinct from the idempotency key is the **transaction id**. Chapter 6's
    `TransactionFilter` stamps every response with an `X-Transaction-Id`, minting one
    if the caller did not supply it. For that id to actually correlate a request across
    tiers, an outbound client must *forward* the inbound id on its downstream call.
    Firefly's resilient client does this automatically: the `X-Transaction-Id` carried
    on the inbound exchange is propagated onto the outbound request, so one logical
    operation shares a transaction id from the BFF through the domain tier into the
    core — and the JSON logs line up end to end. The slices' hand-rolled adapters set
    the idempotency header explicitly; treat transaction-id propagation as a property
    of the generated SDK's resilient client, not something these trimmed adapters wire
    by hand.

## The second hop: the now-live domain-to-core seam

The experience adapter is the *top* hop. Trace the request further and it reaches the
domain tier's `LoanOriginationController`, which runs the `RegisterApplicationSaga`,
whose root step must write to the **core** system of record. That bottom hop used to
be in-JVM only; it is now genuinely live over HTTP, and it is built from the same four
pieces — port, properties, ClientFactory, adapter — one tier down. The port is the
same `LoanOriginationClient` you met in Chapter 10, but now there is a real
`WebClient`-backed implementation behind it.

Start with the properties record, which binds the core service's address under a
`firefly.*` prefix (the namespace the real domain service uses, distinct from the
`lumen.exp.*` prefix of the BFF):

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/config/CoreLoanOriginationProperties.java | Listing 16.5 — the core base URL and timeout, bound from configuration
@ConfigurationProperties(prefix = "firefly.lumen.core.loan-origination")
public record CoreLoanOriginationProperties(
        String basePath,
        Duration timeout
) {

    public CoreLoanOriginationProperties {
        if (timeout == null) {
            timeout = Duration.ofSeconds(10);
        }
    }
}
:::

Identical shape to the experience tier's `LoanOriginationClientProperties` — a
`basePath`, a `timeout` defaulted to ten seconds by the compact constructor — proof
that the convention is the same regardless of which two tiers a seam connects. The
domain's runnable `application.yml` sets `firefly.lumen.core.loan-origination.base-path`
to `http://localhost:8081`, which is what makes the saga's root step actually reach
the running core service.

Now the ClientFactory. This is the domain-to-core equivalent of Listing 16.3, with
one extra responsibility: it registers the *same* live client for two seams at once —
the write-side `LoanOriginationClient` the saga drives, and a read-side
`CoreLoanApplicationReader` the domain's GET-by-id endpoint uses.

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/config/LiveLoanOriginationClientConfig.java | Listing 16.6 — the live core ClientFactory: conditional WebClient + client, registered for both seams
@Configuration
@EnableConfigurationProperties(CoreLoanOriginationProperties.class)
public class LiveLoanOriginationClientConfig {

    private static final Logger log = LoggerFactory.getLogger(LiveLoanOriginationClientConfig.class);
    private static final int MAX_IN_MEMORY_SIZE = 20 * 1024 * 1024;

    @Bean
    @ConditionalOnProperty(prefix = "firefly.lumen.core.loan-origination", name = "base-path")
    @ConditionalOnMissingBean(name = "coreLoanOriginationWebClient")
    public WebClient coreLoanOriginationWebClient(CoreLoanOriginationProperties properties) {
        log.info("Building core Loan Origination WebClient basePath={} timeout={}",
                properties.basePath(), properties.timeout());
        return WebClient.builder()
                .baseUrl(properties.basePath())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_SIZE))
                .build();
    }

    /**
     * The live core client, registered for both the write seam ({@link LoanOriginationClient}) and
     * the read seam ({@link CoreLoanApplicationReader}). Only created when a base path is set and no
     * other {@link LoanOriginationClient} (e.g. a test stub) is present.
     */
    @Bean
    @ConditionalOnProperty(prefix = "firefly.lumen.core.loan-origination", name = "base-path")
    @ConditionalOnMissingBean(LoanOriginationClient.class)
    public WebClientLoanOriginationClient webClientLoanOriginationClient(WebClient coreLoanOriginationWebClient) {
        log.info("Wiring live WebClient LoanOriginationClient against core; the saga writes to core over HTTP");
        return new WebClientLoanOriginationClient(coreLoanOriginationWebClient);
    }
}
:::

The two conditions read exactly like the experience tier's, with the prefix changed
to `firefly.lumen.core.loan-origination` — and they buy the same two properties.
`@ConditionalOnProperty(name = "base-path")` keeps the live client out of the context
until an operator points the domain tier at a real core service; the domain's six
slice tests set no such base path, so the production `WebClient` never builds and the
test's in-memory `StubLoanOriginationClient` is the only `LoanOriginationClient` in
the room. `@ConditionalOnMissingBean(LoanOriginationClient.class)` means that even
when a base path *is* set, the bean defers to any `LoanOriginationClient` already
present — so the test stub still wins.

There is a third actor worth naming, because it is what lets the domain tier boot
*standalone* with no core base path at all. A separate `@AutoConfiguration`,
`LoanOriginationClientConfig`, contributes a no-op **in-JVM** `LoanOriginationClient`
guarded only by `@ConditionalOnMissingBean`. Because it is an auto-configuration, it
is evaluated *after* user configuration and test configuration, so it is the lowest
priority of three: a test stub wins first, the live `WebClient` client wins when a
base path is set, and only if neither is present does the in-JVM default fill the seam.
That three-way ordering is why the domain tier serves whether or not core is reachable.

!!! note "Key term — bean condition precedence"
    Spring evaluates `@Configuration` classes you (or a test) register *before*
    classes registered as `@AutoConfiguration`. Layer `@ConditionalOnMissingBean` on
    top and you get a clean precedence ladder for a seam: a **test stub**
    (`@TestConfiguration`) outranks a **live client** (a user `@Configuration` gated on
    a base path), which outranks an **in-JVM default** (an `@AutoConfiguration`
    fallback). Each higher tier simply causes the one below it to back off — no
    profiles, no flags.

The adapter is the only class that knows the core speaks HTTP. It POSTs to create,
DELETEs to compensate, and GETs to read — and it is honest about how much of the
channel request actually crosses this trimmed write seam.

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/client/WebClientLoanOriginationClient.java | Listing 16.7 — the live core adapter: POST to create, DELETE to compensate, GET to read
    @Override
    public Mono<UUID> createLoanApplication(String applicantName, long amount) {
        CoreCreateRequest body = new CoreCreateRequest(
                UUID.randomUUID(),
                BigDecimal.valueOf(amount, 2),
                DEFAULT_CURRENCY,
                DEFAULT_TERM_MONTHS,
                DEFAULT_PURPOSE);
        log.debug("Core create loan-application applicant={} amountMinor={}", applicantName, amount);
        return webClient.post()
                .uri(LOAN_APPLICATIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(CoreLoanApplicationResponse.class)
                .map(CoreLoanApplicationResponse::loanApplicationId);
    }

    @Override
    public Mono<Void> removeLoanApplication(UUID loanApplicationId) {
        log.debug("Core compensation delete loan-application id={}", loanApplicationId);
        return webClient.delete()
                .uri(LOAN_APPLICATIONS_PATH + "/{id}", loanApplicationId)
                .retrieve()
                .bodyToMono(Void.class);
    }
:::

The `createLoanApplication` method POSTs to `/api/v1/loan-applications` — the exact
path the core service serves in Chapter 2 — and maps the response down to just the
core-assigned `loanApplicationId`, which is the id the saga propagates back up through
the domain controller and the experience tier to the channel. `removeLoanApplication`
is the *compensation*: when a dependent saga step fails, the saga undoes its root step
by DELETEing the application it created. That `DELETE` is the reason the reactor grew a
small second controller on the core side — kept separate so the primary controller you
sliced in Chapter 2 stays byte-identical:

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/web/LoanApplicationDeleteController.java | Listing 16.8 — the core's idempotent DELETE, the target of the saga's compensation
@RestController
@RequestMapping("/api/v1/loan-applications")
@RequiredArgsConstructor
@Tag(name = "LoanApplication", description = "Create and retrieve loan applications")
public class LoanApplicationDeleteController {

    private final LoanApplicationRepository repository;

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a loan application",
            description = "Removes an application by its id; idempotent, so a missing id is a no-op.")
    public Mono<Void> delete(@PathVariable UUID id) {
        return repository.deleteById(id);
    }
}
:::

The delete is **idempotent** — `deleteById` on an absent id is a no-op — and answers
`204 No Content` either way. That matters for resilience: a compensation that is
retried, or that races a partial cleanup, must converge to the same end state without
erroring. Idempotency is not only an inbound concern; it is what makes a *compensating*
call safe to repeat.

!!! warning "The domain-to-core write mapping is intentionally minimal"
    Be honest about what crosses this seam. The trimmed write path carries only the
    applicant name and the amount in minor units; the adapter fills the rest of the
    core's create payload with **defaults** — `currency` `EUR`, `purpose` `GENERAL`,
    `termMonths` `12`. That is why, when you verify a BFF-submitted application directly
    in core, you see those default values rather than the channel request's currency,
    purpose, or term. Richer field mapping is the job of the *generated* core SDK in
    the real service; the sample maps just enough for the live write and its
    compensation to flow over HTTP. The two dependent saga steps (`addApplicant`,
    `proposeOffer`) have no endpoint on the trimmed core controller, so they complete
    in-process with a synthesized id — enough for the saga to finish while the real
    write travels the network.

!!! spring "Spring parity"
    Nothing in Listings 16.5–16.8 is a Firefly annotation: `@ConfigurationProperties`,
    `@Configuration`, `@Bean`, `@ConditionalOnProperty`, `@ConditionalOnMissingBean`,
    `@RestController`, `@DeleteMapping` are all stock Spring. The
    `org.fireflyframework.*` types appear only where the framework adds value — the
    saga engine and the web error model. The seam pattern is convention layered on
    plain Spring, and it reads the same one tier down as one tier up.

## Resilience, applied through Reactor operators

A `WebClient` call is only half a resilient client. The other half is what happens
when the downstream is slow, erroring, or saturated. Firefly's production client
— the resilient `ServiceClient` the generated SDK is built on — wraps every call in a
**Resilience4j** stack, applied not as imperative `try`/`catch` but as *Reactor
operators* on the same `Mono` the adapter returns. Because they are operators, they
compose into the reactive chain without blocking a thread.

Three patterns do the heavy lifting:

- **Circuit breaker.** After a configured proportion of recent calls fail, the breaker
  *opens* and fails fast — every call returns immediately with an error instead of
  waiting on a dependency everyone already knows is sick. After a cooldown it lets a
  trickle of calls through (*half-open*); if they succeed it *closes* again. This is
  what stops one sick tier from cascading into the tiers above it — the very thing the
  three-hop `exp → domain → core` path is exposed to.
- **Retry.** A transient failure — a dropped connection, a `503` during a rolling
  deploy — is retried a bounded number of times, ideally with backoff, so a blip does
  not surface to the customer. Retry is *only* safe because every write carries an
  idempotency guarantee: the experience tier's deterministic idempotency key from
  Listing 16.4, and the core's idempotent DELETE from Listing 16.8 — a retried
  `submitApplication` dedupes downstream, a retried compensation converges instead of
  erroring.
- **Bulkhead.** A cap on concurrent in-flight calls to the dependency, so a slow
  downstream cannot consume every connection and starve the rest of the service. The
  bulkhead isolates the blast radius to the one dependency that is struggling.

Conceptually, the resilient client decorates the adapter's `Mono` with those
operators — this is illustrative, not a slice from the reactor:

```java
// Illustrative — how the resilient client layers Resilience4j onto the reactive call.
return webClient.post()
        .uri(LOAN_APPLICATIONS_PATH)
        .bodyValue(body)
        .retrieve()
        .bodyToMono(CoreLoanApplicationResponse.class)
        .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
        .transformDeferred(BulkheadOperator.of(bulkhead))
        .transformDeferred(RetryOperator.of(retry))
        .timeout(properties.timeout());
```

Each `transformDeferred` wraps the call in one Resilience4j decorator; `timeout`
enforces the budget from the bound `CoreLoanOriginationProperties` (the same record
whose ten-second default you read in Listing 16.5). You tune every threshold — failure
rate, wait duration, max attempts, concurrency cap — through properties, never code,
so the SRE who runs the fleet adjusts a flapping dependency's breaker without a
redeploy.

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
    format. It is the generalization of the two REST seams you sliced in this chapter
    to every protocol a banking platform integrates with.

!!! warning "The multi-protocol clients are illustrative here"
    Lumen Lending's tiers integrate over REST, so the *verified* slices in this chapter
    are the two REST seams — the experience-to-domain client and the live
    domain-to-core client — with their properties, ClientFactories, and `WebClient`
    adapters. The SOAP, gRPC, GraphQL, and WebSocket snippets above are illustrative —
    they show the shape of the unified `ServiceClient` and where it plugs in, not code
    this chapter's build compiles. Read them as "this is how the same pattern extends
    beyond REST," and reach for the framework's `ServiceClient` reference when you wire
    a real non-HTTP dependency.

## Run it: the live three-tier flow

Two ways to see these clients work: the live stack, and the hermetic tests. Take the
live stack first, because that is the payoff — the production `WebClient` beans you
sliced actually building and calling.

Boot all three tiers from `samples/lumen-lending`; order does not matter, because no
tier fail-fasts on a missing downstream:

```text
( cd core-lending-loan-origination   && mvn spring-boot:run ) &
( cd domain-lending-loan-origination && mvn spring-boot:run ) &
( cd exp-lending                     && mvn spring-boot:run ) &
```

On boot, the domain tier logs the ClientFactory from Listing 16.6 building its core
`WebClient` — because its `application.yml` sets the base path:

```text
{"timestamp":"2026-06-17T11:46:18.204+0000","message":"Building core Loan Origination WebClient basePath=http://localhost:8081 timeout=PT10S","logger":"c.f.l.d.config.LiveLoanOriginationClientConfig","level":"INFO"}
{"timestamp":"2026-06-17T11:46:18.231+0000","message":"Wiring live WebClient LoanOriginationClient against core; the saga writes to core over HTTP","logger":"c.f.l.d.config.LiveLoanOriginationClientConfig","level":"INFO"}
```

Now POST a single channel request to the BFF on `8080`. It flows `exp → domain → core`:
the experience client (Listing 16.4) POSTs to the domain at
`http://localhost:8082/api/v1/applications`; the domain's `LoanOriginationController`
runs the `RegisterApplicationSaga`; the saga's root step calls the live core client
(Listing 16.7), which POSTs to `http://localhost:8081/api/v1/loan-applications`; and
the core-assigned id is returned all the way back:

```text
$ curl -s -X POST localhost:8080/api/v1/experience/lending/applications \
    -H 'Content-Type: application/json' \
    -d '{"productId":"11111111-1111-1111-1111-111111111111","requestedAmount":25000.00,"term":36,"purpose":"HOME_IMPROVEMENT","simulationId":"22222222-2222-2222-2222-222222222222"}'
```

```json
{
  "applicationId": "786544c7-2f10-4110-95fe-682d63edbace",
  "simulationId": "22222222-2222-2222-2222-222222222222",
  "status": "SUBMITTED",
  "requestedAmount": 25000.00,
  "term": 36,
  "purpose": "HOME_IMPROVEMENT",
  "createdAt": "2026-06-17T11:46:21.186231",
  "updatedAt": "2026-06-17T11:46:21.186231"
}
```

On the domain tier you can watch the saga drive the write — the root step calls core
over HTTP, then the two dependent steps complete in-process, and the orchestration
finishes successfully:

```text
[orchestration] started   name=RegisterApplicationSaga ... pattern=SAGA
[orchestration] step.success ... stepId=registerLoanApplication latencyMs=94
[orchestration] step.success ... stepId=proposeOffer
[orchestration] step.success ... stepId=registerApplicant
[orchestration] completed name=RegisterApplicationSaga ... pattern=SAGA success=true
```

The proof it really landed in core is to read it straight from the system of record by
the id the BFF returned — note the **defaulted** core fields (`currency` `EUR`,
`termMonths` `12`, `purpose` `GENERAL`) the trimmed write seam supplies, exactly as
the warning above predicted:

```text
$ curl -s localhost:8081/api/v1/loan-applications/786544c7-2f10-4110-95fe-682d63edbace
```

```json
{
  "loanApplicationId": "786544c7-2f10-4110-95fe-682d63edbace",
  "applicationNumber": "9d2e8b8c-fc64-4aae-8578-c77558a3ec4b",
  "applicantId": "8db1c7ab-5d74-44fd-a4c7-d9433f0ddaba",
  "requestedAmount": 25000.00,
  "currency": "EUR",
  "termMonths": 12,
  "purpose": "GENERAL",
  "status": "SUBMITTED",
  "decisionReason": null,
  "createdAt": "2026-06-17T11:46:21.145765",
  "updatedAt": "2026-06-17T11:46:21.145781"
}
```

## Run it: the hermetic tests

The live stack proves the clients work *with* their downstreams. The test suites prove
something subtler and just as important: each tier boots and runs *without* its
downstream, because the production client is conditional and a test stub wins. That is
precisely what the `@ConditionalOnProperty`/`@ConditionalOnMissingBean` pairs buy you.

The experience tier's tests never set `lumen.exp.loan-origination.base-path`, so the
production `WebClient` and adapter from Listing 16.3 never materialize; a
`StubLoanOriginationDomainClient` is registered instead, and `@ConditionalOnMissingBean`
guarantees it wins. From `samples/lumen-lending`:

```text
$ mvn -q -pl exp-lending test
```

```text
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

The same story plays out one tier down. The domain tier's tests register their own
`StubLoanOriginationClient` and set no `firefly.lumen.core.loan-origination.base-path`,
so neither the live `WebClient` client from Listing 16.6 nor the in-JVM default ever
displaces the stub:

```text
$ mvn -q -pl domain-lending-loan-origination test
```

```text
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

And the whole reactor — core, domain, and experience together — proves the entire
`exp → domain → core` contract end to end:

```text
$ mvn -q clean verify
```

```text
Tests run: 33, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Thirty-three green tests (core 18, domain 6, exp 9) confirm the chapter's claims about
both seams: each tier composes its half of the round trip through a port, the
experience tier derives a deterministic idempotency key the stub can observe, the
domain tier runs the saga against an in-memory core stub, and a missing application
surfaces as a problem detail — all without either production `WebClient` ever being
built, because no base path is configured in the test profiles.

!!! tip "Checkpoint"
    Note *why* the suites are green without running downstreams: the production beans in
    both ClientFactories are gated by `@ConditionalOnProperty(name = "base-path")` and
    `@ConditionalOnMissingBean`. With no `base-path` set and a test stub present, both
    conditions keep the live client out of the context. Set
    `firefly.lumen.core.loan-origination.base-path` in the domain test profile and the
    core `WebClient` bean would try to build (watch for the `Building core Loan
    Origination WebClient` log line) — proof that the condition, not luck, is what keeps
    the tests hermetic.

## What you built {.recap}

- A reactive **port** at *each* tier boundary — `LoanOriginationDomainClient` from the
  BFF to the domain, and `LoanOriginationClient` from the domain to the core — that the
  caller depends on, with no HTTP detail leaking past the interface, each returning
  `Mono`.
- Two `@ConfigurationProperties` records, `LoanOriginationClientProperties`
  (prefix `lumen.exp.loan-origination`) and `CoreLoanOriginationProperties`
  (prefix `firefly.lumen.core.loan-origination`), that bind the downstream **base URL
  and timeout** from configuration, so the same code promotes across environments by
  property change.
- Two **ClientFactory** `@Configuration` classes that build the production `WebClient`
  and client bean only when `@ConditionalOnProperty(base-path)` is satisfied and
  `@ConditionalOnMissingBean` confirms nothing overrides it — the back-off-to-your-bean
  rule applied to outbound clients, plus a three-way precedence (test stub → live client
  → in-JVM default) on the domain seam.
- Two `WebClient` **adapters**: the experience adapter that sets the `Idempotency-Key`
  header on every call, and the live **domain-to-core** adapter that POSTs to create,
  DELETEs to compensate (against the core's idempotent delete endpoint), and GETs to
  read — honestly minimal in what it maps. Plus the conceptual picture of the
  **Resilience4j** circuit breaker, retry, and bulkhead applied through Reactor
  operators, and `X-Transaction-Id` propagation across each hop.
- The **unified `ServiceClient`** — the same fluent, resilient grammar over SOAP, gRPC,
  GraphQL, and WebSocket — and an honest line between what these REST slices verify and
  what is illustrative.
- A passing **33-test** reactor (`Tests run: 33, Failures: 0`; exp 9, domain 6, core 18)
  that runs every tier against in-memory stubs with the production clients conditioned
  out — plus a live three-tier `exp → domain → core` flow you can run by hand, no Docker
  on either path.

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
3. **Walk the live two-hop write.** With all three tiers up, POST to the BFF and then
   `GET localhost:8081/api/v1/loan-applications/{id}` for the returned id. Identify
   which fields came from your channel request and which are the trimmed write seam's
   defaults (`currency`, `termMonths`, `purpose`), and explain why — referencing the
   `DEFAULT_*` constants in `WebClientLoanOriginationClient`.
4. **Reason about compensation.** Open `LoanApplicationDeleteController` and the
   `removeLoanApplication` method in `WebClientLoanOriginationClient`. Explain why the
   `DELETE` must be idempotent for a saga compensation to be safe to retry, and what a
   `DELETE` of an already-removed id returns.
5. **Trace the precedence ladder.** Three things can satisfy the domain's
   `LoanOriginationClient` seam: the test `StubLoanOriginationClient`, the live
   `WebClientLoanOriginationClient`, and the in-JVM default in `LoanOriginationClientConfig`.
   Without running anything, order them by precedence and name the two mechanisms
   (`@AutoConfiguration` ordering and `@ConditionalOnMissingBean`) that produce that order.
6. **Sketch a SOAP seam.** Without running it, write the `ServiceClient.soap(...)`
   builder you would use for the legacy core-banking account-verify call, including a
   circuit breaker and a retry. Name which two cross-cutting behaviors you get *for
   free* from the unified client that you would otherwise hand-wire into a JAX-WS stub.

## Where to go next

You now have the caller's half of every tier boundary, twice over: a port,
config-driven addressing, a conditional ClientFactory, and a resilient adapter at both
the `exp → domain` and the now-live `domain → core` hop, plus a unified client that
reaches beyond REST. The next chapters put these calls under load and under watch — the
observability that makes the `X-Transaction-Id` you propagated here actually traceable
across the fleet, and the tests that exercise a full request from the experience tier
down through the domain saga into the core system of record.
