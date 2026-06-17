La capa core es la dueña del sistema de registro. La capa de dominio la orquesta con
comandos, consultas y sagas. Ninguna de las dos está moldeada para un teléfono o una
aplicación web. Un canal necesita una petición que pueda enviar en un solo viaje de ida
y vuelta, una respuesta que pueda renderizar sin una segunda llamada y una respuesta a
"¿qué pasa cuando el usuario pulsa *Enviar* dos veces?". Ese es el trabajo de la **capa
de experiencia** — el backend-for-frontend, o BFF — y es lo que construyes en este
capítulo.

La capa de experiencia es deliberadamente fina. No tiene base de datos ni reglas de
negocio propias. Su propósito entero es la *composición*: moldear una petición de canal,
llamar al dominio a través de la junta del SDK y mapear el resultado a una vista ligera
que el front-end realmente quiere. Todo lo que tiene estado vive detrás de ella. Lo que
añade son las preocupaciones de frontera que un canal necesita — validación en el límite,
autorización declarativa, idempotencia determinista para que un envío reintentado no cree
dos solicitudes, y un modelo de respuesta desacoplado de las interioridades del dominio.

Este capítulo es la tercera capa del stack vivo de Lumen. La misma solicitud de préstamo
que enviaste con POST al core en el capítulo 2 y orquestaste a través de la saga de dominio
en los capítulos posteriores ahora entra desde *fuera*, a través de `exp-lending`, el canal
de cara al cliente. Cuando los tres módulos están en marcha, un único POST al BFF fluye de
extremo a extremo: `exp` valida y compone, llama al dominio sobre HTTP, el dominio ejecuta
`RegisterApplicationSaga`, el paso raíz de la saga escribe en core, y el id asignado por core
vuelve a través de ambas juntas marcado como `SUBMITTED`. Ejecutarás ese flujo exacto al
final del capítulo.

En él rebanas el módulo `exp-lending` de Lumen: un controlador reactivo asegurado, el
servicio que valida y compone, el ayudante de clave de idempotencia determinista que hace
seguro un reintento, los DTOs de canal que son el contrato de cable propio del BFF, y la
junta respaldada por `WebClient` que lleva la llamada al dominio. Una batería de nueve
pruebas demuestra que arranca, valida, deduplica y mapea — sin servicio de dominio y sin
Docker — y luego levantas las tres capas y ves cómo el envío en vivo aterriza en core.
Empecemos por lo que hace que un módulo sea una capa de experiencia.

## El starter de aplicación

Cada capa en Lumen se apoya en un *starter* de Firefly — un starter de Boot que enciende
exactamente las capacidades que esa capa necesita. La capa core usa `starter-core`; la
capa de dominio usa `starter-domain`. La capa de experiencia usa
`fireflyframework-starter-application`, el starter construido para la capa que da la cara
a los clientes. Su única línea de dependencia incorpora las preocupaciones de la capa de
aplicación — seguridad declarativa, caché, las cañerías de CQRS, la fábrica de clientes —
para que el BFF arranque con ellas ya cableadas.

::: listing exp-lending/pom.xml | Listado 17.1 — la capa de experiencia se construye sobre el starter de aplicación
        <!-- Application/experience-layer starter (security, cache, CQRS, client). -->
        <dependency>
            <groupId>org.fireflyframework</groupId>
            <artifactId>fireflyframework-starter-application</artifactId>
        </dependency>
        <!-- Reactive web layer helpers. -->
        <dependency>
            <groupId>org.fireflyframework</groupId>
            <artifactId>fireflyframework-web</artifactId>
        </dependency>
:::

Dos dependencias y el módulo es una capa de experiencia. `starter-application` aporta la
maquinaria de la capa de aplicación; `fireflyframework-web` aporta los mismos ayudantes
web reactivos que conociste en el capítulo 6 — el `GlobalExceptionHandler` que renderiza
los detalles de problema RFC 7807, el filtro de idempotencia, el sellado de transacciones.
Como el módulo web está presente, cada `BusinessException` que lanza el BFF se convierte en
una respuesta limpia de detalle de problema sin código de manejador, exactamente como ocurría
en la capa core.

Hay una consecuencia silenciosa que vale la pena señalar ahora, porque moldea el paso 1.
`starter-application` trae `spring-boot-starter-security` de forma transitiva, así que en
el momento en que el módulo está en el classpath se encienden los valores por defecto de
REST sin estado de Spring Security — HTTP Basic, login por formulario, CSRF. El BFF no los
quiere; quiere autorización a nivel de *método* mediante `@Secure`. Por eso Lumen incluye
un pequeño `WebSecurityConfig` que desactiva los ruidosos valores por defecto y permite cada
intercambio en la cadena de filtros, dejando que la autorización aterrice sobre el manejador.
Verás por qué importa esa división dentro de un momento.

!!! note "Término clave — el starter de aplicación (`fireflyframework-starter-application`)"
    El starter de Firefly Boot para la **capa de experiencia/aplicación** — la capa que
    compone servicios aguas abajo para un canal. Autoconfigura la seguridad declarativa
    (`@Secure`), la caché de resultados, los buses de CQRS y la fábrica de clientes que
    construye clientes SDK generados, de modo que un módulo BFF obtiene las capacidades de
    cara al canal por la sola presencia de la dependencia. El `starter-core` de la capa core
    y el `starter-domain` de la capa de dominio son sus hermanos; cada capa elige el starter
    que encaja con su trabajo.

## Paso 1 — Un controlador reactivo asegurado

El controlador es la puerta de entrada del canal. Es un `@RestController` reactivo plano —
`@PostMapping` para crear, `@GetMapping` para leer uno — y, como el controlador core del
capítulo 6, no lleva manejo de errores ni envoltorio. Lo nuevo aquí es la anotación
`@Secure` en cada método: autorización declarativa, por endpoint, que el starter de
aplicación aplica.

::: listing exp-lending/src/main/java/com/firefly/lumen/exp/web/ApplicationController.java | Listado 17.2 — el controlador del BFF: reactivo, asegurado con @Secure
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

Lee primero los dos métodos, después la seguridad. `createApplication` toma un
`@Valid @RequestBody CreateApplicationRequest`, lo entrega al servicio y mapea el
resultado a un `ResponseEntity` `201 Created`. `getApplication` lee uno por id y lo
mapea a `200 OK`. Ambos devuelven un `Mono` de un `ResponseEntity`, así que el BFF es
no bloqueante desde el socket hacia dentro, y ambos delegan de inmediato — el controlador
no contiene lógica. Ese `@Valid` arma Bean Validation sobre la petición en el mismísimo
borde, la primera línea de defensa antes de que algo cruce al servicio.

La anotación `@Secure` es la aportación de la capa de experiencia. `@Secure(permissions =
{"lending:application:create"})` declara que un llamante debe poseer el permiso
`lending:application:create` para invocar `createApplication`; el método de lectura
requiere `lending:application:read`. No escribes un `if` que compruebe un principal, y no
configuras una regla de patrón de URL en una cadena de filtros. Declaras el permiso sobre
el método, y el `SecurityAspect` del starter de aplicación intercepta la llamada y lo
aplica.

Fíjate también en dónde vive el espacio de URL: `/api/v1/experience/lending/applications`.
El segmento `experience` no es decoración — anuncia la capa en la propia ruta, de modo que
una pasarela, una línea de log o un ingeniero leyendo una traza de acceso puede distinguir
de un vistazo una llamada de canal del `/api/v1/applications` del dominio y del
`/api/v1/loan-applications` del core. Las tres capas comparten un vocabulario pero nunca una
ruta.

!!! note "Término clave — seguridad declarativa (`@Secure`)"
    `@Secure` es la anotación de autorización a nivel de método de la capa de aplicación.
    Declaras los `permissions` (y opcionalmente roles, una expresión o un ámbito de tenant)
    que un método requiere; el `SecurityAspect` del starter — un advice de AOP — intercepta
    el método anotado, resuelve el `AppSecurityContext` del llamante y procede o rechaza
    antes de que se ejecute tu código. La autorización es una *declaración sobre el manejador*,
    no una regla dispersa por una cadena de filtros, de modo que el contrato es visible justo
    donde se define el método.

!!! spring "Equivalente en Spring"
    `@Secure` desempeña el papel que desempeña `@PreAuthorize` de Spring Security, pero es
    una anotación de Firefly evaluada por el `SecurityAspect` del starter de aplicación,
    ajustada por propiedades `firefly.application.security.*` y consistente en cada BFF de
    la flota. La cadena de filtros HTTP que la rodea sigue siendo Spring Security de serie
    con `@EnableWebFluxSecurity` — el `WebSecurityConfig` de Lumen simplemente permite todos
    los intercambios y desactiva los ruidosos valores por defecto de REST sin estado (HTTP
    Basic, login por formulario, CSRF), de modo que la autorización a nivel de *método*, no
    la cadena de filtros, es el único lugar donde vive la autorización.

### `@Secure` es real, pero su aplicación está desactivada en ejecuciones locales

Sé honesto sobre lo que ejercitas realmente. Las anotaciones `@Secure` del controlador son
código de producción real — el `SecurityAspect` intercepta ambos métodos en tiempo de
ejecución, lo cual puedes ver en el log de la prueba (`Intercepting @Secure method:
createApplication`). Pero ni la prueba de rebanada ni la ejecución local acuñan tokens ni
levantan un centro de seguridad. En su lugar, la aplicación se apaga con una sola propiedad.
En el `src/main/resources/application.yml` ejecutable dice:

```yaml
firefly:
  application:
    security:
      enabled: false
```

y la prueba de rebanada fija la misma propiedad en `src/test/resources/application.yml`.
Con `firefly.application.security.enabled=false`, el aspecto hace un cortocircuito — registra
que la seguridad está desactivada y deja pasar la llamada — de modo que el BFF es alcanzable
localmente con `curl` y la rebanada de `WebTestClient` puede manejarlo sin autenticación. La
anotación está presente e interceptada; solo la *aplicación* está apagada. Producción mantiene
la propiedad en `true` y los permisos se comprueban de verdad. El capítulo 19 desarrolla la
historia de seguridad completa — cómo se puebla `AppSecurityContext`, cómo se resuelven los
permisos, cómo llega el token. Aquí, trata `@Secure` como cableado y visible, con la aplicación
aparcada.

!!! warning "Una rebanada en verde y un curl en vivo prueban el cableado, no la autorización"
    Como `security.enabled=false`, ni una rebanada que pasa ni un POST local exitoso prueban
    que `lending:application:create` se aplica. Prueban que el controlador está asegurado por
    anotación, que la petición valida, que el servicio compone, que la llamada alcanza el
    dominio y que los errores se renderizan como detalles de problema. No leas ninguno de los
    dos como una prueba de autorización — ese es el trabajo del capítulo 19, contra el camino
    con la aplicación encendida.

## Paso 2 — Los DTOs de canal

El BFF nunca devuelve un tipo de dominio en el cable. Mapea la respuesta del dominio a su
propio record de *canal*, de modo que el front-end depende del contrato de la capa de
experiencia, no de las interioridades del dominio. Ese desacoplamiento es el propósito entero
de un backend-for-frontend: puedes remodelar el modelo del SDK de dominio sin romper una
sola pantalla de la app, porque la vista de canal se interpone entre ambos.

La petición que el canal envía es `CreateApplicationRequest`, un record validado en el borde
por restricciones ordinarias de Bean Validation.

::: listing exp-lending/src/main/java/com/firefly/lumen/exp/dto/CreateApplicationRequest.java | Listado 17.3 — la petición de canal, validada en el borde
public record CreateApplicationRequest(

        @NotNull(message = "productId is required")
        UUID productId,

        @NotNull(message = "requestedAmount is required")
        @DecimalMin(value = "0.01", message = "requestedAmount must be strictly positive")
        BigDecimal requestedAmount,

        @NotNull(message = "term is required")
        @Min(value = 1, message = "term must be at least 1 month")
        Integer term,

        String purpose,

        UUID simulationId
) {
}
:::

`productId`, `requestedAmount` y `term` son obligatorios y están restringidos; `purpose` y
`simulationId` son opcionales. El `@Valid` sobre el `@RequestBody` del controlador dispara
estas restricciones antes de que se ejecute el cuerpo del manejador, de modo que un importe
cero se rechaza con un `400` en el límite — verás exactamente eso en la ejecución. El
`simulationId` es un enlace blando de vuelta a la simulación que produjo esta solicitud; el
canal lo envía para que el BFF pueda devolverlo como eco.

Mira de cerca los nombres de los campos, porque son el vocabulario del canal, y deliberadamente
*no* es el del core. El canal habla de un `productId`, un `term` (un escueto número de meses) y
un `simulationId`; el DTO del sistema de registro del core del capítulo 2 habla de un
`applicantId`, `termMonths`, una `currency` y un `purpose` que almacena al pie de la letra. Son
dos audiencias diferentes — una pantalla de teléfono frente a un libro mayor — y las capas
intermedias traducen. Ese desajuste no es un accidente que haya que arreglar; es la junta
haciendo su trabajo, y verás exactamente qué campos de canal sobreviven al viaje hasta core (y
cuáles aterrizan como valores por defecto) cuando ejecutes el flujo en vivo.

La respuesta que el canal renderiza es `ApplicationDetailDTO` — la propia vista completa del
BFF, moldeada para una pantalla, no para el almacenamiento del dominio.

::: listing exp-lending/src/main/java/com/firefly/lumen/exp/dto/ApplicationDetailDTO.java | Listado 17.4 — la vista de respuesta de canal, desacoplada del modelo de cable del dominio
public record ApplicationDetailDTO(
        UUID applicationId,
        UUID simulationId,
        String status,
        BigDecimal requestedAmount,
        Integer term,
        String purpose,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
:::

Este es el record que devuelve el controlador y contra el que afirma la prueba. Fíjate en que
devuelve `simulationId` como eco directo, de modo que el front-end puede persistir el enlace de
trazabilidad sin una llamada de seguimiento — una pequeña decisión de composición que pertenece
al canal, no al dominio. El módulo también mantiene un `ApplicationSummaryDTO` para respuestas
de lista; el patrón es idéntico, una forma más ligera para una pantalla distinta. El dominio
puede devolver veinte campos; el BFF devuelve los ocho que necesita una pantalla de detalle, y
nada se filtra que el canal no haya pedido.

!!! note "Término clave — DTO de canal"
    Un **DTO de canal** es un tipo de vista propiedad de la capa de experiencia y moldeado para
    una superficie de cliente — una pantalla de detalle, una fila de lista. Es el contrato de
    cable del BFF, deliberadamente separado del modelo del SDK de dominio para que ambos puedan
    evolucionar de forma independiente. El servicio mapea las respuestas del dominio a DTOs de
    canal; el front-end solo ve estos, que es lo que permite al dominio cambiar sus interioridades
    sin una release de cliente coordinada.

## Paso 3 — El servicio: validar, derivar una clave, componer, mapear

El controlador delega todo en `ApplicationService`, y aquí es donde la capa de experiencia se
gana su nombre. El servicio hace cuatro cosas y solo cuatro: valida la petición de canal, deriva
una clave de idempotencia *determinista* a partir de los campos estables de la petición, cruza
la junta del SDK hacia el dominio y mapea cualquier fallo aguas abajo a una `BusinessException`
que el módulo web pueda renderizar. Sin persistencia, sin reglas de negocio — composición pura.

::: listing exp-lending/src/main/java/com/firefly/lumen/exp/service/ApplicationService.java | Listado 17.5 — componer: validar, derivar una clave determinista, llamar al SDK, mapear errores
    public Mono<ApplicationDetailDTO> createApplication(CreateApplicationRequest request) {
        return Mono.fromCallable(() -> validate(request))
                .flatMap(validated -> {
                    log.debug("Creating application productId={} simulationId={} requestedAmount={} term={}",
                            validated.productId(), validated.simulationId(),
                            validated.requestedAmount(), validated.term());

                    // Idempotency key derived from stable input fields. Same logical request
                    // (retry of the same input) -> same key -> domain dedupes without the
                    // channel minting a resource id.
                    String submitKey = IdempotencyKeys.of(
                            "exp-lending", "create-application", "submit",
                            String.valueOf(validated.productId()),
                            String.valueOf(validated.simulationId()),
                            validated.requestedAmount().toPlainString(),
                            String.valueOf(validated.term()),
                            String.valueOf(validated.purpose()));

                    return domainClient.submitApplication(validated, submitKey)
                            .onErrorMap(this::isNotBusinessException, this::toUpstreamError);
                });
    }
:::

Recorre la cadena reactiva. `Mono.fromCallable(() -> validate(request))` ejecuta la validación
defensiva de forma perezosa, dentro de la tubería reactiva, de modo que un fallo de validación
se convierte en una señal `onError` en lugar de una excepción lanzada que escape del publicador.
`validate` vuelve a comprobar la petición aunque el `@Valid` del controlador ya se haya ejecutado
— el BFF no confía en que cada camino de llamante haya validado, así que protege la propia junta,
lanzando una `BusinessException(BAD_REQUEST, "VALIDATION_FAILED", ...)` ante un campo erróneo.

Luego viene la clave, el corazón del capítulo, y nos detendremos en ella en el siguiente paso.
Con la clave en la mano, `domainClient.submitApplication(validated, submitKey)` cruza la junta
del SDK — `domainClient` es un `LoanOriginationDomainClient`, el puerto reactivo que el BFF llama
para alcanzar el servicio de originación del dominio. Ese puerto es el mismo tipo de junta que
conociste en el capítulo 10: una interfaz de la que depende el servicio, con el *cómo* (un stub
en memoria en las pruebas, un `WebClient` en vivo en un stack en marcha) suministrado detrás de
ella. Finalmente `.onErrorMap(this::isNotBusinessException, this::toUpstreamError)` traduce
cualquier fallo *no de negocio* — un parpadeo de transporte, un error de deserialización — a una
`BusinessException(BAD_GATEWAY, "UPSTREAM_ERROR", ...)`, mientras deja intactas las
`BusinessException` que el dominio ya produjo. Esa es la disciplina de mapeo de errores: el canal
nunca filtra una traza de pila cruda de aguas abajo; o bien deja pasar un error de negocio
significativo o envuelve el resto como un `502` limpio.

El camino de lectura es simétrico, y muestra el mapeo de no encontrado:

::: listing exp-lending/src/main/java/com/firefly/lumen/exp/service/ApplicationService.java | Listado 17.6 — el camino de lectura mapea una solicitud ausente a un detalle de problema 404
    public Mono<ApplicationDetailDTO> getApplication(UUID applicationId) {
        if (applicationId == null) {
            return Mono.error(new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "applicationId is required"));
        }
        log.debug("Getting application applicationId={}", applicationId);
        // Reads are safe to retry; the key is stable for a given id.
        String getKey = IdempotencyKeys.of("exp-lending", "get-application", applicationId.toString());
        return domainClient.getApplication(applicationId, getKey)
                .switchIfEmpty(Mono.error(new BusinessException(
                        HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND",
                        "loan application not found: " + applicationId)))
                .onErrorMap(this::isNotBusinessException, this::toUpstreamError);
    }
:::

El `switchIfEmpty` es el mismo idioma que usó el capítulo 6: cuando el dominio devuelve un
`Mono` vacío, sustitúyelo por una señal de error — aquí una `BusinessException(NOT_FOUND,
"APPLICATION_NOT_FOUND", ...)`. El `GlobalExceptionHandler` del módulo web la convierte en un
detalle de problema `404` que lleva el código `APPLICATION_NOT_FOUND`, sobre el que la prueba de
rebanada afirma en el cable. El BFF lanza una excepción de negocio *semántica* con un código
estable y un estado; el framework renderiza el cuerpo RFC 7807. Tú escribes el significado; el
framework escribe el JSON.

!!! note "Término clave — BusinessException"
    `org.fireflyframework.web.error.exceptions.BusinessException` es el portador del framework
    para un fallo *significativo*: empareja un `HttpStatus` con un código estable y legible por
    máquina (`APPLICATION_NOT_FOUND`, `VALIDATION_FAILED`, `UPSTREAM_ERROR`) y un mensaje humano.
    El `GlobalExceptionHandler` del módulo web la renderiza como un detalle de problema RFC 7807,
    de modo que un cliente de canal ve la misma forma de error desde cada servicio Firefly.
    Lanzar una es la forma en que un servicio dice "esto falló, y aquí está la razón de cara al
    cliente" sin escribir un manejador de excepciones.

## Paso 4 — La clave de idempotencia determinista

Aquí está el patrón insignia de la capa de experiencia, y la razón por la que existe este
capítulo. Un envío de canal puede reintentarse — el usuario pulsa dos veces, la red descarta la
respuesta después de que el servidor la procesara, un cliente móvil reproduce una petición en
cola al reconectarse. Si cada reintento creara una solicitud de préstamo nueva, una pulsación se
convertiría en tres. El arreglo es una *clave de idempotencia*: un token estable adjunto a la
petición de modo que la capa aguas abajo reconoce un reintento y devuelve el resultado original en
lugar de crear un duplicado.

La pregunta difícil es quién acuña la clave. Si el canal genera un UUID aleatorio por intento, un
reintento lleva una clave *nueva* y no deduplica nada. Si el BFF acuña un id de recurso por
adelantado, ha asumido una responsabilidad sobre la identidad que pertenece al sistema de registro.
El patrón de Firefly esquiva ambas: la clave se *deriva* de los campos de negocio estables de la
petición, de modo que la misma petición lógica siempre produce la misma clave — sin que nadie
acuñe un id. Esa derivación vive en un pequeño ayudante.

::: listing exp-lending/src/main/java/com/firefly/lumen/exp/util/IdempotencyKeys.java | Listado 17.7 — la clave determinista: un UUID v3 sobre una entrada unida por ':'
public final class IdempotencyKeys {

    private IdempotencyKeys() {
        // utility
    }

    /**
     * Derives a deterministic UUID-shaped idempotency key from the given parts. Null parts are
     * coerced to the literal string {@code "null"} so the call never throws on missing inputs.
     *
     * @param parts identifying inputs, joined with {@code ":"}
     * @return a stable v3 UUID string derived from the parts
     */
    public static String of(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(parts[i] == null ? "null" : parts[i]);
        }
        return UUID.nameUUIDFromBytes(sb.toString().getBytes(StandardCharsets.UTF_8)).toString();
    }
}
:::

El ayudante entero es un solo método. `IdempotencyKeys.of(parts...)` une sus argumentos con
`':'`, codifica el resultado como UTF-8 y ejecuta `UUID.nameUUIDFromBytes(...)` — el UUID de
*versión 3* (basado en nombre) RFC 4122 del JDK. Un UUID basado en nombre es una función pura de
su entrada: la misma cadena unida siempre da el mismo UUID, y una cadena distinta da uno distinto.
La salida es una cadena UUID válida, así que encaja directamente en una cabecera HTTP estándar
`Idempotency-Key`. Las partes nulas se fuerzan al literal `"null"` para que un campo opcional
ausente nunca lance.

Ahora relee cómo lo llamó el servicio en el listado 17.5. Las partes son un *espacio de nombres*
(`"exp-lending"`, `"create-application"`, `"submit"`) más los campos estables de la petición —
producto, simulación, importe, plazo, propósito. Dos envíos de la *misma* solicitud lógica
producen partes idénticas, de ahí una clave idéntica, de ahí una sola solicitud aguas abajo.
Cambia cualquier campo — un importe distinto — y la clave cambia, porque es una petición
genuinamente diferente. El canal nunca acuña un id de recurso; computa una huella determinista de
la petición, y la capa de dominio deduplica sobre ella. La prueba del servicio demuestra
exactamente esto: recomputa la clave esperada de forma independiente y afirma que el servicio
entregó esa misma clave a la junta del SDK.

!!! note "Término clave — clave de idempotencia determinista"
    Una clave derivada como función pura de las entradas de negocio estables de una petición, de
    modo que un reintento de la *misma* operación lógica produce la *misma* clave. El
    `IdempotencyKeys.of(...)` de Firefly construye una como un UUID basado en nombre (v3) sobre las
    entradas unidas. Como se deriva en lugar de acuñarse, el canal puede permanecer sin estado —
    nunca tiene que recordar una clave entre intentos ni asignar un id de recurso — y la capa aguas
    abajo sigue deduplicando de forma fiable. Entrada estable, clave estable; entrada distinta,
    clave distinta.

!!! spring "Equivalente en Spring"
    Este es el lado productor de la misma historia de idempotencia que el capítulo 6 mostró desde
    el lado consumidor. Allí, el `IdempotencyWebFilter` de `fireflyframework-web` cacheaba y
    reproducía una respuesta indexada por una cabecera `X-Idempotency-Key`. Aquí la capa de
    experiencia *computa* esa clave de forma determinista y la reenvía a través de la junta del
    SDK, de modo que la deduplicación ocurre aguas abajo. Spring puro no te da ninguna de las dos
    mitades; Firefly suministra el filtro que honra la clave y el ayudante que la deriva, y se
    encuentran en la cabecera.

!!! tip "Punto de control"
    Traza un reintento en tu cabeza. Un cliente envía el mismo cuerpo dos veces. El controlador
    valida ambos. El servicio computa `IdempotencyKeys.of(...)` sobre los mismos campos ambas
    veces, de modo que `submitKey` es idéntica en el intento uno y el intento dos. La capa de
    dominio ve la misma clave, reconoce la segunda llamada como un reintento y devuelve la
    solicitud original — una fila, no dos — y el BFF nunca acuñó un id para hacer que eso
    funcione. Ese es el patrón entero.

## Paso 5 — La junta: de un stub en pruebas a un WebClient en vivo en un stack en marcha

`ApplicationService` depende solo de la *interfaz* `LoanOriginationDomainClient` — nunca nombra un
transporte. Esa es la junta, y es lo que permite que el mismo servicio compositor se ejecute de dos
maneras: contra un stub en memoria en la prueba de rebanada, y contra un servicio de dominio real
sobre HTTP cuando levantas el stack. El adaptador de producción es una implementación respaldada
por `WebClient` que reenvía cada llamada al dominio y lleva la clave determinista como una cabecera
estándar.

::: listing exp-lending/src/main/java/com/firefly/lumen/exp/config/WebClientLoanOriginationDomainClient.java | Listado 17.8 — la junta en vivo: enviar con POST la petición de canal al dominio, llevando la clave de idempotencia
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
:::

Este es el envío en vivo entero. `APPLICATIONS_PATH` es `"/api/v1/applications"` — la cara REST de
la capa de dominio — y `IDEMPOTENCY_HEADER` es `"Idempotency-Key"`, de modo que la clave que el
servicio derivó en el listado 17.5 viaja por el cable como la cabecera estándar que el filtro aguas
abajo honra. El adaptador envía la `CreateApplicationRequest` *de canal* directamente y deserializa
la respuesta del dominio de vuelta a la `ApplicationDetailDTO` *de canal*; las formas de cable que
el BFF y el dominio acuerdan son el contrato entre las dos capas. La llamada entera permanece
reactiva — `bodyToMono` devuelve un `Mono`, así que nada bloquea un hilo esperando al dominio.

Ese adaptador lo cablea una pequeña `@Configuration`, y las dos condiciones sobre sus beans son la
razón por la que el mismo módulo arranca tanto para la prueba de rebanada como para la ejecución en
vivo.

::: listing exp-lending/src/main/java/com/firefly/lumen/exp/config/LoanOriginationClientConfig.java | Listado 17.9 — el cliente en vivo solo se materializa cuando se fija un base path, y nunca por encima de un stub de prueba
    @Bean
    @ConditionalOnProperty(prefix = "lumen.exp.loan-origination", name = "base-path")
    @ConditionalOnMissingBean
    public LoanOriginationDomainClient loanOriginationDomainClient(WebClient loanOriginationWebClient) {
        return new WebClientLoanOriginationDomainClient(loanOriginationWebClient);
    }
:::

Lee las dos condiciones juntas. `@ConditionalOnProperty(... name = "base-path")` significa que el
cliente en vivo se crea *solo* cuando un operador ha apuntado el BFF a un servicio de dominio real
— fija `lumen.exp.loan-origination.base-path`, y aparece el adaptador respaldado por `WebClient`.
`@ConditionalOnMissingBean` significa que se hace a un lado en el instante en que ya existe otro
`LoanOriginationDomainClient` — que es exactamente lo que registra la prueba de rebanada, un stub
en memoria. Así que sin base path, o con un stub de prueba presente, el cliente HTTP en vivo nunca
se despierta; con un base path y sin stub, lo hace. Un módulo, dos comportamientos fieles, decididos
por configuración en lugar de por una rama de código.

El base path en sí se enlaza a través de un diminuto record `@ConfigurationProperties`, el mismo
patrón que usa la capa de dominio para su cliente de core.

::: listing exp-lending/src/main/java/com/firefly/lumen/exp/config/LoanOriginationClientProperties.java | Listado 17.10 — las propiedades enlazadas: URL base y un timeout con valor por defecto
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

Y el `application.yml` ejecutable es lo que enciende el camino en vivo. Fija el puerto del BFF,
apunta la junta al dominio y desactiva la aplicación de seguridad para uso local:

::: listing exp-lending/src/main/resources/application.yml | Listado 17.11 — la configuración ejecutable: puerto, el base path del dominio, aplicación de seguridad desactivada
server:
  port: 8080

spring:
  application:
    name: exp-lending

firefly:
  application:
    security:
      enabled: false

# Domain-tier Loan Origination service the BFF calls (prefix bound by
# LoanOriginationClientProperties).
lumen:
  exp:
    loan-origination:
      base-path: http://localhost:8082
:::

Ese `base-path: http://localhost:8082` es la línea que da vida al capítulo entero. Satisface el
`@ConditionalOnProperty`, de modo que el adaptador respaldado por `WebClient` se materializa y la
junta del BFF apunta al servicio de *dominio* en el puerto 8082 — no a core directamente. La
dirección de la dependencia es estricta: `exp` conoce solo al dominio; el dominio conoce a core. Y
`firefly.application.security.enabled: false` es el mismo interruptor de aplicación del paso 1, aquí
en el perfil ejecutable para que un `curl` local no sea rechazado.

!!! note "Término clave — `@ConditionalOnProperty` / `@ConditionalOnMissingBean`"
    Dos condiciones de Spring Boot que hacen que un módulo se comporte de forma diferente según la
    configuración. `@ConditionalOnProperty` crea un bean solo cuando una propiedad con nombre está
    fijada, de modo que el cliente `WebClient` en vivo aparece exactamente cuando
    `lumen.exp.loan-origination.base-path` está configurado. `@ConditionalOnMissingBean` crea un
    bean solo cuando ningún bean de ese tipo existe ya, de modo que un stub registrado por una
    prueba siempre gana. Juntas permiten que el mismo jar `exp-lending` se ejecute contra un stub en
    una prueba y contra un servicio de dominio real en un despliegue, sin cambio de código — solo
    una propiedad.

!!! spring "Equivalente en Spring"
    En una app de Spring de toda la vida construirías a mano un `@Bean` `WebClient`, cablearías a
    mano su URL base y lo intercambiarías condicionalmente por un mock en las pruebas a mano. El
    patrón de Firefly es el mismo `WebClient`, pero el *SDK generado* normalmente suministra este
    adaptador: el `exp-lending` real inyecta un cliente generado a partir del contrato OpenAPI del
    dominio y cableado por una `ClientFactory`. La muestra del libro fabrica el adaptador a mano para
    que la junta sea rebanable, pero la forma — una propiedad de base-path, un bean condicional, un
    stub en pruebas — es exactamente la de producción.

## AppContext, AppSecurityContext y la suplantación de back-office

Dos tipos de la capa de aplicación se entretejen por todo lo que hace `@Secure`, y vale la pena
nombrarlos aunque la rebanada no los pueble. El starter resuelve un **`AppContext`** para cada
llamada — el sobre con ámbito de petición que lleva la identidad, el tenant y los metadatos de
correlación del llamante mientras fluye por el stack reactivo — y dentro de él un
**`AppSecurityContext`** que contiene el principal autenticado y los permisos contra los que
comprueba `@Secure`. Cuando la aplicación está encendida, el `SecurityAspect` lee el
`AppSecurityContext`, compara los permisos del llamante con los `permissions` declarados del método
y decide. En la rebanada viste el aspecto registrar *"No ApplicationExecutionContext found in method
arguments, skipping security check"* — ese es precisamente el camino desactivado; con la aplicación
encendida, ese contexto está presente y se consulta.

Este modelo de contexto es lo que hace posible una capa de experiencia de *back-office*. Un módulo
de back-office — una consola para agentes de soporte y analistas de riesgo — es simplemente otro
BFF construido sobre `starter-application`, pero sus llamantes actúan *en nombre de* los clientes.
El patrón de Firefly para eso es la **suplantación**: un operador autenticado, que posee un permiso
de back-office, asume el `AppSecurityContext` de un cliente durante el lapso de una llamada, de modo
que los servicios aguas abajo ven la petición como la del cliente mientras el rastro de auditoría
registra al operador que actuó. La mecánica — cómo se intercambia el token de un operador, cómo se
sella y audita el contexto suplantado — es ilustrativa aquí; el reactor de Lumen incluye el
`exp-lending` de cara al cliente, no un módulo de back-office. Conceptualmente, una llamada con
suplantación es una que intercambia el contexto de seguridad antes de componer:

```java
// Illustrative: a back-office handler impersonating a customer for one call.
@Secure(permissions = {"backoffice:application:read-as-customer"})
public Mono<ApplicationDetailDTO> getApplicationAsCustomer(UUID customerId, UUID applicationId) {
    return appContext.impersonate(customerId)                 // assume the customer's AppSecurityContext
            .then(applicationService.getApplication(applicationId)); // downstream sees the customer
}
```

La conclusión es estructural, no código que puedas ejecutar hoy: cada canal — una app móvil, un
front-end web, una consola de back-office — es su propia capa de experiencia sobre el mismo
starter, diferenciándose solo en qué `AppSecurityContext` llevan sus llamantes y qué permisos
`@Secure` controlan sus endpoints. El patrón de composición es idéntico; lo que cambia es la
identidad que fluye a través de `AppContext`.

!!! note "Término clave — AppContext / AppSecurityContext"
    `AppContext` es el contexto con ámbito de petición de la capa de aplicación — identidad, tenant,
    correlación — llevado sobre el stack reactivo sin un `ThreadLocal`, de la misma manera que el
    `ExecutionContext` de la capa de dominio (capítulo 10) lleva el estado de la saga.
    `AppSecurityContext` es su rebanada de seguridad: el principal autenticado y los permisos que
    `@Secure` evalúa. La suplantación de back-office funciona intercambiando el `AppSecurityContext`
    por el de un cliente objetivo, de modo que las llamadas aguas abajo se ejecutan con la identidad
    del cliente mientras el log de auditoría mantiene la del operador.

## Paso 6 — Probar la rebanada

La rebanada se verifica con una batería de nueve pruebas: cuatro pruebas de `WebTestClient` que
arrancan el contexto completo del BFF y lo manejan sobre HTTP, cuatro pruebas unitarias rápidas
sobre el servicio, y una prueba de humo. Desde el directorio `samples/lumen-lending`:

```text
mvn -q -pl exp-lending test
```

Deberías ver pasar las nueve:

```text
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Las pruebas web (`ApplicationControllerTest`) arrancan la aplicación real — controladores, los
manejadores anotados con `@Secure`, la cadena de filtros de seguridad y el `GlobalExceptionHandler`
de `fireflyframework-web` — y satisfacen la junta del SDK con un bean stub en memoria. Como ese stub
es un `LoanOriginationDomainClient`, el `@ConditionalOnMissingBean` sobre el cliente en vivo
(listado 17.9) impide que el adaptador `WebClient` se cree jamás, de modo que la rebanada se ejecuta
sin servicio de dominio y sin Docker. Los cuatro casos afirman la creación `201` con un
`ApplicationDetailDTO` mapeado, el viaje de ida y vuelta crear-luego-obtener, el detalle de problema
`404` que lleva `APPLICATION_NOT_FOUND` para un id desconocido, y el rechazo `400` de un importe
cero en el borde de validación.

Las pruebas de servicio (`ApplicationServiceTest`) se ejecutan sin contexto de Spring en absoluto —
construyen `ApplicationService` directamente sobre el stub. La que hay que leer de cerca es la
afirmación de idempotencia, porque clava la tesis central del capítulo:

```java
// From ApplicationServiceTest — the service derived the same key the test computed.
String expectedKey = IdempotencyKeys.of(
        "exp-lending", "create-application", "submit",
        productId.toString(), simulationId.toString(),
        "15000", "36", "PERSONAL");
// ...after createApplication(request) completes...
assertThat(stub.idempotencyKeys()).containsExactly(expectedKey);
```

La prueba recomputa la clave a partir de los mismos campos de negocio y afirma que el servicio
entregó esa clave exacta a través de la junta del SDK. Ese es el contrato de clave determinista,
verificado: misma petición lógica, misma clave, cada vez. El reactor entero son 33 pruebas en verde
— core 18, dominio 6, exp 9 — así que las nueve de aquí son la rebanada del BFF de un stack que está
probado de extremo a extremo.

!!! tip "Punto de control"
    Nueve pruebas en verde, sin servicio de dominio, sin Docker. Las cuatro pruebas web prueban que
    el controlador asegurado compone y renderiza detalles de problema; las cuatro pruebas de servicio
    prueban la validación, la clave determinista, el mapeo de no encontrado y el viaje de ida y
    vuelta. Recuerda que la aplicación de `@Secure` está desactivada para la rebanada mediante
    `firefly.application.security.enabled=false` — así que esta ejecución valida la composición y el
    cableado, y el capítulo 19 validará la autorización en el camino con la aplicación encendida.

## Paso 7 — Ejecutar el flujo en vivo exp → dominio → core

La rebanada probó el BFF en aislamiento. Ahora levanta el stack entero y observa un único POST de
canal viajar por las tres capas. Cada módulo es una app de Spring Boot independiente en su propio
puerto; arranca las tres (el orden no importa — las capas no fallan rápido ante un servicio aguas
abajo ausente):

```text
( cd core-lending-loan-origination   && mvn spring-boot:run ) &
( cd domain-lending-loan-origination && mvn spring-boot:run ) &
( cd exp-lending                     && mvn spring-boot:run ) &
```

Cada app registra `Started …Application in …` y `Netty started on port …` cuando está lista — core
en 8081, dominio en 8082, exp en 8080. Confirma que el BFF está sano:

```text
$ curl -s localhost:8080/actuator/health
{"status":"UP",...}
```

Ahora envía con POST una petición de canal al BFF. El cuerpo es la forma *de canal* del listado
17.3 — un `productId`, un `requestedAmount`, un `term`, un `purpose` y un `simulationId`:

```text
$ curl -s -X POST localhost:8080/api/v1/experience/lending/applications \
    -H 'Content-Type: application/json' \
    -d '{"productId":"11111111-1111-1111-1111-111111111111","requestedAmount":25000.00,"term":36,"purpose":"HOME_IMPROVEMENT","simulationId":"22222222-2222-2222-2222-222222222222"}'
```

Esa única llamada fluye de extremo a extremo: el BFF valida y deriva la clave, la junta `WebClient`
(listado 17.8) envía con POST al dominio en `http://localhost:8082/api/v1/applications` con la
cabecera `Idempotency-Key`, el `LoanOriginationController` del dominio ejecuta
`RegisterApplicationSaga`, el paso raíz de la saga escribe en el sistema de registro de core sobre
HTTP, y el id asignado por core vuelve a través de ambas juntas. El BFF responde `201 Created` con su
vista de canal:

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

Lee la respuesta contra el DTO de canal del listado 17.4: el `simulationId` se devuelve como eco
directo, el `status` es `SUBMITTED` (la saga lo envió, exactamente como hizo el core en el capítulo
2), y `applicationId` es el id que asignó el *core* — el BFF nunca lo acuñó. En la consola de la
capa de dominio puedes ver la saga conducir la escritura, el mismo log de orquestación que conociste
en los capítulos de dominio:

```text
[orchestration] started   name=RegisterApplicationSaga ... pattern=SAGA
[orchestration] step.success ... stepId=registerLoanApplication latencyMs=94
[orchestration] step.success ... stepId=proposeOffer
[orchestration] step.success ... stepId=registerApplicant
[orchestration] completed name=RegisterApplicationSaga ... success=true
```

La prueba de que realmente aterrizó en el sistema de registro es preguntar a core directamente,
usando el `applicationId` que devolvió el BFF:

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

El mismo `loanApplicationId`, el mismo `requestedAmount`, el mismo estado `SUBMITTED` — la petición
de canal se convirtió en una fila real del sistema de registro. Pero mira los campos desajustados,
porque esta es la parte honesta del flujo en vivo. El canal envió `term: 36` y
`purpose: "HOME_IMPROVEMENT"`, pero core almacenó `termMonths: 12`, `purpose: "GENERAL"`,
`currency: "EUR"` y un `applicantId` que el canal nunca suministró. Esos son *valores por defecto de
core*: la junta de escritura recortada entre dominio y core lleva solo el nombre del solicitante y el
importe, de modo que los campos que le importan al canal y que no tienen hueco en la junta mínima
aterrizan como los valores por defecto de core. El mapeo más rico campo por campo es el trabajo del
SDK *generado* en el servicio real; la junta del libro es intencionadamente mínima para que el flujo
sea rebanable. Lo que prueba la ejecución es estructural — el camino está cableado y vivo, exp →
dominio → core — no que cada campo del canal sobreviva al viaje.

!!! note "Término clave — la junta del SDK, en vivo"
    Una **junta** es la interfaz de cliente reactivo de la que una capa depende para alcanzar la
    siguiente capa (`LoanOriginationDomainClient` aquí). En las pruebas la satisface un stub en
    memoria; en un stack en marcha la satisface el adaptador respaldado por `WebClient` apuntado al
    servicio aguas abajo. El código del servicio es idéntico en cualquier caso — compone contra la
    interfaz — que es lo que permite que una prueba de rebanada y un despliegue en vivo ejerciten la
    *misma* lógica de composición. La junta es donde el desacoplamiento se vuelve ejecutable.

!!! tip "Punto de control"
    Tres apps levantadas, un POST a 8080, `201 SUBMITTED` de vuelta, y el mismo id legible
    directamente desde core en 8081 — ese es el camino en vivo `exp → dominio → core`. Cuando hayas
    terminado, libera los puertos con `lsof -ti:8080,8081,8082 | xargs kill`. Si el BFF devuelve un
    `502` con código `UPSTREAM_ERROR`, la capa de dominio no está levantada: eso es `onErrorMap`
    haciendo su trabajo, envolviendo un fallo de transporte como un error de negocio limpio.

## Lo que has construido {.recap}

- Una **capa de experiencia** sobre `fireflyframework-starter-application` más
  `fireflyframework-web` — un BFF sin estado que no tiene base de datos y compone el dominio sobre
  una junta del SDK, mapeando los resultados a sus propios DTOs de canal.
- Un **controlador reactivo asegurado** cuyos métodos `createApplication` y `getApplication` llevan
  `@Secure(permissions = ...)` — autorización declarativa, por endpoint, aplicada por el
  `SecurityAspect` del starter — y que no contienen código de manejo de errores.
- Un **`ApplicationService`** compositor que valida la petición en el borde, deriva una clave de
  idempotencia determinista, cruza la junta hacia el dominio y mapea los fallos aguas abajo a
  `BusinessException`s que el módulo web renderiza como detalles de problema RFC 7807.
- El patrón de **clave de idempotencia determinista** vía `IdempotencyKeys.of(...)` — un UUID basado
  en nombre (v3) sobre los campos estables de la petición, de modo que un envío reintentado deduplica
  aguas abajo **sin que el canal acuñe un id de recurso**.
- La **junta del SDK en vivo**: un `LoanOriginationDomainClient` respaldado por `WebClient` que envía
  con POST la petición de canal al dominio en `/api/v1/applications` llevando la cabecera
  `Idempotency-Key`, materializado por `@ConditionalOnProperty` sobre
  `lumen.exp.loan-origination.base-path` y retenido en las pruebas por `@ConditionalOnMissingBean`.
- Un relato honesto de **`@Secure`**: real e interceptado, pero con la aplicación desactivada tanto
  en la rebanada como en la ejecución local mediante `firefly.application.security.enabled=false`;
  más el modelo `AppContext`/`AppSecurityContext` y un uso de suplantación de back-office, mostrado de
  forma ilustrativa.
- Una batería de nueve pruebas que pasa — cuatro pruebas de rebanada web, cuatro pruebas unitarias de
  servicio, una prueba de humo — más la **ejecución en vivo `exp → dominio → core`** que devuelve
  `201 SUBMITTED` y aterriza una fila real en core (con algunos campos del canal llegando como valores
  por defecto de core a través de la junta mínima).

## Pruébalo tú mismo {.exercises}

1. **Prueba que la clave es determinista.** En `ApplicationServiceTest`, añade una prueba que
   construya dos valores `CreateApplicationRequest` con campos idénticos, llame a `createApplication`
   sobre cada uno y afirme que `stub.idempotencyKeys()` contiene la *misma* clave dos veces. Luego
   cambia un campo (el importe) en la segunda petición y afirma que las claves ahora difieren.
2. **Añade un endpoint de lista.** El módulo ya tiene `ApplicationSummaryDTO`. Añade un método
   `list` con `@GetMapping` a `ApplicationController` que devuelva un `Flux` de resúmenes, un método
   `listApplications` al servicio, y una prueba respaldada por stub que afirme que una solicitud
   creada aparece en la lista. Mantén el permiso `@Secure` consistente con el endpoint de lectura.
3. **Mapea un fallo aguas arriba.** Haz que el `submitApplication` del stub de prueba devuelva
   `Mono.error(new RuntimeException("boom"))` para una entrada, luego añade una prueba que afirme que
   el servicio expone una `BusinessException` con estado `BAD_GATEWAY` y código `UPSTREAM_ERROR` —
   probando que `onErrorMap` envuelve los fallos no de negocio. Luego ejecuta el stack en vivo con la
   capa de dominio *detenida* y envía con POST al BFF: confirma que obtienes el mismo
   `502 UPSTREAM_ERROR` de la junta `WebClient` real.
4. **Observa el cliente condicional.** Ejecuta `exp-lending` con la línea
   `lumen.exp.loan-origination.base-path` eliminada de `application.yml` y observa el arranque fallar
   al encontrar un `LoanOriginationDomainClient` (sin stub, sin base path, así que el bean condicional
   nunca aparece). Vuelve a poner la línea y confirma la línea de log
   `Building Loan Origination WebClient basePath=...`, luego explica en una frase por qué la prueba de
   rebanada sigue funcionando sin esa propiedad (pista: `@ConditionalOnMissingBean` y el stub de
   prueba).
5. **Traza el desajuste en vivo.** Ejecuta el stack completo, envía con POST una petición de canal con
   `term: 36` y `purpose: "HOME_IMPROVEMENT"`, luego obtén con GET el mismo id desde core en 8081.
   Enumera qué campos del canal sobrevivieron al viaje y cuáles llegaron como valores por defecto de
   core, y explica en una frase por qué — apuntando a la junta de escritura mínima entre dominio y
   core que este libro usa en lugar del SDK generado.
6. **Lee el log de seguridad desactivada.** Ejecuta `ApplicationControllerTest` y encuentra la línea
   `Intercepting @Secure method: createApplication`, luego la línea que dice que la comprobación de
   seguridad se omitió. Cambia `firefly.application.security.enabled` a `true` en el `application.yml`
   de prueba, vuelve a ejecutar, y explica qué cambia ahora en el comportamiento del aspecto (no
   necesitas hacer que la prueba pase — observa la diferencia).

## Adónde ir ahora

Tienes un canal que compone una llamada de dominio limpiamente — y has visto esa llamada viajar todo
el camino hasta el sistema de registro y volver. Pero un envío real rara vez es un solo paso:
registrar una solicitud, adjuntar al solicitante y proponer una oferta deben tener éxito o fallar
*juntos*. Viste pasar volando los tres pasos de la saga en el log de orquestación de arriba; el
capítulo 18 vuelve a la **saga** de la capa de dominio — la orquestación `@Saga` que ejecuta esos
pasos como un flujo atómico único y compensa en orden inverso cuando un paso falla. La clave de
idempotencia determinista que construiste aquí es exactamente lo que mantiene cada paso de la saga
seguro de reintentar sin duplicar el trabajo aguas abajo.
