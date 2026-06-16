Twenty-three chapters ago Lumen Lending was an empty folder. Now it is a fleet:
an experience tier that shapes channel requests, a domain tier that orchestrates a
saga and emits events, and a core tier that owns the schema and serves RFC 7807
problem details — all reactive, all version-coherent, all built from tier starters
you added in a single line. You have been *consuming* Firefly the whole way. This
closing chapter turns the lens around twice: first to show how you *extend* the
framework with your own capability, and then how you take any of these services to
production as a native image with a signed bill of materials.

The two halves rhyme. Extending Firefly means writing the same kind of toggleable,
override-friendly auto-configuration the framework writes for itself — so your code
behaves like a first-class capability, not a bolt-on. Going to production means
leaning on the *parent POM* you met in Chapter 3, which already carries a GraalVM
native profile and a CycloneDX SBOM step, so the whole fleet ships the same way.
Neither half adds a new companion test; this chapter is a guided tour of patterns
and build commands you have, in fact, been standing on since Chapter 3. Where a
listing appears, it is still a verbatim slice of the Lumen reactor — the experience
tier's client seam happens to be the cleanest example of every extension pattern at
once.

By the end you will know how to add a capability the Firefly way, hide a vendor
behind a port, package the result as a starter, and turn `mvn -Pnative` into a
container that boots in milliseconds. Then a short word on what lives *beyond* this
book, and a look back over the whole journey.

## Extending Firefly is writing Spring Boot auto-configuration

Here is the reassuring truth the whole book has been building toward: there is no
secret Firefly extension API. A Firefly capability *is* a Spring Boot
auto-configuration — a `@Configuration` class, gated by conditions, registered so
Spring Boot finds it on the classpath, that backs off the instant you define your
own bean. Everything you learned about `@ConditionalOnProperty` and
`@ConditionalOnMissingBean` in Chapter 1's "superset, never a fork" lens is the
extension mechanism. To add a capability to the fleet, you write exactly what the
framework writes for itself.

A capability has three moving parts, and the rest of this section walks each one:

1. A **port** — an interface your application code depends on, never a vendor SDK.
2. An **adapter** — an implementation of that port, plus a **`@Configuration`**
   that contributes it as a bean, gated so it activates by property and yields to
   any bean you define.
3. A **registration** — a `META-INF` import file so Spring Boot discovers the
   configuration without an explicit `@Import`, the same way every framework
   capability is discovered.

Lumen's experience tier already demonstrates all three for its domain-SDK seam.
It is the production wiring the BFF uses to reach the domain service, and it is
built precisely the way a framework capability is — so reading it is reading the
extension pattern.

!!! note "Key term — auto-configuration"
    A **Spring Boot auto-configuration** is a `@Configuration` class that Spring
    Boot applies automatically when it is on the classpath and its conditions
    pass — without the application importing it explicitly. Boot finds candidates
    by reading a registration file (see *registration* below), then evaluates each
    one's `@Conditional...` guards. Firefly's ~70 capabilities are all
    auto-configurations; the one you write to extend the fleet is no different.

## Step 1 — Depend on a port, never a vendor

The first move is the one Chapter 1 called "vendors behind ports" and Appendix B
collected in full: your application code talks to an *interface*, and the concrete
vendor never appears in a handler or service. The experience tier's contract to the
domain service is a hand-rolled reactive port — two methods, both returning `Mono`,
with no `WebClient`, no SDK type, no HTTP anywhere in the signature:

::: listing exp-lending/src/main/java/com/firefly/lumen/exp/client/LoanOriginationDomainClient.java | Listing 24.1 — the port: an interface your code depends on, vendor-free
public interface LoanOriginationDomainClient {

    /**
     * Submits a new loan application to the domain origination service.
     *
     * @param request        the channel-shaped create request, already validated by the BFF
     * @param idempotencyKey deterministic key so retries of the same logical request dedupe
     * @return the created application's detail view
     */
    Mono<ApplicationDetailDTO> submitApplication(CreateApplicationRequest request, String idempotencyKey);

    /**
     * Fetches a single loan application by its identifier.
     *
     * @param applicationId  the application's server-assigned identifier
     * @param idempotencyKey deterministic key for safe read retries
     * @return the application's detail view, or an empty {@link Mono} if it does not exist
     */
    Mono<ApplicationDetailDTO> getApplication(UUID applicationId, String idempotencyKey);
}
:::

This is the seam. The `ApplicationService` in the experience tier injects
`LoanOriginationDomainClient` and never learns whether the call goes over HTTP, an
in-memory stub, or — in the real firefly-oss service — a generated OpenAPI SDK. The
port owns the *contract*; the adapter owns the *mechanism*. When you extend Firefly
with a new integration — a fraud provider, a document store, a pricing engine — you
start here: write the interface your domain wants to call, in your domain's
vocabulary, and resist letting a vendor type leak into it.

!!! note "Key term — port and adapter (hexagonal)"
    A **port** is an interface that expresses what your application needs in its own
    terms; an **adapter** is a concrete implementation that fulfils the port against
    a specific technology. The application depends only on the port, so swapping the
    adapter — HTTP for a stub, one vendor for another — never touches business code.
    This is the hexagonal architecture Firefly uses for every integration capability,
    and the shape you copy when you add your own.

## Step 2 — Contribute the adapter as a gated bean

Now the implementation. The production adapter is an ordinary class that implements
the port by forwarding to a `WebClient`, propagating the deterministic idempotency
key as the standard `Idempotency-Key` header on every call:

::: listing exp-lending/src/main/java/com/firefly/lumen/exp/config/WebClientLoanOriginationDomainClient.java | Listing 24.2 — the adapter: one mechanism behind the port
class WebClientLoanOriginationDomainClient implements LoanOriginationDomainClient {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final String APPLICATIONS_PATH = "/api/v1/applications";

    private final WebClient webClient;

    WebClientLoanOriginationDomainClient(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public Mono<ApplicationDetailDTO> submitApplication(CreateApplicationRequest request, String idempotencyKey) {
        return webClient.post()
                .uri(APPLICATIONS_PATH)
                .header(IDEMPOTENCY_HEADER, idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(ApplicationDetailDTO.class);
    }

    @Override
    public Mono<ApplicationDetailDTO> getApplication(UUID applicationId, String idempotencyKey) {
        return webClient.get()
                .uri(APPLICATIONS_PATH + "/{id}", applicationId)
                .header(IDEMPOTENCY_HEADER, idempotencyKey)
                .retrieve()
                .bodyToMono(ApplicationDetailDTO.class);
    }
}
:::

Note the adapter is **package-private** — `class`, not `public class`. Nothing
outside the configuration package can reference it by type; callers see only the
port. That is intentional, and it is exactly how Firefly's own adapters are
shipped: the implementation is an internal detail, the port is the public surface.

The bean that contributes this adapter is where the capability earns the phrase
"behaves like the framework's own." It is gated by the same two conditions every
Firefly auto-configuration uses:

::: listing exp-lending/src/main/java/com/firefly/lumen/exp/config/LoanOriginationClientConfig.java | Listing 24.3 — the gated wiring: activates by property, yields to your bean
    @Bean
    @ConditionalOnProperty(prefix = "lumen.exp.loan-origination", name = "base-path")
    @ConditionalOnMissingBean
    public WebClient loanOriginationWebClient(LoanOriginationClientProperties properties) {
        log.info("Building Loan Origination WebClient basePath={} timeout={}",
                properties.basePath(), properties.timeout());
        return WebClient.builder()
                .baseUrl(properties.basePath())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_SIZE))
                .build();
    }

    /**
     * Production {@link LoanOriginationDomainClient}. In the real service this wraps the generated
     * domain SDK {@code LoanOriginationApi}; here it is a {@link WebClient}-backed adapter so the
     * wiring is faithful and chapters can slice it verbatim. Only created when a base path is set
     * and no other client bean (e.g. a test stub) is present.
     */
    @Bean
    @ConditionalOnProperty(prefix = "lumen.exp.loan-origination", name = "base-path")
    @ConditionalOnMissingBean
    public LoanOriginationDomainClient loanOriginationDomainClient(WebClient loanOriginationWebClient) {
        return new WebClientLoanOriginationDomainClient(loanOriginationWebClient);
    }
:::

Read the two annotations on the `loanOriginationDomainClient` bean, because between
them they *are* the Firefly contract:

- **`@ConditionalOnProperty(prefix = "lumen.exp.loan-origination", name = "base-path")`**
  — the production adapter materializes only when an operator points the experience
  tier at a real domain service by setting `lumen.exp.loan-origination.base-path`.
  No base path, no bean. This is "you opt in by adding configuration."
- **`@ConditionalOnMissingBean`** — if *anything else* has already contributed a
  `LoanOriginationDomainClient`, this method does not run. Lumen's tests register an
  in-memory stub, which wins automatically; a downstream team could declare their
  own client bean and override the framework's, no fork required. This is "the
  framework backs off the instant you define your own bean."

The bean is also bound to a typed `@ConfigurationProperties` record,
`LoanOriginationClientProperties`, so the base path and timeout are configured under
the `lumen.exp.loan-origination.*` namespace — the same `firefly.*`-style property
tree every capability exposes. A capability you write should bind its own
`@ConfigurationProperties` for exactly this reason: configuration is data, not code.

!!! spring "Spring parity"
    There is nothing Firefly-specific in any of the three listings — `@Configuration`,
    `@Bean`, `@ConditionalOnProperty`, `@ConditionalOnMissingBean`, and
    `@EnableConfigurationProperties` are all stock Spring Boot. That is the entire
    point. Firefly does not give you a new extension API to learn; it gives you a
    *house style* for using Spring Boot's own auto-configuration mechanism, so every
    team's capabilities are gated, overridable, and discoverable the same way. If you
    can write a Spring Boot starter, you can extend Firefly.

## Step 3 — Register the auto-configuration so Boot finds it

The Lumen sample places `LoanOriginationClientConfig` in the application's own
package, so component scanning picks it up directly. A *reusable* capability — one
you publish as a jar for other services to depend on — cannot rely on the consuming
application scanning your package. Instead you register it the way Spring Boot 3
discovers auto-configurations: a plain-text import file on the classpath.

Create `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
and list your configuration class, one fully-qualified name per line:

```text
# META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
com.acme.fraud.FraudCheckAutoConfiguration
```

Annotate the class `@AutoConfiguration` (a specialization of `@Configuration` that
also controls ordering relative to other auto-configurations) and keep the
`@ConditionalOnProperty` / `@ConditionalOnMissingBean` guards from Step 2:

```java
// Illustrative: a reusable capability, discovered by the imports file above.
@AutoConfiguration
@EnableConfigurationProperties(FraudCheckProperties.class)
@ConditionalOnProperty(prefix = "acme.fraud", name = "enabled", havingValue = "true")
public class FraudCheckAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public FraudCheckPort fraudCheckPort(FraudCheckProperties props, WebClient.Builder builder) {
        return new HttpFraudCheckAdapter(builder.baseUrl(props.baseUrl()).build());
    }
}
```

Now any service that adds your jar gets the capability automatically: Boot reads the
imports file, evaluates the conditions, and — if `acme.fraud.enabled` is `true` and
no `FraudCheckPort` already exists — contributes the adapter. Remove the jar and the
capability vanishes. Set the property to `false` and it stays dormant. Declare your
own `FraudCheckPort` bean and yours wins. That additive, reversible, override-anywhere
behavior is not something you bolted on — it falls out of using the same mechanism
the framework uses.

!!! warning "The imports file path and name are exact"
    Spring Boot 3 looks for precisely
    `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
    A typo in the directory, the filename, or the fully-qualified class name fails
    *silently* — Boot simply never finds your configuration, the conditions never
    evaluate, and your bean never appears, with no error to point at. If a capability
    "isn't activating," check this file first. (This replaces the older
    `META-INF/spring.factories` mechanism, which Boot 3 has removed for
    auto-configuration.)

## Step 4 — Package it as a tier-style starter

A capability is a jar a team can depend on. A **starter** is the Firefly idea one
level up: a thin, dependency-only module that bundles a coherent *set* of
capabilities plus their sensible defaults, so a service gets a whole posture from a
single dependency. You met four of them — `starter-core`, `starter-domain`,
`starter-data`, `starter-application` — across the book; each one pulls in the web
module, the right capabilities, and the production defaults (resilient clients,
idempotency, PII masking, JSON logging) for its tier.

You package your own starter the same way: a Maven module whose `pom.xml` declares
dependencies and ships *no code of its own* (or only a small auto-configuration).
The convention even names it for you — Firefly's modules are
`fireflyframework-starter-*`; an organization extending the fleet would publish, say,
`acme-starter-fraud`:

```xml
<!-- Illustrative: acme-starter-fraud/pom.xml — a starter is dependencies, not code. -->
<dependencies>
    <!-- The capability jar with the AutoConfiguration.imports file from Step 3. -->
    <dependency>
        <groupId>com.acme</groupId>
        <artifactId>acme-fraud-check</artifactId>
    </dependency>
    <!-- Pull in the Firefly web + resilience defaults the capability assumes. -->
    <dependency>
        <groupId>org.fireflyframework</groupId>
        <artifactId>fireflyframework-starter-domain</artifactId>
    </dependency>
</dependencies>
```

Because the Lumen reactor inherits the Firefly **parent POM** and imports the
**BOM** (Chapter 3), neither the capability jar nor the starter declares a single
version for a framework dependency — the BOM pins them all into the same
conflict-free set. A consuming service adds `acme-starter-fraud`, with no version,
and inherits a fully-wired fraud check that activates on one property. That is the
fifth Firefly move from Chapter 1 — "correct services in one dependency" — applied
to *your* capability rather than the framework's.

!!! tip "Checkpoint"
    You do not need a new test to confirm the extension pattern — you already ran it.
    Every chapter from 6 onward passed because the framework's auto-configurations
    activated by classpath presence and the sample's test stubs overrode them via
    `@ConditionalOnMissingBean`. Open `LoanOriginationClientConfig` (Listing 24.3)
    next to any test in `exp-lending/src/test`, and you are looking at the production
    bean and the test override that proves the back-off — the exact mechanism your own
    capability inherits.

## Going to production: native images via the parent's `-Pnative`

A Firefly service is a Spring Boot application, so it ships as a Spring Boot
application: a runnable fat jar from `mvn package`, or a layered OCI image from the
Spring Boot Maven plugin. The interesting production option — and one the parent POM
already wires for you — is a **GraalVM native image**: the application compiled
ahead of time to a standalone executable that boots in tens of milliseconds and uses
a fraction of the heap, at the cost of a longer build and closed-world assumptions.

You do not configure any of this per service. The Firefly parent POM you inherited in
Chapter 3 carries a `native` profile that activates the two plugins a native build
needs — Spring Boot's AOT processing and GraalVM's `native-maven-plugin` — and points
the Spring Boot image build at a Paketo buildpack. Activating it is one flag:

```text
mvn -Pnative -pl core-lending-loan-origination spring-boot:build-image
```

That command does three things the profile pre-wired for you. First, Spring Boot's
`process-aot` goal runs at build time: it evaluates your bean definitions, your
conditional auto-configurations, and your property bindings *once*, ahead of time,
and emits the reflection, resource, and proxy hints GraalVM needs. Second, the
Paketo `builder-jammy-tiny` buildpack builds the container with `BP_NATIVE_IMAGE`
set, so the GraalVM compiler produces a native executable rather than a JVM layer.
Third, the result is a minimal OCI image containing a single static-ish binary — no
JVM to warm up.

```text
# The parent's `native` profile sets these for you; you only pass -Pnative.
image:
  builder: paketobuildpacks/builder-jammy-tiny
  env:
    BP_NATIVE_IMAGE: true
```

The payoff is operational: a native Lumen service cold-starts in roughly the time a
JVM service spends loading classes, which makes scale-to-zero and rapid horizontal
scaling practical. The cost is real and worth naming. AOT compilation closes the
world — anything done by runtime reflection, dynamic proxies, or resource loading
that the AOT step could not see must be declared with hints. Because Firefly's
capabilities are ordinary Spring Boot auto-configurations and Spring Boot's AOT
engine understands them, most of the fleet's wiring is handled automatically; a
capability *you* write should be exercised under the native profile before you rely
on it in production.

!!! warning "Native images are closed-world; test the native binary"
    A service that passes every JVM test can still fail as a native image, because
    GraalVM cannot see reflection or resource access that happens only at runtime.
    The failure shows up at startup or first request in the native binary, not in
    your JVM tests. Treat `-Pnative` as a distinct build target: build the image,
    run the service's integration tests *against the running native container*, and
    only then ship it. The AOT step plus Spring Boot's hints cover the framework;
    your own reflective code is your responsibility to hint and verify.

!!! spring "Spring parity"
    The native profile is pure Spring Boot 3 plus GraalVM — `process-aot`, the
    `native-maven-plugin`, and Paketo buildpacks are exactly what you would wire in a
    plain Spring Boot project. Firefly's only contribution is that the parent POM
    carries the profile once, identically, for every service in the fleet, so you do
    not re-derive the plugin configuration in each `pom.xml`. You activate it with
    `-Pnative`; everything underneath is stock Boot.

## A bill of materials, automatically

Regulated platforms increasingly must answer "what, exactly, is inside this
artifact?" — every transitive dependency and its version — for vulnerability
scanning and supply-chain audits. The answer is a **software bill of materials
(SBOM)**, and the Firefly parent POM generates one on every build without you asking.

The parent binds the CycloneDX Maven plugin to the `package` phase. Its
`generate-sbom` execution runs `makeAggregateBom` and writes a CycloneDX document —
JSON, named `application.cdx`, under `META-INF/sbom/` *inside the built artifact* —
so the SBOM travels with the jar or image rather than as a loose side file:

```text
# Inherited from the parent POM — produced by an ordinary `mvn package`.
target/classes/META-INF/sbom/application.cdx.json
```

Because the document is CycloneDX, standard scanners (Grype, Trivy, Dependency-Track)
ingest it directly: you can gate a release on "no known critical CVE in the SBOM" and
prove, artifact by artifact, what shipped. Pair it with the native image and your
production shape is a small OCI container that boots in milliseconds and carries a
machine-readable, signable inventory of everything it contains — the deployment
posture a banking platform is held to, produced by the build you already run.

!!! note "Key term — SBOM (software bill of materials)"
    An **SBOM** is a complete, machine-readable inventory of the components in a
    software artifact — each dependency, its version, and its provenance. **CycloneDX**
    is one of the two common SBOM standards (SPDX is the other). The Firefly parent
    emits a CycloneDX SBOM on `package`, embedded in the artifact, so every service in
    the fleet ships an auditable inventory by default rather than by remembering to.

## Beyond this book

Two parallel efforts sit just outside these pages. The
**`fireflyframework-agentic-bridge`** connects the Java framework to a separately
documented Python agentic platform, so a Firefly service can participate in agent
workflows without leaving the reactive model you learned here. And the framework has
sibling **language ports** — PyFly (Python), and ongoing Rust, Go, and .NET
implementations — that carry the same opinions into other ecosystems. All four are
their own projects with their own documentation; this book stays in Java, on the JVM,
because that is where the journey you just finished lives.

## What you learned {.recap}

- **Extending Firefly is writing Spring Boot auto-configuration.** A capability is a
  `@Configuration` (or `@AutoConfiguration`) class gated by `@ConditionalOnProperty`
  and `@ConditionalOnMissingBean` — there is no separate extension API to learn.
- A capability has three parts: a **port** your code depends on (vendor-free), an
  **adapter** that fulfils it (package-private, behind the port), and a **gated bean**
  that activates by property and yields to any bean you define — exactly the shape of
  the experience tier's `LoanOriginationDomainClient`, its `WebClient` adapter, and
  its `LoanOriginationClientConfig`.
- A reusable capability is **registered** via
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
  so Boot discovers it; a **starter** bundles a coherent set of capabilities plus
  defaults into one dependency-only module, the way the four tier starters do.
- **Production** rides the parent POM: `-Pnative` builds a GraalVM native image via
  Spring Boot AOT and a Paketo buildpack (fast cold start, closed-world — verify the
  native binary), and a **CycloneDX SBOM** is emitted into every artifact on `package`
  for supply-chain audit.
- The **agentic bridge** and the **PyFly/Rust/Go/.NET ports** are parallel projects,
  documented separately and out of scope here.

## Try it yourself {.exercises}

1. **Add a property to the seam.** Give `LoanOriginationClientProperties` a new bound
   field — say a `maxRetries` int with a default — and read it in
   `LoanOriginationClientConfig` to configure the `WebClient`. Confirm it binds under
   `lumen.exp.loan-origination.max-retries` and defaults when absent. You have just
   extended a capability's configuration surface the Firefly way.
2. **Prove the back-off.** In an `exp-lending` test, define your own
   `LoanOriginationDomainClient` `@Bean`, set `lumen.exp.loan-origination.base-path`
   so the production bean *would* normally activate, and assert that your bean is the
   one injected. You have demonstrated `@ConditionalOnMissingBean` overriding the
   framework's default without a fork.
3. **Write the imports file.** Sketch a tiny reusable capability — a port, a
   package-private adapter, and an `@AutoConfiguration` class — and write the exact
   `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
   line that registers it. Name the failure mode if you misspell the path.
4. **Inspect a real SBOM.** Run `mvn -q -pl core-lending-loan-origination package` and
   open `target/classes/META-INF/sbom/application.cdx.json`. Find three Firefly
   modules and confirm their versions match the BOM from Chapter 3 — the SBOM is the
   version coherence of Chapter 1 made auditable.
5. **Plan a native build.** Without running it, list what `-Pnative` changes versus a
   plain `package`: which two plugins activate, what `process-aot` produces, and one
   reflective pattern in a capability of your own that would need a GraalVM hint.

## Where to go next

This is the last chapter, so "next" is your own fleet. You have built Lumen Lending
end to end — a customer can **apply** for a loan through the experience tier, have it
**scored** and **decided** by a domain saga, receive **offers**, and **accept** one,
with the core tier as the system of record and domain events announcing every step —
across four tiers that integrate over contracts, never a shared database. Every line
you read was a verified slice of a running reactor, and every cross-cutting
concern — RFC 7807 errors, idempotency, PII masking, transaction propagation,
context that survives operator boundaries — came from a starter you added in one
line. The enterprise tax that Chapter 1 named is paid once, in the framework, and
inherited by every service. Now go encode your own platform's hard-won decisions the
same way: as a capability the whole fleet gets for free.
