In Chapter 6 you built a reactive REST controller and watched it pass an
end-to-end test. That controller is more than an endpoint: it is a *contract*.
Every loan service in Lumen publishes one, and the tier above never reaches into
its database — it calls that contract over HTTP. This chapter is about the
contract: how a controller becomes a machine-readable **OpenAPI** document, how
that document turns into human-browsable docs and a typed **SDK**, and where each
piece plugs in.

There is a fact about the reactor that makes this chapter land harder than it used
to. When you ran the full local stack in Chapter 2 — core on `:8081`, domain on
`:8082`, experience on `:8080` — and posted a single application to the BFF, that
request really did travel `exp → domain → core` over HTTP and come back stamped
`SUBMITTED`. The tiers do not share a database; they call each other over exactly
the kind of typed reactive client this chapter describes. So the "SDK seam" is no
longer a diagram. It is the live `WebClient` that moved your loan application three
tiers deep and persisted it in the core system of record. This chapter shows you
that seam, and the contract it rides on.

Be clear about scope before we start, because this book lives or dies on honesty.
The companion reactor's `core-lending-loan-origination` module carries the OpenAPI
*annotations* on its controller, and when it boots it serves a real OpenAPI
document and a Swagger UI — you saw the `Application SwaggerUI:` line in the banner
and curled `/v3/api-docs` in Chapter 2. What the reactor does **not** do is run the
openapi-generator at build time to emit a published client artifact, because the
sample's tiers call each other through a hand-rolled reactive client interface
rather than a generated jar. That interface is the *seam* the generated SDK would
slot into, and the reactor wires it as a live `WebClient`. So this chapter slices
what the reactor actually contains — the annotations and the live client — and
teaches the generation machinery with clearly-marked illustrative snippets. When a
block is a sketch rather than a verified slice, it says so.

This is a shorter, conceptual chapter. There is no new business logic to build —
the build is the controller you already wrote and the client seam the reactor
ships — but there are several Run it moments that confirm the live wiring.

## The contract a controller already carries

A WebFlux controller is, by itself, enough to describe an API. The HTTP method,
the path, the path and query parameters, the request body type, and the response
type are all right there in the annotations and the method signatures. A document
generator can read them by reflection and emit an OpenAPI description without you
writing a separate spec file. What the code *cannot* infer is the human intent: a
summary, a longer description, a logical grouping. That is what the Swagger
annotations add.

Open the loan-origination controller and look at the metadata it already carries.

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/web/LoanApplicationController.java | Listing 7.1 — the OpenAPI annotations the reactor ships
@RestController
@RequestMapping("/api/v1/loan-applications")
@RequiredArgsConstructor
@Tag(name = "LoanApplication", description = "Create and retrieve loan applications")
public class LoanApplicationController {

    private final LoanApplicationService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a loan application",
            description = "Validates the request, opens and submits a new application.")
    public Mono<LoanApplicationResponse> create(
            @Valid @RequestBody CreateLoanApplicationRequest request) {
        return service.create(request);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a loan application",
            description = "Fetches a single application by its id; 404 if absent.")
    public Mono<LoanApplicationResponse> getById(@PathVariable UUID id) {
        return service.getById(id);
    }

    @GetMapping
    @Operation(summary = "List loan applications",
            description = "Lists applications, optionally filtered by status.")
    public Flux<LoanApplicationResponse> list(
            @RequestParam(required = false) ApplicationStatus status) {
        return service.list(status);
    }
}
:::

Two annotations from `io.swagger.v3.oas.annotations` do the work. `@Tag` on the
class groups all three operations under one heading — "LoanApplication" — so the
rendered docs read as a coherent resource rather than a flat list of paths.
`@Operation` on each handler supplies the `summary` (the one-line title shown in
the docs index) and the longer `description`. The reactor's own class comment names
this intent: it mirrors the firefly-oss controller style, "constructor injection,
`@Tag`/`@Operation` OpenAPI metadata, reactive return types."

Everything else the generator needs, it reads from the signatures you already
wrote. `@PostMapping` plus `@RequestBody CreateLoanApplicationRequest` tells it the
verb, the path, and the input schema. `Mono<LoanApplicationResponse>` tells it the
response schema — the generator unwraps the `Mono` and describes the
`LoanApplicationResponse` record's fields. `@RequestParam(required = false)
ApplicationStatus status` becomes an optional `status` query parameter whose
allowed values are the enum constants. You annotate the *intent*; the framework
infers the *shape*.

!!! note "Key term — OpenAPI"
    **OpenAPI** (formerly Swagger) is a vendor-neutral, machine-readable
    specification for an HTTP API: its paths, operations, parameters, request and
    response schemas, and error responses, expressed as a single JSON or YAML
    document. It is the contract two services agree on. Because it is structured
    data, tools can render it as docs, validate requests against it, and generate
    client code from it — which is the whole point of the rest of this chapter.

!!! spring "Spring parity"
    There is nothing Firefly-specific in Listing 7.1. `@Tag` and `@Operation` are
    the standard `swagger-core` annotations, and they behave on a Firefly service
    exactly as they do on any Spring Boot WebFlux service. Firefly's contribution
    is upstream of the controller: its web starter wires a generator over these
    annotations and serves the result by default, so every service in the fleet
    exposes its contract at the same path without per-service assembly.

## From annotations to a served document

The annotations are inert until something reads them. In the Spring ecosystem that
something is **springdoc-openapi**: a library that, at startup, scans your
controllers, builds the OpenAPI model from the annotations and signatures above,
and serves it at a well-known URL. Firefly's web starter packages and
pre-configures springdoc, which is why the core service already advertised a
Swagger UI URL in its banner without you adding a dependency.

You saw the proof in Chapter 2, but it is worth re-reading here for what it tells
you about the *contract*. Boot the core service the runnable way — from
`samples/lumen-lending/core-lending-loan-origination`, `mvn spring-boot:run` (or
run the repackaged `java -jar target/*.jar`); it serves on `:8081` against
in-memory H2 with Flyway, no Docker — then ask for the document:

```text
$ curl -s http://localhost:8081/v3/api-docs
{"openapi":"3.1.0","info":{"title":"core-lending-loan-origination API","description":"core-lending-loan-origination API Documentation","license":{"name":"Apache 2.0","url":"https://www.apache.org/licenses/LICENSE-2.0"},"version":"1.0.0"}, ... }
```

Read that `info` block against everything you know about the service. The `openapi`
version is **3.1.0** — the framework defaults to the current spec, not the older
`3.0.1`. The `title`, `core-lending-loan-origination API`, is built by the
framework from your `spring.application.name`, suffixed with `API` — the same
three-line `application.yml` property that surfaced in the banner now surfaces a
second time in the contract. The `license` is stamped in for the whole fleet:
Apache 2.0, the same license the banner footer announces. No second source of
truth — the document is derived from the code that serves the requests and the one
property you set, so it can never silently drift from the running behavior.

Further down, the `paths` object carries the three operations from Listing 7.1.
The mapping is exact: the `@Tag` name becomes each operation's `tags`, the
`@Operation` summary and description carry straight over, and the `201` on `create`
comes from `@ResponseStatus(HttpStatus.CREATED)`. Here is the shape springdoc emits
for the `create` operation — **illustrative** in its exact key ordering, but every
value is traceable to Listing 7.1:

```json
{
  "paths": {
    "/api/v1/loan-applications": {
      "post": {
        "tags": ["LoanApplication"],
        "summary": "Create a loan application",
        "description": "Validates the request, opens and submits a new application.",
        "responses": {
          "201": {
            "content": {
              "application/json": {
                "schema": { "$ref": "#/components/schemas/LoanApplicationResponse" }
              }
            }
          }
        }
      }
    }
  }
}
```

The second thing the service serves is a human face for that document.

!!! note "Key term — Swagger UI and ReDoc"
    **Swagger UI** and **ReDoc** are two renderers that turn an OpenAPI document
    into a browsable web page. Swagger UI (the URL the banner printed) is
    interactive — it lists every operation and lets you fill in parameters and
    *try the request* live against the running service. ReDoc renders the same
    document as clean, read-only reference documentation. Both consume the exact
    `/v3/api-docs` JSON above; they are views, not separate specs.

Because Firefly's web starter turns springdoc on by convention, a developer who
boots any Lumen tier can open the UI and exercise the API without a REST client.
In a fleet, that consistency is the value: the document lives at `/v3/api-docs` and
the UI at the banner's URL on every service, core, domain, and experience alike.

!!! spring "Spring parity"
    On plain Spring Boot you would add the springdoc starter yourself, perhaps
    define an `OpenAPI` bean to set the title and version, and accept springdoc's
    default URLs. Firefly's web layer folds that into auto-configuration and
    derives the title from `spring.application.name` and the license from the
    fleet default — so generation is the baseline, not a per-service assembly.
    Either way the annotations on your controller are identical; only who wires
    the generator differs.

## The SDK seam: how tiers actually talk

Now the payoff, and this is where the reactor has moved beyond a diagram. Recall
the rule from Chapter 1: tiers never share a database; they integrate over
contracts. When the **domain** tier needs to create a loan application, it does not
import the core module or touch its tables — it makes an HTTP `POST` to
`/api/v1/loan-applications`. When the **experience** tier needs to submit an
application, it `POST`s to the domain's `/api/v1/applications`. The question is
*how* a tier makes that call without scattering a `WebClient`, a URL, and a pair of
duplicated DTOs through its business code.

The answer is to put the call behind a **typed reactive port** — a Java interface
whose methods mirror the downstream operations one-to-one and return `Mono`/`Flux`.
The business code (a command handler, a saga step, a BFF service) depends on the
interface; an adapter behind it owns the HTTP. In production that adapter is the
**generated SDK**: openapi-generator reads the downstream service's
`/v3/api-docs`, the same document you just curled, and emits a client class per
tag and a method per operation. In the reactor — which keeps the build Docker-free
and self-contained — the adapter is a hand-rolled `WebClient` that stands in for
exactly that generated client. Same seam; the only difference is who writes the
adapter.

!!! note "Key term — the SDK seam"
    The **SDK seam** is the typed reactive interface a tier calls to reach the next
    tier down — `LoanOriginationClient` in the domain (to core),
    `LoanOriginationDomainClient` in the experience tier (to domain). Business code
    depends only on the interface and its `Mono`/`Flux` methods; an adapter behind
    it makes the HTTP call. In a production Firefly service the adapter *is* the
    generated SDK; in this reactor it is a `WebClient` that mirrors what the
    generated client would do. The contract is the only coupling.

Look at the domain tier's port. Its Javadoc states the relationship plainly: in the
real Firefly service this interface is backed by the generated core SDK client
(`LoanApplicationsApi`, a `WebClient`-based stub generated from core's OpenAPI
contract); the reactor hand-rolls a trimmed port "so the sample compiles and tests
run with no running core service and no Docker."

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/client/LoanOriginationClient.java | Listing 7.2 — the domain's SDK seam to core, a reactive port
public interface LoanOriginationClient {

    /**
     * Creates the loan application in the core system of record and returns its server-assigned id.
     *
     * @param applicantName the primary applicant's display name
     * @param amount        the requested principal, in minor units
     * @return the new loan application id
     */
    Mono<UUID> createLoanApplication(String applicantName, long amount);
:::

Every method on the port returns a `Mono` because the whole stack — command
handlers, saga steps, the core HTTP call — is non-blocking end to end. The methods
map one-to-one onto the saga steps that need them: `createLoanApplication` is the
root step `registerLoanApplication`, and (further down the interface)
`removeLoanApplication` is its compensation, `addApplicant` and `proposeOffer` are
the dependent steps. That is the generated-SDK shape: one typed method per
operation, reactive return types, no URLs in sight.

Now the live adapter. When you set a core base path, the reactor wires a
`WebClient`-backed implementation of that port. Here is its root method — the
one your Chapter 2 submit actually executed when the saga ran:

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/client/WebClientLoanOriginationClient.java | Listing 7.3 — the live WebClient that the SDK seam stands in for
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

This is the generated SDK's body, written by hand. `LOAN_APPLICATIONS_PATH` is
`/api/v1/loan-applications` — the exact path from Listing 7.1 — and the
`CoreCreateRequest`/`CoreLoanApplicationResponse` records are the domain's local
view of core's `CreateLoanApplicationRequest` and `LoanApplicationResponse` DTOs.
The method `POST`s, unwraps the `Mono<CoreLoanApplicationResponse>`, and maps it
down to the one field the saga needs: the core-assigned `loanApplicationId`. When
you posted to the BFF in Chapter 2 and saw the application land in core, *this
method made the write*. The compensating `removeLoanApplication` (a `DELETE` on the
same resource) and the read-side `findById` (a `GET`) sit just below it in the same
class, completing the live seam.

!!! note "Honest scope — the trimmed write seam"
    Be precise about what crosses the wire. The domain's `createLoanApplication`
    takes only an applicant name and an amount, so the adapter fills the rest of
    core's request with defaults: `DEFAULT_CURRENCY` (`EUR`),
    `DEFAULT_PURPOSE` (`GENERAL`), and `DEFAULT_TERM_MONTHS` (`12`). That is why,
    in the Chapter 2 cross-tier verification, the application that arrived at core
    showed `"currency":"EUR"`, `"purpose":"GENERAL"`, and `"termMonths":12`
    regardless of what the channel request carried. The mapping is intentionally
    minimal — rich, lossless field mapping is exactly the job a *generated* SDK
    (every field of the contract, typed) does for the production service. The
    seam is real and live; the payload it carries here is deliberately small.

The same pattern repeats one tier up. The experience BFF's port,
`LoanOriginationDomainClient`, is backed in production by the generated *domain*
SDK; in the reactor its adapter `WebClient`-`POST`s to the domain's
`/api/v1/applications` and — note the detail — forwards a deterministic
`Idempotency-Key` header so a retried channel request dedupes downstream without
the client minting a new resource id. Two seams, two adapters, one uniform reactive
style, all the way from the channel to the system of record.

!!! spring "Spring parity"
    None of this is unique to Firefly: openapi-generator and its WebClient library
    are standard tools any Spring Boot shop can adopt. What Firefly adds is the
    *convention* — every service exposes its contract at `/v3/api-docs`, and the
    tier above consumes it through a typed reactive port whose adapter is generated
    (production) or hand-rolled to match (this reactor). The consumer's business
    code never changes shape between the two.

## Generating the SDK — the pattern behind the seam

The reactor's adapters are hand-written to keep the build self-contained, but the
shape they imitate is mechanical. The same `/v3/api-docs` document that feeds
Swagger UI also feeds **openapi-generator**, which emits the typed client for you.
The plugin below is **illustrative** — it does not run in this module — but in a
production Firefly core service it lives in the `pom.xml` and produces the SDK the
domain tier depends on:

```xml
<!-- Illustrative: generate a reactive WebClient SDK from the served contract. -->
<plugin>
  <groupId>org.openapitools</groupId>
  <artifactId>openapi-generator-maven-plugin</artifactId>
  <executions>
    <execution>
      <goals><goal>generate</goal></goals>
      <configuration>
        <inputSpec>${project.basedir}/target/api-docs.json</inputSpec>
        <generatorName>java</generatorName>
        <library>webclient</library>
        <configOptions>
          <reactive>true</reactive>
          <useJakartaEe>true</useJakartaEe>
        </configOptions>
      </configuration>
    </execution>
  </executions>
</plugin>
```

The `webclient` library with `<reactive>true</reactive>` is the part that keeps the
client honest on the reactive stack: every generated method returns a `Mono` or a
`Flux`, never a blocking value, so a call composes into the caller's pipeline
without parking a thread — precisely the shape of `LoanOriginationClient` in
Listing 7.2. The method that maps to `create` in Listing 7.1 would return
`Mono<LoanApplicationResponse>`, the same return type the controller declares, now
on the caller's side of the wire.

Compare the generated (or generated-equivalent) call with the alternative a team
writes when it skips the SDK entirely. The generated form:

```java
// The SDK seam: a typed method, no URL, no DTO duplicated by hand.
return loanApplicationsApi.create(request)            // -> POST /api/v1/loan-applications
        .map(LoanApplicationResponse::loanApplicationId);
```

The hand-rolled-from-scratch form — note that this is *almost* what Listing 7.3 is,
which is the point:

```java
// The hand-rolled alternative: a URL, a builder, and DTOs duplicated per consumer.
webClient.post()
        .uri("http://core-lending-loan-origination/api/v1/loan-applications")
        .bodyValue(request)
        .retrieve()
        .bodyToMono(LoanApplicationResponse.class);   // and you maintain this DTO by hand
```

Both make the same HTTP call. The generated SDK makes the contract the single
source of truth and shifts any contract change to *compile time* in every consumer;
the hand-rolled version makes a copy of the request/response DTOs in every caller,
kept in sync by hope. The reactor lands in a deliberate middle: it hand-rolls the
adapter (to stay Docker-free) but isolates it behind the typed port, so the
business code already enjoys the SDK's decoupling and the generated client could
drop in without touching a single saga step. Chapter 16 builds the resilient client
layer — timeouts, retries, circuit breakers — *around* SDKs like this.

!!! spring "Spring parity"
    Regenerating an SDK when a contract changes is the reactive analogue of
    recompiling against an updated interface. On plain Spring you might lean on
    `@HttpExchange` declarative clients or hand-write a `WebClient` per dependency;
    the generated-SDK convention gives a fleet one uniform, contract-derived client
    style instead of a per-team grab bag — and the typed port in front of it means
    swapping generated for hand-rolled is invisible to callers.

## How the live seam is wired — conditional, never in the way

One more thing makes the reactor's seam production-faithful rather than a toy: it
turns *on by configuration*, and it steps aside for tests. The live core client is
registered by a plain `@Configuration` guarded by two conditions.

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/config/LiveLoanOriginationClientConfig.java | Listing 7.4 — the live SDK seam activates only when a core base path is set
    @Bean
    @ConditionalOnProperty(prefix = "firefly.lumen.core.loan-origination", name = "base-path")
    @ConditionalOnMissingBean(LoanOriginationClient.class)
    public WebClientLoanOriginationClient webClientLoanOriginationClient(WebClient coreLoanOriginationWebClient) {
        log.info("Wiring live WebClient LoanOriginationClient against core; the saga writes to core over HTTP");
        return new WebClientLoanOriginationClient(coreLoanOriginationWebClient);
    }
:::

`@ConditionalOnProperty(... base-path)` means the live `WebClient` client only
materializes when an operator points the domain tier at a real core service. The
shipped `src/main/resources/application.yml` sets
`firefly.lumen.core.loan-origination.base-path` to `http://localhost:8081`, so the
standalone domain app calls the running core — which is exactly why your Chapter 2
cross-tier submit worked end to end. `@ConditionalOnMissingBean` means the slice
tests, which register their own in-memory `LoanOriginationClient` stub, always win:
the live client never displaces the test seam, so all 33 reactor tests (core 18,
domain 6, exp 9) stay green with no network and no Docker. The experience tier's
`LoanOriginationClientConfig` follows the identical pattern with the
`lumen.exp.loan-origination.base-path` property. Configure a base path, and the
seam goes live; leave it unset (as the tests do), and an in-JVM stub stands in.

!!! note "Key term — conditional client wiring"
    A **conditional client** bean is one Spring only creates when its guards hold —
    here, `@ConditionalOnProperty` (a base path is configured) and
    `@ConditionalOnMissingBean` (no test stub already owns the port). This is the
    same auto-configuration idiom the starters use, applied to the SDK seam: the
    *same* business code runs against a live HTTP client in production and an
    in-memory double in tests, chosen entirely by what is on the classpath and in
    the config — never by a code change.

## Run it

There is no new business logic in this chapter — the contract lives on the
controller you already built, and the seam is the client the reactor ships. So
prove the two halves that this chapter is about.

First, prove the annotated controller still behaves. From `samples/lumen-lending`:

```text
$ mvn -q -pl core-lending-loan-origination test \
    -Dtest=LoanApplicationControllerTest
```

You should see the three web-layer tests pass:

```text
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in com.firefly.lumen.core.web.LoanApplicationControllerTest
```

Second, prove the document is real. With the core service running on `:8081`
(`mvn spring-boot:run` from its module, or the repackaged `java -jar`), read the
contract straight from the live service:

```text
$ curl -s http://localhost:8081/v3/api-docs
{"openapi":"3.1.0","info":{"title":"core-lending-loan-origination API","description":"core-lending-loan-origination API Documentation","license":{"name":"Apache 2.0","url":"https://www.apache.org/licenses/LICENSE-2.0"},"version":"1.0.0"}, ... }
```

!!! tip "Checkpoint"
    Three things confirm this chapter. (1) Open `LoanApplicationController.java`
    and find the `@Tag` on the class and the `@Operation` on each of the three
    handlers — those four annotations are the entire human-authored half of the
    contract. (2) Confirm `/v3/api-docs` reports `"openapi":"3.1.0"` and
    `"title":"core-lending-loan-origination API"` — the machine read the rest from
    your signatures and your one `application.yml` property. (3) Open
    `WebClientLoanOriginationClient.java` and confirm its `createLoanApplication`
    `POST`s to `/api/v1/loan-applications` — that is the live SDK seam your Chapter 2
    submit actually traveled.

## What you learned {.recap}

- A WebFlux controller *is* an API contract. The framework infers paths,
  parameters, and request/response schemas from the annotations and signatures you
  already wrote; you add only human intent with `@Tag` and `@Operation`
  (Listing 7.1).
- Firefly's web starter wires **springdoc-openapi** by default, so the running core
  service serves a real OpenAPI document at `/v3/api-docs` — `"openapi":"3.1.0"`,
  title `core-lending-loan-origination API` derived from `spring.application.name`,
  Apache 2.0 license — and a Swagger UI at the banner's URL. No second source of
  truth.
- Tiers integrate over that contract through a typed reactive **SDK seam** — a Java
  interface (`LoanOriginationClient`, `LoanOriginationDomainClient`) whose methods
  return `Mono`/`Flux`. Business code depends on the interface; an adapter behind it
  owns the HTTP.
- In a production Firefly service the adapter *is* a generated SDK
  (openapi-generator, `webclient`, `reactive=true`). In this reactor the adapter is
  a live `WebClient` (Listing 7.3) that stands in for the generated client — same
  seam, same decoupling. The Chapter 2 `exp → domain → core` submit ran through
  these adapters for real; the domain→core write is intentionally trimmed (some
  core fields default to `EUR`/`GENERAL`/`12`).
- The live seam is **conditionally wired** (Listing 7.4): it activates only when a
  base path is configured and backs off for tests, so all 33 reactor tests stay
  green with no network and no Docker.

## Try it yourself {.exercises}

1. **Add a third operation's intent.** The `list` handler in
   `LoanApplicationController.java` has an `@Operation` summary but no documentation
   of its `status` query parameter. Add a `@Parameter` annotation to the `status`
   argument describing the filter, re-run `mvn -q -pl core-lending-loan-origination
   test`, and boot the service to confirm the parameter description now appears in
   `/v3/api-docs`.
2. **Read the real document.** Boot the core service and curl `/v3/api-docs`. Find
   the `paths` entry for `GET /api/v1/loan-applications/{id}` and check it against
   Listing 7.1 — the `LoanApplication` tag, the summary, the `id` path parameter,
   and the `200` response that `$ref`s `LoanApplicationResponse`.
3. **Describe a response schema.** Open `LoanApplicationResponse.java` and list
   which of its eleven fields the generator marks as required versus optional. What
   in the record tells it `decisionReason` may be `null` (you saw it as `null` in
   Chapter 2's response) while `loanApplicationId` is always present?
4. **Trace the live seam.** Open `WebClientLoanOriginationClient.java` and follow
   `createLoanApplication`: which path does it `POST` to, which fields does it fill
   with defaults, and which single field does it `map` out of the core response?
   Then match those defaults to the `currency`/`purpose`/`termMonths` values the
   application showed when you verified it landed in core in Chapter 2.
5. **Flip the condition.** In the domain's `application.yml`, the property
   `firefly.lumen.core.loan-origination.base-path` is what makes Listing 7.4's live
   client materialize. Predict what the domain tier does if you unset it (hint:
   `@ConditionalOnMissingBean` and the in-JVM default in `LoanOriginationClientConfig`),
   and which `[orchestration]` saga lines would still appear.

## Where to go next

You now have the contract that ties Lumen's tiers together — a document derived
from controllers, served at `/v3/api-docs`, rendered as Swagger UI, and consumed
through a typed reactive SDK seam that, in this reactor, is a live `WebClient`.
Chapter 8 turns to the other side of a core service: the R2DBC persistence layer
behind that controller, where `LoanApplicationResponse` is assembled from real H2
rows. The resilient client layer that wraps SDK seams like these — timeouts,
retries, circuit breakers — arrives in Chapter 16.
