Has construido tres capas sin nombrar nunca la arquitectura en voz alta. Los
capítulos 6 a 8 construyeron un servicio **core** — el sistema de registro de la
originación de préstamos, con un controlador reactivo sobre R2DBC. Los capítulos 10
a 13 construyeron un servicio de **dominio** — manejadores CQRS, una saga, eventos,
todo orquestando sobre un puerto en lugar de una base de datos. Este capítulo añade
la tercera, la capa de **experiencia**, y luego da un paso atrás para nombrar la
forma completa: la arquitectura de cuatro capas que organiza toda flota Firefly, y
la única regla que la mantiene unida.

La forma no es decoración. Cada capa tiene una tarea distinta, elige un starter
distinto e integra con sus vecinas sobre un *contrato* — un SDK generado o una
llamada HTTP — nunca sobre una base de datos compartida. Esa única restricción es lo
que permite a una plataforma de cien servicios evolucionar un servicio cada vez. La
conociste como una promesa en el Capítulo 1; ahora tienes tres capas delante de ti
para hacerla concreta.

Listaremos los directorios para que puedas ver las capas como módulos reales,
diseccionaremos las dos costuras de la capa de experiencia — su controlador de cara
al canal y el puerto reactivo que es su frontera hacia el dominio — y luego
recorreremos los cuatro starters, la regla de no compartir base de datos y la
jerarquía de configuración que une la flota. La porción de Lumen entrega tres de las
cuatro capas; la cuarta, **data**, la nombramos aquí y la construimos en el Capítulo
15.

## Los cuatro directorios

Abre `samples/lumen-lending` y la arquitectura está ahí, en el listado de
directorios. Tres módulos, uno por cada capa construida hasta ahora, bajo un único
POM padre:

```text
samples/lumen-lending/
├── pom.xml                              # the parent: BOM import, module list
├── exp-lending/                         # experience tier  (starter-application)
├── domain-lending-loan-origination/     # domain tier      (starter-domain)
└── core-lending-loan-origination/       # core tier        (starter-core)
```

Lee los nombres como una pila. Una petición de canal aterriza en `exp-lending`, el
Backend-for-Frontend. Este llama a `domain-lending-loan-origination`, la capa de
orquestación que ejecuta los comandos CQRS y la saga. Ese servicio de dominio llama a
`core-lending-loan-origination`, el sistema de registro que posee el esquema y las
filas. Los datos fluyen hacia abajo por la pila en el camino de entrada y de vuelta
hacia arriba en el de salida, y en cada frontera la llamada cruza un *contrato de
red*, no una llamada a método dentro de código compartido.

La cuarta capa, **data**, se situaría junto a estas como otro módulo sobre
`starter-data` — enriquecimiento con la central de riesgos, calidad de datos, linaje.
La porción de Lumen aún no la construye; el Capítulo 15 la introduce. Por ahora,
mantén la imagen en tres módulos reales más uno nombrado-pero-aún-no-construido.

!!! note "Término clave — capa"
    Una **capa** en una plataforma Firefly es un servicio cuyo *rol* está fijado por
    la arquitectura: **experiencia** compone para un canal, **dominio** orquesta
    flujos de negocio, **core** posee los datos, **data** los enriquece. Cada rol se
    corresponde con un starter de capa, y las capas integran solo sobre contratos.
    "¿Qué capa es esta?" se responde con qué starter declara el POM — no con una
    convención de nombres que tengas que recordar.

## La costura externa de la capa de experiencia

La capa de experiencia es la única con la que un canal — una app móvil, un cliente
web — habla directamente. Su controlador es una capa de composición fina y sin
estado: validar la petición con forma de canal, llamar aguas abajo, devolver un DTO
ligero. Abre la única clase web de `exp-lending` y fíjate en lo poca lógica de
negocio que contiene.

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

Dos cosas marcan esto como la capa de *experiencia*, no el controlador core que
escribiste en el Capítulo 6.

Primero, la ruta base. `/api/v1/experience/lending/applications` está dentro del
espacio de nombres `experience` porque es una superficie de cara al canal, distinta
de la API de sistema de registro del core `/api/v1/loan-applications`. Las dos rutas
viven en dos servicios distintos en dos puertos distintos; un cliente nunca alcanza
el core directamente, solo la capa de experiencia que tiene delante.

Segundo, el controlador no hace ningún trabajo de negocio. `createApplication` valida
la petición y delega en `applicationService`, que llama *aguas abajo* — no toca un
repositorio, porque la capa de experiencia no posee base de datos. Compone una llamada
a la capa de dominio y mapea el resultado. Esa es toda la tarea de un BFF: dar forma,
llamar, devolver con forma. La autorización a nivel de método `@Secure` proviene del
starter de aplicación; la cubrimos por completo en el Capítulo 19.

!!! spring "Equivalente en Spring"
    Todo lo estructural aquí es Spring WebFlux puro — `@RestController`,
    `@RequestMapping`, `@PostMapping`, `@GetMapping`, `@PathVariable`, `@Valid`,
    `ResponseEntity`. El par `@Tag`/`@Operation` es springdoc. La única anotación de
    Firefly es `@Secure`, un estereotipo meta-anotado impulsado por el `SecurityAspect`
    del starter. Si has escrito un controlador WebFlux, la capa de experiencia no
    guarda sorpresas — su distinción es arquitectónica (dónde se sitúa, con qué habla),
    no sintáctica.

## La costura interna de la capa de experiencia

El controlador delega en un servicio, y el servicio alcanza la capa de dominio a
través de un *puerto* — una interfaz que es la frontera experiencia-a-dominio. Este
es el mismo patrón que conociste en el Capítulo 10, donde la capa de dominio alcanzaba
el core a través de `LoanOriginationClient`. Una capa más arriba, la forma se repite:
la capa de experiencia depende de una interfaz, nunca de un cliente concreto, y el SDK
generado se conecta detrás de ella.

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
servicio, el salto HTTP al servicio de dominio — es no bloqueante de extremo a
extremo. Y cada método recibe un `idempotencyKey`: la capa de experiencia acuña una
clave determinista por petición lógica de modo que una llamada de canal reintentada se
deduplica aguas abajo en lugar de crear una segunda aplicación. La clave es la
contribución de la capa de experiencia a los reintentos seguros a través de la
frontera de red.

La misma honestidad del Capítulo 10 aplica aquí, una capa más arriba. En un despliegue
Firefly real, `exp-lending` no escribe a mano esta interfaz — inyecta el *SDK de
dominio generado*, un cliente basado en `WebClient` producido a partir del contrato
OpenAPI del servicio de dominio y cableado por un `ClientFactory`. El reactor escribe
a mano un puerto recortado para que el ejemplo compile y sus tests se ejecuten **sin
ningún servicio de dominio en marcha y sin Docker**; un stub en memoria vive bajo
`src/test/java`. El Capítulo 16 es donde el SDK generado y sus valores por defecto
resilientes — reintentos, timeouts, un circuit breaker — toman el relevo, y el
Capítulo 17 vuelve a la capa de experiencia por completo.

!!! warning "El puerto es la costura del SDK, no un cliente escrito a mano que entregas"
    Lee `LoanOriginationDomainClient` como "aquí es donde se conecta el SDK de dominio
    generado", exactamente como leíste `LoanOriginationClient` en el Capítulo 10 para
    el salto dominio→core. La ruta de producción es un cliente `WebClient` generado y
    resiliente; el puerto existe para que esta porción pueda enseñar la *frontera de
    capa* sin levantar el servicio aguas abajo. La frontera es la lección; el stub es
    andamiaje.

!!! note "Término clave — la costura de capa (un puerto reactivo)"
    Una **costura de capa** es la interfaz de la que una capa depende para llamar a la
    siguiente. Es un puerto reactivo — métodos que devuelven `Mono`/`Flux` — de modo
    que el llamante permanece no bloqueante a través del salto de red. La costura es lo
    que hace que la regla de no compartir base de datos sea exigible en código: una
    capa que solo puede ver una interfaz *no puede* meter mano en las tablas de una
    vecina. `LoanOriginationDomainClient` (exp→dominio) y `LoanOriginationClient`
    (dominio→core) son las dos costuras en la porción de Lumen.

## Las cuatro capas, y por qué cada una elige su starter

Ahora la forma completa. Cuatro capas, cuatro starters, cada uno empaquetando las
capacidades y los valores por defecto de producción que su rol necesita y nada que no
necesite. El starter es como un servicio declara qué capa es — una única dependencia,
sin versión porque el BOM del Capítulo 3 la fija.

- **Experiencia (`exp-lending`) — `fireflyframework-starter-application`.** El BFF de
  cara al canal. Composición sin estado: validar, llamar aguas abajo, dar forma a un
  DTO. Su starter trae las preocupaciones de canal — seguridad a nivel de método
  (`@Secure`), caché, la maquinaria de cliente resiliente para llamar a servicios de
  dominio — pero ninguna persistencia, porque no posee datos. Esta es la capa de los
  Listados 14.1 y 14.2.
- **Dominio (`domain-lending-loan-origination`) — `fireflyframework-starter-domain`.**
  Orquestación de negocio. Traduce peticiones de canal de grano grueso en comandos y
  consultas CQRS, ejecuta sagas compensatorias, emite eventos de dominio (Capítulos
  10–13). Su starter trae los buses CQRS, el motor de sagas y el runtime EDA — y, como
  la capa de experiencia, ninguna base de datos, porque llama al core sobre un SDK.
- **Core (`core-lending-loan-origination`) — `fireflyframework-starter-core` más
  R2DBC.** El sistema de registro. Posee el esquema, las filas y las APIs reactivas
  CRUD y de negocio sobre ellas (Capítulos 6–8). Es la *única* capa con un almacén de
  datos, así que su starter se empareja con la pila reactiva R2DBC.
- **Data — `fireflyframework-starter-data`.** Enriquecimiento, calidad de datos y
  linaje — consultas a la central de riesgos, entradas de scoring, las preocupaciones
  de plataforma de datos. Lumen la nombra aquí y la construye en el **Capítulo 15**.

Mira de nuevo los tres POMs del Capítulo 3 y el patrón es exacto: cada módulo declara
precisamente un starter de capa, sin versión, y hereda el resto del padre. El POM de
experiencia declara `fireflyframework-starter-application`; el POM de dominio,
`fireflyframework-starter-domain`; el POM del core, `fireflyframework-starter-core`. El
starter no es una comodidad — es la respuesta legible por máquina a "¿qué tipo de
servicio es este?".

!!! spring "Equivalente en Spring"
    Un starter de capa de Firefly *es* un starter de Spring Boot — un POM que agrega
    dependencias y autoconfiguraciones, el mismo mecanismo detrás de
    `spring-boot-starter-web`. Lo que Firefly añade es la *opinión*: cuatro starters
    alineados con cuatro roles arquitectónicos, cada uno precableando el comportamiento
    transversal (seguridad, CQRS, persistencia, clientes resilientes) que ese rol
    necesita. Añadir `starter-domain` es para un servicio de dominio lo que añadir
    `starter-web` es para una app web — una línea que enciende una porción coherente de
    comportamiento.

## La regla cardinal: integra sobre contratos, nunca sobre una base de datos compartida

Todo lo anterior descansa sobre una restricción, y vale la pena enunciarla como una
ley:

> Las capas integran sobre SDKs generados y HTTP. **Dos capas nunca comparten una
> base de datos.**

Solo la capa core posee un almacén de datos. La capa de dominio lo alcanza a través de
`LoanOriginationClient`; la capa de experiencia alcanza el dominio a través de
`LoanOriginationDomainClient`. Ni el servicio de experiencia ni el de dominio tienen
una dependencia R2DBC, un pool de conexiones o un repositorio — y eso es por diseño, no
por omisión. Si la capa de dominio pudiera consultar las tablas del core directamente,
cada cambio en el esquema del core arriesgaría romper el servicio de dominio en
silencio, y los dos quedarían soldados exactamente del modo en que el manejador CQRS
quedó soldado a sus consumidores en el Capítulo 11.

Integrar sobre un *contrato* rompe esa soldadura. El core puede reformar su
almacenamiento detrás de una superficie OpenAPI estable; el dominio solo ve el SDK
generado; la experiencia solo ve el SDK del dominio. Cada capa puede desplegarse,
escalarse y evolucionar de forma independiente porque lo único que cruza una frontera
es un contrato de red versionado. Esta es la regla del Capítulo 1 — *integra sobre
contratos, no sobre un esquema compartido* — y los dos puertos que diseccionaste son
donde se hace cumplir en código: una capa que solo puede ver una interfaz físicamente
no puede meter mano en las tablas de una vecina.

!!! warning "Una base de datos compartida es el modo de fallo, no un atajo"
    La forma más común en que los equipos destruyen silenciosamente una arquitectura
    en capas es dejar que dos servicios apunten a la misma base de datos "solo para
    esta consulta". En el momento en que lo hacen, las capas quedan acopladas en el
    esquema, los despliegues deben coordinarse y la independencia que la arquitectura
    prometía desaparece. Si una capa necesita datos que no posee, llama al dueño sobre
    el SDK del dueño. No hay excepción que no te cueste la arquitectura.

## Cómo la configuración sigue a las capas

Las capas no solo estructuran el código — estructuran la *configuración*. Recuerda la
jerarquía de configuración del Capítulo 4: el servidor de configuración de Firefly
sirve ajustes en capas, y las capas reflejan las capas arquitectónicas. Los ajustes
**common** aplican a toda la flota — formato de logs, el endpoint de trazado,
convenciones compartidas. Las capas **core**, **domain** y **experience** contienen
cada una ajustes compartidos por cada servicio de *esa capa*. Y el propio
`application.yml` de cada servicio contiene lo que es cierto solo para él.

Así que una capa es también un ámbito de configuración además de un rol de código. Un
ajuste que cada servicio de dominio necesita — un timeout por defecto, una propiedad de
saga — vive en la capa **domain** y cada servicio de dominio lo hereda; un ajuste que
cada servicio core necesita vive en **core**. Un nuevo servicio se une a la jerarquía
simplemente declarando su starter de capa y apuntando al servidor de configuración: el
starter establece la capa, y la capa selecciona qué capas compartidas hereda. Esa es
la convención que el Capítulo 4 nombró, vista ahora desde el lado de la arquitectura —
las mismas cuatro palabras, `common` / `core` / `domain` / `experience`, organizan por
igual el grafo de dependencias y el grafo de propiedades.

!!! spring "Equivalente en Spring"
    La jerarquía se monta sobre Spring Cloud Config, cuya resolución de propiedades en
    capas podrías ensamblar a mano. La contribución de Firefly es la *convención* de
    que las capas son exactamente las capas arquitectónicas — de modo que "¿qué
    configuración compartida hereda este servicio?" tiene la misma respuesta que "¿qué
    starter declara?". Un concepto, la capa, indexa tanto la construcción como la
    configuración.

## Ejecútalo

Los tests de porción de la capa de experiencia demuestran que el BFF arranca, asegura
sus endpoints y compone aguas abajo — todo contra el stub en memoria del puerto de
dominio, sin ningún servicio de dominio en marcha y sin Docker. Desde el directorio
`samples/lumen-lending`:

```text
mvn -q -pl exp-lending test
```

Deberías ver los nueve pasar:

```text
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Esos nueve cubren el arranque del contexto (`ExpLendingApplicationTest`), las rutas de
creación y lectura del controlador sobre `WebTestClient` (`ApplicationControllerTest`)
y la lógica de composición del servicio contra el puerto stub (`ApplicationServiceTest`).
La sustitución es el mismo truco de costura que viste una capa más abajo: el test
provee un `LoanOriginationDomainClient` en memoria, y nada en el controlador o el
servicio cambia. Eso es la frontera de capa ganándose el sueldo — la capa de
experiencia es testeable en aislamiento completo de la capa de dominio que tiene
delante.

!!! tip "Punto de control"
    Ejecuta el comando y confirma `Tests run: 9, Failures: 0`. Luego abre
    `src/test/java` y encuentra `StubLoanOriginationDomainClient` — la implementación
    en memoria del puerto del Listado 14.2. Es lo único que sustituye a todo un
    servicio aguas abajo, que es exactamente por lo que estos nueve tests se ejecutan
    en segundos.

## Lo que has aprendido {.recap}

- Lumen Lending son **cuatro capas**: **experiencia** (BFF de cara al canal, sobre
  `starter-application`), **dominio** (orquestación, sobre `starter-domain`), **core**
  (sistema de registro, sobre `starter-core` más R2DBC) y **data** (enriquecimiento,
  sobre `starter-data`). La porción construye las tres primeras; **data** se nombra
  aquí y se construye en el Capítulo 15.
- Una capa declara su rol mediante el **único starter** que su POM importa — la
  referencia cruzada del Capítulo 3, ahora leída como arquitectura:
  experiencia→`starter-application`, dominio→`starter-domain`, core→`starter-core`.
- La capa de experiencia es una capa de composición fina — ruta base
  `/api/v1/experience/lending/applications`, sin lógica de negocio, sin base de datos —
  que alcanza el dominio a través de `LoanOriginationDomainClient`, un puerto reactivo
  que es la **costura SDK exp→dominio** (el SDK de dominio generado se conecta detrás
  de él en producción).
- La regla cardinal: las capas integran sobre **SDKs generados y HTTP, y nunca
  comparten una base de datos**. Solo el core posee un almacén de datos; los dos
  puertos reactivos son donde la regla se hace cumplir en código.
- La configuración sigue a las capas — la jerarquía **common / core / domain /
  experience** del Capítulo 4 usa las mismas cuatro palabras, de modo que la capa de un
  servicio selecciona tanto las dependencias compartidas como la configuración
  compartida que hereda.

## Pruébalo tú mismo {.exercises}

1. **Traza una petición bajando por la pila.** Empezando en `createApplication` en
   `ApplicationController`, sigue la llamada hacia `ApplicationService` y fuera a través
   de `LoanOriginationDomainClient.submitApplication`. Anota cada frontera que la
   petición cruza y nombra cuál es una llamada a método y cuál es (en producción) un
   salto de red.
2. **Encuentra la base de datos ausente.** Abre `exp-lending/pom.xml` y
   `domain-lending-loan-origination/pom.xml` y confirma que ninguno declara R2DBC o una
   dependencia de repositorio, luego abre `core-lending-loan-origination/pom.xml` y
   encuentra dónde vive el almacén de datos. Explica en una frase por qué solo uno de
   los tres lo tiene.
3. **Lee el starter como el marcador de capa.** Para cada uno de los tres módulos,
   encuentra la única línea `fireflyframework-starter-*` en su POM. Tapa el id de
   artefacto y predice la capa a partir del nombre del directorio; destápalo y
   comprueba. ¿Qué starter declararía un nuevo módulo `data-lending-bureau`?
4. **Compara las dos costuras.** Pon `LoanOriginationDomainClient` (Listado 14.2) junto
   a `LoanOriginationClient` (Listado 10.6). Enumera lo que es igual en ellos (puertos
   reactivos, sustituyen a un SDK generado, testeados con un stub en memoria) y lo que
   difiere (el parámetro `idempotencyKey`, los tipos DTO). ¿Por qué la costura de
   experiencia lleva una clave de idempotencia que la costura de dominio no exponía?
5. **Coloca un ajuste en la jerarquía.** Dado un timeout por defecto aguas abajo que
   *todos* los servicios de dominio deberían compartir, y una ruta base que solo
   `exp-lending` usa, di en qué capa de configuración va cada uno — `common`, una capa
   de capa o el propio `application.yml` del servicio — y justifica cada uno desde el
   objetivo de no duplicación del Capítulo 4.

## Adónde ir ahora

Ahora tienes el mapa completo: cuatro capas, cuatro starters, dos costuras, una regla.
El siguiente capítulo rellena la esquina del mapa que Lumen solo ha nombrado — la capa
**data** sobre `starter-data`, la capa de enriquecimiento y calidad de datos que
alimenta el scoring del que depende la saga de dominio. Después de eso, el Capítulo 16
reemplaza ambos puertos escritos a mano con los SDKs *generados* y sus valores por
defecto resilientes, convirtiendo las costuras que diseccionaste aquí en las llamadas
HTTP reales y resilientes que mantienen unida una flota Firefly.
