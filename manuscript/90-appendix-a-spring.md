This appendix is a quick translation table for Spring Boot developers. For each
thing you already know how to do in plain Spring Boot, it names the Firefly way —
which is almost always *less* code, because the cross-cutting behavior is already
wired. Nothing here replaces Spring Boot; every left-hand entry still works, and
Firefly is built *on* it. The framework version this book tracks is **26.06.01**,
published to Maven Central under the `org.fireflyframework` group and versioned
with CalVer (`YY.MM.Patch`). The companion `lumen-lending` reactor — three runnable
tiers, no Docker — is the worked example these mappings point back to.

A note on honesty, which is the rule for the whole book. The rows below describe
what the *framework* offers. The `lumen-lending` sample exercises a thin slice of
it: a live `POST /api/v1/experience/lending/applications` that returns `201` with
`status: SUBMITTED` and flows experience → domain (saga) → core, all over HTTP on
in-memory H2. Capabilities the sample does *not* wire — the rule engine, the data
tier, event sourcing, ECM/e-signature, notifications/webhooks — are still real
parts of the framework; they are flagged here as *how they work / where they plug
in*, not as things the sample demonstrates.

## Project setup

| Plain Spring Boot | Firefly |
|---|---|
| `spring-boot-starter-parent` as parent | `fireflyframework-parent` (imports the Spring Boot & Cloud BOMs; coexists with a corporate parent) |
| Pin each dependency version | Import `fireflyframework-bom`; declare framework deps with no version (CalVer-aligned, e.g. `26.06.01`) |
| `spring-boot-starter-web` (servlet) | `fireflyframework-web` (reactive WebFlux; servlet stack excluded) |
| Choose a stack per service | Add a tier starter: `starter-core` / `starter-domain` / `starter-data` / `starter-application` |
| `spring init` / start.spring.io | `flywork create` from a tier archetype |
| `spring-boot-maven-plugin` `repackage` for an executable jar | Same plugin, wired by the parent: `mvn spring-boot:run` or `java -jar target/<service>.jar` boots each tier with no extra config |
| Java baseline pinned in your POM | Java 25 by default; build the `-Pjava21` profile to target Java 21 |

## Web and errors

| Plain Spring Boot | Firefly |
|---|---|
| `@RestController` returning a value | `@RestController` returning `Mono<T>` / `Flux<T>` |
| `@ControllerAdvice` + per-app error JSON | Throw semantic exceptions — `ResourceNotFoundException` / `BusinessException` / `ConflictException` / `ValidationException` — and one `GlobalExceptionHandler` in the starter maps each to the right status and RFC 7807 body automatically |
| Hand-rolled `ResponseEntity` error bodies | Standard `ProblemDetail` (RFC 7807) shape, identical fleet-wide, served as `application/problem+json` with framework `extensions` (`traceId`, `spanId`, `severity`, `retryable`, `suggestion`, `category`) |
| Map `MethodArgumentNotValidException` yourself | Bean-validation failures become a `400` problem detail with `type` `https://api.firefly.com/errors/validation_error` and a per-field `errors` array, no advice written |
| DIY request de-duplication | `IdempotencyWebFilter` via `X-Idempotency-Key` (opt out with `@DisableIdempotency`) |
| Generate/propagate a correlation id by hand | `TransactionFilter` mints an `X-Transaction-Id` per request, logs it, and propagates it downstream through `ServiceClient` calls |
| Manual log scrubbing | Built-in PII-masking log appender redacts sensitive fields (IBANs, card numbers, tax IDs, emails) before they hit the log stream |
| `springdoc` setup per service | `@EnableOpenApiGen` + a generated reactive SDK; the OpenAPI `title` is derived from `spring.application.name` |

## Validation

| Plain Spring Boot | Firefly |
|---|---|
| `@NotNull`, `@Size`, custom regex for IBANs/tax IDs | Finance constraints from `fireflyframework-validators`: `@ValidIban`, `@ValidBic`, `@ValidCreditCard`, `@ValidAmount`, `@ValidCurrencyCode`, `@ValidTaxId`, … |
| Validation that runs inside your method | Constraints evaluated by the framework before the controller body runs; a violation short-circuits to the RFC 7807 `400` shown above (the sample's negative-`requestedAmount` case) |

## Data

| Plain Spring Boot | Firefly |
|---|---|
| Spring Data JPA / JDBC (blocking) | Spring Data **R2DBC** (reactive) via `fireflyframework-r2dbc` (the sample's core tier runs this on in-memory H2) |
| `JpaRepository` | `ReactiveCrudRepository<T, ID>` |
| Hand-written `Pageable` plumbing | `PaginationRequest` → `PaginationResponse<T>` |
| Hand-built dynamic `Specification`s | The generic reflective filter engine (`FilterRequest<T>`, `@FilterableId`) |
| Flyway (added manually) | Flyway, bundled; the core tier ships its own migration and runs it via the JDBC driver at startup |
| `@ConfigurationProperties` + manual `WebClient` bean to call another service | An SDK-client config: a typed `@ConfigurationProperties` (e.g. a base-path key) drives a `ServiceClient`/`WebClient`-backed seam, declared `@ConditionalOnProperty` + `@ConditionalOnMissingBean` so it activates only when configured and never displaces a test stub — exactly how the sample's domain tier reaches core |

The richer data tier — event sourcing, projections, an append-only store — is a
framework capability; the sample persists state directly with R2DBC and does not
wire event sourcing, so treat that as *where it plugs in*, not as something the
reactor shows.

## Application logic

| Plain Spring Boot | Firefly |
|---|---|
| Service methods that both read and write | CQRS: `@CommandHandlerComponent` / `@QueryHandlerComponent` extending `CommandHandler<C,R>` / `QueryHandler<Q,R>`; dispatch on `CommandBus` / `QueryBus` |
| `ApplicationEventPublisher` + `@EventListener` (in-JVM only) | `@EventPublisher` / `@PublishResult` / `@EventListener` over a pluggable transport (in-JVM `APPLICATION_EVENT`, Kafka, RabbitMQ, Postgres); the sample uses the in-JVM transport |
| `@Transactional` across services (does not work) | A **saga**: `@Saga` / `@SagaStep` with compensation, run on the `SagaEngine` (or `@Tcc`, or `@Workflow`) — the sample's `RegisterApplicationSaga` drives the domain → core write |
| Manual `WebClient` + Resilience4j config | The unified `ServiceClient` (REST/SOAP/gRPC/GraphQL/WS) with built-in circuit breaker, retry, and `X-Transaction-Id` propagation |
| `@Cacheable` (Caffeine only, blocking) | `CacheAdapter` port: Caffeine L1 + Redis/Hazelcast L2, reactive, `CacheType.AUTO` |
| `if/else` chains or Drools wired by hand for decisioning | A rule-engine port for declarative business rules — *not wired in the sample*; the loan decision stays code-driven, so this is shown as where a rule engine would plug in behind a domain handler |

## Cross-cutting

| Plain Spring Boot | Firefly |
|---|---|
| `@Scheduled` | `@ScheduledSaga` / `@ScheduledWorkflow` (durable, with recovery) |
| Spring Security (servlet) | Spring Security on WebFlux + method-level `@Secure` / `@RequireContext`, and an IDP port with vendor adapters (the sample's BFF carries `@Secure` but runs with `firefly.application.security.enabled=false` for local use) |
| Micrometer wiring per service | `firefly.{module}.{metric}` naming + OTel tracing, with Reactor context propagation enabled for you |
| `ThreadLocal` / MDC for correlation (breaks under Reactor) | `Hooks.enableAutomaticContextPropagation()` — trace and tenant context follow every operator, so `traceId`/`spanId` survive thread hops |
| Document storage / e-signature glued in per app | An ECM port (document management) and an e-signature port with vendor adapters — *not wired in the sample*; shown as where they attach behind a domain service |
| Email/SMS/push and outbound webhooks coded ad hoc | A notifications port and a webhook-dispatch port — *not wired in the sample*; described as how they plug into the event pipeline |

## A worked seam: the SDK client

The single most common Spring-to-Firefly translation in a multi-tier system is
"how does service A call service B." In plain Spring Boot you write a
`@ConfigurationProperties` class for the base URL, build a `WebClient` `@Bean`, and
hope every team does it the same way. In Firefly the same shape is a conventional
*seam*: a typed properties class, a client bean guarded by conditions, and a default
fallback so the service still boots in isolation. The sample's domain tier is the
canonical example —

```java
@ConfigurationProperties(prefix = "firefly.lumen.core.loan-origination")
public record CoreLoanOriginationProperties(String basePath) { }

@AutoConfiguration
@ConditionalOnProperty(prefix = "firefly.lumen.core.loan-origination", name = "base-path")
@ConditionalOnMissingBean(LoanOriginationClient.class)
class LiveLoanOriginationClientConfig {

    @Bean
    LoanOriginationClient loanOriginationClient(CoreLoanOriginationProperties props) {
        // a WebClient/ServiceClient-backed seam pointed at the core base path
        return new WebClientLoanOriginationClient(props.basePath());
    }
}
```

Two conditions carry the weight. `@ConditionalOnProperty` means the live client
only wires when a core base path is configured (it is, in `application.yml`, for the
runnable stack). `@ConditionalOnMissingBean` means it steps aside the moment a
test supplies its own recording stub — so the slice tests never reach the network,
and no test behavior changes when the live seam ships. A separate `@AutoConfiguration`
provides a default in-JVM client (also `@ConditionalOnMissingBean`) so the domain
boots standalone with no downstream. This is the framework's whole posture in
miniature: convention by default, overridable by declaring a bean.

## The one-line summary

Plain Spring Boot gives you the parts and lets you assemble them. Firefly assembles
the parts the same way in every service, and lets you change any of it by declaring
a bean. You are always writing Spring Boot — just never the same boilerplate twice.
