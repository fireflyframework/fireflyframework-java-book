Cada capa de Lumen Lending se apoya en la capa que tiene debajo. La capa de
experiencia compone una respuesta de canal llamando al servicio de dominio; el
servicio de dominio escribe a través del core; el core lee enriquecimiento de la capa
de datos. Esas llamadas cruzan la red, y la red es donde una flota se juega su
reputación. Un servicio aguas abajo va lento, o tiene intermitencias, o desaparece
brevemente — y, a menos que quien *llama* esté preparado para ello, una dependencia
enferma arrastra consigo a quienes la invocan, hasta que un solo timeout se propaga en
cascada hacia una caída que abarca tres capas.

Este capítulo trata sobre la mitad del contrato que corresponde a quien llama: cómo
una capa de Firefly alcanza a la capa de debajo de forma *resiliente* y
*configurable*. Vas a diseccionar el cliente de dominio de la capa de experiencia — la
costura del SDK que va desde el BFF hasta `domain-lending-loan-origination` — y verás
el patrón que Firefly emplea siempre que un servicio llama a otro: un **puerto** del
que depende quien llama, un record `@ConfigurationProperties` que aporta la URL base,
una `@Configuration` al estilo `ClientFactory` que construye el bean de producción solo
cuando un operador lo apunta a un servicio real, y un adaptador `WebClient` que hace el
HTTP. Después miraremos más allá del REST por completo — al `ServiceClient` unificado y
resiliente que habla la misma gramática fluida sobre SOAP, gRPC, GraphQL y WebSocket,
porque una plataforma de core bancario de verdad nunca puede permitirse fingir que todo
es JSON sobre HTTP.

Todo lo que diseccionas vive en el módulo `exp-lending`, y una batería de nueve tests
demuestra que la capa arranca y funciona **sin servicio de dominio y sin Docker** —
porque el cliente de producción es condicional, y un stub de test gana por defecto.

## La costura, replanteada: un puerto del que es dueño quien llama

Conociste la idea de la costura del SDK en el Capítulo 10: la capa de dominio depende
de una *interfaz* `LoanOriginationClient`, no de un cliente HTTP concreto, de modo que
un test puede aportar una implementación en memoria mientras que producción aporta el
SDK generado. La capa de experiencia usa exactamente el mismo movimiento una capa más
arriba. Su puerto es `LoanOriginationDomainClient` — la costura desde el BFF hasta el
servicio de originación del dominio.

::: listing exp-lending/src/main/java/com/firefly/lumen/exp/client/LoanOriginationDomainClient.java | Listado 16.1 — el puerto de experiencia a dominio, una costura reactiva
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

Dos métodos, ambos reactivos, ambos recibiendo una `idempotencyKey` junto al payload.
El puerto dice *qué* necesita la capa de experiencia del servicio de dominio y nada
sobre el *cómo*. Ese "cómo" — la URL base, los timeouts, los verbos HTTP, la política
de reintentos — es la parte configurable e intercambiable, y el resto del capítulo lo
rellena sin que el puerto cambie nunca.

La clave de idempotencia no es decoración. La capa de experiencia la deriva
*deterministicamente* a partir de entradas de negocio estables, de modo que una
petición de canal reintentada — el cliente pulsa dos veces "Solicitar", la red móvil
parpadea, un gateway repite — produce la *misma* clave, y la capa de dominio
deduplica en lugar de abrir una segunda solicitud. La clave viaja hacia abajo como la
cabecera estándar `Idempotency-Key`, que verás como el adaptador la fija.

!!! note "Término clave — la costura del SDK"
    Una **costura** es un puerto (una interfaz) en un límite de capa del que depende
    quien llama en lugar de depender de un cliente concreto. En producción está
    respaldada por el *SDK generado* — un cliente basado en `WebClient` producido a
    partir del contrato OpenAPI del servicio aguas abajo. En tests está respaldada por
    un stub en memoria. Como quien llama programa contra la interfaz, el mismo manejador,
    servicio y controlador se ejecutan sin cambios contra un servicio real o contra un
    stub. El ejemplo del libro escribe a mano un puerto recortado para que compile y se
    pruebe sin ningún servicio aguas abajo en ejecución; léelo como "aquí es donde se
    enchufa el SDK generado".

## URLs base dirigidas por configuración con @ConfigurationProperties

Un BFF que codifica a fuego `http://domain-service:8082` en su cliente es un BFF que no
puedes promover de dev a staging a prod sin recompilar. La respuesta de Firefly es la
misma que te da Spring Boot: enlazar la dirección desde la configuración a un record
tipado e inmutable. Aquí está el de la capa de experiencia.

::: listing exp-lending/src/main/java/com/firefly/lumen/exp/config/LoanOriginationClientProperties.java | Listado 16.2 — la URL base y el timeout, enlazados desde configuracion
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

Todo lo que está bajo `lumen.exp.loan-origination` en `application.yml` (o una variable
de entorno, o un servidor de configuración) se enlaza a este record. `basePath` es la
URL del servicio de dominio; `timeout` es el presupuesto de lectura/conexión, con un
valor por defecto de diez segundos mediante el constructor compacto cuando nada lo
establece. Al ser un `record`, los valores enlazados son inmutables durante toda la
vida de la aplicación — ningún setter muta accidentalmente la URL base en pleno vuelo.

El prefijo es la clave de todo. Promover el servicio entre entornos es un cambio de
propiedad:

```yaml
# dev
lumen:
  exp:
    loan-origination:
      base-path: http://localhost:8082
      timeout: 10s
# prod (same code, different config)
# lumen.exp.loan-origination.base-path: https://loan-origination.internal.lumen.bank
```

!!! note "Término clave — enlazado relajado"
    El **enlazado relajado** de Spring Boot mapea un componente de record llamado
    `basePath` a la clave de propiedad `base-path` (y `BASE_PATH` como variable de
    entorno, y `base_path`, …). Por eso los ficheros de propiedades de arriba escriben
    `base-path` mientras que el record escribe `basePath`, y por eso el
    `@ConditionalOnProperty` del siguiente listado nombra `base-path`. Son la misma
    propiedad; Spring normaliza la forma de escribirla.

!!! spring "Spring parity"
    `@ConfigurationProperties` sobre un `record`, activado con
    `@EnableConfigurationProperties`, es Spring Boot de serie — sin ninguna anotación de
    Firefly a la vista. La aportación de Firefly es la *convención*: cada cliente entre
    capas de la flota enlaza su dirección de esta forma, bajo un prefijo predecible con
    forma `*.loan-origination`, de modo que un operador configura el décimo servicio
    exactamente igual que el primero.

## El patrón ClientFactory: construir el bean de producción, condicionalmente

Ahora el cableado que convierte el puerto y las propiedades en un cliente vivo. Firefly
llama a esto el patrón **ClientFactory**: una `@Configuration` que construye un
`WebClient` a partir de las propiedades enlazadas y expone el puerto como un bean —
pero solo bajo dos condiciones, para que nunca se interponga en un test o en un entorno
mal configurado.

::: listing exp-lending/src/main/java/com/firefly/lumen/exp/config/LoanOriginationClientConfig.java | Listado 16.3 — el ClientFactory: una @Configuration condicional que construye el bean de produccion
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

Lee las dos condiciones sobre cada `@Bean`, porque juntas son las que hacen que esta
configuración sea cortés.

`@ConditionalOnProperty(... name = "base-path")` significa que el bean solo se
materializa cuando un operador ha apuntado de verdad la capa de experiencia hacia un
servicio de dominio real. Sin URL base, no hay cliente — la configuración permanece
inerte en lugar de construir un `WebClient` apuntando a nada. Eso es lo que permite que
los tests del ejemplo se ejecuten sin ningún `lumen.exp.loan-origination.base-path`
establecido: los beans de producción simplemente no aparecen nunca.

`@ConditionalOnMissingBean` significa que, incluso *con* una URL base, este bean se
retira en el instante en que ya hay definido algún otro `LoanOriginationDomainClient` —
exactamente la regla de cede-ante-tu-bean del Capítulo 1, aplicada a un cliente. Un
test registra un stub en memoria, y el bean de producción le cede el paso sin un perfil
ni un flag.

El propio `WebClient` se construye a partir del `basePath` enlazado, con su codec
dimensionado para el payload más grande que la capa espera (aquí, un generoso techo de
20 MB, reflejando la factoría del servicio real). En producción, el segundo bean
envolvería el SDK de dominio *generado*; el ejemplo envuelve un adaptador escrito a mano
para que el cableado sea lo bastante fiel como para diseccionarlo tal cual.

!!! note "Término clave — el patrón ClientFactory"
    Un **ClientFactory** es una `@Configuration` que ensambla un cliente aguas abajo a
    partir de `@ConfigurationProperties` enlazadas y lo expone detrás de un puerto,
    controlado por `@ConditionalOnProperty` (solo cuando está configurado) y
    `@ConditionalOnMissingBean` (solo cuando no se ha sobrescrito). Es la forma estándar
    de Firefly para "cablear un cliente resiliente a la capa de debajo", de modo que el
    cliente saliente de cada capa se configura, se condiciona y se sobrescribe de la
    misma manera.

!!! spring "Spring parity"
    Cada anotación de aquí — `@Configuration`, `@Bean`, `@EnableConfigurationProperties`,
    `@ConditionalOnProperty`, `@ConditionalOnMissingBean` — es Spring Boot puro, los
    mismos condicionales que usan las propias autoconfiguraciones de Firefly. Nada está
    oculto. El patrón es una convención, no un mecanismo nuevo: podrías escribirlo a mano
    en cualquier app de Spring, y el valor de Firefly es que cada servicio lo escribe de
    forma idéntica.

## El adaptador WebClient: el HTTP, y la cabecera de idempotencia

La factoría entrega el puerto a un adaptador respaldado por `WebClient`. Esta es la
única clase que sabe que el servicio de dominio habla HTTP — rutas, verbos, cabeceras.
Mantenerla detrás del puerto significa que el servicio, el controlador y los tests de la
capa de experiencia no importan `WebClient` en absoluto.

::: listing exp-lending/src/main/java/com/firefly/lumen/exp/config/WebClientLoanOriginationDomainClient.java | Listado 16.4 — el adaptador WebClient: el HTTP detras del puerto, la clave de idempotencia en cada llamada
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

El adaptador es una llamada reactiva de `WebClient` de manual: `post()` de la petición
de creación, `get()` del detalle por id, cada uno devolviendo un `Mono` del DTO de
canal. El único detalle en el que merece la pena detenerse es la cabecera. Ambos
métodos fijan `Idempotency-Key` a partir del argumento `idempotencyKey` que calculó el
servicio — de modo que la garantía de deduplicación viaja con cada llamada, escritura
*y* lectura. La capa de experiencia deriva esa clave deterministicamente aguas arriba
(el Capítulo 6 introdujo el filtro de idempotencia que *honra* dicha clave en el lado
receptor); aquí ves el lado *emisor* poniéndola en el cable.

!!! note "Término clave — propagación de X-Transaction-Id"
    Distinta de la clave de idempotencia es el **id de transacción**. El
    `TransactionFilter` del Capítulo 6 estampa cada respuesta con un `X-Transaction-Id`,
    acuñando uno si quien llama no lo aportó. Para que ese id correlacione de verdad una
    petición a través de las capas, un cliente saliente debe *reenviar* el id entrante en
    su llamada aguas abajo. El cliente resiliente de Firefly lo hace automáticamente: el
    `X-Transaction-Id` que lleva el intercambio entrante se propaga a la petición
    saliente, de modo que una operación lógica comparte un id de transacción desde el
    BFF, pasando por la capa de dominio, hasta el core — y los logs JSON cuadran de
    principio a fin. El adaptador escrito a mano del fragmento fija la cabecera de
    idempotencia explícitamente; trata la propagación del id de transacción como una
    propiedad del cliente resiliente del SDK generado, no como algo que este adaptador
    recortado cablee a mano.

## Resiliencia, aplicada mediante operadores de Reactor

Una llamada de `WebClient` es solo la mitad de un cliente resiliente. La otra mitad es
lo que ocurre cuando el servicio de dominio va lento, da errores o está saturado. El
cliente de producción de Firefly — el `ServiceClient` resiliente sobre el que se
construye el SDK generado — envuelve cada llamada en una pila de **Resilience4j**,
aplicada no como un `try`/`catch` imperativo sino como *operadores de Reactor* sobre el
mismo `Mono` que devuelve el adaptador. Al ser operadores, se componen en la cadena
reactiva sin bloquear un hilo.

Tres patrones hacen el trabajo pesado:

- **Cortacircuitos (circuit breaker).** Después de que falle una proporción configurada
  de llamadas recientes, el cortacircuitos se *abre* y falla rápido — cada llamada
  devuelve inmediatamente con un error en lugar de esperar a una dependencia que todo el
  mundo sabe ya que está enferma. Tras un enfriamiento deja pasar un goteo de llamadas
  (*semiabierto*); si tienen éxito se *cierra* de nuevo. Esto es lo que impide que una
  capa enferma se propague en cascada hacia las capas que tiene encima.
- **Reintento (retry).** Un fallo transitorio — una conexión caída, un `503` durante un
  despliegue progresivo — se reintenta un número acotado de veces, idealmente con
  backoff, de modo que un parpadeo no aflore al cliente. El reintento *solo* es seguro
  porque cada llamada lleva la clave de idempotencia determinista del Listado 16.4: un
  `submitApplication` reintentado deduplica aguas abajo en lugar de abrir un segundo
  préstamo.
- **Mamparo (bulkhead).** Un tope al número de llamadas concurrentes en vuelo hacia la
  dependencia, para que un servicio aguas abajo lento no pueda consumir todas las
  conexiones y dejar sin recursos al resto del servicio. El mamparo aísla el radio de
  impacto a la única dependencia que está sufriendo.

Conceptualmente, el cliente resiliente decora el `Mono` del adaptador con esos
operadores — esto es ilustrativo, no un fragmento del reactor:

```java
// Illustrative — how the resilient client layers Resilience4j onto the reactive call.
return webClient.post()
        .uri(APPLICATIONS_PATH)
        .header(IDEMPOTENCY_HEADER, idempotencyKey)
        .bodyValue(request)
        .retrieve()
        .bodyToMono(ApplicationDetailDTO.class)
        .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
        .transformDeferred(BulkheadOperator.of(bulkhead))
        .transformDeferred(RetryOperator.of(retry))
        .timeout(properties.timeout());
```

Cada `transformDeferred` envuelve la llamada en un decorador de Resilience4j; `timeout`
impone el presupuesto de las `LoanOriginationClientProperties` enlazadas. Ajustas cada
umbral — tasa de fallos, tiempo de espera, intentos máximos, tope de concurrencia — a
través de propiedades, nunca de código, de modo que el SRE que opera la flota ajusta el
cortacircuitos de una dependencia con intermitencias sin un redespliegue.

!!! warning "El reintento solo es seguro con idempotencia"
    Reintentar una escritura no idempotente es cómo creas dos préstamos a partir de un
    solo toque. La razón por la que el cliente resiliente de Firefly puede reintentar un
    `POST` siquiera es que la capa de experiencia deriva una clave de idempotencia
    *determinista* y el adaptador la envía en cada llamada. El reintento y la
    idempotencia van en pareja — activa uno sin el otro y tendrás o fragilidad o
    duplicados. Nunca habilites el reintento en una escritura que no lleve una clave de
    idempotencia estable.

!!! spring "Spring parity"
    Resilience4j incluye operadores de Reactor de primera clase —
    `CircuitBreakerOperator`, `RetryOperator`, `BulkheadOperator` — que puedes aplicar a
    cualquier `Mono` o `Flux` en una app de Spring corriente. Firefly no los reemplaza;
    los *precablea* en el cliente generado con valores por defecto estándar de la flota y
    umbrales ajustables con `firefly.*`, de modo que cada llamada saliente de la flota
    lleva cortacircuitos, reintentos y mamparos de la misma forma, en lugar de que cada
    equipo escriba a mano un `WebClient` ligeramente distinto.

## Más allá del REST: un cliente fluido, muchos protocolos

Hasta ahora cada llamada ha sido JSON sobre HTTP, porque las capas que Lumen posee lo
hablan todas. Una plataforma de core bancario de verdad no goza de ese lujo. El sistema
de registro para las cuentas es un servicio **SOAP** de hace veinte años. El motor de
fraude expone **gRPC**. El catálogo de productos está detrás de un gateway **GraphQL**.
Un feed de datos de mercado es un stream **WebSocket**. Integrar cada uno con su propio
cliente a medida — un stub JAX-WS aquí, un canal gRPC allá, un cliente Apollo por algún
otro sitio — es el impuesto empresarial del Capítulo 1, disfrazado de protocolo.

La respuesta de Firefly es un único `ServiceClient` unificado cuya *gramática fluida es
la misma* con independencia del protocolo de transporte que haya debajo. Eliges el
protocolo al construir el cliente; el punto de llamada se lee igual y — crucialmente —
se aplican el mismo cortacircuitos, reintento, mamparo y timeout de Resilience4j y la
misma propagación de `X-Transaction-Id`, sin importar qué protocolo transporte los
bytes. La resiliencia es una propiedad del *cliente*, no del HTTP.

Imagina la llamada SOAP al core bancario heredado que la capa de dominio debe hacer
para verificar una cuenta. En lugar de generar stubs JAX-WS y atornillar la resiliencia
a mano, recurres a la misma gramática de builder:

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

Cambia `ServiceClient.soap(...)` por `ServiceClient.grpc(...)`,
`ServiceClient.graphql(...)` o `ServiceClient.websocket(...)` y la *forma* permanece sin
cambios — configuración base, los mismos mandos de resiliencia, un `execute` que
devuelve un `Mono` (o un `Flux` para un protocolo de streaming). La llamada gRPC al
motor de fraude, la consulta GraphQL al catálogo y la suscripción WebSocket a los datos
de mercado se leen todas como variaciones de un mismo cliente, porque lo son:

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

La ventaja es la misma que defiende todo el libro: un desarrollador que aprendió el
cliente para la llamada REST de dominio ya conoce el cliente para el core SOAP, el motor
de fraude gRPC y el catálogo GraphQL. Una gramática, un modelo de resiliencia, un único
sitio donde ajustar umbrales — a través de cada protocolo que la plataforma se ve
obligada a hablar.

!!! note "Término clave — el ServiceClient unificado"
    El **`ServiceClient`** de Firefly es un cliente saliente agnóstico al protocolo: un
    único builder fluido y una gramática de llamada que apuntan a REST, SOAP, gRPC,
    GraphQL o WebSocket, elegidos en tiempo de construcción. Cada variante comparte la
    misma pila de resiliencia (cortacircuitos, reintento, mamparo, timeout) y la misma
    propagación de contexto (`X-Transaction-Id`), de modo que el comportamiento
    transversal es idéntico con independencia del formato de cable. Es la generalización
    de la costura REST que diseccionaste en este capítulo a cada protocolo con el que se
    integra una plataforma bancaria.

!!! warning "Los clientes multiprotocolo aquí son ilustrativos"
    Las capas de Lumen Lending se integran sobre REST, así que los fragmentos
    *verificados* de este capítulo son la costura REST, sus propiedades, su ClientFactory
    y su adaptador `WebClient`. Los fragmentos de SOAP, gRPC, GraphQL y WebSocket de
    arriba son ilustrativos — muestran la forma del `ServiceClient` unificado y dónde se
    enchufa, no código que compile el build de este capítulo. Léelos como "así es como el
    mismo patrón se extiende más allá del REST", y recurre a la referencia del
    `ServiceClient` del framework cuando cablees una dependencia no HTTP real.

## Ejecútalo

La capa de experiencia arranca y funciona sin un servicio de dominio, y eso es
precisamente lo que te compra el cliente condicional. El contexto de test nunca
establece `lumen.exp.loan-origination.base-path`, así que el `WebClient` de producción y
el adaptador del Listado 16.3 nunca se materializan; en su lugar se registra un
`StubLoanOriginationDomainClient`, y `@ConditionalOnMissingBean` garantiza que gane. El
controlador y el servicio ejecutan su ruta reactiva completa contra el stub — sin HTTP,
sin Docker. Desde el directorio `samples/lumen-lending`:

```text
mvn -q -pl exp-lending test
```

El resultado esperado:

```text
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Nueve tests en verde confirman las afirmaciones del capítulo sobre la costura: la capa
de experiencia compone un ciclo completo de creación-y-lectura a través del puerto,
deriva una clave de idempotencia determinista que el stub puede observar, y aflora una
solicitud inexistente como un problem detail `404` — todo sin que el cliente de
producción llegue a construirse nunca, porque no hay ninguna URL base configurada.

!!! tip "Punto de control"
    Fíjate en *por qué* la batería está en verde sin un servicio de dominio en
    ejecución: los dos beans de producción en `LoanOriginationClientConfig` están
    controlados por `@ConditionalOnProperty(name = "base-path")` y
    `@ConditionalOnMissingBean`. Sin `base-path` establecido y con un stub de test
    presente, ambas condiciones mantienen al cliente de producción fuera del contexto.
    Establece `lumen.exp.loan-origination.base-path` en un perfil de test y el bean
    `WebClient` intentaría construirse — la prueba de que es la condición, y no la
    suerte, lo que mantiene el test hermético.

## Lo que has construido {.recap}

- Un **puerto** reactivo, `LoanOriginationDomainClient`, del que depende la capa de
  experiencia para alcanzar el servicio de originación del dominio — dos métodos que
  devuelven `Mono`, cada uno portando una clave de idempotencia determinista, sin que
  ningún detalle HTTP se filtre más allá de la interfaz.
- Un record `@ConfigurationProperties`, `LoanOriginationClientProperties`, que enlaza la
  **URL base y el timeout** aguas abajo desde la configuración bajo el prefijo
  `lumen.exp.loan-origination`, de modo que el mismo código se promueve entre entornos
  con un cambio de propiedad.
- Una `@Configuration` **ClientFactory** que construye el `WebClient` de producción y el
  bean de cliente solo cuando se satisface `@ConditionalOnProperty(base-path)` y
  `@ConditionalOnMissingBean` confirma que nada lo sobrescribe — la regla de
  cede-ante-tu-bean aplicada a un cliente saliente.
- Un **adaptador** `WebClient` que hace el HTTP real detrás del puerto y fija la
  cabecera `Idempotency-Key` en cada llamada, además de la imagen conceptual del
  cortacircuitos, reintento y mamparo de **Resilience4j** aplicados mediante operadores
  de Reactor, y la propagación de `X-Transaction-Id` a través del salto.
- El **`ServiceClient` unificado** — la misma gramática fluida y resiliente sobre SOAP,
  gRPC, GraphQL y WebSocket — y una línea honesta entre lo que verifica este fragmento
  REST y lo que es ilustrativo.
- Una batería de nueve tests que pasa (`Tests run: 9, Failures: 0`) y que ejecuta toda
  la capa contra un stub en memoria, con el cliente de producción condicionado fuera —
  sin servicio de dominio, sin Docker.

## Pruébalo tú mismo {.exercises}

1. **Haz que el cliente de producción exista por configuración.** En un test de
   `exp-lending`, añade una fuente de propiedades que establezca
   `lumen.exp.loan-origination.base-path` y *elimina* el registro del stub. Observa que
   `LoanOriginationClientConfig` ahora construye el bean `WebClient` (busca la línea de
   log `Building Loan Origination WebClient`). Explica, en una frase, qué condición ha
   cambiado.
2. **Sobrescribe el cliente con tu propio bean.** Deja un `base-path` configurado, luego
   define un segundo `@Bean` de `LoanOriginationDomainClient` en una configuración de
   test. Confirma que el bean de producción se retira y se inyecta el tuyo — y luego
   nombra la única anotación que lo hizo posible.
3. **Traza la clave de idempotencia.** Abre `StubLoanOriginationDomainClient` en
   `src/test/java` y encuentra `idempotencyKeys()`. Escribe un test que envíe la *misma*
   petición lógica dos veces y afirme que la clave registrada es idéntica en ambas
   ocasiones, demostrando que la capa de experiencia la deriva deterministicamente en
   lugar de acuñar una aleatoria.
4. **Cambia el timeout por defecto.** El constructor compacto de
   `LoanOriginationClientProperties` establece `timeout` por defecto en diez segundos.
   Añade un test que enlace las propiedades sin `timeout` y afirme el valor por defecto,
   y luego otro que establezca `timeout: 3s` y afirme la sobrescritura — demostrando que
   la configuración, no el código, es dueña del presupuesto.
5. **Esboza una costura SOAP.** Sin ejecutarlo, escribe el builder
   `ServiceClient.soap(...)` que usarías para la llamada de verificación de cuenta al
   core bancario heredado, incluyendo un cortacircuitos y un reintento. Nombra qué dos
   comportamientos transversales obtienes *gratis* del cliente unificado que de otro modo
   tendrías que cablear a mano en un stub JAX-WS.

## Adónde ir ahora

Ahora tienes la mitad de cada límite de capa que corresponde a quien llama: un puerto,
direccionamiento dirigido por configuración, un ClientFactory condicional, un adaptador
resiliente y un cliente unificado que llega más allá del REST. Los próximos capítulos
ponen estas llamadas bajo carga y bajo vigilancia — la observabilidad que hace que el
`X-Transaction-Id` que propagaste aquí sea de verdad trazable a través de la flota, y
los tests que ejercitan una petición completa desde la capa de experiencia, bajando por
la saga de dominio hasta el sistema de registro del core.
