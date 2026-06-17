Has construido tres capas sin nombrar en voz alta la arquitectura ni una sola vez. Los
capítulos 6 a 8 construyeron un servicio **core** — el sistema de registro de
originación de préstamos, con un controlador reactivo sobre R2DBC. Los capítulos 10 a
13 construyeron un servicio de **dominio** — manejadores CQRS, una saga, eventos, todo
orquestando sobre un puerto en lugar de una base de datos. Este capítulo añade la
tercera, la capa de **experiencia**, y luego da un paso atrás para nombrar la forma
completa: la arquitectura de cuatro capas que organiza toda flota de Firefly, y la única
regla que la mantiene unida.

La forma no es decoración. Cada capa tiene un trabajo distinto, elige un starter
distinto e integra con sus vecinas sobre un *contrato* — un SDK generado o una llamada
HTTP — nunca sobre una base de datos compartida. Esa única restricción es lo que permite
que una plataforma de cien servicios evolucione un servicio cada vez. La conociste como
una promesa en el capítulo 1; ahora tienes tres capas delante para hacerla concreta.

Y esta vez la arquitectura no es un diagrama que aceptas por fe. El reactor de Lumen
ahora **se ejecuta de extremo a extremo** — tres aplicaciones Spring Boot en tres
puertos, sin Docker — y un único `POST` de canal fluye hacia abajo por toda la pila y de
vuelta hacia arriba. Listaremos los directorios para que puedas ver las capas como
módulos reales, abriremos las dos costuras de la capa de experiencia — su controlador
de cara al canal y el puerto reactivo que es su frontera con el dominio — abriremos la
costura de dominio correspondiente, donde realmente ocurre el salto HTTP en vivo,
recorreremos los cuatro starters y la regla de no compartir base de datos, y luego
*arrancaremos las tres capas y veremos viajar la petición*. La porción de Lumen incluye
tres de las cuatro capas; la cuarta, **data**, la nombramos aquí y la construimos en el
capítulo 15.

## Los cuatro directorios

Abre `samples/lumen-lending` y la arquitectura está ahí, en el listado del directorio.
Tres módulos, uno por cada capa construida hasta ahora, bajo un único POM padre:

```text
samples/lumen-lending/
├── pom.xml                              # the parent: BOM import, module list
├── exp-lending/                         # experience tier  (starter-application)  :8080
├── domain-lending-loan-origination/     # domain tier      (starter-domain)       :8082
└── core-lending-loan-origination/       # core tier        (starter-core)         :8081
```

Lee los nombres como una pila. Una petición de canal aterriza en `exp-lending`, el
Backend-for-Frontend, en el puerto **8080**. Este llama a
`domain-lending-loan-origination`, la capa de orquestación que ejecuta los comandos CQRS
y la saga, en el **8082**. Ese servicio de dominio llama a
`core-lending-loan-origination`, el sistema de registro que posee el esquema y las
filas, en el **8081**. Los datos fluyen hacia abajo por la pila a la entrada y de vuelta
hacia arriba a la salida, y en cada frontera la llamada cruza un *contrato de red*, no
una llamada a un método dentro de código compartido.

Los puertos tampoco son arbitrarios: son los puertos a los que el ejemplo en ejecución
realmente se vincula, capturados directamente de los ficheros `application.yml` del
reactor y de la ejecución verificada del README. Usarás los tres antes de terminar este
capítulo.

La cuarta capa, **data**, se situaría junto a estas como otro módulo sobre
`starter-data` — enriquecimiento de buró de crédito, calidad de datos, linaje. La porción
de Lumen aún no la construye; el capítulo 15 la presenta. Por ahora, quédate con la
imagen de tres módulos reales más uno nombrado-pero-aún-no-construido.

!!! note "Término clave — capa"
    Una **capa** en una plataforma Firefly es un servicio cuyo *rol* viene fijado por la
    arquitectura: **experiencia** compone para un canal, **dominio** orquesta flujos de
    negocio, **core** posee los datos, **data** los enriquece. Cada rol se corresponde con
    un starter de capa, y las capas integran únicamente sobre contratos. «¿Qué capa es
    esta?» se responde según qué starter declara el POM — no según una convención de
    nomenclatura que tengas que recordar.

## La costura externa de la capa de experiencia

La capa de experiencia es la única con la que un canal — una app móvil, un cliente web —
habla directamente. Su controlador es una capa de composición fina y sin estado: valida
la petición con la forma del canal, llama aguas abajo, devuelve un DTO ligero. Abre la
única clase web de `exp-lending` y fíjate en cuán poca lógica de negocio contiene.

::: listing exp-lending/src/main/java/com/firefly/lumen/exp/web/ApplicationController.java | Listado 14.1 — el controlador de la capa de experiencia: ruta base /api/v1/experience/lending/applications
@RestController
@RequestMapping("/api/v1/experience/lending/applications")
@Tag(name = "Lending - Applications")
public class ApplicationController {

    private final ApplicationService applicationService;

    public ApplicationController(ApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "createApplication", summary = "Create Application",
            description = "Creates a new loan application via the domain origination service.")
    @Secure(permissions = {"lending:application:create"},
            description = "Create a loan application")
    public Mono<ResponseEntity<ApplicationDetailDTO>> createApplication(
            @Valid @RequestBody CreateApplicationRequest request) {
        return applicationService.createApplication(request)
                .map(result -> ResponseEntity.status(HttpStatus.CREATED).body(result));
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getApplication", summary = "Get Application",
            description = "Retrieves the full details of a loan application by its identifier.")
    @Secure(permissions = {"lending:application:read"},
            description = "Read a loan application")
    public Mono<ResponseEntity<ApplicationDetailDTO>> getApplication(@PathVariable UUID id) {
        return applicationService.getApplication(id)
                .map(ResponseEntity::ok);
    }
}
:::

Dos cosas marcan esto como la capa de *experiencia*, y no como el controlador core que
escribiste en el capítulo 6.

Primero, la ruta base. `/api/v1/experience/lending/applications` está bajo el espacio de
nombres `experience` porque es una superficie de cara al canal, distinta de la API de
sistema de registro del core `/api/v1/loan-applications`. Las dos rutas viven en dos
servicios diferentes en dos puertos diferentes — exp en 8080, core en 8081 — y un
cliente nunca alcanza el core directamente, solo la capa de experiencia que tiene
delante.

Segundo, el controlador no hace trabajo de negocio. `createApplication` valida la
petición y delega en `applicationService`, que llama *aguas abajo* — no toca ningún
repositorio, porque la capa de experiencia no posee base de datos. Compone una llamada a
la capa de dominio y mapea el resultado. Ese es todo el trabajo de un BFF: dar forma,
llamar, dar forma de vuelta. La autorización a nivel de método `@Secure` proviene del
starter de aplicación; lo cubrimos por completo en el capítulo 19.

!!! spring "Equivalente en Spring"
    Todo lo estructural aquí es Spring WebFlux puro — `@RestController`,
    `@RequestMapping`, `@PostMapping`, `@GetMapping`, `@PathVariable`, `@Valid`,
    `ResponseEntity`. El par `@Tag`/`@Operation` es springdoc. La única anotación de
    Firefly es `@Secure`, un estereotipo meta-anotado dirigido por el `SecurityAspect`
    del starter. Si has escrito un controlador WebFlux, la capa de experiencia no
    esconde sorpresas — su carácter distintivo es arquitectónico (dónde se sitúa, con
    qué habla), no sintáctico.

## La costura interna de la capa de experiencia

El controlador delega en un servicio, y el servicio alcanza la capa de dominio a través
de un *puerto* — una interfaz que es la frontera experiencia-a-dominio. Este es el mismo
patrón que conociste en el capítulo 10, donde la capa de dominio alcanzaba el core a
través de `LoanOriginationClient`. Una capa más arriba, la forma se repite: la capa de
experiencia depende de una interfaz, nunca de un cliente concreto, y el SDK generado se
enchufa detrás de ella.

::: listing exp-lending/src/main/java/com/firefly/lumen/exp/client/LoanOriginationDomainClient.java | Listado 14.2 — la costura SDK exp→dominio: un puerto reactivo hacia el servicio de dominio
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

Cada método devuelve un `Mono`, porque toda la pila de experiencia — controlador,
servicio, el salto HTTP al servicio de dominio — es no bloqueante de extremo a extremo. Y
cada método recibe una `idempotencyKey`: la capa de experiencia acuña una clave
determinista por petición lógica de modo que una llamada de canal reintentada se
deduplique aguas abajo en lugar de crear una segunda solicitud. La clave es la
contribución de la capa de experiencia a los reintentos seguros a través de la frontera
de red.

Esa clave no es decorativa — el servicio la deriva de los campos *estables* de la
petición, de modo que un reintento de la misma petición lógica produce la misma clave. El
adaptador de producción entonces la envía como una cabecera estándar `Idempotency-Key`
por el cable hacia la capa de dominio. Puedes ver ambas mitades en el reactor: el
servicio construye la clave, y el adaptador respaldado por `WebClient` la reenvía como
cabecera (más sobre ese adaptador abajo).

La misma honestidad del capítulo 10 aplica aquí, una capa más arriba. En un despliegue
real de Firefly, `exp-lending` no escribe a mano esta interfaz — inyecta el *SDK de
dominio generado*, un cliente basado en `WebClient` producido a partir del contrato
OpenAPI del servicio de dominio y cableado por un `ClientFactory`. El reactor escribe a
mano un puerto recortado para que el ejemplo compile y sus tests se ejecuten con **ningún
servicio de dominio en ejecución y sin Docker**; un stub en memoria vive bajo
`src/test/java`. El capítulo 16 es donde el SDK generado y sus valores por defecto
resilientes — reintentos, timeouts, un cortacircuitos — toman el relevo, y el capítulo 17
vuelve a la capa de experiencia en su totalidad.

!!! warning "El puerto es la costura del SDK, no un cliente escrito a mano que envías"
    Lee `LoanOriginationDomainClient` como «aquí es donde se enchufa el SDK de dominio
    generado», exactamente como leíste `LoanOriginationClient` en el capítulo 10 para el
    salto dominio→core. La ruta de producción es un cliente `WebClient` generado y
    resiliente; el puerto existe para que esta porción pueda enseñar la *frontera de
    capa* sin levantar el servicio aguas abajo. La frontera es la lección; el stub es
    andamiaje.

!!! note "Término clave — la costura de capa (un puerto reactivo)"
    Una **costura de capa** es la interfaz de la que una capa depende para llamar a la
    siguiente. Es un puerto reactivo — métodos que devuelven `Mono`/`Flux` — de modo que
    el llamador permanece no bloqueante a través del salto de red. La costura es lo que
    hace que la regla de no compartir base de datos sea exigible en el código: una capa
    que solo puede ver una interfaz *no puede* alcanzar las tablas de una vecina.
    `LoanOriginationDomainClient` (exp→dominio) y `LoanOriginationClient` (dominio→core)
    son las dos costuras de la porción de Lumen.

## La cara correspondiente de la capa de dominio

Una costura tiene dos extremos. La capa de experiencia *llama* al puerto
`LoanOriginationDomainClient`; algo tiene que *responderle*. Ese algo es el controlador
REST de la capa de dominio — el segundo salto del flujo en vivo. Es deliberadamente la
imagen especular del puerto: la misma ruta `/api/v1/applications` a la que postea el
adaptador exp, la misma petición con forma de canal y la misma vista de detalle. Abre la
capa web del dominio y lee la clase que el BFF realmente alcanza por HTTP.

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/web/LoanOriginationController.java | Listado 14.3 — la cara REST de la capa de dominio: ejecuta la saga que el BFF dispara
@RestController
@RequestMapping("/api/v1/applications")
@Tag(name = "Loan Origination - Applications")
public class LoanOriginationController {

    private static final Logger log = LoggerFactory.getLogger(LoanOriginationController.class);

    /** Default offered rate, in basis points, for the saga's propose-offer step. */
    private static final int DEFAULT_ANNUAL_RATE_BPS = 575;

    private final LoanOriginationService service;
    private final ObjectProvider<CoreLoanApplicationReader> coreReader;

    public LoanOriginationController(LoanOriginationService service,
                                     ObjectProvider<CoreLoanApplicationReader> coreReader) {
        this.service = service;
        this.coreReader = coreReader;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "submitApplication", summary = "Submit Application",
            description = "Runs the RegisterApplicationSaga, writing the application to the core system of record.")
    public Mono<ResponseEntity<ApplicationDetailView>> submit(@RequestBody ApplicationChannelRequest request) {
        long amountMinor = toMinorUnits(request.requestedAmount());
        String applicantName = applicantNameFor(request);
        log.debug("Submitting application productId={} simulationId={} requestedAmount={} term={}",
                request.productId(), request.simulationId(), request.requestedAmount(), request.term());

        return service.submitApplication(applicantName, amountMinor, DEFAULT_ANNUAL_RATE_BPS)
                .flatMap(result -> toDetail(result, request))
                .map(detail -> ResponseEntity.status(HttpStatus.CREATED).body(detail));
    }
:::

Este es el extremo receptor del salto exp→dominio, y está haciendo el trabajo real de la
capa de dominio: no persiste nada por sí mismo, *orquesta*. El método `submit` ejecuta
`service.submitApplication(...)`, que dirige la `RegisterApplicationSaga` que construiste
en el capítulo 11 — y el paso raíz de la saga es lo que alcanza el core. Fíjate en que el
constructor inyecta un `ObjectProvider<CoreLoanApplicationReader>`: la ruta de lectura es
*opcional*, presente solo cuando hay un core en vivo cableado, que es por lo que el
servicio de dominio todavía puede arrancar de forma autónoma sin ningún core detrás.

!!! note "Término clave — la cara REST de la capa de orquestación"
    La capa de dominio expone HTTP solo para que la capa que tiene encima pueda llamarla;
    sus endpoints son disparadores finos para la orquestación, no CRUD sobre una tabla.
    `submit` no escribe una fila — inicia una saga, y la saga decide qué escribir y dónde.
    El trabajo del controlador es traducir la petición de canal en una invocación de saga
    y mapear el `SagaResult` de vuelta a una vista de canal. La persistencia vive una capa
    más abajo, en el core.

## Dónde ocurre el salto HTTP en vivo

El paso raíz de la saga escribe en el core, pero un paso de saga no es un cliente HTTP.
Llama al *mismo* puerto `LoanOriginationClient` que conociste en el capítulo 10 — y en la
pila en vivo ese puerto está respaldado por un adaptador `WebClient` que hace POST al
servicio core en el 8081. Esta es la implementación real de la costura dominio→core, el
gemelo del adaptador exp→dominio. Aquí está el método de escritura que cierra el bucle:

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/client/WebClientLoanOriginationClient.java | Listado 14.4 — la costura de escritura dominio→core: un WebClient que hace POST al core en :8081
    @Override
    public Mono<UUID> createLoanApplication(String applicantName, long amount) {
        CoreCreateRequest body = new CoreCreateRequest(
                UUID.randomUUID(),
                BigDecimal.valueOf(amount, 2),
                DEFAULT_CURRENCY,
                DEFAULT_TERM_MONTHS,
                DEFAULT_PURPOSE);
        log.debug("Core create loan-application applicant={} amountMinor={}", applicantName, amount);
        return webClient.post()
                .uri(LOAN_APPLICATIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(CoreLoanApplicationResponse.class)
                .map(CoreLoanApplicationResponse::loanApplicationId);
    }
:::

Este es el único lugar de toda la pila que habla con la API `/api/v1/loan-applications`
del core, y lo hace por HTTP — el `webClient` se construye contra la URL base del core, y
el `loanApplicationId` de la respuesta es el id asignado por el core que viaja de vuelta
hacia arriba hasta el canal. El adaptador implementa el mismo puerto
`LoanOriginationClient` que los tests de la porción sustituyen por un stub, de modo que la
*saga* nunca sabe si está hablando con un core real por la red o con un grabador en
memoria.

Sé honesto sobre el mapeo, eso sí, porque la porción es deliberadamente mínima. La
costura de escritura recortada lleva solo el nombre del solicitante y la cantidad; los
demás campos del core se rellenan con valores por defecto — `DEFAULT_CURRENCY = "EUR"`,
`DEFAULT_PURPOSE = "GENERAL"`, `DEFAULT_TERM_MONTHS = 12`. Así que cuando envías a través
del BFF con `term: 36`, la fila que aterriza en el core muestra `termMonths: 12` y
`purpose: "GENERAL"`. Eso no es un fallo — es la costura recortada siendo fiel sobre lo
que reenvía. El mapeo más rico campo a campo es exactamente lo que el SDK *generado*
restaura en el capítulo 16; aquí el punto es que el **salto es real**, no que cada campo
viaje en él.

!!! warning "El mapeo dominio→core es intencionadamente mínimo"
    La costura de escritura en vivo reenvía el solicitante y la cantidad y deja que el
    core asigne por defecto el resto, de modo que `currency`, `termMonths` y `purpose`
    pueden no coincidir con lo que posteaste al BFF. Léelo como la *costura funcionando*,
    no como el mapeo estando completo. El SDK generado del capítulo 16 lleva el payload
    completo; la lección aquí es la frontera y el salto de red, que son genuinamente en
    vivo.

El adaptador en vivo no se cablea de forma incondicional — se activa solo cuando un
operador apunta la capa de dominio a un core real. Ese interruptor es una única
propiedad, y una pequeña `@Configuration` la lee:

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/config/LiveLoanOriginationClientConfig.java | Listado 14.5 — la costura de core en vivo es condicional a una ruta base configurada
    @Bean
    @ConditionalOnProperty(prefix = "firefly.lumen.core.loan-origination", name = "base-path")
    @ConditionalOnMissingBean(name = "coreLoanOriginationWebClient")
    public WebClient coreLoanOriginationWebClient(CoreLoanOriginationProperties properties) {
        log.info("Building core Loan Origination WebClient basePath={} timeout={}",
                properties.basePath(), properties.timeout());
        return WebClient.builder()
                .baseUrl(properties.basePath())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_SIZE))
                .build();
    }
:::

Dos condiciones hacen esto seguro. `@ConditionalOnProperty(... name = "base-path")`
significa que el `WebClient` en vivo solo se materializa cuando
`firefly.lumen.core.loan-origination.base-path` está establecida — y el `application.yml`
ejecutable la fija a `http://localhost:8081`, de modo que la app de dominio autónoma llama
al core en ejecución. `@ConditionalOnMissingBean` significa que el stub en memoria de los
tests de la porción siempre gana, de modo que activar el cliente en vivo nunca perturba
los 33 tests en verde. El mismo patrón condicional protege el adaptador de la capa exp,
con clave `lumen.exp.loan-origination.base-path` apuntando a `http://localhost:8082`. La
costura es una interfaz; *qué* implementación la rellena es una decisión de
configuración, no un cambio de código.

!!! spring "Equivalente en Spring"
    `@ConditionalOnProperty` y `@ConditionalOnMissingBean` son condiciones de
    autoconfiguración estándar de Spring Boot — las mismas que usan los propios starters
    del framework. Firefly no añade ningún mecanismo nuevo aquí; simplemente aplica el
    idioma corriente de Boot a la costura de capa: *configura una ruta base → aparece el
    cliente HTTP en vivo; déjala sin establecer → un valor por defecto dentro de la JVM
    (o un stub de test) ocupa su lugar.* Una propiedad cambia una capa de autónoma a
    cableada.

## Las cuatro capas, y por qué cada una elige su starter

Ahora la forma completa. Cuatro capas, cuatro starters, cada uno empaquetando las
capacidades y los valores por defecto de producción que su rol necesita y nada que no
necesite. El starter es la manera en que un servicio declara qué capa es — una única
dependencia, sin versión porque el BOM del capítulo 3 la fija.

- **Experiencia (`exp-lending`) — `fireflyframework-starter-application`.** El BFF de
  cara al canal, en el puerto **8080**. Composición sin estado: validar, llamar aguas
  abajo, dar forma a un DTO. Su starter trae las preocupaciones del canal — seguridad a
  nivel de método (`@Secure`), caché, la maquinaria de cliente resiliente para llamar a
  los servicios de dominio — pero ninguna persistencia, porque no posee datos. Esta es la
  capa de los listados 14.1 y 14.2.
- **Dominio (`domain-lending-loan-origination`) — `fireflyframework-starter-domain`.**
  Orquestación de negocio, en el puerto **8082**. Traduce peticiones de canal de grano
  grueso en comandos y consultas CQRS, ejecuta sagas compensatorias, emite eventos de
  dominio (capítulos 10–13). Su starter trae los buses CQRS, el motor de sagas y el
  runtime EDA — y, como la capa de experiencia, ninguna base de datos, porque llama al
  core sobre un SDK. Esta es la capa de los listados 14.3 a 14.5.
- **Core (`core-lending-loan-origination`) — `fireflyframework-starter-core` más
  R2DBC.** El sistema de registro, en el puerto **8081**. Posee el esquema, las filas y
  el CRUD reactivo y las APIs de negocio sobre ellas (capítulos 6–8). Es la *única* capa
  con un almacén de datos, así que su starter se empareja con la pila reactiva R2DBC —
  ejecutándose sobre H2 en memoria aquí, con Flyway migrando el esquema.
- **Data — `fireflyframework-starter-data`.** Enriquecimiento, calidad de datos y linaje
  — consultas al buró de crédito, entradas de scoring, las preocupaciones de plataforma
  de datos. Lumen la nombra aquí y la construye en el **capítulo 15**.

Mira atrás a los tres POM del capítulo 3 y el patrón es exacto: cada módulo declara
precisamente un starter de capa, sin versión, y hereda el resto del padre. El POM de
experiencia declara `fireflyframework-starter-application`; el POM de dominio,
`fireflyframework-starter-domain`; el POM de core, `fireflyframework-starter-core`. El
starter no es una comodidad — es la respuesta legible por máquina a «¿qué tipo de servicio
es este?».

!!! spring "Equivalente en Spring"
    Un starter de capa de Firefly *es* un starter de Spring Boot — un POM que agrega
    dependencias y autoconfiguraciones, el mismo mecanismo detrás de
    `spring-boot-starter-web`. Lo que Firefly añade es la *opinión*: cuatro starters
    alineados con cuatro roles arquitectónicos, cada uno precableando el comportamiento
    transversal (seguridad, CQRS, persistencia, clientes resilientes) que ese rol
    necesita. Añadir `starter-domain` es para un servicio de dominio lo que añadir
    `starter-web` es para una aplicación web — una línea que enciende una porción
    coherente de comportamiento.

## La regla cardinal: integrar sobre contratos, nunca una base de datos compartida

Todo lo anterior descansa sobre una restricción, y vale la pena enunciarla como una ley:

> Las capas integran sobre SDKs generados y HTTP. **Dos capas nunca comparten una base
> de datos.**

Solo la capa core posee un almacén de datos. La capa de dominio lo alcanza a través de
`LoanOriginationClient` — y acabas de ver el cuerpo en vivo de ese puerto en el listado
14.4: un `WebClient` haciendo POST al core en 8081, no una consulta contra las tablas del
core. La capa de experiencia alcanza el dominio a través de `LoanOriginationDomainClient`,
que en la pila en vivo hace POST al dominio en 8082. Ni el servicio de experiencia ni el
de dominio tienen una dependencia R2DBC, un pool de conexiones ni un repositorio — y eso
es por diseño, no por omisión. Si la capa de dominio pudiera consultar las tablas del core
directamente, cada cambio en el esquema del core arriesgaría romper el servicio de dominio
en silencio, y los dos quedarían soldados exactamente del mismo modo que el manejador CQRS
quedó soldado a sus consumidores en el capítulo 11.

Integrar sobre un *contrato* rompe esa soldadura. El core puede reconfigurar su
almacenamiento detrás de una superficie OpenAPI estable; el dominio solo ve el SDK
generado; la experiencia solo ve el SDK del dominio. Cada capa puede desplegarse, escalarse
y evolucionar de forma independiente porque lo único que cruza una frontera es un contrato
de cable versionado. Esta es la regla del capítulo 1 — *integrar sobre contratos, no sobre
un esquema compartido* — y los dos puertos que abriste son donde se exige en el código: una
capa que solo puede ver una interfaz físicamente no puede alcanzar las tablas de una vecina.

!!! warning "Una base de datos compartida es el modo de fallo, no un atajo"
    La forma más común en que los equipos destruyen calladamente una arquitectura por
    capas es dejar que dos servicios apunten a la misma base de datos «solo para esta
    consulta». En el momento en que lo hacen, las capas quedan acopladas en el esquema,
    los despliegues deben coordinarse y la independencia que la arquitectura prometía se
    ha esfumado. Si una capa necesita datos que no posee, llama al dueño sobre el SDK del
    dueño. No hay excepción que no te cueste la arquitectura.

## Cómo la configuración sigue a las capas

Las capas no solo estructuran el código — estructuran la *configuración*. Recuerda la
jerarquía de configuración del capítulo 4: el servidor de configuración de Firefly sirve
ajustes en capas, y las capas reflejan los tiers. Los ajustes **common** aplican a toda la
flota — formato de log, el endpoint de trazas, convenciones compartidas. Las capas
**core**, **domain** y **experience** contienen cada una los ajustes compartidos por todo
servicio de *esa* capa. Y el `application.yml` propio de cada servicio contiene lo que es
cierto solo para él.

Puedes ver esa última capa de forma concreta en el ejemplo ejecutable. El `application.yml`
del servicio de dominio fija `firefly.lumen.core.loan-origination.base-path:
http://localhost:8081` — lo único cierto para *este* servicio: dónde vive su core. El del
servicio exp fija `lumen.exp.loan-origination.base-path: http://localhost:8082`. Esos son
ajustes por servicio. Un timeout aguas abajo por defecto, en cambio, es cierto para *todo*
servicio de dominio y viviría en la capa **domain**; el formato de log JSON es cierto para
toda la flota y vive en **common**.

Así que una capa es un ámbito de configuración además de un rol de código. Un ajuste que
todo servicio de dominio necesita — un timeout por defecto, una propiedad de saga — vive en
la capa **domain** y todo servicio de dominio lo hereda; un ajuste que todo servicio core
necesita vive en **core**. Un servicio nuevo se une a la jerarquía simplemente declarando
su starter de capa y apuntando al servidor de configuración: el starter establece la capa,
y la capa selecciona qué capas compartidas hereda. Esa es la convención que el capítulo 4
nombró, vista ahora desde el lado de la arquitectura — las mismas cuatro palabras, `common`
/ `core` / `domain` / `experience`, organizan por igual el grafo de dependencias y el grafo
de propiedades.

!!! spring "Equivalente en Spring"
    La jerarquía cabalga sobre Spring Cloud Config, cuya resolución de propiedades por
    capas podrías montar a mano. La contribución de Firefly es la *convención* de que las
    capas son exactamente los tiers — de modo que «¿qué configuración compartida hereda
    este servicio?» tiene la misma respuesta que «¿qué starter declara?». Un concepto, la
    capa, indexa tanto la build como la configuración.

## Ejecuta toda la pila

Ahora la recompensa. La arquitectura no es una afirmación — puedes arrancar las tres capas
y ver una petición viajar la ruta completa `exp → domain → core` y de vuelta. Cada módulo
es una aplicación Spring Boot independiente; cada una se ejecuta con el plugin de Maven (o
como un `java -jar` ya construido) sobre H2 en memoria y el transporte `APPLICATION_EVENT`
dentro de la JVM, así que **no hay Docker, ni base de datos externa, ni broker de
mensajes**. Desde `samples/lumen-lending`, en tres terminales (el orden no importa — las
capas no fallan rápido ante un aguas abajo ausente):

```bash
( cd core-lending-loan-origination   && mvn spring-boot:run ) &
( cd domain-lending-loan-origination && mvn spring-boot:run ) &
( cd exp-lending                     && mvn spring-boot:run ) &
```

Cada app registra `Started …Application in …` y `Netty started on port …` cuando está
lista — core en 8081, domain en 8082, exp en 8080. Confirma que las tres están vivas:

```bash
curl -s localhost:8081/actuator/health   # core  -> {"status":"UP",...}
curl -s localhost:8082/actuator/health   # domain-> {"status":"UP",...}
curl -s localhost:8080/actuator/health   # exp   -> {"status":"UP",...}
```

### Un POST por toda la pila

Envía una única petición de canal al BFF en 8080. La capa de experiencia la valida, cruza
la costura SDK hacia el dominio en 8082, el dominio ejecuta la `RegisterApplicationSaga`,
el paso raíz de la saga escribe en el core en 8081 por HTTP, y el id asignado por el core
vuelve hacia arriba a través de ambas costuras:

```bash
curl -s -X POST localhost:8080/api/v1/experience/lending/applications \
  -H 'Content-Type: application/json' \
  -d '{"productId":"11111111-1111-1111-1111-111111111111","requestedAmount":25000.00,"term":36,"purpose":"HOME_IMPROVEMENT","simulationId":"22222222-2222-2222-2222-222222222222"}'
```

El BFF responde `201 Created` con la vista de detalle del canal — `status` es `SUBMITTED`,
porque el core envía la solicitud al crearla:

```json
{
  "applicationId": "786544c7-2f10-4110-95fe-682d63edbace",
  "simulationId": "22222222-2222-2222-2222-222222222222",
  "status": "SUBMITTED",
  "requestedAmount": 25000.00,
  "term": 36,
  "purpose": "HOME_IMPROVEMENT",
  "createdAt": "2026-06-17T11:46:21.186231",
  "updatedAt": "2026-06-17T11:46:21.186231"
}
```

Ese `applicationId` no lo acuña el BFF — es el id que el sistema de registro **core**
asignó. Demuéstralo leyendo el mismo id directamente del core en 8081:

```bash
curl -s localhost:8081/api/v1/loan-applications/786544c7-2f10-4110-95fe-682d63edbace
```

```json
{
  "loanApplicationId": "786544c7-2f10-4110-95fe-682d63edbace",
  "applicationNumber": "9d2e8b8c-fc64-4aae-8578-c77558a3ec4b",
  "applicantId": "8db1c7ab-5d74-44fd-a4c7-d9433f0ddaba",
  "requestedAmount": 25000.00,
  "currency": "EUR",
  "termMonths": 12,
  "purpose": "GENERAL",
  "status": "SUBMITTED",
  "decisionReason": null,
  "createdAt": "2026-06-17T11:46:21.145765",
  "updatedAt": "2026-06-17T11:46:21.145781"
}
```

El id coincide — la solicitud realmente aterrizó en el core. Y aquí está la honestidad del
listado 14.4 hecha visible: posteaste `term: 36` y `purpose: "HOME_IMPROVEMENT"`, pero la
fila del core muestra `termMonths: 12`, `currency: "EUR"` y `purpose: "GENERAL"`. Esos son
los valores por defecto de la costura de escritura recortada, exactamente como advirtió el
listado. El salto es real; el mapeo de campos es mínimo por diseño, y el SDK generado del
capítulo 16 es donde se completa.

En la consola de la capa de **dominio** puedes ver la saga dirigir la escritura — el paso
raíz llama al core por HTTP, luego los dos pasos dependientes se completan en proceso:

```text
[orchestration] started   name=RegisterApplicationSaga ... pattern=SAGA
[orchestration] step.success ... stepId=registerLoanApplication latencyMs=94
[orchestration] step.success ... stepId=proposeOffer
[orchestration] step.success ... stepId=registerApplicant
[orchestration] completed name=RegisterApplicationSaga ... pattern=SAGA success=true
```

Esa línea final `completed ... success=true` es la arquitectura demostrándose a sí misma:
una petición de canal se desplegó en abanico a través de dos saltos de red, ejecutó una
saga de varios pasos, escribió en un sistema de registro que no posee y regresó — y ni una
sola capa tocó la base de datos de otra. Cuando hayas terminado, libera los puertos:

```bash
lsof -ti:8080,8081,8082 | xargs kill
```

!!! note "Término clave — el flujo de tres capas en vivo"
    El flujo **exp → domain → core** es la arquitectura ejecutándose. Un `POST` al BFF
    (8080) se convierte en una llamada HTTP al dominio (8082), que ejecuta una saga cuyo
    paso raíz es un `POST` HTTP al core (8081); el id asignado por el core regresa a
    través de ambas costuras. Dos costuras, dos saltos de red, una saga, cero bases de
    datos compartidas — esa es la forma completa ejecutándose, no descrita.

### Demuéstralo sin interfaz

Ni siquiera necesitas las tres terminales para confiar en las fronteras: los tests del
reactor ejercitan cada capa de forma aislada, contra stubs en memoria y H2, sin ningún
vecino en ejecución. Desde `samples/lumen-lending`:

```bash
mvn clean verify
```

Los **33** pasan — core **18**, domain **6**, exp **9**:

```text
Tests run: 33, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Para ejecutar solo la porción de la capa de experiencia de forma aislada — el BFF
arrancando, asegurando sus endpoints y componiendo aguas abajo, todo contra el stub en
memoria del puerto de dominio:

```bash
mvn -q -pl exp-lending test
```

```text
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Esos nueve cubren el arranque del contexto (`ExpLendingApplicationTest`), las rutas de
crear-y-leer del controlador sobre `WebTestClient` (`ApplicationControllerTest`) y la
lógica de composición del servicio contra el puerto stub (`ApplicationServiceTest`). La
sustitución es el mismo truco de costura que viste una capa más abajo: el test suministra
un `LoanOriginationDomainClient` en memoria, y nada en el controlador ni en el servicio
cambia — y como el adaptador `WebClient` en vivo es `@ConditionalOnMissingBean`, el stub
siempre gana en los tests. Eso es la frontera de capa ganándose el sueldo — la capa de
experiencia es testeable en completo aislamiento de la capa de dominio que pone delante.

!!! tip "Punto de control"
    Ejecuta `mvn clean verify` y confirma `Tests run: 33, Failures: 0`. Luego arranca las
    tres capas y ejecuta el único `POST localhost:8080/...` de arriba; comprueba que el
    `applicationId` que devuelve se resuelve con un `GET` contra `localhost:8081`. Si
    ambas cosas se cumplen, toda la pila — dos costuras, una saga, tres puertos — está en
    vivo en tu máquina. Para la ruta aislada, abre `exp-lending/src/test/java` y encuentra
    `StubLoanOriginationDomainClient`, la implementación en memoria del puerto del listado
    14.2: es lo único que hace de sustituto de todo un servicio aguas abajo.

## Lo que has aprendido {.recap}

- Lumen Lending son **cuatro capas** que ahora se ejecutan **en vivo y de extremo a
  extremo** sin Docker: **experiencia** (BFF de cara al canal sobre `starter-application`,
  puerto **8080**), **dominio** (orquestación sobre `starter-domain`, **8082**), **core**
  (sistema de registro sobre `starter-core` más R2DBC, **8081**) y **data**
  (enriquecimiento sobre `starter-data`, nombrada aquí, construida en el capítulo 15).
- Una capa declara su rol mediante el **único starter** que importa su POM — la referencia
  cruzada del capítulo 3, ahora leída como arquitectura: experiencia→`starter-application`,
  dominio→`starter-domain`, core→`starter-core`.
- La capa de experiencia es una capa de composición fina (ruta base
  `/api/v1/experience/lending/applications`, sin lógica de negocio, sin base de datos) que
  alcanza el dominio a través de `LoanOriginationDomainClient`, la **costura SDK
  exp→dominio**, llevando una clave de idempotencia determinista. El
  `LoanOriginationController` del dominio responde a esa costura, ejecuta la
  `RegisterApplicationSaga`, y el paso raíz de la saga llama a la **costura dominio→core**
  — `WebClientLoanOriginationClient`, un `WebClient` que hace POST al core en 8081.
- El flujo en vivo está **demostrado**: un `POST` a 8080 → dominio en 8082 → saga → core en
  8081, devolviendo un `applicationId` asignado por el core y
  `[orchestration] completed ... success=true`. El mapeo dominio→core es intencionadamente
  mínimo — `currency`, `termMonths`, `purpose` aterrizan como valores por defecto del core —
  lo cual es la costura siendo honesta, no rota; el SDK generado del capítulo 16 lleva el
  payload completo.
- La regla cardinal: las capas integran sobre **SDKs generados y HTTP, y nunca comparten una
  base de datos**. Solo el core posee un almacén de datos; los dos puertos reactivos — y sus
  adaptadores `WebClient` en vivo con `@ConditionalOnProperty` — son donde la regla se exige
  en el código.
- La configuración sigue a las capas — la jerarquía **common / core / domain / experience**
  del capítulo 4 usa las mismas cuatro palabras, de modo que la capa de un servicio
  selecciona tanto las dependencias compartidas como la configuración compartida que hereda;
  los ajustes por servicio como `firefly.lumen.core.loan-origination.base-path` viven en el
  `application.yml` propio del servicio.

## Pruébalo tú mismo {.exercises}

1. **Traza una petición bajando por la pila en vivo.** Empezando en `createApplication` en
   `ApplicationController` (8080), sigue la llamada hacia `ApplicationService`, fuera a
   través de `LoanOriginationDomainClient.submitApplication`, dentro del
   `LoanOriginationController.submit` del dominio (8082), a través de la saga, y fuera a
   través de `WebClientLoanOriginationClient.createLoanApplication` (8081). Anota cada
   frontera y marca cuál es una llamada a método y cuál un salto de red real.
2. **Observa encoger el mapeo.** Ejecuta el `POST localhost:8080/...` en vivo con
   `term: 60` y `purpose: "DEBT_CONSOLIDATION"`, luego haz `GET localhost:8081/...` con el
   id devuelto. ¿Qué campos sobrevivieron al viaje y cuáles se convirtieron en valores por
   defecto del core? Abre el listado 14.4 y señala las constantes que lo explican. ¿Por qué
   es esto fiel a la costura recortada en lugar de un fallo?
3. **Encuentra la base de datos ausente.** Abre `exp-lending/pom.xml` y
   `domain-lending-loan-origination/pom.xml` y confirma que ninguno declara R2DBC ni una
   dependencia de repositorio, luego abre `core-lending-loan-origination/pom.xml` y
   encuentra dónde vive el almacén de datos. Explica en una sola frase por qué solo uno de
   los tres lo tiene.
4. **Cambia una costura con una propiedad.** En el `application.yml` de `domain`, comenta
   `firefly.lumen.core.loan-origination.base-path` y reinicia la capa de dominio. Usando el
   listado 14.5, predice qué le ocurre al bean `WebClient` en vivo y a la ruta de lectura
   `GET /api/v1/applications/{id}`. Luego comprueba el log de arranque y un `GET`. ¿Por qué
   `@ConditionalOnProperty` hace que la capa se degrade con elegancia en lugar de fallar?
5. **Compara las dos costuras.** Pon `LoanOriginationDomainClient` (listado 14.2) junto a
   `LoanOriginationClient` (capítulo 10), y sus adaptadores en vivo
   (`WebClientLoanOriginationDomainClient`, posteando a 8082, frente a
   `WebClientLoanOriginationClient` del listado 14.4, posteando a 8081). ¿Qué es lo mismo
   (puertos reactivos, adaptadores `WebClient`, stubs `@ConditionalOnMissingBean` en los
   tests) y qué difiere (la cabecera `idempotencyKey`/`Idempotency-Key` en la costura exp,
   los tipos DTO)? ¿Por qué la costura de experiencia lleva una clave de idempotencia que la
   costura de dominio no exponía?
6. **Coloca un ajuste en la jerarquía.** Dado un timeout aguas abajo por defecto que *todo*
   servicio de dominio debería compartir, la `base-path` que solo usa
   `domain-lending-loan-origination`, y el formato de log JSON de toda la flota, di a qué
   capa de configuración pertenece cada uno — `common`, una capa de tier, o el
   `application.yml` propio del servicio — y justifica cada uno desde el objetivo de no
   duplicación del capítulo 4.

## Adónde ir ahora

Ahora tienes el mapa completo, y lo has visto ejecutarse: cuatro capas, cuatro starters,
dos costuras, una regla, tres puertos en vivo. El siguiente capítulo rellena el rincón del
mapa que Lumen solo ha nombrado — la capa **data** sobre `starter-data`, la capa de
enriquecimiento y calidad de datos que alimenta el scoring del que depende la saga de
dominio. Después de eso, el capítulo 16 reemplaza ambos adaptadores `WebClient` escritos a
mano con los SDKs *generados* y sus valores por defecto resilientes — reintentos, timeouts,
un cortacircuitos — y restaura el mapeo completo de campos que la costura de escritura
recortada dejó en valores por defecto, convirtiendo las costuras que abriste aquí en las
llamadas HTTP reales y resilientes que mantienen unida una flota de Firefly.
