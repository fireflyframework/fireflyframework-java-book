Start here with a plain admission: Lumen Lending does not use event sourcing, and
this chapter is optional. The origination slice you have built persists state the
ordinary way — a `loan_application` row updated in place (Chapter 8), a rich
aggregate that owns its lifecycle (Chapter 9), commands and queries over a bus
(Chapter 10), and a saga that compensates when a step fails (Chapter 18). That is
classic CRUD with orchestration, and for origination it is exactly right. Nothing in
this chapter changes that code, and there is no companion test to run.

So why a chapter at all? Because Firefly ships an event-sourcing capability, and a
real lending platform has at least one place that wants it: the **ledger**. When the
question is not "what is the balance now?" but "prove how the balance got here, entry
by entry, and let me reconstruct it at any past instant," CRUD's habit of overwriting
the previous value is the wrong default. Event sourcing keeps the *events* — the
facts that happened — as the source of truth, and derives state by replaying them. An
auditor can never ask CRUD "what did this row say last Tuesday?"; event sourcing
answers that by construction.

This chapter teaches the framework's event-sourcing model against a clearly-labeled
*illustrative* side-example: a `Ledger` aggregate of accounts and postings. None of
its code lives in the reactor — every snippet here is a standard fenced block, not a
verified listing — so read it as a map of where each piece plugs in, not as a slice
you can `mvn test`. When you reach for it, you will build it the way these sketches
show.

!!! warning "Illustrative chapter — no verified listings"
    Unlike the rest of the book, the code here is **not** a verbatim slice of the
    companion reactor and is not checked by continuous integration. The origination
    slice is CRUD plus a saga; event sourcing is an absent capability taught as
    how-it-works. The class and annotation names match Firefly's event-sourcing
    module, but the `Ledger` example is yours to build, not Lumen's to ship.

## When to reach for event sourcing (and when not to)

The decision is not aesthetic; it follows from what the data is *for*. Use the list
below as a triage, then trust the default it implies.

Reach for **event sourcing** when:

- The **history is the product.** A ledger, a balance, a position, an audit log — the
  sequence of changes carries regulatory or business weight, and "the current value"
  is a derived convenience, not the truth.
- You must **reconstruct past state.** Disputes, restatements, and audits all ask
  "what did this look like at time *T*?" Replaying events to a point answers it
  exactly; an overwritten row cannot.
- You need **temporal analytics or what-if.** Because every change is retained, you
  can build new read models retroactively — project a metric you did not think to
  capture, over events that already happened.
- **Concurrent writers contend** over one entity and you want optimistic,
  version-based conflict detection rather than pessimistic locks.

Stay with **CRUD** (the origination default) when:

- The entity has a **lifecycle but not a meaningful history** — a loan application
  moves `DRAFT` to `APPROVED`, and the *current* status is what every caller wants.
  The `updated_at` column and an event published on transition are plenty.
- The team is **small and the domain is young.** Event sourcing adds real machinery —
  an event store, projections, snapshots, upcasters — and you pay that cost up front.
- You do not need to **answer historical questions** that a normal audit table cannot
  already answer.

!!! note "Key term — event sourcing"
    **Event sourcing** stores an entity's state as an append-only sequence of
    immutable **domain events** rather than as a mutable row. Current state is *not*
    stored; it is computed by replaying the events through the aggregate. The events
    are the system of record; any table you can query is a derived **projection** that
    you may rebuild at will from the events.

!!! spring "Spring parity"
    There is no plain-Spring "event-sourcing starter." In a vanilla Spring Boot app
    you would assemble this yourself — a table of events, a JSON serializer, an
    optimistic-locking check, a projection job, a snapshot strategy — and every team
    would do it a little differently. That is precisely the enterprise tax from
    Chapter 1. Firefly's contribution here is the *same* assembly, wired once, behind
    a reactive `EventStore` and an `AggregateRoot` base class, so the fleet's
    event-sourced services agree on the hard parts.

## The aggregate as a fold over events

In Chapter 9, `LoanApplication` changed its own state directly: `approve()` set
`status` to `APPROVED`. An event-sourced aggregate works differently. A command method
does not mutate state — it *decides which event happened* and emits it. State then
changes only as a side effect of **applying** that event, in an `on(...)` handler. The
aggregate's current state is, quite literally, the left fold of all its events through
those handlers.

Here is the `Ledger` aggregate's account as a sketch. Note the two halves: the
command method (`deposit`) validates and raises an event; the `on(...)` handler is the
*only* code that touches a field.

```java
// ILLUSTRATIVE — not in the reactor.
public class Account extends AggregateRoot<String> {

    private String accountId;
    private long balanceMinorUnits;   // current state, derived from events

    // --- command: decide, then raise an event (no field is set here) ---
    public void deposit(long amount, String reference) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Deposit must be positive");
        }
        // raise() appends the event to the aggregate's uncommitted changes
        // and immediately routes it through the matching on(...) handler.
        raise(new FundsDeposited(accountId, amount, reference));
    }

    public void withdraw(long amount, String reference) {
        if (amount > balanceMinorUnits) {
            throw new InsufficientFundsException(accountId, amount, balanceMinorUnits);
        }
        raise(new FundsWithdrawn(accountId, amount, reference));
    }

    // --- on(...) handlers: the ONLY place state mutates ---
    @EventSourcingHandler
    public void on(AccountOpened event) {
        this.accountId = event.accountId();
        this.balanceMinorUnits = 0L;
    }

    @EventSourcingHandler
    public void on(FundsDeposited event) {
        this.balanceMinorUnits += event.amount();
    }

    @EventSourcingHandler
    public void on(FundsWithdrawn event) {
        this.balanceMinorUnits -= event.amount();
    }
}
```

The discipline is strict and worth internalizing: **invariants live in the command,
state lives in the `on(...)` handler.** `withdraw` refuses an overdraft *before*
raising an event, because an event is a fact that already happened and a fact cannot be
un-happened. The `on(FundsWithdrawn)` handler never re-validates — it trusts that any
event in the stream was legal when raised. That split is what makes replay safe: to
rebuild an account from history, the framework constructs a blank `Account` and feeds
every stored event through `on(...)` in order, with no command logic running at all.

!!! note "Key term — `on(...)` handler dispatch"
    An **`on(...)` handler** (here annotated `@EventSourcingHandler`) applies one event
    type to the aggregate's in-memory state. `AggregateRoot` dispatches each event to
    the handler whose parameter matches the event's type. During normal operation a
    handler runs once, right after `raise(...)`; during reconstruction it runs once per
    historical event. Because handlers only assign fields and never validate, replaying
    a million events is deterministic and side-effect-free.

!!! warning "Never put a guard in an `on(...)` handler"
    It is tempting to re-check the overdraft rule inside `on(FundsWithdrawn)`. Do not.
    Validation belongs in the command, which runs once against live state. A guard in
    the handler runs again on *every replay* — and the day you tighten the rule, every
    historical account that was once legal fails to load. The handler's only job is to
    fold a known-good event into state.

## Domain events as the source of truth

The events are the whole point, so they get first-class treatment: each is an
immutable record, carries everything needed to reconstruct it, and is marked so the
framework can serialize, version, and route it.

```java
// ILLUSTRATIVE — not in the reactor.
@DomainEvent(type = "ledger.funds-deposited", revision = 1)
public record FundsDeposited(
        String accountId,
        long amount,
        String reference) {
}
```

Two properties make a record like this durable. First, it is **immutable** — a
`record` with no setters — because a stored fact must never change. Second, the
`@DomainEvent` annotation gives it a stable *logical type* (`ledger.funds-deposited`)
and a *revision*. The logical type decouples the event's identity from its Java class
name, so you can refactor the package or rename the class without orphaning years of
stored events; the store reads and writes the string, not the fully-qualified class
name. The `revision` number is what makes schema evolution tractable later in this
chapter.

!!! note "Key term — `@DomainEvent`"
    `@DomainEvent` marks a record as a serializable, versioned fact in an aggregate's
    history. Its `type` is the stable identifier persisted alongside each event row;
    its `revision` records which schema version produced it. Together they let the
    event store deserialize an event written years ago into whatever the current code
    expects — provided you supply an upcaster for any revision gap.

## The reactive EventStore and optimistic concurrency

The aggregate produces events; something must durably append them and read them back.
That something is the `EventStore` — a reactive port, with an R2DBC adapter,
`R2dbcEventStore`, that persists events to a relational database without ever blocking
the event loop. Its surface is small and entirely `Mono`/`Flux`, exactly the reactive
model from Chapter 5.

```java
// ILLUSTRATIVE — the shape of the port, not in the reactor.
public interface EventStore {

    /** Append new events for an aggregate, asserting its current version. */
    Mono<Void> append(String aggregateId,
                      long expectedVersion,
                      List<DomainEventEnvelope> events);

    /** Stream an aggregate's events in order, for reconstruction. */
    Flux<DomainEventEnvelope> readStream(String aggregateId);
}
```

The load-bearing parameter is `expectedVersion`. Every aggregate carries a **version**
— the count of events in its stream — and each append asserts the version it expected
to be writing on top of. The store checks that the stored version still matches and
*atomically* bumps it as it appends. If two writers loaded the same `Account` at
version 7, both computed a withdrawal, and both tried to append "the 8th event," only
the first succeeds; the second's `append` fails because the stream is already at
version 8. That is **optimistic concurrency**, enforced at the event store rather than
with a database lock.

A repository ties it together. Loading folds the stream into a fresh aggregate; saving
appends the uncommitted events at the version the aggregate was loaded at.

```java
// ILLUSTRATIVE — not in the reactor.
public Mono<Account> load(String accountId) {
    return eventStore.readStream(accountId)
            .reduce(new Account(), (account, envelope) -> {
                account.replay(envelope.event());   // routes through on(...)
                return account;
            });
}

public Mono<Void> save(Account account) {
    return eventStore.append(
            account.getId(),
            account.getBaseVersion(),               // version when loaded
            account.getUncommittedEvents())         // events raised since
        .doOnSuccess(v -> account.markCommitted());
}
```

A failed `append` surfaces as an `onError` you handle the reactive way — typically
`retryWhen` with a reload, so the loser of a race reloads at version 8, re-decides its
withdrawal against the now-current balance, and appends as version 9. The aggregate's
invariant (no overdraft) is re-checked on that reload, which is exactly why you want
the guard in the command and not the handler.

!!! note "Key term — optimistic concurrency via aggregate versioning"
    Each aggregate's stream has a monotonically increasing **version** equal to its
    event count. An append carries the version it read; the store commits only if that
    is still the latest, and rejects the write otherwise. No row is locked between read
    and write — conflicts are *detected* at commit, not *prevented* by blocking — which
    keeps the reactive pipeline non-blocking and lets honest contention retry cleanly.

!!! warning "An event store needs a serialization contract, not raw Java objects"
    Events outlive the code that wrote them. The store persists each event as its
    `@DomainEvent` `type` plus a serialized payload (JSON), never a Java-serialized
    blob keyed by class name. If you skip the logical type and lean on the
    fully-qualified class name, the first package rename makes years of history
    unreadable. Treat the event schema as a published contract from day one.

## Projections and checkpoints

Replaying every event to answer "what is the balance?" is correct but not how you
serve a query at scale. You build a **projection**: a read-optimized table — say
`account_balance(account_id, balance, updated_at)` — kept up to date by consuming the
event stream and applying each event to the table. The aggregate is the write model;
the projection is the read model, and they are deliberately separate (this is the CQRS
split from Chapter 10, now with events as the seam).

A `ProjectionService` consumes events and maintains a **checkpoint** — the position in
the global event stream it has processed up to — so that on restart it resumes exactly
where it left off rather than reprocessing history or skipping events.

```java
// ILLUSTRATIVE — not in the reactor.
@Component
public class AccountBalanceProjection {

    @ProjectionHandler                       // invoked per event, in order
    public Mono<Void> on(FundsDeposited event, ProjectionContext ctx) {
        return balanceTable.add(event.accountId(), event.amount())
                .then(ctx.checkpoint());     // advance the checkpoint atomically
    }

    @ProjectionHandler
    public Mono<Void> on(FundsWithdrawn event, ProjectionContext ctx) {
        return balanceTable.subtract(event.accountId(), event.amount())
                .then(ctx.checkpoint());
    }
}
```

Two properties of checkpoints earn their keep. First, **resumability**: a projection
that crashes after event 4,000,000 restarts and asks the store for events after its
checkpoint, not from zero. Second, **rebuildability**: reset the checkpoint to the
start and the same code rebuilds the table from scratch — which is how you add a brand
new read model months later, over events that already happened, or repair a projection
corrupted by a bug. Because the events are the source of truth, a projection is always
disposable and always reconstructable.

!!! note "Key term — projection and checkpoint"
    A **projection** is a derived read model built by applying events to a query-shaped
    store; it holds no truth of its own and can be dropped and rebuilt. A
    **checkpoint** is the persisted stream position a projection has consumed up to,
    advanced atomically with each applied event so processing is resumable and
    exactly-once in effect. Many projections can consume the same stream at independent
    checkpoints.

!!! spring "Spring parity"
    In plain Spring you would hand-roll this as a scheduled poller or a Kafka consumer
    that tracks an offset in a side table, and you would get the atomic
    "apply-and-advance" subtly wrong at least once. Firefly's `ProjectionService` makes
    the checkpoint a first-class, transactional concept, so the fleet's read models
    share one resumable, rebuildable mechanism instead of each service improvising an
    offset table.

## The transactional outbox

Here is the failure that haunts naive event-driven systems: you append the event to
the store *and* you want to publish it to Kafka so other services react. Do those as
two separate operations and a crash between them either loses the publish (event
stored, never announced) or duplicates it (published, then the store write rolls
back). The **transactional outbox** closes that gap.

The idea is simple and the framework wires it: in the *same database transaction* that
appends the event to the store, you insert a row into an `outbox` table. The commit is
atomic — either both the event and its outbox row land, or neither does. A separate
**relay** then reads the outbox and publishes to the broker, marking each row sent. If
the relay crashes mid-flight, it re-publishes on restart; downstream consumers dedupe
by event ID. You get *at-least-once* delivery with no lost events, without a
distributed transaction across the database and the broker.

```text
   append() transaction
   ┌──────────────────────────────┐
   │  INSERT event   (event_store)│   ── both, or neither ──►  COMMIT
   │  INSERT row     (outbox)     │
   └──────────────────────────────┘
                  │
                  ▼  (separate, asynchronous)
        Outbox relay  ──►  Kafka topic  ──►  other services
                  │
                  └─ mark outbox row as published
```

The payoff is that "save my events" and "tell the world" become a single atomic fact
from the writer's point of view. The aggregate's command finishes when the local
transaction commits; propagation is the relay's problem, and the relay can be as slow,
retried, or restarted as it needs to be without ever losing or fabricating an event.

!!! note "Key term — transactional outbox"
    The **transactional outbox** records "this event must be published" in the same
    local transaction that persists the event, then relays it to the broker
    out-of-band. It trades the impossible (an atomic commit spanning a database and a
    message broker) for the achievable (an atomic local commit plus at-least-once
    relay), eliminating the lost-update and phantom-publish races of dual writes.

!!! spring "Spring parity"
    This is the same pattern Spring teams reach for with libraries like Debezium or a
    hand-built relay — but assembling it correctly (the atomic insert, the relay's
    idempotency, the dedup contract) is fiddly and easy to get wrong. Firefly folds the
    outbox into the event-store append so publishing is a property of saving, not a
    second thing you must remember to do.

## Snapshots

Replay is elegant until a stream is long. An account open for ten years with daily
postings has thousands of events, and folding all of them on every load is wasteful.
A **snapshot** is the cure: periodically, the framework serializes the aggregate's
*current* state and stores it tagged with the version it represents. On the next load,
the store reads the latest snapshot, then replays only the events *after* that
version.

```text
   Without snapshots:   replay events 1 ............................ 5000   (slow)

   With a snapshot @ 4900:
        load snapshot (state @ 4900)  +  replay events 4901 .. 5000  (fast)
```

A snapshot is purely an **optimization, never a source of truth.** The events remain
authoritative; a snapshot is a cached fold you could delete and regenerate at any time.
That distinction matters: if a snapshot is ever suspect — say a serialization bug — you
discard it and replay from an earlier snapshot or from zero, and you get the same
state, because the events never lied. You typically configure the cadence (every N
events, or on a schedule) as a `firefly.*` property and never write snapshot logic by
hand.

!!! note "Key term — snapshot"
    A **snapshot** is a stored, versioned copy of an aggregate's derived state, used to
    shortcut reconstruction: load the snapshot, then replay only the events after its
    version. It is a performance cache, not a record of truth — deletable and
    regenerable from the authoritative event stream at any time.

!!! warning "A snapshot is a cache, not the truth — keep the events forever"
    Two temptations to resist. First, do not treat the snapshot as the truth and start
    pruning old events "because we have a snapshot" — the moment you delete events you
    forfeit replay, audit, and retroactive projections, the very reasons you chose
    event sourcing. Second, version every snapshot: if the aggregate's state shape
    changes, an old snapshot must be ignored (and regenerated by replay), not
    deserialized into the new shape.

## Upcasting for schema evolution

Events are immutable and they live forever, which raises the obvious question: what
happens when the *shape* of an event must change? Suppose `FundsDeposited` revision 1
had no `reference` field and you add one as required in revision 2. Millions of
revision-1 events sit in the store with no `reference`. You cannot rewrite history, and
you do not want the loader to choke on the old shape.

The answer is an **upcaster**: a small, pure function that transforms an event from one
revision to the next *as it is read*, before it reaches your `on(...)` handler. The
store deserializes the revision-1 payload, runs it through the upcaster chain to the
current revision, and hands your aggregate only the current shape. The old events on
disk never change; the upcaster is the lens that reads them forward.

```java
// ILLUSTRATIVE — not in the reactor.
@EventUpcaster(type = "ledger.funds-deposited", fromRevision = 1, toRevision = 2)
public class FundsDepositedV1ToV2 implements Upcaster {

    @Override
    public ObjectNode upcast(ObjectNode payload) {
        // revision 1 had no "reference"; supply a safe default for old events.
        if (!payload.has("reference")) {
            payload.put("reference", "LEGACY-UNREFERENCED");
        }
        return payload;
    }
}
```

Upcasters **chain**: if you reach revision 4, an event written at revision 1 passes
through the 1-to-2, 2-to-3, and 3-to-4 upcasters in turn, each a tiny, independently
testable step. Because they run on read and never touch stored data, they are safe to
deploy and trivial to unit-test — feed in an old payload, assert the new shape. This is
how an event-sourced system evolves its schema over years without a single destructive
migration.

!!! note "Key term — upcasting"
    **Upcasting** transforms a stored event from an older revision to the current one
    at read time, so aggregates and projections always see the latest shape while the
    historical events remain byte-for-byte unchanged. Upcasters are pure functions
    keyed by event `type` and revision, applied in a chain, and they are the mechanism
    by which immutable history coexists with an evolving schema.

!!! warning "Upcast forward only — never edit events in place"
    The cardinal rule of event sourcing is that stored events are immutable. The wrong
    fix for a schema change is a migration script that rewrites old event rows; the
    right fix is an upcaster that reads them forward. Rewriting history forfeits the
    audit guarantee that justified event sourcing in the first place — and a bug in the
    rewrite is unrecoverable, because the original facts are gone.

## What you learned {.recap}

- Lumen Lending's origination slice is **CRUD plus a saga**, not event sourcing, and
  this optional chapter changed none of it. Event sourcing is the right tool when
  **history is the product** — a ledger, a balance, an audit-critical position — and
  CRUD remains the right default for an entity whose *current* state is all anyone
  needs.
- An event-sourced **`AggregateRoot`** decides events in command methods (`deposit`,
  `withdraw`) and mutates state *only* in `on(...)` handlers. Current state is the fold
  of all events; invariants live in the command, never in the handler, so replay is
  deterministic.
- Events are immutable **`@DomainEvent`** records with a stable logical `type` and
  `revision`. The reactive **`EventStore`** (`R2dbcEventStore`) appends and streams
  them, enforcing **optimistic concurrency** by asserting the aggregate's version on
  every append.
- A **`ProjectionService`** builds read models from the stream and tracks a
  **checkpoint** so processing is resumable and read models are rebuildable. The
  **transactional outbox** publishes events atomically with the local append, trading
  an impossible distributed transaction for an at-least-once relay.
- **Snapshots** shortcut replay of long streams and are a cache, never truth;
  **upcasters** transform old events forward at read time, so an immutable, audited
  history coexists with a schema that evolves over years.

## Try it yourself {.exercises}

These exercises are pen-and-paper or scratch-buffer design work — there is no reactor
test to run, because the origination slice does not event-source. The goal is to think
in events.

1. **Sketch the ledger aggregate.** On paper, list the events a `Ledger` account
   emits: `AccountOpened`, `FundsDeposited`, `FundsWithdrawn`, maybe `AccountFrozen`.
   For each, write the `on(...)` handler in one line — exactly which field it mutates —
   and confirm none of them validate anything.
2. **Put the guard in the right place.** Write the `withdraw` command and the
   `on(FundsWithdrawn)` handler. Mark which one rejects an overdraft and explain, in a
   sentence, why putting that check in the handler would break replay the day you
   change the overdraft rule.
3. **Replay a stream by hand.** Given the event sequence `AccountOpened`,
   `FundsDeposited(500)`, `FundsWithdrawn(200)`, `FundsDeposited(50)`, fold them
   through your handlers and state the final balance. Now write down the aggregate's
   *version* after each event — this is what an `append` would assert.
4. **Design one projection.** Specify an `account_statement` read model (one row per
   posting) and name the checkpoint it advances. Then describe, in two sentences, the
   procedure to rebuild it from scratch six months after launch, and why that is even
   possible.
5. **Write an upcaster on paper.** Suppose `FundsWithdrawn` revision 1 stored `amount`
   as a `double` and revision 2 stores it as a `long` of minor units. Sketch the
   `@EventUpcaster` from revision 1 to 2, and state why editing the stored revision-1
   rows instead would be the wrong fix.

## Where to go next

If event sourcing felt like a lot of machinery for the origination slice, that is the
honest takeaway: you reach for it when an audit-critical history justifies the cost,
and you stay with CRUD otherwise. Chapter 13 returns to the path Lumen actually walks,
externalizing decision logic with the **rule engine** — the next capability the
origination flow uses to keep policy out of hand-written conditionals.
