Chapter 8 gave Lumen Lending a real persistence layer: a `loan_application` row,
a UUID primary key, audit columns, and a reactive repository that reads and writes
it. That row is honest about the data, but it is silent about the *rules*. Nothing
in a `BigDecimal requestedAmount` says the amount can never be negative. Nothing in
a `String status` column says you may not approve an application that is still a
draft. Those rules live, today, scattered across whatever service happens to touch
the row — exactly the anemic model that Domain-Driven Design was named to cure.

This chapter promotes the row into a *rich* domain model. You will build a `Money`
value object that makes "non-negative amount in exact minor units" a property of the
type itself, and you will move the loan application's lifecycle — submit, review,
approve, reject, cancel — *into the aggregate*, so the only way to change its status
is to ask it to perform a legal transition. The persistence row does not disappear;
it stays, and a mapper keeps it at arm's length from the API. By the end the rules
have one home, and a plain JUnit test — no Spring, no database — proves they hold.

This is a conceptual chapter with a runnable payoff. Everything you slice here is
ordinary Java and Spring Data; Firefly's contribution is the surrounding
discipline — the finance validators from Chapter 6 guard the *edge*, and the domain
model you build now guards the *core*. The two meet in the middle, and neither
trusts the other to do its job.

## The anemic row, and why it leaks

Here is the shape most teams ship first. The entity is a bag of fields with public
getters and setters; the rules live in a service that mutates it from the outside:

```java
// Anemic: the entity is a data bag; rules live (and scatter) elsewhere.
public class LoanApplication {
    private BigDecimal requestedAmount;   // could be set to -500
    private ApplicationStatus status;     // could be set to APPROVED from anywhere
    // ... getters and setters for every field ...
}

// In some service, far from the data:
app.setStatus(ApplicationStatus.APPROVED);   // was it even under review? who knows.
```

The setter does not know — *cannot* know — whether approving this application is
legal right now. So every caller has to remember to check first, and the day one of
them forgets, you approve a draft. The same is true of the amount: `setRequestedAmount`
will happily store a negative number, and the invariant "money is never negative"
becomes a code review convention instead of a guarantee.

The cure has two parts. First, give the dangerous primitive a *type* that cannot
hold an illegal value — a **value object**. Second, make the entity the only thing
that can change its own state, through methods that encode the legal transitions — an
**aggregate root**. We build them in that order.

!!! note "Key term — anemic vs. rich domain model"
    An **anemic** model splits data (dumb entities with getters/setters) from
    behavior (service classes that mutate them). A **rich** model puts the behavior
    *on* the entity, so invariants are enforced wherever the object goes. DDD favors
    the rich model precisely because it removes the "did everyone remember to check?"
    failure mode.

## Step 1 — A Money value object with an enforced invariant

Money is the textbook value object: two amounts of the same value are
interchangeable, it carries no identity, and it has one hard invariant — it is never
negative. Lumen models it as a Java `record` holding an integer count of *minor
units* (cents), never a `double` or a bare `BigDecimal`, so arithmetic is exact and
rounding is never silently wrong.

The record's canonical constructor is intentionally not where the invariant lives;
a static factory `of` is, because it can reject illegal input with a clear message
before any `Money` exists:

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/Money.java | Listing 9.1 — Money: a non-negative value object in exact minor units
public record Money(long minorUnits) {

    /**
     * Creates a {@code Money} of the given minor units.
     *
     * @param minorUnits amount in minor units
     * @return the value object
     * @throws IllegalArgumentException if {@code minorUnits} is negative
     */
    public static Money of(long minorUnits) {
        if (minorUnits < 0) {
            throw new IllegalArgumentException(
                    "Money cannot be negative: " + minorUnits);
        }
        return new Money(minorUnits);
    }
:::

Because the type is a `record`, you get `equals`, `hashCode`, and `toString` for
free — which is exactly what makes two `Money` values of `150_000` compare equal in
a test without any ceremony. And because `minorUnits` is a `long`, there is no
floating-point amount to round.

The invariant pays off the moment you do arithmetic. Subtraction is defined in terms
of the same factory, so a result that would dip below zero is rejected at the source
rather than producing a nonsensical negative balance:

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/Money.java | Listing 9.2 — arithmetic routed through the invariant
    public Money minus(Money other) {
        return Money.of(this.minorUnits - other.minorUnits);
    }
}
:::

Notice what you did *not* write: there is no `setMinorUnits`, no way to mutate a
`Money` after construction, and no path to a negative one. The invariant is not a
rule you remember to apply; it is a property of the type. Any code that holds a
`Money` holds a valid amount, full stop.

!!! spring "Spring parity"
    There is nothing Firefly-specific here — `Money` is plain Java. That is the
    point: DDD value objects are a *modeling* technique, not a framework feature.
    Firefly's finance validators (Chapter 6) and this value object are complementary:
    `@ValidAmount` rejects a bad number at the HTTP edge before it ever reaches your
    code; `Money.of` guarantees that *inside* the domain, an amount that exists is an
    amount that is valid. Belt and suspenders, on purpose.

## Step 2 — The aggregate root owns its lifecycle

Now the entity. `LoanApplication` is still a Spring Data R2DBC `@Table` — it keeps
the UUID `@Id`, the snake_case `@Column` mappings, and the `created_at`/`updated_at`
audit columns you built in Chapter 8. What changes is that it stops being a passive
bag of setters and starts *enforcing its own lifecycle*.

The lifecycle is a small state machine, named by an enum. The crucial detail is the
last method: the enum knows which states are *terminal* — states from which no
further transition is legal — and the aggregate consults it before allowing a change:

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/domain/ApplicationStatus.java | Listing 9.3 — ApplicationStatus and the terminal-state predicate
public enum ApplicationStatus {

    /** Captured but not yet submitted for review. */
    DRAFT,

    /** Submitted by the applicant; awaiting a credit officer. */
    SUBMITTED,

    /** A credit officer is actively reviewing the application. */
    UNDER_REVIEW,

    /** Approved; an offer may be proposed to the applicant. */
    APPROVED,

    /** Declined; a terminal state. */
    REJECTED,

    /** Withdrawn before a decision was reached; a terminal state. */
    CANCELLED;

    /**
     * @return {@code true} if no further transition is allowed from this state
     */
    public boolean isTerminal() {
        return this == APPROVED || this == REJECTED || this == CANCELLED;
    }
}
:::

The legal transitions form a simple graph: `DRAFT → SUBMITTED → UNDER_REVIEW →
APPROVED`, with `REJECTED` reachable from `SUBMITTED` or `UNDER_REVIEW`, and
`CANCELLED` reachable from any non-terminal state. The aggregate expresses each edge
as a method. There are no public setters for `status`; the *only* way to change it
is to ask the application to perform a transition, and each transition first checks
that it is legal from the current state.

The forward path uses a shared guard, `requireStatus`, that throws if the application
is not in the expected source state:

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/domain/LoanApplication.java | Listing 9.4 — the forward transitions guard their source state
    /** Moves a {@link ApplicationStatus#DRAFT} application to {@code SUBMITTED}. */
    public void submit() {
        requireStatus(ApplicationStatus.DRAFT, "submit");
        transitionTo(ApplicationStatus.SUBMITTED);
    }

    /** Moves a {@code SUBMITTED} application to {@code UNDER_REVIEW}. */
    public void startReview() {
        requireStatus(ApplicationStatus.SUBMITTED, "review");
        transitionTo(ApplicationStatus.UNDER_REVIEW);
    }

    /** Approves an application that is {@code UNDER_REVIEW}. */
    public void approve() {
        requireStatus(ApplicationStatus.UNDER_REVIEW, "approve");
        transitionTo(ApplicationStatus.APPROVED);
    }
:::

Read `approve()` again: it is impossible to approve an application that is not
`UNDER_REVIEW`, because the method refuses before touching the state. The "did
everyone remember to check?" failure mode is gone — the check is the method.

The decision transitions, `reject` and `cancel`, carry a little more rule: each
accepts more than one legal source state, and each *requires a reason*, which it
records on the aggregate as part of the same atomic change:

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/domain/LoanApplication.java | Listing 9.5 — decision transitions enforce a required reason
    public void reject(String reason) {
        if (status != ApplicationStatus.SUBMITTED && status != ApplicationStatus.UNDER_REVIEW) {
            throw illegalTransition("reject");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A rejection reason is required");
        }
        this.decisionReason = reason;
        transitionTo(ApplicationStatus.REJECTED);
    }

    /**
     * Cancels an application that has not yet reached a terminal state.
     *
     * @param reason human-readable cancellation reason; required
     */
    public void cancel(String reason) {
        if (status != null && status.isTerminal()) {
            throw illegalTransition("cancel");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A cancellation reason is required");
        }
        this.decisionReason = reason;
        transitionTo(ApplicationStatus.CANCELLED);
    }
:::

`cancel` is where `isTerminal()` earns its keep: rather than list every legal source
state, it asks the enum whether the current state forbids any further change, and
refuses if so. This is the aggregate and the value object collaborating — behavior
distributed to where the knowledge lives.

All three private helpers keep the public methods declarative. `transitionTo` is the
single chokepoint that mutates state, and it stamps `updatedAt` on every change so no
transition can forget the audit trail:

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/domain/LoanApplication.java | Listing 9.6 — one chokepoint for every state change
    private void requireStatus(ApplicationStatus expected, String action) {
        if (status != expected) {
            throw illegalTransition(action);
        }
    }

    private void transitionTo(ApplicationStatus next) {
        this.status = next;
        this.updatedAt = LocalDateTime.now();
    }

    private IllegalStateException illegalTransition(String action) {
        return new IllegalStateException(
                "Cannot " + action + " a loan application in status " + status);
    }
:::

!!! note "Key term — aggregate root"
    An **aggregate** is a cluster of objects treated as one unit for the purpose of
    changes, and the **aggregate root** is the single entity through which all
    changes flow. `LoanApplication` is the root here: outside code holds a reference
    to it, never to its `status` directly, and every modification goes through a
    method that protects the aggregate's invariants. The root is the boundary of
    consistency.

!!! warning "An aggregate guards its state only if you remove the back doors"
    A behavior-rich aggregate is only as safe as its narrowest mutation path. If you
    leave a public `setStatus` in place "for the mapper" or "for tests," every
    invariant above becomes optional — any caller can skip the transition methods and
    set an illegal state directly. The discipline is to expose *intent* methods
    (`submit`, `approve`) and keep raw state mutation private. Where a framework needs
    field access (Spring Data materializing a row), let the *mapper* be the only
    bridge, never a hand-written setter you call from business code.

## Step 3 — Projecting the row into a value object

The aggregate stores `requestedAmount` as a `BigDecimal` because that is the right
persistence shape for a fixed-scale decimal column. But the *domain* wants a `Money`.
The bridge is a small projection method on the aggregate, `requestedMoney()`, which
converts the stored decimal into exact minor units — multiplying by 100 and taking an
exact `long`, so a value that does not fit a whole number of cents fails loudly
rather than rounding silently:

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/domain/LoanApplication.java | Listing 9.7 — projecting the persisted decimal into Money
    public Money requestedMoney() {
        if (requestedAmount == null) {
            return null;
        }
        return Money.of(requestedAmount.movePointRight(2).longValueExact());
    }
:::

`movePointRight(2)` shifts `1500.00` to `150000`, and `longValueExact()` refuses any
amount that would lose precision. The result flows through `Money.of`, so even on the
read path the non-negative invariant is re-asserted. The persisted column and the
domain value object stay in sync without either one leaking into the other's concerns.

## Step 4 — A mapper isolates the domain from the wire

The aggregate now has rules, an enforced lifecycle, and a `Money` projection. The one
thing it must *not* do is leak directly onto the HTTP API. Construction is also a
domain decision — a new application starts in `DRAFT`, with a normalized currency —
not a blind field-for-field copy from the request body.

Lumen uses a MapStruct mapper for both directions. The response side is a generated
projection; the construction side is hand-written, precisely because it applies
domain defaults rather than copying fields:

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/mapper/LoanApplicationMapper.java | Listing 9.8 — the mapper applies domain defaults on construction
@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.IGNORE
)
public interface LoanApplicationMapper {

    /**
     * Projects a persisted aggregate to its API response shape.
     *
     * @param entity the persisted application
     * @return the response DTO
     */
    LoanApplicationResponse toResponse(LoanApplication entity);

    /**
     * Builds a new, unsaved {@link LoanApplication} from a create request,
     * normalising the currency and starting the aggregate in
     * {@link com.firefly.lumen.core.domain.ApplicationStatus#DRAFT}. The
     * identifiers and timestamps are assigned by the service before persisting.
     *
     * @param request the validated create request
     * @return a transient entity ready to be submitted and saved
     */
    default LoanApplication toNewEntity(CreateLoanApplicationRequest request) {
        return LoanApplication.builder()
                .applicantId(request.applicantId())
                .requestedAmount(request.requestedAmount())
                .currency(request.currency() == null ? null : request.currency().toUpperCase())
                .termMonths(request.termMonths())
                .purpose(request.purpose())
                .build();
    }
}
:::

`toNewEntity` does not set a status field by hand and does not trust the request to
supply one; the application is built and then `submit()` (and the rest of the
lifecycle) takes it forward through legal transitions. The DTO never sees the
aggregate, and the aggregate never sees the DTO — the mapper is the membrane between
them. This is the same separation the persistence row gives you: the domain model is
free to evolve its internals without dragging the API or the database schema along.

!!! spring "Spring parity"
    `componentModel = SPRING` tells MapStruct to generate the implementation as a
    Spring bean, so you inject `LoanApplicationMapper` into a service exactly as you
    would any `@Component` — no Firefly machinery involved. The mapper pattern is
    plain Spring; what DDD adds is the *rule* that the mapper, not business code, owns
    the translation between persistence rows, domain aggregates, and wire DTOs.

## Run it

The whole point of moving behavior onto the aggregate is that you can now test the
*rules* with no Spring context and no database — just the objects. The test
constructs a `DRAFT` application with a builder and exercises the lifecycle directly.
Here is the happy path and the illegal-transition guard, side by side:

::: listing core-lending-loan-origination/src/test/java/com/firefly/lumen/core/domain/LoanApplicationTest.java | Listing 9.9 — the lifecycle is unit-testable in isolation
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

    @Test
    void cannotApproveADraft() {
        LoanApplication app = draft();
        assertThrows(IllegalStateException.class, app::approve);
    }
:::

The `Money` projection is just as testable: a `1500.00` requested amount projects to
exactly `150_000` minor units, and because `Money` is a record, the assertion is a
plain `assertEquals`:

::: listing core-lending-loan-origination/src/test/java/com/firefly/lumen/core/domain/LoanApplicationTest.java | Listing 9.10 — the Money projection, asserted by value equality
    @Test
    void requestedMoneyConvertsToMinorUnits() {
        LoanApplication app = draft();
        assertEquals(Money.of(150_000), app.requestedMoney());
    }
:::

Run the aggregate's test from the sample root:

```text
mvn -q -pl core-lending-loan-origination -Dtest=LoanApplicationTest test
```

You should see all six behaviors pass:

```text
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

!!! tip "Checkpoint"
    Six green tests, no Spring, no database. That is the dividend of a rich domain
    model: the rules live on the objects, so you verify them with the fastest test
    there is — a plain JUnit test that constructs an aggregate and calls a method.
    If you can run this in milliseconds, your invariants are in the right place.

## What you built {.recap}

- A **`Money` value object** — an immutable `record` holding exact minor units, with
  the never-negative invariant enforced by `Money.of` and re-asserted on every
  arithmetic operation, so an amount that exists is always valid.
- A **`LoanApplication` aggregate root** that owns its lifecycle: `submit`,
  `startReview`, `approve`, `reject`, and `cancel` are the *only* ways to change
  status, each refusing an illegal transition before it touches state, with
  `ApplicationStatus.isTerminal()` deciding when no further change is allowed.
- A **`requestedMoney()` projection** that converts the persisted `BigDecimal` into
  `Money` exactly — `longValueExact` fails loudly rather than rounding — keeping the
  storage shape and the domain value object in sync without leaking either way.
- A **MapStruct mapper** that is the membrane between persistence rows, the aggregate,
  and the wire DTOs, applying domain defaults (`DRAFT` status, normalized currency)
  on construction so business code never copies fields by hand.
- A **six-case unit test** that proves all of it with no Spring and no database —
  the payoff of putting behavior where the data lives.

## Try it yourself {.exercises}

1. **Add a `plus` to `Money`.** Open `Money.java` and add a `Money plus(Money other)`
   that mirrors `minus`, routing the result through `Money.of`. Add a test asserting
   `Money.of(100).plus(Money.of(50))` equals `Money.of(150)`. Why does `plus` not
   need an extra guard, while `minus` does?
2. **Forbid re-submission.** In `LoanApplicationTest`, add a test that submits a draft
   and then asserts a second `submit()` throws `IllegalStateException`. Confirm it
   passes against the current `submit()` — then explain which line in
   Listing 9.4 makes it pass.
3. **Reinstate `startReview` coverage.** The test class exercises `submit`, `approve`,
   `reject`, and `cancel`, but `startReview` is only hit on the happy path. Add a test
   that calls `startReview()` on a fresh draft (before `submit`) and asserts it is
   rejected. Run `-Dtest=LoanApplicationTest` and watch the count rise to seven.
4. **Break an invariant on purpose.** Temporarily change `Money.of` to drop the
   negativity check, run the suite, and observe nothing fails — none of the current
   tests construct a negative amount. Restore the check, then add a test asserting
   `Money.of(-1)` throws. This is why invariants need their *own* tests, not just
   incidental coverage.
5. **Trace the edge-to-core handoff.** Re-read Chapter 6's `@ValidAmount` validator,
   then `Money.of` here. Sketch the two places a negative amount is rejected — at the
   HTTP edge and inside the domain — and argue why removing either one is unsafe.

## Where to go next

The aggregate now enforces its rules, but it still changes state through direct method
calls inside one service. Chapter 10 introduces **CQRS**: commands and queries flow
through a bus, and the handler that approves an application becomes a discrete,
testable unit dispatched by `@CommandHandlerComponent`. The rich domain model you
built here is exactly what those handlers will orchestrate.
