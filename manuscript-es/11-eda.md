Chapter 10 dispatched a command through the CQRS bus and watched a handler write to
the core system of record. That handler did its job and returned. But in a real
platform, *registering a loan application* is never the end of the story — a fraud
check wants to know, a notification service wants to greet the applicant, an
analytics pipeline wants to count it, and an audit log wants to record it. The naive
fix is to have the handler call all four. Do that and the handler now knows about
fraud, notifications, analytics, and audit; add a fifth consumer and you edit the
handler again. The write and everyone who cares about it are welded together.

Event-driven architecture breaks that weld. The handler does one thing — write, then
*announce* that it wrote, by publishing a **domain event**. It does not know or care
who is listening. Consumers subscribe to the announcement and react on their own
schedule, independently, and you add or remove them without touching the producer.
This chapter builds that decoupling in Lumen's domain tier: a
`LoanApplicationRegisteredEvent` published by the register handler, and an
`@EventListener` that records every one it receives — wired through Firefly's EDA
runtime, proven by a test, with **no Kafka and no Docker**.

The capability is `firefly-eda`. Like every Firefly capability it is transport-
agnostic: the same `@EventPublisher`/`@EventListener` code runs over an in-JVM bus,
Kafka, RabbitMQ, or Postgres, chosen by configuration rather than by rewriting your
service. Lumen runs it in-JVM so the sample stays dependency-free, and along the way
you will meet the one detail that trips up everyone the first time: how the runtime
decides which listener a given event belongs to.

## The domain event is a plain record

A domain event is a *fact that already happened*, named in the past tense, carrying
just enough data for a consumer to act without calling back. It is an immutable value
object — in Java, a `record`. Lumen's event says "a loan application was registered,"
and carries the server-assigned id, the applicant's name, and the amount:

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/event/LoanApplicationRegisteredEvent.java | Listing 11.1 — the domain event is an immutable record
package com.firefly.lumen.domain.event;

import java.util.UUID;

/**
 * Domain event published when a loan application has been registered in the core
 * system of record.
 *
 * <p>This is the EDA (event-driven architecture) payload. The
 * {@link com.firefly.lumen.domain.handler.RegisterLoanApplicationHandler} publishes it
 * through the Firefly {@code EventPublisher} after the core call succeeds; any number of
 * {@code @EventListener} beans (see
 * {@link com.firefly.lumen.domain.event.LoanApplicationEventRecorder}) react to it
 * asynchronously and independently.
 *
 * @param loanApplicationId the server-assigned application id
 * @param applicantName     the primary applicant's display name
 * @param amount            the requested principal, in minor units
 */
public record LoanApplicationRegisteredEvent(UUID loanApplicationId, String applicantName, long amount) {

    /** Canonical EDA event type used as both the topic and the routing/event-type key. */
    public static final String EVENT_TYPE = "loanApplication.registered";
}
:::

There is nothing Firefly-specific in this file, and that is deliberate. A domain
event is part of your *domain*, not part of any broker. It holds no Kafka headers, no
acknowledgement callback, no transport types — just the facts. The framework wraps it
in transport metadata at the edge, which keeps your event clean enough to unit-test
with a plain `assertEquals`.

Note the `EVENT_TYPE` constant. The producer will pass it as a *logical topic name*
when it publishes — a stable string you can route on. Hold that thought; the
matching rule between producer and consumer is the subtle part of this chapter, and
the constant alone does not tell the whole story.

!!! note "Key term — domain event"
    A **domain event** is an immutable record of something that has already occurred
    in the business domain, named in the past tense (`LoanApplicationRegistered`,
    `OfferAccepted`). It is published by the component that caused the change and
    consumed by anyone who needs to react. Because it is a fact, not a request, the
    producer never waits for a reply and never learns who consumed it — which is
    exactly what lets consumers come and go freely.

## The producer publishes through EventPublisher

Now the producer. The register handler from Chapter 10 already calls the core to
create the application; the EDA addition is one step at the end of the chain. After
the core call succeeds and yields an id, the handler publishes the event — and only
then returns the id. Here is the whole handler, with the publish on the success path:

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/handler/RegisterLoanApplicationHandler.java | Listing 11.2 — the handler writes, then announces
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

Read the reactive shape carefully, because the ordering is a guarantee, not an
accident. `createLoanApplication(...)` returns a `Mono<UUID>`; `flatMap` chains the
publish *after* it and only on success — if the core call fails with `onError`, the
chain short-circuits and nothing is ever published. `publishRegistered` builds the
event and hands it to `EventPublisher.publish(event, eventType)`, which returns
`Mono<Void>`. The `.thenReturn(id)` then yields the id back as the handler's result,
so the publish is sequenced *into* the response: the handler does not complete until
the event has been accepted by the transport.

`EventPublisher` is the framework's transport-agnostic seam — a single interface
whose `publish` you call the same way no matter what sits behind it. You inject it
like any bean; the auto-configuration provides the implementation that matches your
configured transport. In Lumen that implementation delivers in-JVM, but the handler
code would not change one character to run over Kafka.

!!! note "Key term — EventEnvelope"
    Your record is the *payload*. Before it crosses a transport the runtime wraps it
    in an `EventEnvelope` — a framework record that adds the routing metadata a broker
    needs: the `destination` (topic), the `eventType`, a `transactionId` for
    correlation, a `headers` map, a `timestamp`, and the publisher/consumer transport
    type. On the consume side the envelope can also carry an acknowledgement callback.
    You rarely touch it directly; `publish(payload, eventType)` builds it for you, and
    your `@EventListener` method receives the unwrapped payload. The envelope is why
    the same clean record can travel over four different brokers unchanged.

### Publishing declaratively, with an annotation

Calling `EventPublisher.publish` by hand, as the handler does, is the explicit form —
and the clearest one to learn on, because the publish is right there in the reactive
chain. Firefly also offers a *declarative* form for the common case "publish the
result of this method." You annotate the method and the framework publishes its
return value for you, after it completes successfully:

```java
// Illustrative: publish a method's result automatically, no EventPublisher injection.
@PublishResult(
        destination = "loanApplication.registered",
        eventType = "LoanApplicationRegisteredEvent",
        publisherType = PublisherType.APPLICATION_EVENT)
public Mono<LoanApplicationRegisteredEvent> register(RegisterLoanApplicationCommand cmd) {
    return client.createLoanApplication(cmd.getApplicantName(), cmd.getAmount())
        .map(id -> new LoanApplicationRegisteredEvent(id, cmd.getApplicantName(), cmd.getAmount()));
}
```

There is also `@EventPublisher` for publishing a chosen *argument* rather than the
return value. Both are aspect-driven sugar over the same `EventPublisher` seam: the
explicit call and the annotation produce the identical envelope on the identical
transport. Lumen uses the explicit call so the publish is visible in the slice you
just read; reach for the annotations when the publish is purely "emit what I just
produced" and you would rather not thread `EventPublisher` through the method body.

## The consumer is an @EventListener bean

A consumer is any Spring bean with a method annotated `@EventListener`. The framework
discovers it at startup, registers it with the EDA runtime, and invokes it whenever a
matching event is delivered. Lumen's consumer records every event it sees into a
thread-safe list — small enough to assert against in a test, real enough to prove the
wiring:

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/event/LoanApplicationEventRecorder.java | Listing 11.3 — the consumer: an @EventListener bean
@Component
public class LoanApplicationEventRecorder {

    private final List<LoanApplicationRegisteredEvent> received = new CopyOnWriteArrayList<>();

    @EventListener(
            destinations = "LoanApplicationRegisteredEvent",
            eventTypes = "LoanApplicationRegisteredEvent",
            consumerType = PublisherType.APPLICATION_EVENT)
    public void on(LoanApplicationRegisteredEvent event) {
        received.add(event);
    }

    /** Returns an immutable snapshot of the events received so far. */
    public List<LoanApplicationRegisteredEvent> received() {
        return List.copyOf(received);
    }
}
:::

The method signature carries the contract: `on(LoanApplicationRegisteredEvent event)`
takes the unwrapped payload, so you work with your own type, not an envelope. The
`@EventListener` annotation says *which* events this method wants. `consumerType =
PublisherType.APPLICATION_EVENT` binds it to the in-JVM transport — Spring's
`ApplicationEventPublisher` — which is why the sample needs no broker. `destinations`
and `eventTypes` are the routing filters.

And here is the detail that surprises everyone. Look at `eventTypes`: it is
`"LoanApplicationRegisteredEvent"` — the payload's **simple class name** — not the
`"loanApplication.registered"` logical string the producer passes to `publish`. That
is not a typo in the sample; it is how the EDA runtime matches. Read the warning
before you write your first listener.

!!! warning "eventTypes is the payload's simple class name, not a dotted logical string"
    The EDA runtime matches a listener's `eventTypes` against the **simple class name
    of the published payload** — here `LoanApplicationRegisteredEvent`. It does *not*
    match against the `eventType` string you hand to `publish(...)` (the producer's
    `"loanApplication.registered"`). So a listener that sets `eventTypes =
    "loanApplication.registered"` to "match the producer" will silently receive
    nothing: the runtime is comparing it to `LoanApplicationRegisteredEvent` and never
    finding a match. The fix is to set `eventTypes` to the class name. This is the
    single most common EDA wiring bug, and it fails *quietly* — the producer succeeds,
    the consumer just never fires — so commit the rule to memory now: **`eventTypes` is
    the class name.**

!!! note "Key term — PublisherType"
    `PublisherType` selects the transport for a producer (`publisherType`) or consumer
    (`consumerType`). The values are `APPLICATION_EVENT` (in-JVM, over Spring's event
    bus — the default Lumen uses), `KAFKA`, `RABBITMQ`, `POSTGRES`, `NOOP` (drops
    events, useful in tests), and `AUTO` (let the framework pick by what is on the
    classpath). Switching Lumen from in-JVM to Kafka is a `PublisherType` change plus a
    dependency and a few `firefly.eda.*` properties — the `@EventPublisher`/
    `@EventListener` code is untouched. That portability is the whole reason to publish
    through the framework rather than a broker client directly.

!!! spring "Spring parity"
    Firefly's `@EventListener` is deliberately *not* Spring's
    `org.springframework.context.event.EventListener` — it is
    `org.fireflyframework.eda.annotation.EventListener`, a transport-aware superset.
    Spring's version only ever delivers in-process; Firefly's runs the same listener
    over Kafka, RabbitMQ, or Postgres by changing `consumerType`. When you bind it to
    `APPLICATION_EVENT`, it *uses* Spring's `ApplicationEventPublisher` underneath — so
    you get Spring's in-JVM mechanics for free, plus a single annotation that scales out
    to a real broker without a rewrite. Check the import when you copy a listener; the
    two annotations look identical and behave very differently.

## Run it

The EDA listener test proves the wiring end to end without a broker. It drives the
framework's `EventListenerProcessor` directly — the same component every transport
funnels delivered messages into — so the annotation discovery and method invocation
are entirely real; only the external network hop is elided:

::: listing domain-lending-loan-origination/src/test/java/com/firefly/lumen/domain/saga/LoanApplicationEventListenerTest.java | Listing 11.4 — proving the listener fires, with no broker
    @Test
    void annotatedListener_receivesDispatchedEvent() {
        var event = new LoanApplicationRegisteredEvent(UUID.randomUUID(), "Ada Lovelace", 250_000L);

        // The processor routes the payload to every @EventListener whose type matches.
        StepVerifier.create(processor.processEvent(event, Map.of()))
                .verifyComplete();

        assertThat(recorder.received()).containsExactly(event);
    }
:::

The test builds a real event, asks the processor to dispatch it, and asserts two
things: the dispatch completes cleanly (`verifyComplete()`), and the recorder
actually received exactly that event (`containsExactly(event)`). The second assertion
is the one that matters — it proves the runtime matched the payload's class name
`LoanApplicationRegisteredEvent` to the listener's `eventTypes` and invoked `on(...)`.
Producer and consumer share no reference to each other; the only link is the event
type, resolved by the framework.

Run the domain module's tests from the sample root:

```text
mvn -q -pl domain-lending-loan-origination test
```

You should see all six pass:

```text
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

The EDA listener test is one of those six, alongside the register handler and the
saga tests from earlier chapters. Six green tests means the producer publishes, the
runtime routes by class name, and the `@EventListener` fires — the entire decoupled
loop, verified in-process in under a second.

!!! tip "Checkpoint"
    Open `LoanApplicationEventRecorder` and change `eventTypes` from
    `"LoanApplicationRegisteredEvent"` to `"loanApplication.registered"` — the
    producer's logical string. Rerun `-Dtest=LoanApplicationEventListenerTest`. It now
    fails: the recorder receives nothing, because the runtime matched the payload's
    *class name* and found no listener registered under it. Read the assertion failure
    — `containsExactly` reports an empty list — then restore the class name and watch it
    pass. You just reproduced the most common EDA bug on purpose, which is the surest
    way never to ship it.

## The CQRS/EDA bridge

You have seen the explicit path: a CQRS handler injects `EventPublisher` and publishes
in its reactive chain. Firefly closes the loop between the two capabilities with two
declarative bridges, so a command handler can emit a domain event — and a query side
can react to one — without hand-wiring.

On the *write* side, `@PublishDomainEvent` lets a CQRS handler announce its result as
a domain event automatically, the same shape Listing 11.2 wrote by hand:

```java
// Illustrative: a CQRS handler that publishes its result as a domain event.
@CommandHandlerComponent
@PublishDomainEvent(
        destination = "loanApplication.registered",
        eventType = "LoanApplicationRegisteredEvent")
public class RegisterLoanApplicationHandler
        extends CommandHandler<RegisterLoanApplicationCommand, UUID> {
    // doHandle(...) returns the id; the framework publishes the event after success.
}
```

On the *read* side, `@InvalidateCacheOn` ties a cached query to the events that make
it stale, so a registration event automatically evicts the cached application list —
the read model self-heals instead of serving a snapshot the write side already
obsoleted:

```java
// Illustrative: evict a query's cache when matching events arrive.
@QueryHandlerComponent
@InvalidateCacheOn(eventTypes = "LoanApplicationRegisteredEvent")
public class GetApplicationStatusHandler
        extends QueryHandler<GetApplicationStatusQuery, ApplicationStatusView> {
    // cached results are invalidated whenever a LoanApplicationRegisteredEvent fires
}
```

Both annotations live in the CQRS module and route through the same EDA runtime you
just exercised — and both key on `eventTypes` by the **same simple-class-name rule**,
so the warning above applies here verbatim. Lumen uses the explicit `EventPublisher`
call rather than `@PublishDomainEvent` so the publish is visible in the slice; in a
larger service the declarative bridge removes that boilerplate while keeping the exact
same producer/consumer decoupling. The two capabilities — CQRS for the command/query
split, EDA for the announcement — are designed to meet here: a command changes state,
publishes a fact, and the read side adjusts, all without either half naming the other.

## What you built {.recap}

- A **`LoanApplicationRegisteredEvent`** — an immutable `record` domain event,
  past-tense and broker-free, carrying just the facts a consumer needs and a stable
  `EVENT_TYPE` logical name.
- A **producer** in `RegisterLoanApplicationHandler` that writes to the core and then
  publishes the event through the transport-agnostic `EventPublisher`, sequenced into
  the reactive chain with `flatMap` so it fires only on success.
- A **consumer**, `LoanApplicationEventRecorder`, an `@EventListener` bean bound to the
  in-JVM `PublisherType.APPLICATION_EVENT` transport — so the whole loop runs with no
  Kafka and no Docker.
- The decisive runtime rule: the EDA runtime matches a listener's `eventTypes` by the
  payload's **simple class name** (`LoanApplicationRegisteredEvent`), not by the dotted
  logical string the producer publishes with — a mismatch fails *silently*.
- A **broker-free integration test** that dispatches a real event through the
  framework's `EventListenerProcessor` and asserts the listener received it — one of
  the module's six passing tests.

## Try it yourself {.exercises}

1. **Add a second listener.** Create a new `@Component` with an `@EventListener`
   method on `LoanApplicationRegisteredEvent` (same `consumerType` and `eventTypes` as
   the recorder) that increments a counter. Add a test asserting that one dispatched
   event reaches *both* listeners. This is the payoff of EDA: the producer did not
   change, yet a new consumer reacts.
2. **Reproduce the silent-failure bug, then prove it.** Change the recorder's
   `eventTypes` to `LoanApplicationRegisteredEvent.EVENT_TYPE` (the
   `"loanApplication.registered"` string), run `-Dtest=LoanApplicationEventListenerTest`,
   and confirm it fails with an empty `received()` list. Write a one-sentence comment
   explaining why, then restore the class name.
3. **Publish through the handler.** In `RegisterLoanApplicationHandlerTest`, register a
   real `LoanApplicationEventRecorder` bean and assert that handling a
   `RegisterLoanApplicationCommand` causes the recorder to receive exactly one event
   with the id the handler returned — proving the publish in Listing 11.2 is sequenced
   correctly.
4. **Make publishing conditional.** Re-read the explicit `publishRegistered` method,
   then sketch (no need to run it) how you would only publish when the amount exceeds a
   threshold. Where does the guard go — in the handler, or could `@PublishResult`'s
   `condition` attribute express it declaratively instead?
5. **Trace a transport swap.** Without changing any `@EventPublisher` or
   `@EventListener` code, list exactly what you would change to move Lumen's events
   onto Kafka: which `PublisherType` values, which dependency, which `firefly.eda.*`
   properties. The fact that the listener and producer *code* stay untouched is the
   point of the seam.

## Where to go next

The event you published here is a *notification* — a fact other components react to,
after which it is gone. Chapter 12 takes the next step: instead of storing the current
state and announcing changes, what if the sequence of events *is* the state? **Event
sourcing** treats the append-only log of domain events as the source of truth, and the
current state as a fold over that log. The `LoanApplicationRegisteredEvent` you just
built is exactly the kind of event that, persisted rather than discarded, becomes a
complete and replayable history of every application.
