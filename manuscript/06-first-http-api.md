By now you have a service that boots, a parent POM that keeps it version-coherent,
and a feel for the reactive types everything speaks. It is time to give Lumen
Lending an edge a client can call. In this chapter you build the first HTTP
surface of the loan-origination core: a reactive `@RestController` that creates,
fetches, and lists loan applications, served on port `8081` against in-memory H2.

The controller itself is small — three methods, no error-handling code, no logging
plumbing, no envelope. That smallness is the point. The web module that Firefly
adds to Spring WebFlux does the cross-cutting work *around* your handler:
validation failures and missing resources come back as standard RFC 7807
problem-detail responses (enriched with trace context), retries are deduplicated,
every response is stamped with a transaction id, and personally identifiable data
is masked in the logs — all without a line of it appearing in the controller. You
write the three business methods; the framework writes the consistency.

We will build the controller and its DTOs, lean on finance-aware validators for
the request, raise a semantic exception for the not-found case, watch the
framework's filters log a request before your handler runs, and then run the slice
test that proves the `400` and `404` problem details come back exactly as promised.

## Step 1 — A reactive controller, and nothing else

Open the loan-origination core and look at its single web class. It is a textbook
WebFlux `@RestController` — `@RequestMapping` for the base path, constructor
injection (here via Lombok's `@RequiredArgsConstructor`), and methods that return
`Mono` and `Flux` instead of bare values.

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/web/LoanApplicationController.java | Listing 6.1 — the whole web surface: three reactive methods
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

Read it slowly, because every choice is deliberate.

The `create` method returns `Mono<LoanApplicationResponse>` and carries
`@ResponseStatus(HttpStatus.CREATED)`, so a successful POST answers `201 Created`
with the new resource as its body. The `@Valid` on the `@RequestBody` is what arms
validation — more on that in Step 3. `getById` returns one application or nothing;
`list` returns a `Flux`, the zero-to-many publisher, and accepts an optional
`?status=` filter through `@RequestParam(required = false)`. Because the parameter
is typed as `ApplicationStatus`, WebFlux converts the query string to the enum for
you and rejects an unknown value before your code runs.

What is *not* here matters as much as what is. There is no `try`/`catch`, no
`ResponseEntity` envelope, no `@ExceptionHandler`, no logging. The controller
delegates to a `LoanApplicationService` and returns the publisher it gets back. The
framework subscribes at the edge when it writes the response, so you never call
`.subscribe()` yourself — you compose and return.

Why can the method body be a one-liner that just forwards the `Mono`? Because in
WebFlux the *return value* is a promise, not a result. The controller hands back an
unstarted publisher; WebFlux's `DispatcherHandler` is the subscriber, and it only
pulls when it is ready to serialize the body to the response. If you had called
`service.create(request).block()` you would have collapsed that promise on an
event-loop thread — the cardinal sin the previous chapters warned about. Returning
the publisher keeps the whole path lazy and non-blocking end to end.

!!! spring "Spring parity"
    Every annotation here is plain Spring WebFlux: `@RestController`,
    `@RequestMapping`, `@PostMapping`, `@GetMapping`, `@PathVariable`,
    `@RequestParam`, `@RequestBody`, `@Valid`, `@ResponseStatus`. The
    `@Tag`/`@Operation` pair is springdoc OpenAPI. If you have written a WebFlux
    controller, you have written this one. What Firefly changes is not the
    controller — it is everything that happens to the request and response *around*
    it, which the rest of the chapter is about.

## Step 2 — The response DTO

The controller returns a `LoanApplicationResponse`, never the persistence entity.
Keeping a dedicated view type means the wire contract is decoupled from the table:
you can reshape storage without breaking clients, and you never accidentally leak
an internal field. It is a `record`, so it is immutable and serializes straight to
JSON with no getters to write.

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/dto/LoanApplicationResponse.java | Listing 6.2 — the response view, a plain immutable record
public record LoanApplicationResponse(
        UUID loanApplicationId,
        UUID applicationNumber,
        UUID applicantId,
        BigDecimal requestedAmount,
        String currency,
        Integer termMonths,
        String purpose,
        ApplicationStatus status,
        String decisionReason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
:::

There is nothing reactive about a DTO — it is the shape of one value. The `Mono`
and `Flux` in the controller are publishers *of* this record; the record itself is
just data. These are the exact field names a client sees on the wire:
`loanApplicationId`, `applicationNumber`, `applicantId`, `requestedAmount`,
`currency`, `termMonths`, `purpose`, `status`, `decisionReason`, `createdAt`,
`updatedAt`. Keep them in mind — you will match them against the live JSON in a
moment, and they are what the controller test asserts on.

Note `decisionReason` in particular: it is `null` on a freshly created application
and only fills in once a decision is made (the rule engine of Chapter 13 is what
would populate it in the full service). The view type already carries the field so
the contract does not change when that capability lands.

## Step 3 — Validation with finance-aware constraints

A loan application carries money, a currency, and a term, and the request must be
rejected — cleanly, before any business logic runs — if any of those is nonsense.
That is the job of the create request DTO. It mixes ordinary Jakarta Bean
Validation constraints with two finance-specific constraints that Firefly ships in
`fireflyframework-validators`.

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/dto/CreateLoanApplicationRequest.java | Listing 6.3 — the create request, validated by Jakarta plus finance constraints
public record CreateLoanApplicationRequest(

        @NotNull(message = "Applicant ID is required")
        UUID applicantId,

        @NotNull(message = "Requested amount is required")
        @ValidAmount(min = 0.01, message = "Requested amount must be a positive monetary value")
        BigDecimal requestedAmount,

        @NotBlank(message = "Currency is required")
        @ValidCurrencyCode(message = "Currency must be a valid ISO-4217 code")
        String currency,

        @NotNull(message = "Term is required")
        @Positive(message = "Term must be a positive number of months")
        Integer termMonths,

        @NotBlank(message = "Loan purpose is required")
        String purpose
) {
}
:::

`@NotNull`, `@NotBlank`, and `@Positive` are standard Jakarta constraints. The two
that earn their keep are Firefly's, imported from `org.fireflyframework.annotations`:

- `@ValidAmount(min = 0.01)` checks that `requestedAmount` is a sane, positive
  monetary value — not zero, not negative, within a configurable bound. A bare
  `@Positive` would miss the precision and range rules that money demands, and
  `min = 0.01` makes "no zero-euro loans" an explicit, declarative rule.
- `@ValidCurrencyCode` checks that `currency` is a real ISO-4217 code, so `"EUR"`
  passes and `"XYZ"` does not.

These are the same constraint annotations the production firefly-oss services use,
surfaced here on a clean request record. You write the constraint; the framework
supplies the validator and the consistent error message.

A natural question: do these constraints compose with the plain Jakarta ones, or do
they fight? They compose. Each Firefly constraint is an ordinary
`ConstraintValidator`, so `@NotNull` and `@ValidAmount` on the same field both run
in the *same* `@Valid` pass, and every violation they find is collected into one
report. That is why a single bad request can come back with several field errors at
once rather than failing on the first one — and why the error body has an `errors`
array rather than a single message.

!!! note "Key term — finance validators (`fireflyframework-validators`)"
    A small library of Jakarta-compatible constraint annotations for the values a
    banking platform handles repeatedly — amounts, currency codes, IBANs, BICs, tax
    IDs, card numbers. Each is an ordinary `ConstraintValidator`, so it composes
    with `@NotNull` and friends and participates in the same `@Valid` pass. Using
    them instead of copy-pasted regexes is how an entire fleet validates an IBAN the
    same way.

When `@Valid` on the controller's `@RequestBody` fails — say `requestedAmount` is
`-5.00` — Spring raises a `WebExchangeBindException` *before* your handler body ever
runs. You do not catch it. The web module's global handler turns it into a `400`
problem detail, which is the subject of Step 5. This ordering is important: a
rejected payload never touches the service, never opens a transaction, and never
hits the database. Validation is a gate, not a cleanup.

!!! spring "Spring parity"
    `@Valid` triggering validation on a `@RequestBody` is stock Spring. In vanilla
    WebFlux, a failed bind produces a `WebExchangeBindException` that — without a
    handler — yields Spring's default error JSON, whose shape varies by Boot version
    and is not RFC 7807. Firefly's only change is to catch that exception centrally
    and render it as a standard problem detail, identically in every service.

## Step 4 — Semantic exceptions become RFC 7807, for free

Now the not-found case. When you GET an application id that does not exist, the
correct answer is `404 Not Found` with a machine-readable body. In a hand-rolled
service that means an `@ExceptionHandler` and a custom JSON shape — invented again
in every service, agreeing with none.

Firefly removes the decision. The service throws a *semantic* exception from the
framework's error kernel, and the web module renders it. Here is how the service
handles a miss:

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/service/LoanApplicationService.java | Listing 6.4 — throwing a semantic exception on a miss; no handler required
    @Transactional(readOnly = true)
    public Mono<LoanApplicationResponse> getById(UUID id) {
        return repository.findById(id)
                .map(mapper::toResponse)
                .switchIfEmpty(Mono.error(
                        new ResourceNotFoundException("Loan application not found: " + id)));
    }
:::

The reactive shape is worth a beat. `repository.findById(id)` returns a `Mono` that
is *empty* when the row is absent — not an error, just nothing. `switchIfEmpty`
substitutes an error signal in that case, raising
`org.fireflyframework.web.error.exceptions.ResourceNotFoundException`. That
exception travels down the reactive chain to the edge, where the web module's
`GlobalExceptionHandler` catches it and emits a `404` with
`Content-Type: application/problem+json` and an RFC 7807 body. The controller does
not know any of this happened; it simply returned the `Mono` the service gave it.

Why `switchIfEmpty` rather than an `if (result == null) throw`? Because there is no
result yet — `findById` has not run. You are describing what to do *when* the empty
signal arrives, in the same declarative style as the rest of the pipeline. The
alternative, subscribing eagerly to check for null, would block the very thread the
chapter keeps reminding you not to block.

Here is the live `404` body, captured from the running core service when you GET an
id that is not there. Notice it is *not* a bare `about:blank` stub: alongside the
RFC's five standard members (`type`, `title`, `status`, `detail`, `instance`)
Firefly tucks an `extensions` object carrying the trace context, a severity, a
retry hint, the path, a remediation `suggestion`, and an error `category`:

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "Loan application not found: 00000000-0000-0000-0000-000000000000",
  "instance": "/api/v1/loan-applications/00000000-0000-0000-0000-000000000000?traceId=1efc25dec634992124f6a1520970dfef",
  "extensions": {
    "traceId": "1efc25dec634992124f6a1520970dfef",
    "spanId": "6709a187f331beb0",
    "severity": "LOW",
    "retryable": false,
    "path": "/api/v1/loan-applications/00000000-0000-0000-0000-000000000000",
    "suggestion": "Verify the resource identifier and ensure it exists.",
    "category": "RESOURCE"
  }
}
```

The `detail` is exactly the string you passed to `ResourceNotFoundException` —
`"Loan application not found: " + id` — so the message you write in the service is
the message a client reads on the wire. The `retryable: false` and
`category: "RESOURCE"` are the framework classifying the error for you: a missing
resource is not something a client should retry, and a generic client can branch on
`category` without parsing the human-readable `detail`.

!!! note "Key term — RFC 7807 problem detail"
    An IETF standard JSON format for HTTP error responses, served as
    `application/problem+json`, with stable top-level members — `type`, `title`,
    `status`, `detail`, `instance`. Because the shape is standard, a client writes
    error handling once and it works against every Firefly endpoint, instead of
    decoding a different bespoke blob per service. Firefly's `GlobalExceptionHandler`
    additionally fills the standard `extensions` object with trace IDs, a `severity`,
    a `retryable` hint, a `suggestion`, and a `category`.

!!! spring "Spring parity"
    Spring 6 ships a `ProblemDetail` type and `ResponseEntityExceptionHandler` that
    can produce RFC 7807 — but you still wire it up, map your domain exceptions to
    statuses, and repeat that mapping in every service. Firefly does the wiring once
    in the web module: throw `ResourceNotFoundException` and the `404` problem detail
    is automatic, trace context and all. You are using Spring's mechanism; you just
    never assemble it yourself.

### The validation 400, the same shape

The same machinery handles the validation failure from Step 3. Post a negative
`requestedAmount`, which violates `@ValidAmount`, and the framework rejects it
before the service runs — with the same problem-detail shape, but now a *typed*
`type` URI and an `errors` array under `extensions` pinpointing the offending field:

```json
{
  "type": "https://api.firefly.com/errors/validation_error",
  "title": "Validation Failed",
  "status": 400,
  "detail": "Invalid request parameters",
  "instance": "/api/v1/loan-applications?traceId=62f4d701-8adc-49c7-bfea-5e325569e5d6",
  "extensions": {
    "code": "VALIDATION_ERROR",
    "suggestion": "Please check the validation errors and correct your request.",
    "errors": [
      {
        "field": "requestedAmount",
        "code": "ValidAmount",
        "message": "Requested amount must be a positive monetary value",
        "metadata": { "bindingFailure": false, "rejectedValue": "-5.00" }
      }
    ]
  }
}
```

Read the `errors[]` entry top to bottom and you can see the whole validation story
in one object: `field` is `requestedAmount`, `code` is `ValidAmount` (the simple
name of the constraint that fired, not a generic "invalid"), `message` is the exact
text you wrote in the annotation, and `metadata.rejectedValue` echoes the bad input
`"-5.00"` back. A client form can highlight the right field and show the right
message without guessing. And `type` is now a stable, dereferenceable URI —
`https://api.firefly.com/errors/validation_error` — so a `400` from validation is
distinguishable from any other `400` by its `type` alone, where the `404` above used
the generic `about:blank`. One handler, two error kinds, one shape, every service.

!!! note "Key term — the `GlobalExceptionHandler`"
    Firefly's `GlobalExceptionHandler` is a single web-module component that catches
    every unhandled error at the reactive edge and renders RFC 7807. It maps
    framework exceptions to statuses (`ResourceNotFoundException` to `404`), turns
    Spring's `WebExchangeBindException` and the validators' failures into the typed
    `validation_error` `400` above, and enriches both with trace context and a
    `suggestion`. You never register it — it arrives with `fireflyframework-web`.

## Step 5 — Watch the cross-cutting filters run

The `GlobalExceptionHandler` is one of several behaviors the web module and the
`core` starter bring to *every* request, with no code in your controller. They are
ordinary Spring `WebFilter` beans, auto-configured when the framework is on the
classpath and overridable like any bean. This service depends on
`fireflyframework-web` and `fireflyframework-starter-core`, so it gets all of them:

- **`GlobalExceptionHandler`** — translates framework exceptions and validation
  failures into RFC 7807 responses, as you just saw. This is what makes the `400`
  and `404` come back as problem details.
- **`IdempotencyWebFilter`** — when a write request carries an `X-Idempotency-Key`
  header, the filter caches the first response under that key and replays it on a
  retry, so a client that resends a `POST` after a timeout does not create a second
  loan application. Safe retries without dedupe logic in your handler.
- **`TransactionFilter`** — stamps every response with an `X-Transaction-Id`,
  generating one if the caller did not supply it, so a single request is traceable
  end to end across services and logs.
- **PII masking in logs** — the framework's logging configuration redacts
  personally identifiable data — emails, national IDs, card numbers — so a stray log
  line cannot leak a customer's details.

These are not theoretical. Boot the core service (next step) and POST a loan
application, and *before* your `create` method runs, the request threads through
those filters and they log it. Here are the real `DEBUG` lines on the request
thread — note the `X-Transaction-Id` being generated and the idempotency filter
inspecting the call, each line decorated with the `traceId`/`spanId` that now ride
every log on this thread:

```text
{"timestamp":"2026-06-17T08:21:44.132+0000","message":"Generated new transaction ID: ce0c2ede-0e81-430f-9c99-7464a1613884","logger":"o.f.core.config.TransactionFilter","level":"DEBUG","traceId":"bfa32cdc5313c5951ec124b491f07687","spanId":"78466db40897c823"}
{"timestamp":"2026-06-17T08:21:44.134+0000","message":"IdempotencyWebFilter.filter: Processing request POST /api/v1/loan-applications","logger":"o.f.w.i.filter.IdempotencyWebFilter","level":"DEBUG","traceId":"bfa32cdc5313c5951ec124b491f07687","spanId":"78466db40897c823"}
```

You did not opt into any of this per endpoint; the filters apply fleet-wide the
moment the dependency is present, and they self-describe in the log so you can see
them work. To send the idempotency key from a client, you add one header:

```text
POST /api/v1/loan-applications HTTP/1.1
Host: localhost:8081
Content-Type: application/json
X-Idempotency-Key: 4f2c9e10-7b3a-4f6e-9c21-2a1d5b8e0c33

{ "applicantId": "8b1d0d3c-1f2a-4f7e-9a3b-7d2c4e5f6a7b",
  "requestedAmount": "12500.00", "currency": "EUR",
  "termMonths": 36, "purpose": "HOME_IMPROVEMENT" }
```

Send that POST twice with the same key and you get the same `201` and the same
body both times — one application, not two. The second request never reaches your
`create` method; `IdempotencyWebFilter` replays the cached response.

!!! warning "These filters are real, but you still must not block"
    The cross-cutting filters run on the same event-loop threads as your handler.
    They are non-blocking by design; keep your handler non-blocking too. A blocking
    call inside `create` — a JDBC query, `.block()`, `Thread.sleep` — stalls the
    loop for every request the filter chain is serving, not just yours. The data
    layer is R2DBC precisely so this stays true end to end.

!!! spring "Spring parity"
    Each of these is a plain `WebFilter` — the WebFlux equivalent of a servlet
    `Filter`. You could write all four by hand and register them in every service.
    Firefly's contribution is that they are written once, tuned by `firefly.*`
    properties, and active by default, so the tenth service behaves exactly like the
    first without anyone re-deriving idempotency or transaction propagation.

## Step 6 — Run the API by hand

You have read the whole web surface; now drive it. The core service is an
independent Spring Boot app — boot it the ordinary way with the Maven plugin, or as
the repackaged executable jar (Spring Boot repackage is wired in the build). From
the `core-lending-loan-origination` module directory:

```text
$ mvn spring-boot:run
# or, after `mvn -pl core-lending-loan-origination package`:
$ java -jar target/core-lending-loan-origination-0.1.0-SNAPSHOT.jar
```

It boots on **port 8081** against in-memory H2 (R2DBC at runtime plus a Flyway
migration — no Docker, no external database), prints the `:: firefly-core ::`
banner you met in Chapter 2, and starts listening. The run commands for all three
tiers live in `samples/lumen-lending/README.md`.

POST a request body with the applicant, the amount, the currency, the term in
months, and the purpose:

```text
$ curl -s -X POST http://localhost:8081/api/v1/loan-applications \
    -H 'Content-Type: application/json' \
    -d '{
          "applicantId": "8b1d0d3c-1f2a-4f7e-9a3b-7d2c4e5f6a7b",
          "requestedAmount": "12500.00",
          "currency": "EUR",
          "termMonths": 36,
          "purpose": "HOME_IMPROVEMENT"
        }'
```

The service validates the payload, persists the application, and submits it in one
step, answering `201 Created` with the stored resource. Note the generated
`loanApplicationId` and `applicationNumber`, the lifecycle timestamps, and — the
headline — the `status`, which is `SUBMITTED`, not `DRAFT`, because the service
submits the application as part of creation:

```json
{
  "loanApplicationId": "6fb206b6-288f-4559-a403-460662a32329",
  "applicationNumber": "f2419b18-ae0c-4c32-9839-6c07ae424eda",
  "applicantId": "8b1d0d3c-1f2a-4f7e-9a3b-7d2c4e5f6a7b",
  "requestedAmount": 12500.00,
  "currency": "EUR",
  "termMonths": 36,
  "purpose": "HOME_IMPROVEMENT",
  "status": "SUBMITTED",
  "decisionReason": null,
  "createdAt": "2026-06-17T08:21:44.215",
  "updatedAt": "2026-06-17T08:21:44.215"
}
```

Now read it back by its id with a `GET`, using the `loanApplicationId` the create
call returned:

```text
$ curl -s http://localhost:8081/api/v1/loan-applications/6fb206b6-288f-4559-a403-460662a32329
```

```json
{
  "loanApplicationId": "6fb206b6-288f-4559-a403-460662a32329",
  "applicationNumber": "f2419b18-ae0c-4c32-9839-6c07ae424eda",
  "applicantId": "8b1d0d3c-1f2a-4f7e-9a3b-7d2c4e5f6a7b",
  "requestedAmount": 12500.00,
  "currency": "EUR",
  "termMonths": 36,
  "purpose": "HOME_IMPROVEMENT",
  "status": "SUBMITTED",
  "decisionReason": null,
  "createdAt": "2026-06-17T08:21:44.215",
  "updatedAt": "2026-06-17T08:21:44.215"
}
```

That round trip — POST creates and submits, GET reads back — is the spine of the
core service, and it is exactly the same code path the slice test drives headless.
Then exercise the two unhappy paths and confirm the bodies match Step 4: a GET on a
missing id returns the `404` problem detail, and a POST with `requestedAmount: -5.00`
returns the typed `400`.

!!! note "Where this fits the live stack"
    This chapter exercises the **core** tier in isolation on `8081`. In the full
    reactor the core is the system of record at the bottom of the
    **exp → domain → core** chain: a single channel `POST` to the experience BFF on
    `8080` flows through the domain orchestrator on `8082`, whose
    `RegisterApplicationSaga` writes to *this very endpoint* over HTTP and gets back
    the core-assigned id. The three endpoints you built here are what that saga
    ultimately calls. (The domain→core write seam is intentionally minimal — some
    channel fields land as core defaults — and is the subject of later chapters.)

## Step 7 — Prove it

You do not need a running server to prove all of this. The reactor ships a slice
test that boots the full reactive context against in-memory H2 (R2DBC runtime plus a
Flyway migration, no Docker) and drives the real API with `WebTestClient`. It
asserts the create-and-read round trip, the RFC 7807 `404` for a missing id, and the
`400` rejection of a bad amount — the same code paths you just exercised by hand,
run headless. From `samples/lumen-lending`, run just this test:

```text
$ mvn -q -pl core-lending-loan-origination -Dtest=LoanApplicationControllerTest test
```

You should see its three cases pass:

```text
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in com.firefly.lumen.core.web.LoanApplicationControllerTest
[INFO] BUILD SUCCESS
```

Three green tests confirm the chapter's three claims, and they map one-to-one onto
the three things you just did with `curl`. `createsAnApplicationAndReadsItBack`
posts the valid body, asserts `201` and `status == "SUBMITTED"` and
`requestedAmount == 12500.00`, then GETs by the returned `loanApplicationId` and
asserts `200` with the `currency` and `purpose` read back.
`returnsRfc7807ProblemDetailWhenMissing` GETs a random id and asserts `404` with the
JSON path `$.status` equal to `404` — proof that the problem detail is real JSON,
not just a status line. `rejectsAnInvalidPayload` posts `requestedAmount = -5.00`
and asserts `400`, proof that `@ValidAmount` fires before any business logic runs.

Run the whole module and all eighteen of its tests pass — the three web-layer cases
plus the domain-model and reactive-helper tests you will meet in later chapters:

```text
$ mvn -q -pl core-lending-loan-origination test
```

```text
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in com.firefly.lumen.core.web.LoanApplicationControllerTest
[INFO] Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

!!! tip "Checkpoint"
    Run `mvn -q -pl core-lending-loan-origination test` from `samples/lumen-lending`
    and confirm `Tests run: 18, Failures: 0` and a final `BUILD SUCCESS`. If you want
    only the web slice, scope it with
    `-Dtest=LoanApplicationControllerTest` and look for `Tests run: 3, Failures: 0`.
    Those green lines are the contract every listing in this chapter is checked
    against — the core module is **18** tests, and the whole three-tier reactor is
    **33** (core 18, domain 6, exp 9).

## What you built {.recap}

- A reactive `@RestController` at `/api/v1/loan-applications` (served on `8081`)
  with three methods — `create` (POST, `201`), `getById` (GET one), and `list`
  (GET many with an optional `?status=` filter) — returning `Mono` and `Flux` of
  DTOs and containing no error-handling code.
- A `CreateLoanApplicationRequest` validated by Jakarta constraints plus Firefly's
  finance-aware `@ValidAmount` and `@ValidCurrencyCode`, and an immutable
  `LoanApplicationResponse` record (fields `loanApplicationId`, `applicantId`,
  `requestedAmount`, `currency`, `termMonths`, `status`, `decisionReason`, …) as the
  wire contract.
- A not-found path that throws the framework's `ResourceNotFoundException` and gets
  an RFC 7807 `404` rendered automatically by the web module's
  `GlobalExceptionHandler` — with an `extensions` object carrying `traceId`,
  `severity`, `retryable: false`, `suggestion`, and `category: "RESOURCE"` — and a
  validation `400` with the typed `type`
  `https://api.firefly.com/errors/validation_error` and an `errors[]` array.
- The free cross-cutting `WebFilter`s every request inherits — `TransactionFilter`
  stamping an `X-Transaction-Id`, `IdempotencyWebFilter` deduplicating retries, and
  PII masking — observed in the real `DEBUG` log lines on the request thread.
- A passing slice test (`Tests run: 3, Failures: 0`) and a green module
  (`Tests run: 18, Failures: 0`, `BUILD SUCCESS`) that verify the `201` round trip,
  the `404` problem detail, and the `400` validation rejection.

## Try it yourself {.exercises}

1. **Add a constraint.** In `CreateLoanApplicationRequest.java`, cap the term with
   a Jakarta `@Max` on `termMonths` (for example, 84 months). Re-run the test, then
   add a fourth test case to `LoanApplicationControllerTest` that posts a 120-month
   term and asserts `400`. Inspect the `errors[]` entry — what is the `code` for a
   `@Max` violation, and how does it differ from the `ValidAmount` code?
2. **Filter the list.** The `list` method already accepts `?status=`. Add a test
   that creates an application and then GETs
   `/api/v1/loan-applications?status=SUBMITTED`, asserting the new application is in
   the returned list. Then GET `?status=DRAFT` and assert it is *not* — proof the
   enum filter works.
3. **Reject a bad currency.** Add a test that posts a request with
   `currency = "XYZ"` and asserts `400`, confirming `@ValidCurrencyCode` rejects a
   non-ISO-4217 code. Check that the `errors[].field` is `currency` and the `code` is
   `ValidCurrencyCode`, mirroring the `ValidAmount` case.
4. **Inspect the problem detail.** Extend `returnsRfc7807ProblemDetailWhenMissing`
   to also assert that `$.detail` contains the phrase `Loan application not found`
   and that `$.extensions.category` equals `RESOURCE`, tying the response body back
   to the message thrown in `LoanApplicationService.getById` and to the framework's
   classification.
5. **Prove idempotency by hand.** Boot the service (`mvn spring-boot:run` on `8081`)
   and `POST` the same valid body twice with an identical `X-Idempotency-Key` header.
   Confirm you get one application back, not two, then `GET /api/v1/loan-applications`
   to verify only one was created. Watch the `IdempotencyWebFilter` `DEBUG` line on
   the second request.

## Where to go next

You have a working HTTP surface, but the service behind it is still thin: `create`
maps a request to an entity and saves it, and `getById` reads one row back. The
next chapters fill in what those methods orchestrate — the domain model and the
reactive persistence layer (Chapter 15's data tier) that turn these three endpoints
into a real system of record, and the saga and rule engine (Chapters 12 and 13) that
will one day populate that `decisionReason` field.
