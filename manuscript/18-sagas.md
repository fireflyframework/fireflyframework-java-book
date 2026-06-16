Chapter 6 gave you `@Transactional`, and inside one service against one database it
is the right tool: the moment a step fails, the row never appears, because the
database rolls the whole unit back. But registering a loan application is not one
write to one database. It creates the application in the core system of record,
attaches an applicant party, and proposes an offer — three operations that, in a
real platform, cross three service boundaries and three datastores. There is no
shared transaction to roll back. If the offer step fails after the application is
already written, `@Transactional` cannot help you: the application is committed, in
another service, and now it is an orphan.

This is the distributed-transaction problem, and the answer is the **saga**. A saga
breaks a multi-step operation into discrete steps, each with a **compensation** — an
explicit "undo" the engine runs if a later step fails. There is no global rollback;
instead, completed steps are *compensated* in reverse, so the system is driven back
toward a consistent state by running real business operations (delete the
application, release the hold) rather than by reverting database rows. Compensation
is the cross-service substitute for rollback, and it is the heart of this chapter.

In this chapter you slice Lumen's `RegisterApplicationSaga` — a `@Saga` with a root
`@SagaStep` that creates the application and two dependent steps that fan out from
it — and the `LoanOriginationService` that runs it through the `SagaEngine`. Then
you read the headline test: it forces the offer step to fail and proves the engine
compensates the root step, so no orphaned application is left behind. Everything runs
in the `domain-lending-loan-origination` module, with **no core service and no
Docker**. At the end you will meet two sibling patterns — Workflow and TCC — and know
when to reach for each.

The cast of files, all under `samples/lumen-lending/domain-lending-loan-origination`:

- `src/main/java/com/firefly/lumen/domain/saga/RegisterApplicationSaga.java` — the
  `@Saga` itself: three `@SagaStep` methods, their `compensate` undos, the
  `dependsOn` topology, and a `@StepEvent` on each step.
- `src/main/java/com/firefly/lumen/domain/service/LoanOriginationService.java` — the
  service that assembles `StepInputs` and calls `SagaEngine.execute(...)`, reading
  back a `SagaResult`.
- `src/test/java/com/firefly/lumen/domain/saga/RegisterApplicationSagaCompensationTest.java`
  — the headline compensation test.
- `src/test/java/com/firefly/lumen/domain/saga/RegisterApplicationSagaHappyPathTest.java`
  — the success-path companion, one of the module's six tests.

## What a saga is, and why a method tree

A Firefly saga is an ordinary Spring bean — annotated `@Saga` *and* `@Service` — whose
methods are the steps. You do not write a state machine or a workflow XML; you write
methods, annotate each with `@SagaStep`, and declare two things per step: the name of
its compensation method, and which other step it `dependsOn`. From those declarations
the engine builds a **dependency DAG** and runs it: steps with no unmet dependencies
run, their outputs flow forward, dependent steps run next, and if anything fails, the
already-completed steps are compensated in reverse.

!!! note "Key term — saga"
    A **saga** is a sequence of local steps that together accomplish a
    distributed operation, where each step has a **compensating action** that
    semantically undoes it. There is no two-phase commit and no global rollback;
    consistency is restored by *compensation* — running the inverse business
    operation for each step that already completed. A saga trades the strong
    atomicity of `@Transactional` for the only kind of atomicity available across
    independent services.

Lumen's saga has the smallest topology that still teaches the whole pattern: a single
root, then two dependents that fan out from it.

```text
registerLoanApplication            (root; compensate = removeLoanApplication)
  ├── registerApplicant            (dependsOn root)
  └── proposeOffer                 (dependsOn root)
```

The root creates the application and must run first, because both dependents need the
new application id. Once the root completes, `registerApplicant` and `proposeOffer`
have their dependency satisfied and the engine fans them out. The id travels from the
root to the dependents through the `ExecutionContext` you met in Chapter 10 — the
request-scoped bag that flows through the whole saga.

## Step 1 — The root step and its compensation

Open the saga and read the root step first. It is annotated `@SagaStep` with an `id`
and a `compensate` — the name of the method the engine calls to undo this step — and a
`@StepEvent` that names a domain event to emit when the step completes. The method
dispatches a CQRS command through the `CommandBus` (the same bus from Chapter 10) and,
crucially, writes the new application id into the `ExecutionContext`.

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/saga/RegisterApplicationSaga.java | Listing 18.1 — the root step writes the application and publishes its id into the context
    @SagaStep(id = STEP_REGISTER_LOAN_APPLICATION, compensate = COMPENSATE_REMOVE_LOAN_APPLICATION)
    @StepEvent(type = EVENT_LOAN_APPLICATION_REGISTERED)
    public Mono<UUID> registerLoanApplication(RegisterLoanApplicationCommand command, ExecutionContext ctx) {
        return commandBus.send(command)
                .doOnNext(loanApplicationId -> ctx.putVariable(CTX_LOAN_APPLICATION_ID, loanApplicationId));
    }

    /** Compensation for the root step: delete the application created above. */
    public Mono<Void> removeLoanApplication(UUID loanApplicationId) {
        return client.removeLoanApplication(loanApplicationId);
    }
:::

Read the two methods as a pair, because that pairing *is* the saga discipline. The
step does the work — `commandBus.send(command)` returns a `Mono<UUID>`, and
`doOnNext` stashes that id under `CTX_LOAN_APPLICATION_ID` so later steps can read it.
The compensation is the exact inverse: given the id the step produced, delete the
application from the core. The engine remembers each step's output, and when it must
compensate, it hands that output to the compensation method. So `removeLoanApplication`
receives the very `UUID` the step returned and deletes precisely the row the step
created. No orphan.

Notice that the compensation calls `client.removeLoanApplication(...)` directly — the
`LoanOriginationClient` SDK seam from Chapter 10 — rather than dispatching another
command. A compensation is a plain reactive method returning `Mono<Void>`; it can do
whatever undoing the step requires. The framework's only contract is that it return
`Mono<Void>` and accept the step's result.

!!! note "Key term — `@SagaStep` and compensate"
    `@SagaStep(id, compensate, dependsOn)` marks a method as one step of a saga.
    `id` names the step (and is the key you use to supply its input). `compensate`
    names the method that undoes this step if a later step fails. `dependsOn`
    names the step (or steps) that must complete before this one runs. The forward
    method returns the step's result; the compensation receives that result and
    returns `Mono<Void>`. A step without a meaningful undo can still declare a
    `compensate` method that returns `Mono.empty()`.

!!! spring "Spring parity"
    `@Saga` is meta-annotated with `@Service`, so component scanning discovers the
    saga bean exactly as it discovers any `@Service` — there is no special
    registry to populate. The Firefly addition is the post-processing that reads
    the `@SagaStep`/`@StepEvent` annotations, builds the dependency DAG, and
    registers the saga with the `SagaEngine` by its `@Saga(name = ...)`. You write
    plain Spring beans; the engine reads the annotations.

## Step 2 — The dependent steps fan out

The two dependent steps add one annotation attribute the root did not have:
`dependsOn = STEP_REGISTER_LOAN_APPLICATION`. That single attribute is what places
them *after* the root in the DAG, and because they depend on the same step and on
nothing else, the engine can run them as a fan-out once the root completes. Each reads
the application id back out of the `ExecutionContext` and stamps it onto its command
before dispatch.

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/saga/RegisterApplicationSaga.java | Listing 18.2 — two dependent steps read the id from the context and dispatch
    @SagaStep(id = STEP_REGISTER_APPLICANT,
            compensate = COMPENSATE_REMOVE_APPLICANT,
            dependsOn = STEP_REGISTER_LOAN_APPLICATION)
    @StepEvent(type = EVENT_APPLICANT_REGISTERED)
    public Mono<UUID> registerApplicant(RegisterApplicantCommand command, ExecutionContext ctx) {
        UUID loanApplicationId = ctx.getVariableAs(CTX_LOAN_APPLICATION_ID, UUID.class);
        return commandBus.send(command.withLoanApplicationId(loanApplicationId));
    }

    /** Compensation for {@link #registerApplicant}. No-op in this slice; nothing to undo upstream. */
    public Mono<Void> removeApplicant(UUID applicantId, ExecutionContext ctx) {
        return Mono.empty();
    }

    // ---------------------------------------------------------------------
    // Dependent step: propose an offer (after the root step).
    // ---------------------------------------------------------------------

    @SagaStep(id = STEP_PROPOSE_OFFER,
            compensate = COMPENSATE_REMOVE_OFFER,
            dependsOn = STEP_REGISTER_LOAN_APPLICATION)
    @StepEvent(type = EVENT_OFFER_PROPOSED)
    public Mono<UUID> proposeOffer(ProposeOfferCommand command, ExecutionContext ctx) {
        UUID loanApplicationId = ctx.getVariableAs(CTX_LOAN_APPLICATION_ID, UUID.class);
        return commandBus.send(command.withLoanApplicationId(loanApplicationId));
    }

    /** Compensation for {@link #proposeOffer}. No-op in this slice; nothing to undo upstream. */
    public Mono<Void> removeOffer(UUID offerId, ExecutionContext ctx) {
        return Mono.empty();
    }
:::

The `ctx.getVariableAs(CTX_LOAN_APPLICATION_ID, UUID.class)` call is the other half of
the `putVariable` you saw in the root step. The root *wrote* the id; each dependent
*reads* it, typed, and threads it into the command with `withLoanApplicationId(...)`.
This is how data flows along a DAG edge without the steps holding references to one
another — they communicate only through the `ExecutionContext`, which is exactly what
lets the engine schedule and, when needed, compensate them independently.

Both dependent compensations return `Mono.empty()` here, and the comment is honest
about why: in this slice, registering an applicant and proposing an offer leave
nothing upstream that needs undoing if they themselves were the *last* thing to run.
The compensation that does real work is the root's `removeLoanApplication`, because
the root is the step that created the durable write the saga must not orphan. That is
the case the test exercises.

!!! note "Key term — `ExecutionContext` in a saga"
    The **`ExecutionContext`** is the request-scoped store that flows through every
    step of one saga run. A step writes intermediate state with
    `ctx.putVariable(key, value)` and a later step reads it back, typed, with
    `ctx.getVariableAs(key, Type.class)`. In a fleet it also carries tenant and
    correlation ids on the reactive stack, with no `ThreadLocal`. Here it carries
    one thing that matters enormously: the new application id, from the root step to
    the two dependents.

## Step 3 — Step events announce each completed step

Every step in this saga carries a `@StepEvent(type = ...)`. When a step completes
successfully, the engine emits a step-level domain event of that type. This is the
saga's tie-in to the EDA runtime from Chapter 11: `registerLoanApplication` emits
`loanApplication.registered`, `registerApplicant` emits `applicant.registered`, and
`proposeOffer` emits `offer.proposed`. A fraud check, a notification service, or an
audit log can subscribe to those step events and react as the saga progresses, without
the saga knowing they exist.

The event-type constants live at the top of the saga class:

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/saga/RegisterApplicationSaga.java | Listing 18.3 — the step-event types each step emits
    /** Step-event types emitted by each step. */
    public static final String EVENT_LOAN_APPLICATION_REGISTERED = "loanApplication.registered";
    public static final String EVENT_APPLICANT_REGISTERED = "applicant.registered";
    public static final String EVENT_OFFER_PROPOSED = "offer.proposed";
:::

The point of `@StepEvent` is that announcement is *declarative* and *per step*: you
do not inject an `EventPublisher` into the saga or call `publish` in the step body.
You annotate the step, and the engine emits the event when the step succeeds. The
forward progress of the saga and the stream of facts about it are wired together by
the engine, so observers see exactly the steps that completed — and, by their absence,
the steps that did not.

!!! spring "Spring parity"
    `@StepEvent` rides on the same EDA runtime as Chapter 11's `@EventListener`. A
    step event is an ordinary domain event published by the engine on the configured
    transport; in Lumen that is the in-JVM `APPLICATION_EVENT` bus, so the saga emits
    step events with no broker. Swapping to Kafka is the same `PublisherType` and
    `firefly.eda.*` change you saw before — the saga code does not move.

## Step 4 — Running the saga: StepInputs in, SagaResult out

A saga bean defines the steps but does not start itself. The `SagaEngine` runs it by
name, and the domain service is where that call lives. `LoanOriginationService`
injects the `SagaEngine`, builds one input per step with `StepInputs`, and calls
`execute(...)` — handing back the `SagaResult` so the caller can inspect exactly what
happened.

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/service/LoanOriginationService.java | Listing 18.4 — assembling StepInputs and running the saga through the engine
    public Mono<SagaResult> submitApplication(String applicantName, long amount, int annualRateBps) {
        StepInputs inputs = StepInputs.builder()
                .forStepId(RegisterApplicationSaga.STEP_REGISTER_LOAN_APPLICATION,
                        new RegisterLoanApplicationCommand(applicantName, amount))
                .forStepId(RegisterApplicationSaga.STEP_REGISTER_APPLICANT,
                        new RegisterApplicantCommand(applicantName))
                .forStepId(RegisterApplicationSaga.STEP_PROPOSE_OFFER,
                        new ProposeOfferCommand(amount, annualRateBps))
                .build();

        return sagaEngine.execute(RegisterApplicationSaga.SAGA_NAME, inputs);
    }
:::

Three moves, and each maps to something you have already seen. `StepInputs.builder()`
attaches one input object to each step id — the command each `@SagaStep` method will
receive as its first parameter. The step ids here (`STEP_REGISTER_LOAN_APPLICATION`
and friends) are the same constants the `@SagaStep(id = ...)` annotations declared, so
the builder is literally addressing each step by name. Then
`sagaEngine.execute(SAGA_NAME, inputs)` looks the saga up by its `@Saga(name = ...)`,
runs the DAG, and returns a `Mono<SagaResult>`.

The `SagaResult` is the whole point of getting a value back instead of a fire-and-
forget. It tells you whether the saga succeeded or failed, and — when it failed —
which steps failed and which were compensated. The service does not interpret the
result; it returns it, so the caller (and, in a moment, the test) can read it:

```java
// Illustrative — the questions a SagaResult answers.
result.isSuccess();        // every step completed
result.isFailed();         // at least one step failed
result.failedSteps();      // ids of the steps that threw
result.compensatedSteps(); // ids of completed steps the engine undid
```

!!! note "Key term — SagaEngine, StepInputs, SagaResult"
    The **`SagaEngine`** runs a registered saga by name. **`StepInputs`** is the
    per-step input map you hand it — `forStepId(id, input)` for each step. The engine
    returns a **`SagaResult`**: `isSuccess()`/`isFailed()` for the overall outcome,
    `failedSteps()` for the steps that threw, and `compensatedSteps()` for the
    completed steps it had to undo. The result is data, not an exception, so the
    caller decides what a partial failure means for the API response.

!!! spring "Spring parity"
    There is no plain-Spring equivalent of `SagaEngine` — this is a Firefly
    orchestration capability, auto-configured when the orchestration module is on the
    classpath. What *is* familiar is the seam: the engine is an injected bean, the
    inputs are plain objects, and the result is a plain record. You orchestrate a
    distributed transaction with the same dependency-injection ergonomics you use for
    any service.

## Step 5 — The headline: compensation when a step fails

Now the test that justifies the whole pattern. The compensation test wires the saga
exactly as production does — real `SagaEngine`, real `CommandBus`, real handlers — and
changes one thing: it substitutes a stub `LoanOriginationClient` configured to make
the `proposeOffer` step fail. Then it runs `submitApplication(...)` and asserts what
the engine did with the failure.

::: listing domain-lending-loan-origination/src/test/java/com/firefly/lumen/domain/saga/RegisterApplicationSagaCompensationTest.java | Listing 18.5 — forcing proposeOffer to fail and asserting the root step was compensated
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

        var stub = (StubLoanOriginationClient) client;
        // The application was created by the root step...
        assertThat(stub.createdApplications()).hasSize(1);
        // ...and then removed by the root step's compensation — same id, no orphan.
        assertThat(stub.removedApplications()).containsExactlyElementsOf(stub.createdApplications());
    }
:::

Read the assertions as a story of what the engine did. First, the saga as a whole
failed: `isSuccess()` is false, `isFailed()` is true. Second, the *specific* step that
failed is `proposeOffer` — `failedSteps()` contains it — because the stub was
configured to throw there. Third, and this is the payoff, `compensatedSteps()`
contains the *root* step, `registerLoanApplication`. The root succeeded, then a
dependent failed, so the engine ran the root's `removeLoanApplication` compensation to
undo it.

The final two assertions prove the compensation was not merely *recorded* but
*effective*. The stub remembers every application it created and every one it removed.
After the saga, exactly one application was created (by the root step) and the set of
removed applications equals the set of created ones — same id, accounted for. The
write the root step made is gone. **No orphan.** That is the entire promise of a saga,
verified: when a later step fails, the work the earlier steps committed does not leak.

The setup that makes the offer step fail is a one-bean test configuration —
`new StubLoanOriginationClient().failProposeOffer()` — supplied for the
`LoanOriginationClient` port. Nothing else about the wiring changes; the saga, the
engine, and the compensation are all the real code. This is the SDK seam from
Chapter 10 earning its keep again: because the saga depends on the *interface*, a test
can make any step fail by configuring the stub, and observe the engine's real
compensation behavior with no core service and no Docker.

!!! tip "Checkpoint"
    The companion `RegisterApplicationSagaHappyPathTest` runs the same saga with an
    *unconfigured* stub, so every step succeeds, and asserts `result.isSuccess()` is
    true with no compensations. Read the two tests side by side: same saga, same
    engine, and the only difference is whether the stub fails the offer step. That
    contrast is the saga pattern in two files — success runs forward, failure runs
    forward then compensates back.

## Run it

The whole domain tier — CQRS handlers, the EDA listener, and both saga tests — boots
and runs without a core service. From the `samples/lumen-lending` directory:

```text
mvn -q -pl domain-lending-loan-origination test
```

The expected result:

```text
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Six green tests, and two of them are the saga: `RegisterApplicationSagaHappyPathTest`
proves the forward path completes, and `RegisterApplicationSagaCompensationTest` —
Listing 18.5 — proves the failure path compensates the root step and leaves no orphan.
When the compensation test runs you will see the engine log it in real time:
`step.failed ... stepId=proposeOffer`, then `compensation.started`, then a
`dead-lettered` entry recording the failed step. Those log lines are the engine
narrating exactly the behavior the assertions check.

!!! warning "A saga is eventual, not atomic — compensations must be safe to run"
    A saga gives up the all-or-nothing atomicity of `@Transactional`. Between the
    root step committing and a later step failing, the application *briefly exists*
    before compensation removes it — the system is consistent only *eventually*.
    That puts weight on your compensations: they must be idempotent (the engine may
    retry), they must tolerate being run against a step whose effect is only
    partially applied, and they should not themselves fail silently. Design each
    `compensate` method as carefully as the step it undoes.

## Two siblings: Workflow and TCC

The saga is one of three orchestration patterns Firefly's engine supports. The other
two solve the same distributed-transaction problem with different trade-offs, and
knowing where each fits keeps you from forcing a saga onto a job it is wrong for. Both
are described here conceptually — the reactor verifies the saga, not these — so treat
the snippets below as how-it-works, not as something this build runs.

**Workflow — fire forward, no compensation.** A workflow is a saga's optimistic
cousin: a sequence of steps that run forward to completion, *without* compensating
on failure. You use it when the steps have no meaningful undo — sending a sequence of
notifications, running an enrichment pipeline, fanning out read-only calls — or when a
failed step should simply stop the flow and be retried later rather than rolled back.
A workflow keeps the DAG, the `ExecutionContext`, and the step events, and drops the
compensation half:

```java
// Illustrative — a workflow step: forward-only, no compensate attribute.
@WorkflowStep(id = "notifyApplicant", dependsOn = "registerApplicant")
public Mono<Void> notifyApplicant(NotifyCommand command, ExecutionContext ctx) {
    return notifier.send(command);            // nothing to undo if a later step fails
}
```

**TCC — Try, Confirm, Cancel.** TCC is the saga's stricter cousin, for resources that
support *reservation*. Each participant exposes three operations: **Try** reserves the
resource (place a hold on funds, reserve inventory) without committing it; **Confirm**
makes every reservation permanent once all Tries succeed; **Cancel** releases the
reservations if any Try fails. Where a saga commits each step and compensates after
the fact, TCC holds everything in a reserved state until the whole operation is known
to succeed — so there is no window where a committed step must be visibly undone. It
costs more (every resource must support the three-phase protocol) and buys tighter
consistency:

```java
// Illustrative — a TCC participant exposes try / confirm / cancel.
@TccParticipant(id = "reserveFunds")
class ReserveFunds {
    @TccTry     Mono<Void> tryReserve(ReserveCommand c, ExecutionContext ctx) { /* place hold */ }
    @TccConfirm Mono<Void> confirm(ExecutionContext ctx)                      { /* settle hold */ }
    @TccCancel  Mono<Void> cancel(ExecutionContext ctx)                       { /* release hold */ }
}
```

!!! note "Key term — Saga vs. Workflow vs. TCC"
    All three orchestrate multi-step distributed operations through the same engine
    and `ExecutionContext`. A **Saga** commits each step and *compensates* completed
    steps in reverse on failure — best when steps have a clean semantic undo. A
    **Workflow** runs forward with *no* compensation — best when steps cannot or need
    not be undone. **TCC** (Try-Confirm-Cancel) *reserves* each resource, then
    confirms all or cancels all — best when participants support reservations and you
    want to avoid a visible committed-then-undone window. Pick by how your resources
    behave: undoable, fire-forward, or reservable.

## What you built {.recap}

- A **`@Saga`** bean, `RegisterApplicationSaga`, whose methods are `@SagaStep`s: a
  root `registerLoanApplication` and two dependents, `registerApplicant` and
  `proposeOffer`, that `dependsOn` the root and fan out once it completes.
- A **compensation** for each step — the root's `removeLoanApplication` deletes the
  application the step created, given the very id the step returned — which is the
  cross-service substitute for `@Transactional` rollback.
- The **`ExecutionContext`** carrying the new application id from the root step
  (`putVariable`) to the dependents (`getVariableAs`), so steps share data along DAG
  edges without referencing one another.
- A **`@StepEvent`** on every step, so the engine emits a step-level domain event on
  the EDA runtime as the saga progresses.
- The **`SagaEngine`** run from `LoanOriginationService`: `StepInputs` in, a
  `SagaResult` out, exposing `isSuccess`/`isFailed`/`failedSteps`/`compensatedSteps`.
- The **headline compensation test** (`Tests run: 6, Failures: 0`): forcing
  `proposeOffer` to fail proves the root step is compensated and the created
  application is removed — **no orphan** — with no core service and no Docker.

## Try it yourself {.exercises}

1. **Read the failure into the API.** `submitApplication` returns the raw
   `SagaResult`. Sketch how an experience-tier caller would turn `result.isFailed()`
   plus `result.failedSteps()` into an HTTP response — which status code, and what
   would you put in the RFC 7807 `detail`? You do not need to run it; argue the
   mapping.
2. **Fail the root instead.** In `StubLoanOriginationClient`, add a `failCreate()`
   switch like the existing `failProposeOffer()` and write a test that makes the
   *root* step fail. What should `compensatedSteps()` contain, and why is it empty?
   (Hint: the root never completed, so there is nothing to undo.)
3. **Give a dependent a real undo.** `removeOffer` returns `Mono.empty()`. Change
   `proposeOffer` so its compensation calls a new stub method that records a removed
   offer, then make a *third* step fail and assert both the root and the offer are
   compensated. This proves the engine compensates *all* completed steps, in reverse.
4. **Trace a step event.** Add an `@EventListener` (Chapter 11) for the
   `offer.proposed` step-event type and assert, on the happy path, that it fired
   exactly once. Then run the compensation test and confirm it did *not* fire — the
   offer step never completed, so its `@StepEvent` never emitted.
5. **Choose the pattern.** For each of these operations, decide Saga, Workflow, or
   TCC and justify it in one sentence: (a) reserve seats, charge a card, issue
   tickets; (b) send a welcome email, then a follow-up; (c) debit one account and
   credit another across two core services.

## Where to go next

You now have the domain tier's hardest capability: a distributed transaction that
either completes or cleanly undoes itself, verified without a single downstream
service running. But the saga's steps still call the *stub* `LoanOriginationClient`.
The next chapters replace that seam with the generated core SDK and wire the saga's
commands to real HTTP calls against the core system of record you built earlier — at
which point `removeLoanApplication` deletes a real row in a real service, and the
compensation you proved here protects production data, not a stub's list.
