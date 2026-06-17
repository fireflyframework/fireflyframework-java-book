A estas alturas tienes un servicio que arranca, un POM padre que lo mantiene
coherente en versiones y una idea clara de los tipos reactivos que todo habla. Es
hora de darle a Lumen Lending una superficie que un cliente pueda llamar. En este
capítulo construyes la primera superficie HTTP del núcleo de originación de
préstamos: un `@RestController` reactivo que crea, recupera y lista solicitudes de
préstamo, servido en el puerto `8081` contra H2 en memoria.

El controlador en sí es pequeño: tres métodos, sin código de manejo de errores, sin
fontanería de logging, sin envoltorio. Esa pequeñez es justamente la idea. El módulo
web que Firefly añade a Spring WebFlux hace el trabajo transversal *alrededor* de tu
manejador: los fallos de validación y los recursos ausentes vuelven como respuestas
estándar de tipo problem-detail RFC 7807 (enriquecidas con el contexto de traza), los
reintentos se deduplican, cada respuesta se sella con un id de transacción y los datos
de identificación personal se enmascaran en los logs, todo sin que aparezca una sola
línea de ello en el controlador. Tú escribes los tres métodos de negocio; el framework
escribe la consistencia.

Construiremos el controlador y sus DTOs, nos apoyaremos en validadores conscientes de
las finanzas para la petición, lanzaremos una excepción semántica para el caso de no
encontrado, observaremos cómo los filtros del framework registran una petición antes de
que se ejecute tu manejador, y luego ejecutaremos la prueba de rebanada que demuestra
que los problem details `400` y `404` vuelven exactamente como se prometió.

## Paso 1 — Un controlador reactivo, y nada más

Abre el núcleo de originación de préstamos y mira su única clase web. Es un
`@RestController` de WebFlux de manual: `@RequestMapping` para la ruta base, inyección
por constructor (aquí mediante el `@RequiredArgsConstructor` de Lombok) y métodos que
devuelven `Mono` y `Flux` en lugar de valores desnudos.

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/web/LoanApplicationController.java | Listado 6.1 — toda la superficie web: tres métodos reactivos
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

Léelo despacio, porque cada decisión es deliberada.

El método `create` devuelve `Mono<LoanApplicationResponse>` y lleva
`@ResponseStatus(HttpStatus.CREATED)`, de modo que un POST satisfactorio responde
`201 Created` con el nuevo recurso como cuerpo. El `@Valid` sobre el `@RequestBody` es
lo que arma la validación; más sobre esto en el Paso 3. `getById` devuelve una solicitud
o nada; `list` devuelve un `Flux`, el publicador de cero-a-muchos, y acepta un filtro
opcional `?status=` a través de `@RequestParam(required = false)`. Como el parámetro está
tipado como `ApplicationStatus`, WebFlux convierte por ti la cadena de consulta al enum y
rechaza un valor desconocido antes de que se ejecute tu código.

Lo que *no* está aquí importa tanto como lo que sí está. No hay `try`/`catch`, ni
envoltorio `ResponseEntity`, ni `@ExceptionHandler`, ni logging. El controlador delega en
un `LoanApplicationService` y devuelve el publicador que recibe. El framework se suscribe
en el borde cuando escribe la respuesta, así que nunca llamas tú a `.subscribe()`:
compones y devuelves.

¿Por qué puede el cuerpo del método ser una sola línea que simplemente reenvía el `Mono`?
Porque en WebFlux el *valor de retorno* es una promesa, no un resultado. El controlador
entrega un publicador no iniciado; el `DispatcherHandler` de WebFlux es el suscriptor, y
solo tira cuando está listo para serializar el cuerpo a la respuesta. Si hubieras llamado
a `service.create(request).block()` habrías colapsado esa promesa sobre un hilo del bucle
de eventos, el pecado capital del que advertían los capítulos anteriores. Devolver el
publicador mantiene todo el camino perezoso y no bloqueante de extremo a extremo.

!!! spring "Equivalente en Spring"
    Cada anotación aquí es Spring WebFlux puro: `@RestController`,
    `@RequestMapping`, `@PostMapping`, `@GetMapping`, `@PathVariable`,
    `@RequestParam`, `@RequestBody`, `@Valid`, `@ResponseStatus`. El par
    `@Tag`/`@Operation` es springdoc OpenAPI. Si has escrito un controlador de WebFlux,
    has escrito este. Lo que Firefly cambia no es el controlador: es todo lo que le
    ocurre a la petición y a la respuesta *alrededor* de él, que es de lo que trata el
    resto del capítulo.

## Paso 2 — El DTO de respuesta

El controlador devuelve un `LoanApplicationResponse`, nunca la entidad de persistencia.
Mantener un tipo de vista dedicado significa que el contrato del cable queda desacoplado
de la tabla: puedes remodelar el almacenamiento sin romper a los clientes y nunca filtras
por accidente un campo interno. Es un `record`, así que es inmutable y se serializa
directamente a JSON sin getters que escribir.

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/dto/LoanApplicationResponse.java | Listado 6.2 — la vista de respuesta, un record inmutable y sencillo
public record LoanApplicationResponse(
        UUID loanApplicationId,
        UUID applicationNumber,
        UUID applicantId,
        BigDecimal requestedAmount,
        String currency,
        Integer termMonths,
        String purpose,
        ApplicationStatus status,
        String decisionReason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
:::

Un DTO no tiene nada de reactivo: es la forma de un valor. El `Mono` y el `Flux` del
controlador son publicadores *de* este record; el record en sí es solo datos. Estos son
los nombres exactos de los campos que un cliente ve en el cable:
`loanApplicationId`, `applicationNumber`, `applicantId`, `requestedAmount`,
`currency`, `termMonths`, `purpose`, `status`, `decisionReason`, `createdAt`,
`updatedAt`. Tenlos presentes: los compararás con el JSON en vivo en un momento, y son
sobre lo que la prueba del controlador hace sus aserciones.

Fíjate en `decisionReason` en particular: es `null` en una solicitud recién creada y solo
se rellena una vez que se toma una decisión (el motor de reglas del Capítulo 13 sería el
que lo poblaría en el servicio completo). El tipo de vista ya lleva el campo para que el
contrato no cambie cuando aterrice esa capacidad.

## Paso 3 — Validación con restricciones conscientes de las finanzas

Una solicitud de préstamo lleva dinero, una moneda y un plazo, y la petición debe
rechazarse —limpiamente, antes de que se ejecute cualquier lógica de negocio— si alguno
de ellos es un disparate. Ese es el trabajo del DTO de petición de creación. Mezcla
restricciones ordinarias de Jakarta Bean Validation con dos restricciones específicas de
finanzas que Firefly distribuye en `fireflyframework-validators`.

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/dto/CreateLoanApplicationRequest.java | Listado 6.3 — la petición de creación, validada por Jakarta más restricciones financieras
public record CreateLoanApplicationRequest(

        @NotNull(message = "Applicant ID is required")
        UUID applicantId,

        @NotNull(message = "Requested amount is required")
        @ValidAmount(min = 0.01, message = "Requested amount must be a positive monetary value")
        BigDecimal requestedAmount,

        @NotBlank(message = "Currency is required")
        @ValidCurrencyCode(message = "Currency must be a valid ISO-4217 code")
        String currency,

        @NotNull(message = "Term is required")
        @Positive(message = "Term must be a positive number of months")
        Integer termMonths,

        @NotBlank(message = "Loan purpose is required")
        String purpose
) {
}
:::

`@NotNull`, `@NotBlank` y `@Positive` son restricciones estándar de Jakarta. Las dos que
se ganan el sueldo son las de Firefly, importadas de `org.fireflyframework.annotations`:

- `@ValidAmount(min = 0.01)` comprueba que `requestedAmount` es un valor monetario
  positivo y sensato: no cero, no negativo, dentro de un límite configurable. Un
  `@Positive` a secas se perdería las reglas de precisión y rango que el dinero exige, y
  `min = 0.01` convierte el "nada de préstamos de cero euros" en una regla explícita y
  declarativa.
- `@ValidCurrencyCode` comprueba que `currency` es un código ISO-4217 real, de modo que
  `"EUR"` pasa y `"XYZ"` no.

Estas son las mismas anotaciones de restricción que usan los servicios de firefly-oss en
producción, expuestas aquí sobre un record de petición limpio. Tú escribes la restricción;
el framework aporta el validador y el mensaje de error consistente.

Una pregunta natural: ¿se componen estas restricciones con las de Jakarta sencillas, o
pelean entre sí? Se componen. Cada restricción de Firefly es un `ConstraintValidator`
ordinario, así que `@NotNull` y `@ValidAmount` sobre el mismo campo se ejecutan ambas en
la *misma* pasada de `@Valid`, y cada infracción que encuentran se recoge en un solo
informe. Por eso una única petición incorrecta puede volver con varios errores de campo a
la vez en lugar de fallar en el primero, y por eso el cuerpo de error tiene un array
`errors` en lugar de un solo mensaje.

!!! note "Término clave — validadores financieros (`fireflyframework-validators`)"
    Una pequeña biblioteca de anotaciones de restricción compatibles con Jakarta para los
    valores que una plataforma bancaria maneja repetidamente: importes, códigos de moneda,
    IBANs, BICs, identificadores fiscales, números de tarjeta. Cada una es un
    `ConstraintValidator` ordinario, así que se compone con `@NotNull` y compañía y
    participa en la misma pasada de `@Valid`. Usarlas en lugar de regexes copiados y
    pegados es como una flota entera valida un IBAN de la misma manera.

Cuando el `@Valid` sobre el `@RequestBody` del controlador falla —digamos que
`requestedAmount` es `-5.00`— Spring lanza un `WebExchangeBindException` *antes* de que
el cuerpo de tu manejador llegue a ejecutarse. Tú no lo capturas. El manejador global del
módulo web lo convierte en un problem detail `400`, que es el tema del Paso 5. Este orden
es importante: un payload rechazado nunca toca el servicio, nunca abre una transacción y
nunca llega a la base de datos. La validación es una compuerta, no una limpieza posterior.

!!! spring "Equivalente en Spring"
    Que `@Valid` dispare la validación sobre un `@RequestBody` es Spring de serie. En
    WebFlux puro, un bind fallido produce un `WebExchangeBindException` que —sin un
    manejador— da el JSON de error por defecto de Spring, cuya forma varía según la versión
    de Boot y no es RFC 7807. El único cambio de Firefly es capturar esa excepción de forma
    centralizada y renderizarla como un problem detail estándar, de manera idéntica en cada
    servicio.

## Paso 4 — Las excepciones semánticas se convierten en RFC 7807, gratis

Ahora el caso de no encontrado. Cuando haces un GET de un id de solicitud que no existe,
la respuesta correcta es `404 Not Found` con un cuerpo legible por máquina. En un servicio
hecho a mano eso significa un `@ExceptionHandler` y una forma JSON personalizada, inventada
de nuevo en cada servicio, sin coincidir con ninguno.

Firefly elimina la decisión. El servicio lanza una excepción *semántica* del núcleo de
errores del framework, y el módulo web la renderiza. Así maneja el servicio un fallo de
búsqueda:

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/service/LoanApplicationService.java | Listado 6.4 — lanzar una excepción semántica en un fallo de búsqueda; no se requiere manejador
    @Transactional(readOnly = true)
    public Mono<LoanApplicationResponse> getById(UUID id) {
        return repository.findById(id)
                .map(mapper::toResponse)
                .switchIfEmpty(Mono.error(
                        new ResourceNotFoundException("Loan application not found: " + id)));
    }
:::

La forma reactiva merece un momento. `repository.findById(id)` devuelve un `Mono` que está
*vacío* cuando la fila está ausente: no es un error, simplemente nada. `switchIfEmpty`
sustituye en ese caso una señal de error, lanzando
`org.fireflyframework.web.error.exceptions.ResourceNotFoundException`. Esa excepción viaja
por la cadena reactiva hasta el borde, donde el `GlobalExceptionHandler` del módulo web la
captura y emite un `404` con `Content-Type: application/problem+json` y un cuerpo RFC 7807.
El controlador no sabe que nada de esto ha ocurrido; simplemente devolvió el `Mono` que le
dio el servicio.

¿Por qué `switchIfEmpty` en lugar de un `if (result == null) throw`? Porque todavía no hay
resultado: `findById` no se ha ejecutado. Estás describiendo qué hacer *cuando* llegue la
señal vacía, en el mismo estilo declarativo que el resto de la canalización. La
alternativa, suscribirse de forma anticipada para comprobar el null, bloquearía justamente
el hilo que el capítulo te recuerda no bloquear.

Aquí está el cuerpo `404` en vivo, capturado del servicio core en ejecución cuando haces un
GET de un id que no está. Fíjate en que *no* es un esqueleto desnudo `about:blank`: junto a
los cinco miembros estándar del RFC (`type`, `title`, `status`, `detail`, `instance`)
Firefly mete un objeto `extensions` que lleva el contexto de traza, una severidad, una
pista de reintento, la ruta, una `suggestion` de remediación y una `category` de error:

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

El `detail` es exactamente la cadena que pasaste a `ResourceNotFoundException` —
`"Loan application not found: " + id`—, de modo que el mensaje que escribes en el servicio
es el mensaje que un cliente lee en el cable. El `retryable: false` y el
`category: "RESOURCE"` son el framework clasificando el error por ti: un recurso ausente no
es algo que un cliente deba reintentar, y un cliente genérico puede ramificar según
`category` sin parsear el `detail` legible por humanos.

!!! note "Término clave — problem detail RFC 7807"
    Un formato JSON estándar del IETF para respuestas de error HTTP, servido como
    `application/problem+json`, con miembros estables de primer nivel: `type`, `title`,
    `status`, `detail`, `instance`. Como la forma es estándar, un cliente escribe el manejo
    de errores una vez y funciona contra cada endpoint de Firefly, en lugar de descodificar
    un bloque a medida distinto por servicio. El `GlobalExceptionHandler` de Firefly rellena
    además el objeto `extensions` estándar con IDs de traza, una `severity`, una pista
    `retryable`, una `suggestion` y una `category`.

!!! spring "Equivalente en Spring"
    Spring 6 incluye un tipo `ProblemDetail` y un `ResponseEntityExceptionHandler` que
    pueden producir RFC 7807, pero aun así lo cableas tú, mapeas tus excepciones de dominio
    a estados y repites ese mapeo en cada servicio. Firefly hace el cableado una vez en el
    módulo web: lanza `ResourceNotFoundException` y el problem detail `404` es automático,
    con contexto de traza incluido. Estás usando el mecanismo de Spring; simplemente nunca
    lo ensamblas tú.

### El 400 de validación, la misma forma

La misma maquinaria maneja el fallo de validación del Paso 3. Envía un `requestedAmount`
negativo, que viola `@ValidAmount`, y el framework lo rechaza antes de que se ejecute el
servicio, con la misma forma de problem detail, pero ahora con un URI `type` *tipado* y un
array `errors` bajo `extensions` que señala el campo infractor:

```json
{
  "type": "https://api.firefly.com/errors/validation_error",
  "title": "Validation Failed",
  "status": 400,
  "detail": "Invalid request parameters",
  "instance": "/api/v1/loan-applications?traceId=62f4d701-8adc-49c7-bfea-5e325569e5d6",
  "extensions": {
    "code": "VALIDATION_ERROR",
    "suggestion": "Please check the validation errors and correct your request.",
    "errors": [
      {
        "field": "requestedAmount",
        "code": "ValidAmount",
        "message": "Requested amount must be a positive monetary value",
        "metadata": { "bindingFailure": false, "rejectedValue": "-5.00" }
      }
    ]
  }
}
```

Lee la entrada de `errors[]` de arriba abajo y puedes ver toda la historia de la validación
en un solo objeto: `field` es `requestedAmount`, `code` es `ValidAmount` (el nombre simple
de la restricción que se disparó, no un genérico "inválido"), `message` es el texto exacto
que escribiste en la anotación, y `metadata.rejectedValue` devuelve como eco la entrada
incorrecta `"-5.00"`. Un formulario de cliente puede resaltar el campo correcto y mostrar el
mensaje correcto sin adivinar. Y `type` es ahora un URI estable y dereferenciable —
`https://api.firefly.com/errors/validation_error`—, de modo que un `400` de validación es
distinguible de cualquier otro `400` solo por su `type`, mientras que el `404` de arriba
usaba el genérico `about:blank`. Un manejador, dos clases de error, una forma, cada servicio.

!!! note "Término clave — el `GlobalExceptionHandler`"
    El `GlobalExceptionHandler` de Firefly es un único componente del módulo web que captura
    cada error no manejado en el borde reactivo y renderiza RFC 7807. Mapea excepciones del
    framework a estados (`ResourceNotFoundException` a `404`), convierte el
    `WebExchangeBindException` de Spring y los fallos de los validadores en el `400` tipado
    `validation_error` de arriba, y enriquece ambos con contexto de traza y una `suggestion`.
    Nunca lo registras: llega con `fireflyframework-web`.

## Paso 5 — Observa cómo se ejecutan los filtros transversales

El `GlobalExceptionHandler` es uno de varios comportamientos que el módulo web y el starter
`core` aportan a *cada* petición, sin código en tu controlador. Son beans `WebFilter`
ordinarios de Spring, autoconfigurados cuando el framework está en el classpath y
sobreescribibles como cualquier bean. Este servicio depende de `fireflyframework-web` y
`fireflyframework-starter-core`, así que los obtiene todos:

- **`GlobalExceptionHandler`** — traduce las excepciones del framework y los fallos de
  validación en respuestas RFC 7807, como acabas de ver. Es lo que hace que el `400` y el
  `404` vuelvan como problem details.
- **`IdempotencyWebFilter`** — cuando una petición de escritura lleva una cabecera
  `X-Idempotency-Key`, el filtro cachea la primera respuesta bajo esa clave y la reproduce
  en un reintento, de modo que un cliente que reenvía un `POST` tras un timeout no crea una
  segunda solicitud de préstamo. Reintentos seguros sin lógica de deduplicación en tu
  manejador.
- **`TransactionFilter`** — sella cada respuesta con un `X-Transaction-Id`, generando uno
  si quien llama no lo aportó, de modo que una sola petición es trazable de extremo a
  extremo entre servicios y logs.
- **Enmascarado de PII en los logs** — la configuración de logging del framework redacta
  los datos de identificación personal —correos, identificadores nacionales, números de
  tarjeta— de modo que una línea de log perdida no pueda filtrar los detalles de un cliente.

Estos no son teóricos. Arranca el servicio core (siguiente paso) y haz POST de una solicitud
de préstamo, y *antes* de que se ejecute tu método `create`, la petición atraviesa esos
filtros y ellos la registran. Aquí están las líneas `DEBUG` reales en el hilo de la
petición: fíjate en el `X-Transaction-Id` que se genera y en el filtro de idempotencia que
inspecciona la llamada, cada línea decorada con el `traceId`/`spanId` que ahora cabalgan
sobre cada log de este hilo:

```text
{"timestamp":"2026-06-17T08:21:44.132+0000","message":"Generated new transaction ID: ce0c2ede-0e81-430f-9c99-7464a1613884","logger":"o.f.core.config.TransactionFilter","level":"DEBUG","traceId":"bfa32cdc5313c5951ec124b491f07687","spanId":"78466db40897c823"}
{"timestamp":"2026-06-17T08:21:44.134+0000","message":"IdempotencyWebFilter.filter: Processing request POST /api/v1/loan-applications","logger":"o.f.w.i.filter.IdempotencyWebFilter","level":"DEBUG","traceId":"bfa32cdc5313c5951ec124b491f07687","spanId":"78466db40897c823"}
```

No optaste por nada de esto por endpoint; los filtros se aplican a toda la flota en el
momento en que la dependencia está presente, y se autodescriben en el log para que puedas
verlos funcionar. Para enviar la clave de idempotencia desde un cliente, añades una
cabecera:

```text
POST /api/v1/loan-applications HTTP/1.1
Host: localhost:8081
Content-Type: application/json
X-Idempotency-Key: 4f2c9e10-7b3a-4f6e-9c21-2a1d5b8e0c33

{ "applicantId": "8b1d0d3c-1f2a-4f7e-9a3b-7d2c4e5f6a7b",
  "requestedAmount": "12500.00", "currency": "EUR",
  "termMonths": 36, "purpose": "HOME_IMPROVEMENT" }
```

Envía ese POST dos veces con la misma clave y obtienes el mismo `201` y el mismo cuerpo las
dos veces: una solicitud, no dos. La segunda petición nunca llega a tu método `create`;
`IdempotencyWebFilter` reproduce la respuesta cacheada.

!!! warning "Estos filtros son reales, pero aun así no debes bloquear"
    Los filtros transversales se ejecutan en los mismos hilos del bucle de eventos que tu
    manejador. Son no bloqueantes por diseño; mantén tu manejador no bloqueante también. Una
    llamada bloqueante dentro de `create` —una consulta JDBC, `.block()`, `Thread.sleep`—
    detiene el bucle para cada petición que la cadena de filtros está sirviendo, no solo la
    tuya. La capa de datos es R2DBC precisamente para que esto siga siendo cierto de extremo
    a extremo.

!!! spring "Equivalente en Spring"
    Cada uno de estos es un `WebFilter` sencillo, el equivalente en WebFlux de un `Filter`
    de servlet. Podrías escribir los cuatro a mano y registrarlos en cada servicio. La
    aportación de Firefly es que están escritos una vez, ajustados por propiedades
    `firefly.*` y activos por defecto, de modo que el décimo servicio se comporta
    exactamente como el primero sin que nadie vuelva a derivar la idempotencia o la
    propagación de transacciones.

## Paso 6 — Ejecuta la API a mano

Has leído toda la superficie web; ahora condúcela. El servicio core es una app de Spring
Boot independiente: arráncala de la manera ordinaria con el plugin de Maven, o como el jar
ejecutable reempaquetado (el repackage de Spring Boot está cableado en el build). Desde el
directorio del módulo `core-lending-loan-origination`:

```text
$ mvn spring-boot:run
# or, after `mvn -pl core-lending-loan-origination package`:
$ java -jar target/core-lending-loan-origination-0.1.0-SNAPSHOT.jar
```

Arranca en el **puerto 8081** contra H2 en memoria (R2DBC en tiempo de ejecución más una
migración Flyway, sin Docker, sin base de datos externa), imprime el banner
`:: firefly-core ::` que conociste en el Capítulo 2 y empieza a escuchar. Los comandos de
ejecución para las tres capas viven en `samples/lumen-lending/README.md`.

Haz POST de un cuerpo de petición con el solicitante, el importe, la moneda, el plazo en
meses y el propósito:

```text
$ curl -s -X POST http://localhost:8081/api/v1/loan-applications \
    -H 'Content-Type: application/json' \
    -d '{
          "applicantId": "8b1d0d3c-1f2a-4f7e-9a3b-7d2c4e5f6a7b",
          "requestedAmount": "12500.00",
          "currency": "EUR",
          "termMonths": 36,
          "purpose": "HOME_IMPROVEMENT"
        }'
```

El servicio valida el payload, persiste la solicitud y la somete en un solo paso,
respondiendo `201 Created` con el recurso almacenado. Fíjate en el `loanApplicationId` y el
`applicationNumber` generados, en las marcas de tiempo de ciclo de vida y —el titular— en el
`status`, que es `SUBMITTED`, no `DRAFT`, porque el servicio somete la solicitud como parte
de la creación:

```json
{
  "loanApplicationId": "6fb206b6-288f-4559-a403-460662a32329",
  "applicationNumber": "f2419b18-ae0c-4c32-9839-6c07ae424eda",
  "applicantId": "8b1d0d3c-1f2a-4f7e-9a3b-7d2c4e5f6a7b",
  "requestedAmount": 12500.00,
  "currency": "EUR",
  "termMonths": 36,
  "purpose": "HOME_IMPROVEMENT",
  "status": "SUBMITTED",
  "decisionReason": null,
  "createdAt": "2026-06-17T08:21:44.215",
  "updatedAt": "2026-06-17T08:21:44.215"
}
```

Ahora recupéralo por su id con un `GET`, usando el `loanApplicationId` que devolvió la
llamada de creación:

```text
$ curl -s http://localhost:8081/api/v1/loan-applications/6fb206b6-288f-4559-a403-460662a32329
```

```json
{
  "loanApplicationId": "6fb206b6-288f-4559-a403-460662a32329",
  "applicationNumber": "f2419b18-ae0c-4c32-9839-6c07ae424eda",
  "applicantId": "8b1d0d3c-1f2a-4f7e-9a3b-7d2c4e5f6a7b",
  "requestedAmount": 12500.00,
  "currency": "EUR",
  "termMonths": 36,
  "purpose": "HOME_IMPROVEMENT",
  "status": "SUBMITTED",
  "decisionReason": null,
  "createdAt": "2026-06-17T08:21:44.215",
  "updatedAt": "2026-06-17T08:21:44.215"
}
```

Ese viaje de ida y vuelta —POST crea y somete, GET vuelve a leer— es la columna vertebral
del servicio core, y es exactamente el mismo camino de código que la prueba de rebanada
conduce sin cabeza. Luego ejercita los dos caminos infelices y confirma que los cuerpos
coinciden con el Paso 4: un GET sobre un id ausente devuelve el problem detail `404`, y un
POST con `requestedAmount: -5.00` devuelve el `400` tipado.

!!! note "Dónde encaja esto en el stack en vivo"
    Este capítulo ejercita la capa **core** de forma aislada en el `8081`. En el reactor
    completo, el core es el sistema de registro al fondo de la cadena
    **exp → domain → core**: un único `POST` de canal al BFF de experiencia en el `8080`
    fluye a través del orquestador de dominio en el `8082`, cuyo
    `RegisterApplicationSaga` escribe a *este mismísimo endpoint* sobre HTTP y recibe de
    vuelta el id asignado por el core. Los tres endpoints que construiste aquí son lo que
    esa saga acaba llamando. (La costura de escritura domain→core es intencionadamente
    mínima —algunos campos del canal aterrizan como valores por defecto del core— y es el
    tema de capítulos posteriores.)

## Paso 7 — Demuéstralo

No necesitas un servidor en ejecución para demostrar todo esto. El reactor incluye una
prueba de rebanada que arranca el contexto reactivo completo contra H2 en memoria (R2DBC en
tiempo de ejecución más una migración Flyway, sin Docker) y conduce la API real con
`WebTestClient`. Hace aserciones sobre el viaje de ida y vuelta de creación y lectura, el
`404` RFC 7807 para un id ausente y el rechazo `400` de un importe incorrecto: los mismos
caminos de código que acabas de ejercitar a mano, ejecutados sin cabeza. Desde
`samples/lumen-lending`, ejecuta solo esta prueba:

```text
$ mvn -q -pl core-lending-loan-origination -Dtest=LoanApplicationControllerTest test
```

Deberías ver pasar sus tres casos:

```text
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in com.firefly.lumen.core.web.LoanApplicationControllerTest
[INFO] BUILD SUCCESS
```

Tres pruebas en verde confirman las tres afirmaciones del capítulo, y se corresponden una a
una con las tres cosas que acabas de hacer con `curl`. `createsAnApplicationAndReadsItBack`
hace POST del cuerpo válido, asegura `201` y `status == "SUBMITTED"` y
`requestedAmount == 12500.00`, luego hace GET por el `loanApplicationId` devuelto y asegura
`200` con el `currency` y el `purpose` releídos.
`returnsRfc7807ProblemDetailWhenMissing` hace GET de un id aleatorio y asegura `404` con la
ruta JSON `$.status` igual a `404`: prueba de que el problem detail es JSON real, no solo una
línea de estado. `rejectsAnInvalidPayload` hace POST de `requestedAmount = -5.00` y asegura
`400`, prueba de que `@ValidAmount` se dispara antes de que se ejecute cualquier lógica de
negocio.

Ejecuta el módulo entero y las dieciocho pruebas pasan: los tres casos de la capa web más las
pruebas del modelo de dominio y de los helpers reactivos que conocerás en capítulos
posteriores:

```text
$ mvn -q -pl core-lending-loan-origination test
```

```text
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in com.firefly.lumen.core.web.LoanApplicationControllerTest
[INFO] Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

!!! tip "Punto de control"
    Ejecuta `mvn -q -pl core-lending-loan-origination test` desde `samples/lumen-lending`
    y confirma `Tests run: 18, Failures: 0` y un `BUILD SUCCESS` final. Si quieres
    solo la rebanada web, acótala con
    `-Dtest=LoanApplicationControllerTest` y busca `Tests run: 3, Failures: 0`.
    Esas líneas verdes son el contrato contra el que se comprueba cada listado de este
    capítulo: el módulo core son **18** pruebas, y el reactor completo de tres capas son
    **33** (core 18, domain 6, exp 9).

## Lo que has construido {.recap}

- Un `@RestController` reactivo en `/api/v1/loan-applications` (servido en `8081`) con tres
  métodos —`create` (POST, `201`), `getById` (GET de uno) y `list` (GET de muchos con un
  filtro opcional `?status=`)— que devuelven `Mono` y `Flux` de DTOs y no contienen código
  de manejo de errores.
- Un `CreateLoanApplicationRequest` validado por restricciones de Jakarta más los
  `@ValidAmount` y `@ValidCurrencyCode` conscientes de las finanzas de Firefly, y un record
  inmutable `LoanApplicationResponse` (campos `loanApplicationId`, `applicantId`,
  `requestedAmount`, `currency`, `termMonths`, `status`, `decisionReason`, …) como contrato
  del cable.
- Un camino de no encontrado que lanza la `ResourceNotFoundException` del framework y obtiene
  un `404` RFC 7807 renderizado automáticamente por el `GlobalExceptionHandler` del módulo
  web —con un objeto `extensions` que lleva `traceId`, `severity`, `retryable: false`,
  `suggestion` y `category: "RESOURCE"`— y un `400` de validación con el `type` tipado
  `https://api.firefly.com/errors/validation_error` y un array `errors[]`.
- Los `WebFilter`s transversales gratuitos que hereda cada petición —`TransactionFilter`
  sellando un `X-Transaction-Id`, `IdempotencyWebFilter` deduplicando reintentos y el
  enmascarado de PII—, observados en las líneas `DEBUG` reales del log en el hilo de la
  petición.
- Una prueba de rebanada que pasa (`Tests run: 3, Failures: 0`) y un módulo en verde
  (`Tests run: 18, Failures: 0`, `BUILD SUCCESS`) que verifican el viaje de ida y vuelta
  `201`, el problem detail `404` y el rechazo de validación `400`.

## Pruébalo tú mismo {.exercises}

1. **Añade una restricción.** En `CreateLoanApplicationRequest.java`, limita el plazo con
   un `@Max` de Jakarta sobre `termMonths` (por ejemplo, 84 meses). Vuelve a ejecutar la
   prueba, luego añade un cuarto caso de prueba a `LoanApplicationControllerTest` que haga
   POST de un plazo de 120 meses y asegure `400`. Inspecciona la entrada de `errors[]`: ¿cuál
   es el `code` para una violación de `@Max`, y en qué se diferencia del code `ValidAmount`?
2. **Filtra la lista.** El método `list` ya acepta `?status=`. Añade una prueba que cree una
   solicitud y luego haga GET de
   `/api/v1/loan-applications?status=SUBMITTED`, asegurando que la nueva solicitud está en la
   lista devuelta. Luego haz GET de `?status=DRAFT` y asegura que *no* lo está: prueba de que
   el filtro por enum funciona.
3. **Rechaza una moneda incorrecta.** Añade una prueba que haga POST de una petición con
   `currency = "XYZ"` y asegure `400`, confirmando que `@ValidCurrencyCode` rechaza un código
   no ISO-4217. Comprueba que el `errors[].field` es `currency` y el `code` es
   `ValidCurrencyCode`, reflejando el caso de `ValidAmount`.
4. **Inspecciona el problem detail.** Extiende `returnsRfc7807ProblemDetailWhenMissing`
   para asegurar también que `$.detail` contiene la frase `Loan application not found` y que
   `$.extensions.category` es igual a `RESOURCE`, atando el cuerpo de la respuesta de vuelta
   al mensaje lanzado en `LoanApplicationService.getById` y a la clasificación del framework.
5. **Demuestra la idempotencia a mano.** Arranca el servicio (`mvn spring-boot:run` en
   `8081`) y haz `POST` del mismo cuerpo válido dos veces con una cabecera `X-Idempotency-Key`
   idéntica. Confirma que recuperas una solicitud, no dos, luego haz
   `GET /api/v1/loan-applications` para verificar que solo se creó una. Observa la línea
   `DEBUG` de `IdempotencyWebFilter` en la segunda petición.

## Adónde ir ahora

Tienes una superficie HTTP funcional, pero el servicio que hay detrás aún es delgado:
`create` mapea una petición a una entidad y la guarda, y `getById` lee de vuelta una fila.
Los próximos capítulos rellenan lo que esos métodos orquestan: el modelo de dominio y la
capa de persistencia reactiva (la capa de datos del Capítulo 15) que convierten estos tres
endpoints en un sistema de registro real, y la saga y el motor de reglas (Capítulos 12 y 13)
que algún día poblarán ese campo `decisionReason`.
