A loan does not end at "approved." Somewhere a contract is generated, presented,
signed, and stored as durable evidence — and in a regulated lender that paper trail
is the difference between a booked loan and a compliance finding. Firefly's
Enterprise Content Management (ECM) module handles it, and like every Firefly
integration it is hexagonal: you depend on ports, and a provider adapter does the
real work.

!!! warning "Honesty about Lumen Lending"
    The companion reactor does **not** implement the contract flow — in the trimmed
    origination slice, accepting an offer is where the story stops, and a real
    service's `signContract(...)` would be a stub returning `ACTIVE`. This appendix
    therefore teaches the ECM model and shows how the contract step *would* be
    built; the snippets are illustrative, not slices of the reactor.

## The shape of ECM

`fireflyframework-ecm` defines around eighteen reactive port interfaces grouped
into families — documents, e-signature, intelligent document processing, folders,
security, and audit. You depend on the port you need; an `@EcmAdapter` registers an
implementation, selected by `firefly.ecm.*` properties (see Appendix B). Security
ports deny by default, and missing capabilities fall back to safe no-ops rather
than failing closed in surprising ways.

## Step 1 — Generate the agreement (TemplateRenderUtil)

Document generation is not part of ECM proper; it lives in `fireflyframework-utils`
as `TemplateRenderUtil`, a FreeMarker-to-XHTML-to-PDF engine with watermarks,
encryption, metadata, and template caching. You render the loan agreement from the
approved application and the accepted offer:

```java
// Illustrative — not in the companion reactor.
byte[] pdf = templateRenderUtil.renderPdf(
        "loan-agreement.ftl",
        Map.of("application", application, "offer", acceptedOffer));
```

The same FreeMarker engine backs the notification templates from Chapter 22, so a
service has one templating story for documents and messages alike.

## Step 2 — Store it (document ports)

The rendered bytes become a stored, versioned document through the document and
content ports, behind whichever storage adapter is configured (AWS S3 or Azure Blob):

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

## Step 3 — Sign it (e-signature ports)

The e-signature family models an envelope, a signing request, validation, and
proof. The provider — DocuSign, Adobe Sign, or Logalty (qualified e-evidence) — is
a property, so the lender can change vendors without touching this code:

```java
// Illustrative.
Mono<SignatureEnvelope> envelope = signatureEnvelopePort.create(
        EnvelopeSpec.builder()
            .document(stored.ref())
            .signer(applicant.email(), applicant.fullName())
            .build());
// later: validationPort.validate(envelopeId), proofPort.retrieve(envelopeId)
```

When the signature completes, the proof (the sealed evidence package) is stored
alongside the document, and a domain event — handled exactly like the events in
Chapter 11 — moves the application to its booked state.

## The other ECM ports, briefly

- **Intelligent document processing** — extraction, classification, and data
  extraction ports for inbound documents (an uploaded payslip or ID), so onboarding
  can read a document rather than just file it.
- **Content security** — permission and document-security ports, deny-by-default,
  governing who may read or change a stored document.
- **Audit** — an audit port recording every access and change, which is precisely
  what an examiner asks to see.

## Where to go next

The contract flow is the natural sequel to the offer-acceptance step in Part IV:
generate with `TemplateRenderUtil`, store and secure through the document ports,
sign through the e-signature ports, and let a domain event book the loan. Building
it end to end — against the in-process defaults first, real providers later — is
the most rewarding exercise in this book.
