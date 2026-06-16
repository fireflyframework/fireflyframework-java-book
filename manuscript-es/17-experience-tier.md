La capa core es la propietaria del sistema de registro. La capa de dominio la
orquesta con comandos, consultas y sagas. Ninguna de las dos está diseñada para
un móvil ni para una aplicación web. Un canal necesita una petición que pueda
enviar en un solo viaje de ida y vuelta, una respuesta que pueda renderizar sin
una segunda llamada y una respuesta a la pregunta "¿qué pasa cuando el usuario
pulsa *Enviar* dos veces?". Ese es el trabajo de la **capa de experiencia** — el
backend-for-frontend, o BFF — y es lo que vas a construir en este capítulo.

La capa de experiencia es deliberadamente delgada. No tiene base de datos ni
posee reglas de negocio propias. Todo su propósito es la *composición*: dar forma
a una petición de canal, llamar al dominio a través de la junta del SDK y mapear
el resultado a una vista ligera que el front-end realmente quiere. Todo lo que
tiene estado vive detrás de ella. Lo que aporta son las preocupaciones de borde
que un canal necesita — validación en la frontera, autorización declarativa,
idempotencia determinista para que un envío reintentado no cree dos solicitudes,
y un modelo de respuesta desacoplado de las interioridades del dominio.

En este capítulo recortas el módulo `exp-lending` de Lumen: un controlador
reactivo asegurado, el servicio que valida y compone, el helper de clave de
idempotencia determinista que hace seguro un reintento, y los DTO de canal que
son el propio contrato de cable del BFF. Una batería de nueve tests demuestra que
arranca, valida, deduplica y mapea — sin servicio de dominio y sin Docker.
Empecemos por lo que hace que un módulo sea una capa de experiencia.

## El starter de aplicación

Cada capa en Lumen se apoya en un *starter* de Firefly — un starter de Boot que
enciende exactamente las capacidades que esa capa necesita. La capa core usa
`starter-core`; la capa de dominio usa `starter-domain`. La capa de experiencia
usa `fireflyframework-starter-application`, el starter construido para la capa que
mira hacia los clientes. Su única línea de dependencia trae las preocupaciones de
la capa de aplicación — seguridad declarativa, caching, la fontanería de CQRS, la
factoría de clientes — de modo que el BFF arranca con ellas ya cableadas.

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

Dos dependencias y el módulo es una capa de experiencia. `starter-application`
trae la maquinaria de la capa de aplicación; `fireflyframework-web` trae los
mismos helpers de web reactiva que conociste en el Capítulo 6 — el
`GlobalExceptionHandler` que renderiza los problem details de RFC 7807, el filtro
de idempotencia, el estampado de transacciones. Como el módulo web está presente,
cada `BusinessException` que lanza el BFF se convierte en una respuesta limpia de
problem detail sin código de manejador, exactamente como ocurría en la capa core.

!!! note "Término clave — el starter de aplicación (`fireflyframework-starter-application`)"
    El starter de Firefly Boot para la **capa de experiencia/aplicación** — la capa
    que compone servicios aguas abajo para un canal. Autoconfigura la seguridad
    declarativa (`@Secure`), el caching de resultados, los buses de CQRS y la
    factoría de clientes que construye los clientes SDK generados, de modo que un
    módulo BFF obtiene las capacidades orientadas al canal solo por la presencia de
    la dependencia. El `starter-core` de la capa core y el `starter-domain` de la
    capa de dominio son sus hermanos; cada capa elige el starter que encaja con su
    trabajo.

## Paso 1 — Un controlador reactivo asegurado

El controlador es la puerta de entrada del canal. Es un `@RestController` reactivo
sin más — `@PostMapping` para crear, `@GetMapping` para leer uno — y, como el
controlador core del Capítulo 6, no lleva manejo de errores ni envoltorio. Lo
nuevo aquí es la anotación `@Secure` en cada método: autorización declarativa, por
endpoint, que el starter de aplicación hace cumplir.

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

Lee primero los dos métodos y luego la seguridad. `createApplication` toma un
`@Valid @RequestBody CreateApplicationRequest`, se lo entrega al servicio y mapea
el resultado a un `ResponseEntity` `201 Created`. `getApplication` lee uno por id
y lo mapea a `200 OK`. Ambos devuelven un `Mono` de un `ResponseEntity`, de modo
que el BFF es no bloqueante desde el socket hacia dentro, y ambos delegan de
inmediato — el controlador no contiene lógica. Ese `@Valid` arma la Bean
Validation sobre la petición en el borde mismo, la primera línea de defensa antes
de que nada cruce hacia el servicio.

La anotación `@Secure` es la aportación de la capa de experiencia. `@Secure(permissions =
{"lending:application:create"})` declara que quien llama debe poseer el permiso
`lending:application:create` para invocar `createApplication`; el método de
lectura requiere `lending:application:read`. No escribes un `if` que comprueba un
principal, y no configuras una regla de patrón de URL en una cadena de filtros.
Declaras el permiso en el método, y el `SecurityAspect` del starter de aplicación
intercepta la llamada y lo hace cumplir.

!!! note "Término clave — seguridad declarativa (`@Secure`)"
    `@Secure` es la anotación de autorización a nivel de método de la capa de
    aplicación. Declaras los `permissions` (y opcionalmente roles, una expresión o
    un alcance de tenant) que requiere un método; el `SecurityAspect` del starter —
    un advice de AOP — intercepta el método anotado, resuelve el `AppSecurityContext`
    de quien llama y, o bien procede, o bien rechaza antes de que tu código se
    ejecute. La autorización es una *declaración sobre el manejador*, no una regla
    desperdigada por una cadena de filtros, así que el contrato es visible justo
    donde se define el método.

!!! spring "Equivalente en Spring"
    `@Secure` cumple el papel que cumple `@PreAuthorize` de Spring Security, pero es
    una anotación de Firefly evaluada por el `SecurityAspect` del starter de
    aplicación, ajustada por las propiedades `firefly.application.security.*` y
    consistente en cada BFF de la flota. La cadena de filtros HTTP circundante sigue
    siendo Spring Security de fábrica con `@EnableWebFluxSecurity` — el
    `WebSecurityConfig` de Lumen simplemente permite todos los intercambios y
    desactiva los ruidosos valores por defecto de REST sin estado (HTTP Basic, login
    por formulario, CSRF), de modo que la autorización de *método*, y no la cadena de
    filtros, es el único lugar donde vive la autorización.

### `@Secure` es real, pero está desactivado en el test de slice

Sé honesto sobre lo que ejercita el test de slice. Las anotaciones `@Secure` del
controlador son código de producción real — el `SecurityAspect` intercepta ambos
métodos en tiempo de ejecución, lo cual puedes ver en el log del test
(`Intercepting @Secure method: createApplication`). Pero el test de slice no acuña
tokens ni levanta un centro de seguridad. En su lugar, conmuta una propiedad en
`src/test/resources/application.yml`:

```yaml
firefly:
  application:
    security:
      enabled: false
```

Con `firefly.application.security.enabled=false`, el aspecto hace un
cortocircuito — registra que la seguridad está desactivada y deja pasar la
llamada — de modo que el slice de `WebTestClient` puede manejar el BFF sin
autenticación. La anotación está presente y es interceptada; solo el *cumplimiento*
está apagado. En producción se mantiene la propiedad en `true` y los permisos se
comprueban de verdad. El Capítulo 19 desarrolla la historia completa de
seguridad — cómo se rellena el `AppSecurityContext`, cómo se resuelven los
permisos, cómo llega el token. Aquí, trata `@Secure` como cableado y visible, con
el cumplimiento aparcado para el slice.

!!! warning "El slice demuestra el cableado y la composición, no la autorización"
    Como el test fija `security.enabled=false`, una ejecución en verde *no* demuestra
    que `lending:application:create` se haga cumplir. Demuestra que el controlador
    está asegurado-por-anotación, que la petición valida, que el servicio compone y
    que los errores se renderizan como problem details. No leas el slice en verde
    como un test de autorización — eso es trabajo del Capítulo 19, contra el camino
    con el cumplimiento activado.

## Paso 2 — Los DTO de canal

El BFF nunca devuelve un tipo de dominio por el cable. Mapea la respuesta del
dominio a su propio record de *canal*, de modo que el front-end depende del
contrato de la capa de experiencia y no de las interioridades del dominio. Ese
desacoplamiento es todo el sentido de un backend-for-frontend: puedes remodelar el
modelo del SDK de dominio sin romper una sola pantalla de la aplicación, porque la
vista de canal se interpone entre ambos.

La petición que el canal envía es `CreateApplicationRequest`, un record validado
en el borde por restricciones ordinarias de Bean Validation.

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

`productId`, `requestedAmount` y `term` son obligatorios y están restringidos;
`purpose` y `simulationId` son opcionales. El `@Valid` sobre el `@RequestBody` del
controlador dispara estas restricciones antes de que se ejecute el cuerpo del
manejador, de modo que un importe cero se rechaza con un `400` en la frontera —
verás exactamente eso en la ejecución. El `simulationId` es un enlace suave de
vuelta a la simulación que produjo esta solicitud; el canal lo envía para que el
BFF pueda devolverlo en el eco.

La respuesta que el canal renderiza es `ApplicationDetailDTO` — la propia vista
completa del BFF, moldeada para una pantalla, no para el almacenamiento del
dominio.

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

Este es el record que devuelve el controlador y contra el que el test hace sus
aserciones. Fíjate en que devuelve `simulationId` directamente en el eco, de modo
que el front-end puede persistir el enlace de trazabilidad sin una llamada de
seguimiento — una pequeña decisión de composición que pertenece al canal, no al
dominio. El módulo también mantiene un `ApplicationSummaryDTO` para respuestas de
lista; el patrón es idéntico, una forma más ligera para una pantalla distinta. El
dominio puede devolver veinte campos; el BFF devuelve los ocho que necesita una
pantalla de detalle, y no se filtra nada que el canal no haya pedido.

!!! note "Término clave — DTO de canal"
    Un **DTO de canal** es un tipo de vista propiedad de la capa de experiencia y
    moldeado para una superficie de cliente — una pantalla de detalle, una fila de
    lista. Es el contrato de cable del BFF, deliberadamente separado del modelo del
    SDK de dominio para que ambos puedan evolucionar de forma independiente. El
    servicio mapea las respuestas del dominio a DTO de canal; el front-end solo ve
    siempre estos, que es lo que permite al dominio cambiar sus interioridades sin
    una release coordinada del cliente.

## Paso 3 — El servicio: validar, derivar una clave, componer, mapear

El controlador delega todo a `ApplicationService`, y aquí es donde la capa de
experiencia se gana su nombre. El servicio hace cuatro cosas y solo cuatro: valida
la petición de canal, deriva una clave de idempotencia *determinista* de los
campos estables de la petición, cruza la junta del SDK hacia el dominio, y mapea
cualquier fallo aguas abajo a una `BusinessException` que el módulo web pueda
renderizar. Sin persistencia, sin reglas de negocio — pura composición.

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

Recorre la cadena reactiva. `Mono.fromCallable(() -> validate(request))` ejecuta
la validación defensiva de forma perezosa, dentro del pipeline reactivo, de modo
que un fallo de validación se convierte en una señal `onError` en lugar de una
excepción lanzada que escapa del publicador. `validate` vuelve a comprobar la
petición aunque el `@Valid` del controlador ya se ejecutó — el BFF no se fía de
que toda ruta de llamada haya validado, así que protege la propia junta,
levantando una `BusinessException(BAD_REQUEST, "VALIDATION_FAILED", ...)` ante un
campo inválido.

Luego viene la clave, el corazón del capítulo, en la que nos detendremos en el
siguiente paso. Con la clave en mano, `domainClient.submitApplication(validated, submitKey)`
cruza la junta del SDK — el mismo tipo de puerto reactivo que conociste en el
Capítulo 10, aquí llamado `LoanOriginationDomainClient`, que hace de sustituto del
SDK de dominio generado para que el ejemplo se ejecute sin servicio de dominio.
Finalmente `.onErrorMap(this::isNotBusinessException, this::toUpstreamError)`
traduce cualquier fallo *no de negocio* — un parpadeo de transporte, un error de
deserialización — a una `BusinessException(BAD_GATEWAY, "UPSTREAM_ERROR", ...)`,
dejando intactas las `BusinessException` que el dominio ya produjo. Esa es la
disciplina de mapeo de errores: el canal nunca filtra una traza de pila cruda de
aguas abajo; o bien deja pasar un error de negocio con significado, o bien envuelve
el resto como un `502` limpio.

El camino de lectura es simétrico, y muestra el mapeo de no-encontrado:

::: listing exp-lending/src/main/java/com/firefly/lumen/exp/service/ApplicationService.java | Listado 17.6 — el camino de lectura mapea una solicitud ausente a un problem detail 404
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

El `switchIfEmpty` es el mismo idiom que usó el Capítulo 6: cuando el dominio
devuelve un `Mono` vacío, se sustituye por una señal de error — aquí una
`BusinessException(NOT_FOUND, "APPLICATION_NOT_FOUND", ...)`. El
`GlobalExceptionHandler` del módulo web la convierte en un problem detail `404` que
porta el código `APPLICATION_NOT_FOUND`, sobre el que el test de slice hace sus
aserciones en el cable. El BFF lanza una excepción de negocio *semántica* con un
código estable y un estado; el framework renderiza el cuerpo de RFC 7807. Tú
escribes el significado; el framework escribe el JSON.

!!! note "Término clave — BusinessException"
    `org.fireflyframework.web.error.exceptions.BusinessException` es el portador del
    framework para un fallo *con significado*: empareja un `HttpStatus` con un código
    estable, legible por máquina (`APPLICATION_NOT_FOUND`, `VALIDATION_FAILED`,
    `UPSTREAM_ERROR`) y un mensaje humano. El `GlobalExceptionHandler` del módulo web
    la renderiza como un problem detail de RFC 7807, de modo que un cliente de canal
    ve la misma forma de error desde cada servicio Firefly. Lanzar una es la forma en
    que un servicio dice "esto falló, y aquí está la razón de cara al cliente" sin
    escribir un manejador de excepciones.

## Paso 4 — La clave de idempotencia determinista

Aquí está el patrón insignia de la capa de experiencia, y la razón por la que
existe este capítulo. Un envío de canal puede reintentarse — el usuario hace doble
pulsación, la red pierde la respuesta después de que el servidor la procesó, un
cliente móvil reproduce una petición en cola al reconectarse. Si cada reintento
creara una solicitud de préstamo nueva, una pulsación se convertiría en tres. La
solución es una *clave de idempotencia*: un token estable adjunto a la petición
para que la capa de aguas abajo reconozca un reintento y devuelva el resultado
original en lugar de crear un duplicado.

La pregunta difícil es quién acuña la clave. Si el canal genera un UUID aleatorio
por intento, un reintento lleva una *nueva* clave y no deduplica nada. Si el BFF
acuña un id de recurso por adelantado, ha asumido una responsabilidad sobre la
identidad que pertenece al sistema de registro. El patrón de Firefly esquiva
ambos: la clave se *deriva* de los campos de negocio estables de la petición, de
modo que la misma petición lógica siempre produce la misma clave — sin que nadie
acuñe un id. Esa derivación vive en un pequeño helper.

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

El helper entero es un solo método. `IdempotencyKeys.of(parts...)` une sus
argumentos con `':'`, codifica el resultado como UTF-8 y ejecuta
`UUID.nameUUIDFromBytes(...)` — el UUID *versión 3* (basado en nombre) de RFC 4122
del JDK. Un UUID basado en nombre es una función pura de su entrada: la misma
cadena unida siempre produce el mismo UUID, y una cadena distinta produce uno
distinto. La salida es una cadena UUID válida, así que encaja directamente en una
cabecera HTTP `Idempotency-Key` estándar. Las partes nulas se fuerzan al literal
`"null"` para que un campo opcional ausente nunca lance.

Ahora vuelve a leer cómo lo llamó el servicio en el Listado 17.5. Las partes son
un *espacio de nombres* (`"exp-lending"`, `"create-application"`, `"submit"`) más
los campos estables de la petición — producto, simulación, importe, plazo,
propósito. Dos envíos de la *misma* solicitud lógica producen partes idénticas, de
ahí una clave idéntica, de ahí una sola solicitud aguas abajo. Cambia cualquier
campo — un importe distinto — y la clave cambia, porque es una petición
genuinamente distinta. El canal nunca acuña un id de recurso; calcula una huella
determinista de la petición, y la capa de dominio deduplica sobre ella. El test
del servicio demuestra exactamente esto: recalcula la clave esperada de forma
independiente y comprueba que el servicio entregó esa misma clave a la junta del
SDK.

!!! note "Término clave — clave de idempotencia determinista"
    Una clave derivada como función pura de las entradas de negocio estables de una
    petición, de modo que un reintento de la *misma* operación lógica produce la
    *misma* clave. El `IdempotencyKeys.of(...)` de Firefly construye una como un UUID
    basado en nombre (v3) sobre las entradas unidas. Como se deriva en lugar de
    acuñarse, el canal puede permanecer sin estado — nunca tiene que recordar una
    clave entre intentos ni reservar un id de recurso — y la capa de aguas abajo aún
    deduplica de forma fiable. Entrada estable, clave estable; entrada distinta, clave
    distinta.

!!! spring "Equivalente en Spring"
    Este es el lado productor de la misma historia de idempotencia que el Capítulo 6
    mostró desde el lado consumidor. Allí, el `IdempotencyWebFilter` de
    `fireflyframework-web` cacheaba y reproducía una respuesta indexada por una
    cabecera `X-Idempotency-Key`. Aquí la capa de experiencia *calcula* esa clave de
    forma determinista y la reenvía a través de la junta del SDK, de modo que la
    deduplicación ocurre aguas abajo. Spring puro no te da ninguna de las dos mitades;
    Firefly aporta el filtro que honra la clave y el helper que la deriva, y ambos se
    encuentran en la cabecera.

!!! tip "Punto de control"
    Traza un reintento mentalmente. Un cliente envía el mismo cuerpo dos veces. El
    controlador valida ambos. El servicio calcula `IdempotencyKeys.of(...)` sobre los
    mismos campos las dos veces, así que `submitKey` es idéntica en el intento uno y en
    el intento dos. La capa de dominio ve la misma clave, reconoce la segunda llamada
    como un reintento y devuelve la solicitud original — una fila, no dos — y el BFF
    nunca acuñó un id para que eso funcione. Ese es el patrón entero.

## AppContext, AppSecurityContext y la suplantación en back-office

Dos tipos de la capa de aplicación atraviesan todo lo que hace `@Secure`, y vale
la pena nombrarlos aunque el slice no los rellene. El starter resuelve un
**`AppContext`** para cada llamada — el envoltorio de alcance de petición que porta
la identidad de quien llama, el tenant y los metadatos de correlación mientras
fluye por la pila reactiva — y dentro de él un **`AppSecurityContext`** que
contiene el principal autenticado y los permisos contra los que comprueba
`@Secure`. Cuando el cumplimiento está activado, el `SecurityAspect` lee el
`AppSecurityContext`, compara los permisos de quien llama con los `permissions`
declarados por el método, y decide. En el slice viste al aspecto registrar *"No
ApplicationExecutionContext found in method arguments, skipping security check"* —
ese es precisamente el camino desactivado; con el cumplimiento activado, ese
contexto está presente y se consulta.

Este modelo de contexto es lo que hace posible una capa de experiencia de
*back-office*. Un módulo de back-office — una consola para agentes de soporte y
analistas de riesgo — es simplemente otro BFF construido sobre
`starter-application`, pero quienes lo llaman actúan *en nombre de* los clientes.
El patrón de Firefly para eso es la **suplantación**: un operador autenticado, que
posee un permiso de back-office, asume el `AppSecurityContext` de un cliente
durante la duración de una llamada, de modo que los servicios de aguas abajo ven
la petición como la del cliente mientras el rastro de auditoría registra al
operador que actuó. La mecánica — cómo se intercambia el token de un operador, cómo
se estampa y audita el contexto suplantado — es ilustrativa aquí; el reactor de
Lumen entrega el `exp-lending` orientado al cliente, no un módulo de back-office.
Conceptualmente, una llamada con suplantación es una que intercambia el contexto
de seguridad antes de componer:

```java
// Illustrative: a back-office handler impersonating a customer for one call.
@Secure(permissions = {"backoffice:application:read-as-customer"})
public Mono<ApplicationDetailDTO> getApplicationAsCustomer(UUID customerId, UUID applicationId) {
    return appContext.impersonate(customerId)                 // assume the customer's AppSecurityContext
            .then(applicationService.getApplication(applicationId)); // downstream sees the customer
}
```

La conclusión es estructural, no código que puedas ejecutar hoy: cada canal — una
aplicación móvil, un front-end web, una consola de back-office — es su propia capa
de experiencia sobre el mismo starter, diferenciándose solo en qué
`AppSecurityContext` portan quienes la llaman y qué permisos `@Secure` controlan
sus endpoints. El patrón de composición es idéntico; lo que cambia es la identidad
que fluye a través de `AppContext`.

!!! note "Término clave — AppContext / AppSecurityContext"
    `AppContext` es el contexto de alcance de petición de la capa de aplicación —
    identidad, tenant, correlación — portado en la pila reactiva sin un `ThreadLocal`,
    igual que el `ExecutionContext` de la capa de dominio (Capítulo 10) porta el
    estado de la saga. `AppSecurityContext` es su rebanada de seguridad: el principal
    autenticado y los permisos que evalúa `@Secure`. La suplantación en back-office
    funciona intercambiando el `AppSecurityContext` por el de un cliente objetivo, de
    modo que las llamadas de aguas abajo se ejecutan con la identidad del cliente
    mientras el log de auditoría mantiene la del operador.

## Ejecútalo

El slice se verifica con una batería de nueve tests: cuatro tests de
`WebTestClient` que arrancan el contexto completo del BFF y lo manejan por HTTP,
cuatro tests unitarios rápidos sobre el servicio, y un test de humo. Desde el
directorio `samples/lumen-lending`:

```text
mvn -q -pl exp-lending test
```

Deberías ver pasar los nueve:

```text
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Los tests web (`ApplicationControllerTest`) arrancan la aplicación real —
controladores, los manejadores anotados con `@Secure`, la cadena de filtros de
seguridad, y el `GlobalExceptionHandler` de `fireflyframework-web` — y satisfacen
la junta del SDK con un bean `StubLoanOriginationDomainClient` en memoria, de modo
que no hay servicio de dominio ni Docker. Comprueban el `201` de creación con un
`ApplicationDetailDTO` mapeado, el viaje de ida y vuelta crear-luego-leer, el
problem detail `404` que porta `APPLICATION_NOT_FOUND` para un id desconocido, y el
rechazo `400` de un importe cero en el borde de validación.

Los tests del servicio (`ApplicationServiceTest`) se ejecutan sin ningún contexto
de Spring en absoluto — construyen `ApplicationService` directamente sobre el stub.
El que conviene leer con atención es la aserción de idempotencia, porque fija la
afirmación central del capítulo:

```java
// From ApplicationServiceTest — the service derived the same key the test computed.
String expectedKey = IdempotencyKeys.of(
        "exp-lending", "create-application", "submit",
        productId.toString(), simulationId.toString(),
        "15000", "36", "PERSONAL");
// ...after createApplication(request) completes...
assertThat(stub.idempotencyKeys()).containsExactly(expectedKey);
```

El test recalcula la clave a partir de los mismos campos de negocio y comprueba que
el servicio entregó esa clave exacta a través de la junta del SDK. Ese es el
contrato de la clave determinista, verificado: misma petición lógica, misma clave,
siempre.

!!! tip "Punto de control"
    Nueve tests en verde, sin servicio de dominio, sin Docker. Los cuatro tests web
    demuestran que el controlador asegurado compone y renderiza problem details; los
    cuatro tests del servicio demuestran la validación, la clave determinista, el
    mapeo de no-encontrado y el viaje de ida y vuelta. Recuerda que el cumplimiento de
    `@Secure` está desactivado para el slice vía
    `firefly.application.security.enabled=false` — así que esta ejecución valida la
    composición y el cableado, y el Capítulo 19 validará la autorización en el camino
    con el cumplimiento activado.

## Lo que has construido {.recap}

- Una **capa de experiencia** sobre `fireflyframework-starter-application` más
  `fireflyframework-web` — un BFF sin estado que no posee base de datos y compone el
  dominio a través de una junta del SDK, mapeando los resultados a sus propios DTO
  de canal.
- Un **controlador reactivo asegurado** cuyos métodos `createApplication` y
  `getApplication` llevan `@Secure(permissions = ...)` — autorización declarativa,
  por endpoint, que hace cumplir el `SecurityAspect` del starter — y que no
  contienen código de manejo de errores.
- Un **`ApplicationService`** que compone, que valida la petición en el borde,
  deriva una clave de idempotencia determinista, cruza la junta hacia el dominio, y
  mapea los fallos de aguas abajo a `BusinessException` que el módulo web renderiza
  como problem details de RFC 7807.
- El patrón de **clave de idempotencia determinista** vía `IdempotencyKeys.of(...)`
  — un UUID basado en nombre (v3) sobre los campos estables de la petición, de modo
  que un envío reintentado deduplica aguas abajo **sin que el canal acuñe un id de
  recurso**.
- Un relato honesto sobre **`@Secure`**: real e interceptado, pero con el
  cumplimiento desactivado en el slice vía
  `firefly.application.security.enabled=false`; más el modelo
  `AppContext`/`AppSecurityContext` y un uso de suplantación en back-office, mostrado
  de forma ilustrativa.
- Una batería de nueve tests que pasa — cuatro tests de slice web, cuatro tests
  unitarios de servicio, un test de humo — que demuestra que el BFF arranca, valida,
  deduplica y mapea sin servicio de dominio y sin Docker.

## Pruébalo tú mismo {.exercises}

1. **Demuestra que la clave es determinista.** En `ApplicationServiceTest`, añade un
   test que construya dos valores `CreateApplicationRequest` con campos idénticos,
   llame a `createApplication` en cada uno, y compruebe que `stub.idempotencyKeys()`
   contiene la *misma* clave dos veces. Luego cambia un campo (el importe) en la
   segunda petición y comprueba que las claves ahora difieren.
2. **Añade un endpoint de lista.** El módulo ya tiene `ApplicationSummaryDTO`. Añade
   un método `list` con `@GetMapping` a `ApplicationController` que devuelva un
   `Flux` de resúmenes, un método `listApplications` al servicio, y un test
   respaldado por el stub que compruebe que una solicitud creada aparece en la lista.
   Mantén el permiso `@Secure` consistente con el endpoint de lectura.
3. **Mapea un fallo de aguas arriba.** Haz que
   `StubLoanOriginationDomainClient.submitApplication` devuelva
   `Mono.error(new RuntimeException("boom"))` para una entrada, y luego añade un test
   que compruebe que el servicio expone una `BusinessException` con estado
   `BAD_GATEWAY` y código `UPSTREAM_ERROR` — demostrando que `onErrorMap` envuelve
   los fallos que no son de negocio.
4. **Lee el log de seguridad desactivada.** Ejecuta `ApplicationControllerTest` y
   encuentra la línea `Intercepting @Secure method: createApplication`, luego la
   línea que dice que se omitió la comprobación de seguridad. Conmuta
   `firefly.application.security.enabled` a `true` en el `application.yml` del test,
   vuelve a ejecutar, y explica qué cambia ahora en el comportamiento del aspecto (no
   necesitas hacer que el test pase — observa la diferencia).
5. **Traza un reintento de extremo a extremo.** Partiendo de un cuerpo enviado dos
   veces, anota cada campo que alimenta a `IdempotencyKeys.of(...)` en
   `createApplication`, y luego explica en una frase por qué un `purpose` cambiado
   produce una clave distinta mientras que dejar un campo opcional sin rellenar aún
   produce una clave usable (recuerda la coerción de null a `"null"`).

## Adónde ir ahora

Tienes un canal que compone limpiamente una llamada de dominio. Pero un envío real
rara vez es una sola llamada — registrar una solicitud, adjuntar al solicitante, y
proponer una oferta deben tener éxito o fallar *juntos*. El Capítulo 18 vuelve a la
**saga** de la capa de dominio: la orquestación `@Saga` que ejecuta esos pasos como
un único flujo atómico y compensa en reversa cuando un paso falla. La clave de
idempotencia determinista que construiste aquí es exactamente lo que mantiene cada
paso de la saga seguro para reintentar sin duplicar el trabajo de aguas abajo.
