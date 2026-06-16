Un servicio core es dueño de sus datos. Hasta aquí, el servicio de originación de
préstamos de Lumen Lending tiene un controlador, DTOs y un servicio que devuelve
valores prefabricados; este capítulo le da una capa de persistencia real. Al
terminar, una solicitud de préstamo que envíes por POST se escribe en una tabla
relacional, se vuelve a leer por id y se lista por estado — de forma reactiva, de
extremo a extremo, sin ninguna llamada bloqueante en todo el recorrido.

Vas a construir seis piezas: una entidad R2DBC con `@Table`, un enum de estado, un
repositorio reactivo, una migración Flyway que crea el esquema, un mapeador
MapStruct entre la entidad y el DTO, y el servicio de aplicación que los une. La
mayor parte se parecerá a Spring Data corriente — porque lo es. La única parte
realmente delicada, y el mejor momento didáctico del capítulo, es un truco de cuatro
líneas con `Persistable` que le dice a R2DBC si un `save` debe hacer INSERT o UPDATE
cuando eres *tú* quien asigna la clave primaria. Si lo haces mal, tu segunda lectura
sobrescribe en silencio en lugar de insertar; si lo haces bien, es invisible para
siempre.

Todo lo de aquí vive en `core-lending-loan-origination`, el servicio que actúa como
sistema de registro del mapa de cuatro capas del Capítulo 1. Los cores son dueños del
esquema y de los datos; exponen un CRUD reactivo sobre R2DBC y nunca comparten una
base de datos con otra capa. Esta es la capa de datos de ese core.

!!! warning "R2DBC, no JPA"
    El preludio advertía que JPA, JDBC y Hibernate son bloqueantes y no tienen cabida
    en la pila reactiva. Esa advertencia es fundamental aquí. Si recurres a
    `@Entity`, `EntityManager` o `JpaRepository`, vuelves al mundo de los servlets y
    bloquearás el bucle de eventos. Todo lo de este capítulo es Spring Data **R2DBC**
    — la historia relacional reactiva que presentó el preludio.

## La entidad: una `@Table` de R2DBC

Spring Data R2DBC mapea una clase Java sencilla a una tabla. No hay proveedor JPA, ni
carga perezosa, ni sesión con dirty-checking — solo un mapeo ligero de columnas a
campos y viceversa. Marcas la clase con `@Table`, la clave primaria con `@Id` y cada
columna con `@Column`, nombrando explícitamente la columna snake_case de la base de
datos para que el campo Java en camelCase y el nombre SQL puedan diferir sin
sorpresas.

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/domain/LoanApplication.java | Listado 8.1 — el mapeo de tabla R2DBC y la clave primaria UUID
@Table("loan_application")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoanApplication implements Persistable<UUID> {

    @Id
    @Column("loan_application_id")
    private UUID loanApplicationId;

    /** Stable public reference, distinct from the surrogate primary key. */
    @Column("application_number")
    private UUID applicationNumber;

    /** Applicant who owns this request (soft reference; no cross-context FK). */
    @Column("applicant_id")
    private UUID applicantId;

    /** Requested principal, stored as a fixed-scale decimal. */
    @Column("requested_amount")
    private BigDecimal requestedAmount;

    /** ISO-4217 currency code of {@link #requestedAmount} (e.g. {@code EUR}). */
    @Column("currency")
    private String currency;

    /** Requested repayment term in whole months. */
    @Column("term_months")
    private Integer termMonths;

    /** Free-text purpose of the loan (e.g. {@code HOME_IMPROVEMENT}). */
    @Column("purpose")
    private String purpose;

    @Column("status")
    private ApplicationStatus status;

    /** Reason captured when the application is rejected or cancelled. */
    @Column("decision_reason")
    private String decisionReason;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("updated_at")
    private LocalDateTime updatedAt;
:::

Hay varias cosas en las que fijarse. Las anotaciones de Lombok (`@Data`, `@Builder`,
`@NoArgsConstructor`, `@AllArgsConstructor`) generan el código repetitivo —
accesores, un builder y los dos constructores que necesita el mapeo de R2DBC. La
clave primaria es un `UUID`, no un `BIGSERIAL` generado por la base de datos; la
aplicación se lo asigna antes del primer guardado, lo que mantiene los ids opacos y
permite que un llamante acuñe uno sin un viaje de ida y vuelta. Y el campo `status`
es el enum `ApplicationStatus`, que R2DBC almacena como una cadena en una columna
`VARCHAR` — más sobre esto al hablar de la migración.

!!! note "Termino clave — clave subrogada frente a clave de negocio"
    `loanApplicationId` es la **clave subrogada**: un UUID interno sin más significado
    que la identidad, usado para joins y búsquedas. `applicationNumber` es una **clave
    de negocio**: una referencia pública estable que puedes citarle a un cliente.
    Mantenerlas separadas significa que puedes cambiar cómo se genera cualquiera de
    ellas sin romper la otra — y el repositorio puede buscar una solicitud por
    cualquiera de las dos.

## El enum de estado

El ciclo de vida de una solicitud es un pequeño conjunto cerrado de estados, así que
es un enum. R2DBC no necesita ninguna configuración especial para persistirlo: por
defecto mapea un enum a su `name()` como cadena, que es exactamente lo que espera la
columna de estado `VARCHAR(32)`.

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/domain/ApplicationStatus.java | Listado 8.2 — el enum del ciclo de vida, almacenado como cadena
public enum ApplicationStatus {

    /** Captured but not yet submitted for review. */
    DRAFT,

    /** Submitted by the applicant; awaiting a credit officer. */
    SUBMITTED,

    /** A credit officer is actively reviewing the application. */
    UNDER_REVIEW,

    /** Approved; an offer may be proposed to the applicant. */
    APPROVED,

    /** Declined; a terminal state. */
    REJECTED,

    /** Withdrawn before a decision was reached; a terminal state. */
    CANCELLED;

    /**
     * @return {@code true} if no further transition is allowed from this state
     */
    public boolean isTerminal() {
        return this == APPROVED || this == REJECTED || this == CANCELLED;
    }
}
:::

Almacenar el `name()` en lugar del ordinal es la opción segura: añadir o reordenar
constantes del enum más adelante nunca reetiqueta en silencio las filas existentes.
La columna es lo bastante ancha (`VARCHAR(32)`) para el nombre más largo, con margen
para crecer.

## El truco de `Persistable`: insertar frente a actualizar

Aquí está la parte sutil. El `save` de Spring Data tiene que decidir, para cada
entidad, si emite un `INSERT` o un `UPDATE`. Con un id generado por la base de datos,
la regla es fácil: un id `null` significa "nunca se ha guardado, así que INSERT" y un
id no nulo significa "ya tiene clave, así que UPDATE". Esa heurística es la que usa
Spring Data por defecto.

Pero Lumen asigna el UUID *él mismo*, antes del primer guardado. Para cuando la
entidad llega al repositorio su id ya no es nulo — así que la heurística por defecto
concluye "esto debe ser una actualización", emite un `UPDATE ... WHERE id = ?`,
coincide con cero filas, y la inserción que pretendías sencillamente nunca ocurre.
Este es un clásico tiro al pie de R2DBC con claves asignadas por el cliente, y falla
en silencio: ninguna excepción, solo una fila que nunca se escribió.

La solución es dejar de permitir que R2DBC adivine. Implementa `Persistable<UUID>` y
responde a la pregunta de forma explícita con un método `isNew()`, respaldado por un
indicador `@Transient` que nunca se escribe en la base de datos.

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/domain/LoanApplication.java | Listado 8.3 — decirle a R2DBC "esto es nuevo" de forma explícita
    /**
     * Transient flag (never persisted) that tells Spring Data R2DBC whether a
     * {@code save} should INSERT or UPDATE. Because the primary key is a
     * client-assigned UUID rather than a DB-generated value, R2DBC cannot infer
     * "new vs. existing" from a null id; {@link Persistable} makes it explicit.
     */
    @Transient
    @Builder.Default
    private boolean newEntity = false;

    /** {@inheritDoc} */
    @Override
    public UUID getId() {
        return loanApplicationId;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isNew() {
        return newEntity;
    }

    /**
     * Marks this aggregate as a freshly created entity so the next {@code save}
     * performs an INSERT.
     *
     * @return this aggregate, for chaining
     */
    public LoanApplication markNew() {
        this.newEntity = true;
        return this;
    }
:::

La anotación `@Transient` mantiene `newEntity` fuera del mapeo, de modo que no hay
columna `new_entity` y el indicador nunca llega al cable. `getId()` devuelve la clave
subrogada; `isNew()` devuelve el indicador. Cuando el servicio está a punto de
guardar una solicitud completamente nueva llama a `markNew()`, R2DBC ve
`isNew() == true`, y se emite un `INSERT` independientemente del id no nulo. En
cualquier `save` posterior la entidad cargada tiene `newEntity == false`, así que se
emite un `UPDATE` — que es exactamente lo correcto.

!!! warning "Pon el indicador en false por defecto, no en true"
    El indicador es `false` por defecto para que una solicitud *cargada* desde la base
    de datos — que nunca llama a `markNew()` — se trate correctamente como una
    actualización. Solo el camino de creación opta por INSERT. Ponerlo en `true` por
    defecto haría que cada save tras una lectura intentara reinsertar una fila que ya
    existe, y obtendrías errores de clave duplicada en lugar de actualizaciones.

!!! spring "Equivalente en Spring"
    Esto es Spring Data puro, no una añadidura de Firefly. En Spring Data JPA rara vez
    te topas con este problema porque Hibernate rastrea el estado de la entidad en su
    contexto de persistencia. R2DBC no tiene sesión ni dirty-checking, así que cuando
    eres *tú* el dueño de la clave debes ser también el dueño de la decisión de
    insertar/actualizar — y `Persistable<T>` es el enganche estándar y documentado de
    Spring Data para hacer exactamente eso. Firefly no cambia nada de esto; solo
    mantiene la pila reactiva subyacente sin bloqueos.

## El repositorio reactivo

Con la entidad en su sitio, el repositorio es una sola línea más dos consultas
derivadas. Extiende `ReactiveCrudRepository<LoanApplication, UUID>` y Spring Data
genera una implementación reactiva en tiempo de ejecución: `save` devuelve
`Mono<LoanApplication>`, `findById` devuelve `Mono<LoanApplication>`, `findAll`
devuelve `Flux<LoanApplication>`. Añades métodos de búsqueda *nombrándolos*, y Spring
Data analiza el nombre para construir una consulta.

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/persistence/LoanApplicationRepository.java | Listado 8.4 — un repositorio reactivo con consultas derivadas
public interface LoanApplicationRepository
        extends ReactiveCrudRepository<LoanApplication, UUID> {

    /**
     * @param status lifecycle state to match
     * @return every application currently in {@code status}
     */
    Flux<LoanApplication> findByStatus(ApplicationStatus status);

    /**
     * @param applicationNumber the stable public reference
     * @return the matching application, if any
     */
    Mono<LoanApplication> findByApplicationNumber(UUID applicationNumber);
}
:::

`findByStatus` devuelve un `Flux` porque muchas solicitudes pueden compartir un
estado; `findByApplicationNumber` devuelve un `Mono` porque la clave de negocio es
única. No escribes SQL ni una clase de implementación — los nombres de los métodos
*son* la consulta, y Spring Data construye `WHERE status = ?` y
`WHERE application_number = ?` por ti.

!!! note "Termino clave — consulta derivada"
    Una **consulta derivada** es un método de repositorio cuyo nombre Spring Data
    analiza para construir una consulta: `findBy` + nombres de propiedades + palabras
    clave opcionales (`And`, `OrderBy`, `Between`). Es la forma más rápida de añadir
    un buscador, sin SQL que mantener — a costa de nombres de método largos cuando los
    criterios crecen. Para algo más rico, anotas el método con `@Query`, o — para
    filtrado de listas abierto — recurres al motor de filtrado que se describe al
    final de este capítulo.

## El esquema: una migración Flyway

R2DBC mapea a una tabla; algo tiene que *crear* esa tabla. Lumen usa **Flyway**, que
aplica scripts de migración SQL versionados en orden y registra cuáles se han
ejecutado, de modo que el esquema es reproducible desde una base de datos vacía y
evoluciona en pasos rastreados. Un archivo de migración se nombra
`V<version>__<description>.sql`; Flyway ejecuta `V1` antes que `V2`, cada uno una sola
vez, y se niega a cambiar en silencio uno que ya se ha aplicado.

::: listing core-lending-loan-origination/src/main/resources/db/migration/V1__loan_application.sql | Listado 8.5 — la primera migración crea la tabla
CREATE TABLE loan_application (
    loan_application_id UUID PRIMARY KEY,
    application_number  UUID NOT NULL,
    applicant_id        UUID NOT NULL,
    requested_amount    NUMERIC(19, 2) NOT NULL,
    currency            VARCHAR(3) NOT NULL,
    term_months         INTEGER NOT NULL,
    purpose             VARCHAR(255) NOT NULL,
    status              VARCHAR(32) NOT NULL,
    decision_reason     VARCHAR(1000),
    created_at          TIMESTAMP NOT NULL,
    updated_at          TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX ux_loan_application_application_number
    ON loan_application (application_number);

CREATE INDEX ix_loan_application_status
    ON loan_application (status);
:::

Lee esto frente al Listado 8.1 columna por columna: cada `@Column("...")` mapea a una
columna de aquí. `loan_application_id` es la `PRIMARY KEY` (la subrogada);
`application_number` lleva un índice `UNIQUE` porque la clave de negocio no debe
repetirse; `status` recibe un índice simple porque `findByStatus` filtra por él. El
enum aterriza en `VARCHAR(32)`, el importe en `NUMERIC(19, 2)` para guardar dinero
sin error de coma flotante, y las dos marcas de tiempo de auditoría son `NOT NULL`.

Este mismo script se ejecuta sin cambios contra H2 en memoria en las pruebas y contra
un Postgres con forma de producción — está escrito deliberadamente en DDL portable y
compatible con H2, de modo que la prueba que ejecutarás dentro de un momento ejercita
la migración *real*, no un mock.

!!! spring "Equivalente en Spring"
    Esto es Spring Boot de serie. La autoconfiguración de Flyway ve `flyway-core` en
    el classpath y ejecuta al arrancar todo lo que haya bajo
    `src/main/resources/db/migration`. En la pila reactiva, Flyway sigue usando una
    conexión JDBC bloqueante de vida corta *solo al arrancar* para aplicar las
    migraciones — y eso está bien, porque ocurre una vez antes de que el bucle de
    eventos empiece a atender tráfico; el camino de la petición sigue siendo R2DBC y
    sin bloqueos. Firefly deja este cableado exactamente como lo entrega Spring Boot.

## El mapeador: de entidad a DTO con MapStruct

El repositorio trata con entidades; la capa web trata con DTOs. **MapStruct** genera
el código de conversión en tiempo de compilación a partir de una interfaz que
declaras, de modo que obtienes un mapeo rápido y de bajo consumo de memoria, sin
reflexión y sin bucles de copia escritos a mano. Declarar `componentModel = SPRING`
hace que la implementación generada sea un bean de Spring que puedes inyectar como
cualquier otro.

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/mapper/LoanApplicationMapper.java | Listado 8.6 — un mapeador MapStruct, mitad generado y mitad escrito a mano
@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.IGNORE
)
public interface LoanApplicationMapper {

    /**
     * Projects a persisted aggregate to its API response shape.
     *
     * @param entity the persisted application
     * @return the response DTO
     */
    LoanApplicationResponse toResponse(LoanApplication entity);

    /**
     * Builds a new, unsaved {@link LoanApplication} from a create request,
     * normalising the currency and starting the aggregate in
     * {@link com.firefly.lumen.core.domain.ApplicationStatus#DRAFT}. The
     * identifiers and timestamps are assigned by the service before persisting.
     *
     * @param request the validated create request
     * @return a transient entity ready to be submitted and saved
     */
    default LoanApplication toNewEntity(CreateLoanApplicationRequest request) {
        return LoanApplication.builder()
                .applicantId(request.applicantId())
                .requestedAmount(request.requestedAmount())
                .currency(request.currency() == null ? null : request.currency().toUpperCase())
                .termMonths(request.termMonths())
                .purpose(request.purpose())
                .build();
    }
}
:::

`toResponse` está completamente generado: MapStruct empareja los campos de la entidad
con los del DTO por nombre y escribe la implementación por ti, así que la proyección
mecánica de entidad a respuesta te cuesta una firma de método. `toNewEntity` es un
método `default` que escribes a mano, porque construir una solicitud nueva no es una
copia campo a campo — normaliza la moneda a mayúsculas y deja deliberadamente sin
asignar el id, el estado y las marcas de tiempo, porque es el *servicio* quien es
dueño de esos valores por defecto. Mezclar métodos generados y escritos a mano en un
mismo mapeador es MapStruct idiomático: deja que genere las copias aburridas y toma el
control donde hay lógica real.

## El servicio: uniéndolo todo de forma reactiva

El servicio de aplicación es donde se componen las piezas. Recibe por constructor el
repositorio y el mapeador, está marcado con `@Transactional`, y devuelve tipos
reactivos en todo momento — de modo que nada se bloquea. Aquí es también donde el
truco de `Persistable` rinde frutos: el camino de creación acuña los ids, fija las
marcas de tiempo, envía la solicitud a través de su método de dominio, llama a
`markNew()` y solo entonces guarda.

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/service/LoanApplicationService.java | Listado 8.7 — los caminos de creación y lectura, totalmente reactivos
    /**
     * Opens a new application, submits it, persists it, and returns the view.
     *
     * @param request the validated create request
     * @return the persisted application as a response DTO
     */
    public Mono<LoanApplicationResponse> create(CreateLoanApplicationRequest request) {
        LoanApplication application = mapper.toNewEntity(request);
        application.setLoanApplicationId(UUID.randomUUID());
        application.setApplicationNumber(UUID.randomUUID());
        application.setStatus(ApplicationStatus.DRAFT);
        application.setCreatedAt(LocalDateTime.now());
        application.setUpdatedAt(LocalDateTime.now());
        application.submit();
        application.markNew();
        return repository.save(application).map(mapper::toResponse);
    }

    /**
     * Fetches one application by its surrogate id.
     *
     * @param id the loan-application id
     * @return the application
     * @throws ResourceNotFoundException if no application has that id
     */
    @Transactional(readOnly = true)
    public Mono<LoanApplicationResponse> getById(UUID id) {
        return repository.findById(id)
                .map(mapper::toResponse)
                .switchIfEmpty(Mono.error(
                        new ResourceNotFoundException("Loan application not found: " + id)));
    }
:::

Sigue el camino de creación: construye una entidad transitoria a partir de la
petición, asigna ambos UUIDs, estampa el estado y las marcas de tiempo, ejecuta
`submit()` (el método de dominio que mueve la solicitud de `DRAFT` a `SUBMITTED`),
luego `markNew()` para que R2DBC emita un `INSERT`. El save devuelve
`Mono<LoanApplication>`, y `.map(mapper::toResponse)` lo convierte en el
`Mono<LoanApplicationResponse>` que devuelve el controlador. Ningún `.block()`, ningún
`subscribe()` — el framework se suscribe en el borde.

El camino de lectura muestra el modelo de errores del framework. `findById` devuelve
un `Mono` vacío cuando no hay fila; `switchIfEmpty` convierte ese vacío en una
`ResourceNotFoundException`, y la capa web la renderiza automáticamente como un
problem detail RFC 7807. No hay ningún 404 escrito a mano en ninguna parte —
exactamente la promesa de "un único modelo de errores, en todas partes" del
Capítulo 1.

El método `list` lo completa, alternando entre `findAll` y la consulta derivada
`findByStatus` según se haya proporcionado o no un filtro de estado:

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/service/LoanApplicationService.java | Listado 8.8 — listado, con y sin filtro de estado
    @Transactional(readOnly = true)
    public Flux<LoanApplicationResponse> list(ApplicationStatus status) {
        Flux<LoanApplication> source = (status == null)
                ? repository.findAll()
                : repository.findByStatus(status);
        return source.map(mapper::toResponse);
    }
:::

!!! spring "Equivalente en Spring"
    `@Transactional` también funciona en la pila reactiva, pero gestiona una
    transacción *reactiva* ligada al contexto de Reactor en lugar de a un
    thread-local — razón por la cual nunca debe envolver una llamada bloqueante.
    `@Transactional(readOnly = true)` en los caminos de lectura es la misma pista que
    darías en MVC: permite a la capa de datos saltarse el trabajo de dirty-tracking en
    las consultas. Las mismas anotaciones, con semántica reactiva por debajo.

## Ejecútalo

Como la migración es DDL portable y la prueba arranca contra H2 en memoria con el
runtime de R2DBC, toda la capa de persistencia es verificable sin Docker y sin una
base de datos externa. La prueba de slice del controlador crea una solicitud por
HTTP, la vuelve a leer, comprueba el problem detail 404 y rechaza un payload inválido
— todo contra el esquema real migrado por Flyway. Ejecútala desde
`samples/lumen-lending`:

```text
mvn -q -pl core-lending-loan-origination test
```

Deberías ver pasar las tres pruebas:

```text
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
```

!!! tip "Punto de control"
    Ejecuta `mvn -q -pl core-lending-loan-origination test` desde
    `samples/lumen-lending`. Un `Tests run: 3, Failures: 0` en verde significa que
    Flyway aplicó `V1__loan_application.sql` a H2, que el indicador `Persistable`
    impulsó un `INSERT` real, que `findById` volvió a leer la fila, y que un id
    inexistente produjo un 404 RFC 7807 — todo el viaje de ida y vuelta, verificado.
    Si la prueba de creación falla con un síntoma de clave duplicada o "0 rows
    updated", lo primero que hay que comprobar es la llamada a `markNew()` y el valor
    por defecto del indicador `isNew()`.

## El camino de producción para los endpoints de listado

`list(status)` es honesto pero tosco: un único filtro opcional, sin paginación, sin
ordenación. Un servicio core real expone endpoints de listado con filtrado
arbitrario, paginación estable y un sobre de respuesta consistente — y el slice de
originación de préstamos de Lumen mantiene esa superficie pequeña a propósito. La
respuesta del framework, que usan los servicios Firefly de producción, es un **motor
de filtrado reflexivo genérico** emparejado con un par
`PaginationRequest`/`PaginationResponse`. Describes un DTO de filtro, anotas los
campos que pueden filtrarse, y el motor construye la consulta y la respuesta paginada
por ti — sin explosión de métodos derivados, sin código repetitivo de paginación por
endpoint.

Conceptualmente, una petición filtrable y una llamada paginada tienen este aspecto.
(Esto es un boceto ilustrativo de la forma del framework — el slice de originación de
préstamos construido no cablea el motor de filtrado; usa las consultas derivadas de
arriba.)

```java
// Illustrative: the framework's filter + pagination surface, not Lumen's slice.
public record LoanApplicationFilter(
        @FilterableId UUID applicantId,
        ApplicationStatus status) {}

Mono<PaginationResponse<LoanApplicationResponse>> page =
        filterService.filter(
                new LoanApplicationFilter(applicantId, ApplicationStatus.SUBMITTED),
                PaginationRequest.of(/* page */ 0, /* size */ 20));
```

Lo que importa es el *camino*, no la sintaxis: cuando un endpoint de listado supera
una consulta derivada, no fabricas a mano DTOs de page/size/sort y un constructor de
consultas a medida en cada servicio — el impuesto empresarial que nombró el Capítulo
1. Declaras el filtro, anotas los campos filtrables, y heredas un endpoint de listado
uniforme, reactivo y paginado. El slice de Lumen se queda con las consultas derivadas
porque dos buscadores son todo lo que necesita; recurre al motor de filtrado en el
momento en que aparezca una superficie de filtrado real.

!!! note "Termino clave — `@FilterableId`"
    `@FilterableId` marca un campo de tipo id en un DTO de filtro como algo por lo que
    el motor puede filtrar, con el manejo de tipos correcto para claves UUID. Es la
    forma del motor de filtrado de decir "este campo es un objetivo de filtro seguro e
    indexado" — la habilitación explícita que evita que el filtrado reflexivo se
    convierta en una superficie de consulta abierta sobre todos los campos.

## Lo que has construido {.recap}

- Una **entidad `@Table` de Spring Data R2DBC**, `LoanApplication`, con un `@Id` UUID,
  mapeos `@Column` en snake_case y un enum `ApplicationStatus` almacenado como cadena.
- El **truco de `Persistable<UUID>`**: un indicador `@Transient` `newEntity` más
  `isNew()`, de modo que R2DBC emite un `INSERT` para las claves asignadas por el
  cliente en lugar de un `UPDATE` silencioso de cero filas — y un `UPDATE` para todo
  lo cargado desde la base de datos.
- Un **repositorio reactivo** que extiende `ReactiveCrudRepository` con dos consultas
  derivadas (`findByStatus`, `findByApplicationNumber`) y sin SQL escrito a mano.
- Una **migración Flyway**, `V1__loan_application.sql`, en DDL portable que se ejecuta
  sobre H2 en las pruebas y sobre Postgres en producción desde el mismo archivo.
- Un **mapeador MapStruct** que genera `toResponse` y escribe a mano `toNewEntity`, y
  un **servicio** `@Transactional` que los compone de forma reactiva — con una
  `ResourceNotFoundException` que el framework renderiza como un 404 RFC 7807.
- Un `Tests run: 3, Failures: 0` en verde de
  `mvn -q -pl core-lending-loan-origination test`, demostrando el viaje de ida y
  vuelta contra el esquema real migrado.

## Pruebalo tu mismo {.exercises}

1. **Observa cómo falla el truco.** En `LoanApplicationService.create`, comenta la
   línea `application.markNew()` y vuelve a ejecutar
   `mvn -q -pl core-lending-loan-origination test`. Observa cómo se rompe la prueba de
   creación, luego restaura la línea y explica en una frase por qué el id UUID no nulo
   hizo que R2DBC eligiera `UPDATE`.
2. **Añade un buscador derivado.** Añade `Flux<LoanApplication> findByApplicantId(UUID
   applicantId)` a `LoanApplicationRepository` y un método de servicio fino al estilo
   `list` que lo use. Sin SQL — deja que el nombre del método cargue la consulta.
3. **Extiende el esquema.** Añade una columna `risk_band VARCHAR(16)` a
   `V1__loan_application.sql` y un campo `@Column("risk_band")` correspondiente en
   `LoanApplication`, luego confirma que la prueba de slice sigue en verde contra el
   esquema H2 migrado.
4. **Mapea un campo nuevo.** Expón tu campo `risk_band` en `LoanApplicationResponse` y
   confirma que el `toResponse` generado por MapStruct lo recoge automáticamente por
   nombre — sin necesidad de cambiar la interfaz del mapeador.
5. **Esboza el camino de producción.** Sin cablearlo, escribe un record
   `LoanApplicationFilter` con un id de solicitante `@FilterableId` y un estado, y
   anota qué método de servicio existente reemplazaría una vez que el endpoint de
   listado necesite paginación.

## Adonde ir ahora

El core ahora es dueño de sus datos. Las siguientes capas hacia arriba de la pila
ponen este servicio a trabajar: la capa de dominio orquesta estas operaciones CRUD en
flujos de negocio sobre SDKs generados, y una saga coordina el camino de decisión de
varios pasos con compensacion cuando un paso falla. La capa de persistencia que has
construido aquí es el sistema de registro al que todo lo que está por encima acaba
escribiendo.
