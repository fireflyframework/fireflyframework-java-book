This appendix is a quick translation table for Spring Boot developers. For each
thing you already know how to do in plain Spring Boot, it names the Firefly way —
which is almost always *less* code, because the cross-cutting behavior is already
wired. Nothing here replaces Spring Boot; every left-hand entry still works.

## Project setup

| Plain Spring Boot | Firefly |
|---|---|
| `spring-boot-starter-parent` as parent | `fireflyframework-parent` (imports the Spring Boot & Cloud BOMs; coexists with a corporate parent) |
| Pin each dependency version | Import `fireflyframework-bom`; declare framework deps with no version |
| `spring-boot-starter-web` (servlet) | `fireflyframework-web` (reactive WebFlux; servlet stack excluded) |
| Choose a stack per service | Add a tier starter: `starter-core` / `starter-domain` / `starter-data` / `starter-application` |
| `spring init` / start.spring.io | `flywork create` from a tier archetype |

## Web and errors

| Plain Spring Boot | Firefly |
|---|---|
| `@RestController` returning a value | `@RestController` returning `Mono<T>` / `Flux<T>` |
| `@ControllerAdvice` + per-app error JSON | Throw `ResourceNotFoundException` / `BusinessException` / `ConflictException`; the framework emits RFC 7807 automatically |
| Hand-rolled `ResponseEntity` error bodies | Standard `ProblemDetail` shape, identical fleet-wide |
| DIY request de-duplication | `IdempotencyWebFilter` via `X-Idempotency-Key` (opt out with `@DisableIdempotency`) |
| Manual log scrubbing | Built-in PII masking appender |
| `springdoc` setup per service | `@EnableOpenApiGen` + generated reactive SDK |

## Validation

| Plain Spring Boot | Firefly |
|---|---|
| `@NotNull`, `@Size`, custom regex for IBANs/tax IDs | Finance constraints from `fireflyframework-validators`: `@ValidIban`, `@ValidBic`, `@ValidCreditCard`, `@ValidAmount`, `@ValidCurrencyCode`, `@ValidTaxId`, … |

## Data

| Plain Spring Boot | Firefly |
|---|---|
| Spring Data JPA / JDBC (blocking) | Spring Data **R2DBC** (reactive) via `fireflyframework-r2dbc` |
| `JpaRepository` | `ReactiveCrudRepository<T, ID>` |
| Hand-written `Pageable` plumbing | `PaginationRequest` → `PaginationResponse<T>` |
| Hand-built dynamic `Specification`s | The generic reflective filter engine (`FilterRequest<T>`, `@FilterableId`) |
| Flyway (added manually) | Flyway, bundled |

## Application logic

| Plain Spring Boot | Firefly |
|---|---|
| Service methods that both read and write | CQRS: `@CommandHandlerComponent` / `@QueryHandlerComponent` extending `CommandHandler<C,R>` / `QueryHandler<Q,R>`; dispatch on `CommandBus` / `QueryBus` |
| `ApplicationEventPublisher` + `@EventListener` (in-JVM only) | `@EventPublisher` / `@PublishResult` / `@EventListener` over a pluggable transport (in-JVM, Kafka, RabbitMQ, Postgres) |
| `@Transactional` across services (does not work) | A **saga**: `@Saga` / `@SagaStep` with compensation, run on the `SagaEngine` (or `@Tcc`, or `@Workflow`) |
| Manual `WebClient` + Resilience4j config | The unified `ServiceClient` (REST/SOAP/gRPC/GraphQL/WS) with built-in circuit breaker, retry, and `X-Transaction-Id` propagation |
| `@Cacheable` (Caffeine only, blocking) | `CacheAdapter` port: Caffeine L1 + Redis/Hazelcast L2, reactive, `CacheType.AUTO` |

## Cross-cutting

| Plain Spring Boot | Firefly |
|---|---|
| `@Scheduled` | `@ScheduledSaga` / `@ScheduledWorkflow` (durable, with recovery) |
| Spring Security (servlet) | Spring Security on WebFlux + `@Secure` / `@RequireContext`, and an IDP port with vendor adapters |
| Micrometer wiring per service | `firefly.{module}.{metric}` naming + OTel tracing, with Reactor context propagation enabled for you |
| `ThreadLocal` / MDC for correlation (breaks under Reactor) | `Hooks.enableAutomaticContextPropagation()` — trace and tenant context follow every operator |

## The one-line summary

Plain Spring Boot gives you the parts and lets you assemble them. Firefly assembles
the parts the same way in every service, and lets you change any of it by declaring
a bean. You are always writing Spring Boot — just never the same boilerplate twice.
