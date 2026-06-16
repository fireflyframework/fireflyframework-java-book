By now you have a service that boots, a parent POM that keeps it version-coherent,
and a feel for the reactive types everything speaks. It is time to give Lumen
Lending an edge a client can call. In this chapter you build the first HTTP
surface of the loan-origination core: a reactive `@RestController` that creates,
fetches, and lists loan applications.

The controller itself is small — three methods, no error-handling code, no logging
plumbing, no envelope. That smallness is the point. The web module that Firefly
adds to Spring WebFlux does the cross-cutting work *around* your handler:
validation failures and missing resources come back as standard RFC 7807
problem-detail responses, retries are deduplicated, every response is stamped with
a transaction id, and personally identifiable data is masked in the logs — all
without a line of it appearing in the controller. You write the three business
methods; the framework writes the consistency.

We will build the controller and its DTOs, lean on finance-aware validators for
the request, raise a semantic exception for the not-found case, and then run the
slice test that proves the 400 and 404 problem details come back exactly as
promised.

## A reactive controller, and nothing else

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
validation — more on that next section. `getById` returns one application or
nothing; `list` returns a `Flux`, the zero-to-many publisher, and accepts an
optional `?status=` filter through `@RequestParam(required = false)`. Because the
parameter is typed as `ApplicationStatus`, WebFlux converts the query string to the
enum for you and rejects an unknown value before your code runs.

What is *not* here matters as much as what is. There is no `try`/`catch`, no
`ResponseEntity` envelope, no `@ExceptionHandler`, no logging. The controller
delegates to a `LoanApplicationService` and returns the publisher it gets back. The
framework subscribes at the edge when it writes the response, so you never call
`.subscribe()` yourself — you compose and return.

!!! spring "Spring parity"
    Every annotation here is plain Spring WebFlux: `@RestController`,
    `@RequestMapping`, `@PostMapping`, `@GetMapping`, `@PathVariable`,
    `@RequestParam`, `@RequestBody`, `@Valid`, `@ResponseStatus`. The
    `@Tag`/`@Operation` pair is springdoc OpenAPI. If you have written a WebFlux
    controller, you have written this one. What Firefly changes is not the
    controller — it is everything that happens to the request and response *around*
    it, which the rest of the chapter is about.

## The response DTO

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
just data.

## Validation with finance-aware constraints

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
that earn their keep are Firefly's:

- `@ValidAmount(min = 0.01)` checks that `requestedAmount` is a sane, positive
  monetary value — not zero, not negative, within a configurable bound. A bare
  `@Positive` would miss the precision and range rules that money demands.
- `@ValidCurrencyCode` checks that `currency` is a real ISO-4217 code, so `"EUR"`
  passes and `"XYZ"` does not.

These are the same constraint annotations the production firefly-oss services use,
surfaced here on a clean request record. You write the constraint; the framework
supplies the validator and the consistent error message.

!!! note "Key term — finance validators (`fireflyframework-validators`)"
    A small library of Jakarta-compatible constraint annotations for the values a
    banking platform handles repeatedly — amounts, currency codes, IBANs, BICs, tax
    IDs, card numbers. Each is an ordinary `ConstraintValidator`, so it composes
    with `@NotNull` and friends and participates in the same `@Valid` pass. Using
    them instead of copy-pasted regexes is how an entire fleet validates an IBAN the
    same way.

When `@Valid` on the controller's `@RequestBody` fails — say `requestedAmount` is
`-5.00` — Spring raises a `WebExchangeBindException` before your handler body ever
runs. You do not catch it. The web module's global handler turns it into a `400`
problem detail, which is the subject of the next two sections.

!!! spring "Spring parity"
    `@Valid` triggering validation on a `@RequestBody` is stock Spring. In vanilla
    WebFlux, a failed bind produces a `WebExchangeBindException` that — without a
    handler — yields Spring's default error JSON, whose shape varies by Boot version
    and is not RFC 7807. Firefly's only change is to catch that exception centrally
    and render it as a standard problem detail, identically in every service.

## Semantic exceptions become RFC 7807, for free

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

A problem-detail response looks like this on the wire:

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "Loan application not found: 7b1f...",
  "instance": "/api/v1/loan-applications/7b1f..."
}
```

The same machinery handles the validation failure from the previous section: a
`400` problem detail describing which field was rejected and why. One handler, one
shape, every error, every service.

!!! note "Key term — RFC 7807 problem detail"
    An IETF standard JSON format for HTTP error responses, served as
    `application/problem+json`, with stable fields — `type`, `title`, `status`,
    `detail`, `instance`. Because the shape is standard, a client writes error
    handling once and it works against every Firefly endpoint, instead of decoding a
    different bespoke blob per service.

!!! spring "Spring parity"
    Spring 6 ships a `ProblemDetail` type and `ResponseEntityExceptionHandler` that
    can produce RFC 7807 — but you still wire it up, map your domain exceptions to
    statuses, and repeat that mapping in every service. Firefly does the wiring once
    in the web module: throw `ResourceNotFoundException` and the `404` problem detail
    is automatic. You are using Spring's mechanism; you just never assemble it
    yourself.

## The web module's free cross-cutting filters

The `GlobalExceptionHandler` is one of several behaviors the web module and the
`core` starter bring to *every* request, with no code in your controller. They are
ordinary Spring `WebFilter` beans, auto-configured when the framework is on the
classpath and overridable like any bean. This service depends on
`fireflyframework-web` and `fireflyframework-starter-core`, so it gets all of them:

- **`GlobalExceptionHandler`** — translates framework exceptions and validation
  failures into RFC 7807 responses, as you just saw. This is what makes the 400 and
  404 in the test come back as problem details.
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

You did not opt into any of these per endpoint; they apply fleet-wide the moment
the dependency is present. To send the idempotency key from a client, you would add
one header:

```text
POST /api/v1/loan-applications HTTP/1.1
Content-Type: application/json
X-Idempotency-Key: 4f2c9e10-7b3a-4f6e-9c21-2a1d5b8e0c33

{ "applicantId": "...", "requestedAmount": 12500.00, "currency": "EUR",
  "termMonths": 36, "purpose": "HOME_IMPROVEMENT" }
```

Send that POST twice with the same key and you get the same `201` and the same
body both times — one application, not two.

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

## Run it

The slice test boots the full reactive context against in-memory H2 (R2DBC plus a
Flyway migration, no Docker) and drives the API with `WebTestClient`. It asserts
the create-and-read round trip, the RFC 7807 `404` for a missing id, and the `400`
rejection of a bad amount. Run just this test:

```text
mvn -q -pl core-lending-loan-origination -Dtest=LoanApplicationControllerTest test
```

You should see it pass:

```text
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
```

Three green tests confirm the chapter's three claims: a `POST` returns `201` and
the resource reads back, an unknown id returns a `404` problem detail, and a
negative amount is rejected with a `400` before any business logic runs.

!!! tip "Checkpoint"
    Run the command above and confirm `Tests run: 3, Failures: 0`. The
    `returnsRfc7807ProblemDetailWhenMissing` test asserts `$.status` equals `404` in
    the response body — proof that the problem detail is real JSON, not just a status
    code. The `rejectsAnInvalidPayload` test sends `requestedAmount = -5.00` and
    expects `400`, proof that `@ValidAmount` is doing its job.

## What you built {.recap}

- A reactive `@RestController` at `/api/v1/loan-applications` with three methods —
  `create` (POST, `201`), `getById` (GET one), and `list` (GET many with an
  optional `?status=` filter) — returning `Mono` and `Flux` of DTOs and containing
  no error-handling code.
- A `CreateLoanApplicationRequest` validated by Jakarta constraints plus Firefly's
  finance-aware `@ValidAmount` and `@ValidCurrencyCode`, and an immutable
  `LoanApplicationResponse` record as the wire contract.
- A not-found path that throws the framework's `ResourceNotFoundException` and gets
  an RFC 7807 `404` rendered automatically by the web module's
  `GlobalExceptionHandler` — alongside the free `IdempotencyWebFilter`,
  `TransactionFilter`, and PII masking that every request inherits.
- A passing slice test (`Tests run: 3, Failures: 0`) that verifies the `201` round
  trip, the `404` problem detail, and the `400` validation rejection.

## Try it yourself {.exercises}

1. **Add a constraint.** In `CreateLoanApplicationRequest.java`, cap the term with
   a Jakarta `@Max` on `termMonths` (for example, 84 months). Re-run the test, then
   add a fourth test case that posts a 120-month term and asserts a `400`.
2. **Filter the list.** The `list` method already accepts `?status=`. Add a test to
   `LoanApplicationControllerTest` that creates an application and then GETs
   `/api/v1/loan-applications?status=SUBMITTED`, asserting the new application is in
   the returned `Flux`.
3. **Reject a bad currency.** Add a test that posts a request with
   `currency = "XYZ"` and asserts a `400`, confirming `@ValidCurrencyCode` rejects a
   non-ISO-4217 code the same way `@ValidAmount` rejects a bad amount.
4. **Inspect the problem detail.** Extend `returnsRfc7807ProblemDetailWhenMissing`
   to also assert that `$.detail` contains the phrase `Loan application not found`,
   tying the response body back to the message thrown in
   `LoanApplicationService.getById`.
5. **Prove idempotency by hand.** Boot the service (Chapter 2) and `POST` the same
   valid body twice with an identical `X-Idempotency-Key` header. Confirm you get
   one application back, not two, then list applications to verify only one was
   created.

## Where to go next

You have a working HTTP surface, but the service behind it is still thin: `create`
maps a request to an entity and saves it, and `getById` reads one row back. The
next chapters fill in what those methods orchestrate — the domain model and the
reactive persistence layer that turns these three endpoints into a real system of
record.
