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

!!! warning "Honesty about Lumen Lending"
    None of these four capabilities appears in the companion reactor. The trimmed
    origination slice stops at offer acceptance, so there is **no documents,
    scheduling, notifications, or webhook code in the sample, and no companion test
    for this chapter.** Every code block below is *illustrative* — a faithful sketch
    of how the capability is used, not a verbatim slice of the reactor. Where a
    capability connects to something the sample *does* build (the events from
    Chapter 11, the e-signature flow in Appendix C), the text says so explicitly.

## Documents — render a PDF you can sign

When an offer is accepted, the loan agreement must become a real document:
generated from the application and the accepted offer, presented for signature, and
stored as durable evidence. Firefly splits that into two responsibilities.
*Generating* the document is the job of `TemplateRenderUtil` in
`fireflyframework-utils`; *storing and signing* it is the job of the ECM ports in
Appendix C. This section covers the first half — turning data into a PDF.

`TemplateRenderUtil` is a FreeMarker-to-XHTML-to-PDF pipeline: you author the
agreement as a FreeMarker template (`.ftl`) that produces well-formed XHTML, feed it
a data model, and it renders a print-ready PDF with support for watermarks,
encryption, metadata, and template caching. The differentiating idea is the
*two-stage* rendering — FreeMarker first produces XHTML, which a PDF engine then
paginates — so the same template language and the same data model that drive your
notification emails also drive your legal documents.

```java
// Illustrative — not in the companion reactor.
byte[] agreement = templateRenderUtil.renderPdf(
        "loan-agreement.ftl",
        Map.of(
            "application", application,     // the approved LoanApplication
            "offer",       acceptedOffer,   // the offer the customer accepted
            "generatedAt", LocalDate.now()));
```

The template itself is ordinary FreeMarker over XHTML — the loan terms interpolated
into a styled document body:

```html
<!-- loan-agreement.ftl (illustrative) -->
<h1>Personal Loan Agreement</h1>
<p>Borrower: ${application.applicantName}</p>
<p>Principal: ${offer.amount} ${offer.currency}</p>
<p>Term: ${offer.termMonths} months at ${offer.apr}% APR</p>
```

Those `agreement` bytes are exactly what Appendix C's e-signature flow consumes: the
document port stores the rendered file, the e-signature ports wrap it in a signing
envelope, and the sealed proof comes back to be stored alongside it. Generation and
signing are deliberately separate concerns — you can render an agreement without
signing it, and the signature ports do not care how the bytes were produced.

!!! note "Key term — `TemplateRenderUtil`"
    A document-rendering utility in `fireflyframework-utils` that compiles a
    FreeMarker template to XHTML and then to PDF. It is *not* part of the ECM
    document ports; it produces the bytes that those ports store and sign. Because it
    shares FreeMarker with the notification channels below, a service has **one**
    templating story for both legal documents and customer messages.

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
for *business* work: `@Scheduled` is fire-and-forget and node-local. If the process
dies mid-run, the work is simply lost; if you run three replicas, the job fires three
times.

Firefly's answer is to make scheduled work *durable* by routing it through the same
orchestration engines you already use for foreground flows. `@ScheduledSaga` triggers
a saga (Chapter 18) on a schedule, and `@ScheduledWorkflow` triggers a workflow — so
a scheduled run gets persistence, step-level recovery, and compensation, not just a
timer. The differentiating sentence: a plain `@Scheduled` method *runs* on a
schedule, whereas a `@ScheduledSaga` *starts a recoverable, single-flighted
transaction* on a schedule, surviving restarts and coordinating across replicas.

```java
// Illustrative — not in the companion reactor.
@ScheduledSaga(
        cron = "0 0 2 * * *",            // every day at 02:00
        name = "expire-stale-offers")
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

The lighter `@Scheduled` still has its place — health pings, cache warmups, metric
rollups, anything idempotent and disposable. Reach for `@ScheduledSaga` or
`@ScheduledWorkflow` the moment the work mutates business state and you would be sorry
to run it twice or lose it halfway.

!!! spring "Spring parity"
    `@ScheduledSaga` and `@ScheduledWorkflow` build *on* Spring's scheduling — the
    trigger is the same cron mechanism — and add durability through Firefly's saga and
    workflow runtimes. Use plain Spring `@Scheduled` for ephemeral ticks; use the
    Firefly variants when "this fired but never finished" or "this fired twice" would
    be a real incident.

## Notifications — email, SMS, and push behind ports

The same approval that generates an agreement should also reach the customer:
"You're approved," "Please sign by Friday," "Your loan is active." Firefly models
that as a set of **channel services** — email, SMS, and push — each sitting behind a
provider port, exactly like the hexagonal pattern you saw for events and ECM. You
call a channel-neutral API; an adapter selected by a `firefly.notifications.*`
property routes the message to SendGrid, Twilio, Firebase, or another vendor without
your code naming any of them.

Two details make the notifications module more than a thin SDK wrapper. First, it
shares the FreeMarker engine with document generation, so a templated email is
rendered the same way the loan agreement is — one templating story for documents and
messages alike. Second, it honors **per-user channel preferences**: a customer who
opted out of SMS but wants email gets the message on the channel they chose, decided
by the framework rather than by branching in your handler.

```java
// Illustrative — not in the companion reactor.
Mono<Void> sent = notificationService.send(
        Notification.builder()
            .recipient(applicant.id())          // resolves the user's channel prefs
            .template("loan-approved")          // FreeMarker template, shared engine
            .model(Map.of("name", applicant.fullName(),
                          "amount", offer.amount()))
            .build());
// The framework renders the template and dispatches over each channel
// the recipient has opted into — email, SMS, and/or push.
```

A natural trigger for that send is an event you already publish. The
`LoanApplicationRegisteredEvent` from Chapter 11 — or a later `OfferAcceptedEvent` —
is exactly the announcement a notification consumer reacts to: an `@EventListener`
receives the fact and calls `notificationService.send(...)`, so messaging is
decoupled from the write that caused it, the same way every other consumer is.

!!! note "Key term — channel service"
    A **channel service** is the port for one delivery medium — email, SMS, or push.
    You program against the channel-neutral `NotificationService` (or a specific
    channel port) and a provider adapter does the real send. Switching from one SMS
    vendor to another is a property change and an adapter on the classpath, not an
    SDK rewrite — the same one-property-swap promise as Firefly's other ports.

!!! spring "Spring parity"
    Spring offers `JavaMailSender` for email and nothing unified for SMS or push, so
    each service grows its own vendor clients and its own preference logic. Firefly's
    notifications module unifies the three channels behind one API, shares the
    templating engine with document generation, and centralizes channel preferences —
    so "notify the customer" is one call, not three integrations.

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
raw payload, then immediately returns `2xx` — fast, before any business logic runs.
Phase two processes the stored event asynchronously, with idempotency keyed on the
provider's event id so a redelivery is recognized and dropped rather than applied
twice. The differentiating idea is *store-then-process*: you never make a flaky
caller wait on your business logic, and you never lose an event because processing
threw.

```java
// Illustrative — not in the companion reactor.
@WebhookEndpoint(
        path = "/webhooks/payments",
        provider = "acme-payments")          // selects the signature scheme
public class PaymentWebhookHandler {

    @WebhookHandler(event = "disbursement.settled")
    public Mono<Void> onSettled(DisbursementSettled event) {
        // Phase 2: the framework already validated the signature, stored the raw
        // payload, returned 2xx, and de-duplicated on the provider's event id.
        return loans.markDisbursed(event.loanId());
    }
}
```

!!! warning "An inbound webhook must acknowledge before it processes"
    If you do the business work *before* returning `2xx`, a slow database call or a
    transient error turns into the provider retrying — and now you are processing the
    same event two, three, five times under load. The store-then-process split exists
    precisely so the acknowledgement is fast and the processing is idempotent. Let the
    framework store and ack; do your work in phase two.

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
family, opposite postures.

!!! note "Key term — webhook vs callback"
    In Firefly's vocabulary a **webhook** is *inbound* (the world calls you) and a
    **callback** is *outbound* (you call the world). They are separate capabilities
    because their problems are different: inbound is about fast acknowledgement,
    signature validation, and idempotent redelivery; outbound is about HMAC signing
    and circuit-broken, retrying delivery. If you find yourself writing the same retry
    code on both sides, you have probably collapsed two different problems into one.

!!! spring "Spring parity"
    Plain Spring gives you `@RestController` for the inbound endpoint and `WebClient`
    for the outbound call, and leaves signature validation, durable storage,
    idempotency, HMAC signing, and circuit breaking entirely to you. Firefly's
    webhook and callback modules pre-wire those concerns so every service ingests and
    emits integrations the same way — the inbound `2xx`-then-process contract and the
    outbound signed-and-broken delivery, identical across the fleet.

## What you learned {.recap}

- **Documents** are rendered with `TemplateRenderUtil` — a FreeMarker → XHTML → PDF
  pipeline in `fireflyframework-utils` — whose bytes feed Appendix C's e-signature
  flow. Generation and signing are deliberately separate steps.
- **Scheduling** comes in two strengths: plain Spring `@Scheduled` for ephemeral
  ticks, and Firefly's `@ScheduledSaga` / `@ScheduledWorkflow` for durable,
  single-flighted, recoverable background work that mutates business state.
- **Notifications** are channel services — email, SMS, push — behind provider ports,
  sharing the FreeMarker engine with document generation and honoring per-user channel
  preferences, and they are naturally triggered by the domain events from Chapter 11.
- **Webhooks and callbacks** are asymmetric: inbound webhooks *store fast, then
  process* (signature validation, durable storage, idempotent redelivery); outbound
  callbacks *sign, then deliver* (HMAC signatures over a circuit-breaker-guarded
  client). Same word family, opposite postures.
- None of this is in the companion reactor; the chapter is a map of where each
  capability plugs into the Lumen Lending you have built, not a verified slice.

## Try it yourself {.exercises}

1. **Sketch the agreement template.** Write a small `loan-agreement.ftl` that
   interpolates an applicant name, principal, term, and APR into XHTML. You do not
   need to render it — the goal is to see that the document template is the same
   FreeMarker you would use for an email body.
2. **Pick the right scheduler.** For each of these, decide between `@Scheduled`,
   `@ScheduledSaga`, and `@ScheduledWorkflow`, and justify it in one sentence: a
   five-minute health ping; a nightly job that expires offers and reverses partial
   work on failure; an hourly cache warmup.
3. **Wire a notification to an event.** Sketch an `@EventListener` (the Chapter 11
   style) on `LoanApplicationRegisteredEvent` that calls `notificationService.send`
   with a `"loan-approved"` template. Note that the producer does not change — a new
   consumer simply reacts, exactly as in the EDA chapter.
4. **Defend the two-phase webhook.** In two or three sentences, explain what breaks
   if an inbound webhook handler does its database work *before* returning `2xx`.
   Name the failure mode (provider retries) and the fix (store-then-process with
   idempotency on the provider's event id).
5. **Contrast the postures.** Write the one sentence that distinguishes an inbound
   webhook from an outbound callback in Firefly's vocabulary, then list the concern
   that is unique to each (idempotent redelivery for one; HMAC signing for the other).

## Where to go next

You have now seen Lumen Lending's edges as well as its core. What remains is
confidence: proving the whole thing works and keeping it working in production.
Chapter 23 gathers the testing patterns the book has been using all along — slice
tests against in-memory R2DBC, broker-free EDA tests, `StepVerifier` on every
publisher — into a deliberate strategy, and Chapter 24 takes the service the last
mile to production.
