Chapter 1 named four tiers — experience, domain, core, and **data** — and promised
we would meet the last one here. It is the only tier Lumen Lending's origination
slice does not actually build, and rather than paper over that, this chapter is
going to be honest about it. The slice you have been growing reads and writes loan
applications against a database it owns. It is a *system of record*: the core tier,
backed by plain reactive persistence. The data tier is a different animal — it does
not own a schema, it *sources* and *improves* data that originates elsewhere. In a
real lending platform that means credit-bureau pulls, KYC and AML screening,
fraud-signal lookups, address normalization, and the audit trail that records where
every borrowed fact came from.

The origination slice has none of that. Where a production platform would call a
credit bureau, the reactor records a self-made stub result so the sample can boot
with no external system and no Docker — exactly the same honesty you saw with the
SDK seam in Chapter 10. So this chapter teaches the data tier the only way it can be
taught truthfully: by walking the `starter-data` building blocks — the
`DataEnricher` framework, the data-quality engine, and lineage tracking — with
clearly **illustrative** snippets, and pointing at precisely where each one would
plug into Lumen if the platform grew that far.

There are no verbatim reactor listings in this chapter and no `mvn` "Run it"
moment, because the origination slice does not exercise the data tier. Every code
block below is illustrative — a standard fenced block, never a `::: listing`
directive — and labelled as such. Read it as a map of the territory, not a slice of
the build.

!!! note "Key term — the data tier (`starter-data`)"
    The **data tier** is the home for *enrichment, data quality, and lineage* — the
    work of bringing external or derived data into the platform and vouching for it.
    A data-tier service owns no business schema of its own the way the core does;
    it *enriches* a subject (an applicant, an address, a company) by calling
    providers, scores the result against quality rules, and records where each fact
    came from. Its starter is `fireflyframework-starter-data`. In Lumen it would sit
    behind the domain tier, called when origination needs a credit decision or a KYC
    clearance.

## Why origination needs a data tier (but the slice doesn't have one)

Trace a real loan application and you hit the data tier almost immediately. Before a
decision engine can score an applicant, *someone* has to pull a credit report,
verify identity documents, screen the applicant against sanctions and PEP lists, and
normalize the home address to something the rest of the platform agrees on. None of
those facts originate in Lumen. They come from outside providers — Experian,
Equifax, a KYC vendor, a sanctions-list service — each with its own API, latency,
cost, and occasional outage.

You could call those providers straight from a domain handler. Teams do, and they
regret it: the handler grows retry logic for one bureau, a fallback to a second
bureau when the first is down, a cache so a re-submitted application does not pay for
a second pull, a quality check to reject a garbage response, and an audit record of
which bureau answered. That is the data tier's job description, and the point of
`starter-data` is to make it a *capability* you configure rather than plumbing you
hand-roll in every handler.

The origination slice skips all of it. In Chapter 10 the command handler called
`client.createLoanApplication(...)` and returned an id; nowhere did it enrich the
applicant. If it did, the honest stand-in would look like the SDK seam — a port with
a hand-written result — so the tests stay broker-free:

```java
// Illustrative — the kind of stub the slice would use instead of a real bureau call.
// A self-recorded result, so the sample boots with no Experian, no network, no Docker.
public final class StubCreditBureauClient implements CreditBureauClient {
    @Override
    public Mono<CreditReport> pull(String applicantTaxId) {
        return Mono.just(new CreditReport(applicantTaxId, /* score */ 720, "STUB"));
    }
}
```

That stub is *all* the slice would carry. Everything else in this chapter — the
fallback chains, the per-provider circuit breakers, the quality gates, the lineage
records — is what `starter-data` provides so that the production version of that one
line becomes a configured capability instead of a thousand lines of bespoke
resilience code.

!!! warning "This chapter describes a capability the slice does not run"
    Be clear-eyed: the build in `samples/lumen-lending` does **not** depend on
    `fireflyframework-starter-data`, and nothing below is verified by a Lumen test.
    Treat every snippet as how-it-works and where-it-plugs-in. When the chapter says
    "the enricher falls back," read "the data tier is *designed* to fall back" — the
    proof lives in the framework's own module tests, not in this sample.

## The DataEnricher framework

The center of the data tier is enrichment: taking a thin subject and *filling it in*
from external sources. Firefly models this as a small contract. An `EnricherOperation`
is one named unit of enrichment — "pull a credit report," "screen against sanctions,"
"normalize this address" — and the `DataEnricher` is the orchestrator that runs an
operation against a chain of providers, with resilience and caching wrapped around
each call.

Conceptually an operation is an interface much like a CQRS handler: a typed input, a
typed output, and a single reactive method.

```java
// Illustrative — the shape of an enrichment operation.
public interface EnricherOperation<I, O> {

    /** Stable name used for routing, metrics, caching, and lineage. */
    String name();

    /** Runs the enrichment against one provider; returns the enriched value. */
    Mono<O> enrich(I input, EnrichmentContext context);
}
```

A credit-bureau operation implements that interface once per *provider*. You write an
`ExperianCreditOperation` and an `EquifaxCreditOperation`, each speaking its own
vendor's API but both producing the same `CreditReport` output type. The output type
is the contract the rest of the platform depends on; the providers behind it are
interchangeable.

```java
// Illustrative — one provider's implementation of the credit-pull operation.
@EnricherComponent(name = "creditReport", provider = "experian", order = 1)
public class ExperianCreditOperation
        implements EnricherOperation<ApplicantRef, CreditReport> {

    private final ExperianClient experian;

    @Override
    public String name() { return "creditReport"; }

    @Override
    public Mono<CreditReport> enrich(ApplicantRef input, EnrichmentContext ctx) {
        return experian.report(input.taxId())
                .map(CreditReportMapper::fromExperian);
    }
}
```

Like the CQRS handlers of Chapter 10, the operation is discovered by an annotation —
here a stereotype such as `@EnricherComponent` — so you never hand-register it. The
`name` groups providers that produce the same output (`"creditReport"`), and the
`order` ranks them within that group. That ranking is what turns a set of operations
into a *fallback chain*.

!!! note "Key term — EnricherOperation vs. DataEnricher"
    An **`EnricherOperation<I, O>`** is *one provider's* way to enrich a subject — a
    leaf. The **`DataEnricher`** is the orchestrator: given a logical enrichment
    name like `"creditReport"`, it gathers every operation registered under that
    name, orders them, and runs them as a fallback chain with resilience and caching
    around each. You write operations; you *call* the enricher. The relationship is
    deliberately the same shape as `CommandHandler` to `CommandBus`.

### Provider fallback chains

A single bureau is a single point of failure. Bureaus have outages, rate limits, and
gaps in coverage — and a loan application that cannot be scored because Experian is
down is revenue lost for an avoidable reason. The data tier's answer is a **fallback
chain**: register two or more providers under the same enrichment name, ordered by
preference, and let the `DataEnricher` try them in turn until one succeeds.

```java
// Illustrative — calling the enricher; the chain is configured, not coded here.
public Mono<CreditReport> creditReportFor(ApplicantRef applicant) {
    return dataEnricher.enrich("creditReport", applicant);
    // Tries experian (order 1); on failure, falls through to equifax (order 2);
    // on failure of both, the chain's terminal policy decides: error or a default.
}
```

The caller names the enrichment — `"creditReport"` — not the provider, exactly as a
CQRS caller names the message and not the handler. The `DataEnricher` resolves the
chain: the `order = 1` provider first, and if it errors (or times out, or its
circuit is open), the *next* operation in the chain. Adding Equifax as a backup is a
new `@EnricherComponent(order = 2)` class and zero changes to the caller. Promoting
Equifax to primary is an `order` swap. The chain's terminal behavior — fail hard
versus return a configured default — is a policy you set, not an `if` you write.

```java
// Illustrative — the second provider in the same chain. Same name, higher order.
@EnricherComponent(name = "creditReport", provider = "equifax", order = 2)
public class EquifaxCreditOperation
        implements EnricherOperation<ApplicantRef, CreditReport> {
    // ... same CreditReport output type; different vendor API behind it.
}
```

This is the same hexagonal instinct Chapter 1 described for identity and content
providers: the platform depends on the *capability* (`"creditReport"`), and the
vendors sit behind a port, swappable by configuration. The difference is that
enrichment expects to use *more than one* provider at once — not one chosen adapter,
but a ranked chain it walks on failure.

!!! spring "Spring parity"
    A fallback chain is something you could assemble in plain Spring with an ordered
    `List<EnricherOperation>` injected by type and a reactive `onErrorResume`
    cascade — Spring will inject beans in `@Order` sequence, and Reactor's
    `onErrorResume` expresses "try the next one." `starter-data` is that pattern,
    pre-built: discovery by annotation, ordering by attribute, and the cascade
    generated for you, so every enrichment in the fleet falls back the same way
    instead of each team re-deriving the `onErrorResume` ladder.

### Per-provider resilience with Resilience4j

Falling back is only safe if a failing provider *fails fast*. If Experian is
timing out at thirty seconds per call, walking the chain to Equifax after every
timeout makes every credit pull catastrophically slow. So the data tier wraps each
provider call in its own Resilience4j decorators — a circuit breaker, a timeout, a
retry, and a bulkhead — keyed by provider, not shared across the chain.

The keying matters. A shared circuit breaker would trip on Experian's outage and
then *also* block the healthy Equifax call. Per-provider breakers isolate them: when
Experian's breaker opens, the enricher skips straight to Equifax without waiting for
a doomed call, and Experian's breaker half-opens later to probe for recovery — all
while Equifax served traffic uninterrupted.

```yaml
# Illustrative — per-provider resilience, tuned by configuration, not by code.
firefly:
  data:
    enrichers:
      creditReport:
        providers:
          experian:
            order: 1
            resilience:
              timeout: 3s
              retry: { max-attempts: 2, backoff: 200ms }
              circuit-breaker: { failure-rate-threshold: 50, wait-duration-in-open-state: 30s }
          equifax:
            order: 2
            resilience:
              timeout: 5s
              circuit-breaker: { failure-rate-threshold: 50, wait-duration-in-open-state: 30s }
```

Because the decorators are configured rather than coded, the resilience posture of
the entire data tier is visible in one place and tuned without redeploying logic.
This is the same Resilience4j the resilient SDK clients of Chapter 14 use; the data
tier simply applies it *per provider within a chain*, which is the granularity
enrichment needs.

!!! warning "Don't share a circuit breaker across providers in a chain"
    The whole value of a fallback chain is that one provider's outage routes traffic
    to another. A circuit breaker shared across the chain defeats that — it opens on
    the first provider's failures and blocks the fallback too. Key breakers (and
    bulkheads) by **provider**, so an open breaker on the primary is exactly the
    signal that makes the enricher reach for the backup.

## Enrichment caching and cost tracking

External data costs money and time. A credit-bureau pull is billed per inquiry and
takes a network round trip; a KYC screening may be metered per check. If a customer
re-submits an application, or two parts of the platform ask for the same applicant's
report within minutes, paying twice is waste — and, for some bureau products,
re-pulling can even *affect the applicant's score*. The data tier therefore caches
enrichment results, keyed by the enrichment name plus the subject, with a TTL you set
per enrichment.

```java
// Illustrative — an operation that opts its results into caching with a TTL.
@EnricherComponent(
        name = "creditReport",
        provider = "experian",
        order = 1,
        cacheable = true,
        cacheTtl = "15m")
public class ExperianCreditOperation
        implements EnricherOperation<ApplicantRef, CreditReport> {
    // a repeated pull for the same applicant inside 15 minutes is served from cache
}
```

The cache rides on the same provider-agnostic caching abstraction Firefly uses
elsewhere (Chapter 13), so the backing store — Caffeine in-process, Redis across
instances — is a configuration choice, not a code change. A cached enrichment never
touches the provider, so it never trips a breaker, never incurs a fee, and returns in
microseconds.

Paired with caching is **cost tracking**. Because every provider call flows through
the `DataEnricher`, the framework is positioned to count and price it. The data tier
records, per enrichment and per provider, how many live calls were made, how many
were served from cache, and — where you supply a unit cost — what the spend was. That
turns "why is our bureau bill so high this month?" from a forensic investigation into
an Actuator endpoint and a metric.

```java
// Illustrative — enrichment metadata the tier can surface alongside the result.
// callsMade=1, cacheHits=0, provider="experian", estimatedCost=0.85 USD
EnrichmentResult<CreditReport> result = dataEnricher.enrichDetailed("creditReport", applicant).block();
result.value();          // the CreditReport
result.provider();       // which provider in the chain actually answered
result.fromCache();      // whether this was a billable live call
result.estimatedCost();  // priced from the configured per-call cost
```

!!! note "Key term — enrichment cost tracking"
    **Cost tracking** is the data tier's accounting of external-data spend. Every
    enrichment carries metadata — which provider answered, whether it was a cache
    hit, and an estimated cost from a configured per-call price — so the platform can
    attribute spend to a provider, a product, or a tenant, and a cache hit visibly
    shows up as a call you *did not* pay for. It exists because, in lending, the data
    you buy is often the largest variable cost per application.

!!! spring "Spring parity"
    Caching here is Spring's cache abstraction underneath, the same one `@Cacheable`
    uses — so a Redis or Caffeine `CacheManager` you already run is the backing
    store. What `starter-data` adds is the *key strategy* (enrichment name plus
    subject), the per-enrichment TTL, and the cost metadata threaded through the
    result, none of which a bare `@Cacheable` gives you.

## The data-quality engine

A successful provider call is not the same as a *usable* answer. A bureau can return
a report with a missing score, a stale pull date, or a name that does not match the
applicant on file; a KYC vendor can return a low-confidence match. Letting that
through pollutes every downstream decision. The data tier puts a **rule-based
data-quality engine** between the raw enrichment and the rest of the platform: a set
of named rules that score and validate a result before it is trusted.

A quality rule is, again, a small typed unit — a predicate over the enriched value
that yields a pass/fail (or a weighted score) and a reason.

```java
// Illustrative — a data-quality rule over a credit report.
@DataQualityRule(name = "creditScorePresent", dimension = COMPLETENESS, weight = 1.0)
public class CreditScorePresentRule implements QualityRule<CreditReport> {

    @Override
    public RuleResult evaluate(CreditReport report) {
        return report.score() != null
                ? RuleResult.pass()
                : RuleResult.fail("credit score is missing");
    }
}
```

Rules are grouped by *dimension* — completeness, validity, freshness, consistency,
accuracy — the standard vocabulary of data quality, so a result's score is not one
opaque number but a breakdown you can reason about: "complete and valid, but stale."
The engine runs every rule registered for a type, aggregates the weighted outcomes,
and produces a `QualityReport`.

### Quality gates

A score is only useful if something *acts* on it. A **quality gate** is a threshold
the enrichment must clear to be accepted: below it, the result is rejected or routed
to manual review rather than fed to the decision engine. The gate is where data
quality stops being a dashboard and becomes a control.

```java
// Illustrative — gating an enrichment on its quality score before it is used.
public Mono<CreditReport> trustedCreditReport(ApplicantRef applicant) {
    return dataEnricher.enrich("creditReport", applicant)
            .flatMap(report -> qualityEngine.assess(report)
                    .flatMap(quality -> quality.score() >= 0.80
                            ? Mono.just(report)
                            : Mono.error(new QualityGateException("creditReport", quality))));
}
```

In a Lumen that owned this tier, that gate is precisely what protects the saga of
Chapter 11: the `registerLoanApplication` step would not proceed on a credit report
that failed the gate — it would compensate, or hand off to a human, instead of
scoring a decision on data the platform does not trust. The gate threshold and which
dimensions are mandatory are configuration, so risk and compliance can tighten the
bar without a code change.

!!! note "Key term — quality gate"
    A **quality gate** is a minimum quality score (optionally per dimension) that an
    enrichment result must meet to be accepted downstream. Results below the gate are
    rejected, defaulted, or escalated to manual review. The gate is the difference
    between *measuring* data quality and *enforcing* it: it is the point where a
    low-confidence bureau response stops being a number on a chart and starts
    blocking a loan decision that would otherwise be made on bad data.

!!! spring "Spring parity"
    The quality engine is plain Spring beans — each rule is a `@Component`
    discovered by type, aggregated by an engine bean — with no framework magic you
    could not write yourself. The value `starter-data` adds is the *vocabulary*
    (dimensions, weights, gates) and the wiring, so every service expresses data
    quality the same way instead of inventing a bespoke validation method per
    enrichment.

## Pluggable data lineage

When a regulator, an auditor, or a disputing customer asks "where did this credit
score come from, and when?", the platform must answer precisely: which provider,
which version of which rule chain, at what time, against what input, with what
quality score. That is **data lineage** — the recorded provenance of every enriched
fact — and in a lending platform it is not optional, it is a compliance obligation.

Because every enrichment flows through the `DataEnricher`, the tier is the natural
place to capture lineage automatically. Each enrichment emits a lineage record: the
subject, the enrichment name, the provider that answered, whether it was a cache hit,
the quality outcome, a timestamp, and the correlation id from the `ExecutionContext`
of Chapter 10 — so a single application's entire data-sourcing history can be
reconstructed from the audit trail.

```java
// Illustrative — the lineage record the tier emits per enrichment.
// subject=applicant:7b1f..., enrichment="creditReport", provider="equifax",
// fromCache=false, qualityScore=0.91, at=2026-06-17T10:14:32Z, correlationId=...
public record LineageRecord(
        String subjectRef,
        String enrichment,
        String provider,
        boolean fromCache,
        double qualityScore,
        Instant at,
        String correlationId) { }
```

Crucially, lineage capture is **pluggable**. Where the records *go* is a port: an
in-memory recorder for tests, a log appender for development, a Kafka topic or an
audit database in production, an OpenLineage-compatible sink if you feed a data
catalog. You depend on a `LineageRecorder` interface and choose the adapter by
configuration, the same hexagonal move as every other vendor seam in Firefly.

```java
// Illustrative — the lineage sink is a port; the adapter is chosen by config.
public interface LineageRecorder {
    Mono<Void> record(LineageRecord record);
}
```

That pluggability is what lets the *same* enrichment code run with a no-op recorder
in a unit test and a durable audit sink in production — exactly the test-vs-production
substitution you saw with the SDK seam, applied to provenance.

!!! note "Key term — data lineage"
    **Data lineage** is the recorded provenance of a derived or sourced fact: where
    it came from, when, by which provider and rules, and with what quality. In the
    data tier each enrichment automatically emits a `LineageRecord` to a pluggable
    `LineageRecorder` sink. Lineage answers the audit question "justify this number,"
    and because it keys on the same correlation id that flows through the rest of the
    platform, a fact's provenance joins up with the request that needed it.

!!! spring "Spring parity"
    There is no Spring Boot starter for "data lineage" — this is genuinely a Firefly
    capability rather than a re-wired Spring one. What *is* plain Spring is the
    mechanism: the recorder is a port (an interface) with adapters selected by
    `@ConditionalOnProperty`, exactly like the identity and content ports of
    Chapter 1. Firefly supplies the lineage *model* and the automatic emission;
    Spring supplies the bean wiring that swaps the sink.

## How the pieces compose

Step back and the data tier is one pipeline, and each section above is a stage of it.
A domain handler asks for an enrichment by name. The `DataEnricher` walks the
**fallback chain**, each provider call wrapped in **per-provider Resilience4j** and
short-circuited by the **cache**, recording **cost** as it goes. The winning result
runs the **data-quality engine**, and a **quality gate** decides whether it is
trusted. Whatever happens, a **lineage record** is emitted to a pluggable sink. The
domain tier gets back either a trusted, priced, provenance-tracked fact — or a clean
error it can compensate against.

That whole pipeline is what the origination slice replaces with a single stubbed line.
The stub is honest and sufficient for teaching origination; the pipeline is what makes
the production version of that line safe, cheap, observable, and auditable. Knowing
the shape of the tier is what lets you recognize, the day Lumen needs a real credit
pull, that the answer is a `starter-data` service behind the domain tier — not more
resilience code in a handler.

## What you learned {.recap}

- The **data tier** (`starter-data`) is the platform's home for *enrichment, data
  quality, and lineage* — sourcing and vouching for external data like credit
  reports and KYC results — and Lumen's origination slice deliberately does **not**
  build it, standing in a self-recorded stub the way Chapter 10 stood in the SDK seam.
- The **`DataEnricher`** runs named **`EnricherOperation`** providers as a ranked
  **fallback chain**, so a credit pull tries one bureau and falls through to the next,
  with the caller naming the *enrichment* and never the provider.
- Each provider call is wrapped in **per-provider Resilience4j** (circuit breaker,
  timeout, retry, bulkhead) — keyed by provider so an open breaker on the primary is
  the signal that routes to the backup — and short-circuited by **caching with cost
  tracking**, so repeat pulls are free and bureau spend is attributable.
- A **rule-based data-quality engine** scores results across dimensions, and a
  **quality gate** enforces a trust threshold before a result reaches the decision
  path — the point where measuring quality becomes controlling it.
- **Pluggable data lineage** emits a provenance record per enrichment to a port-based
  `LineageRecorder` sink, answering the audit question "where did this fact come
  from?" with a no-op adapter in tests and a durable sink in production.
- Everything in this chapter is **illustrative**: no verbatim reactor slice, no
  `mvn` run, because the sample does not depend on `starter-data`. The proof lives in
  the framework's own module tests; here it is a map of where the tier plugs in.

## Try it yourself {.exercises}

1. **Sketch a bureau enricher with a fallback chain.** On paper, design a
   `"creditReport"` enrichment for Lumen with two providers — a primary and a
   backup. Write the two `@EnricherComponent` class signatures (name, provider,
   order) and the one `dataEnricher.enrich("creditReport", applicant)` call site in a
   domain handler. Mark exactly which lines change when you (a) add a third bureau and
   (b) promote the backup to primary — and confirm the call site is not among them.
2. **Place the resilience.** For the two providers above, write the
   `firefly.data.enrichers.creditReport` YAML with a per-provider timeout and circuit
   breaker. Then explain, in one sentence, what would break if you moved the circuit
   breaker up to the chain level instead of per provider.
3. **Add a quality gate to the origination saga.** Re-read Chapter 11's
   `registerLoanApplication` step. Describe where a `qualityEngine.assess(...)` gate
   on the credit report would sit in that reactive chain, and what the saga should do
   — proceed, compensate, or escalate — when the gate fails. Which `firefly.*`
   property would risk own?
4. **Cost a re-submission.** A customer submits the same application twice within ten
   minutes, and the credit pull is `cacheable = true, cacheTtl = "15m"` at $0.85 per
   live call. Using the `EnrichmentResult` metadata (`fromCache`, `estimatedCost`),
   state what the cost-tracking numbers report for the second submission and why.
5. **Choose a lineage sink.** The `LineageRecorder` port has adapters for in-memory,
   log, Kafka, and an audit database. Pick the adapter you would wire in a unit test
   and the one for production, and explain how swapping them leaves the enrichment
   code — and the emitted `LineageRecord` — completely unchanged.

## Where to go next

This is the last of the four tiers, and with it you have the whole map: experience
composes, domain orchestrates, core records, data sources and vouches. The remaining
chapters return to the running slice to tie the tiers together — wiring the generated
SDK that replaces the Chapter 10 port, and standing the full origination flow up end
to end. When you reach a point where Lumen needs a real credit decision rather than a
stubbed id, you now know the shape of the service that answers it: a `starter-data`
enricher, behind a fallback chain, gated on quality, recording its lineage — added as
a tier, not bolted onto a handler.
