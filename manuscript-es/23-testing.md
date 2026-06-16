Every chapter so far has ended the same way: a `mvn` command and a line of green
output. That was not a flourish. It is the central claim of this book made
operational — that a Firefly service, for all its reactive plumbing and saga
orchestration and event routing, can be *tested like ordinary code*, fast and
without a single container. This chapter steps back from building features and
looks squarely at the tests themselves: what kinds Lumen Lending ships, how they
form a pyramid, and why the whole suite runs in seconds on a laptop with no Docker
daemon in sight.

You already have the vocabulary. You met `StepVerifier` in Chapter 5, the
`WebTestClient` slice in Chapter 6, and the broker-free EDA test in Chapter 11.
Here you assemble them into a deliberate strategy. The reactor's two
loan-origination modules carry twenty-four tests across four levels — plain unit,
reactive `StepVerifier`, full-context web slice, and a saga compensation
integration test — and we will read one representative test at each level, in order,
from the cheapest to the most thorough. By the end you will know exactly which kind
of test to reach for, and why the most demanding one in the suite still needs no
network.

The tests live in two directories. The core module's tests are under
`core-lending-loan-origination/src/test/java/com/firefly/lumen/core/` (with `domain/`
and `web/` subpackages); the domain module's are under
`domain-lending-loan-origination/src/test/java/com/firefly/lumen/domain/` (with a
`saga/` subpackage). We will draw one slice from each level and finish by running
both modules green together.

## The test pyramid, Firefly edition

The test pyramid is an old idea: have many fast, narrow tests at the base, fewer
medium-scope tests in the middle, and a small number of broad, slow tests at the
top. The shape matters because the cheap tests at the base catch most regressions in
milliseconds, while the expensive ones at the top — the ones that boot a context or
talk to a broker — are reserved for the wiring that unit tests cannot reach.

Lumen Lending's suite maps cleanly onto four tiers:

- **Plain unit tests** — a pure object under test, no Spring, no reactive types, no
  I/O. `MoneyTest` and `LoanApplicationTest` live here. Microseconds each.
- **Reactive unit tests** — still no Spring, but the unit under test returns a `Mono`
  or `Flux`, so you assert signals with `StepVerifier`. `ReactiveModelTest` is the
  pure tour; the saga tests use the same tool with real beans.
- **Web slice tests** — `@SpringBootTest` boots the full reactive context against
  in-memory H2 and drives the HTTP surface with `WebTestClient`. One per service edge.
  `LoanApplicationControllerTest` is the example.
- **Orchestration / integration tests** — `@SpringBootTest` again, but exercising a
  whole saga or EDA flow end to end through the framework runtime. The
  `RegisterApplicationSagaCompensationTest` is the headline here.

!!! note "Key term — test pyramid"
    A **test pyramid** describes the healthy *proportion* of tests by scope: a wide
    base of fast, isolated unit tests, a narrower band of integration tests, and a
    thin cap of end-to-end tests. Inverting it — leaning on slow, broad tests to catch
    bugs a unit test should have caught — gives you a suite that is slow to run and
    slow to diagnose. Firefly's design keeps the base cheap on purpose: the domain
    logic is plain Java, so most of your tests never start a context.

The thing that makes this pyramid unusual is the **top**. In most enterprise stacks
the upper tiers demand infrastructure — a Postgres container, a Kafka broker, a
docker-compose file you babysit. Firefly's upper tiers do not. The web slice runs
against H2 speaking R2DBC; the saga and EDA tests run the orchestration and event
runtimes in-process. The entire suite is `mvn test` and nothing else. We will see
exactly how at each level, and then name where real containers *do* belong.

## Level 1 — Plain unit tests

The base of the pyramid is the domain model tested as plain Java. No annotations
that start a context, no publishers, no mocks of framework types — just construct an
object, call a method, assert the result. These tests cost nothing to run and pin
down the rules that matter most: the ones inside your aggregates and value objects.

`MoneyTest` is the smallest example in the reactor. `Money` is the core's
minor-units value object, and the test states three invariants as three one-line
facts:

::: listing core-lending-loan-origination/src/test/java/com/firefly/lumen/core/MoneyTest.java | Listing 23.1 — a plain unit test: no Spring, no reactive types
class MoneyTest {

    @Test
    void ofRejectsNegativeAmounts() {
        assertThrows(IllegalArgumentException.class, () -> Money.of(-1));
    }

    @Test
    void minusReturnsTheDifference() {
        assertEquals(Money.of(50), Money.of(150).minus(Money.of(100)));
    }

    @Test
    void minusThrowsWhenResultWouldBeNegative() {
        assertThrows(IllegalArgumentException.class,
                () -> Money.of(100).minus(Money.of(150)));
    }
}
:::

This is JUnit 5 and nothing else. `assertThrows` pins the guard clauses — `Money`
refuses to exist as a negative amount, and a subtraction that would go below zero
fails loudly rather than silently producing a bad balance. `assertEquals` checks the
happy arithmetic. There is no `@SpringBootTest`, no `@Autowired`, no `Mono`. A test
like this runs in microseconds and never flakes, because there is nothing
asynchronous or external to flake.

The same level reaches up into the aggregate. `LoanApplicationTest` constructs a
`LoanApplication` with its builder and walks it through its legal status
transitions — entirely in memory, with no persistence:

::: listing core-lending-loan-origination/src/test/java/com/firefly/lumen/core/domain/LoanApplicationTest.java | Listing 23.2 — unit-testing an aggregate's state machine in memory
    @Test
    void happyPathReachesApproved() {
        LoanApplication app = draft();
        app.submit();
        assertEquals(ApplicationStatus.SUBMITTED, app.getStatus());
        app.startReview();
        assertEquals(ApplicationStatus.UNDER_REVIEW, app.getStatus());
        app.approve();
        assertEquals(ApplicationStatus.APPROVED, app.getStatus());
        assertTrue(app.getStatus().isTerminal());
    }
:::

The aggregate enforces its own rules — `cannotApproveADraft` asserts that calling
`approve()` on a `DRAFT` throws `IllegalStateException` — and you verify every one of
them without a database round trip, because the rules live in the object, not in the
table. This is the dividend of keeping domain logic in plain Java: the most
important behavior in the system is also the cheapest to test.

!!! tip "Checkpoint"
    Run just the two unit classes from `samples/lumen-lending`:

    ```text
    mvn -q -pl core-lending-loan-origination -Dtest=MoneyTest,LoanApplicationTest test
    ```

    You should see `Tests run: 9, Failures: 0` — three from `MoneyTest`, six from
    `LoanApplicationTest`. Note the time elapsed in the report: single-digit
    milliseconds. That speed is why the base of the pyramid should be wide.

## Level 2 — Reactive unit tests with StepVerifier

One level up, the unit under test returns a publisher. You cannot `assertEquals` a
`Mono` — it is a recipe, not a value — so you subscribe and assert the signal
sequence with `StepVerifier`, exactly as Chapter 5 taught. Crucially this is still a
*unit* test: no context boots, no port opens. `ReactiveModelTest` is the purest
form, asserting against hand-built publishers:

::: listing core-lending-loan-origination/src/test/java/com/firefly/lumen/core/ReactiveModelTest.java | Listing 23.3 — asserting a publisher's signals without Spring or a network
    @Test
    void operatorsTransformTheStream() {
        Flux<Integer> evensDoubled = Flux.range(1, 6)
                .filter(n -> n % 2 == 0)
                .map(n -> n * 10);

        StepVerifier.create(evensDoubled)
                .expectNext(20, 40, 60)
                .verifyComplete();
    }
:::

`StepVerifier.create(...)` subscribes; `.expectNext(...)` asserts each `onNext` in
order; `.verifyComplete()` asserts the terminal `onComplete` *and runs the
verification*. The whole thing executes synchronously on the test thread in
microseconds. This is the canonical way to test any reactive method you write — a
service method, a mapper, a custom operator — because it pulls values through the
pipeline without `.block()` and asserts precisely what the stream emitted.

The same tool scales up to test reactive code that *does* involve real framework
beans, while still asserting through `StepVerifier`. You saw it in Chapter 11: the
EDA listener test drives the `EventListenerProcessor` and wraps the dispatch in a
`StepVerifier`. The lesson is that `StepVerifier` is not tied to the unit level — it
is how you assert *any* publisher, whether the publisher came from `Flux.range` or
from a saga engine. We will see it again at the very top of the pyramid.

!!! spring "Spring parity"
    `StepVerifier` ships in `reactor-test`, a pure Project Reactor artifact with no
    Firefly in it — a plain Spring WebFlux app tests reactive code the identical way.
    Firefly adds nothing to the tool; it simply gives you more reactive code worth
    testing with it, and keeps `reactor-test` on the test classpath via the core
    starter so you never wire the dependency yourself.

## Level 3 — The web slice with @SpringBootTest and WebTestClient

Now the pyramid narrows. To test the HTTP edge you need the real context: the
controller, the service, the validators, the R2DBC repository, the global exception
handler, and the web filters all wired together as they are at runtime. That is what
`@SpringBootTest` gives you — and what makes Lumen's web slice the first test in this
chapter that boots Spring.

The cost is a context startup of a couple of seconds. The payoff is that you verify
the *integration* of the whole edge: that `@Valid` really fires, that
`ResourceNotFoundException` really becomes a `404` problem detail, that JSON really
serializes through the configured codecs. None of that can be reached by a unit
test, because none of it lives in a single object.

Here is how the slice boots and acquires its client:

::: listing core-lending-loan-origination/src/test/java/com/firefly/lumen/core/web/LoanApplicationControllerTest.java | Listing 23.4 — booting the full reactive context and binding a WebTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoanApplicationControllerTest {

    @Autowired
    private WebTestClient client;
:::

`@SpringBootTest` starts the application context. `webEnvironment = RANDOM_PORT`
boots a real reactive server on an ephemeral port and injects a `WebTestClient`
bound to it. The `WebTestClient` is the reactive analogue of `MockMvc`: a
non-blocking HTTP client that drives your endpoints and lets you assert on status,
headers, and the JSON body with a fluent chain.

With the context up, each test reads as a request and a set of expectations:

::: listing core-lending-loan-origination/src/test/java/com/firefly/lumen/core/web/LoanApplicationControllerTest.java | Listing 23.5 — driving the API and asserting on the RFC 7807 body
    @Test
    void returnsRfc7807ProblemDetailWhenMissing() {
        client.get()
                .uri("/api/v1/loan-applications/{id}", UUID.randomUUID())
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.status").isEqualTo(HttpStatus.NOT_FOUND.value());
    }
:::

`client.get().uri(...).exchange()` performs the request;
`.expectStatus().isNotFound()` asserts the `404`;
`.expectBody().jsonPath("$.status").isEqualTo(404)` reaches into the JSON and proves
the problem detail is a real body, not a bare status line. The
`createsAnApplicationAndReadsItBack` test in the same class does a full
POST-then-GET round trip, and `rejectsAnInvalidPayload` posts a negative amount to
confirm `@ValidAmount` produces a `400`. Three tests, one booted context, the entire
edge verified.

Notice the detail that makes this practical. The class comment says it best:

> boots the full reactive context against in-memory H2 (R2DBC runtime + Flyway
> migration, no Docker).

The repository under test is a *real* R2DBC repository running against an in-memory
H2 database in R2DBC mode, with the schema applied by a Flyway migration at startup.
There is no mock repository and there is no container. The persistence path is
exercised for real — saves, reads, the round trip — against a database that lives
entirely inside the JVM and vanishes when the test ends. That is how a slice test
this thorough still runs in a couple of seconds with nothing installed.

!!! note "Key term — slice test"
    A **slice test** boots enough of the application to exercise one layer end to end
    — here, the whole HTTP edge down to the database — using a real context rather than
    mocks. It sits above unit tests (it starts Spring) and below a full external
    integration test (it uses in-JVM substitutes like H2 instead of the production
    Postgres). It is the highest-value-per-second test in most services: broad enough
    to catch wiring bugs, fast enough to run on every save.

!!! spring "Spring parity"
    `@SpringBootTest` with `RANDOM_PORT` and an injected `WebTestClient` is stock
    Spring Boot — Firefly does not replace the test harness, it rides it. What the
    booted context contains *is* Firefly: the auto-configured `GlobalExceptionHandler`,
    the validators, the idempotency and transaction filters from Chapter 6. So the same
    Boot test mechanism verifies the framework's cross-cutting behavior for free,
    without you registering any of it in the test.

## Level 4 — The saga compensation test

At the apex sits the most demanding test in the suite: proving that when a saga step
fails, the framework *compensates* the steps that already succeeded, leaving no
orphaned write behind. This is the test that earns the orchestration chapter's
central promise, and it is worth reading in full because it shows how much you can
verify with `@SpringBootTest` and `StepVerifier` and still no broker, no container,
no real downstream.

Recall the saga's shape from the orchestration chapter: `registerLoanApplication` is
the root step, and both `registerApplicant` and `proposeOffer` `dependsOn` it. The
root step declares `compensate = removeLoanApplication`. If a dependent step fails
after the root succeeded, the engine must run the root's compensation. The test
forces exactly that failure by swapping in a stub configured to make `proposeOffer`
throw:

::: listing domain-lending-loan-origination/src/test/java/com/firefly/lumen/domain/saga/RegisterApplicationSagaCompensationTest.java | Listing 23.6 — forcing a dependent step to fail with a test-scoped stub bean
@SpringBootTest
@Import(RegisterApplicationSagaCompensationTest.FailingOfferConfig.class)
class RegisterApplicationSagaCompensationTest {

    @TestConfiguration
    static class FailingOfferConfig {
        @Bean
        LoanOriginationClient loanOriginationClient() {
            return new StubLoanOriginationClient().failProposeOffer();
        }
    }
:::

`@SpringBootTest` boots the domain context — the saga engine, the CQRS bus, the
command handlers — all real. The `@TestConfiguration` with `@Import` overrides one
bean: the `LoanOriginationClient` becomes a `StubLoanOriginationClient` set to
`failProposeOffer()`. This is the seam. The framework runtime is genuine; only the
SDK boundary to the (absent) downstream service is stubbed, so the test can drive a
failure deterministically without a network. Substituting a single bean at the edge,
rather than mocking the engine, is what keeps the orchestration under test *real*.

The assertion is where the compensation is proven:

::: listing domain-lending-loan-origination/src/test/java/com/firefly/lumen/domain/saga/RegisterApplicationSagaCompensationTest.java | Listing 23.7 — asserting the failure, the failed step, and the compensated root
    @Test
    void submitApplication_failsAndCompensatesRootStep_whenDependentStepThrows() {
        StepVerifier.create(service.submitApplication("Ada Lovelace", 250_000L, 575))
                .assertNext(result -> {
                    // The saga as a whole failed.
                    assertThat(result.isSuccess()).isFalse();
                    assertThat(result.isFailed()).isTrue();
                    // The failing step is the proposeOffer dependent step.
                    assertThat(result.failedSteps()).contains(RegisterApplicationSaga.STEP_PROPOSE_OFFER);
                    // The root step was compensated (its removeLoanApplication ran).
                    assertThat(result.compensatedSteps())
                            .contains(RegisterApplicationSaga.STEP_REGISTER_LOAN_APPLICATION);
                })
                .verifyComplete();
:::

Read the assertion chain as the story the saga tells.
`service.submitApplication(...)` returns a `Mono` of the saga result;
`StepVerifier.create(...)` subscribes and `.assertNext(...)` inspects the single
emitted result. The result reports `isFailed()` — the saga as a whole did not
succeed — and `failedSteps()` contains `STEP_PROPOSE_OFFER`, the dependent step that
threw. The decisive line is the last one: `compensatedSteps()` contains
`STEP_REGISTER_LOAN_APPLICATION`, proving the engine ran the root step's
`removeLoanApplication` compensation after the dependent failure. The same
`StepVerifier` you used on `Flux.range(1, 6)` two levels down is here asserting a
full orchestration outcome.

The test does not stop at the result object. It reaches into the stub to prove the
*effect* — that the application created by the root step was actually removed, with
no orphan left in the (stubbed) downstream:

::: listing domain-lending-loan-origination/src/test/java/com/firefly/lumen/domain/saga/RegisterApplicationSagaCompensationTest.java | Listing 23.8 — proving the side effect was undone: same id created, then removed
        var stub = (StubLoanOriginationClient) client;
        // The application was created by the root step...
        assertThat(stub.createdApplications()).hasSize(1);
        // ...and then removed by the root step's compensation — same id, no orphan.
        assertThat(stub.removedApplications()).containsExactlyElementsOf(stub.createdApplications());
    }
:::

`createdApplications()` has exactly one entry — the root step did run and create the
application. `removedApplications()` contains exactly the same ids — the compensation
ran and undid it. `containsExactlyElementsOf` ties the two together: every id that
was created was subsequently removed, so the saga left the system clean. This is the
strongest correctness claim in the book — automatic rollback of a partially completed
distributed transaction — and it is verified with a Boot context, a stubbed seam, and
a `StepVerifier`. No saga state was persisted to disk; no broker delivered a message
over a socket.

!!! note "Key term — compensation"
    In the saga pattern, **compensation** is the act of undoing a completed step when a
    later step fails — the distributed-systems substitute for a database rollback, which
    cannot span independent services. Each step that mutates state declares a
    `compensate` method; the engine invokes those methods, in reverse, for the steps
    that already succeeded. Testing compensation means forcing a downstream failure and
    asserting the undo ran — which is precisely what Listings 23.7 and 23.8 do.

## No Docker: how the upper tiers stay container-free

It is worth pausing on the recurring phrase in this chapter, because it is a genuine
design choice and not a sample-only shortcut. Every test in these two modules — even
the full-context web slice and the saga compensation test — runs with no Docker
daemon, no docker-compose, and nothing pre-installed. Two substitutions make that
possible.

For persistence, the web slice runs against **H2 in R2DBC mode**: a real reactive
SQL database that lives inside the JVM. Firefly's data layer speaks R2DBC, and the
R2DBC driver for H2 lets the same repository code that runs against Postgres in
production run against an in-memory database in the test, with a Flyway migration
applying the schema at startup. The persistence path is exercised for real; only the
*engine behind it* is the lightweight in-JVM one.

For messaging and orchestration, the EDA and saga runtimes run **in-process**. As
Chapter 11 showed, binding a listener to `PublisherType.APPLICATION_EVENT` routes
events over Spring's in-JVM event bus rather than Kafka, so the EDA test dispatches
through the real `EventListenerProcessor` with no broker. The saga engine likewise
runs entirely inside the Boot context; the only thing stubbed is the SDK call to the
absent downstream service. In both cases the *framework runtime* is real — annotation
discovery, routing, compensation all execute — and only the external network hop is
elided.

The result is a suite that is fully self-contained: clone, `mvn test`, watch it pass.
That is not a small thing. A test suite that needs infrastructure is a test suite
that runs in CI and nowhere else; a suite that runs anywhere runs *constantly*, which
is where its value compounds.

!!! warning "In-JVM substitutes verify wiring, not the real backend"
    H2 is not Postgres, and the in-JVM event bus is not Kafka. These substitutes
    faithfully exercise *your* code — repositories, listeners, saga steps — but they do
    not catch dialect-specific SQL, Postgres constraint behavior, Kafka partitioning,
    consumer-group rebalancing, or serialization across a real wire. For those you need
    a test against the genuine backend, which is the next section. Treat the
    container-free tiers as exhaustive coverage of your logic and *partial* coverage of
    your infrastructure.

## Where Testcontainers fits

The pyramid as built tops out at in-JVM substitutes, and for the vast majority of
your tests that is the right ceiling — fast, deterministic, runnable anywhere. But
the warning above is real: some bugs only appear against the genuine backend. That is
where **Testcontainers** belongs — a thin, deliberate cap above the in-JVM tier, run
sparingly, for the handful of tests that must prove behavior against real Postgres or
real Kafka.

Testcontainers is a library that starts a throwaway Docker container for the duration
of a test and tears it down after. You point your R2DBC URL or Kafka bootstrap
servers at the container and run the same test you would otherwise run against H2 or
the in-JVM bus — but now against the real engine. The shape is illustrative here
because the reactor ships no such test; Lumen stays container-free by design. Were
you to add one, it would read like this:

```java
// Illustrative: a Testcontainers integration test against real Postgres.
// Not in the reactor — Lumen's suite is container-free by design.
@SpringBootTest
@Testcontainers
class LoanApplicationPostgresIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void r2dbcProps(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () ->
                "r2dbc:postgresql://" + postgres.getHost()
                        + ":" + postgres.getFirstMappedPort()
                        + "/" + postgres.getDatabaseName());
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);
    }

    // ... the same WebTestClient assertions as the H2 slice, now against real Postgres
}
```

`@Container` starts a Postgres 16 container; `@DynamicPropertySource` rewrites the
R2DBC connection properties to point at it before the context boots; the test body is
otherwise identical to the H2 slice. A Kafka container follows the same pattern with
`KafkaContainer` and a `PublisherType.KAFKA` listener, proving real partition and
consumer-group behavior the in-JVM bus cannot.

The judgment call is *how many* of these to write. A Testcontainers test costs
seconds of container startup and requires a Docker daemon, so it inverts the pyramid
the moment you overuse it. Reserve it for what genuinely needs the real backend — a
Postgres-specific migration, a Kafka rebalancing scenario, a serialization contract —
and keep the broad coverage at the H2 and in-JVM tiers. The pyramid stays a pyramid:
a wide, container-free base and middle, capped by a thin, deliberate band of
real-backend tests.

!!! spring "Spring parity"
    Testcontainers, `@DynamicPropertySource`, and the JUnit `@Testcontainers`
    extension are all plain Spring Boot and Testcontainers — no Firefly involved.
    Because Firefly's data and EDA layers are configured by ordinary `spring.r2dbc.*`
    and `firefly.eda.*` properties, pointing a test at a containerized backend is the
    same property override you would write in any Spring Boot service. The framework
    does not get in the way of real-backend testing; it just makes you need it less.

## Run it

Run both loan-origination modules' tests together, from the `samples/lumen-lending`
directory:

```text
mvn -q -pl core-lending-loan-origination,domain-lending-loan-origination test
```

The two modules pass every test. The core module runs **eighteen** tests —
`MoneyTest` (3), `LoanApplicationTest` (6), `ReactiveModelTest` (6), and the
`LoanApplicationControllerTest` slice (3). The domain module runs **six** —
`DomainLendingApplicationTest` (1), `RegisterLoanApplicationHandlerTest` (2),
`LoanApplicationEventListenerTest` (1), `RegisterApplicationSagaHappyPathTest` (1),
and `RegisterApplicationSagaCompensationTest` (1). Twenty-four green tests across
all four levels of the pyramid:

```text
core-lending-loan-origination ..... Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
domain-lending-loan-origination ... Tests run: 6,  Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

!!! tip "Checkpoint"
    Run the command above and confirm both modules report `Failures: 0, Errors: 0`.
    Note the wall-clock time — the whole twenty-four-test suite, including two booted
    Spring contexts and a full saga compensation, finishes in seconds with no Docker
    running. To watch a single tier, narrow with `-Dtest`, for example
    `-Dtest=RegisterApplicationSagaCompensationTest` for just the apex test. If you
    see `Unable to load ...MacOSDnsServerAddressStreamProvider` in the log, ignore it —
    it is a harmless Netty DNS warning on macOS, not a test failure.

## What you learned {.recap}

- A Firefly service tests as a **pyramid**: a wide base of plain unit tests, a band of
  reactive `StepVerifier` tests, web **slice tests** with `@SpringBootTest` and
  `WebTestClient`, and a thin cap of orchestration/integration tests — twenty-four in
  Lumen's two loan-origination modules.
- **Level 1** is plain Java — `MoneyTest` and `LoanApplicationTest` assert invariants
  and state transitions with no Spring and no publishers, in microseconds, because the
  domain logic lives in the objects.
- **Level 2** uses `StepVerifier` from `reactor-test` to assert a publisher's signal
  sequence without `.block()`; the same tool scales from `Flux.range` up to a whole
  saga result.
- **Level 3**, the web slice, boots the full reactive context against **in-memory H2
  in R2DBC mode** (schema via Flyway, no Docker) and drives the edge with
  `WebTestClient`, verifying validation, the RFC 7807 `404`, and the persistence round
  trip for real.
- **Level 4** proves saga **compensation**: a `@TestConfiguration` swaps in a stub that
  fails `proposeOffer`, and the test asserts `compensatedSteps()` contains
  `registerLoanApplication` and that every created application id was removed — full
  distributed rollback, with no broker and no container.
- The upper tiers stay **container-free** by substituting H2 for Postgres and the
  in-JVM event bus for Kafka, keeping the framework runtime real and only eliding the
  network. **Testcontainers** is the deliberate, thin cap for the few tests that must
  run against the genuine Postgres or Kafka.

## Try it yourself {.exercises}

1. **Add an invariant to Level 1.** In `MoneyTest`, add a test that
   `Money.of(0)` is permitted (zero is a valid amount) and that adding two amounts
   returns their sum, mirroring the existing `minusReturnsTheDifference` style. Run
   `-Dtest=MoneyTest` and keep it green — a new fact pinned in microseconds.
2. **Assert a reactive service method.** Pick a service method that returns a `Mono`,
   and write a `StepVerifier` test for it that asserts the emitted value with
   `.expectNextMatches(...)` and `.verifyComplete()`. Confirm it runs without booting a
   context — that is the Level 2 discipline.
3. **Extend the web slice.** In `LoanApplicationControllerTest`, add a test that posts
   a request with `currency = "XYZ"` and asserts `.expectStatus().isBadRequest()` plus
   `.jsonPath("$.status").isEqualTo(400)`, proving `@ValidCurrencyCode` is enforced at
   the booted edge. Re-run `-Dtest=LoanApplicationControllerTest`.
4. **Break compensation and watch it fail.** In `RegisterApplicationSaga`, temporarily
   remove the `compensate` attribute from the root step's `@SagaStep`, then run
   `-Dtest=RegisterApplicationSagaCompensationTest`. Read the assertion failure on
   `compensatedSteps()` — the root no longer rolls back — then restore the attribute.
   You have just proven what the test is actually guarding.
5. **Sketch a Testcontainers cap.** Take the illustrative `LoanApplicationPostgresIT`
   above and list, for your own service, exactly which one or two tests genuinely need
   real Postgres (a dialect-specific migration? a unique constraint?) and which belong
   at the H2 tier. Write the list as a comment — the goal is a *thin* cap, not a second
   full suite.

## Where to go next

You can now test a Firefly service at every level and know which level each behavior
belongs to. The next chapters turn from proving a service correct to running it well:
observability, so a service in production tells you what it is doing, and the
operational concerns — configuration, health, deployment — that take Lumen Lending from
a suite of green tests to a system you can trust on call.
