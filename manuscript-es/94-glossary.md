Los términos se definen tal como los usa este libro — en el contexto del Java
Firefly Framework y de Spring Boot reactivo. Cuando un término tiene un
significado más amplio en otros ámbitos, la definición que damos aquí es la
práctica que necesitas para Lumen Lending. Cuando se describe una capacidad pero
*no* está cableada en el ejemplo ejecutable, la entrada lo dice con claridad e
indica dónde encajaría.

**Actuator.** El conjunto de endpoints de producción de Spring Boot —
`/actuator/health`, `/actuator/info`, métricas y más. El starter de Firefly
activa los adecuados por defecto y aporta indicadores de salud para los
subsistemas que cableó (buses CQRS, EDA, R2DBC), de modo que cada servicio de la
flota es observable de la misma manera. Un `"status":"UP"` verde de nivel
superior es la primera prueba de vida; en el ejemplo se sirve en
`:8081/actuator/health` (core), `:8082` (domain) y `:8080` (experience).

**Agregado.** En el diseño dirigido por el dominio, un grupo de objetos tratados
como una sola unidad para los cambios de datos, con una *raíz del agregado*
(aquí, `LoanApplication`) que hace cumplir las invariantes del grupo. El código
externo solo toca la raíz.

**Auto-configuración.** Un mecanismo de Spring Boot: clases de configuración
(anotadas con `@AutoConfiguration`, registradas en
`META-INF/spring/...AutoConfiguration.imports`) que se activan según lo que haya
en el classpath y qué beans ya existan, aplicando valores por defecto sensatos y
cediendo el paso cuando defines los tuyos. Cada capacidad de Firefly se entrega
como auto-configuración condicionada por `@ConditionalOnProperty` y
`@ConditionalOnMissingBean` — por eso "añadir una dependencia" equivale a
"encender una capacidad", y por eso los puntos de entrada del ejemplo permanecen
vacíos.

**Contrapresión.** La capacidad de un flujo reactivo de señalar que no puede
seguir el ritmo, de modo que un productor rápido no desborde a un consumidor
lento. Está integrada en Project Reactor; rara vez la gestionas a mano, pero es
la razón por la que `Flux` es seguro de usar a través de una red.

**Base-path.** Una propiedad de configuración que nombra la URL raíz de un
servicio aguas abajo, a partir de la cual una capa construye su `WebClient`. En
el ejemplo, la capa de experience lee
`lumen.exp.loan-origination.base-path` para alcanzar el domain en
`http://localhost:8082`, y el domain lee
`firefly.lumen.core.loan-origination.base-path` para alcanzar el core en
`http://localhost:8081`. Establecer (u omitir) un base path es lo que conmuta el
cliente HTTP en vivo entre encendido y apagado vía `@ConditionalOnProperty`.

**Bean.** Un objeto creado y gestionado por el contexto de aplicación de Spring.
Declaras beans con estereotipos (`@Component`, `@Service`, …) y los recibes por
inyección de constructor. Definir tu propio bean es también la forma de
*sobrescribir* un valor por defecto de Firefly, ya que la auto-configuración está
condicionada por `@ConditionalOnMissingBean`.

**BFF (Backend-for-Frontend).** Un servicio que existe para servir a una clase de
cliente (una app móvil, una app web), dándole forma y agregando los datos aguas
abajo para él. En Firefly esto es la capa de *experience*, construida sobre
`starter-application`; en el ejemplo es `exp-lending` en el puerto `8080`.

**BOM (Bill of Materials).** Un POM que contiene únicamente gestión de
*versiones* de dependencias. Importar el `fireflyframework-bom` permite a tus
servicios declarar dependencias del framework sin versiones, todas garantizadas
para concordar. El ejemplo fija el framework `26.06.01` de esta manera.

**Comando (CQRS).** Una instrucción para cambiar el estado (por ejemplo,
`RegisterLoanApplication`). Despachado en el `CommandBus` a exactamente un
manejador. Contrasta con una *consulta*.

**Bus de comandos/consultas.** El componente de Firefly que enruta un comando o
una consulta a su único manejador registrado, aplicando validación,
autorización, métricas y trazado a su alrededor automáticamente. Al arrancar, los
buses informan de cuántos manejadores encontraron (`DefaultCommandBus ready with
N registered handlers`).

**Compensación.** En una saga, la acción que deshace un paso previamente
completado cuando un paso posterior falla — el sustituto de los sistemas
distribuidos para un rollback de transacción. En el ejemplo, el core expone un
`DELETE /api/v1/loan-applications/{id}` idempotente para que el paso raíz de la
saga pueda compensar una solicitud registrada.

**Correlation ID.** Un identificador adjuntado a una petición y propagado a
través de cada salto (logs, llamadas aguas abajo, eventos) para que una operación
lógica pueda trazarse de extremo a extremo. Firefly lo lleva por ti a través de
las fronteras reactivas; en los logs JSON aflora como `traceId`/`spanId`. Véase
también *ExecutionContext* y *X-Transaction-Id*.

**Capa core.** Los servicios sistema-de-registro que poseen la base de datos y
exponen APIs CRUD reactivas simples y de negocio. Construidos sobre
`starter-core` + `fireflyframework-r2dbc`; en el ejemplo,
`core-lending-loan-origination` en el puerto `8081`, que persiste las solicitudes
de préstamo en H2 en memoria sobre R2DBC con esquema gestionado por Flyway.

**CQRS (Command Query Responsibility Segregation).** Separar el camino de
escritura (comandos) del camino de lectura (consultas) para que cada uno pueda
modelarse, escalarse y cachearse de forma independiente. El bus de consultas puede
respaldarse con `fireflyframework-cache`.

**Capa data.** Servicios dedicados al enriquecimiento, la calidad de los datos y
el linaje (por ejemplo, datos de buró de crédito). Construidos sobre
`starter-data`. *No cableada en el ejemplo* — el reactor de préstamos entrega solo
core, domain y experience; la capa data se describe como el lugar donde
encajarían los servicios de buró/enriquecimiento.

**Evento de dominio.** Un registro de que algo significativo ocurrió en el dominio
(`LoanApplicationRegistered`). Publicado a través de la capa EDA; consumido por
listeners y otros servicios.

**Capa domain.** Los servicios de orquestación que convierten operaciones de
negocio gruesas en comandos, consultas y sagas, sin poseer ninguna base de datos
y llamando a los servicios core a través de costuras SDK. Construidos sobre
`starter-domain`; en el ejemplo, `domain-lending-loan-origination` en el puerto
`8082`, donde se ejecuta `RegisterApplicationSaga`.

**EDA (Event-Driven Architecture).** Un estilo en el que los servicios se
comunican publicando y consumiendo eventos en lugar de llamarse entre sí
directamente. La capa EDA de Firefly es agnóstica al transporte (in-JVM, Kafka,
RabbitMQ, Postgres). El ejemplo ejecuta el transporte in-JVM `APPLICATION_EVENT`
— sin broker, sin Docker; Kafka y los demás son la forma en que escalaría hacia
fuera, no lo que el ejemplo usa.

**EventPublisher.** La abstracción de Firefly que un servicio inyecta para emitir
un evento de dominio sin conocer el transporte. La misma llamada a
`EventPublisher` se enruta a la entrega in-JVM en el ejemplo o a
Kafka/RabbitMQ/Postgres una vez que ese transporte está configurado — el código
que publica no cambia.

**Event sourcing.** Persistir un agregado como la secuencia ordenada de eventos
que le ocurrieron, y reconstruir su estado reproduciéndolos — en lugar de
almacenar solo la instantánea más reciente. *No cableado en el ejemplo*: el core
persiste el estado actual como filas en H2. El event sourcing se presenta como
cómo funciona y dónde encajaría (un almacén de eventos que alimenta
*proyecciones*).

**ExecutionContext.** El objeto de Firefly que lleva la identidad por petición y
los datos multi-tenant (usuario, tenant, organización, sesión, IDs de petición) a
través de los manejadores CQRS, de modo que el comportamiento y el aislamiento
sean conscientes del tenant. Propagado a través de las fronteras reactivas junto
con el correlation ID.

**Capa experience.** Véase *BFF*.

**Jar ejecutable (repackage).** Un jar "gordo" autocontenido producido por el
objetivo `repackage` del plugin de Maven de Spring Boot, ejecutable con
`java -jar`. El ejemplo cablea esto para que cada módulo se ejecute idénticamente
vía `mvn spring-boot:run` o `java -jar target/<module>.jar`.

**Flux.** Un publicador de Project Reactor de **cero a muchos** elementos, de
forma asíncrona y con contrapresión. El análogo reactivo de un flujo de valores.

**Flyway.** Una herramienta de migración de bases de datos. Scripts SQL
versionados (`V1__….sql`) llevan cualquier base de datos a un esquema conocido. En
el ejemplo, la capa core ejecuta Flyway sobre JDBC contra H2 al arrancar, mientras
que las lecturas/escrituras en tiempo de ejecución van sobre R2DBC.

**Framework / metaframework.** Un *framework* te da bloques de construcción y un
lugar para tu código (Spring Boot). Un *metaframework* se construye encima de uno,
añadiendo opiniones y comportamiento transversal precableado para que una flota
sea consistente por defecto (Firefly).

**Arquitectura hexagonal (puertos y adaptadores).** Diseñar un componente en torno
a una interfaz (el *puerto*) con implementaciones intercambiables (los
*adaptadores*). Los cores de integración de Firefly (IDP, ECM, notificaciones)
funcionan así: dependen del puerto, eligen un adaptador con una sola propiedad. La
costura `LoanOriginationClient` del ejemplo es un puerto con dos adaptadores — un
adaptador en vivo `WebClient` y un stub de grabación para pruebas.

**Idempotencia.** La propiedad de que realizar una operación más de una vez tiene
el mismo efecto que hacerlo una sola vez. La capa web de Firefly deduplica las
escrituras reintentadas mediante una cabecera `X-Idempotency-Key`; el ejemplo
registra el `IdempotencyWebFilter` inspeccionando cada escritura. El `DELETE`
compensatorio del core también es idempotente por diseño.

**Mono.** Un publicador de Project Reactor de **como mucho un** elemento (o un
error, o nada), de forma asíncrona. El análogo reactivo de un único valor futuro.

**Enmascarado de PII.** Redactar automáticamente la información de identificación
personal (documentos nacionales, números de tarjeta, tokens) de los logs. Firefly
lo aplica en toda la flota para que los campos sensibles nunca lleguen en claro a
un agregador de logs.

**Puerto.** Véase *arquitectura hexagonal*.

**Proyección.** Un modelo de lectura construido consumiendo eventos — una vista
del estado optimizada para consultas, mantenida al día a medida que llegan los
eventos. Se empareja con el *event sourcing*; *no cableada en el ejemplo*,
presentada como la forma en que se construiría el lado de lectura.

**Consulta (CQRS).** Una petición para leer el estado sin cambiarlo. Despachada en
el `QueryBus`; los resultados pueden estar cacheados.

**R2DBC (Reactive Relational Database Connectivity).** La alternativa no bloqueante
y reactiva a JDBC. Los repositorios devuelven `Mono`/`Flux`; necesaria para
mantener la capa de datos fuera del camino crítico del event loop. La capa core del
ejemplo usa el driver R2DBC de H2 en tiempo de ejecución (movido del scope `test`
al scope `runtime` para que arranque sobre H2 sin base de datos externa), y el
driver JDBC de H2 solo para Flyway.

**Reactivo.** Un modelo de programación construido sobre flujos asíncronos
(`Mono`/`Flux`) con contrapresión, donde describes *qué hacer cuando llegan los
valores* en lugar de bloquearte esperándolos.

**Reactor (Project Reactor).** La biblioteca de reactive-streams que está debajo de
Spring WebFlux, que proporciona `Mono` y `Flux` y el vocabulario de operadores
(`map`, `flatMap`, `zip`, …). Firefly también habilita la propagación automática de
contexto de Reactor para que los valores de `ThreadLocal`/MDC (incluido el contexto
de traza) crucen las fronteras de los hilos.

**RFC 7807 (Problem Details).** El formato JSON estándar del IETF para las
respuestas de error HTTP (`type`, `title`, `status`, `detail`, `instance`),
servido como `application/problem+json`. El `GlobalExceptionHandler` de Firefly lo
emite automática e idénticamente para cada servicio, metiendo los extras del
framework — `traceId`/`spanId`, una pista `retryable`, una `suggestion` de
remediación, una `category` — dentro del objeto estándar `extensions`. (Spring 6
trae `ProblemDetail`; la contribución de Firefly es hacer el contrato común a toda
la flota sin un `@ControllerAdvice` por servicio.)

**Saga.** Una secuencia de pasos locales a través de servicios que juntos logran un
resultado de negocio, con *compensación* para deshacer los pasos completados si uno
posterior falla. La `RegisterApplicationSaga` del ejemplo se ejecuta en la capa
domain: su paso raíz escribe en el core sobre HTTP, y luego dos pasos dependientes
se completan en proceso. El motor de orquestación de Firefly ofrece también
*workflows* y *TCC*.

**Costura SDK / SDK generado.** La interfaz que una capa usa para llamar a la capa
de debajo sobre HTTP — nunca una base de datos compartida. En un servicio Firefly
completo, esto es un cliente tipado y reactivo *generado* a partir de la spec
OpenAPI aguas abajo. El ejemplo escribe a mano una costura recortada
(`LoanOriginationClient`, con un adaptador en vivo respaldado por `WebClient`) que
hace las veces de ese SDK generado; el mapeo de campos más rico se deja
explícitamente al cliente generado real.

**StepVerifier.** Una utilidad de `reactor-test` que se suscribe a un `Mono`/`Flux`
en una prueba y verifica la secuencia exacta de elementos, errores y señales de
finalización — el equivalente reactivo de verificar un valor de retorno.

**Starter.** Una dependencia curada que trae todo para una capacidad o capa,
desencadenando su auto-configuración. Los starters de capa de Firefly son
`starter-core`, `starter-domain`, `starter-data` y `starter-application`; un
starter de Firefly cablea toda una base de referencia con opiniones en lugar de una
sola capacidad.

**TCC (Try-Confirm-Cancel).** Un patrón de transacción distribuida con una
consistencia más fuerte que una saga: cada participante primero *intenta*
(reserva), y luego todos *confirman* o todos *cancelan*. Ofrecido por el motor de
orquestación de Firefly; *no ejercitado en el ejemplo*, que usa una saga.

**Testcontainers.** Una biblioteca que levanta servicios reales (bases de datos,
brokers) en contenedores Docker desechables para pruebas de integración. El ejemplo
de préstamos deliberadamente *no* la usa — sus pruebas se ejecutan contra H2 en
memoria y el transporte EDA in-JVM, de modo que no necesitan Docker; Testcontainers
es el recurso al que acudes cuando una prueba debe ejercitar un Postgres o un Kafka
reales.

**Capa.** Una de las cuatro capas arquitectónicas — experience, domain, core, data
— cada una respaldada por su propio starter de Firefly, integrándose sobre
contratos en lugar de un esquema compartido. El ejemplo cablea tres de las cuatro
(experience → domain → core).

**WebClient.** El cliente HTTP no bloqueante de Spring WebFlux. Las capas de
Firefly lo usan para llamar a la capa de debajo: la capa de experience del ejemplo
construye un `WebClient` a partir de su base-path para llamar al domain, y el domain
construye uno para llamar al core. Es el adaptador concreto detrás de la costura SDK.

**WebFlux.** El stack web reactivo de Spring, la contraparte no bloqueante de Spring
MVC. Los controladores devuelven `Mono`/`Flux` y se ejecutan sobre un event loop de
Netty (`Netty started on port …` en el log de arranque).

**Workflow (orquestación).** Una secuencia de pasos hacia delante sin rollback
automático — el más simple de los tres patrones de orquestación de Firefly
(workflow, saga, TCC).

**X-Idempotency-Key.** La cabecera de petición que el `IdempotencyWebFilter` de
Firefly lee para deduplicar las escrituras reintentadas, de modo que un cliente que
reenvía un `POST` tras un timeout no cree un segundo recurso. Véase *idempotencia*.

**X-Transaction-Id.** Una cabecera que Firefly propaga a través de las llamadas
entre servicios para coser una operación multiservicio en los logs y las trazas.
Cuando no se suministra ninguna, el `TransactionFilter` genera una (`Generated new
transaction ID: …` en el log de arranque).
