Lumen Lending makes a credit decision, but it does not *reason* about one. Walk
back through the slice you have built and find the moment a loan is priced. It is in
the domain tier's `LoanOriginationService.submitApplication`, which takes an `amount`
and an `annualRateBps` as plain arguments and hands them straight into a
`ProposeOfferCommand`. From there the saga's `proposeOffer` step dispatches that
command on the `CommandBus`, and `ProposeOfferHandler` records the offer by calling
`client.proposeOffer(loanApplicationId, amount, annualRateBps)` — passing the same
rate it was handed, untouched. Nothing along that path computes whether the applicant
*qualifies*, or at what rate. The eligibility and pricing logic is, in effect, a
hand-supplied constant — the simplest possible heuristic, decided by the caller and
threaded through the command to the client.

That is honest for a teaching slice, and it is exactly the kind of decision that, in
a real lending platform, you do *not* want welded into Java and redeployed every time
risk changes a threshold. Credit policy moves on its own clock: a regulator tightens
a debt-to-income ceiling, a product team launches a promo rate, a fraud signal gets a
new weight. Encoding those in compiled code makes every policy change a release. This
chapter is about the Firefly component built for precisely this seam —
**`fireflyframework-rule-engine`** — and about *where* it plugs into Lumen Lending so
that the decision step becomes data, authored in YAML, versioned in a table, and
evaluated reactively.

A note on honesty up front, because this is an honest chapter. The lending reactor
**does not wire the rule engine**. There is no `::: listing` slice here that the build
verifies, and there is no `mvn` command to run at the end — the code below is
*illustrative*, shown in standard fenced blocks, so you can see how a credit-eligibility
or pricing rule would be authored and evaluated. Everything described is real engine
behavior, drawn from `fireflyframework-rule-engine`'s own DSL and service layer; what is
*not* real is any claim that Lumen calls it today. The real moving parts this chapter
talks *around* — the `ProposeOfferCommand` and its `ProposeOfferHandler` (Chapter 10's
CQRS slice) and the `RegisterApplicationSaga` that drives them (Chapter 18) — are
genuine and verified; the rule engine is the piece that *would* slot between them. Read
this as the map of where decisioning belongs, not a tour of code already in place.

!!! note "Key term — rule engine"
    A **rule engine** evaluates business policy expressed as *data* — rules you can
    author, version, and change without recompiling the service. Firefly's is a
    stateless, reactive **expression-evaluation engine**: you hand it a YAML rule and
    a map of inputs, and it returns computed outputs plus a condition outcome and
    audit metadata. It is *not* a Drools-style inference engine — there is no working
    memory, no fact base, no forward-chaining. Each evaluation is an independent
    function call over one input map, which is exactly what a credit decision is.

## Where the heuristic lives, and where the engine would go

Find the seam first; the rest of the chapter fills it. In the domain tier the rate is
born as an argument: `LoanOriginationService.submitApplication` accepts `annualRateBps`
and packs it into a `ProposeOfferCommand`. That command rides through the saga's
`proposeOffer` step (Chapter 18) and lands in `ProposeOfferHandler` (Chapter 10's
CQRS slice), whose one job is to forward the number to the core over the SDK seam:

```java
// Today, in the slice: the offer's amount and rate arrive as arguments.
public Mono<SagaResult> submitApplication(String applicantName, long amount, int annualRateBps) {
    StepInputs inputs = StepInputs.builder()
        // ... root + applicant steps ...
        .forStepId(RegisterApplicationSaga.STEP_PROPOSE_OFFER,
            new ProposeOfferCommand(amount, annualRateBps))   // <-- hand-supplied heuristic
        .build();
    return sagaEngine.execute(RegisterApplicationSaga.SAGA_NAME, inputs);
}
```

The real handler is as thin as it gets — it does not decide anything, it relays:

```java
// Today, in the slice (verbatim shape): the handler just forwards the passed-in rate.
@Override
protected Mono<UUID> doHandle(ProposeOfferCommand command) {
    return client.proposeOffer(command.getLoanApplicationId(),
        command.getAmount(), command.getAnnualRateBps());   // rate came in as an argument
}
```

The `proposeOffer` step is therefore a *decision* in disguise: it asserts an applicant
is eligible and prices the loan, but the policy behind those numbers lives nowhere in
the code — it was decided by whoever called `submitApplication`. Swap in the rule engine
and the same handler becomes explicit and configurable. Conceptually, it gains a
dependency on the engine and asks it for a decision before calling the client:

```java
// Illustrative: the decision step consults a stored rule instead of a constant.
@CommandHandlerComponent
public class ProposeOfferHandler extends CommandHandler<ProposeOfferCommand, UUID> {

    private final LoanOriginationClient client;
    private final RulesEvaluationService rules;   // from fireflyframework-rule-engine

    @Override
    protected Mono<UUID> doHandle(ProposeOfferCommand command) {
        var request = new RuleEvaluationByCodeRequestDTO();
        request.setRuleDefinitionCode("credit-eligibility");        // a stored, versioned rule
        request.setInputData(Map.of(
            "creditScore", command.getCreditScore(),
            "annualIncome", command.getAnnualIncome(),
            "requestedAmount", command.getAmount()));
        return rules.evaluateRuleByCodeWithAudit(request, /* exchange */ null)
            .flatMap(result -> {
                var out = result.getOutputData();                  // computed outputs
                if (!"APPROVED".equals(out.get("approval_status"))) {
                    return Mono.error(new DeclinedException(out.get("reason")));
                }
                int rateBps = ((Number) out.get("annual_rate_bps")).intValue();
                return client.proposeOffer(command.getLoanApplicationId(),
                    command.getAmount(), rateBps);                 // rate now comes from policy
            });
    }
}
```

The shape is the lesson. Eligibility and pricing stop being arguments and become the
*outputs* of a rule named `credit-eligibility`, evaluated reactively inside the saga
step. A risk analyst can change the threshold or the rate band by editing YAML and
re-storing the rule — no code change, no redeploy. The rest of this chapter shows how
that rule is authored, stored, evaluated, audited, and operated.

Stay honest about the gap, though. Today's `ProposeOfferCommand` carries only `amount`,
`annualRateBps`, and the injected `loanApplicationId` — the illustrative handler's
`command.getCreditScore()` and `command.getAnnualIncome()` assume a *richer* command
that the slice does not have. Wiring the engine for real means two changes, not one:
carry the credit signals into the command (and back up the BFF channel that supplies
them), *and* replace the relay call with the by-code evaluation above. The chapter
shows the second, harder half; the first is ordinary plumbing the earlier tier chapters
already taught.

!!! spring "Spring parity"
    There is no plain-Spring equivalent that gives you this for free. In vanilla Spring
    Boot you would hand-roll a policy abstraction — a `@Service` reading thresholds from
    `@ConfigurationProperties`, or an in-house DSL — and rebuild the parser, caching,
    audit, and validation yourself. That is the same enterprise tax Chapter 1 named:
    every team reinvents decisioning slightly differently. Firefly's rule engine is one
    pre-wired, reactive answer, activated by adding `fireflyframework-rule-engine-core`
    to the classpath.

## Authoring a credit-eligibility rule in the DSL

A rule is a YAML document with a small required spine — `name`, `description`,
`inputs`, `output` — and a logic section. Here is the eligibility decision Lumen's
`proposeOffer` step would consult, written end to end:

```yaml
# credit-eligibility.yaml — authored by a risk analyst, stored by code
name: "Credit Eligibility"
description: "Decide loan eligibility and price the rate from credit score and income"
inputs:
  creditScore:
    type: number
    default: 0
  annualIncome:
    type: number
    default: 0
  requestedAmount:
    type: number
    default: 0
output: {approval_status: text, annual_rate_bps: number, reason: text}

constants:
  - code: MIN_SCORE
    defaultValue: 650
  - code: MIN_INCOME
    defaultValue: 50000

when:
  - creditScore at_least MIN_SCORE
  - annualIncome at_least MIN_INCOME
  - requestedAmount is_positive
then:
  - set approval_status to "APPROVED"
  - run annual_rate_bps as if_else(creditScore at_least 750, 1100, 1450)
  - set reason to "Meets score and income thresholds"
else:
  - set approval_status to "DECLINED"
  - set annual_rate_bps to 0
  - set reason to "Below minimum score or income"
```

Read it as the policy it is. The `when:` block is a list of conditions, AND-ed
together: the score must be **at least** the `MIN_SCORE` constant, income at least
`MIN_INCOME`, and the requested amount positive. When all hold, the `then:` actions
fire — set the status, compute a rate band with the inline `if_else` function (1100
basis points for prime credit, 1450 otherwise), and record a reason. When any fails,
`else:` declines. The three output variables named in `output:` are what the engine
returns to your handler.

Three naming conventions carry meaning, and the engine reads them automatically.
**Input variables are `camelCase`** (`creditScore`) and come from the input map you
pass. **Constants are `UPPER_CASE`** (`MIN_SCORE`) and are resolved from the rule's
`constants:` block or the shared constants store — which is how a threshold becomes a
value risk can change centrally, not a literal buried in a hundred rules. **Computed
variables are `snake_case`** (`annual_rate_bps`) and are created during evaluation.

!!! note "Key term — comparison, logical, and expression conditions"
    The DSL offers three flavors of condition. **Comparison** conditions test one value
    against another with 30-plus operators — `at_least`, `equals`, `in_list`,
    `is_positive`, `is_credit_score`, `matches`, and so on. **Logical** conditions
    combine them with `and`, `or`, and `not`, with parentheses for grouping:
    `(creditScore at_least 650 AND annualIncome greater_than 40000) OR hasGuarantor equals true`.
    **Expression** conditions evaluate arithmetic and function calls inline —
    `debt_ratio at_most 0.4` where `debt_ratio` was just `calculate`d. A credit policy
    uses all three.

The action vocabulary is just as expressive. `set` assigns; `calculate` evaluates pure
arithmetic (`+ - * / % **`) with real operator precedence; `run` invokes a function.
The engine ships a deep built-in library — mathematical (`max`, `round`, `sqrt`),
statistical (`avg`, `sum`), string (`upper`, `format`, `concat`), date (`datediff`,
`calculate_age`), and a financial suite tailored to exactly this domain:

```yaml
# Illustrative then: fragment — pricing and risk math the engine ships built in
then:
  - calculate monthly_income as annualIncome / 12
  - run debt_ratio as debt_to_income_ratio(monthlyDebt, monthly_income)
  - run payment as calculate_loan_payment(requestedAmount, annual_rate_bps, termMonths)
  - run ltv as loan_to_value(requestedAmount, collateralValue)
  - if debt_ratio greater_than 0.43 then circuit_breaker "DTI_EXCEEDED"
```

`debt_to_income_ratio`, `calculate_loan_payment`, and `loan_to_value` are real engine
built-ins — the lending math you would otherwise reimplement is already there. The
last line shows the **circuit_breaker** action: a hard stop that terminates evaluation
early with a labelled outcome, the rule-author's equivalent of "decline immediately,
no further questions."

!!! warning "`if_else` evaluates both branches"
    The DSL has no C-style `? :` operator; use the `if_else(condition, then, else)`
    function. But it is *not* short-circuiting — **both** the then- and else-value
    expressions are evaluated eagerly, then one is selected. If a branch calls an
    expensive function or a `rest_get`, it runs regardless of the condition. Keep
    branch expressions cheap, or gate the costly work behind a separate `when:`/`then:`
    instead of burying it in an `if_else`.

## How rules are stored and versioned

A rule authored as a string is fine for a unit test, but a lending platform needs rules
that operations can manage. The engine persists them. The models module defines a
`RuleDefinition` R2DBC entity backed by a `rule_definitions` table, and the service
layer exposes CRUD over it. The table's shape tells you what "managed" means here:

```sql
-- from fireflyframework-rule-engine-models: the stored-rule schema
CREATE TABLE rule_definitions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(100) NOT NULL UNIQUE,   -- stable handle: "credit-eligibility"
    name VARCHAR(200) NOT NULL,
    yaml_content TEXT NOT NULL,          -- the DSL document itself
    version VARCHAR(20),                 -- semantic version of this rule
    is_active BOOLEAN NOT NULL DEFAULT true,
    tags VARCHAR(500),
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

Two columns matter most for decisioning. **`code`** is the stable, unique handle your
service evaluates by — `credit-eligibility` — so the saga step never hardcodes YAML; it
names a rule the platform owns. **`version`** carries the rule's semantic version, and
`is_active` flags which definitions may be evaluated, with `created_by`/`updated_by`
and timestamps recording *who* changed policy and *when*. Together these make a credit
rule auditable as a governed artifact: you can see that `credit-eligibility` is at
version `2.3.0`, who last edited it, and whether it is live.

That is where the rule engine's versioning honestly stops. Storing one row per `code`
gives you the *current* definition plus its version label and authorship trail; it is a
governed store, not a full version-control system with a history of every prior YAML
body. If you need to roll back to last quarter's policy or diff two revisions, you layer
that on — keep rules in Git as the source of truth and store the active one, or model
your own history table. The engine gives you the stable handle, the version label, and
the authorship columns; the surrounding governance is yours to design.

!!! spring "Spring parity"
    `RuleDefinition` is a plain Spring Data R2DBC entity and the repository is a plain
    reactive repository — the same persistence you met in Chapter 8. Nothing exotic:
    the engine stores rules the way Lumen stores a `loan_application`. What it adds on
    top is the parser, evaluator, and cache that turn a stored YAML string into a
    runnable decision.

## Evaluating reactively — direct, by-code, and batch

Evaluation is reactive end to end, returning a `Mono`, which is why it drops cleanly
into a saga step's pipeline. There are three ways in.

The lowest-level path is the **direct** engine: inject `ASTRulesEvaluationEngine` and
evaluate a YAML string with an input map, no database involved. This is the unit-test
path — handy for proving a rule before you store it:

```java
// Illustrative: evaluate a YAML rule string directly, no persistence
Map<String, Object> inputs = Map.of(
    "creditScore", 720,
    "annualIncome", 84_000L,
    "requestedAmount", 15_000L);

Mono<ASTRulesEvaluationResult> result = engine.evaluateRulesReactive(ruleYaml, inputs);
// result.isSuccess(), result.getOutputData(), result.getExecutionTimeMs()
```

The production path is **by-code**: evaluate a stored rule by its `code` through
`RulesEvaluationService.evaluateRuleByCodeWithAudit`. This is the call the
`ProposeOfferHandler` above would make — it loads `credit-eligibility` from the store,
evaluates it against the input map, and (as the method name promises) writes an audit
record. Over REST the same surface is `POST /api/v1/rules/evaluate/by-code`:

```bash
# Illustrative: ask the stored credit rule for a decision
curl -X POST http://localhost:8080/api/v1/rules/evaluate/by-code \
  -H 'Content-Type: application/json' \
  -d '{
        "ruleDefinitionCode": "credit-eligibility",
        "inputData": { "creditScore": 720, "annualIncome": 84000, "requestedAmount": 15000 },
        "includeDetails": true
      }'
```

The third path is **batch**. A lending platform does not only decide one application at
a time; it re-scores a book of loans when policy changes, or pre-qualifies a marketing
segment overnight. `BatchRulesEvaluationService` (and `POST /api/v1/rules/batch/evaluate`)
scores many input sets against a rule in one call, returning per-row results plus
aggregate statistics, with companion `validate`, `statistics`, and `health` endpoints.
The same `credit-eligibility` rule that prices one new application can re-rank ten
thousand existing ones — without a bespoke batch job.

!!! spring "Spring parity"
    Because every entry point returns a `Mono`, the engine composes into the same
    reactive pipeline Lumen already runs. The live `proposeOffer` step does
    `commandBus.send(command)` and chains downstream operators on the result; a
    rule-backed step would just `.flatMap` `evaluateRuleByCodeWithAudit(...)` into that
    same chain — no blocking bridge, no `block()`, no thread hand-off. A vanilla-Spring
    decision service that returned a plain value (or worse, blocked on a database read)
    would force exactly the kind of reactive seam Chapter 5 warned against. The engine
    speaks `Mono` natively, so it disappears into the flow.

!!! note "Key term — AST"
    The engine does not interpret YAML text on every call. It parses each rule once
    into an **Abstract Syntax Tree** — a typed tree of condition, expression, and action
    nodes — and evaluates *that*. The AST is what makes evaluation fast and type-safe,
    and it is the hottest thing the engine caches (next section). When the DSL reference
    talks about "AST-based parsing," this tree is what it means: your YAML becomes
    structure, and structure is what runs.

## Caching definitions and ASTs

Parsing YAML and loading a row from PostgreSQL on every decision would be wasteful when
the same `credit-eligibility` rule fires thousands of times a minute. The engine caches
through the Firefly cache abstraction (Chapter 20) — Caffeine by default, Redis-pluggable
for distributed deployments — under the `firefly.rules.cache` prefix. Four caches matter:

```yaml
# Illustrative: the rule engine's cache configuration (real default keys)
firefly:
  rules:
    cache:
      provider: CAFFEINE          # set to REDIS for distributed evaluation
      caffeine:
        ast-cache:                # parsed ASTs — the hottest cache
          maximum-size: 1000
          expire-after-write: 2h
        rule-definitions-cache:   # stored definitions loaded from the database
          maximum-size: 200
          expire-after-write: 10m
        constants-cache:          # shared constants
        validation-cache:         # validation results
```

The **AST cache** is the one that earns its keep: a rule is parsed once and the tree is
reused, so the per-decision cost is evaluation, not re-parsing. The **definitions cache**
keeps hot stored rules out of the database. Because it runs on the Firefly cache
abstraction, switching from in-process Caffeine to a shared Redis — so every instance in
the fleet evaluates the same cached policy — is the one-property change you meet in
Chapter 20: set `firefly.rules.cache.provider` to `REDIS`.

!!! warning "Cached ASTs mean stored-rule edits are not instant"
    The flip side of caching is staleness. Edit `credit-eligibility` in the store and
    instances holding the old AST keep using it until the cache entry expires (default
    two hours) or is evicted. For policy that must change *now* — a regulator-mandated
    threshold — plan an explicit cache eviction or a shorter TTL into your operational
    runbook, rather than assuming a re-store takes effect on the next request.

## Validation before you store

A bad rule should be caught at authoring time, not when a customer's application hits
it. The engine exposes YAML validation as its own endpoint — `POST /api/v1/validation/yaml`
(and a lighter `/syntax`) — which checks both DSL syntax and the naming conventions
(`camelCase` inputs, `UPPER_CASE` constants, `snake_case` computed variables) before a
rule is ever evaluated. The rule-definitions CRUD surface validates on write too, so a
malformed `credit-eligibility` cannot be stored active.

```bash
# Illustrative: validate a rule's YAML before storing it
curl -X POST http://localhost:8080/api/v1/validation/yaml \
  -H 'Content-Type: application/json' \
  -d '{ "yamlContent": "name: ...\ninputs: [creditScore]\n..." }'
```

This is the gate that makes analyst-authored policy safe. A risk analyst edits YAML, the
validation endpoint reports a misspelled operator or a mis-cased variable, and the rule
is corrected *before* it can decline a real applicant for the wrong reason. Validation
results are themselves cached, so re-validating an unchanged rule is cheap.

## The audit trail

Every credit decision is a regulated event: you must be able to say, months later, why a
specific applicant was approved or declined, against which rule, with which inputs. The
engine records this automatically. The models module defines an `AuditTrail` entity and
`AuditTrailService` writes one record per evaluation — which is exactly why the
production entry point is named `evaluateRuleByCodeWithAudit`. The trail is queryable by
entity, user, or operation type, with statistics and cleanup, and surfaces over
`/api/v1/audit/trails`.

For a lending decision, the value is concrete: the audit record ties the decision to the
rule `code` and `version` that produced it, the input data, and the outcome. When an
auditor asks "why was application X declined in March?", the answer is a query, not an
archaeology dig through logs. This is the capability that makes a configurable decision
*defensible* — change the policy freely, but never lose the record of which policy decided
which case.

!!! spring "Spring parity"
    Chapter 6's RFC 7807 error model and this audit trail are complementary
    cross-cutting concerns: one makes failures uniform at the HTTP edge, the other makes
    *decisions* traceable in the data layer. Neither is something you would want each
    team to reinvent — and both ship pre-wired, the recurring Firefly bargain.

## Compiling a rule to Python

The most surprising feature is portability. `PythonCodeGenerator` /
`PythonCompilationService` emit an *equivalent Python function* for a stored rule,
exposed at `POST /api/v1/python/compile` and cached like everything else. The same
`credit-eligibility` YAML that the Java engine evaluates reactively can be compiled to a
standalone Python function that computes the identical decision.

Why would a lending platform want this? Two reasons. First, **offline execution** — a
data-science team backtesting a new credit policy against years of historical
applications wants to run it in a notebook, in pandas, at scale, without standing up the
Java service. Second, **external-runtime portability** — a model-serving platform, an
ETL job, or a partner's environment that speaks Python can execute the *same* policy the
production service uses, from a single authored source. The YAML stays the one source of
truth; the Python is a generated artifact that keeps a non-JVM runtime in lockstep with
the live rule.

This is genuinely where the engine's "rule as data, not code" thesis pays off: a credit
policy authored once becomes a reactive decision in production *and* a Python function
for analysis, with no second implementation to drift.

## What you learned {.recap}

- Lumen Lending's slice makes its credit decision with a **hand-supplied heuristic** —
  `LoanOriginationService` packs a fixed `amount` and `annualRateBps` into
  `ProposeOfferCommand`, the `proposeOffer` saga step dispatches it, and
  `ProposeOfferHandler` relays the rate straight to the core client. The reactor does
  **not** wire the rule engine; this chapter is the map of where it would plug in.
- `fireflyframework-rule-engine` is a **stateless, reactive expression-evaluation
  engine**: you give it a YAML rule and an input map, it returns computed outputs, a
  condition outcome, and audit metadata. It is not Drools — no working memory, no
  inference — which suits a credit decision, an independent function call over one
  payload.
- Rules are authored in a **YAML DSL** with comparison, logical, and expression
  conditions; a rich action vocabulary (`set`, `calculate`, `run`, `circuit_breaker`);
  and built-in financial functions (`calculate_loan_payment`, `debt_to_income_ratio`).
  Naming conventions — `camelCase` inputs, `UPPER_CASE` constants, `snake_case` computed
  values — carry meaning the engine reads automatically.
- Rules are **stored and versioned** as `RuleDefinition` rows keyed by a stable `code`,
  evaluated **reactively** three ways (direct, by-code, batch), **cached** as parsed ASTs
  and definitions through the Firefly cache abstraction, **validated** at a dedicated
  endpoint, and **audited** on every evaluation — and can even be **compiled to Python**
  for offline scoring and external runtimes.
- The decision step's plug-point in Lumen is the **`ProposeOfferHandler`** — today a
  one-line relay that forwards the passed-in `annualRateBps` to the core client.
  Replacing that line with a by-code evaluation of a `credit-eligibility` rule (and
  enriching `ProposeOfferCommand` to carry the credit signals) turns implicit policy
  into governed, changeable data — no redeploy when risk moves a threshold.

## Try it yourself {.exercises}

These exercises author and reason about rules in the DSL. There is no reactor module to
run them against — the rule engine is not wired into Lumen — so treat the YAML as the
deliverable, checking your work against the DSL conventions in this chapter.

1. **Author a minimal eligibility rule.** Write a complete YAML rule named
   `"Basic Eligibility"` with `inputs: [creditScore, annualIncome]`, a `MIN_SCORE`
   constant defaulting to `620`, a `when:` that requires the score `at_least MIN_SCORE`
   and income `greater_than 30000`, and a `then:`/`else:` that sets `approval_status` to
   `"APPROVED"` or `"DECLINED"`. Confirm every variable follows the right casing.
2. **Add a priced rate band.** Extend your rule so the `then:` block also sets an
   `annual_rate_bps` output: use `run annual_rate_bps as if_else(creditScore at_least 760,
   999, 1399)`. Declare `annual_rate_bps` in the `output:` map. Why must the variable be
   `snake_case` and not `annualRateBps`?
3. **Guard with a circuit breaker.** Add an input `existingDebtRatio` and a line that
   stops evaluation early — `if existingDebtRatio greater_than 0.43 then circuit_breaker
   "DTI_EXCEEDED"` — placed so it runs before the approval logic. Describe, in one
   sentence, what the engine returns when the breaker trips.
4. **Sketch the plug-in.** Re-read the illustrative `ProposeOfferHandler` in this chapter
   and the real one in Chapter 10's domain code — the real `doHandle` is a single line,
   `client.proposeOffer(command.getLoanApplicationId(), command.getAmount(),
   command.getAnnualRateBps())`. Write three or four sentences naming exactly which line
   changes, what new dependency the handler gains, where the rate now comes from, and
   what `ProposeOfferCommand` itself must carry that it does not today.
5. **Trace the audit.** Suppose `credit-eligibility` declines an applicant. Name the two
   columns of the stored `RuleDefinition` an auditor needs (the chapter calls them the
   handle and the version label), and the method whose name promises the trail gets
   written. In a sentence, say why the by-code path — not the direct engine — is the one
   you wire into production.
6. **Justify the Python compile.** In a short paragraph, argue why compiling
   `credit-eligibility` to Python keeps a backtesting notebook *honest* — that is, why a
   generated artifact from the live YAML is safer than a data scientist re-implementing
   the policy by hand.

## Where to go next

This chapter framed the rule engine as the home for configurable decisioning and showed,
honestly, that Lumen's slice does not yet live there. Chapter 14 zooms back out to the
**four-tier model** itself — experience, domain, core, data — and why each tier picks its
starter and talks to the others over SDKs rather than a shared database. The decision
step you mapped here belongs to the domain tier; the next chapter explains the tier
boundaries that decision flows across.
