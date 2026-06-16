Los términos se definen tal y como los usa este libro: en el contexto del Java Firefly
Framework y de Spring Boot reactivo. Cuando un término tiene un significado más amplio en
otros ámbitos, la definición que aquí se ofrece es la práctica que necesitas para Lumen Lending.

**Agregado (Aggregate).** En el diseño guiado por el dominio, un conjunto de objetos tratados como una
única unidad para los cambios de datos, con una *raíz del agregado* (aquí, `LoanApplication`) que
hace cumplir las invariantes del conjunto. El código externo solo toca la raíz.

**Autoconfiguración (Auto-configuration).** Un mecanismo de Spring Boot: clases de configuración que
se activan en función de lo que hay en el classpath y de qué beans ya existen, aplicando
valores por defecto sensatos y cediendo el paso cuando defines los tuyos. Cada capacidad de Firefly
se entrega como autoconfiguración controlada por `@ConditionalOnProperty` y
`@ConditionalOnMissingBean`.

**Contrapresión (Backpressure).** La capacidad de un flujo reactivo de señalar que no puede seguir el ritmo,
de modo que un productor rápido no abrume a un consumidor lento. Está integrada en Project Reactor;
rara vez la gestionas a mano, pero es la razón por la que `Flux` es seguro a través de una red.

**Bean.** Un objeto creado y gestionado por el contexto de aplicación de Spring. Declaras
los beans con estereotipos (`@Component`, `@Service`, …) y los recibes por
inyección de constructor.

**BFF (Backend-for-Frontend).** Un servicio que existe para atender a una clase de cliente
(una app móvil, una app web), dando forma y agregando los datos de aguas abajo para ese cliente. En
Firefly esta es la capa de *experiencia*, construida sobre `starter-application`.

**BOM (Bill of Materials).** Un POM que contiene únicamente la gestión de *versiones* de
dependencias. Importar el `fireflyframework-bom` permite que tus servicios declaren
dependencias del framework sin versiones, con la garantía de que todas concuerdan.

**Comando (CQRS).** Una instrucción para cambiar el estado (p. ej. `RegisterLoanApplication`).
Se despacha en el `CommandBus` hacia exactamente un manejador. Contrasta con una *consulta*.

**Bus de comandos/consultas (Command/Query bus).** El componente de Firefly que enruta un comando o una consulta hacia su
único manejador registrado, aplicando a su alrededor validación, autorización, métricas y
trazabilidad de forma automática.

**Compensación (Compensation).** En una saga, la acción que deshace un paso previamente completado
cuando un paso posterior falla: el sustituto, en sistemas distribuidos, de un rollback
transaccional (p. ej. `removeLoanApplication` compensando a `registerLoanApplication`).

**CQRS (Command Query Responsibility Segregation).** Separar la ruta de escritura
(comandos) de la ruta de lectura (consultas) para que cada una pueda modelarse, escalarse y cachearse
de forma independiente.

**Capa core (Core tier).** Los servicios sistema-de-registro que poseen la base de datos y exponen APIs
reactivas sencillas de CRUD y de negocio. Construida sobre `starter-core` + `fireflyframework-r2dbc`.

**Identificador de correlación (Correlation ID).** Un identificador adjuntado a una petición y propagado a través de cada
salto (logs, llamadas a aguas abajo, eventos) para que una operación lógica pueda trazarse
de extremo a extremo. Firefly lo transporta por ti a través de las fronteras reactivas.

**Capa de datos (Data tier).** Servicios dedicados al enriquecimiento, la calidad de los datos y el linaje (por
ejemplo, datos de la central de riesgos crediticios). Construida sobre `starter-data`.

**Evento de dominio (Domain event).** Un registro de que ha ocurrido algo significativo en el dominio
(`LoanApplicationRegistered`). Se publica a través de la capa de EDA; lo consumen
listeners y otros servicios.

**Capa de dominio (Domain tier).** Los servicios de orquestación que convierten operaciones de negocio gruesas
en comandos, consultas y sagas, que no poseen ninguna base de datos y llaman a los servicios core
mediante SDKs. Construida sobre `starter-domain`.

**EDA (Event-Driven Architecture).** Un estilo en el que los servicios se comunican
publicando y consumiendo eventos en lugar de llamarse unos a otros directamente. La capa de
EDA de Firefly es agnóstica al transporte (en la JVM, Kafka, RabbitMQ, Postgres).

**ExecutionContext.** El objeto de Firefly que transporta la identidad por petición y
los datos multi-tenant (usuario, tenant, organización, sesión, IDs de petición) a través de los manejadores
de CQRS, de modo que el comportamiento y el aislamiento sean conscientes del tenant.

**Event sourcing.** Persistir un agregado como la secuencia ordenada de eventos que
le han ocurrido, y reconstruir su estado reproduciéndolos, en lugar de almacenar
solo la última instantánea.

**Capa de experiencia (Experience tier).** Véase *BFF*.

**Flux.** Un publicador de Project Reactor de **cero a muchos** elementos, de forma asíncrona y
con contrapresión. El análogo reactivo de un flujo de valores.

**Flyway.** Una herramienta de migración de bases de datos. Los scripts SQL versionados (`V1__…sql`) llevan cualquier
base de datos a un esquema conocido; la capa R2DBC de Firefly la incorpora.

**Framework / metaframework.** Un *framework* te da bloques de construcción y un lugar
para tu código (Spring Boot). Un *metaframework* se construye sobre uno de ellos, añadiendo
opiniones y comportamiento transversal precableado para que una flota sea consistente por defecto
(Firefly).

**Arquitectura hexagonal (puertos y adaptadores).** Diseñar un componente en torno a una
interfaz (el *puerto*) con implementaciones intercambiables (los *adaptadores*).
Los cores de integración de Firefly (IDP, ECM, notificaciones) funcionan así: dependes del
puerto y eliges un adaptador mediante una propiedad.

**Idempotencia (Idempotency).** La propiedad por la que realizar una operación más de una vez tiene el
mismo efecto que hacerlo una sola vez. La capa web de Firefly deduplica las escrituras reintentadas mediante una
`X-Idempotency-Key`.

**Mono.** Un publicador de Project Reactor de **a lo sumo un** elemento (o un error, o
nada), de forma asíncrona. El análogo reactivo de un único valor futuro.

**Enmascaramiento de PII (PII masking).** Redactar automáticamente la información de identificación personal
(documentos de identidad nacionales, números de tarjeta, tokens) de los logs. Firefly lo aplica en toda la flota.

**Puerto (Port).** Véase *arquitectura hexagonal*.

**Proyección (Projection).** Un modelo de lectura construido consumiendo eventos: una vista del estado
optimizada para consulta, que se mantiene al día a medida que llegan los eventos.

**Consulta (CQRS).** Una petición para leer el estado sin cambiarlo. Se despacha en el
`QueryBus`; los resultados pueden cachearse.

**R2DBC (Reactive Relational Database Connectivity).** La alternativa no bloqueante y reactiva
a JDBC. Los repositorios devuelven `Mono`/`Flux`; es necesaria para mantener la capa de datos
fuera de la ruta crítica del event loop.

**Reactivo (Reactive).** Un modelo de programación construido sobre flujos asíncronos (`Mono`/`Flux`) con
contrapresión, en el que describes *qué hacer cuando lleguen los valores* en lugar de bloquearte
esperándolos.

**RFC 7807 (Problem Details).** Una forma JSON estándar para las respuestas de error HTTP
(`type`, `title`, `status`, `detail`, …). Firefly la emite de forma automática e
idéntica para cada servicio.

**Saga.** Una secuencia de pasos locales a través de servicios que juntos logran un
resultado de negocio, con *compensación* para deshacer los pasos completados si uno posterior falla.
El motor de orquestación de Firefly también ofrece *workflows* y *TCC*.

**SDK (generado).** Un cliente tipado y reactivo generado a partir de la especificación OpenAPI de un servicio,
usado por la capa superior para llamarlo por HTTP, nunca una base de datos compartida.

**StepVerifier.** Una utilidad de `reactor-test` que se suscribe a un `Mono`/`Flux` en un
test y verifica la secuencia exacta de elementos, errores y señales de finalización.

**Starter.** Una dependencia curada que aporta todo lo necesario para una capacidad o una capa.
Los starters de capa de Firefly son `starter-core`, `starter-domain`, `starter-data` y
`starter-application`.

**TCC (Try-Confirm-Cancel).** Un patrón de transacción distribuida con una consistencia más fuerte
que una saga: cada participante primero *intenta* (reserva), y luego todos
*confirman* o todos *cancelan*.

**Capa (Tier).** Una de las cuatro capas arquitectónicas (experiencia, dominio, core, datos),
cada una respaldada por su propio starter de Firefly, que se integran mediante contratos en lugar de un
esquema compartido.

**WebFlux.** La pila web reactiva de Spring, la contraparte no bloqueante de Spring
MVC. Los controladores devuelven `Mono`/`Flux` y se ejecutan en un event loop.

**Workflow (orquestación).** Una secuencia de pasos hacia delante sin rollback automático:
el más sencillo de los tres patrones de orquestación de Firefly (workflow, saga,
TCC).

**X-Transaction-Id.** Una cabecera que Firefly propaga a través de las llamadas entre servicios para entrelazar una
operación multiservicio en los logs y las trazas.
