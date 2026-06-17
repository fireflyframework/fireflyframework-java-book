Un servicio core es dueño de sus datos. Hasta aquí, el servicio de originación de
préstamos de Lumen Lending tiene un controlador, DTOs y un servicio que devuelve
valores prefabricados; este capítulo le da una capa de persistencia de verdad. Al
final, una solicitud de préstamo que envíes con un POST se escribe en una tabla
relacional, se vuelve a leer por id y se lista por estado — de forma reactiva, de
extremo a extremo, sin ninguna llamada bloqueante en todo el camino. Y no es un
experimento mental: la capa core ahora arranca sobre H2 en memoria y sirve CRUD en
vivo en el puerto `8081`, así que el mismísimo `POST` que sigues a continuación
deposita una fila real que un `GET` posterior vuelve a leer directamente.

Vas a construir seis piezas: una entidad R2DBC `@Table`, un enum de estado, un
repositorio reactivo, una migración de Flyway que crea el esquema, un mapeador
MapStruct entre entidad y DTO, y el servicio de aplicación que lo une todo. La mayor
parte se parecerá a Spring Data normal y corriente — porque lo es. La única parte
genuinamente espinosa, y el mejor momento didáctico del capítulo, es un truco de
cuatro líneas con `Persistable` que le dice a R2DBC si un `save` debe hacer un INSERT
o un UPDATE cuando eres *tú* quien asigna la clave primaria. Equivócate ahí y tu
segunda lectura sobrescribe en silencio en lugar de insertar; acierta y será
invisible para siempre.

Todo lo de aquí vive en `core-lending-loan-origination`, el servicio que es sistema
de registro del mapa de cuatro capas del Capítulo 1. Los cores son dueños del esquema
y de los datos; exponen CRUD reactivo sobre R2DBC y nunca comparten base de datos con
otra capa. Esta es la capa de datos de ese core — y es el suelo al que todo lo que
está por encima acaba escribiendo. Cuando la capa de experiencia acepta una petición
de canal y la capa de dominio ejecuta su `RegisterApplicationSaga`, el paso raíz de
la saga escribe *aquí*, en esta tabla, sobre HTTP. La persistencia que construyes en
este capítulo es la base de toda esa pila.

!!! warning "R2DBC, no JPA"
    El preludio advertía de que JPA, JDBC y Hibernate son bloqueantes y no tienen
    cabida en la pila reactiva. Esa advertencia es de carga estructural aquí. Si
    recurres a `@Entity`, `EntityManager` o `JpaRepository`, vuelves al mundo de los
    servlets y vas a atascar el bucle de eventos. Todo en este capítulo es Spring
    Data **R2DBC** — la historia relacional reactiva que presentó el preludio. La
    única excepción deliberada es Flyway, que usa una conexión JDBC de vida corta *solo
    al arrancar* para migrar el esquema; volveremos a por qué eso es seguro cuando
    lleguemos a la migración.

## Paso 1 — Define la entidad: una `@Table` de R2DBC

Spring Data R2DBC mapea una clase Java sencilla a una tabla. No hay proveedor JPA, ni
carga perezosa, ni sesión con detección de cambios — solo un mapeo ligero de columnas
a campos y vuelta. Marcas la clase con `@Table`, la clave primaria con `@Id` y cada
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

Hay un par de cosas en las que fijarse. Las anotaciones de Lombok (`@Data`,
`@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor`) generan el código repetitivo
— los accesores, un builder, los dos constructores que necesita el mapeo de R2DBC. La
clave primaria es un `UUID`, no un `BIGSERIAL` generado por la base de datos; la
aplicación se la asigna antes del primer save, lo que mantiene los ids opacos y
permite a un llamante acuñar uno sin un viaje de ida y vuelta. Y el campo `status` es
el enum `ApplicationStatus`, que R2DBC almacena como una cadena en una columna
`VARCHAR` — más sobre esto con la migración.

¿Por qué nombrar cada columna explícitamente, cuando R2DBC podría derivar
`applicant_id` de `applicantId` por sí mismo? Porque el nombre explícito es un
contrato. El campo Java se puede renombrar por legibilidad, o la columna de la BD se
puede mantener estable para un informe externo, sin que el otro derive en silencio. El
mapeo está en un solo sitio, en el código fuente, y la migración del Paso 4 debe
coincidir con él columna por columna — que es exactamente la comprobación cruzada que
ejecutarás allí.

!!! note "Término clave — clave subrogada vs. clave de negocio"
    `loanApplicationId` es la **clave subrogada**: un UUID interno sin significado más
    allá de la identidad, usado para joins y búsquedas. `applicationNumber` es una
    **clave de negocio**: una referencia pública estable que puedes citarle a un
    cliente. Mantenerlas separadas significa que puedes cambiar cómo se genera
    cualquiera de las dos sin romper la otra — y el repositorio puede buscar una
    solicitud por cualquiera de ellas.

!!! spring "Equivalente en Spring"
    `@Table`, `@Id` y `@Column` provienen de `spring-data-relational`, el paquete que
    Spring Data R2DBC comparte con Spring Data JDBC — no de `jakarta.persistence` de
    JPA. Parecen las anotaciones JPA del mismo nombre, pero no acarrean ninguna de las
    cargas del ciclo de vida: nada de `@GeneratedValue`, nada de tipos de fetch, nada
    de cascadas. Si tu IDE autoimporta aquí `jakarta.persistence.Id`, el mapeo no
    funcionará — el runtime reactivo nunca lo ve. Firefly no añade nada a esto; es
    Spring Data de serie, mantenido no bloqueante por debajo.

## Paso 2 — Modela el ciclo de vida como un enum

El ciclo de vida de una solicitud es un conjunto pequeño y cerrado de estados, así que
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
constantes del enum más adelante nunca reetiqueta en silencio las filas existentes. La
columna es lo bastante ancha (`VARCHAR(32)`) para el nombre más largo con margen para
crecer. El ayudante `isTerminal()` es pequeño pero se gana el sustento — el propio
método `cancel(...)` de la entidad lo llama para rechazar una transición fuera de
`APPROVED`, `REJECTED` o `CANCELLED`, manteniendo las reglas de estados legales junto a
los estados que gobiernan en lugar de dispersas por los servicios.

## Paso 3 — Resuelve el problema de insertar-frente-a-actualizar con `Persistable`

Aquí está la parte sutil. El `save` de Spring Data tiene que decidir, para cada
entidad, si emite un `INSERT` o un `UPDATE`. Con un id generado por la base de datos,
la regla es fácil: un id `null` significa "nunca se ha guardado, así que INSERT", y un
id no nulo significa "ya tiene clave, así que UPDATE". Esa heurística es la que usa
Spring Data por defecto.

Pero Lumen asigna el UUID *él mismo*, antes del primer save. Para cuando la entidad
llega al repositorio su id ya no es nulo — así que la heurística por defecto concluye
"esto debe de ser una actualización", emite un `UPDATE ... WHERE id = ?`, no encuentra
ninguna fila, y el insert que pretendías nunca ocurre, en silencio. Este es un
clásico tropiezo de R2DBC con claves asignadas por el cliente, y falla sin hacer
ruido: ninguna excepción, solo una fila que nunca se escribió.

El arreglo es dejar de permitir que R2DBC adivine. Implementa `Persistable<UUID>` y
responde la pregunta explícitamente con un método `isNew()`, respaldado por una bandera
`@Transient` que nunca se escribe en la base de datos.

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/domain/LoanApplication.java | Listado 8.3 — decirle a R2DBC "esto es nuevo" explícitamente
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

La anotación `@Transient` mantiene `newEntity` fuera del mapeo, así que no hay columna
`new_entity` y la bandera nunca llega al cable. `getId()` devuelve la clave subrogada;
`isNew()` devuelve la bandera. Cuando el servicio está a punto de guardar una solicitud
recién creada llama a `markNew()`, R2DBC ve `isNew() == true` y se emite un `INSERT`
independientemente del id no nulo. En cualquier `save` posterior la entidad cargada
tiene `newEntity == false`, así que se emite un `UPDATE` — que es exactamente lo
correcto.

Fíjate en que `@Transient` aquí es `org.springframework.data.annotation.Transient`, el
marcador de Spring Data — no la palabra clave `transient` de Java ni el `@Transient`
de JPA. Le dice al *mapeo* que omita el campo; el valor sigue viviendo en el objeto y
se serializa con normalidad si lo pones en un DTO (cosa que no harías). El
emparejamiento con `@Builder.Default` también importa: el builder de Lombok ignora los
inicializadores de campo a menos que le digas lo contrario, así que sin
`@Builder.Default` una entidad construida con el builder obtendría `newEntity == false`
solo por la suerte del valor por defecto — esto hace explícito el `false` por defecto
y honesto al builder.

!!! warning "El valor por defecto de la bandera es false, no true"
    La bandera toma por defecto `false` para que una solicitud *cargada* desde la base
    de datos — que nunca llama a `markNew()` — se trate correctamente como una
    actualización. Solo el camino de creación se apunta al INSERT. Tomar `true` por
    defecto haría que cada save tras una lectura intentara reinsertar una fila que ya
    existe, y obtendrías errores de clave duplicada en lugar de actualizaciones.

!!! spring "Equivalente en Spring"
    Esto es Spring Data puro, no un añadido de Firefly. En Spring Data JPA rara vez te
    encuentras con este problema porque Hibernate rastrea el estado de la entidad en su
    contexto de persistencia. R2DBC no tiene sesión ni detección de cambios, así que
    cuando eres *tú* el dueño de la clave también debes ser dueño de la decisión de
    insertar/actualizar — y `Persistable<T>` es el gancho estándar y documentado de
    Spring Data para hacer exactamente eso. Firefly no cambia nada de esto; solo
    mantiene no bloqueante la pila reactiva que hay debajo.

## Paso 4 — Añade el repositorio reactivo

Con la entidad en su sitio, el repositorio es una sola línea más dos consultas
derivadas. Extiende `ReactiveCrudRepository<LoanApplication, UUID>` y Spring Data
genera una implementación reactiva en tiempo de ejecución: `save` devuelve
`Mono<LoanApplication>`, `findById` devuelve `Mono<LoanApplication>`, `findAll`
devuelve `Flux<LoanApplication>`. Añades métodos buscadores *nombrándolos*, y Spring
Data analiza el nombre para convertirlo en una consulta.

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
única. No escribes ni SQL ni clase de implementación — los nombres de los métodos *son*
la consulta, y Spring Data construye `WHERE status = ?` y `WHERE application_number = ?`
por ti.

El *tipo* de retorno es parte del contrato, no decoración. Spring Data lo inspecciona:
un retorno `Mono` le dice al runtime que espere como mucho una fila (y que dé error si
un buscador "único" de algún modo encuentra varias), mientras que un `Flux` dice
"transmite tantas como coincidan". Elegir la cardinalidad equivocada aquí es el tipo de
error que el compilador no atrapará — elige `Mono` para claves que indexas como únicas,
`Flux` para todo lo demás.

!!! note "Término clave — consulta derivada"
    Una **consulta derivada** es un método de repositorio cuyo nombre Spring Data
    analiza para convertirlo en una consulta: `findBy` + nombres de propiedades +
    palabras clave opcionales (`And`, `OrderBy`, `Between`). Es la forma más rápida de
    añadir un buscador, sin SQL que mantener — a costa de nombres de método largos una
    vez que crecen los criterios. Para algo más rico, anotas el método con `@Query`, o
    — para filtrado de listas abierto — recurres al motor de filtros descrito al final
    de este capítulo.

## Paso 5 — Crea el esquema con una migración de Flyway

R2DBC mapea a una tabla; algo tiene que *crear* esa tabla. Lumen usa **Flyway**, que
aplica scripts de migración SQL versionados en orden y registra cuáles se han
ejecutado, de modo que el esquema es reproducible desde una base de datos vacía y
evoluciona en pasos rastreados. Un archivo de migración se nombra
`V<version>__<description>.sql`; Flyway ejecuta `V1` antes de `V2`, una vez cada uno, y
se niega a cambiar en silencio uno que ya se haya aplicado.

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

Léelo contra el Listado 8.1 columna por columna: cada `@Column("...")` mapea a una
columna de aquí. El `loan_application_id` es la `PRIMARY KEY` (la subrogada);
`application_number` lleva un índice `UNIQUE` porque la clave de negocio no debe
repetirse; `status` obtiene un índice corriente porque `findByStatus` filtra por él. El
enum aterriza en `VARCHAR(32)`, el importe en `NUMERIC(19, 2)` para guardar dinero sin
error de coma flotante, y las dos marcas de tiempo de auditoría son `NOT NULL`.

El mismo archivo continúa (justo después del fragmento de arriba) creando una tabla
`proposed_offer` que la saga de la capa de dominio usa para la oferta que propone — una
migración, dos tablas relacionadas, aplicadas juntas. El fragmento se detiene en el
esquema de loan-application porque esa es la tabla a la que mapea la entidad de este
capítulo; la tabla de ofertas es la misma disciplina DDL aplicada al siguiente
agregado.

Este mismo script se ejecuta sin cambios contra H2 en memoria en los tests *y* contra
el servicio en ejecución: la capa core arranca sobre H2 con `r2dbc:h2:mem:///lumen`
para las lecturas en tiempo de ejecución y `jdbc:h2:mem:lumen` para la migración de
Flyway, ambas apuntando a la misma base de datos en memoria para que la tabla que crea
Flyway sea la que lee R2DBC. Está escrito deliberadamente en DDL portable y compatible
con H2 para que el test que ejecutas en un momento ejercite la migración *real*, no un
mock — y el `POST` en vivo que sigues aterrice en la tabla *real* migrada.

!!! spring "Equivalente en Spring"
    Esto es Spring Boot de serie. La autoconfiguración de Flyway ve `flyway-core` en el
    classpath y ejecuta cualquier cosa bajo `src/main/resources/db/migration` al
    arrancar. En la pila reactiva Flyway sigue usando una conexión JDBC bloqueante de
    vida corta *solo al arrancar* para aplicar las migraciones — eso está bien, porque
    ocurre una vez antes de que el bucle de eventos empiece a servir tráfico; el camino
    de la petición se mantiene R2DBC y no bloqueante. Firefly deja este cableado
    exactamente como lo entrega Spring Boot.

!!! note "Término clave — H2 en memoria con R2DBC y JDBC"
    El servicio core usa *dos* drivers contra una base de datos en memoria. **R2DBC** es
    el driver reactivo que usa el camino de la petición; **JDBC** es el driver
    bloqueante que Flyway necesita para migrar. Ambas URLs nombran la misma base de
    datos H2 (`lumen`) y establecen `DB_CLOSE_DELAY=-1` para que la base de datos
    sobreviva durante toda la vida de la JVM en lugar de desvanecerse cuando se cierra
    la conexión de la migración. Por eso el core arranca y sirve CRUD real sin Docker y
    sin base de datos externa — todo el sistema de registro vive en proceso.

## Paso 6 — Mapea entidad a DTO con MapStruct

El repositorio trata con entidades; la capa web trata con DTOs. **MapStruct** genera
el código de conversión en tiempo de compilación a partir de una interfaz que declaras,
así que obtienes un mapeo rápido y de baja asignación de memoria, sin reflexión y sin
bucles de copia escritos a mano. Declarar `componentModel = SPRING` convierte la
implementación generada en un bean de Spring que puedes inyectar como cualquier otro.

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
con los campos del DTO por nombre y escribe la implementación por ti, de modo que la
proyección mecánica de entidad a respuesta te cuesta una sola firma de método. Como
`LoanApplicationResponse` es un record cuyos componentes — `loanApplicationId`,
`applicationNumber`, `applicantId`, `requestedAmount`, `currency`, `termMonths`,
`purpose`, `status`, `decisionReason`, `createdAt`, `updatedAt` — se alinean
nombre-a-nombre con la entidad, MapStruct rellena cada uno. `toNewEntity` es un método
`default` que escribes a mano, porque construir una nueva solicitud no es una copia
campo a campo — normaliza la moneda a mayúsculas y deja deliberadamente sin asignar el
id, el estado y las marcas de tiempo, porque es el *servicio* quien es dueño de esos
valores por defecto. Mezclar métodos generados y escritos a mano en un mismo mapeador
es MapStruct idiomático: deja que genere las copias aburridas, toma el control donde
hay lógica de verdad.

`unmappedTargetPolicy = ReportingPolicy.IGNORE` le dice a MapStruct que no falle la
compilación cuando un campo destino no tiene fuente que coincida — relevante en
`toNewEntity`, donde el id/estado/marcas de tiempo se dejan intencionadamente para el
servicio. Sin ella, la compilación daría error en esos huecos. La política `ERROR` por
defecto es más estricta y a veces preferible, pero aquí los huecos son por diseño.

!!! spring "Equivalente en Spring"
    MapStruct es un procesador de anotaciones en tiempo de compilación, no una
    característica de Spring — pero `componentModel = SPRING` convierte el
    `LoanApplicationMapperImpl` generado en un `@Component`, así que el escaneo de
    componentes de Spring lo encuentra y lo inyectas por constructor como cualquier
    bean. No hay dependencia de MapStruct en tiempo de ejecución en el camino de la
    petición ni reflexión: el mapeo es código de getter/setter corriente generado en
    `target/generated-sources`. Este es el mismo patrón que usan las interfaces
    `*Mapper` reales de firefly-oss.

## Paso 7 — Únelo todo en un servicio reactivo

El servicio de aplicación es donde se componen las piezas. Recibe por constructor el
repositorio y el mapeador (mediante el `@RequiredArgsConstructor` de Lombok), está
marcado como `@Transactional` y devuelve tipos reactivos de principio a fin — así que
nada bloquea. Aquí también es donde el truco de `Persistable` rinde sus frutos: el
camino de creación acuña los ids, fija las marcas de tiempo, envía la solicitud a
través de su método de dominio, llama a `markNew()` y solo entonces guarda.

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/service/LoanApplicationService.java | Listado 8.7 — los caminos de creación y lectura, completamente reactivos
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

Sigue el camino de creación: construye una entidad transitoria a partir de la petición,
asigna ambos UUIDs, estampa el estado y las marcas de tiempo, ejecuta `submit()` (el
método de dominio que mueve la solicitud de `DRAFT` a `SUBMITTED`), luego `markNew()`
para que R2DBC emita un `INSERT`. El save devuelve `Mono<LoanApplication>`, y
`.map(mapper::toResponse)` lo convierte en el `Mono<LoanApplicationResponse>` que
devuelve el controlador. Sin `.block()`, sin `subscribe()` — el framework se suscribe
en el borde.

El orden importa en ese método, y merece la pena decir por qué `submit()` se ejecuta
*antes* de `markNew()` y del save. `submit()` es la propia transición de estado del
agregado: comprueba que la solicitud está en `DRAFT`, la cambia a `SUBMITTED` y
reestampa `updatedAt`. Hacerlo en memoria antes del único `save` significa que la fila
se escribe ya en `SUBMITTED` en un solo INSERT — que es exactamente por qué la
respuesta de abajo vuelve como `"status":"SUBMITTED"`, no `"DRAFT"`, aunque la entidad
se *creó* en `DRAFT`. El estado que pidió el llamante nunca se persiste; el estado
enviado, sí.

El camino de lectura muestra el modelo de errores del framework. `findById` devuelve un
`Mono` vacío cuando no hay fila; `switchIfEmpty` convierte ese vacío en una
`ResourceNotFoundException`, y la capa web la renderiza automáticamente como un problem
detail RFC 7807. No hay ningún 404 escrito a mano en ningún sitio — exactamente la
promesa de "un modelo de errores, en todas partes" del Capítulo 1.

El método `list` lo redondea, alternando entre `findAll` y la consulta derivada
`findByStatus` según si se suministró o no un filtro de estado:

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/service/LoanApplicationService.java | Listado 8.8 — listado, con y sin un filtro de estado
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
    transacción *reactiva* ligada al contexto de Reactor en lugar de a un thread-local
    — que es por lo que nunca debe envolver una llamada bloqueante.
    `@Transactional(readOnly = true)` en los caminos de lectura es la misma pista que
    darías en MVC: permite a la capa de datos saltarse el trabajo de detección de
    cambios para las consultas. Mismas anotaciones, semántica reactiva por debajo.

## Paso 8 — Arranca el core y observa cómo aterriza una fila

Esta es la recompensa: la capa de persistencia no solo está probada en unit tests,
sino que se ejecuta. Arranca la capa core por su cuenta — arranca sobre H2 en memoria,
aplica la migración de Flyway y sirve CRUD en el puerto `8081` sin Docker y sin base de
datos externa. Desde `samples/lumen-lending`:

```text
$ ( cd core-lending-loan-origination && mvn spring-boot:run )
```

El starter imprime primero el banner del framework — la misma forma que conociste en el
quickstart, con la línea de capa `:: firefly-core ::` anunciando qué baseline arrancó:

```text

  _____.__                _____.__
_/ ____\__|______   _____/ ____\  | ___.__.
\   __\|  \_  __ \_/ __ \   __\|  |<   |  |
 |  |  |  ||  | \/\  ___/|  |  |  |_\___  |
 |__|  |__||__|    \___  >__|  |____/ ____|
                       \/           \/
:: firefly-core ::               (v0.1.0-SNAPSHOT)

(c)2025 Firefly Software Foundation
Licensed under Apache 2.0

Spring Boot Version: 3.5.10
Application: core-lending-loan-origination
Application Description: Unknown
Application SwaggerUI: http://localhost:8081/swagger-ui.html
⇩⇩⇩ Logs start below ⇩⇩⇩
```

Bajo el divisor, el log de arranque en JSON muestra a Flyway migrando y a R2DBC
levantándose sobre H2 — la capa de persistencia cableándose a sí misma antes de que
Netty empiece a escuchar:

```text
{"timestamp":"2026-06-17T11:46:18.204+0000","message":"Successfully applied 1 migration to schema \"PUBLIC\", now at version v1","logger":"o.f.core.FlywayExecutor","level":"INFO"}
{"timestamp":"2026-06-17T11:46:18.611+0000","message":"Netty started on port 8081 (http)","logger":"o.s.b.w.e.netty.NettyWebServer","level":"INFO"}
{"timestamp":"2026-06-17T11:46:18.640+0000","message":"Started CoreLendingApplication in 2.481 seconds","logger":"c.f.l.core.CoreLendingApplication","level":"INFO"}
```

Una rápida comprobación de salud confirma que el subsistema R2DBC está levantado contra
H2 (recortado):

```text
$ curl -s http://localhost:8081/actuator/health
{"status":"UP","groups":["liveness","readiness"],"components":{
  "r2dbc":{"status":"UP","details":{"database":"H2"}},
  "eda":{"status":"UP","details":{"enabled":true,"message":"All EDA components are healthy"}},
  "ping":{"status":"UP"}}}
```

Ahora envía con un POST una solicitud de préstamo directamente al core. El cuerpo de la
petición lleva el solicitante, el importe, la moneda, el plazo en meses y el propósito:

```text
$ curl -s -X POST http://localhost:8081/api/v1/loan-applications \
    -H 'Content-Type: application/json' \
    -d '{
          "applicantId": "11111111-1111-1111-1111-111111111111",
          "requestedAmount": 250000.00,
          "currency": "EUR",
          "termMonths": 60,
          "purpose": "Home improvement"
        }'
```

El servicio ejecuta `create` del Listado 8.7 — construye la entidad, asigna ambos
UUIDs, estampa las marcas de tiempo, llama a `submit()`, llama a `markNew()`, y `save`
emite un `INSERT` real en la tabla migrada `loan_application`. Responde con
`201 Created` y la fila persistida. Fíjate en el `loanApplicationId` y el
`applicationNumber` asignados por el framework, las marcas de tiempo de auditoría y el
`status` principal de `SUBMITTED` — la fila se escribió ya enviada, exactamente como
explicó el Paso 7:

```json
{
  "loanApplicationId": "16d94afc-3c7f-4f1e-9b2a-2f5c4e7d8a90",
  "applicationNumber": "fb049375-6e21-4d3a-bd0c-9a1b2c3d4e5f",
  "applicantId": "11111111-1111-1111-1111-111111111111",
  "requestedAmount": 250000.00,
  "currency": "EUR",
  "termMonths": 60,
  "purpose": "Home improvement",
  "status": "SUBMITTED",
  "decisionReason": null,
  "createdAt": "2026-06-17T11:46:21.145765",
  "updatedAt": "2026-06-17T11:46:21.145781"
}
```

Vuelve a leerla directamente por el `loanApplicationId` que devolvió la llamada de
creación. Esto es `getById` — `findById` encuentra la fila que R2DBC acaba de insertar
y el mapeador la proyecta al mismo DTO:

```text
$ curl -s http://localhost:8081/api/v1/loan-applications/16d94afc-3c7f-4f1e-9b2a-2f5c4e7d8a90
```

```json
{
  "loanApplicationId": "16d94afc-3c7f-4f1e-9b2a-2f5c4e7d8a90",
  "applicationNumber": "fb049375-6e21-4d3a-bd0c-9a1b2c3d4e5f",
  "applicantId": "11111111-1111-1111-1111-111111111111",
  "requestedAmount": 250000.00,
  "currency": "EUR",
  "termMonths": 60,
  "purpose": "Home improvement",
  "status": "SUBMITTED",
  "decisionReason": null,
  "createdAt": "2026-06-17T11:46:21.145765",
  "updatedAt": "2026-06-17T11:46:21.145781"
}
```

Ese viaje de ida y vuelta — el POST inserta y envía, el GET vuelve a leer la fila
persistida de forma idéntica — es el truco de `Persistable` demostrado en vivo. El id
no era nulo antes del save, y sin embargo la fila existe y se vuelve a leer: eso solo
ocurre porque `isNew()` devolvió `true` y R2DBC emitió un INSERT en lugar de un UPDATE
de cero filas.

Ahora el camino infeliz. Pide un id que nunca se insertó, y `switchIfEmpty` convierte
el `Mono` vacío en una `ResourceNotFoundException` que el framework renderiza como un
problem detail RFC 7807 — sin 404 escrito a mano, `application/problem+json`, con el
contexto de traza metido en `extensions`:

```text
$ curl -s http://localhost:8081/api/v1/loan-applications/00000000-0000-0000-0000-000000000000
```

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "Loan application not found: 00000000-0000-0000-0000-000000000000",
  "instance": "/api/v1/loan-applications/00000000-0000-0000-0000-000000000000?traceId=1efc25dec634992124f6a1520970dfef",
  "extensions": {
    "traceId": "1efc25dec634992124f6a1520970dfef",
    "spanId": "6709a187f331beb0",
    "severity": "LOW",
    "retryable": false,
    "path": "/api/v1/loan-applications/00000000-0000-0000-0000-000000000000",
    "suggestion": "Verify the resource identifier and ensure it exists.",
    "category": "RESOURCE"
  }
}
```

!!! note "Término clave — el sistema de registro"
    Este core en ejecución es el **sistema de registro** de la originación de
    préstamos: el único almacén autoritativo de los datos. Cuando la capa de
    experiencia (puerto `8080`) acepta una petición de canal y la capa de dominio
    (puerto `8082`) ejecuta su `RegisterApplicationSaga`, el paso raíz de la saga
    escribe *aquí*, sobre HTTP, en esta misma tabla — y el `applicationId` que devuelve
    al llamante es el `loanApplicationId` que acuñó este core. La capa de persistencia
    que acabas de ejercitar a mano es la base de todo ese flujo. (La costura de
    escritura dominio→core es intencionadamente mínima en este sample: lleva el
    solicitante y el importe, así que algunos campos del core — `currency`,
    `termMonths`, `purpose` — aterrizan como valores por defecto. El mapeo más rico es
    trabajo del SDK generado, cubierto en el Capítulo 7.)

## Paso 9 — Demuéstralo sin servidor

No necesitas el servidor en ejecución para confiar en nada de lo anterior. El reactor
incluye un test de slice de controlador que arranca el contexto reactivo completo
contra H2 en memoria (runtime R2DBC más la migración de Flyway), maneja la API real con
`WebTestClient` y verifica el viaje de ida y vuelta crear-luego-leer, el 404 RFC 7807 y
el rechazo de validación de un payload incorrecto — todo contra el esquema real
migrado, los *mismos* caminos de código que acabas de ejercitar a mano, ejecutados sin
interfaz gráfica. Desde `samples/lumen-lending`:

```text
$ mvn -q -pl core-lending-loan-origination test
```

Los tres casos del test de slice pasan:

```text
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in com.firefly.lumen.core.web.LoanApplicationControllerTest
```

y todo el módulo core está en verde — dieciocho tests a través del modelo de dominio,
los ayudantes reactivos y la capa web:

```text
[INFO] Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

A lo largo de todo el reactor, un `mvn clean verify` ejecuta **33** tests — core 18,
dominio 6, experiencia 9 — todos en verde, sin Docker y sin servicios externos.

!!! tip "Punto de control"
    Ejecuta `mvn -q -pl core-lending-loan-origination test` desde
    `samples/lumen-lending`. Un `Tests run: 3, Failures: 0` en verde en
    `LoanApplicationControllerTest`, y `Tests run: 18` para el módulo, significa que
    Flyway aplicó `V1__loan_application.sql` a H2, la bandera `Persistable` impulsó un
    `INSERT` real, `findById` volvió a leer la fila, y un id ausente produjo un 404 RFC
    7807 — el viaje de ida y vuelta entero, verificado. Si el test de creación falla
    con un síntoma de clave duplicada o "0 rows updated", lo primero que hay que
    comprobar es la llamada a `markNew()` y el valor por defecto de la bandera
    `isNew()`.

## El camino de producción para los endpoints de listado

`list(status)` es honesto pero tosco: un filtro opcional, sin paginación, sin
ordenación. Un servicio core de verdad expone endpoints de listado con filtrado
arbitrario, paginación estable y un envoltorio de respuesta consistente — y el slice de
originación de préstamos de Lumen mantiene esa superficie pequeña a propósito. La
respuesta del framework, que usan los servicios Firefly de producción, es un **motor de
filtros reflexivo y genérico** emparejado con un par `PaginationRequest`/
`PaginationResponse`. Describes un DTO de filtro, anotas los campos por los que se puede
filtrar, y el motor construye la consulta y la respuesta paginada por ti — sin
explosión de métodos derivados, sin código repetitivo de paginación por endpoint.

Conceptualmente, una petición filtrable y una llamada paginada tienen este aspecto.
(Esto es un boceto ilustrativo de la forma del framework — el slice de originación de
préstamos construido no cablea el motor de filtros; usa las consultas derivadas de
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

Lo importante es el *camino*, no la sintaxis: cuando un endpoint de listado supera a una
consulta derivada, no te fabricas a mano DTOs de page/size/sort y un constructor de
consultas a medida en cada servicio — el impuesto empresarial que nombró el Capítulo 1.
Declaras el filtro, anotas los campos filtrables y heredas un endpoint de listado
uniforme, reactivo y paginado. El slice de Lumen se queda con consultas derivadas
porque dos buscadores es todo lo que necesita; recurre al motor de filtros en el momento
en que aparezca una superficie de filtrado de verdad.

!!! note "Término clave — `@FilterableId`"
    `@FilterableId` marca un campo de tipo id en un DTO de filtro como algo por lo que
    el motor puede filtrar, con el manejo de tipos correcto para claves UUID. Es la
    forma del motor de filtros de decir "este campo es un objetivo de filtro seguro e
    indexado" — la suscripción explícita que impide que el filtrado reflexivo se
    convierta en una superficie de consulta abierta sobre cada campo.

## Lo que has construido {.recap}

- Una **entidad `@Table` de Spring Data R2DBC**, `LoanApplication`, con un `@Id` UUID,
  mapeos `@Column` en snake_case, y un enum `ApplicationStatus` almacenado como cadena.
- El **truco de `Persistable<UUID>`**: una bandera `@Transient` `newEntity` más
  `isNew()`, para que R2DBC emita un `INSERT` para claves asignadas por el cliente en
  lugar de un `UPDATE` silencioso de cero filas — y un `UPDATE` para todo lo cargado
  desde la base de datos.
- Un **repositorio reactivo** que extiende `ReactiveCrudRepository` con dos consultas
  derivadas (`findByStatus`, `findByApplicationNumber`) y sin SQL escrito a mano.
- Una **migración de Flyway**, `V1__loan_application.sql`, en DDL portable que se
  ejecuta sobre H2 en los tests y en el servicio en vivo desde el mismo archivo —
  migrando sobre JDBC mientras el camino de la petición se mantiene R2DBC.
- Un **mapeador MapStruct** que genera `toResponse` y escribe a mano `toNewEntity`, y un
  **servicio** `@Transactional` que los compone reactivamente — con una
  `ResourceNotFoundException` que el framework renderiza como un 404 RFC 7807.
- Un **viaje de ida y vuelta en vivo**: el core arrancó sobre H2 en el puerto `8081`, un
  `POST` insertó y envió una fila real (`status: SUBMITTED`), y un `GET` la volvió a
  leer byte por byte — la misma fila a la que escribe la saga de dominio como sistema de
  registro.
- Un `Tests run: 3, Failures: 0` en verde para el slice de controlador y `Tests run: 18`
  para el módulo (33 a lo largo de todo el reactor), demostrando el viaje de ida y vuelta
  contra el esquema real migrado.

## Pruébalo tú mismo {.exercises}

1. **Observa cómo falla el truco.** En `LoanApplicationService.create`, comenta la línea
   `application.markNew()` y vuelve a ejecutar `mvn -q -pl
   core-lending-loan-origination test`. Observa cómo se rompe el test de creación, luego
   restaura la línea y explica en una frase por qué el id UUID no nulo hizo que R2DBC
   eligiera `UPDATE`.
2. **Demuéstralo en vivo.** Arranca el core con `( cd core-lending-loan-origination &&
   mvn spring-boot:run )`, envía con un POST una solicitud de préstamo a
   `http://localhost:8081/api/v1/loan-applications`, luego vuelve a hacerle un `GET` por
   el `loanApplicationId` devuelto. Confirma que el `status` es `SUBMITTED`, luego pide
   un UUID aleatorio y confirma que obtienes el 404 RFC 7807.
3. **Añade un buscador derivado.** Añade `Flux<LoanApplication> findByApplicantId(UUID
   applicantId)` a `LoanApplicationRepository` y un método de servicio fino estilo
   `list` que lo use. Sin SQL — deja que el nombre del método lleve la consulta.
4. **Extiende el esquema.** Añade una columna `risk_band VARCHAR(16)` a
   `V1__loan_application.sql` y un campo `@Column("risk_band")` que coincida en
   `LoanApplication`, luego confirma que el test de slice sigue en verde contra el
   esquema H2 migrado.
5. **Mapea un campo nuevo.** Expón tu campo `risk_band` en `LoanApplicationResponse` y
   confirma que el `toResponse` generado por MapStruct lo recoge automáticamente por
   nombre — sin que haga falta ningún cambio en la interfaz del mapeador.
6. **Esboza el camino de producción.** Sin cablearlo, escribe un record
   `LoanApplicationFilter` con un id de solicitante `@FilterableId` y un estado, y anota
   qué método de servicio existente reemplazaría una vez que el endpoint de listado
   necesite paginación.

## Adónde ir ahora

El core ahora es dueño de sus datos, arranca sobre H2 y sirve CRUD real en el puerto
`8081`. Las siguientes capas hacia arriba de la pila ponen a trabajar este servicio: la
capa de dominio orquesta estas operaciones CRUD en flujos de negocio sobre SDKs
generados, y la `RegisterApplicationSaga` coordina el camino de decisión multipaso con
compensación cuando un paso falla — escribiendo, en última instancia, en la mismísima
tabla que construiste aquí. La capa de persistencia es el sistema de registro al que
todo lo que está por encima escribe, y el flujo de envío en vivo exp → dominio → core
que seguirás más adelante toca fondo justo aquí.
