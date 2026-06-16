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

We will list the directories so you can see the tiers as real modules, slice the
experience tier's two seams — its channel-facing controller and the reactive port
that is its boundary to the domain — and then walk the four starters, the
no-shared-database rule, and the configuration hierarchy that ties the fleet
together. Lumen's slice ships three of the four tiers; the fourth, **data**, we name
here and build in Chapter 15.

## The four directories

Open `samples/lumen-lending` and the architecture is sitting in the directory
listing. Three modules, one per tier built so far, under one parent POM:

```text
samples/lumen-lending/
├── pom.xml                              # the parent: BOM import, module list
├── exp-lending/                         # experience tier  (starter-application)
├── domain-lending-loan-origination/     # domain tier      (starter-domain)
└── core-lending-loan-origination/       # core tier        (starter-core)
```

Read the names as a stack. A channel request lands on `exp-lending`, the
Backend-for-Frontend. It calls `domain-lending-loan-origination`, the orchestration
layer that runs the CQRS commands and the saga. That domain service calls
`core-lending-loan-origination`, the system of record that owns the schema and the
rows. The data flows down the stack on the way in and back up on the way out, and at
every boundary the call crosses a *network contract*, not a method call into shared
code.

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
different services on two different ports; a client never reaches the core directly,
only the experience tier in front of it.

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

## The four tiers, and why each picks its starter

Now the whole shape. Four tiers, four starters, each bundling the capabilities and
production defaults that its role needs and nothing it does not. The starter is how a
service declares which tier it is — a single dependency, version-less because the BOM
from Chapter 3 pins it.

- **Experience (`exp-lending`) — `fireflyframework-starter-application`.** The
  channel-facing BFF. Stateless composition: validate, call downstream, shape a DTO.
  Its starter brings the channel concerns — method security (`@Secure`), caching, the
  resilient client machinery to call domain services — but no persistence, because it
  owns no data. This is the tier in Listings 14.1 and 14.2.
- **Domain (`domain-lending-loan-origination`) — `fireflyframework-starter-domain`.**
  Business orchestration. Translates coarse channel requests into CQRS commands and
  queries, runs compensating sagas, emits domain events (Chapters 10–13). Its starter
  brings the CQRS buses, saga engine, and EDA runtime — and, like the experience tier,
  no database, because it calls the core over an SDK.
- **Core (`core-lending-loan-origination`) — `fireflyframework-starter-core` plus
  R2DBC.** The system of record. Owns the schema, the rows, and the reactive CRUD and
  business APIs over them (Chapters 6–8). It is the *only* tier with a datastore, so
  its starter is paired with the reactive R2DBC stack.
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
`LoanOriginationClient`; the experience tier reaches the domain through
`LoanOriginationDomainClient`. Neither the experience nor the domain service has an
R2DBC dependency, a connection pool, or a repository — and that is by design, not
omission. If the domain tier could query the core's tables directly, every change to
the core's schema would risk breaking the domain service silently, and the two would
be welded together exactly the way the CQRS handler was welded to its consumers in
Chapter 11.

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

## Run it

The experience tier's slice tests prove the BFF boots, secures its endpoints, and
composes downstream — all against the in-memory stub for the domain port, with no
running domain service and no Docker. From the `samples/lumen-lending` directory:

```text
mvn -q -pl exp-lending test
```

You should see all nine pass:

```text
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Those nine cover the context booting (`ExpLendingApplicationTest`), the controller's
create-and-read paths over `WebTestClient` (`ApplicationControllerTest`), and the
service's composition logic against the stub port (`ApplicationServiceTest`). The
substitution is the same seam trick you saw one tier down: the test supplies an
in-memory `LoanOriginationDomainClient`, and nothing in the controller or service
changes. That is the tier boundary earning its keep — the experience tier is testable
in complete isolation from the domain tier it fronts.

!!! tip "Checkpoint"
    Run the command and confirm `Tests run: 9, Failures: 0`. Then open
    `src/test/java` and find `StubLoanOriginationDomainClient` — the in-memory
    implementation of the port from Listing 14.2. It is the only thing standing in for
    a whole downstream service, which is exactly why these nine tests run in seconds.

## What you learned {.recap}

- Lumen Lending is **four tiers**: **experience** (channel-facing BFF, on
  `starter-application`), **domain** (orchestration, on `starter-domain`), **core**
  (system of record, on `starter-core` plus R2DBC), and **data** (enrichment, on
  `starter-data`). The slice builds the first three; **data** is named here and built
  in Chapter 15.
- A tier declares its role by the **one starter** its POM imports — the cross-ref
  from Chapter 3, now read as architecture: experience→`starter-application`,
  domain→`starter-domain`, core→`starter-core`.
- The experience tier is a thin composition layer — base path
  `/api/v1/experience/lending/applications`, no business logic, no database — that
  reaches the domain through `LoanOriginationDomainClient`, a reactive port that is
  the **exp→domain SDK seam** (the generated domain SDK plugs in behind it in
  production).
- The cardinal rule: tiers integrate over **generated SDKs and HTTP, and never share
  a database**. Only the core owns a datastore; the two reactive ports are where the
  rule is enforced in code.
- Configuration follows the tiers — the Chapter 4 **common / core / domain /
  experience** hierarchy uses the same four words, so a service's tier selects both
  the shared dependencies and the shared config it inherits.

## Try it yourself {.exercises}

1. **Trace a request down the stack.** Starting at `createApplication` in
   `ApplicationController`, follow the call into `ApplicationService` and out through
   `LoanOriginationDomainClient.submitApplication`. Write down every boundary the
   request crosses and name which is a method call and which is (in production) a
   network hop.
2. **Find the absent database.** Open `exp-lending/pom.xml` and
   `domain-lending-loan-origination/pom.xml` and confirm neither declares R2DBC or a
   repository dependency, then open `core-lending-loan-origination/pom.xml` and find
   where the datastore lives. Explain in one sentence why only one of the three has it.
3. **Read the starter as the tier marker.** For each of the three modules, find the
   single `fireflyframework-starter-*` line in its POM. Cover the artifact id and
   predict the tier from the directory name; uncover it and check. Which starter would
   a new `data-lending-bureau` module declare?
4. **Compare the two seams.** Put `LoanOriginationDomainClient` (Listing 14.2) next to
   `LoanOriginationClient` (Listing 10.6). List what is the same about them (reactive
   ports, stand in for a generated SDK, tested with an in-memory stub) and what differs
   (the `idempotencyKey` parameter, the DTO types). Why does the experience seam carry
   an idempotency key the domain seam did not surface?
5. **Place a setting in the hierarchy.** Given a default downstream timeout that
   *every* domain service should share, and a base path that only `exp-lending` uses,
   say which config layer each belongs in — `common`, a tier layer, or the service's
   own `application.yml` — and justify each from the no-duplication goal of Chapter 4.

## Where to go next

You now have the whole map: four tiers, four starters, two seams, one rule. The next
chapter fills in the corner of the map Lumen has only named — the **data** tier on
`starter-data`, the enrichment and data-quality layer that feeds the scoring the
domain saga relies on. After that, Chapter 16 replaces both hand-rolled ports with the
*generated* SDKs and their resilient defaults, turning the seams you sliced here into
the real, resilient HTTP calls that hold a Firefly fleet together.
