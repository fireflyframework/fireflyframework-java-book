Cada capa de Lumen Lending se apoya en la capa que tiene debajo. La capa de experiencia compone
una respuesta de canal llamando al servicio de dominio; el servicio de dominio escribe a través
del núcleo; el núcleo lee el enriquecimiento desde la capa de datos. Esas llamadas cruzan la red,
y la red es donde una flota se gana su reputación. Un servicio descendente está lento,
o inestable, o brevemente ausente — y, salvo que el *llamador* esté construido para ello, una
dependencia enferma arrastra consigo a sus llamadores, hasta que un único timeout cae en cascada
en una interrupción de tres capas de ancho.

Este capítulo trata de la mitad del contrato que corresponde al llamador: cómo una capa de Firefly
alcanza la capa que tiene debajo de forma *resiliente* y *configurable*. Lumen ya funciona **de
extremo a extremo** — un único `POST` de canal al BFF fluye `exp → domain → core` y aterriza una
fila en el sistema de registro del núcleo — así que este capítulo recorta **dos** costuras vivas,
una por salto. Primero el cliente de dominio de la capa de experiencia: la costura del SDK desde el
BFF hasta `domain-lending-loan-origination`. Luego la costura **domain-to-core** recién activada: un
`WebClient` que la saga de la capa de orquestación usa para escribir en el servicio de núcleo sobre
HTTP, y para *eliminar* sobre HTTP cuando tiene que compensar. Ambas costuras usan el patrón idéntico
que Firefly usa en todas partes donde un servicio llama a otro: un **puerto** del que depende el
llamador, un record `@ConfigurationProperties` que suministra la URL base, una `@Configuration` al
estilo `ClientFactory` que construye el bean de producción *solo* cuando un operador lo apunta a un
servicio real, y un adaptador `WebClient` que hace el HTTP. Luego miraremos más allá de REST por
completo — al `ServiceClient` unificado y resiliente que habla la misma gramática fluida sobre SOAP,
gRPC, GraphQL y WebSocket, porque una plataforma de core bancario real nunca puede permitirse fingir
que todo es JSON sobre HTTP.

Todo lo que recortas está vivo en el reactor, y las suites de pruebas demuestran que cada capa
arranca y funciona **sin Docker** y, por defecto, **sin servicio descendente vivo** — porque los
clientes de producción son condicionales, y un stub de prueba gana por defecto. Las nueve pruebas
de la capa de experiencia corren contra un stub de dominio en memoria; las seis de la capa de
dominio corren contra un stub de núcleo en memoria; los clientes HTTP vivos solo se materializan
cuando un operador configura una ruta base.

## La costura, replanteada: un puerto que el llamador posee

Conociste la idea de la costura del SDK en el Capítulo 10: la capa de dominio depende de una
*interfaz* `LoanOriginationClient`, no de un cliente HTTP concreto, de modo que una prueba puede
suministrar una implementación en memoria mientras producción suministra el SDK generado. La capa de
experiencia usa exactamente el mismo movimiento una capa más arriba. Su puerto es
`LoanOriginationDomainClient` — la costura desde el BFF hasta el servicio de originación de dominio.

::: listing exp-lending/src/main/java/com/firefly/lumen/exp/client/LoanOriginationDomainClient.java | Listado 16.1 — el puerto experiencia-a-dominio, una costura reactiva
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

Dos métodos, ambos reactivos, ambos tomando una `idempotencyKey` junto al payload.
El puerto dice *qué* necesita la capa de experiencia del servicio de dominio y nada
sobre *cómo*. Ese "cómo" — la URL base, los timeouts, los verbos HTTP, la política de
reintentos — es la parte configurable e intercambiable, y el resto del capítulo lo rellena
sin que el puerto cambie jamás.

La clave de idempotencia no es decoración. La capa de experiencia la deriva
*deterministamente* de entradas de negocio estables, de modo que una petición de canal
reintentada — el cliente toca dos veces "Solicitar", la red móvil parpadea, una pasarela
reproduce — produce la *misma* clave, y la capa de dominio deduplica en lugar de abrir una
segunda solicitud. La clave viaja hacia abajo como la cabecera estándar `Idempotency-Key`, que
verás que el adaptador establece.

!!! note "Término clave — la costura del SDK"
    Una **costura** es un puerto (una interfaz) en un límite de capa del que depende el llamador
    en lugar de un cliente concreto. En producción la respalda el *SDK generado* —
    un cliente basado en `WebClient` producido a partir del contrato OpenAPI del servicio descendente.
    En pruebas la respalda un stub en memoria. Como el llamador programa contra la
    interfaz, el mismo manejador, servicio y controlador corren sin cambios contra un servicio real
    o contra un stub. El ejemplo del libro escribe a mano un puerto recortado para que compile y
    se pruebe sin ningún servicio descendente en ejecución; léelo como "aquí es donde se enchufa el
    SDK generado".

## URLs base dirigidas por configuración con @ConfigurationProperties

Un BFF que codifica de forma fija `http://domain-service:8082` en su cliente es un BFF que no
puedes promover de dev a staging a prod sin recompilar. La respuesta de Firefly es la
misma que te da Spring Boot: vincula la dirección desde la configuración a un record tipado e
inmutable. Aquí está el de la capa de experiencia.

::: listing exp-lending/src/main/java/com/firefly/lumen/exp/config/LoanOriginationClientProperties.java | Listado 16.2 — la URL base y el timeout, vinculados desde la configuración
@ConfigurationProperties(prefix = "lumen.exp.loan-origination")
public record LoanOriginationClientProperties(
        String basePath,
        Duration timeout
) {

    public LoanOriginationClientProperties {
        if (timeout == null) {
            timeout = Duration.ofSeconds(10);
        }
    }
}
:::

Todo lo que está bajo `lumen.exp.loan-origination` en `application.yml` (o una variable de
entorno, o un servidor de configuración) se vincula a este record. `basePath` es la URL del
servicio de dominio; `timeout` es el presupuesto de lectura/conexión, con valor por defecto de diez
segundos vía el constructor compacto cuando nada lo establece. Como es un `record`, los valores
vinculados son inmutables durante toda la vida de la aplicación — ningún setter muta accidentalmente
la URL base en pleno vuelo.

El prefijo es la idea central. Promover el servicio entre entornos es un
cambio de propiedad. El `application.yml` de `exp-lending` en `src/main/resources` que se envía con el
reactor apunta el BFF a la capa de dominio que corre localmente — que es exactamente cómo un único
`POST` de canal alcanza el servicio de dominio cuando ejecutas la pila completa:

```yaml
# exp-lending — runnable profile
lumen:
  exp:
    loan-origination:
      base-path: http://localhost:8082
# prod (same code, different config)
# lumen.exp.loan-origination.base-path: https://loan-origination.internal.lumen.bank
```

!!! note "Término clave — vinculación relajada"
    La **vinculación relajada** (relaxed binding) de Spring Boot mapea un componente de record llamado
    `basePath` a la clave de propiedad `base-path` (y `BASE_PATH` como variable de entorno, y
    `base_path`, …). Por eso los archivos de propiedades de arriba escriben `base-path` mientras el
    record escribe `basePath`, y por eso el `@ConditionalOnProperty` del siguiente listado
    nombra `base-path`. Son la misma propiedad; Spring normaliza la grafía.

!!! spring "Equivalente en Spring"
    `@ConfigurationProperties` sobre un `record`, activado con
    `@EnableConfigurationProperties`, es Spring Boot de serie — sin ninguna anotación de Firefly
    a la vista. La contribución de Firefly es la *convención*: cada cliente entre capas de la
    flota vincula su dirección de esta forma, bajo un prefijo predecible con forma
    `*.loan-origination`, de modo que un operador configura el décimo servicio exactamente como el primero.

## El patrón ClientFactory: construir el bean de producción, condicionalmente

Ahora el cableado que convierte el puerto y las propiedades en un cliente vivo. Firefly
llama a esto el patrón **ClientFactory**: una `@Configuration` que construye un `WebClient`
a partir de las propiedades vinculadas y expone el puerto como un bean — pero solo bajo dos
condiciones, de modo que nunca se interpone en el camino de una prueba o de un entorno mal configurado.

::: listing exp-lending/src/main/java/com/firefly/lumen/exp/config/LoanOriginationClientConfig.java | Listado 16.3 — el ClientFactory: una @Configuration condicional que construye el bean de producción
@Configuration
@EnableConfigurationProperties(LoanOriginationClientProperties.class)
public class LoanOriginationClientConfig {

    private static final Logger log = LoggerFactory.getLogger(LoanOriginationClientConfig.class);
    private static final int MAX_IN_MEMORY_SIZE = 20 * 1024 * 1024;

    /**
     * Builds the {@link WebClient} the domain client adapter uses. Mirrors the codec sizing of the
     * real {@code LoanOriginationClientFactory}.
     */
    @Bean
    @ConditionalOnProperty(prefix = "lumen.exp.loan-origination", name = "base-path")
    @ConditionalOnMissingBean
    public WebClient loanOriginationWebClient(LoanOriginationClientProperties properties) {
        log.info("Building Loan Origination WebClient basePath={} timeout={}",
                properties.basePath(), properties.timeout());
        return WebClient.builder()
                .baseUrl(properties.basePath())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_SIZE))
                .build();
    }

    /**
     * Production {@link LoanOriginationDomainClient}. In the real service this wraps the generated
     * domain SDK {@code LoanOriginationApi}; here it is a {@link WebClient}-backed adapter so the
     * wiring is faithful and chapters can slice it verbatim. Only created when a base path is set
     * and no other client bean (e.g. a test stub) is present.
     */
    @Bean
    @ConditionalOnProperty(prefix = "lumen.exp.loan-origination", name = "base-path")
    @ConditionalOnMissingBean
    public LoanOriginationDomainClient loanOriginationDomainClient(WebClient loanOriginationWebClient) {
        return new WebClientLoanOriginationDomainClient(loanOriginationWebClient);
    }
}
:::

Lee las dos condiciones en cada `@Bean`, porque juntas hacen que esta
configuración sea cortés.

`@ConditionalOnProperty(... name = "base-path")` significa que el bean solo se materializa cuando
un operador ha apuntado realmente la capa de experiencia a un servicio de dominio real. Sin ruta
base, no hay cliente — la configuración permanece inerte en lugar de construir un `WebClient`
apuntando a nada. Eso es lo que permite que las pruebas del ejemplo corran sin ningún
`lumen.exp.loan-origination.base-path` establecido: los beans de producción simplemente nunca aparecen.

`@ConditionalOnMissingBean` significa que, incluso *con* una ruta base, este bean se retira
en el instante en que algún otro `LoanOriginationDomainClient` ya está definido — exactamente la
regla de "retírate ante tu propio bean" del Capítulo 1, aplicada a un cliente. Una prueba registra un
stub en memoria, y el bean de producción le cede el paso sin un perfil ni un flag.

El propio `WebClient` se construye a partir del `basePath` vinculado, con su códec dimensionado para
el payload más grande que la capa espera (aquí, un generoso techo de 20 MB, reflejando la
fábrica del servicio real). En producción, el segundo bean envolvería el SDK de dominio *generado*;
el ejemplo envuelve un adaptador escrito a mano para que el cableado sea lo bastante fiel
como para recortarlo literalmente.

!!! note "Término clave — el patrón ClientFactory"
    Un **ClientFactory** es una `@Configuration` que ensambla un cliente descendente a partir de
    `@ConfigurationProperties` vinculadas y lo expone tras un puerto, controlado por
    `@ConditionalOnProperty` (solo cuando está configurado) y `@ConditionalOnMissingBean`
    (solo cuando no se sobrescribe). Es la forma estándar de Firefly para "cablear un cliente
    resiliente a la capa de abajo", de modo que el cliente de salida de cada capa se configure,
    sea condicional y sea sobrescribible de la misma manera.

!!! spring "Equivalente en Spring"
    Cada anotación aquí — `@Configuration`, `@Bean`, `@EnableConfigurationProperties`,
    `@ConditionalOnProperty`, `@ConditionalOnMissingBean` — es Spring Boot puro, los
    mismos condicionales que usan las propias autoconfiguraciones de Firefly. Nada está oculto. El
    patrón es una convención, no un mecanismo nuevo: podrías escribirlo a mano en cualquier
    aplicación Spring, y el valor de Firefly es que cada servicio lo escribe de forma idéntica.

## El adaptador WebClient: el HTTP, y la cabecera de idempotencia

La fábrica entrega el puerto a un adaptador respaldado por `WebClient`. Esta es la única
clase que sabe que el servicio de dominio habla HTTP — rutas, verbos, cabeceras. Mantenerla
tras el puerto significa que el servicio, el controlador y las pruebas de la capa de experiencia nunca
importan `WebClient` en absoluto.

::: listing exp-lending/src/main/java/com/firefly/lumen/exp/config/WebClientLoanOriginationDomainClient.java | Listado 16.4 — el adaptador WebClient: HTTP tras el puerto, clave de idempotencia en cada llamada
class WebClientLoanOriginationDomainClient implements LoanOriginationDomainClient {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final String APPLICATIONS_PATH = "/api/v1/applications";

    private final WebClient webClient;

    WebClientLoanOriginationDomainClient(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public Mono<ApplicationDetailDTO> submitApplication(CreateApplicationRequest request, String idempotencyKey) {
        return webClient.post()
                .uri(APPLICATIONS_PATH)
                .header(IDEMPOTENCY_HEADER, idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(ApplicationDetailDTO.class);
    }

    @Override
    public Mono<ApplicationDetailDTO> getApplication(UUID applicationId, String idempotencyKey) {
        return webClient.get()
                .uri(APPLICATIONS_PATH + "/{id}", applicationId)
                .header(IDEMPOTENCY_HEADER, idempotencyKey)
                .retrieve()
                .bodyToMono(ApplicationDetailDTO.class);
    }
}
:::

El adaptador es una llamada reactiva de `WebClient` de manual: `post()` la petición de creación a
`/api/v1/applications`, `get()` el detalle por id, cada una devolviendo un `Mono` del
DTO de canal. Y `/api/v1/applications` no es una cadena arbitraria — es precisamente
la ruta que sirve la capa de dominio, que es cómo este salto realmente conecta cuando la
pila completa está en marcha. El único detalle que merece detenerse es la cabecera: ambos métodos
establecen `Idempotency-Key` a partir del argumento `idempotencyKey` que el servicio calculó — de modo
que la garantía de deduplicación viaja con cada llamada, escritura *y* lectura. La capa de experiencia
deriva esa clave deterministamente aguas arriba (el Capítulo 6 introdujo el filtro de idempotencia que
*honra* dicha clave en el lado receptor); aquí ves el lado *emisor* ponerla en el cable.

!!! note "Término clave — propagación de X-Transaction-Id"
    Distinto de la clave de idempotencia es el **id de transacción**. El
    `TransactionFilter` del Capítulo 6 sella cada respuesta con un `X-Transaction-Id`, acuñando uno
    si el llamador no lo suministró. Para que ese id realmente correlacione una petición entre
    capas, un cliente de salida debe *reenviar* el id entrante en su llamada descendente.
    El cliente resiliente de Firefly hace esto automáticamente: el `X-Transaction-Id` que lleva
    el intercambio entrante se propaga a la petición de salida, de modo que una operación lógica
    comparte un id de transacción desde el BFF, a través de la capa de dominio, hasta el
    núcleo — y los logs JSON encajan de extremo a extremo. Los adaptadores escritos a mano de los
    recortes establecen la cabecera de idempotencia explícitamente; trata la propagación del id de
    transacción como una propiedad del cliente resiliente del SDK generado, no como algo que estos
    adaptadores recortados cablean a mano.

## El segundo salto: la costura domain-to-core ahora viva

El adaptador de experiencia es el salto *superior*. Sigue la petición más allá y alcanza la
`LoanOriginationController` de la capa de dominio, que ejecuta la `RegisterApplicationSaga`,
cuyo paso raíz debe escribir en el sistema de registro del **núcleo**. Ese salto inferior solía
estar solo dentro de la JVM; ahora está genuinamente vivo sobre HTTP, y está construido con las mismas
cuatro piezas — puerto, propiedades, ClientFactory, adaptador — una capa más abajo. El puerto es
el mismo `LoanOriginationClient` que conociste en el Capítulo 10, pero ahora hay una implementación
real respaldada por `WebClient` tras él.

Empieza con el record de propiedades, que vincula la dirección del servicio de núcleo bajo un
prefijo `firefly.*` (el espacio de nombres que usa el servicio de dominio real, distinto del
prefijo `lumen.exp.*` del BFF):

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/config/CoreLoanOriginationProperties.java | Listado 16.5 — la URL base y el timeout del núcleo, vinculados desde la configuración
@ConfigurationProperties(prefix = "firefly.lumen.core.loan-origination")
public record CoreLoanOriginationProperties(
        String basePath,
        Duration timeout
) {

    public CoreLoanOriginationProperties {
        if (timeout == null) {
            timeout = Duration.ofSeconds(10);
        }
    }
}
:::

Forma idéntica a la de `LoanOriginationClientProperties` de la capa de experiencia — un
`basePath`, un `timeout` con valor por defecto de diez segundos por el constructor compacto — prueba
de que la convención es la misma con independencia de qué dos capas conecta una costura. El
`application.yml` ejecutable del dominio establece `firefly.lumen.core.loan-origination.base-path`
en `http://localhost:8081`, que es lo que hace que el paso raíz de la saga realmente alcance
el servicio de núcleo en ejecución.

Ahora el ClientFactory. Este es el equivalente domain-to-core del Listado 16.3, con
una responsabilidad extra: registra el *mismo* cliente vivo para dos costuras a la vez —
el `LoanOriginationClient` del lado de escritura que la saga conduce, y un
`CoreLoanApplicationReader` del lado de lectura que usa el endpoint GET-por-id del dominio.

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/config/LiveLoanOriginationClientConfig.java | Listado 16.6 — el ClientFactory de núcleo vivo: WebClient + cliente condicionales, registrados para ambas costuras
@Configuration
@EnableConfigurationProperties(CoreLoanOriginationProperties.class)
public class LiveLoanOriginationClientConfig {

    private static final Logger log = LoggerFactory.getLogger(LiveLoanOriginationClientConfig.class);
    private static final int MAX_IN_MEMORY_SIZE = 20 * 1024 * 1024;

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

    /**
     * The live core client, registered for both the write seam ({@link LoanOriginationClient}) and
     * the read seam ({@link CoreLoanApplicationReader}). Only created when a base path is set and no
     * other {@link LoanOriginationClient} (e.g. a test stub) is present.
     */
    @Bean
    @ConditionalOnProperty(prefix = "firefly.lumen.core.loan-origination", name = "base-path")
    @ConditionalOnMissingBean(LoanOriginationClient.class)
    public WebClientLoanOriginationClient webClientLoanOriginationClient(WebClient coreLoanOriginationWebClient) {
        log.info("Wiring live WebClient LoanOriginationClient against core; the saga writes to core over HTTP");
        return new WebClientLoanOriginationClient(coreLoanOriginationWebClient);
    }
}
:::

Las dos condiciones se leen exactamente como las de la capa de experiencia, con el prefijo cambiado
a `firefly.lumen.core.loan-origination` — y compran las mismas dos propiedades.
`@ConditionalOnProperty(name = "base-path")` mantiene al cliente vivo fuera del contexto
hasta que un operador apunta la capa de dominio a un servicio de núcleo real; las seis pruebas
de recorte del dominio no establecen tal ruta base, así que el `WebClient` de producción nunca se
construye y el `StubLoanOriginationClient` en memoria de la prueba es el único `LoanOriginationClient`
en la sala. `@ConditionalOnMissingBean(LoanOriginationClient.class)` significa que, incluso
cuando *hay* una ruta base establecida, el bean cede ante cualquier `LoanOriginationClient` ya
presente — de modo que el stub de prueba sigue ganando.

Hay un tercer actor que merece nombrarse, porque es lo que permite a la capa de dominio arrancar
*de forma autónoma* sin ninguna ruta base de núcleo. Una `@AutoConfiguration` separada,
`LoanOriginationClientConfig`, contribuye un `LoanOriginationClient` **dentro de la JVM** sin
operación, protegido solo por `@ConditionalOnMissingBean`. Como es una autoconfiguración, se
evalúa *después* de la configuración de usuario y de la configuración de prueba, así que es la de
menor prioridad de las tres: un stub de prueba gana primero, el cliente `WebClient` vivo gana cuando
hay una ruta base establecida, y solo si no hay ninguno de los dos el valor por defecto dentro de la
JVM rellena la costura. Esa ordenación a tres bandas es por lo que la capa de dominio sirve esté el
núcleo accesible o no.

!!! note "Término clave — precedencia de condiciones de bean"
    Spring evalúa las clases `@Configuration` que tú (o una prueba) registráis *antes* de
    las clases registradas como `@AutoConfiguration`. Coloca `@ConditionalOnMissingBean` por
    encima y obtienes una escalera de precedencia limpia para una costura: un **stub de prueba**
    (`@TestConfiguration`) supera a un **cliente vivo** (una `@Configuration` de usuario controlada por
    una ruta base), que supera a un **valor por defecto dentro de la JVM** (un respaldo
    `@AutoConfiguration`). Cada capa superior simplemente hace que la de debajo se retire — sin
    perfiles, sin flags.

El adaptador es la única clase que sabe que el núcleo habla HTTP. Hace POST para crear,
DELETE para compensar, y GET para leer — y es honesto sobre cuánto de la
petición de canal cruza realmente esta costura de escritura recortada.

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/client/WebClientLoanOriginationClient.java | Listado 16.7 — el adaptador de núcleo vivo: POST para crear, DELETE para compensar, GET para leer
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

    @Override
    public Mono<Void> removeLoanApplication(UUID loanApplicationId) {
        log.debug("Core compensation delete loan-application id={}", loanApplicationId);
        return webClient.delete()
                .uri(LOAN_APPLICATIONS_PATH + "/{id}", loanApplicationId)
                .retrieve()
                .bodyToMono(Void.class);
    }
:::

El método `createLoanApplication` hace POST a `/api/v1/loan-applications` — la ruta exacta
que el servicio de núcleo sirve en el Capítulo 2 — y mapea la respuesta hacia abajo a solo el
`loanApplicationId` asignado por el núcleo, que es el id que la saga propaga de vuelta hacia arriba a través
del controlador de dominio y la capa de experiencia hasta el canal. `removeLoanApplication`
es la *compensación*: cuando un paso dependiente de la saga falla, la saga deshace su paso raíz
haciendo DELETE de la solicitud que creó. Ese `DELETE` es la razón por la que el reactor creció un
pequeño segundo controlador en el lado del núcleo — mantenido separado para que el controlador primario que
recortaste en el Capítulo 2 permanezca byte-idéntico:

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/web/LoanApplicationDeleteController.java | Listado 16.8 — el DELETE idempotente del núcleo, el objetivo de la compensación de la saga
@RestController
@RequestMapping("/api/v1/loan-applications")
@RequiredArgsConstructor
@Tag(name = "LoanApplication", description = "Create and retrieve loan applications")
public class LoanApplicationDeleteController {

    private final LoanApplicationRepository repository;

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a loan application",
            description = "Removes an application by its id; idempotent, so a missing id is a no-op.")
    public Mono<Void> delete(@PathVariable UUID id) {
        return repository.deleteById(id);
    }
}
:::

El delete es **idempotente** — `deleteById` sobre un id ausente es una operación nula — y responde
`204 No Content` en cualquier caso. Eso importa para la resiliencia: una compensación que se
reintenta, o que compite con una limpieza parcial, debe converger al mismo estado final sin
errar. La idempotencia no es solo una preocupación entrante; es lo que hace que una llamada
*de compensación* sea segura de repetir.

!!! warning "El mapeo de escritura domain-to-core es intencionadamente mínimo"
    Sé honesto sobre lo que cruza esta costura. La ruta de escritura recortada lleva solo el
    nombre del solicitante y el importe en unidades menores; el adaptador rellena el resto del
    payload de creación del núcleo con **valores por defecto** — `currency` `EUR`, `purpose` `GENERAL`,
    `termMonths` `12`. Por eso, cuando verificas una solicitud enviada por el BFF directamente
    en el núcleo, ves esos valores por defecto en lugar de la divisa, el propósito o el plazo de la
    petición de canal. Un mapeo de campos más rico es el trabajo del SDK de núcleo *generado* en
    el servicio real; el ejemplo mapea lo justo para que la escritura viva y su
    compensación fluyan sobre HTTP. Los dos pasos dependientes de la saga (`addApplicant`,
    `proposeOffer`) no tienen endpoint en el controlador de núcleo recortado, así que se completan
    en proceso con un id sintetizado — suficiente para que la saga termine mientras la escritura real
    viaja por la red.

!!! spring "Equivalente en Spring"
    Nada en los Listados 16.5–16.8 es una anotación de Firefly: `@ConfigurationProperties`,
    `@Configuration`, `@Bean`, `@ConditionalOnProperty`, `@ConditionalOnMissingBean`,
    `@RestController`, `@DeleteMapping` son todas de Spring de serie. Los
    tipos `org.fireflyframework.*` aparecen solo donde el framework aporta valor — el
    motor de saga y el modelo de error web. El patrón de costura es convención apoyada en
    Spring puro, y se lee igual una capa más abajo que una capa más arriba.

## Resiliencia, aplicada a través de operadores de Reactor

Una llamada `WebClient` es solo la mitad de un cliente resiliente. La otra mitad es lo que ocurre
cuando el servicio descendente está lento, errando o saturado. El cliente de producción de Firefly
— el `ServiceClient` resiliente sobre el que se construye el SDK generado — envuelve cada llamada en
una pila de **Resilience4j**, aplicada no como `try`/`catch` imperativo sino como *operadores
de Reactor* sobre el mismo `Mono` que el adaptador devuelve. Como son operadores, se
componen en la cadena reactiva sin bloquear un hilo.

Tres patrones hacen el trabajo pesado:

- **Cortacircuitos.** Después de que una proporción configurada de llamadas recientes falle, el cortacircuitos
  se *abre* y falla rápido — cada llamada devuelve inmediatamente con un error en lugar de
  esperar a una dependencia que todos saben ya que está enferma. Tras un enfriamiento deja pasar
  un goteo de llamadas (*medio abierto*); si tienen éxito se *cierra* de nuevo. Esto es
  lo que detiene que una capa enferma caiga en cascada hacia las capas que tiene encima — justo aquello a lo que la
  ruta de tres saltos `exp → domain → core` está expuesta.
- **Reintento.** Un fallo transitorio — una conexión caída, un `503` durante un despliegue
  gradual — se reintenta un número acotado de veces, idealmente con backoff, de modo que un parpadeo no
  aflore al cliente. El reintento es *solo* seguro porque cada escritura lleva una
  garantía de idempotencia: la clave de idempotencia determinista de la capa de experiencia del
  Listado 16.4, y el DELETE idempotente del núcleo del Listado 16.8 — un `submitApplication`
  reintentado deduplica aguas abajo, una compensación reintentada converge en lugar de
  errar.
- **Mamparo (bulkhead).** Un tope sobre las llamadas concurrentes en vuelo hacia la dependencia, de modo que un
  servicio descendente lento no pueda consumir todas las conexiones y dejar sin recursos al resto del servicio. El
  mamparo aísla el radio de impacto a la única dependencia que está sufriendo.

Conceptualmente, el cliente resiliente decora el `Mono` del adaptador con esos
operadores — esto es ilustrativo, no un recorte del reactor:

```java
// Illustrative — how the resilient client layers Resilience4j onto the reactive call.
return webClient.post()
        .uri(LOAN_APPLICATIONS_PATH)
        .bodyValue(body)
        .retrieve()
        .bodyToMono(CoreLoanApplicationResponse.class)
        .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
        .transformDeferred(BulkheadOperator.of(bulkhead))
        .transformDeferred(RetryOperator.of(retry))
        .timeout(properties.timeout());
```

Cada `transformDeferred` envuelve la llamada en un decorador de Resilience4j; `timeout`
impone el presupuesto del `CoreLoanOriginationProperties` vinculado (el mismo record
cuyo valor por defecto de diez segundos leíste en el Listado 16.5). Ajustas cada umbral — tasa de
fallo, duración de espera, máximo de intentos, tope de concurrencia — a través de propiedades, nunca de código,
de modo que el SRE que opera la flota ajusta el cortacircuitos de una dependencia inestable sin un
redespliegue.

!!! warning "El reintento solo es seguro con idempotencia"
    Reintentar una escritura no idempotente es cómo creas dos préstamos a partir de un toque. La
    razón por la que el cliente resiliente de Firefly puede reintentar un `POST` siquiera es que la capa de
    experiencia deriva una clave de idempotencia *determinista* y el adaptador la envía en cada
    llamada. Reintento e idempotencia son una pareja — activa uno sin el otro y tendrás
    o bien fragilidad o bien duplicados. Nunca habilites el reintento en una escritura que no lleve
    una clave de idempotencia estable.

!!! spring "Equivalente en Spring"
    Resilience4j incluye operadores de Reactor de primera clase —
    `CircuitBreakerOperator`, `RetryOperator`, `BulkheadOperator` — que puedes aplicar
    a cualquier `Mono` o `Flux` en una aplicación Spring corriente. Firefly no los reemplaza; los
    *precablea* en el cliente generado con valores por defecto estándar de la flota y
    umbrales ajustables vía `firefly.*`, de modo que cada llamada de salida de la flota esté
    cortacircuitada, reintentada y con mamparo de la misma manera en lugar de que cada equipo escriba a mano un
    `WebClient` ligeramente distinto.

## Más allá de REST: un cliente fluido, muchos protocolos

Hasta ahora cada llamada ha sido JSON sobre HTTP, porque las capas que Lumen posee lo hablan
todas. Una plataforma de core bancario real no tiene ese lujo. El sistema de registro de
las cuentas es un servicio **SOAP** de veinte años. El motor de fraude expone **gRPC**.
El catálogo de productos está tras una pasarela **GraphQL**. Un feed de datos de mercado es un
flujo **WebSocket**. Integrar cada uno con su propio cliente a medida — un stub JAX-WS
aquí, un canal gRPC allá, un cliente Apollo en otro sitio — es el impuesto empresarial
del Capítulo 1, disfrazado de protocolo.

La respuesta de Firefly es un único `ServiceClient` unificado cuya *gramática fluida es la
misma* con independencia del protocolo de cable que tenga debajo. Seleccionas el protocolo cuando
construyes el cliente; el punto de llamada se lee igual, y — crucialmente — el mismo
cortacircuitos, reintento, mamparo, timeout y propagación de `X-Transaction-Id` de Resilience4j
aplican sin importar qué protocolo transporte los bytes. La resiliencia es una propiedad
del *cliente*, no de HTTP.

Imagina la llamada SOAP de core bancario heredado que la capa de dominio debe hacer para verificar una
cuenta. En lugar de generar stubs JAX-WS y atornillar la resiliencia a mano, echas mano de
la misma gramática de builder:

```java
// Illustrative — the same fluent ServiceClient, pointed at a legacy SOAP core-banking service.
ServiceClient soap = ServiceClient.soap("core-banking-accounts")
        .baseUrl("https://core-banking.internal.lumen.bank/ws")
        .wsdl("classpath:wsdl/accounts.wsdl")
        .timeout(Duration.ofSeconds(8))
        .circuitBreaker(cb -> cb.failureRateThreshold(50))
        .retry(r -> r.maxAttempts(3))
        .build();

Mono<AccountStatus> status = soap.operation("VerifyAccount")
        .body(new VerifyAccountRequest(iban))
        .execute(AccountStatus.class);
```

Cambia `ServiceClient.soap(...)` por `ServiceClient.grpc(...)`, `ServiceClient.graphql(...)`,
o `ServiceClient.websocket(...)` y la *forma* no cambia — configuración base,
los mismos mandos de resiliencia, un `execute` que devuelve un `Mono` (o un `Flux` para un
protocolo de streaming). La llamada gRPC al motor de fraude, la consulta GraphQL al catálogo, y la
suscripción WebSocket de datos de mercado se leen todas como variaciones de un cliente, porque
lo son:

```java
// Illustrative — gRPC and a WebSocket stream through the same grammar and resilience stack.
Mono<FraudVerdict> verdict = ServiceClient.grpc("fraud-engine")
        .target("fraud.internal.lumen.bank:9090")
        .circuitBreaker(cb -> cb.failureRateThreshold(40))
        .build()
        .method("ScoreApplication")
        .body(applicationId)
        .execute(FraudVerdict.class);

Flux<PriceTick> ticks = ServiceClient.websocket("market-data")
        .url("wss://market.internal.lumen.bank/stream")
        .build()
        .subscribe("rates/EURUSD", PriceTick.class);
```

La ganancia es la misma que argumenta todo el libro: un desarrollador que aprendió el cliente para
la llamada REST de dominio ya conoce el cliente para el núcleo SOAP, el motor de fraude gRPC,
y el catálogo GraphQL. Una gramática, un modelo de resiliencia, un único lugar para ajustar
umbrales — a través de cada protocolo que la plataforma se ve forzada a hablar.

!!! note "Término clave — el ServiceClient unificado"
    El **`ServiceClient`** de Firefly es un cliente de salida agnóstico del protocolo: un único
    builder y gramática de llamada fluidos que apuntan a REST, SOAP, gRPC, GraphQL o
    WebSocket, elegido en tiempo de construcción. Cada variante comparte la misma pila de resiliencia
    (cortacircuitos, reintento, mamparo, timeout) y la misma propagación de contexto
    (`X-Transaction-Id`), de modo que el comportamiento transversal es idéntico sin importar el formato
    de cable. Es la generalización de las dos costuras REST que recortaste en este capítulo
    a cada protocolo con el que se integra una plataforma bancaria.

!!! warning "Los clientes multiprotocolo aquí son ilustrativos"
    Las capas de Lumen Lending se integran sobre REST, así que los recortes *verificados* de este capítulo
    son las dos costuras REST — el cliente experiencia-a-dominio y el cliente
    domain-to-core vivo — con sus propiedades, ClientFactories y adaptadores `WebClient`.
    Los fragmentos SOAP, gRPC, GraphQL y WebSocket de arriba son ilustrativos —
    muestran la forma del `ServiceClient` unificado y dónde se enchufa, no código
    que la build de este capítulo compile. Léelos como "así es como el mismo patrón se extiende
    más allá de REST", y echa mano de la referencia del `ServiceClient` del framework cuando cablees
    una dependencia real no HTTP.

## Ejecútalo: el flujo de tres capas vivo

Dos maneras de ver funcionar estos clientes: la pila viva, y las pruebas herméticas. Toma la
pila viva primero, porque ese es el premio — los beans `WebClient` de producción que
recortaste construyéndose y llamando de verdad.

Arranca las tres capas desde `samples/lumen-lending`; el orden no importa, porque ninguna
capa falla rápido ante un servicio descendente ausente:

```text
( cd core-lending-loan-origination   && mvn spring-boot:run ) &
( cd domain-lending-loan-origination && mvn spring-boot:run ) &
( cd exp-lending                     && mvn spring-boot:run ) &
```

Al arrancar, la capa de dominio registra en el log el ClientFactory del Listado 16.6 construyendo su
`WebClient` de núcleo — porque su `application.yml` establece la ruta base:

```text
{"timestamp":"2026-06-17T11:46:18.204+0000","message":"Building core Loan Origination WebClient basePath=http://localhost:8081 timeout=PT10S","logger":"c.f.l.d.config.LiveLoanOriginationClientConfig","level":"INFO"}
{"timestamp":"2026-06-17T11:46:18.231+0000","message":"Wiring live WebClient LoanOriginationClient against core; the saga writes to core over HTTP","logger":"c.f.l.d.config.LiveLoanOriginationClientConfig","level":"INFO"}
```

Ahora haz POST de una única petición de canal al BFF en el `8080`. Fluye `exp → domain → core`:
el cliente de experiencia (Listado 16.4) hace POST al dominio en
`http://localhost:8082/api/v1/applications`; la `LoanOriginationController` del dominio
ejecuta la `RegisterApplicationSaga`; el paso raíz de la saga llama al cliente de núcleo vivo
(Listado 16.7), que hace POST a `http://localhost:8081/api/v1/loan-applications`; y
el id asignado por el núcleo se devuelve todo el camino de vuelta:

```text
$ curl -s -X POST localhost:8080/api/v1/experience/lending/applications \
    -H 'Content-Type: application/json' \
    -d '{"productId":"11111111-1111-1111-1111-111111111111","requestedAmount":25000.00,"term":36,"purpose":"HOME_IMPROVEMENT","simulationId":"22222222-2222-2222-2222-222222222222"}'
```

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

En la capa de dominio puedes observar a la saga conducir la escritura — el paso raíz llama al núcleo
sobre HTTP, luego los dos pasos dependientes se completan en proceso, y la orquestación
termina con éxito:

```text
[orchestration] started   name=RegisterApplicationSaga ... pattern=SAGA
[orchestration] step.success ... stepId=registerLoanApplication latencyMs=94
[orchestration] step.success ... stepId=proposeOffer
[orchestration] step.success ... stepId=registerApplicant
[orchestration] completed name=RegisterApplicationSaga ... pattern=SAGA success=true
```

La prueba de que realmente aterrizó en el núcleo es leerla directamente del sistema de registro por
el id que devolvió el BFF — fíjate en los campos del núcleo con **valores por defecto** (`currency` `EUR`,
`termMonths` `12`, `purpose` `GENERAL`) que la costura de escritura recortada suministra, exactamente como
predijo el aviso de arriba:

```text
$ curl -s localhost:8081/api/v1/loan-applications/786544c7-2f10-4110-95fe-682d63edbace
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

## Ejecútalo: las pruebas herméticas

La pila viva demuestra que los clientes funcionan *con* sus servicios descendentes. Las suites de pruebas demuestran
algo más sutil e igual de importante: cada capa arranca y funciona *sin* su
servicio descendente, porque el cliente de producción es condicional y un stub de prueba gana. Eso es
precisamente lo que te compran los pares `@ConditionalOnProperty`/`@ConditionalOnMissingBean`.

Las pruebas de la capa de experiencia nunca establecen `lumen.exp.loan-origination.base-path`, así que el
`WebClient` y el adaptador de producción del Listado 16.3 nunca se materializan; en su lugar se registra un
`StubLoanOriginationDomainClient`, y `@ConditionalOnMissingBean`
garantiza que gana. Desde `samples/lumen-lending`:

```text
$ mvn -q -pl exp-lending test
```

```text
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

La misma historia se repite una capa más abajo. Las pruebas de la capa de dominio registran su propio
`StubLoanOriginationClient` y no establecen ningún `firefly.lumen.core.loan-origination.base-path`,
así que ni el cliente `WebClient` vivo del Listado 16.6 ni el valor por defecto dentro de la JVM jamás
desplazan al stub:

```text
$ mvn -q -pl domain-lending-loan-origination test
```

```text
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Y el reactor completo — núcleo, dominio y experiencia juntos — demuestra todo el
contrato `exp → domain → core` de extremo a extremo:

```text
$ mvn -q clean verify
```

```text
Tests run: 33, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Treinta y tres pruebas en verde (núcleo 18, dominio 6, exp 9) confirman las afirmaciones del capítulo sobre
ambas costuras: cada capa compone su mitad del viaje de ida y vuelta a través de un puerto, la
capa de experiencia deriva una clave de idempotencia determinista que el stub puede observar, la
capa de dominio ejecuta la saga contra un stub de núcleo en memoria, y una solicitud ausente
aflora como un problem detail — todo sin que ningún `WebClient` de producción se construya jamás,
porque no hay ninguna ruta base configurada en los perfiles de prueba.

!!! tip "Punto de control"
    Fíjate en *por qué* las suites están en verde sin ejecutar servicios descendentes: los beans de producción en
    ambos ClientFactories están controlados por `@ConditionalOnProperty(name = "base-path")` y
    `@ConditionalOnMissingBean`. Sin ningún `base-path` establecido y con un stub de prueba presente, ambas
    condiciones mantienen al cliente vivo fuera del contexto. Establece
    `firefly.lumen.core.loan-origination.base-path` en el perfil de prueba del dominio y el
    bean `WebClient` de núcleo intentaría construirse (vigila la línea de log `Building core Loan
    Origination WebClient`) — prueba de que la condición, no la suerte, es lo que mantiene
    las pruebas herméticas.

## Lo que has construido {.recap}

- Un **puerto** reactivo en *cada* límite de capa — `LoanOriginationDomainClient` desde el
  BFF hasta el dominio, y `LoanOriginationClient` desde el dominio hasta el núcleo — del que el
  llamador depende, sin ningún detalle de HTTP filtrándose más allá de la interfaz, cada uno devolviendo
  `Mono`.
- Dos records `@ConfigurationProperties`, `LoanOriginationClientProperties`
  (prefijo `lumen.exp.loan-origination`) y `CoreLoanOriginationProperties`
  (prefijo `firefly.lumen.core.loan-origination`), que vinculan la **URL base y el timeout**
  descendentes desde la configuración, de modo que el mismo código promueve entre entornos con un
  cambio de propiedad.
- Dos clases `@Configuration` **ClientFactory** que construyen el `WebClient` de producción
  y el bean cliente solo cuando se satisface `@ConditionalOnProperty(base-path)` y
  `@ConditionalOnMissingBean` confirma que nada lo sobrescribe — la regla de "retírate ante tu propio bean"
  aplicada a clientes de salida, más una precedencia a tres bandas (stub de prueba → cliente vivo
  → valor por defecto dentro de la JVM) en la costura de dominio.
- Dos **adaptadores** `WebClient`: el adaptador de experiencia que establece la cabecera `Idempotency-Key`
  en cada llamada, y el adaptador **domain-to-core** vivo que hace POST para crear,
  DELETE para compensar (contra el endpoint de delete idempotente del núcleo), y GET para
  leer — honestamente mínimo en lo que mapea. Más la imagen conceptual del
  cortacircuitos, reintento y mamparo de **Resilience4j** aplicados a través de operadores de
  Reactor, y la propagación de `X-Transaction-Id` en cada salto.
- El **`ServiceClient` unificado** — la misma gramática fluida y resiliente sobre SOAP, gRPC,
  GraphQL y WebSocket — y una línea honesta entre lo que verifican estos recortes REST y
  lo que es ilustrativo.
- Un reactor de **33 pruebas** en verde (`Tests run: 33, Failures: 0`; exp 9, dominio 6, núcleo 18)
  que ejecuta cada capa contra stubs en memoria con los clientes de producción condicionados
  fuera — más un flujo vivo de tres capas `exp → domain → core` que puedes ejecutar a mano, sin Docker
  en ninguno de los caminos.

## Pruébalo tú mismo {.exercises}

1. **Haz existir el cliente de producción mediante configuración.** En una prueba de `exp-lending`, añade una
   fuente de propiedades que establezca `lumen.exp.loan-origination.base-path` y *elimina* el
   registro del stub. Observa que `LoanOriginationClientConfig` ahora construye el
   bean `WebClient` (vigila la línea de log `Building Loan Origination WebClient`).
   Explica, en una frase, qué condición se invirtió.
2. **Sobrescribe el cliente con tu propio bean.** Deja un `base-path` configurado, luego
   define un segundo `@Bean` `LoanOriginationDomainClient` en una configuración de prueba.
   Confirma que el bean de producción se retira y se inyecta el tuyo — luego nombra la única
   anotación que lo hizo posible.
3. **Recorre la escritura viva de dos saltos.** Con las tres capas en marcha, haz POST al BFF y luego
   `GET localhost:8081/api/v1/loan-applications/{id}` para el id devuelto. Identifica
   qué campos vinieron de tu petición de canal y cuáles son los valores por defecto de la costura de
   escritura recortada (`currency`, `termMonths`, `purpose`), y explica por qué — refiriéndote a las
   constantes `DEFAULT_*` en `WebClientLoanOriginationClient`.
4. **Razona sobre la compensación.** Abre `LoanApplicationDeleteController` y el método
   `removeLoanApplication` en `WebClientLoanOriginationClient`. Explica por qué el
   `DELETE` debe ser idempotente para que una compensación de saga sea segura de reintentar, y qué
   devuelve un `DELETE` de un id ya eliminado.
5. **Traza la escalera de precedencia.** Tres cosas pueden satisfacer la costura
   `LoanOriginationClient` del dominio: el `StubLoanOriginationClient` de prueba, el
   `WebClientLoanOriginationClient` vivo, y el valor por defecto dentro de la JVM en `LoanOriginationClientConfig`.
   Sin ejecutar nada, ordénalos por precedencia y nombra los dos mecanismos
   (la ordenación de `@AutoConfiguration` y `@ConditionalOnMissingBean`) que producen ese orden.
6. **Esboza una costura SOAP.** Sin ejecutarlo, escribe el builder `ServiceClient.soap(...)`
   que usarías para la llamada de verificación de cuenta del core bancario heredado, incluyendo un
   cortacircuitos y un reintento. Nombra qué dos comportamientos transversales obtienes *gratis*
   del cliente unificado que de otro modo cablearías a mano en un stub JAX-WS.

## Adónde ir ahora

Ahora tienes la mitad del llamador de cada límite de capa, por partida doble: un puerto,
direccionamiento dirigido por configuración, un ClientFactory condicional, y un adaptador resiliente tanto en
el salto `exp → domain` como en el salto `domain → core` ahora vivo, más un cliente unificado que
alcanza más allá de REST. Los siguientes capítulos ponen estas llamadas bajo carga y bajo vigilancia — la
observabilidad que hace que el `X-Transaction-Id` que propagaste aquí sea realmente trazable
a través de la flota, y las pruebas que ejercitan una petición completa desde la capa de experiencia
hacia abajo a través de la saga de dominio hasta el sistema de registro del núcleo.
