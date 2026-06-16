Este apéndice es una tabla de traducción rápida para desarrolladores de Spring Boot. Por
cada cosa que ya sabes hacer en Spring Boot puro, te indica la forma de hacerla en Firefly,
que casi siempre es *menos* código, porque el comportamiento transversal ya viene
cableado. Nada de lo que hay aquí reemplaza a Spring Boot; cada entrada de la columna izquierda sigue funcionando.

## Configuración del proyecto

| Spring Boot puro | Firefly |
|---|---|
| `spring-boot-starter-parent` como parent | `fireflyframework-parent` (importa los BOM de Spring Boot y Cloud; coexiste con un parent corporativo) |
| Fijar la versión de cada dependencia | Importar `fireflyframework-bom`; declarar las dependencias del framework sin versión |
| `spring-boot-starter-web` (servlet) | `fireflyframework-web` (WebFlux reactivo; la pila servlet queda excluida) |
| Elegir una pila por servicio | Añadir un starter de capa: `starter-core` / `starter-domain` / `starter-data` / `starter-application` |
| `spring init` / start.spring.io | `flywork create` a partir de un arquetipo de capa |

## Web y errores

| Spring Boot puro | Firefly |
|---|---|
| `@RestController` que devuelve un valor | `@RestController` que devuelve `Mono<T>` / `Flux<T>` |
| `@ControllerAdvice` + JSON de error por aplicación | Lanza `ResourceNotFoundException` / `BusinessException` / `ConflictException`; el framework emite RFC 7807 automáticamente |
| Cuerpos de error con `ResponseEntity` hechos a mano | Forma `ProblemDetail` estándar, idéntica en toda la flota |
| Deduplicación de peticiones casera | `IdempotencyWebFilter` mediante `X-Idempotency-Key` (puedes desactivarlo con `@DisableIdempotency`) |
| Limpieza manual de logs | Appender de enmascarado de PII integrado |
| Configuración de `springdoc` por servicio | `@EnableOpenApiGen` + SDK reactivo generado |

## Validación

| Spring Boot puro | Firefly |
|---|---|
| `@NotNull`, `@Size`, regex personalizadas para IBAN/NIF | Restricciones financieras de `fireflyframework-validators`: `@ValidIban`, `@ValidBic`, `@ValidCreditCard`, `@ValidAmount`, `@ValidCurrencyCode`, `@ValidTaxId`, … |

## Datos

| Spring Boot puro | Firefly |
|---|---|
| Spring Data JPA / JDBC (bloqueante) | Spring Data **R2DBC** (reactivo) mediante `fireflyframework-r2dbc` |
| `JpaRepository` | `ReactiveCrudRepository<T, ID>` |
| Cableado de `Pageable` escrito a mano | `PaginationRequest` → `PaginationResponse<T>` |
| `Specification`s dinámicas construidas a mano | El motor de filtros reflectivo y genérico (`FilterRequest<T>`, `@FilterableId`) |
| Flyway (añadido manualmente) | Flyway, ya incluido |

## Lógica de aplicación

| Spring Boot puro | Firefly |
|---|---|
| Métodos de servicio que leen y escriben a la vez | CQRS: `@CommandHandlerComponent` / `@QueryHandlerComponent` que extienden `CommandHandler<C,R>` / `QueryHandler<Q,R>`; despacho en `CommandBus` / `QueryBus` |
| `ApplicationEventPublisher` + `@EventListener` (solo dentro de la JVM) | `@EventPublisher` / `@PublishResult` / `@EventListener` sobre un transporte conectable (en la JVM, Kafka, RabbitMQ, Postgres) |
| `@Transactional` entre servicios (no funciona) | Una **saga**: `@Saga` / `@SagaStep` con compensación, ejecutada en el `SagaEngine` (o `@Tcc`, o `@Workflow`) |
| Configuración manual de `WebClient` + Resilience4j | El `ServiceClient` unificado (REST/SOAP/gRPC/GraphQL/WS) con circuit breaker, reintentos y propagación de `X-Transaction-Id` integrados |
| `@Cacheable` (solo Caffeine, bloqueante) | Puerto `CacheAdapter`: Caffeine L1 + Redis/Hazelcast L2, reactivo, `CacheType.AUTO` |

## Aspectos transversales

| Spring Boot puro | Firefly |
|---|---|
| `@Scheduled` | `@ScheduledSaga` / `@ScheduledWorkflow` (duraderos, con recuperación) |
| Spring Security (servlet) | Spring Security sobre WebFlux + `@Secure` / `@RequireContext`, y un puerto de IDP con adaptadores de proveedor |
| Cableado de Micrometer por servicio | Nomenclatura `firefly.{module}.{metric}` + trazado con OTel, con la propagación de contexto de Reactor activada por ti |
| `ThreadLocal` / MDC para correlación (se rompe con Reactor) | `Hooks.enableAutomaticContextPropagation()`: el contexto de traza y de tenant acompaña a cada operador |

## El resumen en una línea

Spring Boot puro te da las piezas y deja que las ensambles. Firefly ensambla
las piezas de la misma forma en cada servicio, y te deja cambiar cualquiera de ellas declarando
un bean. Siempre estás escribiendo Spring Boot, solo que nunca el mismo boilerplate dos veces.
