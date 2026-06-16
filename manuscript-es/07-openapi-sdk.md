In Chapter 6 you built a reactive REST controller and watched it pass an
end-to-end test. That controller is more than an endpoint: it is a *contract*.
Every loan service in Lumen publishes one, and the tier above never reaches into
its database — it calls that contract over HTTP through a generated client. This
chapter is about the contract: how a controller becomes a machine-readable
**OpenAPI** document, how that document turns into human-browsable docs and a
typed **SDK**, and where each piece plugs in.

Be clear about scope before we start. The companion reactor's
`core-lending-loan-origination` module is a single, self-contained service. It
carries the OpenAPI *annotations* on its controller — you will slice the real
ones in a moment — but it does **not** stand up a Swagger UI page or run the SDK
generator as part of its build, because nothing in this one module consumes a
generated client. The SDK story is the pattern the multi-tier build in Chapter 14
depends on. So this chapter slices what the reactor actually contains, and teaches
the generation-and-consumption machinery with clearly-marked illustrative
snippets. When a code block is a sketch rather than a verified slice, it says so.

This is a shorter, conceptual chapter. There is no new hands-on build — the build
is the controller you already wrote — but there is a Run it moment at the end that
confirms the annotated controller still passes.

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
the docs index) and the longer `description`. The reactor's own comment names this
intent: it mirrors the firefly-oss controller style, "constructor injection,
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
    is upstream of the controller: its web starter is the layer that — when you
    enable it — wires a generator over these annotations and serves the result,
    so every service in the fleet exposes its contract the same way.

## From annotations to an OpenAPI document

The annotations are inert until something reads them. In the Spring ecosystem that
something is **springdoc-openapi**: a library that, at startup, scans your
controllers, builds the OpenAPI model from the annotations and signatures above,
and serves it at a well-known URL. Firefly packages and pre-configures this so a
service exposes its contract by convention rather than by hand-wiring.

The loan-origination module does not pull springdoc onto its classpath — it has no
consumer that needs the document — so the following is **illustrative**: it shows
what you add to a service that *should* publish its contract, and what you get.

```xml
<!-- Illustrative: the dependency that turns annotations into a served document. -->
<dependency>
  <groupId>org.springdoc</groupId>
  <artifactId>springdoc-openapi-starter-webflux-ui</artifactId>
</dependency>
```

With that on the classpath and the feature enabled, the running service serves
two things. The first is the raw OpenAPI document — the contract itself — as JSON:

```text
GET /v3/api-docs

{
  "openapi": "3.0.1",
  "info": { "title": "core-lending-loan-origination", "version": "v1" },
  "paths": {
    "/api/v1/loan-applications": {
      "post": {
        "tags": ["LoanApplication"],
        "summary": "Create a loan application",
        "description": "Validates the request, opens and submits a new application.",
        "responses": { "201": { "$ref": "#/components/schemas/LoanApplicationResponse" } }
      }
    }
  }
}
```

Read that JSON against Listing 7.1 and the mapping is exact: the `@Tag` name became
`tags`, the `@Operation` summary and description carried straight over, and the
`201` came from `@ResponseStatus(HttpStatus.CREATED)`. No second source of truth —
the document is derived from the code that serves the requests, so it can never
silently drift from the running behavior.

The second thing the service serves is a human face for that document.

!!! note "Key term — Swagger UI and ReDoc"
    **Swagger UI** and **ReDoc** are two renderers that turn an OpenAPI document
    into a browsable web page. Swagger UI (typically at `/swagger-ui.html`) is
    interactive — it lists every operation and lets you fill in parameters and
    *try the request* live against the running service. ReDoc renders the same
    document as clean, read-only reference documentation. Both consume the exact
    `/v3/api-docs` JSON above; they are views, not separate specs.

Firefly's convention is that enabling API generation through the framework's
properties turns these on together, so a developer who boots any Lumen service can
open the UI and exercise the API without a REST client. In a fleet, that
consistency is the value: the docs live at the same path on every service.

!!! spring "Spring parity"
    On plain Spring Boot you would add the springdoc starter yourself, perhaps
    define an `OpenAPI` bean to set the title and version, and accept springdoc's
    default URLs. Firefly's web layer folds that into auto-configuration gated by a
    property — the framework's `@EnableOpenApiGen`-style switch — so generation is
    a toggle, not a per-service assembly. Either way the annotations on your
    controller are identical; only who wires the generator differs.

## The SDK over HTTP: how tiers talk

Now the payoff. Recall the rule from Chapter 1: tiers never share a database; they
integrate over contracts. When the **domain** tier needs to create a loan
application, it does not import the core module or touch its tables — it makes an
HTTP call to `POST /api/v1/loan-applications`. The question is *how* it makes that
call without hand-writing a `WebClient`, a URL, and a pair of DTOs that duplicate
the core service's own.

The answer is the generated **SDK**. The same OpenAPI document that feeds Swagger
UI also feeds **openapi-generator**, a tool that emits a typed client library from
the contract. Point it at a service's `/v3/api-docs`, choose the reactive WebClient
generator, and it produces a small Java library: a client class per tag, a method
per operation, and model classes for every schema. The tier above adds that library
as a dependency and calls a method instead of crafting an HTTP request.

!!! note "Key term — generated SDK (the SDK-over-HTTP contract)"
    A **generated SDK** is a client library produced *from* a service's OpenAPI
    document, not written by hand. Each Lumen core service ships one — a reactive
    `WebClient`-based client whose methods and model types mirror its controller
    one-to-one. The tier above depends on the SDK and calls typed methods; under
    the hood every call is an HTTP request to the contract. The contract is the
    only coupling. Regenerate the SDK when the contract changes, and the compiler
    tells you what broke.

This module does not run the generator — there is no consumer here — so the
generator configuration below is **illustrative**. In the multi-tier build of
Chapter 14, a plugin like this lives in each core service's `pom.xml` and produces
the SDK that the domain tier consumes.

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

The `webclient` library with `<reactive>true</reactive>` is the part that keeps
the client honest on the reactive stack: every generated method returns a `Mono` or
a `Flux`, never a blocking value, so a call from the domain tier composes into its
own reactive pipeline without parking a thread. A method that maps to the
`create` operation in Listing 7.1 returns `Mono<LoanApplicationResponse>` — the
same return type the controller declares, now on the caller's side of the wire.

Here is what consuming that SDK looks like from the domain tier. This is an
**illustrative** sketch — the generated class names follow openapi-generator's
conventions, and the real client appears in Chapter 16 — but the shape is exactly
what you will write:

```java
// Illustrative: the domain tier calls core through the generated SDK, not a raw WebClient.
public Mono<LoanApplicationResponse> openApplication(CreateLoanApplicationRequest request) {
    return loanApplicationApi.create(request)   // typed method -> POST /api/v1/loan-applications
            .doOnNext(app -> log.info("opened application {}", app.loanApplicationId()));
}
```

Notice what is *absent*: no URL string, no `WebClient` builder, no JSON mapping, no
hand-written request DTO. `loanApplicationApi.create(...)` is a generated method
whose argument and return types are generated from the core service's contract.
If the core team renames a field or changes a status code, regenerating the SDK
shifts the break to compile time in every consumer — which is precisely the
property a fleet wants. Compare that to the alternative every team writes
otherwise:

```java
// The hand-rolled alternative: a URL, a builder, and DTOs duplicated per consumer.
webClient.post()
        .uri("http://core-lending-loan-origination/api/v1/loan-applications")
        .bodyValue(request)
        .retrieve()
        .bodyToMono(LoanApplicationResponse.class);   // and you maintain this DTO by hand
```

Both make the same HTTP call. The generated SDK makes the contract the single
source of truth; the hand-rolled version makes a copy of it in every caller, to be
kept in sync by hope. Chapter 16 builds the real resilient client layer — timeouts,
retries, circuit breakers — *around* generated SDKs like this one.

!!! spring "Spring parity"
    None of this is unique to Firefly: openapi-generator and the WebClient
    generator are standard tools any Spring Boot shop can adopt. What Firefly adds
    is the *convention* — every core service exposes its contract at the same path
    and ships an SDK generated the same way, so the domain tier consumes a fleet of
    services through one uniform, reactive client style instead of a per-service
    grab bag.

## Run it

There is no new test in this chapter — the contract lives on the controller you
already built. But the OpenAPI annotations are real source, so prove the annotated
controller still behaves by running its end-to-end test from the reactor root.

```text
mvn -q -pl core-lending-loan-origination test \
    -Dtest=LoanApplicationControllerTest
```

You should see the three web-layer tests pass:

```text
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
```

!!! tip "Checkpoint"
    Open `LoanApplicationController.java` and confirm the `@Tag` on the class and
    the `@Operation` on each of the three handlers. Those four annotations are the
    entire human-authored half of this service's contract; the machine reads the
    rest from the method signatures. If the test above is green, the annotated
    controller is exactly the one the (illustrative) generator would describe.

## What you learned {.recap}

- A WebFlux controller *is* an API contract. The framework infers paths,
  parameters, and request/response schemas from the annotations and signatures you
  already wrote; you add only human intent with `@Tag` and `@Operation`.
- The loan-origination controller carries real `@Tag` and `@Operation` metadata
  (Listing 7.1). It does **not** itself ship Swagger UI or run the SDK generator —
  that machinery is the pattern Chapter 14's multi-tier build relies on.
- **springdoc-openapi** turns those annotations into a served OpenAPI document at
  `/v3/api-docs`, and **Swagger UI**/**ReDoc** render it as interactive and
  read-only docs. Firefly wires this as a property-gated toggle.
- **openapi-generator** turns the same document into a typed, reactive
  `WebClient`-based **SDK**. Each core service ships one; the tier above depends on
  it and calls typed methods that return `Mono`/`Flux`. The contract is the only
  coupling — the **SDK-over-HTTP** rule that lets a fleet integrate without sharing
  a schema.

## Try it yourself {.exercises}

1. **Add a third operation's intent.** The `list` handler in
   `LoanApplicationController.java` has an `@Operation` summary but no documentation
   of its `status` query parameter. Add a `@Parameter` annotation to the `status`
   argument describing the filter, and re-run the controller test to confirm the
   service still boots and passes.
2. **Predict the document.** Without running a generator, hand-write the `paths`
   entry that springdoc would emit for the `GET /api/v1/loan-applications/{id}`
   operation in Listing 7.1 — the tag, summary, the `id` path parameter, and the
   `200` response schema. Then check your prediction against the signature.
3. **Describe a response schema.** Open `LoanApplicationResponse.java` and list
   which of its eleven fields the generator would mark as required versus optional.
   What in the record tells it `decisionReason` may be absent but
   `loanApplicationId` is always present?
4. **Spot the duplication.** Compare the hand-rolled `WebClient` snippet in this
   chapter with the generated-SDK call above it. Name every artifact the
   hand-rolled version forces each consumer to maintain by hand, and which single
   source of truth the generated SDK replaces them with.

## Where to go next

You now have the contract that ties Lumen's tiers together — a document derived
from controllers, rendered as docs, and consumed as a generated SDK. Chapter 8
turns to the other side of a core service: the R2DBC persistence layer behind that
controller, where `LoanApplicationResponse` is assembled from real rows. The full
multi-tier wiring, where one service's generated SDK becomes another's dependency,
arrives in Chapter 16.
