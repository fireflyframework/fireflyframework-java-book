A loan does not end at "approved." Somewhere a contract is generated, presented,
signed, and stored as durable evidence — and in a regulated lender that paper trail
is the difference between a booked loan and a compliance finding. Firefly's
Enterprise Content Management (ECM) module handles it, and like every Firefly
integration it is hexagonal: you depend on ports, and a provider adapter does the
real work.

!!! warning "Honesty about Lumen Lending"
    The companion reactor does **not** implement the contract flow. In the trimmed
    origination slice the story stops at `SUBMITTED`: a `POST` to the experience BFF
    (`/api/v1/experience/lending/applications` on `:8080`) returns `201` and flows
    `exp → domain → core` — the domain runs `RegisterApplicationSaga`, whose root
    step writes to the core system of record on `:8081`. There is no offer
    acceptance, no contract generation, and no signature step on disk; a real
    service's `signContract(...)` would be a stub returning `ACTIVE`. This appendix
    therefore teaches the ECM model and shows how the contract step *would* be built
    on top of that flow. **Every snippet here is illustrative** — plain fenced code,
    not a verbatim slice of the reactor — and `fireflyframework-ecm` is **not** on
    the sample's classpath.

## The shape of ECM

`fireflyframework-ecm` defines a family of reactive port interfaces grouped by
concern — documents and content, e-signature, intelligent document processing
(IDP), folders, content security, and audit. You depend on the port you need; an
`@EcmAdapter`-style provider component registers an implementation, selected by
`firefly.ecm.*` properties (the same property-driven adapter pattern Appendix B
describes for the other integration modules). Two design rules carry through the
whole module:

- **Security denies by default.** A permission port that has not been told a
  principal may read a document answers "no," not "yes." You opt in to access, you
  do not opt out of it.
- **Missing capabilities degrade safely.** If a configured provider does not
  implement, say, qualified e-evidence, the affected port falls back to a documented
  no-op or a typed "unsupported" error rather than failing closed in a surprising
  place mid-flow.

Every port returns reactive types — `Mono<T>` for a single result, `Flux<T>` for a
stream — so an ECM call composes into the same reactive pipeline as the rest of a
Firefly service. Nothing here blocks a Netty event-loop thread.

!!! note "Key term — ECM port vs. adapter"
    An **ECM port** is a provider-neutral interface — `store a document`, `create a
    signing envelope`, `extract fields from a scan`. An **adapter** is the concrete
    implementation for one vendor (S3, DocuSign, a cloud OCR service). Your service
    code only ever names the port; `firefly.ecm.*` properties bind it to an adapter
    at startup, so swapping DocuSign for Adobe Sign is a configuration change, not a
    code change.

## Step 1 — Generate the agreement (TemplateRenderUtil)

Document generation is not part of ECM proper; it lives in
`fireflyframework-utils` as `TemplateRenderUtil`, a FreeMarker-to-XHTML-to-PDF
engine with watermarks, encryption, document metadata, and template caching. The
contract step would render the loan agreement from the approved application and the
accepted offer:

```java
// Illustrative — not in the companion reactor.
byte[] pdf = templateRenderUtil.renderPdf(
        "loan-agreement.ftl",
        Map.of("application", application, "offer", acceptedOffer));
```

A few things matter about where this plugs in. `TemplateRenderUtil` is a plain
utility bean, not an ECM port — it turns a template plus a data model into bytes
and stops there; storing, securing, and signing those bytes is ECM's job. The
engine renders FreeMarker (`.ftl`) to XHTML and then to PDF, so the template author
writes ordinary markup with `${...}` expressions, and the watermark, owner/user
encryption, and PDF metadata (title, author, keywords) are render options rather
than hand-rolled PDF plumbing. Template caching means a high-volume booking flow
compiles each `.ftl` once.

Crucially, the *same* FreeMarker engine backs the notification templates from the
notifications chapter, so a service has one templating story for documents and
messages alike — the welcome email and the loan agreement are rendered by the same
machinery from the same kind of template.

!!! note "Key term — render model"
    The **render model** is the `Map<String, Object>` (or a typed holder) you hand
    the template. Keep it shaped for the document, not for your domain internals:
    pass an `application` view and an `acceptedOffer` view with exactly the fields
    the agreement prints, so the template stays a presentation concern and a change
    to an internal entity does not silently reshape a legal document.

## Step 2 — Store it (document and content ports)

The rendered bytes become a stored, versioned document through the document and
content ports, behind whichever storage adapter is configured (for example AWS S3
or Azure Blob):

```java
// Illustrative.
Mono<DocumentRef> stored = documentPort.store(
        DocumentSpec.builder()
            .name("loan-agreement-" + application.id() + ".pdf")
            .contentType("application/pdf")
            .bytes(pdf)
            .folder("loans/" + application.id())
            .build());
```

The port returns a `DocumentRef` — a stable handle (an id, a content type, a
version) you persist against the application, not the bytes themselves. Storage is
the adapter's problem: the same `documentPort.store(...)` call lands the PDF in an
S3 bucket or an Azure container depending only on `firefly.ecm.*`, and a folder
port organizes documents into a hierarchy (`loans/{applicationId}`) so retrieval
and retention policies have something to hang on. Because the adapter, not your
code, owns the bucket name, region, and credentials, the booking flow you write in
the domain tier is identical in dev (where a local/in-memory adapter could stand
in) and in production.

## Step 3 — Sign it (e-signature ports)

The e-signature family models an envelope, a signing request, validation, and
proof. The provider — DocuSign, Adobe Sign, or a qualified-e-evidence vendor such
as Logalty — is a property, so the lender can change vendors without touching this
code:

```java
// Illustrative.
Mono<SignatureEnvelope> envelope = signatureEnvelopePort.create(
        EnvelopeSpec.builder()
            .document(stored.ref())
            .signer(applicant.email(), applicant.fullName())
            .build());
// later: validationPort.validate(envelopeId), proofPort.retrieve(envelopeId)
```

Read the lifecycle in three beats. The **envelope** bundles one or more documents
and one or more signers into a single signing transaction; `create(...)` hands the
document off to the provider and returns a handle plus (with a remote provider) a
signing URL the BFF can surface to the applicant. **Validation** asks the provider
whether the completed signatures are cryptographically and procedurally sound —
right signer, untampered document, valid certificate. **Proof** retrieves the
sealed evidence package (the signed PDF plus the audit trail / certificate of
completion) that an examiner will later demand.

Signing is inherently asynchronous: the applicant signs minutes or days after the
envelope is created. So the realistic wiring is event-driven, not a blocking wait.
The provider adapter signals completion (via a webhook or a poll), the service
publishes a domain event, and a handler — exactly the kind of EDA event handler the
events chapter builds, and the same in-JVM `APPLICATION_EVENT` transport the sample
already uses for its saga — retrieves the proof, stores it next to the agreement
through the document port, and moves the application to its booked state. The
contract step is therefore not one synchronous method; it is *generate → store →
create envelope* on the request, then *signature completed → retrieve proof → store
proof → book* on the event.

!!! note "Key term — signature proof"
    The **proof** is the durable evidence of a completed signature: the signed
    document, a tamper-evident seal, and the audit trail (who signed, when, from
    where, with which certificate). Storing the proof — not just the signed PDF —
    is what turns "the customer clicked sign" into something defensible in front of
    a regulator. Treat the proof as the system of record for the contract, stored
    and secured like any other ECM document.

## Where the contract step would sit in the sample

In the running reactor the live path is `exp → domain → core`: the domain's
`RegisterApplicationSaga` already orchestrates a multi-step write across tiers. The
contract flow is the natural sequel to an *offer-acceptance* step the slice does
not yet have. Conceptually it would be one more saga (or a continuation of the
existing one) on the **domain** tier — the orchestration layer is exactly where a
"generate, store, sign, book" sequence belongs — calling out to the ECM ports the
way the current saga's root step calls the core `WebClient` client. The core tier
would persist the resulting `DocumentRef` and booked status; the experience BFF
would expose the signing URL to the channel. None of that is wired today, but it
slots onto the seams the sample already demonstrates.

## The other ECM ports, briefly

- **Intelligent document processing (IDP)** — extraction, classification, and
  data-extraction ports for *inbound* documents (an uploaded payslip, an ID scan),
  so onboarding can *read* a document rather than just file it. These ports turn a
  scanned image into structured fields a rule can act on; in a real lender they feed
  the credit decision rather than the contract, but they share the same adapter and
  security model. (As with everything in this appendix, IDP is not wired in the
  sample.)
- **Content security** — permission and document-security ports, deny-by-default,
  governing who may read or change a stored document, so the signed agreement is not
  world-readable just because it was successfully stored.
- **Audit** — an audit port recording every access and change to a document, which
  is precisely what an examiner asks to see and what closes the loop on the proof
  stored in Step 3.

## Where to go next

The contract flow is the most rewarding exercise this book leaves on the table:
generate the agreement with `TemplateRenderUtil`, store and secure it through the
document and content ports, create and validate an e-signature envelope, let an EDA
event carry the completed proof, and book the loan — extending the same
`exp → domain → core` saga the sample runs live. Build it against in-process or
local defaults first and wire real providers (S3/Azure for storage,
DocuSign/Adobe/Logalty for signing) later, switching each one with a single
`firefly.ecm.*` property and no change to the code you wrote.
