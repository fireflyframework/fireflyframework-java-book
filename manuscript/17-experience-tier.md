The core tier owns the system of record. The domain tier orchestrates it with
commands, queries, and sagas. Neither is shaped for a phone or a web app. A
channel needs a request it can post in one round trip, a response it can render
without a second call, and an answer to "what happens when the user taps *Submit*
twice?" That is the job of the **experience tier** — the backend-for-frontend, or
BFF — and it is what you build in this chapter.

The experience tier is deliberately thin. It holds no database and owns no
business rules of its own. Its whole purpose is *composition*: shape a channel
request, call the domain over the SDK seam, and map the result to a lightweight
view the front-end actually wants. Everything stateful lives behind it. What it
adds is the edge concerns a channel needs — validation at the boundary,
declarative authorization, deterministic idempotency so a retried submit does not
create two applications, and a response model decoupled from the domain's internals.

In this chapter you slice Lumen's `exp-lending` module: a secured reactive
controller, the service that validates and composes, the deterministic
idempotency-key helper that makes a retry safe, and the channel DTOs that are the
BFF's own wire contract. A nine-test suite proves it boots, validates, dedupes,
and maps — with no domain service and no Docker. Let's start with what makes a
module an experience tier at all.

## The application starter

Every tier in Lumen leans on a Firefly *starter* — a Boot starter that turns on
exactly the capabilities that tier needs. The core tier uses `starter-core`; the
domain tier uses `starter-domain`. The experience tier uses
`fireflyframework-starter-application`, the starter built for the layer that faces
clients. Its single dependency line pulls in the application-layer concerns —
declarative security, caching, the CQRS plumbing, the client factory — so the BFF
boots with them already wired.

::: listing exp-lending/pom.xml | Listing 17.1 — the experience tier is built on the application starter
        <!-- Application/experience-layer starter (security, cache, CQRS, client). -->
        <dependency>
            <groupId>org.fireflyframework</groupId>
            <artifactId>fireflyframework-starter-application</artifactId>
        </dependency>
        <!-- Reactive web layer helpers. -->
        <dependency>
            <groupId>org.fireflyframework</groupId>
            <artifactId>fireflyframework-web</artifactId>
        </dependency>
:::

Two dependencies and the module is an experience tier. `starter-application` brings
the application-layer machinery; `fireflyframework-web` brings the same reactive web
helpers you met in Chapter 6 — the `GlobalExceptionHandler` that renders RFC 7807
problem details, the idempotency filter, the transaction stamping. Because the web
module is present, every `BusinessException` the BFF throws becomes a clean
problem-detail response with no handler code, exactly as it did in the core tier.

!!! note "Key term — the application starter (`fireflyframework-starter-application`)"
    The Firefly Boot starter for the **experience/application layer** — the tier that
    composes downstream services for a channel. It auto-configures declarative
    security (`@Secure`), result caching, the CQRS buses, and the client factory that
    builds generated SDK clients, so a BFF module gets the channel-facing capabilities
    by dependency presence alone. The core tier's `starter-core` and the domain tier's
    `starter-domain` are its siblings; each tier picks the starter that matches its job.

## Step 1 — A secured reactive controller

The controller is the channel's front door. It is a plain reactive
`@RestController` — `@PostMapping` to create, `@GetMapping` to read one — and like
the core controller in Chapter 6 it carries no error handling and no envelope. What
is new here is the `@Secure` annotation on each method: declarative, per-endpoint
authorization that the application starter enforces.

::: listing exp-lending/src/main/java/com/firefly/lumen/exp/web/ApplicationController.java | Listing 17.2 — the BFF controller: reactive, secured with @Secure
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

Read the two methods first, then the security. `createApplication` takes a
`@Valid @RequestBody CreateApplicationRequest`, hands it to the service, and maps
the result to a `201 Created` `ResponseEntity`. `getApplication` reads one by id and
maps it to `200 OK`. Both return `Mono` of a `ResponseEntity`, so the BFF is
non-blocking from the socket inward, and both delegate immediately — the controller
holds no logic. That `@Valid` arms Bean Validation on the request at the very edge,
the first line of defense before anything crosses into the service.

The `@Secure` annotation is the experience-tier addition. `@Secure(permissions =
{"lending:application:create"})` declares that a caller must hold the
`lending:application:create` permission to invoke `createApplication`; the read
method requires `lending:application:read`. You do not write an `if` that checks a
principal, and you do not configure a URL-pattern rule in a filter chain. You state
the permission on the method, and the application starter's `SecurityAspect`
intercepts the call and enforces it.

!!! note "Key term — declarative security (`@Secure`)"
    `@Secure` is the application layer's method-level authorization annotation. You
    declare the `permissions` (and optionally roles, an expression, or a tenant scope)
    a method requires; the starter's `SecurityAspect` — an AOP advice — intercepts the
    annotated method, resolves the caller's `AppSecurityContext`, and either proceeds or
    rejects before your code runs. Authorization is a *declaration on the handler*, not
    a rule scattered across a filter chain, so the contract is visible right where the
    method is defined.

!!! spring "Spring parity"
    `@Secure` plays the role Spring Security's `@PreAuthorize` plays, but it is a
    Firefly annotation evaluated by the application starter's `SecurityAspect`, tuned by
    `firefly.application.security.*` properties and consistent across every BFF in the
    fleet. The surrounding HTTP filter chain is still stock Spring Security
    `@EnableWebFluxSecurity` — Lumen's `WebSecurityConfig` simply permits all exchanges
    and disables the noisy stateless-REST defaults (HTTP Basic, form login, CSRF), so
    that *method* authorization, not the filter chain, is the single place authorization
    lives.

### `@Secure` is real, but disabled in the slice test

Be honest about what the slice test exercises. The controller's `@Secure`
annotations are real production code — the `SecurityAspect` intercepts both methods
at runtime, which you can see in the test log (`Intercepting @Secure method:
createApplication`). But the slice test does not mint tokens or stand up a security
center. Instead it flips one property in `src/test/resources/application.yml`:

```yaml
firefly:
  application:
    security:
      enabled: false
```

With `firefly.application.security.enabled=false`, the aspect short-circuits — it
logs that security is disabled and allows the call through — so the `WebTestClient`
slice can drive the BFF without authentication. The annotation is present and
intercepted; only *enforcement* is off. Production keeps the property `true` and the
permissions are checked for real. Chapter 19 builds out the full security story —
how `AppSecurityContext` is populated, how permissions are resolved, how the token
arrives. Here, treat `@Secure` as wired and visible, with enforcement parked for the
slice.

!!! warning "The slice proves wiring and composition, not authorization"
    Because the test sets `security.enabled=false`, a green run does *not* prove that
    `lending:application:create` is enforced. It proves the controller is secured-by-
    annotation, that the request validates, that the service composes, and that errors
    render as problem details. Do not read the passing slice as an authorization test —
    that is Chapter 19's job, against the enforcement-on path.

## Step 2 — The channel DTOs

The BFF never returns a domain type on the wire. It maps the domain response into
its own *channel* record, so the front-end depends on the experience tier's contract,
not on the domain's internals. That decoupling is the whole point of a
backend-for-frontend: you can reshape the domain SDK's model without breaking a
single app screen, because the channel view stands between them.

The request the channel posts is `CreateApplicationRequest`, a record validated at
the edge by ordinary Bean Validation constraints.

::: listing exp-lending/src/main/java/com/firefly/lumen/exp/dto/CreateApplicationRequest.java | Listing 17.3 — the channel request, validated at the edge
public record CreateApplicationRequest(

        @NotNull(message = "productId is required")
        UUID productId,

        @NotNull(message = "requestedAmount is required")
        @DecimalMin(value = "0.01", message = "requestedAmount must be strictly positive")
        BigDecimal requestedAmount,

        @NotNull(message = "term is required")
        @Min(value = 1, message = "term must be at least 1 month")
        Integer term,

        String purpose,

        UUID simulationId
) {
}
:::

`productId`, `requestedAmount`, and `term` are required and constrained;
`purpose` and `simulationId` are optional. The `@Valid` on the controller's
`@RequestBody` triggers these constraints before the handler body runs, so a zero
amount is rejected with a `400` at the boundary — you will see exactly that in the
run. The `simulationId` is a soft link back to the simulation that produced this
application; the channel sends it so the BFF can echo it back.

The response the channel renders is `ApplicationDetailDTO` — the BFF's own full view,
shaped for a screen, not for the domain's storage.

::: listing exp-lending/src/main/java/com/firefly/lumen/exp/dto/ApplicationDetailDTO.java | Listing 17.4 — the channel response view, decoupled from the domain wire model
public record ApplicationDetailDTO(
        UUID applicationId,
        UUID simulationId,
        String status,
        BigDecimal requestedAmount,
        Integer term,
        String purpose,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
:::

This is the record the controller returns and the test asserts against. Notice it
echoes `simulationId` straight back, so the front-end can persist the traceability
link without a follow-up call — a small composition decision that belongs to the
channel, not the domain. The module also keeps an `ApplicationSummaryDTO` for list
responses; the pattern is identical, a lighter shape for a different screen. The
domain may return twenty fields; the BFF returns the eight a detail screen needs,
and nothing leaks through that the channel did not ask for.

!!! note "Key term — channel DTO"
    A **channel DTO** is a view type owned by the experience tier and shaped for one
    client surface — a detail screen, a list row. It is the BFF's wire contract,
    deliberately separate from the domain SDK's model so the two can evolve
    independently. The service maps domain responses into channel DTOs; the front-end
    only ever sees these, which is what lets the domain change its internals without a
    coordinated client release.

## Step 3 — The service: validate, derive a key, compose, map

The controller delegates everything to `ApplicationService`, and this is where the
experience tier earns its name. The service does four things and only four: it
validates the channel request, derives a *deterministic* idempotency key from the
request's stable fields, crosses the SDK seam to the domain, and maps any downstream
failure to a `BusinessException` the web module can render. No persistence, no
business rules — pure composition.

::: listing exp-lending/src/main/java/com/firefly/lumen/exp/service/ApplicationService.java | Listing 17.5 — compose: validate, derive a deterministic key, call the SDK, map errors
    public Mono<ApplicationDetailDTO> createApplication(CreateApplicationRequest request) {
        return Mono.fromCallable(() -> validate(request))
                .flatMap(validated -> {
                    log.debug("Creating application productId={} simulationId={} requestedAmount={} term={}",
                            validated.productId(), validated.simulationId(),
                            validated.requestedAmount(), validated.term());

                    // Idempotency key derived from stable input fields. Same logical request
                    // (retry of the same input) -> same key -> domain dedupes without the
                    // channel minting a resource id.
                    String submitKey = IdempotencyKeys.of(
                            "exp-lending", "create-application", "submit",
                            String.valueOf(validated.productId()),
                            String.valueOf(validated.simulationId()),
                            validated.requestedAmount().toPlainString(),
                            String.valueOf(validated.term()),
                            String.valueOf(validated.purpose()));

                    return domainClient.submitApplication(validated, submitKey)
                            .onErrorMap(this::isNotBusinessException, this::toUpstreamError);
                });
    }
:::

Walk the reactive chain. `Mono.fromCallable(() -> validate(request))` runs the
defensive validation lazily, inside the reactive pipeline, so a validation failure
becomes an `onError` signal rather than a thrown exception escaping the publisher.
`validate` re-checks the request even though the controller's `@Valid` already ran —
the BFF does not trust that every caller path validated, so it guards the seam itself,
raising a `BusinessException(BAD_REQUEST, "VALIDATION_FAILED", ...)` on a bad field.

Then comes the key, the heart of the chapter, and we will dwell on it in the next
step. With the key in hand, `domainClient.submitApplication(validated, submitKey)`
crosses the SDK seam — the same kind of reactive port you met in Chapter 10, here
named `LoanOriginationDomainClient`, standing in for the generated domain SDK so the
sample runs with no domain service. Finally `.onErrorMap(this::isNotBusinessException,
this::toUpstreamError)` translates any *non-business* failure — a transport blip, a
deserialization error — into a `BusinessException(BAD_GATEWAY, "UPSTREAM_ERROR", ...)`,
while leaving `BusinessException`s the domain already produced untouched. That is the
error-mapping discipline: the channel never leaks a raw downstream stack trace; it
either passes through a meaningful business error or wraps the rest as a clean
`502`.

The read path is symmetrical, and shows the not-found mapping:

::: listing exp-lending/src/main/java/com/firefly/lumen/exp/service/ApplicationService.java | Listing 17.6 — the read path maps a missing application to a 404 problem detail
    public Mono<ApplicationDetailDTO> getApplication(UUID applicationId) {
        if (applicationId == null) {
            return Mono.error(new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "applicationId is required"));
        }
        log.debug("Getting application applicationId={}", applicationId);
        // Reads are safe to retry; the key is stable for a given id.
        String getKey = IdempotencyKeys.of("exp-lending", "get-application", applicationId.toString());
        return domainClient.getApplication(applicationId, getKey)
                .switchIfEmpty(Mono.error(new BusinessException(
                        HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND",
                        "loan application not found: " + applicationId)))
                .onErrorMap(this::isNotBusinessException, this::toUpstreamError);
    }
:::

The `switchIfEmpty` is the same idiom Chapter 6 used: when the domain returns an
empty `Mono`, substitute an error signal — here a `BusinessException(NOT_FOUND,
"APPLICATION_NOT_FOUND", ...)`. The web module's `GlobalExceptionHandler` turns that
into a `404` problem detail carrying the `APPLICATION_NOT_FOUND` code, which the slice
test asserts on the wire. The BFF throws a *semantic* business exception with a stable
code and a status; the framework renders the RFC 7807 body. You write the meaning;
the framework writes the JSON.

!!! note "Key term — BusinessException"
    `org.fireflyframework.web.error.exceptions.BusinessException` is the framework's
    carrier for a *meaningful* failure: it pairs an `HttpStatus` with a stable, machine-
    readable code (`APPLICATION_NOT_FOUND`, `VALIDATION_FAILED`, `UPSTREAM_ERROR`) and a
    human message. The web module's `GlobalExceptionHandler` renders it as an RFC 7807
    problem detail, so a channel client sees the same error shape from every Firefly
    service. Throwing one is how a service says "this failed, and here is the client-
    facing reason" without writing an exception handler.

## Step 4 — The deterministic idempotency key

Here is the experience tier's signature pattern, and the reason this chapter exists.
A channel submit can be retried — the user double-taps, the network drops the response
after the server processed it, a mobile client replays a queued request on reconnect.
If each retry created a fresh loan application, one tap would become three. The fix is
an *idempotency key*: a stable token attached to the request so the downstream tier
recognizes a retry and returns the original result instead of creating a duplicate.

The hard question is who mints the key. If the channel generates a random UUID per
attempt, a retry carries a *new* key and dedupes nothing. If the BFF mints a resource
id up front, it has taken on responsibility for identity that belongs to the system of
record. Firefly's pattern sidesteps both: the key is *derived* from the request's
stable business fields, so the same logical request always produces the same key —
without anyone minting an id. That derivation lives in one small helper.

::: listing exp-lending/src/main/java/com/firefly/lumen/exp/util/IdempotencyKeys.java | Listing 17.7 — the deterministic key: a v3 UUID over a ':'-joined input
public final class IdempotencyKeys {

    private IdempotencyKeys() {
        // utility
    }

    /**
     * Derives a deterministic UUID-shaped idempotency key from the given parts. Null parts are
     * coerced to the literal string {@code "null"} so the call never throws on missing inputs.
     *
     * @param parts identifying inputs, joined with {@code ":"}
     * @return a stable v3 UUID string derived from the parts
     */
    public static String of(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(parts[i] == null ? "null" : parts[i]);
        }
        return UUID.nameUUIDFromBytes(sb.toString().getBytes(StandardCharsets.UTF_8)).toString();
    }
}
:::

The whole helper is one method. `IdempotencyKeys.of(parts...)` joins its arguments
with `':'`, encodes the result as UTF-8, and runs `UUID.nameUUIDFromBytes(...)` —
the JDK's RFC 4122 *version 3* (name-based) UUID. A name-based UUID is a pure function
of its input: the same joined string always yields the same UUID, and a different
string yields a different one. The output is a valid UUID string, so it drops straight
into a standard `Idempotency-Key` HTTP header. Null parts are coerced to the literal
`"null"` so a missing optional field never throws.

Now re-read how the service called it in Listing 17.5. The parts are a *namespace*
(`"exp-lending"`, `"create-application"`, `"submit"`) plus the request's stable fields
— product, simulation, amount, term, purpose. Two submits of the *same* logical
application produce identical parts, hence an identical key, hence one application
downstream. Change any field — a different amount — and the key changes, because it is
a genuinely different request. The channel never mints a resource id; it computes a
deterministic fingerprint of the request, and the domain tier dedupes on it. The
service test proves exactly this: it recomputes the expected key independently and
asserts the service handed that same key to the SDK seam.

!!! note "Key term — deterministic idempotency key"
    A key derived as a pure function of a request's stable business inputs, so a retry
    of the *same* logical operation produces the *same* key. Firefly's `IdempotencyKeys.
    of(...)` builds one as a name-based (v3) UUID over the joined inputs. Because it is
    derived rather than minted, the channel can stay stateless — it never has to
    remember a key between attempts or allocate a resource id — and the downstream tier
    still dedupes reliably. Stable input, stable key; different input, different key.

!!! spring "Spring parity"
    This is the producer side of the same idempotency story Chapter 6 showed from the
    consumer side. There, `fireflyframework-web`'s `IdempotencyWebFilter` cached and
    replayed a response keyed by an `X-Idempotency-Key` header. Here the experience tier
    *computes* that key deterministically and forwards it across the SDK seam, so the
    dedupe happens downstream. Plain Spring gives you neither half; Firefly supplies the
    filter that honors the key and the helper that derives it, and they meet at the
    header.

!!! tip "Checkpoint"
    Trace one retry in your head. A client posts the same body twice. The controller
    validates both. The service computes `IdempotencyKeys.of(...)` over the same fields
    both times, so `submitKey` is identical on attempt one and attempt two. The domain
    tier sees the same key, recognizes the second call as a retry, and returns the
    original application — one row, not two — and the BFF never minted an id to make that
    work. That is the entire pattern.

## AppContext, AppSecurityContext, and back-office impersonation

Two application-layer types thread through everything `@Secure` does, and they are
worth naming even though the slice does not populate them. The starter resolves an
**`AppContext`** for each call — the request-scoped envelope carrying the caller's
identity, tenant, and correlation metadata as it flows on the reactive stack — and
within it an **`AppSecurityContext`** that holds the authenticated principal and the
permissions `@Secure` checks against. When enforcement is on, the `SecurityAspect`
reads the `AppSecurityContext`, compares the caller's permissions to the method's
declared `permissions`, and decides. In the slice you saw the aspect log *"No
ApplicationExecutionContext found in method arguments, skipping security check"* —
that is precisely the disabled path; with enforcement on, that context is present and
consulted.

This context model is what makes a *back-office* experience tier possible. A
back-office module — a console for support agents and underwriters — is just another
BFF built on `starter-application`, but its callers act *on behalf of* customers.
Firefly's pattern for that is **impersonation**: an authenticated operator, holding a
back-office permission, assumes a customer's `AppSecurityContext` for the duration of
a call, so downstream services see the request as the customer's while the audit trail
records the operator who acted. The mechanics — how an operator's token is exchanged,
how the impersonated context is stamped and audited — are illustrative here; Lumen's
reactor ships the customer-facing `exp-lending`, not a back-office module. Conceptually
an impersonating call is one that swaps the security context before composing:

```java
// Illustrative: a back-office handler impersonating a customer for one call.
@Secure(permissions = {"backoffice:application:read-as-customer"})
public Mono<ApplicationDetailDTO> getApplicationAsCustomer(UUID customerId, UUID applicationId) {
    return appContext.impersonate(customerId)                 // assume the customer's AppSecurityContext
            .then(applicationService.getApplication(applicationId)); // downstream sees the customer
}
```

The takeaway is structural, not code you can run today: every channel — a mobile app,
a web front-end, a back-office console — is its own experience tier on the same
starter, differing only in which `AppSecurityContext` its callers carry and which
`@Secure` permissions gate its endpoints. The composition pattern is identical; the
identity flowing through `AppContext` is what changes.

!!! note "Key term — AppContext / AppSecurityContext"
    `AppContext` is the application layer's request-scoped context — identity, tenant,
    correlation — carried on the reactive stack without a `ThreadLocal`, the way the
    domain tier's `ExecutionContext` (Chapter 10) carries saga state. `AppSecurityContext`
    is the security slice of it: the authenticated principal and the permissions
    `@Secure` evaluates. Back-office impersonation works by swapping the
    `AppSecurityContext` for a target customer's, so downstream calls run with the
    customer's identity while the audit log keeps the operator's.

## Run it

The slice is verified by a nine-test suite: four `WebTestClient` tests that boot the
full BFF context and drive it over HTTP, four fast unit tests on the service, and one
smoke test. From the `samples/lumen-lending` directory:

```text
mvn -q -pl exp-lending test
```

You should see all nine pass:

```text
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

The web tests (`ApplicationControllerTest`) boot the real application — controllers,
the `@Secure`-annotated handlers, the security filter chain, and
`fireflyframework-web`'s `GlobalExceptionHandler` — and satisfy the SDK seam with an
in-memory `StubLoanOriginationDomainClient` bean, so there is no domain service and
no Docker. They assert the `201` create with a mapped `ApplicationDetailDTO`, the
create-then-get round trip, the `404` problem detail carrying `APPLICATION_NOT_FOUND`
for an unknown id, and the `400` rejection of a zero amount at the validation edge.

The service tests (`ApplicationServiceTest`) run with no Spring context at all — they
construct `ApplicationService` directly over the stub. The one to read closely is the
idempotency assertion, because it pins the chapter's central claim:

```java
// From ApplicationServiceTest — the service derived the same key the test computed.
String expectedKey = IdempotencyKeys.of(
        "exp-lending", "create-application", "submit",
        productId.toString(), simulationId.toString(),
        "15000", "36", "PERSONAL");
// ...after createApplication(request) completes...
assertThat(stub.idempotencyKeys()).containsExactly(expectedKey);
```

The test recomputes the key from the same business fields and asserts the service
handed that exact key across the SDK seam. That is the deterministic-key contract,
verified: same logical request, same key, every time.

!!! tip "Checkpoint"
    Nine green tests, no domain service, no Docker. The four web tests prove the secured
    controller composes and renders problem details; the four service tests prove
    validation, the deterministic key, the not-found mapping, and the round trip. Recall
    the `@Secure` enforcement is disabled for the slice via
    `firefly.application.security.enabled=false` — so this run validates composition and
    wiring, and Chapter 19 will validate authorization on the enforcement-on path.

## What you built {.recap}

- An **experience tier** on `fireflyframework-starter-application` plus
  `fireflyframework-web` — a stateless BFF that owns no database and composes the
  domain over an SDK seam, mapping results to its own channel DTOs.
- A **secured reactive controller** whose `createApplication` and `getApplication`
  methods carry `@Secure(permissions = ...)` — declarative, per-endpoint authorization
  enforced by the starter's `SecurityAspect` — and that hold no error-handling code.
- A composing **`ApplicationService`** that validates the request at the edge, derives
  a deterministic idempotency key, crosses the seam to the domain, and maps downstream
  failures to `BusinessException`s the web module renders as RFC 7807 problem details.
- The **deterministic idempotency-key** pattern via `IdempotencyKeys.of(...)` — a
  name-based (v3) UUID over the request's stable fields, so a retried submit dedupes
  downstream **without the channel minting a resource id**.
- An honest account of **`@Secure`**: real and intercepted, but with enforcement
  disabled in the slice via `firefly.application.security.enabled=false`; plus the
  `AppContext`/`AppSecurityContext` model and a back-office impersonation use, shown
  illustratively.
- A passing nine-test suite — four web slice tests, four service unit tests, one smoke
  test — proving the BFF boots, validates, dedupes, and maps with no domain service and
  no Docker.

## Try it yourself {.exercises}

1. **Prove the key is deterministic.** In `ApplicationServiceTest`, add a test that
   builds two `CreateApplicationRequest` values with identical fields, calls
   `createApplication` on each, and asserts `stub.idempotencyKeys()` contains the *same*
   key twice. Then change one field (the amount) on the second request and assert the
   keys now differ.
2. **Add a list endpoint.** The module already has `ApplicationSummaryDTO`. Add a
   `@GetMapping` `list` method to `ApplicationController` returning a `Flux` of
   summaries, a `listApplications` method to the service, and a stub-backed test
   asserting a created application appears in the list. Keep the `@Secure` permission
   consistent with the read endpoint.
3. **Map an upstream failure.** Make `StubLoanOriginationDomainClient.submitApplication`
   return `Mono.error(new RuntimeException("boom"))` for one input, then add a test
   asserting the service surfaces a `BusinessException` with status `BAD_GATEWAY` and
   code `UPSTREAM_ERROR` — proving `onErrorMap` wraps non-business failures.
4. **Read the disabled-security log.** Run `ApplicationControllerTest` and find the
   `Intercepting @Secure method: createApplication` line, then the line that says the
   security check was skipped. Flip `firefly.application.security.enabled` to `true` in
   the test `application.yml`, re-run, and explain what now changes in the aspect's
   behavior (you do not need to make the test pass — observe the difference).
5. **Trace a retry end to end.** Starting from a double-submitted body, write down each
   field that feeds `IdempotencyKeys.of(...)` in `createApplication`, then explain in one
   sentence why a changed `purpose` produces a different key while leaving an optional
   field unset still produces a usable key (recall null coercion to `"null"`).

## Where to go next

You have a channel that composes one domain call cleanly. But a real submit is rarely
one call — registering an application, attaching the applicant, and proposing an offer
must succeed or fail *together*. Chapter 18 returns to the domain tier's **saga**:
the `@Saga` orchestration that runs those steps as one atomic flow and compensates in
reverse when a step fails. The deterministic idempotency key you built here is exactly
what keeps each saga step safe to retry without duplicating downstream work.
