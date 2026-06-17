En el capítulo 6 construiste un controlador REST reactivo y lo viste superar una
prueba de extremo a extremo. Ese controlador es más que un endpoint: es un
*contrato*. Cada servicio de préstamos en Lumen publica uno, y la capa superior
nunca accede a su base de datos: llama a ese contrato por HTTP. Este capítulo trata
sobre el contrato: cómo un controlador se convierte en un documento **OpenAPI**
legible por máquina, cómo ese documento se transforma en documentación navegable por
personas y en un **SDK** tipado, y dónde encaja cada pieza.

Hay un hecho sobre el reactor que hace que este capítulo cale más hondo que antes.
Cuando ejecutaste la pila local completa en el capítulo 2 —core en `:8081`, domain
en `:8082`, experience en `:8080`— y enviaste una sola solicitud al BFF, esa
petición viajó de verdad `exp → domain → core` por HTTP y volvió marcada como
`SUBMITTED`. Las capas no comparten una base de datos; se llaman entre sí
exactamente a través del tipo de cliente reactivo tipado que describe este capítulo.
Así que la "costura del SDK" ya no es un diagrama. Es el `WebClient` vivo que movió
tu solicitud de préstamo tres capas hacia dentro y la persistió en el sistema de
registro central. Este capítulo te muestra esa costura, y el contrato sobre el que
viaja.

Aclaremos el alcance antes de empezar, porque este libro vive o muere por su
honestidad. El módulo `core-lending-loan-origination` del reactor que lo acompaña
lleva las *anotaciones* OpenAPI en su controlador, y cuando arranca sirve un
documento OpenAPI real y una Swagger UI: viste la línea `Application SwaggerUI:` en
el banner e hiciste curl a `/v3/api-docs` en el capítulo 2. Lo que el reactor **no**
hace es ejecutar el openapi-generator en tiempo de compilación para emitir un
artefacto de cliente publicado, porque las capas del ejemplo se llaman entre sí a
través de una interfaz de cliente reactivo escrita a mano en lugar de un jar
generado. Esa interfaz es la *costura* en la que encajaría el SDK generado, y el
reactor la cablea como un `WebClient` vivo. Así que este capítulo separa lo que el
reactor contiene realmente —las anotaciones y el cliente vivo— y enseña la
maquinaria de generación con fragmentos ilustrativos claramente marcados. Cuando un
bloque es un boceto en lugar de un fragmento verificado, lo dice.

Este es un capítulo más corto y conceptual. No hay nueva lógica de negocio que
construir —lo que se construye es el controlador que ya escribiste y la costura de
cliente que entrega el reactor— pero hay varios momentos «Ejecútalo» que confirman
el cableado vivo.

## El contrato que un controlador ya lleva consigo

Un controlador WebFlux es, por sí mismo, suficiente para describir una API. El
método HTTP, la ruta, los parámetros de ruta y de consulta, el tipo del cuerpo de la
petición y el tipo de la respuesta están todos ahí, en las anotaciones y en las
firmas de los métodos. Un generador de documentos puede leerlos por reflexión y
emitir una descripción OpenAPI sin que escribas un archivo de especificación
separado. Lo que el código *no puede* inferir es la intención humana: un resumen, una
descripción más larga, una agrupación lógica. Eso es lo que añaden las anotaciones de
Swagger.

Abre el controlador de loan-origination y observa los metadatos que ya lleva consigo.

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/web/LoanApplicationController.java | Listado 7.1 — las anotaciones OpenAPI que entrega el reactor
@RestController
@RequestMapping("/api/v1/loan-applications")
@RequiredArgsConstructor
@Tag(name = "LoanApplication", description = "Create and retrieve loan applications")
public class LoanApplicationController {

    private final LoanApplicationService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a loan application",
            description = "Validates the request, opens and submits a new application.")
    public Mono<LoanApplicationResponse> create(
            @Valid @RequestBody CreateLoanApplicationRequest request) {
        return service.create(request);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a loan application",
            description = "Fetches a single application by its id; 404 if absent.")
    public Mono<LoanApplicationResponse> getById(@PathVariable UUID id) {
        return service.getById(id);
    }

    @GetMapping
    @Operation(summary = "List loan applications",
            description = "Lists applications, optionally filtered by status.")
    public Flux<LoanApplicationResponse> list(
            @RequestParam(required = false) ApplicationStatus status) {
        return service.list(status);
    }
}
:::

Dos anotaciones de `io.swagger.v3.oas.annotations` hacen el trabajo. `@Tag` sobre la
clase agrupa las tres operaciones bajo un único encabezado —"LoanApplication"— de
modo que la documentación renderizada se lee como un recurso coherente en lugar de
una lista plana de rutas. `@Operation` en cada manejador aporta el `summary` (el
título de una línea que se muestra en el índice de la documentación) y la
`description` más larga. El propio comentario de clase del reactor da nombre a esta
intención: refleja el estilo de controlador de firefly-oss, "inyección por
constructor, metadatos OpenAPI con `@Tag`/`@Operation`, tipos de retorno reactivos".

Todo lo demás que el generador necesita lo lee de las firmas que ya escribiste.
`@PostMapping` más `@RequestBody CreateLoanApplicationRequest` le indica el verbo, la
ruta y el esquema de entrada. `Mono<LoanApplicationResponse>` le indica el esquema de
respuesta: el generador desenvuelve el `Mono` y describe los campos del record
`LoanApplicationResponse`. `@RequestParam(required = false) ApplicationStatus status`
se convierte en un parámetro de consulta opcional `status` cuyos valores permitidos
son las constantes del enum. Tú anotas la *intención*; el framework infiere la
*forma*.

!!! note "Término clave — OpenAPI"
    **OpenAPI** (antes Swagger) es una especificación neutral respecto al
    proveedor y legible por máquina de una API HTTP: sus rutas, operaciones,
    parámetros, esquemas de petición y respuesta y respuestas de error, expresados
    como un único documento JSON o YAML. Es el contrato sobre el que se ponen de
    acuerdo dos servicios. Como son datos estructurados, las herramientas pueden
    renderizarlo como documentación, validar peticiones contra él y generar código
    de cliente a partir de él, que es de lo que va todo el resto de este capítulo.

!!! spring "Equivalente en Spring"
    No hay nada específico de Firefly en el Listado 7.1. `@Tag` y `@Operation` son
    las anotaciones estándar de `swagger-core`, y se comportan en un servicio
    Firefly exactamente igual que en cualquier servicio Spring Boot WebFlux. La
    contribución de Firefly está aguas arriba del controlador: su starter web
    cablea un generador sobre estas anotaciones y sirve el resultado por defecto,
    de modo que cada servicio de la flota expone su contrato en la misma ruta sin
    ensamblaje por servicio.

## De las anotaciones a un documento servido

Las anotaciones son inertes hasta que algo las lee. En el ecosistema de Spring ese
algo es **springdoc-openapi**: una biblioteca que, al arrancar, escanea tus
controladores, construye el modelo OpenAPI a partir de las anotaciones y firmas
anteriores y lo sirve en una URL conocida. El starter web de Firefly empaqueta y
preconfigura springdoc, que es la razón por la que el servicio core ya anunciaba una
URL de Swagger UI en su banner sin que añadieras ninguna dependencia.

Viste la prueba en el capítulo 2, pero vale la pena releerla aquí por lo que te dice
sobre el *contrato*. Arranca el servicio core de la forma ejecutable —desde
`samples/lumen-lending/core-lending-loan-origination`, `mvn spring-boot:run` (o
ejecuta el `java -jar target/*.jar` reempaquetado); sirve en `:8081` contra una H2
en memoria con Flyway, sin Docker— y luego pide el documento:

```text
$ curl -s http://localhost:8081/v3/api-docs
{"openapi":"3.1.0","info":{"title":"core-lending-loan-origination API","description":"core-lending-loan-origination API Documentation","license":{"name":"Apache 2.0","url":"https://www.apache.org/licenses/LICENSE-2.0"},"version":"1.0.0"}, ... }
```

Lee ese bloque `info` a la luz de todo lo que sabes sobre el servicio. La versión de
`openapi` es **3.1.0**: el framework usa por defecto la especificación actual, no la
más antigua `3.0.1`. El `title`, `core-lending-loan-origination API`, lo construye el
framework a partir de tu `spring.application.name`, con el sufijo `API`: la misma
propiedad de tres líneas de `application.yml` que afloró en el banner aflora ahora una
segunda vez en el contrato. La `license` queda estampada para toda la flota: Apache
2.0, la misma licencia que anuncia el pie del banner. No hay una segunda fuente de
verdad: el documento se deriva del código que sirve las peticiones y de la única
propiedad que estableciste, así que nunca puede desviarse en silencio del
comportamiento en ejecución.

Más abajo, el objeto `paths` lleva las tres operaciones del Listado 7.1. La
correspondencia es exacta: el nombre de `@Tag` se convierte en los `tags` de cada
operación, el summary y la description de `@Operation` pasan tal cual, y el `201` de
`create` proviene de `@ResponseStatus(HttpStatus.CREATED)`. Aquí está la forma que
springdoc emite para la operación `create` —**ilustrativa** en su orden exacto de
claves, pero cada valor es rastreable hasta el Listado 7.1—:

```json
{
  "paths": {
    "/api/v1/loan-applications": {
      "post": {
        "tags": ["LoanApplication"],
        "summary": "Create a loan application",
        "description": "Validates the request, opens and submits a new application.",
        "responses": {
          "201": {
            "content": {
              "application/json": {
                "schema": { "$ref": "#/components/schemas/LoanApplicationResponse" }
              }
            }
          }
        }
      }
    }
  }
}
```

Lo segundo que sirve el servicio es una cara humana para ese documento.

!!! note "Término clave — Swagger UI y ReDoc"
    **Swagger UI** y **ReDoc** son dos renderizadores que convierten un documento
    OpenAPI en una página web navegable. Swagger UI (la URL que imprimió el banner)
    es interactiva: lista cada operación y te permite rellenar parámetros y
    *probar la petición* en vivo contra el servicio en ejecución. ReDoc renderiza el
    mismo documento como documentación de referencia limpia y de solo lectura. Ambos
    consumen exactamente el mismo JSON de `/v3/api-docs` anterior; son vistas, no
    especificaciones separadas.

Como el starter web de Firefly activa springdoc por convención, un desarrollador que
arranque cualquier capa de Lumen puede abrir la UI y ejercitar la API sin un cliente
REST. En una flota, esa consistencia es el valor: el documento vive en
`/v3/api-docs` y la UI en la URL del banner en cada servicio, ya sea core, domain o
experience.

!!! spring "Equivalente en Spring"
    En Spring Boot a secas añadirías tú mismo el starter de springdoc, quizá
    definirías un bean `OpenAPI` para establecer el título y la versión, y
    aceptarías las URLs por defecto de springdoc. La capa web de Firefly lo pliega
    en la autoconfiguración y deriva el título de `spring.application.name` y la
    licencia del valor por defecto de la flota, de modo que la generación es la base
    de partida, no un ensamblaje por servicio. En ambos casos las anotaciones de tu
    controlador son idénticas; lo único que cambia es quién cablea el generador.

## La costura del SDK: cómo se hablan realmente las capas

Ahora la recompensa, y aquí es donde el reactor ha ido más allá de un diagrama.
Recuerda la regla del capítulo 1: las capas nunca comparten una base de datos; se
integran sobre contratos. Cuando la capa **domain** necesita crear una solicitud de
préstamo, no importa el módulo core ni toca sus tablas: hace un `POST` HTTP a
`/api/v1/loan-applications`. Cuando la capa **experience** necesita enviar una
solicitud, hace `POST` a `/api/v1/applications` de domain. La pregunta es *cómo* una
capa hace esa llamada sin esparcir un `WebClient`, una URL y un par de DTOs
duplicados por todo su código de negocio.

La respuesta es poner la llamada detrás de un **puerto reactivo tipado**: una
interfaz Java cuyos métodos reflejan las operaciones del servicio inferior uno a uno
y devuelven `Mono`/`Flux`. El código de negocio (un manejador de comandos, un paso
de saga, un servicio del BFF) depende de la interfaz; un adaptador detrás de ella es
dueño del HTTP. En producción ese adaptador es el **SDK generado**: openapi-generator
lee el `/v3/api-docs` del servicio inferior, el mismo documento que acabas de
consultar con curl, y emite una clase de cliente por tag y un método por operación.
En el reactor —que mantiene la compilación libre de Docker y autocontenida— el
adaptador es un `WebClient` escrito a mano que sustituye exactamente a ese cliente
generado. La misma costura; la única diferencia es quién escribe el adaptador.

!!! note "Término clave — la costura del SDK"
    La **costura del SDK** es la interfaz reactiva tipada que una capa llama para
    alcanzar la siguiente capa inferior: `LoanOriginationClient` en domain (hacia
    core), `LoanOriginationDomainClient` en la capa experience (hacia domain). El
    código de negocio depende solo de la interfaz y de sus métodos `Mono`/`Flux`; un
    adaptador detrás de ella hace la llamada HTTP. En un servicio Firefly de
    producción el adaptador *es* el SDK generado; en este reactor es un `WebClient`
    que refleja lo que haría el cliente generado. El contrato es el único
    acoplamiento.

Observa el puerto de la capa domain. Su Javadoc expone la relación con claridad: en
el servicio Firefly real esta interfaz está respaldada por el cliente del SDK de core
generado (`LoanApplicationsApi`, un stub basado en `WebClient` generado a partir del
contrato OpenAPI de core); el reactor escribe a mano un puerto recortado "para que el
ejemplo compile y las pruebas se ejecuten sin un servicio core en marcha y sin
Docker".

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/client/LoanOriginationClient.java | Listado 7.2 — la costura del SDK de domain hacia core, un puerto reactivo
public interface LoanOriginationClient {

    /**
     * Creates the loan application in the core system of record and returns its server-assigned id.
     *
     * @param applicantName the primary applicant's display name
     * @param amount        the requested principal, in minor units
     * @return the new loan application id
     */
    Mono<UUID> createLoanApplication(String applicantName, long amount);
:::

Cada método del puerto devuelve un `Mono` porque toda la pila —manejadores de
comandos, pasos de saga, la llamada HTTP a core— es no bloqueante de extremo a
extremo. Los métodos se corresponden uno a uno con los pasos de la saga que los
necesitan: `createLoanApplication` es el paso raíz `registerLoanApplication`, y (más
abajo en la interfaz) `removeLoanApplication` es su compensación, mientras que
`addApplicant` y `proposeOffer` son los pasos dependientes. Esa es la forma del SDK
generado: un método tipado por operación, tipos de retorno reactivos, ninguna URL a
la vista.

Ahora el adaptador vivo. Cuando estableces una ruta base de core, el reactor cablea
una implementación respaldada por `WebClient` de ese puerto. Aquí está su método raíz
—el que tu envío del capítulo 2 ejecutó realmente cuando corrió la saga—:

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/client/WebClientLoanOriginationClient.java | Listado 7.3 — el WebClient vivo al que sustituye la costura del SDK
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

Este es el cuerpo del SDK generado, escrito a mano. `LOAN_APPLICATIONS_PATH` es
`/api/v1/loan-applications` —la ruta exacta del Listado 7.1— y los records
`CoreCreateRequest`/`CoreLoanApplicationResponse` son la vista local de domain de los
DTOs `CreateLoanApplicationRequest` y `LoanApplicationResponse` de core. El método
hace `POST`, desenvuelve el `Mono<CoreLoanApplicationResponse>` y lo mapea hasta el
único campo que la saga necesita: el `loanApplicationId` asignado por core. Cuando
hiciste `POST` al BFF en el capítulo 2 y viste la solicitud aterrizar en core, *este
método hizo la escritura*. El `removeLoanApplication` compensador (un `DELETE` sobre
el mismo recurso) y el `findById` del lado de lectura (un `GET`) están justo debajo
en la misma clase, completando la costura viva.

!!! note "Honest scope — la costura de escritura recortada"
    Sé preciso sobre qué cruza el cable. El `createLoanApplication` de domain toma
    solo un nombre de solicitante y un importe, así que el adaptador rellena el resto
    de la petición de core con valores por defecto: `DEFAULT_CURRENCY` (`EUR`),
    `DEFAULT_PURPOSE` (`GENERAL`) y `DEFAULT_TERM_MONTHS` (`12`). Por eso, en la
    verificación entre capas del capítulo 2, la solicitud que llegó a core mostraba
    `"currency":"EUR"`, `"purpose":"GENERAL"` y `"termMonths":12` con independencia de
    lo que llevara la petición del canal. La correspondencia es intencionadamente
    mínima: la correspondencia de campos rica y sin pérdidas es exactamente el
    trabajo que un SDK *generado* (cada campo del contrato, tipado) hace para el
    servicio de producción. La costura es real y viva; el payload que transporta aquí
    es deliberadamente pequeño.

El mismo patrón se repite una capa más arriba. El puerto del BFF de experience,
`LoanOriginationDomainClient`, está respaldado en producción por el SDK de *domain*
generado; en el reactor su adaptador hace `POST` con `WebClient` a
`/api/v1/applications` de domain y —fíjate en el detalle— reenvía una cabecera
`Idempotency-Key` determinista para que una petición de canal reintentada se
deduplique aguas abajo sin que el cliente acuñe un nuevo id de recurso. Dos costuras,
dos adaptadores, un estilo reactivo uniforme, todo el camino desde el canal hasta el
sistema de registro.

!!! spring "Equivalente en Spring"
    Nada de esto es exclusivo de Firefly: openapi-generator y su biblioteca WebClient
    son herramientas estándar que cualquier equipo de Spring Boot puede adoptar. Lo
    que añade Firefly es la *convención*: cada servicio expone su contrato en
    `/v3/api-docs`, y la capa superior lo consume a través de un puerto reactivo
    tipado cuyo adaptador es generado (producción) o escrito a mano para
    corresponderse (este reactor). El código de negocio del consumidor nunca cambia
    de forma entre ambos.

## Generar el SDK — el patrón detrás de la costura

Los adaptadores del reactor están escritos a mano para mantener la compilación
autocontenida, pero la forma que imitan es mecánica. El mismo documento de
`/v3/api-docs` que alimenta a Swagger UI también alimenta a **openapi-generator**,
que emite el cliente tipado por ti. El plugin de abajo es **ilustrativo** —no se
ejecuta en este módulo— pero en un servicio core Firefly de producción vive en el
`pom.xml` y produce el SDK del que depende la capa domain:

```xml
<!-- Illustrative: generate a reactive WebClient SDK from the served contract. -->
<plugin>
  <groupId>org.openapitools</groupId>
  <artifactId>openapi-generator-maven-plugin</artifactId>
  <executions>
    <execution>
      <goals><goal>generate</goal></goals>
      <configuration>
        <inputSpec>${project.basedir}/target/api-docs.json</inputSpec>
        <generatorName>java</generatorName>
        <library>webclient</library>
        <configOptions>
          <reactive>true</reactive>
          <useJakartaEe>true</useJakartaEe>
        </configOptions>
      </configuration>
    </execution>
  </executions>
</plugin>
```

La biblioteca `webclient` con `<reactive>true</reactive>` es la parte que mantiene al
cliente honesto sobre la pila reactiva: cada método generado devuelve un `Mono` o un
`Flux`, nunca un valor bloqueante, de modo que una llamada se compone en el pipeline
del llamante sin aparcar un hilo, precisamente la forma de `LoanOriginationClient` en
el Listado 7.2. El método que se corresponde con `create` en el Listado 7.1
devolvería `Mono<LoanApplicationResponse>`, el mismo tipo de retorno que declara el
controlador, ahora en el lado del llamante del cable.

Compara la llamada generada (o equivalente a la generada) con la alternativa que un
equipo escribe cuando se salta el SDK por completo. La forma generada:

```java
// The SDK seam: a typed method, no URL, no DTO duplicated by hand.
return loanApplicationsApi.create(request)            // -> POST /api/v1/loan-applications
        .map(LoanApplicationResponse::loanApplicationId);
```

La forma escrita a mano desde cero —fíjate en que esto es *casi* lo que es el Listado
7.3, que es justo el punto—:

```java
// The hand-rolled alternative: a URL, a builder, and DTOs duplicated per consumer.
webClient.post()
        .uri("http://core-lending-loan-origination/api/v1/loan-applications")
        .bodyValue(request)
        .retrieve()
        .bodyToMono(LoanApplicationResponse.class);   // and you maintain this DTO by hand
```

Ambas hacen la misma llamada HTTP. El SDK generado convierte el contrato en la única
fuente de verdad y traslada cualquier cambio del contrato a *tiempo de compilación* en
cada consumidor; la versión escrita a mano hace una copia de los DTOs de
petición/respuesta en cada llamante, mantenidos sincronizados a base de esperanza. El
reactor aterriza en un punto medio deliberado: escribe el adaptador a mano (para
mantenerse libre de Docker) pero lo aísla detrás del puerto tipado, de modo que el
código de negocio ya disfruta del desacoplamiento del SDK y el cliente generado
podría encajar sin tocar un solo paso de saga. El capítulo 16 construye la capa de
cliente resiliente —timeouts, reintentos, cortacircuitos— *alrededor* de SDKs como
este.

!!! spring "Equivalente en Spring"
    Regenerar un SDK cuando cambia un contrato es el análogo reactivo de recompilar
    contra una interfaz actualizada. En Spring a secas podrías apoyarte en clientes
    declarativos `@HttpExchange` o escribir a mano un `WebClient` por dependencia; la
    convención del SDK generado le da a una flota un estilo de cliente único y
    uniforme derivado del contrato en lugar de un cajón de sastre por equipo; y el
    puerto tipado delante de él significa que cambiar generado por escrito a mano es
    invisible para los llamantes.

## Cómo se cablea la costura viva — condicional, nunca en medio

Una cosa más hace que la costura del reactor sea fiel a producción en lugar de un
juguete: se activa *por configuración* y se aparta para las pruebas. El cliente core
vivo se registra mediante una `@Configuration` corriente protegida por dos
condiciones.

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/config/LiveLoanOriginationClientConfig.java | Listado 7.4 — la costura del SDK viva se activa solo cuando se establece una ruta base de core
    @Bean
    @ConditionalOnProperty(prefix = "firefly.lumen.core.loan-origination", name = "base-path")
    @ConditionalOnMissingBean(LoanOriginationClient.class)
    public WebClientLoanOriginationClient webClientLoanOriginationClient(WebClient coreLoanOriginationWebClient) {
        log.info("Wiring live WebClient LoanOriginationClient against core; the saga writes to core over HTTP");
        return new WebClientLoanOriginationClient(coreLoanOriginationWebClient);
    }
:::

`@ConditionalOnProperty(... base-path)` significa que el cliente `WebClient` vivo solo
se materializa cuando un operador apunta la capa domain a un servicio core real. El
`src/main/resources/application.yml` que se entrega establece
`firefly.lumen.core.loan-origination.base-path` en `http://localhost:8081`, de modo
que la app domain independiente llama al core en ejecución, que es exactamente por lo
que tu envío entre capas del capítulo 2 funcionó de extremo a extremo.
`@ConditionalOnMissingBean` significa que las pruebas de slice, que registran su
propio stub de `LoanOriginationClient` en memoria, siempre ganan: el cliente vivo
nunca desplaza a la costura de prueba, así que las 33 pruebas del reactor (core 18,
domain 6, exp 9) siguen en verde sin red y sin Docker. El `LoanOriginationClientConfig`
de la capa experience sigue el patrón idéntico con la propiedad
`lumen.exp.loan-origination.base-path`. Configura una ruta base, y la costura cobra
vida; déjala sin establecer (como hacen las pruebas), y un stub dentro de la JVM toma
el relevo.

!!! note "Término clave — cableado condicional del cliente"
    Un bean de **cliente condicional** es uno que Spring solo crea cuando se cumplen
    sus guardas: aquí, `@ConditionalOnProperty` (hay una ruta base configurada) y
    `@ConditionalOnMissingBean` (ningún stub de prueba ya posee el puerto). Este es
    el mismo idioma de autoconfiguración que usan los starters, aplicado a la costura
    del SDK: el *mismo* código de negocio se ejecuta contra un cliente HTTP vivo en
    producción y contra un doble en memoria en las pruebas, elegido enteramente por
    lo que hay en el classpath y en la configuración, nunca por un cambio de código.

## Ejecútalo

No hay nueva lógica de negocio en este capítulo —el contrato vive en el controlador
que ya construiste, y la costura es el cliente que entrega el reactor—. Así que prueba
las dos mitades de las que trata este capítulo.

Primero, prueba que el controlador anotado sigue comportándose. Desde
`samples/lumen-lending`:

```text
$ mvn -q -pl core-lending-loan-origination test \
    -Dtest=LoanApplicationControllerTest
```

Deberías ver pasar las tres pruebas de la capa web:

```text
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in com.firefly.lumen.core.web.LoanApplicationControllerTest
```

Segundo, prueba que el documento es real. Con el servicio core en marcha en `:8081`
(`mvn spring-boot:run` desde su módulo, o el `java -jar` reempaquetado), lee el
contrato directamente del servicio vivo:

```text
$ curl -s http://localhost:8081/v3/api-docs
{"openapi":"3.1.0","info":{"title":"core-lending-loan-origination API","description":"core-lending-loan-origination API Documentation","license":{"name":"Apache 2.0","url":"https://www.apache.org/licenses/LICENSE-2.0"},"version":"1.0.0"}, ... }
```

!!! tip "Punto de control"
    Tres cosas confirman este capítulo. (1) Abre `LoanApplicationController.java` y
    encuentra el `@Tag` sobre la clase y el `@Operation` en cada uno de los tres
    manejadores: esas cuatro anotaciones son la mitad entera del contrato escrita por
    humanos. (2) Confirma que `/v3/api-docs` informa `"openapi":"3.1.0"` y
    `"title":"core-lending-loan-origination API"`: la máquina leyó el resto de tus
    firmas y de tu única propiedad de `application.yml`. (3) Abre
    `WebClientLoanOriginationClient.java` y confirma que su `createLoanApplication`
    hace `POST` a `/api/v1/loan-applications`: esa es la costura del SDK viva por la
    que viajó realmente tu envío del capítulo 2.

## Lo que has aprendido {.recap}

- Un controlador WebFlux *es* un contrato de API. El framework infiere las rutas,
  los parámetros y los esquemas de petición/respuesta a partir de las anotaciones y
  firmas que ya escribiste; tú solo añades la intención humana con `@Tag` y
  `@Operation` (Listado 7.1).
- El starter web de Firefly cablea **springdoc-openapi** por defecto, de modo que el
  servicio core en ejecución sirve un documento OpenAPI real en `/v3/api-docs`
  —`"openapi":"3.1.0"`, título `core-lending-loan-origination API` derivado de
  `spring.application.name`, licencia Apache 2.0— y una Swagger UI en la URL del
  banner. Sin segunda fuente de verdad.
- Las capas se integran sobre ese contrato a través de una **costura del SDK**
  reactiva tipada: una interfaz Java (`LoanOriginationClient`,
  `LoanOriginationDomainClient`) cuyos métodos devuelven `Mono`/`Flux`. El código de
  negocio depende de la interfaz; un adaptador detrás de ella es dueño del HTTP.
- En un servicio Firefly de producción el adaptador *es* un SDK generado
  (openapi-generator, `webclient`, `reactive=true`). En este reactor el adaptador es
  un `WebClient` vivo (Listado 7.3) que sustituye al cliente generado: la misma
  costura, el mismo desacoplamiento. El envío `exp → domain → core` del capítulo 2
  corrió a través de estos adaptadores de verdad; la escritura domain→core está
  intencionadamente recortada (algunos campos de core toman por defecto
  `EUR`/`GENERAL`/`12`).
- La costura viva está **cableada condicionalmente** (Listado 7.4): se activa solo
  cuando hay una ruta base configurada y se aparta para las pruebas, de modo que las
  33 pruebas del reactor siguen en verde sin red y sin Docker.

## Pruébalo tú mismo {.exercises}

1. **Añade la intención de una tercera operación.** El manejador `list` en
   `LoanApplicationController.java` tiene un `summary` de `@Operation` pero ninguna
   documentación de su parámetro de consulta `status`. Añade una anotación
   `@Parameter` al argumento `status` describiendo el filtro, vuelve a ejecutar
   `mvn -q -pl core-lending-loan-origination test` y arranca el servicio para
   confirmar que la descripción del parámetro aparece ahora en `/v3/api-docs`.
2. **Lee el documento real.** Arranca el servicio core y haz curl a `/v3/api-docs`.
   Encuentra la entrada `paths` para `GET /api/v1/loan-applications/{id}` y
   contrástala con el Listado 7.1: el tag `LoanApplication`, el summary, el parámetro
   de ruta `id` y la respuesta `200` que hace `$ref` a `LoanApplicationResponse`.
3. **Describe un esquema de respuesta.** Abre `LoanApplicationResponse.java` y lista
   cuáles de sus once campos marca el generador como obligatorios frente a
   opcionales. ¿Qué hay en el record que le indica que `decisionReason` puede ser
   `null` (lo viste como `null` en la respuesta del capítulo 2) mientras que
   `loanApplicationId` siempre está presente?
4. **Traza la costura viva.** Abre `WebClientLoanOriginationClient.java` y sigue
   `createLoanApplication`: ¿a qué ruta hace `POST`, qué campos rellena con valores
   por defecto y qué único campo extrae con `map` de la respuesta de core? Luego haz
   coincidir esos valores por defecto con los valores de `currency`/`purpose`/`termMonths`
   que mostraba la solicitud cuando verificaste que aterrizó en core en el capítulo 2.
5. **Invierte la condición.** En el `application.yml` de domain, la propiedad
   `firefly.lumen.core.loan-origination.base-path` es lo que hace que el cliente vivo
   del Listado 7.4 se materialice. Predice qué hace la capa domain si la dejas sin
   establecer (pista: `@ConditionalOnMissingBean` y el valor por defecto dentro de la
   JVM en `LoanOriginationClientConfig`), y qué líneas de saga `[orchestration]`
   seguirían apareciendo.

## Adónde ir ahora

Ahora tienes el contrato que une las capas de Lumen: un documento derivado de los
controladores, servido en `/v3/api-docs`, renderizado como Swagger UI y consumido a
través de una costura del SDK reactiva tipada que, en este reactor, es un `WebClient`
vivo. El capítulo 8 se vuelve hacia el otro lado de un servicio core: la capa de
persistencia R2DBC detrás de ese controlador, donde `LoanApplicationResponse` se
ensambla a partir de filas reales de H2. La capa de cliente resiliente que envuelve
costuras del SDK como estas —timeouts, reintentos, cortacircuitos— llega en el
capítulo 16.
