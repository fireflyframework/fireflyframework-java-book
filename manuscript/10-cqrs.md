Chapter 9 left the loan application enforcing its own rules — but it changed state
through a direct method call inside one service. That works until the orchestration
gets real. A domain service that registers an application, attaches an applicant,
proposes an offer, *and* knows how to undo each step if a later one fails is a lot of
behavior to pour into one method. The cure is to break the work into discrete,
named, individually testable units, and to dispatch them through a bus instead of
calling them directly. That is **CQRS**, and it is the heart of the domain tier.

In this chapter you meet the **domain** tier of Lumen Lending — `starter-domain`, the
orchestration layer that owns no database and talks to the core system of record over
an SDK. You will define a `Command<R>` and a `Query<R>`, write the single-method
handlers that satisfy them, and watch the framework discover and dispatch those
handlers by their generic type, with no manual registration. You will see the
`CommandBus` and `QueryBus` that route the work, the multi-tenant `ExecutionContext`
that flows through it, and the SDK seam — a reactive port — that stands in for the
generated core client until Chapter 14 wires the real one.

Everything you slice lives in the `domain-lending-loan-origination` module, and a
six-method test suite proves it boots and runs with no core service and no Docker.
Let's start with the two halves of CQRS: commands that change state, and queries
that read it.

## Why split commands from queries

The acronym is **C**ommand **Q**uery **R**esponsibility **S**egregation, and the idea
is older than any framework: the operation that *changes* the world and the operation
that *reads* it have different shapes, different scaling needs, and different failure
modes, so model them separately. A command — "register this loan application" — is an
imperative with a result you care about (the new id). A query — "what is this
application's status?" — is a question with an answer and no side effects.

Firefly makes the split concrete with two generic interfaces and two buses. A command
implements `Command<R>`, where `R` is the result type, and travels on the
`CommandBus`. A query implements `Query<R>` and travels on the `QueryBus`. The buses
are separate on purpose: the write path can validate, emit events, and participate in
a saga, while the read path can cache aggressively, because a read changes nothing.

!!! note "Key term — CQRS"
    **CQRS** separates the model that *writes* state (commands) from the model that
    *reads* it (queries). In Firefly each is a small typed message — `Command<R>` or
    `Query<R>` — dispatched through its own bus to a handler the framework discovers by
    generic type. The benefit is not ceremony; it is that each operation becomes a
    discrete unit you can test, trace, cache, and orchestrate independently.

## Step 1 — Define a command

A command is a plain message: the data the operation needs, plus the result type
baked into the interface. Here is the command to register a loan application. It
implements `Command<UUID>` — the result is the server-assigned id — and carries only
the two fields this slice needs.

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/command/RegisterLoanApplicationCommand.java | Listing 10.1 — a command is a typed message carrying its result type
public final class RegisterLoanApplicationCommand implements Command<UUID> {

    private final String applicantName;
    private final long amount;

    public RegisterLoanApplicationCommand(String applicantName, long amount) {
        this.applicantName = applicantName;
        this.amount = amount;
    }

    public String getApplicantName() {
        return applicantName;
    }

    public long getAmount() {
        return amount;
    }
}
:::

That `Command<UUID>` is the load-bearing part. The type parameter is a *contract*: it
says "dispatching me yields a `Mono<UUID>`," and it is also the key the framework uses
to find the handler. The command itself has no behavior — no logic, no client, no
bus. It is an envelope. The behavior lives in a handler, and the type parameter is the
wire between them.

!!! spring "Spring parity"
    `Command<R>` and `Query<R>` are Firefly interfaces, but the message-and-handler
    split is the same idea behind Spring's `ApplicationEventPublisher` or a mediator
    library like a Java port of MediatR. What Firefly adds over a raw event publisher
    is the *typed result*: a command returns a `Mono<R>`, so the caller gets the new
    id back, not just a fire-and-forget notification.

## Step 2 — Write the handler

A handler is where the command does its work. Firefly's handler pattern is
deliberately narrow: you extend `CommandHandler<C, R>` and implement *one* method,
`doHandle`, which receives the command and returns a `Mono<R>`. The framework wraps
your `doHandle` with validation, metrics, tracing, and error mapping, so your method
contains only the business step.

Here is the handler for the register command. It is annotated
`@CommandHandlerComponent`, injects the SDK-seam client and an event publisher, and
in `doHandle` calls the core to create the application, then publishes a domain event.

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/handler/RegisterLoanApplicationHandler.java | Listing 10.2 — a command handler: one doHandle, annotated for discovery
@CommandHandlerComponent
public class RegisterLoanApplicationHandler extends CommandHandler<RegisterLoanApplicationCommand, UUID> {

    private final LoanOriginationClient client;
    private final EventPublisher eventPublisher;

    public RegisterLoanApplicationHandler(LoanOriginationClient client, EventPublisher eventPublisher) {
        this.client = client;
        this.eventPublisher = eventPublisher;
    }

    @Override
    protected Mono<UUID> doHandle(RegisterLoanApplicationCommand command) {
        return client.createLoanApplication(command.getApplicantName(), command.getAmount())
                .flatMap(id -> publishRegistered(id, command).thenReturn(id));
    }

    private Mono<Void> publishRegistered(UUID id, RegisterLoanApplicationCommand command) {
        var event = new LoanApplicationRegisteredEvent(id, command.getApplicantName(), command.getAmount());
        return eventPublisher.publish(event, LoanApplicationRegisteredEvent.EVENT_TYPE);
    }
}
:::

Read the generics on the `extends` clause: `CommandHandler<RegisterLoanApplicationCommand, UUID>`.
The first type parameter is the command this handler answers; the second is the result.
That pairing is exactly the information the framework needs to route — it indexes every
`@CommandHandlerComponent` by its command type, so when something sends a
`RegisterLoanApplicationCommand`, the bus already knows this is the handler. You never
write a `register(...)` line or a switch statement; the generic type *is* the registration.

Inside `doHandle`, the reactive vocabulary from Chapter 5 is all you need. `client.
createLoanApplication(...)` returns a `Mono<UUID>`; `flatMap` chains the asynchronous
event publish and then re-emits the id with `thenReturn`. This is the canonical
"command writes, then emits a domain event" shape — the write reaches the system of
record first, and only on success does the event go out.

!!! note "Key term — the single-method handler pattern"
    A Firefly handler extends `CommandHandler<C, R>` (or `QueryHandler<Q, R>`) and
    implements exactly one abstract method, `doHandle`. Everything cross-cutting —
    input validation, the metrics timer, the trace span, error translation — lives in
    the framework's surrounding `handle` method, which calls your `doHandle`. You write
    the business step and nothing else, and every handler in the fleet is wrapped the
    same way.

!!! spring "Spring parity"
    `@CommandHandlerComponent` is a meta-annotated Spring stereotype — under the hood
    it is a `@Component`, so component scanning finds the handler exactly as it finds
    an `@Service`. The Firefly addition is the post-processing that reads the handler's
    generic type parameters and registers it on the `CommandBus`. No XML, no manual
    `bus.register(...)`: classpath presence plus a generic signature is the whole wiring.

## Step 3 — Define a query and its handler

The read side is symmetrical and simpler. A query implements `Query<R>`; here
`GetApplicationStatusQuery` implements `Query<String>` because the answer is a status
label.

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/query/GetApplicationStatusQuery.java | Listing 10.3 — a query is a typed question
public final class GetApplicationStatusQuery implements Query<String> {

    private final UUID loanApplicationId;

    public GetApplicationStatusQuery(UUID loanApplicationId) {
        this.loanApplicationId = loanApplicationId;
    }

    public UUID getLoanApplicationId() {
        return loanApplicationId;
    }
}
:::

Its handler follows the same single-method pattern as the command handler, but extends
`QueryHandler<Q, R>` and is annotated `@QueryHandlerComponent`. The slice keeps the
read model trivial on purpose — once an application id exists, its status is reported
as `REGISTERED` — so the command/query split is visible without dragging in a separate
projection store.

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/handler/GetApplicationStatusHandler.java | Listing 10.4 — the read-side handler, discovered on the QueryBus
@QueryHandlerComponent
public class GetApplicationStatusHandler extends QueryHandler<GetApplicationStatusQuery, String> {

    @Override
    protected Mono<String> doHandle(GetApplicationStatusQuery query) {
        return Mono.just("REGISTERED");
    }
}
:::

Notice how little there is to it: no client, no event, just a `Mono` of the answer. A
real read handler would consult a projection or call the core's read API, but the
*shape* is identical — extend the base class, parameterize with the query and its
result, implement `doHandle`. The framework discovers it on the `QueryBus` precisely
the way it discovered the command handler on the `CommandBus`, by the generic type.

!!! tip "Checkpoint"
    Stop and notice the pattern. Four files — two messages, two handlers — and you have
    not written a single line that *registers* a handler, *routes* a message, or
    *subscribes* to a `Mono`. The command/query interfaces declare the result type; the
    handler base classes declare which message they answer; the framework reads those
    generics and wires the buses. If you came expecting a configuration class, there
    isn't one.

## Step 4 — Dispatch through the buses

Handlers are discovered, but something has to *send*. That is the `CommandBus` and
`QueryBus`. You inject them like any bean and call `send` for a command or `query` for
a query; each returns a `Mono<R>` whose type matches the message's result parameter.

The cleanest place to see the read path is the domain service. `LoanOriginationService`
injects the `QueryBus` and dispatches the status query in one line:

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/service/LoanOriginationService.java | Listing 10.5 — the service dispatches a query through the QueryBus
    /** Reads the current status of a loan application via the {@link QueryBus}. */
    public Mono<String> getApplicationStatus(UUID loanApplicationId) {
        return queryBus.query(new GetApplicationStatusQuery(loanApplicationId));
    }
:::

`queryBus.query(new GetApplicationStatusQuery(id))` returns `Mono<String>` — the
compiler infers the result type from the query's `Query<String>` parameter, and the
bus routes to `GetApplicationStatusHandler` because that handler declared the same
query type. The caller never names the handler. It names the *message*, and the bus
finds the rest.

The command side dispatches the same way, with `commandBus.send(command)`. You will
not see a bare `send` in the service, because in Lumen the write path is wrapped in a
saga (the next chapter's subject), but inside a saga step the call is exactly that:

```java
// Inside a saga step — a command dispatched on the CommandBus.
return commandBus.send(command)
        .doOnNext(id -> ctx.putVariable(CTX_LOAN_APPLICATION_ID, id));
```

`commandBus.send(command)` returns the `Mono<UUID>` promised by the command's
`Command<UUID>` parameter; the step stashes the new id in the `ExecutionContext` so
later steps can read it. Whether dispatched directly or from a saga step, the contract
is the same: hand the bus a typed message, get back a `Mono` of its declared result.

!!! note "Key term — ExecutionContext"
    The **`ExecutionContext`** is the request-scoped bag of variables and metadata that
    flows through a dispatch — across command and query handlers, and across every step
    of a saga. It is how a multi-tenant fleet keeps a tenant id, a correlation id, and
    intermediate results (like the new application id) traveling with the work, on the
    reactive stack, without a `ThreadLocal`. You write to it with `putVariable` and read
    it back, typed, with `getVariableAs`.

## Step 5 — The SDK seam, honestly

The command handler called `client.createLoanApplication(...)`. What is that client?
It is a **reactive port** — an interface the domain tier depends on to reach the core
system of record. Here is the seam.

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/client/LoanOriginationClient.java | Listing 10.6 — the SDK seam: a reactive port to the core service
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

Be clear-eyed about what this is. In a real Firefly deployment, the domain tier does
not hand-write this interface — it injects the *generated core SDK*, a WebClient-based
client produced from the core service's OpenAPI contract. The reactor hand-rolls a
trimmed port here for one honest reason: so the sample compiles and its tests run with
**no running core service and no Docker**. The port is a stand-in. Chapter 14 replaces
it with the generated SDK and wires the real HTTP call to the core tier you built in
Chapters 7 and 8.

That substitution is exactly why CQRS and the port matter. The handler depends on the
*interface*, never on a concrete client, so a test can supply an in-memory
implementation and the production wiring can supply the generated SDK — and the
handler does not change. The tiers integrate over a contract, never a shared database,
which is the rule Chapter 1 set for the whole fleet.

!!! warning "The port is a stand-in, not the production client"
    Do not read `LoanOriginationClient` as "how Firefly calls a downstream service."
    The production path is a *generated* SDK client with resilient defaults — retries,
    timeouts, a circuit breaker (Chapter 14). The hand-rolled port exists so this
    chapter can teach CQRS without standing up the core service. When you see the port,
    read "this is where the generated SDK plugs in."

## How queries cache, and where the bus reports

Two capabilities of the CQRS buses are worth knowing even though the slice does not
exercise them, because they explain *why* the command/query split is more than
naming.

Because a query changes nothing, the `QueryBus` can cache its result. Firefly's CQRS
auto-configuration supports caching a query's answer keyed by the query instance, so a
repeated `GetApplicationStatusQuery` for the same id can be served from cache instead
of re-hitting the read model. You opt in per query type and tune it with `firefly.cqrs.*`
properties; the command bus never caches, because a command's whole purpose is the
side effect. Conceptually a cacheable query handler looks like this:

```java
// Illustrative — a query handler that opts into result caching.
@QueryHandlerComponent(cacheable = true, cacheTtl = 30)
public class GetApplicationStatusHandler extends QueryHandler<GetApplicationStatusQuery, String> {
    @Override
    protected Mono<String> doHandle(GetApplicationStatusQuery query) {
        return Mono.just("REGISTERED");
    }
}
```

And because every dispatch flows through a bus, the framework can observe it. When the
CQRS capability is on the classpath it contributes an Actuator endpoint, `/actuator/cqrs`,
that reports the registered handlers and per-type metrics — how many commands and
queries have been dispatched, their latencies, their failure counts. You did not wire
any of that; it comes with the bus, identically across the fleet. The slice in this
chapter does not assert against the endpoint, so treat both caching and `/actuator/cqrs`
as how-it-works, not as something this build verifies.

!!! spring "Spring parity"
    Query caching rides on Spring's cache abstraction, and `/actuator/cqrs` is a plain
    Spring Boot Actuator endpoint — both are mechanisms you could assemble by hand in a
    vanilla app. Firefly's value is that they are pre-wired to the buses: every command
    and query is timed and counted, and any query can opt into caching, without you
    touching a `CacheManager` or writing an `@Endpoint`.

## Run it

The whole tier boots and runs without a core service. The reactor proves it with six
tests: two that exercise the register handler in isolation against an in-memory stub,
and four that boot a real Spring context to wire the buses, the handlers, and the saga
— substituting only the SDK seam. From the `samples/lumen-lending` directory:

```text
mvn -q -pl domain-lending-loan-origination test
```

The expected result:

```text
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

The handler test is the one to read closely, because it shows the dispatch machinery
without a Spring context at all — it constructs the handler directly and calls the
framework's public `handle` method, which wraps `doHandle`:

```java
// From RegisterLoanApplicationHandlerTest — handle(...) wraps your doHandle(...).
StepVerifier.create(handler.handle(command))
        .assertNext(id -> assertThat(id).isEqualTo(client.createdApplications().get(0)))
        .verifyComplete();
```

The four context-booting tests go one level up: they let the framework discover every
`@CommandHandlerComponent`, register the `@Saga`, and wire the `CommandBus` and
`QueryBus` — and the *only* substitution is a `@Bean` that supplies the in-memory
`StubLoanOriginationClient` for the `LoanOriginationClient` port. No Docker, no core
service. That is the SDK seam earning its keep.

!!! tip "Checkpoint"
    Six green tests, and not one of them needs the core tier running. The command/query
    split, the typed buses, and the port together let you test the orchestration layer
    in complete isolation — the fastest possible feedback for the most business-critical
    code in the fleet.

## What you built {.recap}

- A **`Command<UUID>`** and a **`Query<String>`** — typed messages that carry their
  result type, the write side on the `CommandBus`, the read side on the `QueryBus`,
  separated because writing and reading have different shapes and needs.
- Two **single-method handlers** — `@CommandHandlerComponent` and
  `@QueryHandlerComponent` stereotypes that extend `CommandHandler<C, R>` /
  `QueryHandler<Q, R>` and implement only `doHandle`, while the framework supplies
  validation, metrics, tracing, and error mapping around them.
- **Auto-discovery by generic type** — the framework indexes each handler by its
  command/query type parameter and routes `commandBus.send` / `queryBus.query` to it,
  with no manual registration and no switch statement.
- The **`ExecutionContext`** that carries tenant, correlation, and intermediate state
  through a dispatch on the reactive stack, and a first look at query caching and
  `/actuator/cqrs` as capabilities the bus contributes for free.
- The **SDK seam** — `LoanOriginationClient`, a reactive port that stands in for the
  generated core SDK so the whole tier compiles and tests green with no core service
  and no Docker, and that Chapter 14 will replace with the real client.

## Try it yourself {.exercises}

1. **Add a query.** Define `GetApplicationAmountQuery implements Query<Long>` alongside
   the status query, and a `@QueryHandlerComponent` handler that returns a fixed
   `Mono.just(0L)`. Inject the `QueryBus` in a small test and dispatch it. You wrote no
   registration code — explain which line told the framework about your new handler.
2. **Read the generic wiring.** In `RegisterLoanApplicationHandler`, change the second
   type parameter of `CommandHandler<RegisterLoanApplicationCommand, UUID>` to `String`
   without changing `doHandle`. Read the compile error and explain, in one sentence,
   why the result type is part of the contract and not a free choice.
3. **Prove the port is the seam.** Open `StubLoanOriginationClient` under
   `src/test/java`, change the format of the call it records in `createLoanApplication`,
   and re-run `RegisterLoanApplicationHandlerTest`. Adjust only the test's assertion and
   confirm the *handler* never changed — it depends on the interface, not the stub.
4. **Trace a command to its handler.** Starting from `commandBus.send(command)` in
   `RegisterApplicationSaga`, follow the type parameters: which handler answers a
   `RegisterLoanApplicationCommand`, and what in its class declaration makes the bus
   pick it? Write down the single fact the bus uses to route.
5. **Make a query cacheable in your head.** The slice's `GetApplicationStatusHandler`
   returns a constant. Argue why caching it would be safe, then describe one change to
   the read model that would make caching *unsafe* — and which `firefly.cqrs.*` knob you
   would reach for to bound the staleness.

## Where to go next

You now have discrete, dispatched, testable commands and queries — but a real
registration is several commands that must succeed or fail *together*, with each
completed step undone if a later one breaks. Chapter 11 introduces the **saga**: the
`@Saga` and `@SagaStep` orchestration that runs `registerLoanApplication`,
`registerApplicant`, and `proposeOffer` as one atomic flow, threads the new id through
the `ExecutionContext` you met here, and compensates in reverse when a step fails. The
commands you just built are exactly the steps that saga will orchestrate.
