Este apéndice es una tabla de traducción rápida para desarrolladores de Spring Boot.
Para cada cosa que ya sabes hacer en Spring Boot puro, nombra la forma de Firefly,
que casi siempre es *menos* código, porque el comportamiento transversal ya viene
cableado. Nada de lo que hay aquí reemplaza a Spring Boot; cada entrada de la columna
izquierda sigue funcionando, y Firefly está construido *sobre* él. La versión del
framework que sigue este libro es la **26.06.01**, publicada en Maven Central bajo el
grupo `org.fireflyframework` y versionada con CalVer (`YY.MM.Patch`). El reactor de
acompañamiento `lumen-lending` — tres capas ejecutables, sin Docker — es el ejemplo
trabajado al que apuntan estas correspondencias.

Una nota sobre honestidad, que es la regla de todo el libro. Las filas de abajo
describen lo que ofrece el *framework*. El ejemplo `lumen-lending` ejercita una
porción fina de él: un `POST /api/v1/experience/lending/applications` real que devuelve
`201` con `status: SUBMITTED` y fluye experiencia → dominio (saga) → núcleo, todo sobre
HTTP en H2 en memoria. Las capacidades que el ejemplo *no* cablea — el motor de reglas,
la capa de datos, el event sourcing, ECM/firma electrónica, notificaciones/webhooks —
siguen siendo partes reales del framework; aquí se señalan como *cómo funcionan / dónde
se enchufan*, no como cosas que el ejemplo demuestra.

## Configuración del proyecto

| Spring Boot puro | Firefly |
|---|---|
| `spring-boot-starter-parent` como parent | `fireflyframework-parent` (importa los BOM de Spring Boot y Cloud; coexiste con un parent corporativo) |
| Fijar la versión de cada dependencia | Importa `fireflyframework-bom`; declara las dependencias del framework sin versión (alineadas con CalVer, p. ej. `26.06.01`) |
| `spring-boot-starter-web` (servlet) | `fireflyframework-web` (WebFlux reactivo; la pila de servlets queda excluida) |
| Elegir una pila por servicio | Añade un starter de capa: `starter-core` / `starter-domain` / `starter-data` / `starter-application` |
| `spring init` / start.spring.io | `flywork create` a partir de un arquetipo de capa |
| `spring-boot-maven-plugin` `repackage` para un jar ejecutable | El mismo plugin, cableado por el parent: `mvn spring-boot:run` o `java -jar target/<service>.jar` arranca cada capa sin configuración adicional |
| Base de Java fijada en tu POM | Java 25 por defecto; compila con el perfil `-Pjava21` para apuntar a Java 21 |

## Web y errores

| Spring Boot puro | Firefly |
|---|---|
| `@RestController` que devuelve un valor | `@RestController` que devuelve `Mono<T>` / `Flux<T>` |
| `@ControllerAdvice` + JSON de error por aplicación | Lanza excepciones semánticas — `ResourceNotFoundException` / `BusinessException` / `ConflictException` / `ValidationException` — y un único `GlobalExceptionHandler` en el starter mapea cada una al estado correcto y al cuerpo RFC 7807 automáticamente |
| Cuerpos de error `ResponseEntity` hechos a mano | Forma estándar `ProblemDetail` (RFC 7807), idéntica en toda la flota, servida como `application/problem+json` con `extensions` del framework (`traceId`, `spanId`, `severity`, `retryable`, `suggestion`, `category`) |
| Mapear tú mismo `MethodArgumentNotValidException` | Los fallos de bean-validation se convierten en un problem detail `400` con `type` `https://api.firefly.com/errors/validation_error` y un array `errors` por campo, sin escribir ningún advice |
| Des-duplicación de peticiones casera | `IdempotencyWebFilter` mediante `X-Idempotency-Key` (puedes desactivarla con `@DisableIdempotency`) |
| Generar/propagar un id de correlación a mano | `TransactionFilter` acuña un `X-Transaction-Id` por petición, lo registra y lo propaga aguas abajo a través de las llamadas de `ServiceClient` |
| Limpieza manual de logs | Un appender de logs con enmascaramiento de PII integrado redacta los campos sensibles (IBAN, números de tarjeta, identificadores fiscales, correos) antes de que lleguen al flujo de logs |
| Configurar `springdoc` por servicio | `@EnableOpenApiGen` + un SDK reactivo generado; el `title` de OpenAPI se deriva de `spring.application.name` |

## Validación

| Spring Boot puro | Firefly |
|---|---|
| `@NotNull`, `@Size`, regex personalizadas para IBAN/identificadores fiscales | Restricciones financieras de `fireflyframework-validators`: `@ValidIban`, `@ValidBic`, `@ValidCreditCard`, `@ValidAmount`, `@ValidCurrencyCode`, `@ValidTaxId`, … |
| Validación que se ejecuta dentro de tu método | Restricciones evaluadas por el framework antes de que se ejecute el cuerpo del controlador; una violación cortocircuita al `400` RFC 7807 mostrado arriba (el caso de `requestedAmount` negativo del ejemplo) |

## Datos

| Spring Boot puro | Firefly |
|---|---|
| Spring Data JPA / JDBC (bloqueante) | Spring Data **R2DBC** (reactivo) mediante `fireflyframework-r2dbc` (la capa de núcleo del ejemplo lo ejecuta sobre H2 en memoria) |
| `JpaRepository` | `ReactiveCrudRepository<T, ID>` |
| Fontanería de `Pageable` escrita a mano | `PaginationRequest` → `PaginationResponse<T>` |
| `Specification`s dinámicas construidas a mano | El motor de filtros reflexivo y genérico (`FilterRequest<T>`, `@FilterableId`) |
| Flyway (añadido manualmente) | Flyway, incluido; la capa de núcleo trae su propia migración y la ejecuta a través del driver JDBC en el arranque |
| `@ConfigurationProperties` + bean `WebClient` manual para llamar a otro servicio | Una configuración de cliente SDK: un `@ConfigurationProperties` tipado (p. ej. una clave de base-path) alimenta una costura respaldada por `ServiceClient`/`WebClient`, declarada con `@ConditionalOnProperty` + `@ConditionalOnMissingBean` para que se active solo cuando está configurada y nunca desplace un stub de test — exactamente como la capa de dominio del ejemplo alcanza el núcleo |

La capa de datos más rica — event sourcing, proyecciones, un almacén de solo
adición — es una capacidad del framework; el ejemplo persiste el estado directamente
con R2DBC y no cablea event sourcing, así que trátalo como *dónde se enchufa*, no como
algo que el reactor muestre.

## Lógica de aplicación

| Spring Boot puro | Firefly |
|---|---|
| Métodos de servicio que leen y escriben a la vez | CQRS: `@CommandHandlerComponent` / `@QueryHandlerComponent` que extienden `CommandHandler<C,R>` / `QueryHandler<Q,R>`; despacho en `CommandBus` / `QueryBus` |
| `ApplicationEventPublisher` + `@EventListener` (solo dentro de la JVM) | `@EventPublisher` / `@PublishResult` / `@EventListener` sobre un transporte conectable (en la JVM `APPLICATION_EVENT`, Kafka, RabbitMQ, Postgres); el ejemplo usa el transporte en la JVM |
| `@Transactional` entre servicios (no funciona) | Una **saga**: `@Saga` / `@SagaStep` con compensación, ejecutada en el `SagaEngine` (o `@Tcc`, o `@Workflow`) — la `RegisterApplicationSaga` del ejemplo dirige la escritura dominio → núcleo |
| `WebClient` manual + configuración de Resilience4j | El `ServiceClient` unificado (REST/SOAP/gRPC/GraphQL/WS) con circuit breaker, reintentos y propagación de `X-Transaction-Id` integrados |
| `@Cacheable` (solo Caffeine, bloqueante) | El port `CacheAdapter`: Caffeine L1 + Redis/Hazelcast L2, reactivo, `CacheType.AUTO` |
| Cadenas de `if/else` o Drools cableado a mano para la toma de decisiones | Un port de motor de reglas para reglas de negocio declarativas — *no cableado en el ejemplo*; la decisión del préstamo sigue dirigida por código, así que esto se muestra como dónde se enchufaría un motor de reglas detrás de un manejador de dominio |

## Transversal

| Spring Boot puro | Firefly |
|---|---|
| `@Scheduled` | `@ScheduledSaga` / `@ScheduledWorkflow` (duraderos, con recuperación) |
| Spring Security (servlet) | Spring Security sobre WebFlux + `@Secure` / `@RequireContext` a nivel de método, y un port de IDP con adaptadores de proveedor (el BFF del ejemplo lleva `@Secure` pero se ejecuta con `firefly.application.security.enabled=false` para uso local) |
| Cableado de Micrometer por servicio | Nomenclatura `firefly.{module}.{metric}` + tracing con OTel, con la propagación del contexto de Reactor habilitada por ti |
| `ThreadLocal` / MDC para correlación (se rompe bajo Reactor) | `Hooks.enableAutomaticContextPropagation()` — el contexto de traza y de tenant sigue a cada operador, de modo que `traceId`/`spanId` sobreviven a los saltos de hilo |
| Almacenamiento de documentos / firma electrónica pegado por aplicación | Un port de ECM (gestión documental) y un port de firma electrónica con adaptadores de proveedor — *no cableados en el ejemplo*; mostrados como dónde se conectan detrás de un servicio de dominio |
| Email/SMS/push y webhooks salientes codificados ad hoc | Un port de notificaciones y un port de despacho de webhooks — *no cableados en el ejemplo*; descritos como cómo se enchufan en la canalización de eventos |

## Una costura trabajada: el cliente SDK

La traducción de Spring a Firefly más común en un sistema multicapa es "cómo llama
el servicio A al servicio B". En Spring Boot puro escribes una clase
`@ConfigurationProperties` para la URL base, construyes un `@Bean` `WebClient` y
esperas que cada equipo lo haga igual. En Firefly esa misma forma es una *costura*
convencional: una clase de propiedades tipada, un bean de cliente protegido por
condiciones y un fallback por defecto para que el servicio siga arrancando de forma
aislada. La capa de dominio del ejemplo es el caso canónico —

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

Dos condiciones cargan con el peso. `@ConditionalOnProperty` significa que el cliente
en vivo solo se cablea cuando hay una base-path de núcleo configurada (lo está, en
`application.yml`, para la pila ejecutable). `@ConditionalOnMissingBean` significa que
se aparta en el momento en que un test aporta su propio stub de grabación — así los
tests de porción nunca llegan a la red, y ningún comportamiento de test cambia cuando
se publica la costura en vivo. Una `@AutoConfiguration` aparte proporciona un cliente
en la JVM por defecto (también `@ConditionalOnMissingBean`) para que el dominio arranque
de forma autónoma sin nada aguas abajo. Esta es toda la postura del framework en
miniatura: convención por defecto, anulable declarando un bean.

## El resumen de una línea

Spring Boot puro te da las piezas y te deja ensamblarlas. Firefly ensambla las piezas
de la misma manera en cada servicio, y te deja cambiar cualquiera de ellas declarando
un bean. Siempre estás escribiendo Spring Boot — solo que nunca el mismo boilerplate
dos veces.
