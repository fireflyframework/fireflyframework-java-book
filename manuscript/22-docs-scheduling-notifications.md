A loan-origination service does most of its work inside its own boundary —
validating, scoring, deciding, persisting. But the moments a customer actually
*feels* happen at the edges, where the service reaches out to the world: a PDF
agreement to sign, a "your loan is approved" email, a nightly job that expires
stale offers, a webhook from the payment rail, a callback to a partner's system.
Each of these is a small integration with its own failure modes, and a fleet that
hand-rolls each one ends up with a dozen subtly different retry loops, signature
schemes, and templating engines — exactly the enterprise tax Chapter 1 named.

This chapter is a tour of four Firefly capabilities that handle those edges
consistently: **documents** (rendering a PDF you can sign), **scheduling**
(durable, recoverable background work), **notifications** (email, SMS, and push
behind provider ports), and **webhooks and callbacks** (inbound ingestion and
outbound signed delivery). The aim is not to make you an expert in any one — each
has its own reference — but to give you the shape of each, the one sentence that
makes it different from the obvious alternative, and where it plugs into the Lumen
Lending you have been building.

Read this chapter as a *map*, not a tutorial. Each section follows the same
rhythm: the business moment that needs the capability, the obvious alternative and
why it falls short at fleet scale, the Firefly capability that replaces it, a small
faithful sketch of its real API shape, and the single seam in Lumen Lending where
it would attach. By the end you will know which capability to reach for, what it is
called, and which event or appendix it connects to — enough to open the right
reference and start, without having mistaken a sketch for a slice.

!!! warning "Honesty about Lumen Lending"
    None of these four capabilities appears in the companion reactor. The trimmed
    origination slice stops at offer acceptance — the proven live flow is
    `POST /api/v1/experience/lending/applications` returning `201 SUBMITTED`,
    through the saga, into core — so there is **no documents, scheduling,
    notifications, or webhook code in the sample, and no companion test for this
    chapter.** Every code block below is *illustrative*: a faithful sketch of how
    the capability is used, shaped after the real framework module's API, not a
    verbatim slice of the reactor. Where a capability connects to something the
    sample *does* build (the events from Chapter 11, the e-signature flow in
    Appendix C), the text says so explicitly, and only that connection is claimed.

## Documents — render a PDF you can sign

When an offer is accepted, the loan agreement must become a real document:
generated from the application and the accepted offer, presented for signature, and
stored as durable evidence. Firefly splits that into two responsibilities.
*Generating* the document is the job of `TemplateRenderUtil` in
`fireflyframework-utils`; *storing and signing* it is the job of the ECM ports in
Appendix C. This section covers the first half — turning data into a PDF.

### The shape of the rendering pipeline

`TemplateRenderUtil` is a FreeMarker-to-XHTML-to-PDF pipeline: you author the
agreement as a FreeMarker template (`.ftl`) that produces well-formed XHTML, feed it
a data model, and it renders a print-ready PDF with support for watermarks,
encryption, metadata, and template caching. The differentiating idea is the
*two-stage* rendering — FreeMarker first produces XHTML, which a PDF engine then
paginates — so the same template language and the same data model that drive your
notification emails also drive your legal documents.

The why behind two stages is worth a sentence. A one-stage "data straight to PDF"
API forces you to express layout in code; a two-stage pipeline lets a designer own
the XHTML/CSS while you own only the data model. The intermediate XHTML is also
inspectable — you can render the same template to HTML to debug a layout, then to
PDF for the real artifact, from one template source.

The entry point is a static utility, not an injected bean. The headline method
takes a template name and a data model and returns the rendered bytes:

```java
// Illustrative — not in the companion reactor.
byte[] agreement = TemplateRenderUtil.renderTemplateToPdfBytes(
        "loan-agreement.ftl",
        Map.of(
            "application", application,     // the approved LoanApplication
            "offer",       acceptedOffer,   // the offer the customer accepted
            "generatedAt", LocalDate.now()));
```

By default the utility resolves templates from `classpath:/templates` and then a
local `./templates` directory, caches compiled templates, and rethrows template
errors rather than swallowing them — so a typo in the `.ftl` fails loudly at render
time rather than producing a half-blank page.

### The template is ordinary FreeMarker over XHTML

The template itself is ordinary FreeMarker over XHTML — the loan terms interpolated
into a styled document body:

```html
<!-- loan-agreement.ftl (illustrative) -->
<h1>Personal Loan Agreement</h1>
<p>Borrower: ${application.applicantName}</p>
<p>Principal: ${offer.amount} ${offer.currency}</p>
<p>Term: ${offer.termMonths} months at ${offer.apr}% APR</p>
```

If you have written a templated email body you have already written this — the same
`${...}` interpolation, the same data model. That sameness is the point of the next
section's notification story too.

### Where it plugs in

Those `agreement` bytes are exactly what Appendix C's e-signature flow consumes: the
document port stores the rendered file, the e-signature ports wrap it in a signing
envelope, and the sealed proof comes back to be stored alongside it. Generation and
signing are deliberately separate concerns — you can render an agreement without
signing it, and the signature ports do not care how the bytes were produced. In
Lumen Lending the natural trigger is offer acceptance: the moment the experience
tier records an accepted offer, a document consumer renders the agreement and hands
the bytes to Appendix C's ECM ports.

!!! note "Key term — `TemplateRenderUtil`"
    A document-rendering utility in `fireflyframework-utils` that compiles a
    FreeMarker template to XHTML and then to PDF (the real entry point is the static
    `TemplateRenderUtil.renderTemplateToPdfBytes(template, model)`). It is *not* part
    of the ECM document ports; it produces the bytes that those ports store and sign.
    Because it shares FreeMarker with the notification channels below, a service has
    **one** templating story for both legal documents and customer messages.

!!! spring "Spring parity"
    Plain Spring has no opinion here — you would pick a templating library
    (FreeMarker, Thymeleaf) and a PDF engine (OpenPDF, Flying Saucer) and wire them
    together yourself, differently in each service. Firefly's contribution is the
    pre-assembled pipeline and the shared template engine, so document generation
    looks the same fleet-wide.

## Scheduling — durable, recoverable background work

Origination has work that is not triggered by a request: offers expire after a
window, a nightly batch re-scores applications whose bureau data changed, a reminder
goes out three days before a signing deadline. The reflex is Spring's `@Scheduled` —
a method that fires on a cron. That is fine for a heartbeat, but it has a sharp edge
for *business* work.

### Why plain `@Scheduled` is not enough for business work

`@Scheduled` is fire-and-forget and node-local, and that produces two failure modes
the moment the work mutates state. First, **lost work**: if the process dies
mid-run, the half-finished job is simply gone — there is no journal to resume from.
Second, **duplicated work**: if you run three replicas, the timer fires on all three,
so a "expire stale offers" job runs three times and a customer could see an offer
expired, reopened, and re-expired in a confusing flurry. Neither is acceptable when
the work changes money-shaped state.

### `@ScheduledSaga` makes the trigger durable

Firefly's answer is to make scheduled work *durable* by routing it through the same
orchestration engines you already use for foreground flows. `@ScheduledSaga` triggers
a saga (Chapter 18) on a schedule, and `@ScheduledWorkflow` triggers a workflow — so
a scheduled run gets persistence, step-level recovery, and compensation, not just a
timer. The differentiating sentence: a plain `@Scheduled` method *runs* on a
schedule, whereas a `@ScheduledSaga` *starts a recoverable, single-flighted
transaction* on a schedule, surviving restarts and coordinating across replicas.

The mechanics are worth naming precisely, because the annotations divide the labor.
`@Saga(name = ...)` (Chapter 18) defines *what* the transaction is — its steps,
their dependencies, their compensations. `@ScheduledSaga(cron = ...)` adds *when* it
fires. A scheduled saga therefore carries **both** annotations: one for identity and
recovery, one for the trigger.

```java
// Illustrative — not in the companion reactor.
@Saga(name = "expire-stale-offers")
@ScheduledSaga(
        cron = "0 0 2 * * *",            // every day at 02:00
        description = "Expire offers past their acceptance window")
public class ExpireStaleOffersSaga {

    @SagaStep(id = "find")
    public Flux<OfferId> findExpired() {
        return offers.findExpiredBefore(Instant.now());
    }

    @SagaStep(id = "expire", dependsOn = "find", compensate = "reopen")
    public Mono<Void> expire(OfferId id) {
        return offers.markExpired(id);   // each step is journaled and recoverable
    }

    public Mono<Void> reopen(OfferId id) {
        return offers.reopen(id);        // compensation if a later step fails
    }
}
```

The `@ScheduledSaga` attributes mirror Spring's own scheduling vocabulary on
purpose: alongside `cron` you can use `fixedRate`, `fixedDelay`, and `initialDelay`,
narrow the cron to a `zone`, and flip a saga off with `enabled = false` without
deleting it. The annotation is `@Repeatable`, so one saga can carry several schedules
(a fast `fixedRate` heartbeat *and* a nightly `cron`, say). What it adds over plain
Spring is everything *after* the trigger fires: the run is journaled, single-flighted
across replicas, and recoverable.

The lighter `@Scheduled` still has its place — health pings, cache warmups, metric
rollups, anything idempotent and disposable. Reach for `@ScheduledSaga` or
`@ScheduledWorkflow` the moment the work mutates business state and you would be sorry
to run it twice or lose it halfway.

!!! note "Key term — `@ScheduledSaga`"
    A type-level annotation in `fireflyframework-orchestration` that fires a saga on
    a schedule. It pairs with `@Saga(name = ...)`: the `@Saga` annotation gives the
    transaction its identity, steps, and compensations; `@ScheduledSaga` adds the
    `cron`/`fixedRate`/`fixedDelay` trigger. The pairing is the whole idea — you are
    not scheduling a *method*, you are scheduling a *recoverable transaction*.

!!! spring "Spring parity"
    `@ScheduledSaga` and `@ScheduledWorkflow` build *on* Spring's scheduling — the
    trigger is the same cron and `fixedRate`/`fixedDelay` mechanism, with the same
    expression syntax — and add durability through Firefly's saga and workflow
    runtimes. Use plain Spring `@Scheduled` for ephemeral ticks; use the Firefly
    variants when "this fired but never finished" or "this fired twice" would be a
    real incident.

## Notifications — email, SMS, and push behind ports

The same approval that generates an agreement should also reach the customer:
"You're approved," "Please sign by Friday," "Your loan is active." Firefly models
that as a set of **channel services** — email, SMS, and push — each sitting behind a
provider port, exactly like the hexagonal pattern you saw for events and ECM. You
call a channel service; an adapter selected by a `firefly.notifications.*` property
(or, concretely, by which provider starter is on the classpath —
`fireflyframework-notifications-sendgrid`, `-twilio`, `-firebase`, `-resend`) routes
the message to the vendor without your code naming any of them.

### One channel service per medium

The module's surface is one service interface per medium —
`EmailService.sendEmail(...)` / `sendTemplateEmail(...)`, `SMSService`, `PushService`
— each returning a `Mono` and each backed by a provider adapter. Programming against
the interface, not the vendor SDK, is the same one-property-swap promise as Firefly's
other ports: switching SMS vendors is an adapter on the classpath and a property,
not a rewrite of your handler.

```java
// Illustrative — not in the companion reactor.
Mono<EmailResponseDTO> sent = emailService.sendTemplateEmail(
        EmailTemplateRequestDTO.builder()
            .to(applicant.email())
            .templateId("loan-approved")        // FreeMarker template, shared engine
            .templateVariables(Map.of(
                    "name",   applicant.fullName(),
                    "amount", offer.amount()))
            .subject("Your loan is approved")
            .build());
// The provider adapter (SendGrid, Resend, ...) does the real send;
// the template is rendered by the shared FreeMarker engine before dispatch.
```

### Two details that make it more than a thin SDK wrapper

First, it shares the FreeMarker engine with document generation — the
`FreemarkerNotificationTemplateEngine` renders a templated email the same way
`TemplateRenderUtil` renders the loan agreement, so a service has one templating
story for documents and messages alike. Second, it honors **per-user channel
preferences**: a `NotificationPreferenceService` answers
`isChannelEnabled(userId, channel)`, so a customer who opted out of SMS but wants
email gets the message on the channel they chose — decided by the framework's
preference store rather than by branching in your handler.

```java
// Illustrative — choose the channel from the customer's stored preference.
Mono<Boolean> wantsSms = preferenceService.isChannelEnabled(applicant.id(), "SMS");
```

### Where it plugs in

A natural trigger for that send is an event you already publish. The
`LoanApplicationRegisteredEvent` from Chapter 11 — or a later `OfferAcceptedEvent` —
is exactly the announcement a notification consumer reacts to: an `@EventListener`
receives the fact and calls the channel service, so messaging is decoupled from the
write that caused it, the same way every other consumer is. The producer does not
change; a new consumer simply subscribes — and because Lumen Lending's EDA runs over
the in-JVM `APPLICATION_EVENT` transport, that consumer wires up with no broker.

!!! note "Key term — channel service"
    A **channel service** is the port for one delivery medium — `EmailService`,
    `SMSService`, or `PushService`. You program against the channel service and a
    provider adapter does the real send. Switching from one SMS vendor to another is
    a property change and an adapter on the classpath, not an SDK rewrite — the same
    one-property-swap promise as Firefly's other ports.

!!! spring "Spring parity"
    Spring offers `JavaMailSender` for email and nothing unified for SMS or push, so
    each service grows its own vendor clients and its own preference logic. Firefly's
    notifications module unifies the three channels behind one port-per-medium API,
    shares the templating engine with document generation, and centralizes channel
    preferences in a `NotificationPreferenceService` — so "notify the customer" is one
    call against a port, not three bespoke integrations.

## Webhooks vs callbacks — inbound ingestion vs outbound delivery

The last edge is the trickiest, because it runs both directions, and the two
directions are *not* symmetric. A **webhook** is something the outside world sends
*to* you — a payment processor telling you a disbursement settled. A **callback** is
something you send *to* the outside world — telling a broker partner that an
application reached a decision. Firefly treats them as two capabilities with
genuinely different concerns, and conflating them is the mistake the module is
designed to prevent.

### Inbound webhooks — store fast, then process

An inbound webhook arrives from a system you do not control, often with an aggressive
delivery-and-retry policy: acknowledge in a few hundred milliseconds or it retries,
sometimes redelivering the same event many times. So Firefly's inbound handling is
**two-phase**. Phase one validates the provider's signature and *durably stores* the
raw payload, then immediately returns `202 Accepted` — fast, before any business logic
runs. Phase two processes the stored event asynchronously, with idempotency keyed on
the provider's event id so a redelivery is recognized and dropped rather than applied
twice. The differentiating idea is *store-then-process*: you never make a flaky caller
wait on your business logic, and you never lose an event because processing threw.

Concretely, the module is a `WebhookController` that receives the POST and answers
`ResponseEntity.status(HttpStatus.ACCEPTED)` once the payload is queued, a
`WebhookSignatureValidator` port that each provider implements with its own HMAC
scheme (the port's own Javadoc sketches Stripe, Twilio, and GitHub validators), and a
`WebhookIdempotencyService` that de-duplicates on the provider's event id during phase
two. Your job is the phase-two business reaction:

```java
// Illustrative — not in the companion reactor.
// The framework's WebhookController has already validated the provider signature,
// stored the raw payload, returned 202 Accepted, and de-duplicated on the event id.
// You supply the phase-two business reaction:
public Mono<Void> onDisbursementSettled(DisbursementSettled event) {
    return loans.markDisbursed(event.loanId());
}
```

!!! warning "An inbound webhook must acknowledge before it processes"
    If you do the business work *before* returning `2xx`, a slow database call or a
    transient error turns into the provider retrying — and now you are processing the
    same event two, three, five times under load. The store-then-process split exists
    precisely so the acknowledgement is fast (the controller returns `202 Accepted`
    the moment the payload is stored) and the processing is idempotent. Let the
    framework store, validate, and ack; do your work in phase two.

### Outbound callbacks — sign, then deliver resiliently

An outbound callback is the mirror image. *You* are now the caller, so the burden is
on you to prove authenticity and to survive the partner being down. Firefly signs the
payload with **HMAC** so the recipient can verify it came from you and was not
tampered with, and delivers it through a **circuit-breaker**-guarded client with
retries — so a partner that is timing out trips the breaker instead of drowning your
threads, and recovers automatically when it comes back. The differentiating sentence:
an outbound callback is an HMAC-signed, circuit-broken *delivery*, not just an HTTP
POST.

```java
// Illustrative — not in the companion reactor.
Mono<Void> delivered = callbackService.send(
        Callback.builder()
            .destination(partner.callbackUrl())
            .event("application.decisioned")
            .payload(Map.of("applicationId", id, "decision", "APPROVED"))
            .build());
// The framework HMAC-signs the body, adds the signature header, and delivers
// through a circuit-breaker-guarded client with retry and backoff.
```

Put the two side by side and the asymmetry is the whole lesson. Inbound, you trust
nothing and optimize for *not losing* events you did not ask for: validate, store,
ack, then process idempotently. Outbound, you are trusted by no one and optimize for
*provable, resilient* delivery: sign, then deliver through a breaker. Same word
family, opposite postures — and the symmetry is a trap, because the retry code you
would write for one is the wrong code for the other.

### Where it plugs in

In Lumen Lending the inbound edge would be a payment-rail webhook confirming a
disbursement, landing in core to flip a loan to disbursed; the outbound edge would be
a decision callback to a broker partner, fired off the same `OfferAcceptedEvent` (or a
decision event) that the notifications consumer reacts to. Both are downstream of the
events you already publish — the EDA backbone from Chapter 11 is the spine the edges
hang off.

!!! note "Key term — webhook vs callback"
    In Firefly's vocabulary a **webhook** is *inbound* (the world calls you) and a
    **callback** is *outbound* (you call the world). They are separate capabilities
    because their problems are different: inbound is about fast acknowledgement
    (`202 Accepted`), signature validation, and idempotent redelivery; outbound is
    about HMAC signing and circuit-broken, retrying delivery. If you find yourself
    writing the same retry code on both sides, you have probably collapsed two
    different problems into one.

!!! spring "Spring parity"
    Plain Spring gives you `@RestController` for the inbound endpoint and `WebClient`
    for the outbound call, and leaves signature validation, durable storage,
    idempotency, HMAC signing, and circuit breaking entirely to you. Firefly's
    webhook and callback modules pre-wire those concerns so every service ingests and
    emits integrations the same way — the inbound `202`-then-process contract and the
    outbound signed-and-broken delivery, identical across the fleet.

## What you learned {.recap}

- **Documents** are rendered with `TemplateRenderUtil` — a FreeMarker → XHTML → PDF
  pipeline in `fireflyframework-utils`, entered through the static
  `renderTemplateToPdfBytes(template, model)` — whose bytes feed Appendix C's
  e-signature flow. Generation and signing are deliberately separate steps, and the
  natural trigger in Lumen Lending is offer acceptance.
- **Scheduling** comes in two strengths: plain Spring `@Scheduled` for ephemeral
  ticks, and Firefly's `@ScheduledSaga` / `@ScheduledWorkflow` for durable,
  single-flighted, recoverable background work that mutates business state. A
  scheduled saga carries **both** `@Saga(name = ...)` (identity, steps, compensation)
  and `@ScheduledSaga(cron = ...)` (the trigger).
- **Notifications** are channel services — `EmailService`, `SMSService`, `PushService`
  — behind provider ports, sharing the FreeMarker engine with document generation via
  `FreemarkerNotificationTemplateEngine` and honoring per-user channel preferences
  through `NotificationPreferenceService.isChannelEnabled(...)`, and they are naturally
  triggered by the domain events from Chapter 11 over the in-JVM `APPLICATION_EVENT`
  transport.
- **Webhooks and callbacks** are asymmetric: inbound webhooks *store fast, then
  process* (a `WebhookController` returns `202 Accepted`, a `WebhookSignatureValidator`
  validates the provider HMAC, a `WebhookIdempotencyService` drops redeliveries);
  outbound callbacks *sign, then deliver* (HMAC signatures over a circuit-breaker-
  guarded client). Same word family, opposite postures.
- None of this is in the companion reactor; the chapter is a map of where each
  capability plugs into the live `exp → domain → core` flow you have built, not a
  verified slice — every snippet above is a faithful sketch of the real module's API,
  not captured output.

## Try it yourself {.exercises}

1. **Sketch the agreement template.** Write a small `loan-agreement.ftl` that
   interpolates an applicant name, principal, term, and APR into XHTML, and note the
   call that would render it: `TemplateRenderUtil.renderTemplateToPdfBytes(...)`. You
   do not need to render it — the goal is to see that the document template is the
   same FreeMarker you would use for an email body.
2. **Pick the right scheduler.** For each of these, decide between `@Scheduled`,
   `@ScheduledSaga`, and `@ScheduledWorkflow`, and justify it in one sentence: a
   five-minute health ping; a nightly job that expires offers and reverses partial
   work on failure; an hourly cache warmup. For the one you pick `@ScheduledSaga` for,
   write the *two* annotations it needs and say what each contributes.
3. **Wire a notification to an event.** Sketch an `@EventListener` (the Chapter 11
   style) on `LoanApplicationRegisteredEvent` that calls `emailService.sendTemplateEmail`
   with a `"loan-approved"` template, gated on
   `preferenceService.isChannelEnabled(userId, "EMAIL")`. Note that the producer does
   not change — a new consumer simply reacts, exactly as in the EDA chapter.
4. **Defend the two-phase webhook.** In two or three sentences, explain what breaks
   if an inbound webhook handler does its database work *before* returning `2xx`.
   Name the failure mode (provider retries), the fast acknowledgement the framework
   gives instead (`202 Accepted` once stored), and the fix (store-then-process with
   idempotency on the provider's event id).
5. **Contrast the postures.** Write the one sentence that distinguishes an inbound
   webhook from an outbound callback in Firefly's vocabulary, then list the concern
   that is unique to each (idempotent redelivery for one; HMAC signing for the other)
   and the framework piece that owns it (`WebhookIdempotencyService` vs the outbound
   HMAC signer).

## Where to go next

You have now seen Lumen Lending's edges as well as its core. What remains is
confidence: proving the whole thing works and keeping it working in production.
Chapter 23 gathers the testing patterns the book has been using all along — slice
tests against in-memory R2DBC, broker-free EDA tests over `APPLICATION_EVENT`,
`StepVerifier` on every publisher — into a deliberate strategy, backed by the
reactor's real **33 tests** (core 18, domain 6, exp 9). Chapter 24 then takes the
service the last mile to production.
