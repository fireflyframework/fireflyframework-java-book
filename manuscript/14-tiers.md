You have built three tiers without ever naming the architecture out loud. Chapters
6 through 8 built a **core** service — the loan-origination system of record, with a
reactive controller over R2DBC. Chapters 10 through 13 built a **domain** service —
CQRS handlers, a saga, events, all orchestrating over a port instead of a database.
This chapter adds the third, the **experience** tier, and then steps back to name
the whole shape: the four-tier architecture that organizes every Firefly fleet, and
the one rule that holds it together.

The shape is not decoration. Each tier has a distinct job, picks a distinct starter,
and integrates with its neighbors over a *contract* — a generated SDK or an HTTP
call — never over a shared database. That single constraint is what lets a
hundred-service platform evolve one service at a time. You met it as a promise in
Chapter 1; now you have three tiers in front of you to make it concrete.

And this time the architecture is not a diagram you take on faith. Lumen's reactor
now **runs end to end** — three Spring Boot apps on three ports, no Docker — and a
single channel `POST` flows down the whole stack and back up. We will list the
directories so you can see the tiers as real modules, slice the experience tier's two
seams — its channel-facing controller and the reactive port that is its boundary to
the domain — slice the matching domain seam where the live HTTP hop actually happens,
walk the four starters and the no-shared-database rule, and then *boot all three
tiers and watch the request travel*. Lumen's slice ships three of the four tiers; the
fourth, **data**, we name here and build in Chapter 15.

## The four directories

Open `samples/lumen-lending` and the architecture is sitting in the directory
listing. Three modules, one per tier built so far, under one parent POM:

```text
samples/lumen-lending/
├── pom.xml                              # the parent: BOM import, module list
├── exp-lending/                         # experience tier  (starter-application)  :8080
├── domain-lending-loan-origination/     # domain tier      (starter-domain)       :8082
└── core-lending-loan-origination/       # core tier        (starter-core)         :8081
```

Read the names as a stack. A channel request lands on `exp-lending`, the
Backend-for-Frontend, on port **8080**. It calls `domain-lending-loan-origination`,
the orchestration layer that runs the CQRS commands and the saga, on **8082**. That
domain service calls `core-lending-loan-origination`, the system of record that owns
the schema and the rows, on **8081**. The data flows down the stack on the way in and
back up on the way out, and at every boundary the call crosses a *network contract*,
not a method call into shared code.

The ports are not arbitrary either: they are the ports the running sample actually
binds, captured straight from the reactor's `application.yml` files and the README's
verified run. You will use all three by the end of this chapter.

The fourth tier, **data**, would sit beside these as another module on
`starter-data` — credit-bureau enrichment, data quality, lineage. Lumen's slice does
not build it yet; Chapter 15 introduces it. For now, hold the picture at three real
modules plus one named-but-not-yet-built.

!!! note "Key term — tier"
    A **tier** in a Firefly platform is a service whose *role* is fixed by the
    architecture: **experience** composes for a channel, **domain** orchestrates
    business flows, **core** owns data, **data** enriches it. Each role maps to one
    tier starter, and tiers integrate only over contracts. "Which tier is this?" is
    answered by which starter the POM declares — not by a naming convention you have
    to remember.

## The experience tier's outward seam

The experience tier is the only one a channel — a mobile app, a web client — talks
to directly. Its controller is a thin, stateless composition layer: validate the
channel-shaped request, call downstream, shape a lightweight DTO back. Open
`exp-lending`'s single web class and notice how little business logic it holds.

::: listing exp-lending/src/main/java/com/firefly/lumen/exp/web/ApplicationController.java | Listing 14.1 — the experience-tier controller: base path /api/v1/experience/lending/applications
@RestController
@RequestMapping("/api/v1/experience/lending/applications")
@Tag(name = "Lending - Applications")
public class ApplicationController {

    private final ApplicationService applicationService;

    public ApplicationController(ApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "createApplication", summary = "Create Application",
            description = "Creates a new loan application via the domain origination service.")
    @Secure(permissions = {"lending:application:create"},
            description = "Create a loan application")
    public Mono<ResponseEntity<ApplicationDetailDTO>> createApplication(
            @Valid @RequestBody CreateApplicationRequest request) {
        return applicationService.createApplication(request)
                .map(result -> ResponseEntity.status(HttpStatus.CREATED).body(result));
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getApplication", summary = "Get Application",
            description = "Retrieves the full details of a loan application by its identifier.")
    @Secure(permissions = {"lending:application:read"},
            description = "Read a loan application")
    public Mono<ResponseEntity<ApplicationDetailDTO>> getApplication(@PathVariable UUID id) {
        return applicationService.getApplication(id)
                .map(ResponseEntity::ok);
    }
}
:::

Two things mark this as the *experience* tier, not the core controller you wrote in
Chapter 6.

First, the base path. `/api/v1/experience/lending/applications` is namespaced under
`experience` because it is a channel-facing surface, distinct from the core's
`/api/v1/loan-applications` system-of-record API. The two paths live in two
different services on two different ports — exp on 8080, core on 8081 — and a client
never reaches the core directly, only the experience tier in front of it.

Second, the controller does no business work. `createApplication` validates the
request and delegates to `applicationService`, which calls *downstream* — it does not
touch a repository, because the experience tier owns no database. It composes a call
to the domain tier and maps the result. That is the whole job of a BFF: shape, call,
shape back. The `@Secure` method-level authorization comes from the application
starter; we cover it fully in Chapter 19.

!!! spring "Spring parity"
    Everything structural here is plain Spring WebFlux — `@RestController`,
    `@RequestMapping`, `@PostMapping`, `@GetMapping`, `@PathVariable`, `@Valid`,
    `ResponseEntity`. The `@Tag`/`@Operation` pair is springdoc. The only Firefly
    annotation is `@Secure`, a meta-annotated stereotype driven by the starter's
    `SecurityAspect`. If you have written a WebFlux controller, the experience tier
    holds no surprises — its distinctness is architectural (where it sits, what it
    talks to), not syntactic.

## The experience tier's inward seam

The controller delegates to a service, and the service reaches the domain tier
through a *port* — an interface that is the experience-to-domain boundary. This is
the same pattern you met in Chapter 10, where the domain tier reached the core
through `LoanOriginationClient`. One tier up, the shape repeats: the experience tier
depends on an interface, never a concrete client, and the generated SDK plugs in
behind it.

::: listing exp-lending/src/main/java/com/firefly/lumen/exp/client/LoanOriginationDomainClient.java | Listing 14.2 — the exp→domain SDK seam: a reactive port to the domain service
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

Every method returns a `Mono`, because the whole experience stack — controller,
service, the HTTP hop to the domain service — is non-blocking end to end. And every
method takes an `idempotencyKey`: the experience tier mints a deterministic key per
logical request so that a retried channel call dedupes downstream instead of creating
a second application. The key is the experience tier's contribution to safe retries
across the network boundary.

That key is not decorative — the service derives it from the request's *stable* fields,
so a retry of the same logical request produces the same key. The production adapter
then sends it as a standard `Idempotency-Key` header on the wire to the domain tier.
You can see both halves in the reactor: the service builds the key, and the
`WebClient`-backed adapter forwards it as a header (more on that adapter below).

The same honesty from Chapter 10 applies here, one tier up. In a real Firefly
deployment, `exp-lending` does not hand-write this interface — it injects the
*generated domain SDK*, a `WebClient`-based client produced from the domain service's
OpenAPI contract and wired by a `ClientFactory`. The reactor hand-rolls a trimmed
port so the sample compiles and its tests run with **no running domain service and no
Docker**; an in-memory stub lives under `src/test/java`. Chapter 16 is where the
generated SDK and its resilient defaults — retries, timeouts, a circuit breaker —
take over, and Chapter 17 returns to the experience tier in full.

!!! warning "The port is the SDK seam, not a hand-written client you ship"
    Read `LoanOriginationDomainClient` as "this is where the generated domain SDK
    plugs in," exactly as you read `LoanOriginationClient` in Chapter 10 for the
    domain→core hop. The production path is a generated, resilient `WebClient` client;
    the port exists so this slice can teach the *tier boundary* without standing up
    the downstream service. The boundary is the lesson; the stub is scaffolding.

!!! note "Key term — the tier seam (a reactive port)"
    A **tier seam** is the interface one tier depends on to call the next. It is a
    reactive port — methods returning `Mono`/`Flux` — so the caller stays non-blocking
    across the network hop. The seam is what makes the no-shared-database rule
    enforceable in code: a tier that can only see an interface *cannot* reach into a
    neighbor's tables. `LoanOriginationDomainClient` (exp→domain) and
    `LoanOriginationClient` (domain→core) are the two seams in Lumen's slice.

## The domain tier's matching face

A seam has two ends. The experience tier *calls* the `LoanOriginationDomainClient`
port; something has to *answer* it. That something is the domain tier's REST
controller — the second hop in the live flow. It is deliberately the mirror image of
the port: the same `/api/v1/applications` path the exp adapter posts to, the same
channel-shaped request and detail view. Open the domain web layer and read the class
that the BFF actually reaches over HTTP.

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/web/LoanOriginationController.java | Listing 14.3 — the domain tier's REST face: it runs the saga the BFF triggers
@RestController
@RequestMapping("/api/v1/applications")
@Tag(name = "Loan Origination - Applications")
public class LoanOriginationController {

    private static final Logger log = LoggerFactory.getLogger(LoanOriginationController.class);

    /** Default offered rate, in basis points, for the saga's propose-offer step. */
    private static final int DEFAULT_ANNUAL_RATE_BPS = 575;

    private final LoanOriginationService service;
    private final ObjectProvider<CoreLoanApplicationReader> coreReader;

    public LoanOriginationController(LoanOriginationService service,
                                     ObjectProvider<CoreLoanApplicationReader> coreReader) {
        this.service = service;
        this.coreReader = coreReader;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "submitApplication", summary = "Submit Application",
            description = "Runs the RegisterApplicationSaga, writing the application to the core system of record.")
    public Mono<ResponseEntity<ApplicationDetailView>> submit(@RequestBody ApplicationChannelRequest request) {
        long amountMinor = toMinorUnits(request.requestedAmount());
        String applicantName = applicantNameFor(request);
        log.debug("Submitting application productId={} simulationId={} requestedAmount={} term={}",
                request.productId(), request.simulationId(), request.requestedAmount(), request.term());

        return service.submitApplication(applicantName, amountMinor, DEFAULT_ANNUAL_RATE_BPS)
                .flatMap(result -> toDetail(result, request))
                .map(detail -> ResponseEntity.status(HttpStatus.CREATED).body(detail));
    }
:::

This is the receiving end of the exp→domain hop, and it is doing the domain tier's
real job: it does not persist anything itself, it *orchestrates*. The `submit` method
runs `service.submitApplication(...)`, which drives the `RegisterApplicationSaga` you
built in Chapter 11 — and the saga's root step is what reaches the core. Note the
constructor injects an `ObjectProvider<CoreLoanApplicationReader>`: the read path is
*optional*, present only when a live core is wired, which is why the domain service
can still boot standalone with no core behind it.

!!! note "Key term — the orchestration tier's REST face"
    The domain tier exposes HTTP only so the tier above it can call in; its endpoints
    are thin triggers for orchestration, not CRUD over a table. `submit` does not
    write a row — it starts a saga, and the saga decides what to write where. The
    controller's job is to translate the channel request into a saga invocation and
    map the `SagaResult` back into a channel view. The persistence lives one tier
    further down, in the core.

## Where the live HTTP hop happens

The saga's root step writes to the core, but a saga step is not an HTTP client. It
calls the *same* `LoanOriginationClient` port you met in Chapter 10 — and in the live
stack that port is backed by a `WebClient` adapter that POSTs to the core service on
8081. This is the domain→core seam's real implementation, the twin of the exp→domain
adapter. Here is the write method that closes the loop:

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/client/WebClientLoanOriginationClient.java | Listing 14.4 — the domain→core write seam: a WebClient that POSTs to the core on :8081
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
:::

This is the only place in the whole stack that talks to the core's
`/api/v1/loan-applications` API, and it does so over HTTP — the `webClient` is built
against the core's base URL, and the response's `loanApplicationId` is the
core-assigned id that travels back up to the channel. The adapter implements the same
`LoanOriginationClient` port the slice tests stub out, so the *saga* never knows
whether it is talking to a real core over the network or to an in-memory recorder.

Be honest about the mapping, though, because the slice is deliberately minimal. The
trimmed write seam carries only the applicant name and the amount; the other core
fields are filled with defaults — `DEFAULT_CURRENCY = "EUR"`, `DEFAULT_PURPOSE =
"GENERAL"`, `DEFAULT_TERM_MONTHS = 12`. So when you submit through the BFF with
`term: 36`, the row that lands in core shows `termMonths: 12` and `purpose: "GENERAL"`.
That is not a bug — it is the trimmed seam being faithful about what it forwards. The
richer field-by-field mapping is exactly what the *generated* SDK restores in
Chapter 16; here the point is that the **hop is real**, not that every field rides it.

!!! warning "The domain→core mapping is intentionally minimal"
    The live write seam forwards the applicant and amount and lets the core default
    the rest, so `currency`, `termMonths`, and `purpose` may not match what you posted
    to the BFF. Read this as the *seam working*, not the mapping being complete. The
    generated SDK in Chapter 16 carries the full payload; the lesson here is the
    boundary and the network hop, which are genuinely live.

The live adapter does not wire itself up unconditionally — it activates only when an
operator points the domain tier at a real core. That switch is a single property,
and a small `@Configuration` reads it:

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/config/LiveLoanOriginationClientConfig.java | Listing 14.5 — the live core seam is conditional on a configured base path
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
:::

Two conditions make this safe. `@ConditionalOnProperty(... name = "base-path")` means
the live `WebClient` only materializes when `firefly.lumen.core.loan-origination.base-path`
is set — and the runnable `application.yml` sets it to `http://localhost:8081`, so the
standalone domain app calls the running core. `@ConditionalOnMissingBean` means the
slice tests' in-memory stub always wins, so turning on the live client never disturbs
the 33 green tests. The same conditional pattern guards the exp tier's adapter, keyed
on `lumen.exp.loan-origination.base-path` pointing at `http://localhost:8082`. The
seam is an interface; *which* implementation fills it is a configuration decision, not
a code change.

!!! spring "Spring parity"
    `@ConditionalOnProperty` and `@ConditionalOnMissingBean` are stock Spring Boot
    auto-configuration conditions — the same ones the framework's own starters use.
    Firefly adds no new mechanism here; it just applies the ordinary Boot idiom to the
    tier seam: *configure a base path → the live HTTP client appears; leave it unset
    → an in-JVM default (or a test stub) stands in.* One property flips a tier from
    standalone to wired-up.

## The four tiers, and why each picks its starter

Now the whole shape. Four tiers, four starters, each bundling the capabilities and
production defaults that its role needs and nothing it does not. The starter is how a
service declares which tier it is — a single dependency, version-less because the BOM
from Chapter 3 pins it.

- **Experience (`exp-lending`) — `fireflyframework-starter-application`.** The
  channel-facing BFF, on port **8080**. Stateless composition: validate, call
  downstream, shape a DTO. Its starter brings the channel concerns — method security
  (`@Secure`), caching, the resilient client machinery to call domain services — but
  no persistence, because it owns no data. This is the tier in Listings 14.1 and 14.2.
- **Domain (`domain-lending-loan-origination`) — `fireflyframework-starter-domain`.**
  Business orchestration, on port **8082**. Translates coarse channel requests into
  CQRS commands and queries, runs compensating sagas, emits domain events
  (Chapters 10–13). Its starter brings the CQRS buses, saga engine, and EDA runtime —
  and, like the experience tier, no database, because it calls the core over an SDK.
  This is the tier in Listings 14.3 through 14.5.
- **Core (`core-lending-loan-origination`) — `fireflyframework-starter-core` plus
  R2DBC.** The system of record, on port **8081**. Owns the schema, the rows, and the
  reactive CRUD and business APIs over them (Chapters 6–8). It is the *only* tier with
  a datastore, so its starter is paired with the reactive R2DBC stack — running on
  in-memory H2 here, with Flyway migrating the schema.
- **Data — `fireflyframework-starter-data`.** Enrichment, data quality, and lineage —
  credit-bureau lookups, scoring inputs, the data-platform concerns. Lumen names it
  here and builds it in **Chapter 15**.

Look back at the three POMs from Chapter 3 and the pattern is exact: each module
declares precisely one tier starter, with no version, and inherits the rest from the
parent. The experience POM declares `fireflyframework-starter-application`; the domain
POM, `fireflyframework-starter-domain`; the core POM, `fireflyframework-starter-core`.
The starter is not a convenience — it is the machine-readable answer to "what kind of
service is this?"

!!! spring "Spring parity"
    A Firefly tier starter *is* a Spring Boot starter — a POM that aggregates
    dependencies and auto-configurations, the same mechanism behind
    `spring-boot-starter-web`. What Firefly adds is the *opinion*: four starters
    aligned to four architectural roles, each pre-wiring the cross-cutting behavior
    (security, CQRS, persistence, resilient clients) that role needs. Adding
    `starter-domain` is to a domain service what adding `starter-web` is to a web app —
    one line that turns on a coherent slice of behavior.

## The cardinal rule: integrate over contracts, never a shared database

Everything above rests on one constraint, and it is worth stating as a law:

> Tiers integrate over generated SDKs and HTTP. **No two tiers ever share a
> database.**

Only the core tier owns a datastore. The domain tier reaches it through
`LoanOriginationClient` — and you just saw that port's live body in Listing 14.4: a
`WebClient` POSTing to the core on 8081, not a query against the core's tables. The
experience tier reaches the domain through `LoanOriginationDomainClient`, which in
the live stack POSTs to the domain on 8082. Neither the experience nor the domain
service has an R2DBC dependency, a connection pool, or a repository — and that is by
design, not omission. If the domain tier could query the core's tables directly,
every change to the core's schema would risk breaking the domain service silently,
and the two would be welded together exactly the way the CQRS handler was welded to
its consumers in Chapter 11.

Integrating over a *contract* breaks that weld. The core can reshape its storage
behind a stable OpenAPI surface; the domain only sees the generated SDK; the
experience only sees the domain's SDK. Each tier can be deployed, scaled, and evolved
independently because the only thing crossing a boundary is a versioned wire contract.
This is the rule from Chapter 1 — *integrate over contracts, not over a shared
schema* — and the two ports you sliced are where it is enforced in code: a tier that
can only see an interface physically cannot reach into a neighbor's tables.

!!! warning "A shared database is the failure mode, not a shortcut"
    The most common way teams quietly destroy a tiered architecture is to let two
    services point at the same database "just for this one query." The moment they do,
    the tiers are coupled at the schema, deployments must be coordinated, and the
    independence the architecture promised is gone. If a tier needs data it does not
    own, it calls the owner over the owner's SDK. There is no exception that does not
    cost you the architecture.

## How configuration follows the tiers

The tiers do not only structure code — they structure *configuration*. Recall the
config hierarchy from Chapter 4: the Firefly config server serves settings in layers,
and the layers mirror the tiers. **common** settings apply to the whole fleet — log
format, the tracing endpoint, shared conventions. **core**, **domain**, and
**experience** layers each hold settings shared by every service of *that tier*. And
each service's own `application.yml` holds what is true for it alone.

You can see that last layer concretely in the runnable sample. The domain service's
`application.yml` sets `firefly.lumen.core.loan-origination.base-path:
http://localhost:8081` — the one thing true for *this* service: where its core lives.
The exp service's sets `lumen.exp.loan-origination.base-path: http://localhost:8082`.
Those are per-service settings. A default downstream timeout, by contrast, is true for
*every* domain service and would live in the **domain** layer; the JSON log format is
true for the whole fleet and lives in **common**.

So a tier is a configuration scope as well as a code role. A setting that every
domain service needs — a default timeout, a saga property — lives in the **domain**
layer and every domain service inherits it; a setting every core service needs lives
in **core**. A new service joins the hierarchy simply by declaring its tier starter
and pointing at the config server: the starter establishes the tier, and the tier
selects which shared layers it inherits. That is the convention Chapter 4 named, seen
now from the architecture's side — the same four words, `common` / `core` / `domain` /
`experience`, organize the dependency graph and the property graph alike.

!!! spring "Spring parity"
    The hierarchy rides on Spring Cloud Config, whose layered property resolution you
    could assemble by hand. Firefly's contribution is the *convention* that the layers
    are exactly the tiers — so "which shared config does this service inherit?" has the
    same answer as "which starter does it declare?" One concept, the tier, indexes both
    the build and the configuration.

## Run the whole stack

Now the payoff. The architecture is not a claim — you can boot all three tiers and
watch a request travel the full `exp → domain → core` path and back. Each module is
an independent Spring Boot app; each runs with the Maven plugin (or as a built
`java -jar`) on in-memory H2 and the in-JVM `APPLICATION_EVENT` transport, so there is
**no Docker, no external database, and no message broker**. From `samples/lumen-lending`,
in three terminals (order does not matter — the tiers do not fail-fast on a missing
downstream):

```bash
( cd core-lending-loan-origination   && mvn spring-boot:run ) &
( cd domain-lending-loan-origination && mvn spring-boot:run ) &
( cd exp-lending                     && mvn spring-boot:run ) &
```

Each app logs `Started …Application in …` and `Netty started on port …` when ready —
core on 8081, domain on 8082, exp on 8080. Confirm all three are alive:

```bash
curl -s localhost:8081/actuator/health   # core  -> {"status":"UP",...}
curl -s localhost:8082/actuator/health   # domain-> {"status":"UP",...}
curl -s localhost:8080/actuator/health   # exp   -> {"status":"UP",...}
```

### One POST down the whole stack

Send a single channel request to the BFF on 8080. The experience tier validates it,
crosses the SDK seam to the domain on 8082, the domain runs the
`RegisterApplicationSaga`, the saga's root step writes to the core on 8081 over HTTP,
and the core-assigned id comes back up through both seams:

```bash
curl -s -X POST localhost:8080/api/v1/experience/lending/applications \
  -H 'Content-Type: application/json' \
  -d '{"productId":"11111111-1111-1111-1111-111111111111","requestedAmount":25000.00,"term":36,"purpose":"HOME_IMPROVEMENT","simulationId":"22222222-2222-2222-2222-222222222222"}'
```

The BFF answers `201 Created` with the channel detail view — `status` is `SUBMITTED`,
because the core submits the application on create:

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

That `applicationId` is not minted by the BFF — it is the id the **core** system of
record assigned. Prove it by reading the same id directly from the core on 8081:

```bash
curl -s localhost:8081/api/v1/loan-applications/786544c7-2f10-4110-95fe-682d63edbace
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

The id matches — the application really landed in core. And here is the honesty from
Listing 14.4 made visible: you posted `term: 36` and `purpose: "HOME_IMPROVEMENT"`,
but the core row shows `termMonths: 12`, `currency: "EUR"`, and `purpose: "GENERAL"`.
Those are the trimmed write seam's defaults, exactly as the listing warned. The hop is
real; the field mapping is minimal by design, and Chapter 16's generated SDK is where
it fills in.

On the **domain** tier's console you can watch the saga drive the write — the root
step calls the core over HTTP, then the two dependent steps complete in-process:

```text
[orchestration] started   name=RegisterApplicationSaga ... pattern=SAGA
[orchestration] step.success ... stepId=registerLoanApplication latencyMs=94
[orchestration] step.success ... stepId=proposeOffer
[orchestration] step.success ... stepId=registerApplicant
[orchestration] completed name=RegisterApplicationSaga ... pattern=SAGA success=true
```

That final `completed ... success=true` line is the architecture proving itself: a
channel request fanned out across two network hops, ran a multi-step saga, wrote to a
system of record it does not own, and returned — and not one tier touched another's
database. When you are done, free the ports:

```bash
lsof -ti:8080,8081,8082 | xargs kill
```

!!! note "Key term — the live three-tier flow"
    The **exp → domain → core** flow is the architecture executing. A `POST` to the
    BFF (8080) becomes an HTTP call to the domain (8082), which runs a saga whose root
    step is an HTTP `POST` to the core (8081); the core's assigned id returns through
    both seams. Two seams, two network hops, one saga, zero shared databases — that is
    the whole shape running, not described.

### Prove it headless

You do not even need the three terminals to trust the boundaries: the reactor's tests
exercise every tier in isolation, against in-memory stubs and H2, with no running
neighbor. From `samples/lumen-lending`:

```bash
mvn clean verify
```

All **33** pass — core **18**, domain **6**, exp **9**:

```text
Tests run: 33, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

To run just the experience tier's slice in isolation — the BFF booting, securing its
endpoints, and composing downstream, all against the in-memory stub for the domain
port:

```bash
mvn -q -pl exp-lending test
```

```text
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Those nine cover the context booting (`ExpLendingApplicationTest`), the controller's
create-and-read paths over `WebTestClient` (`ApplicationControllerTest`), and the
service's composition logic against the stub port (`ApplicationServiceTest`). The
substitution is the same seam trick you saw one tier down: the test supplies an
in-memory `LoanOriginationDomainClient`, and nothing in the controller or service
changes — and because the live `WebClient` adapter is `@ConditionalOnMissingBean`, the
stub always wins in tests. That is the tier boundary earning its keep — the experience
tier is testable in complete isolation from the domain tier it fronts.

!!! tip "Checkpoint"
    Run `mvn clean verify` and confirm `Tests run: 33, Failures: 0`. Then boot all
    three tiers and run the single `POST localhost:8080/...` above; check that the
    `applicationId` it returns resolves with a `GET` against `localhost:8081`. If both
    hold, the whole stack — two seams, a saga, three ports — is live on your machine.
    For the isolated path, open `exp-lending/src/test/java` and find
    `StubLoanOriginationDomainClient`, the in-memory implementation of the port from
    Listing 14.2: it is the only thing standing in for a whole downstream service.

## What you learned {.recap}

- Lumen Lending is **four tiers** that now run **live and end to end** with no Docker:
  **experience** (channel-facing BFF on `starter-application`, port **8080**),
  **domain** (orchestration on `starter-domain`, **8082**), **core** (system of record
  on `starter-core` plus R2DBC, **8081**), and **data** (enrichment on `starter-data`,
  named here, built in Chapter 15).
- A tier declares its role by the **one starter** its POM imports — the cross-ref
  from Chapter 3, now read as architecture: experience→`starter-application`,
  domain→`starter-domain`, core→`starter-core`.
- The experience tier is a thin composition layer (base path
  `/api/v1/experience/lending/applications`, no business logic, no database) that
  reaches the domain through `LoanOriginationDomainClient`, the **exp→domain SDK
  seam**, carrying a deterministic idempotency key. The domain's
  `LoanOriginationController` answers that seam, runs the `RegisterApplicationSaga`,
  and the saga's root step calls the **domain→core seam** —
  `WebClientLoanOriginationClient`, a `WebClient` that POSTs to the core on 8081.
- The live flow is **proven**: one `POST` to 8080 → domain on 8082 → saga →
  core on 8081, returning a core-assigned `applicationId` and
  `[orchestration] completed ... success=true`. The domain→core mapping is
  intentionally minimal — `currency`, `termMonths`, `purpose` land as core defaults —
  which is the seam being honest, not broken; the generated SDK in Chapter 16 carries
  the full payload.
- The cardinal rule: tiers integrate over **generated SDKs and HTTP, and never share
  a database**. Only the core owns a datastore; the two reactive ports — and their
  `@ConditionalOnProperty` live `WebClient` adapters — are where the rule is enforced
  in code.
- Configuration follows the tiers — the Chapter 4 **common / core / domain /
  experience** hierarchy uses the same four words, so a service's tier selects both
  the shared dependencies and the shared config it inherits; per-service settings like
  `firefly.lumen.core.loan-origination.base-path` live in the service's own
  `application.yml`.

## Try it yourself {.exercises}

1. **Trace a request down the live stack.** Starting at `createApplication` in
   `ApplicationController` (8080), follow the call into `ApplicationService`, out
   through `LoanOriginationDomainClient.submitApplication`, into the domain's
   `LoanOriginationController.submit` (8082), through the saga, and out through
   `WebClientLoanOriginationClient.createLoanApplication` (8081). Write down every
   boundary and mark which is a method call and which is a real network hop.
2. **Watch the mapping shrink.** Run the live `POST localhost:8080/...` with
   `term: 60` and `purpose: "DEBT_CONSOLIDATION"`, then `GET localhost:8081/...` the
   returned id. Which fields survived the trip and which became core defaults? Open
   Listing 14.4 and point to the constants that explain it. Why is this faithful to
   the trimmed seam rather than a bug?
3. **Find the absent database.** Open `exp-lending/pom.xml` and
   `domain-lending-loan-origination/pom.xml` and confirm neither declares R2DBC or a
   repository dependency, then open `core-lending-loan-origination/pom.xml` and find
   where the datastore lives. Explain in one sentence why only one of the three has it.
4. **Flip a seam with a property.** In `domain` `application.yml`, comment out
   `firefly.lumen.core.loan-origination.base-path` and reboot the domain tier. Using
   Listing 14.5, predict what happens to the live `WebClient` bean and the
   `GET /api/v1/applications/{id}` read path. Then check the boot log and a `GET`. Why
   does `@ConditionalOnProperty` make the tier degrade gracefully instead of failing?
5. **Compare the two seams.** Put `LoanOriginationDomainClient` (Listing 14.2) next to
   `LoanOriginationClient` (Chapter 10), and their live adapters
   (`WebClientLoanOriginationDomainClient`, posting to 8082, vs.
   `WebClientLoanOriginationClient` in Listing 14.4, posting to 8081). What is the same
   (reactive ports, `WebClient` adapters, `@ConditionalOnMissingBean` stubs in tests)
   and what differs (the `idempotencyKey`/`Idempotency-Key` header on the exp seam, the
   DTO types)? Why does the experience seam carry an idempotency key the domain seam
   did not surface?
6. **Place a setting in the hierarchy.** Given a default downstream timeout that
   *every* domain service should share, the `base-path` only `domain-lending-loan-origination`
   uses, and the fleet-wide JSON log format, say which config layer each belongs in —
   `common`, a tier layer, or the service's own `application.yml` — and justify each
   from the no-duplication goal of Chapter 4.

## Where to go next

You now have the whole map, and you have watched it run: four tiers, four starters,
two seams, one rule, three live ports. The next chapter fills in the corner of the map
Lumen has only named — the **data** tier on `starter-data`, the enrichment and
data-quality layer that feeds the scoring the domain saga relies on. After that,
Chapter 16 replaces both hand-rolled `WebClient` adapters with the *generated* SDKs and
their resilient defaults — retries, timeouts, a circuit breaker — and restores the full
field mapping the trimmed write seam left to defaults, turning the seams you sliced
here into the real, resilient HTTP calls that hold a Firefly fleet together.
