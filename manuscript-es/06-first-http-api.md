A estas alturas ya tienes un servicio que arranca, un POM padre que lo mantiene
coherente en versiones y cierta familiaridad con los tipos reactivos que todo
habla. Ha llegado el momento de dar a Lumen Lending una superficie que un cliente
pueda invocar. En este capítulo construyes la primera superficie HTTP del núcleo
de originación de préstamos: un `@RestController` reactivo que crea, recupera y
lista solicitudes de préstamo.

El controlador en sí es pequeño: tres métodos, sin código de gestión de errores,
sin fontanería de logging, sin envoltorio. Esa pequeñez es justamente el punto. El
módulo web que Firefly añade a Spring WebFlux hace el trabajo transversal
*alrededor* de tu manejador: los fallos de validación y los recursos ausentes
vuelven como respuestas estándar de problem-detail RFC 7807, los reintentos se
deduplican, cada respuesta se sella con un identificador de transacción y los datos
de carácter personal se enmascaran en los logs, todo ello sin que aparezca una sola
línea de eso en el controlador. Tú escribes los tres métodos de negocio; el
framework escribe la consistencia.

Construiremos el controlador y sus DTO, nos apoyaremos en validadores conscientes
de las finanzas para la petición, lanzaremos una excepción semántica para el caso
de no-encontrado y luego ejecutaremos el test de slice que demuestra que los
problem-detail de 400 y 404 vuelven exactamente como se prometió.

## Un controlador reactivo, y nada más

Abre el núcleo de originación de préstamos y observa su única clase web. Es un
`@RestController` de WebFlux de manual: `@RequestMapping` para la ruta base,
inyección por constructor (aquí vía `@RequiredArgsConstructor` de Lombok) y métodos
que devuelven `Mono` y `Flux` en lugar de valores planos.

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/web/LoanApplicationController.java | Listado 6.1 — toda la superficie web: tres metodos reactivos
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
`201 Created` con el nuevo recurso como cuerpo. El `@Valid` sobre el `@RequestBody`
es lo que arma la validación; más sobre esto en la siguiente sección. `getById`
devuelve una solicitud o nada; `list` devuelve un `Flux`, el publicador de cero-a-
muchos, y acepta un filtro `?status=` opcional a través de
`@RequestParam(required = false)`. Como el parámetro está tipado como
`ApplicationStatus`, WebFlux convierte la cadena de consulta al enum por ti y
rechaza un valor desconocido antes de que tu código se ejecute.

Lo que *no* está aquí importa tanto como lo que sí está. No hay `try`/`catch`, ni
envoltorio `ResponseEntity`, ni `@ExceptionHandler`, ni logging. El controlador
delega en un `LoanApplicationService` y devuelve el publicador que recibe. El
framework se suscribe en el borde cuando escribe la respuesta, de modo que nunca
llamas a `.subscribe()` tú mismo: compones y devuelves.

!!! spring "Equivalente en Spring"
    Cada anotación aquí es Spring WebFlux puro: `@RestController`,
    `@RequestMapping`, `@PostMapping`, `@GetMapping`, `@PathVariable`,
    `@RequestParam`, `@RequestBody`, `@Valid`, `@ResponseStatus`. El par
    `@Tag`/`@Operation` es springdoc OpenAPI. Si has escrito un controlador WebFlux,
    has escrito este. Lo que Firefly cambia no es el controlador: es todo lo que le
    sucede a la petición y a la respuesta *alrededor* de él, que es de lo que trata
    el resto del capítulo.

## El DTO de respuesta

El controlador devuelve un `LoanApplicationResponse`, nunca la entidad de
persistencia. Mantener un tipo de vista dedicado significa que el contrato de cable
queda desacoplado de la tabla: puedes remodelar el almacenamiento sin romper a los
clientes, y nunca filtras accidentalmente un campo interno. Es un `record`, así que
es inmutable y se serializa directamente a JSON sin getters que escribir.

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

Un DTO no tiene nada de reactivo: es la forma de un valor. El `Mono` y el `Flux`
del controlador son publicadores *de* este record; el record en sí es solo datos.

## Validación con restricciones conscientes de las finanzas

Una solicitud de préstamo lleva dinero, una divisa y un plazo, y la petición debe
rechazarse —limpiamente, antes de que se ejecute cualquier lógica de negocio— si
alguno de ellos es absurdo. Ese es el trabajo del DTO de petición de creación.
Mezcla restricciones ordinarias de Jakarta Bean Validation con dos restricciones
específicas de finanzas que Firefly incluye en `fireflyframework-validators`.

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/dto/CreateLoanApplicationRequest.java | Listado 6.3 — la peticion de creacion, validada por Jakarta mas restricciones de finanzas
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

`@NotNull`, `@NotBlank` y `@Positive` son restricciones estándar de Jakarta. Las
dos que se ganan el sueldo son las de Firefly:

- `@ValidAmount(min = 0.01)` comprueba que `requestedAmount` es un valor monetario
  sensato y positivo: ni cero, ni negativo, dentro de un límite configurable. Un
  simple `@Positive` se perdería las reglas de precisión y rango que el dinero
  exige.
- `@ValidCurrencyCode` comprueba que `currency` es un código ISO-4217 real, de modo
  que `"EUR"` pasa y `"XYZ"` no.

Estas son las mismas anotaciones de restricción que usan los servicios de
producción de firefly-oss, presentadas aquí sobre un record de petición limpio. Tú
escribes la restricción; el framework suministra el validador y el mensaje de error
consistente.

!!! note "Término clave — validadores de finanzas (`fireflyframework-validators`)"
    Una pequeña biblioteca de anotaciones de restricción compatibles con Jakarta
    para los valores que una plataforma bancaria maneja repetidamente: importes,
    códigos de divisa, IBAN, BIC, identificadores fiscales, números de tarjeta. Cada
    una es un `ConstraintValidator` ordinario, de modo que se compone con `@NotNull`
    y compañía y participa en la misma pasada de `@Valid`. Usarlas en lugar de
    expresiones regulares copiadas y pegadas es como una flota entera valida un IBAN
    de la misma manera.

Cuando `@Valid` sobre el `@RequestBody` del controlador falla —digamos que
`requestedAmount` es `-5.00`— Spring lanza una `WebExchangeBindException` antes de
que el cuerpo de tu manejador llegue siquiera a ejecutarse. Tú no la capturas. El
manejador global del módulo web la convierte en un problem-detail `400`, que es el
tema de las dos secciones siguientes.

!!! spring "Equivalente en Spring"
    Que `@Valid` dispare la validación sobre un `@RequestBody` es Spring de fábrica.
    En WebFlux puro, un bind fallido produce una `WebExchangeBindException` que —sin
    un manejador— genera el JSON de error por defecto de Spring, cuya forma varía
    según la versión de Boot y no es RFC 7807. El único cambio de Firefly es capturar
    esa excepción de forma centralizada y renderizarla como un problem-detail
    estándar, idéntico en cada servicio.

## Las excepciones semánticas se convierten en RFC 7807, gratis

Ahora el caso de no-encontrado. Cuando haces un GET de un id de solicitud que no
existe, la respuesta correcta es `404 Not Found` con un cuerpo legible por máquina.
En un servicio hecho a mano eso significa un `@ExceptionHandler` y una forma JSON a
medida: reinventada de nuevo en cada servicio, sin coincidir con ninguno.

Firefly elimina la decisión. El servicio lanza una excepción *semántica* del núcleo
de errores del framework, y el módulo web la renderiza. Así gestiona el servicio un
fallo:

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/service/LoanApplicationService.java | Listado 6.4 — lanzar una excepcion semantica ante un fallo; sin manejador requerido
    @Transactional(readOnly = true)
    public Mono<LoanApplicationResponse> getById(UUID id) {
        return repository.findById(id)
                .map(mapper::toResponse)
                .switchIfEmpty(Mono.error(
                        new ResourceNotFoundException("Loan application not found: " + id)));
    }
:::

La forma reactiva merece un momento. `repository.findById(id)` devuelve un `Mono`
que está *vacío* cuando la fila está ausente: no un error, simplemente nada.
`switchIfEmpty` sustituye en ese caso una señal de error, lanzando
`org.fireflyframework.web.error.exceptions.ResourceNotFoundException`. Esa excepción
viaja por la cadena reactiva hacia abajo hasta el borde, donde el
`GlobalExceptionHandler` del módulo web la captura y emite un `404` con
`Content-Type: application/problem+json` y un cuerpo RFC 7807. El controlador no
sabe que nada de esto ha ocurrido; simplemente devolvió el `Mono` que el servicio le
dio.

Una respuesta de problem-detail tiene este aspecto en el cable:

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "Loan application not found: 7b1f...",
  "instance": "/api/v1/loan-applications/7b1f..."
}
```

La misma maquinaria gestiona el fallo de validación de la sección anterior: un
problem-detail `400` que describe qué campo fue rechazado y por qué. Un manejador,
una forma, cada error, cada servicio.

!!! note "Término clave — problem-detail RFC 7807"
    Un formato JSON estándar del IETF para respuestas de error HTTP, servido como
    `application/problem+json`, con campos estables: `type`, `title`, `status`,
    `detail`, `instance`. Como la forma es estándar, un cliente escribe la gestión de
    errores una vez y funciona contra cada endpoint de Firefly, en lugar de
    decodificar un blob a medida distinto por servicio.

!!! spring "Equivalente en Spring"
    Spring 6 incluye un tipo `ProblemDetail` y un `ResponseEntityExceptionHandler`
    que pueden producir RFC 7807, pero aun así tienes que cablearlo, mapear tus
    excepciones de dominio a estados y repetir ese mapeo en cada servicio. Firefly
    hace el cableado una vez en el módulo web: lanza `ResourceNotFoundException` y el
    problem-detail `404` es automático. Estás usando el mecanismo de Spring;
    simplemente nunca lo ensamblas tú mismo.

## Los filtros transversales gratuitos del módulo web

El `GlobalExceptionHandler` es uno de varios comportamientos que el módulo web y el
starter `core` aportan a *cada* petición, sin código en tu controlador. Son beans
`WebFilter` ordinarios de Spring, autoconfigurados cuando el framework está en el
classpath y sobrescribibles como cualquier bean. Este servicio depende de
`fireflyframework-web` y `fireflyframework-starter-core`, así que los obtiene todos:

- **`GlobalExceptionHandler`** — traduce las excepciones del framework y los fallos
  de validación a respuestas RFC 7807, como acabas de ver. Esto es lo que hace que
  el 400 y el 404 del test vuelvan como problem-details.
- **`IdempotencyWebFilter`** — cuando una petición de escritura lleva una cabecera
  `X-Idempotency-Key`, el filtro cachea la primera respuesta bajo esa clave y la
  reproduce en un reintento, de modo que un cliente que reenvía un `POST` tras un
  timeout no crea una segunda solicitud de préstamo. Reintentos seguros sin lógica
  de deduplicación en tu manejador.
- **`TransactionFilter`** — sella cada respuesta con un `X-Transaction-Id`,
  generando uno si quien llama no lo suministró, de modo que una única petición sea
  rastreable de extremo a extremo a través de servicios y logs.
- **Enmascarado de PII en los logs** — la configuración de logging del framework
  redacta los datos de carácter personal —correos electrónicos, documentos de
  identidad nacionales, números de tarjeta— de modo que una línea de log perdida no
  pueda filtrar los datos de un cliente.

No optaste por ninguno de estos por endpoint; se aplican a toda la flota en el
momento en que la dependencia está presente. Para enviar la clave de idempotencia
desde un cliente, añadirías una cabecera:

```text
POST /api/v1/loan-applications HTTP/1.1
Content-Type: application/json
X-Idempotency-Key: 4f2c9e10-7b3a-4f6e-9c21-2a1d5b8e0c33

{ "applicantId": "...", "requestedAmount": 12500.00, "currency": "EUR",
  "termMonths": 36, "purpose": "HOME_IMPROVEMENT" }
```

Envía ese POST dos veces con la misma clave y obtienes el mismo `201` y el mismo
cuerpo ambas veces: una solicitud, no dos.

!!! warning "Estos filtros son reales, pero aun así no debes bloquear"
    Los filtros transversales se ejecutan en los mismos hilos del bucle de eventos
    que tu manejador. Son no-bloqueantes por diseño; mantén tu manejador
    no-bloqueante también. Una llamada bloqueante dentro de `create` —una consulta
    JDBC, `.block()`, `Thread.sleep`— atasca el bucle para cada petición que la
    cadena de filtros esté sirviendo, no solo la tuya. La capa de datos es R2DBC
    precisamente para que esto siga siendo cierto de extremo a extremo.

!!! spring "Equivalente en Spring"
    Cada uno de estos es un `WebFilter` simple: el equivalente en WebFlux de un
    `Filter` de servlet. Podrías escribir los cuatro a mano y registrarlos en cada
    servicio. La aportación de Firefly es que están escritos una vez, ajustados por
    propiedades `firefly.*` y activos por defecto, de modo que el décimo servicio se
    comporta exactamente igual que el primero sin que nadie vuelva a derivar la
    idempotencia o la propagación de transacciones.

## Ejecútalo

El test de slice arranca el contexto reactivo completo contra una H2 en memoria
(R2DBC más una migración de Flyway, sin Docker) e impulsa la API con
`WebTestClient`. Verifica el viaje de ida y vuelta de crear-y-leer, el `404` RFC
7807 para un id ausente y el rechazo `400` de un importe incorrecto. Ejecuta solo
este test:

```text
mvn -q -pl core-lending-loan-origination -Dtest=LoanApplicationControllerTest test
```

Deberías verlo pasar:

```text
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
```

Tres tests en verde confirman las tres afirmaciones del capítulo: un `POST`
devuelve `201` y el recurso se vuelve a leer, un id desconocido devuelve un
problem-detail `404`, y un importe negativo se rechaza con un `400` antes de que se
ejecute cualquier lógica de negocio.

!!! tip "Punto de control"
    Ejecuta el comando de arriba y confirma `Tests run: 3, Failures: 0`. El test
    `returnsRfc7807ProblemDetailWhenMissing` verifica que `$.status` es igual a `404`
    en el cuerpo de la respuesta: prueba de que el problem-detail es JSON real, no
    solo un código de estado. El test `rejectsAnInvalidPayload` envía
    `requestedAmount = -5.00` y espera `400`, prueba de que `@ValidAmount` está
    haciendo su trabajo.

## Lo que has construido {.recap}

- Un `@RestController` reactivo en `/api/v1/loan-applications` con tres métodos —
  `create` (POST, `201`), `getById` (GET de uno) y `list` (GET de muchos con un
  filtro `?status=` opcional)— que devuelven `Mono` y `Flux` de DTO y no contienen
  código de gestión de errores.
- Un `CreateLoanApplicationRequest` validado por restricciones de Jakarta más los
  validadores conscientes de las finanzas `@ValidAmount` y `@ValidCurrencyCode` de
  Firefly, y un record `LoanApplicationResponse` inmutable como contrato de cable.
- Un camino de no-encontrado que lanza la `ResourceNotFoundException` del framework y
  obtiene un `404` RFC 7807 renderizado automáticamente por el
  `GlobalExceptionHandler` del módulo web, junto con el `IdempotencyWebFilter`, el
  `TransactionFilter` y el enmascarado de PII gratuitos que cada petición hereda.
- Un test de slice que pasa (`Tests run: 3, Failures: 0`) que verifica el viaje de
  ida y vuelta del `201`, el problem-detail `404` y el rechazo de validación `400`.

## Pruébalo tú mismo {.exercises}

1. **Añade una restricción.** En `CreateLoanApplicationRequest.java`, limita el
   plazo con un `@Max` de Jakarta sobre `termMonths` (por ejemplo, 84 meses).
   Vuelve a ejecutar el test y luego añade un cuarto caso de test que envíe un plazo
   de 120 meses y verifique un `400`.
2. **Filtra la lista.** El método `list` ya acepta `?status=`. Añade un test a
   `LoanApplicationControllerTest` que cree una solicitud y luego haga GET de
   `/api/v1/loan-applications?status=SUBMITTED`, verificando que la nueva solicitud
   está en el `Flux` devuelto.
3. **Rechaza una divisa incorrecta.** Añade un test que envíe una petición con
   `currency = "XYZ"` y verifique un `400`, confirmando que `@ValidCurrencyCode`
   rechaza un código no-ISO-4217 igual que `@ValidAmount` rechaza un importe
   incorrecto.
4. **Inspecciona el problem-detail.** Amplía
   `returnsRfc7807ProblemDetailWhenMissing` para que también verifique que `$.detail`
   contiene la frase `Loan application not found`, vinculando el cuerpo de la
   respuesta con el mensaje lanzado en `LoanApplicationService.getById`.
5. **Demuestra la idempotencia a mano.** Arranca el servicio (Capítulo 2) y haz
   `POST` del mismo cuerpo válido dos veces con una cabecera `X-Idempotency-Key`
   idéntica. Confirma que recuperas una solicitud, no dos, y luego lista las
   solicitudes para verificar que solo se creó una.

## Adónde ir ahora

Tienes una superficie HTTP funcional, pero el servicio que hay detrás sigue siendo
delgado: `create` mapea una petición a una entidad y la guarda, y `getById` lee una
fila de vuelta. Los próximos capítulos rellenan lo que esos métodos orquestan: el
modelo de dominio y la capa de persistencia reactiva que convierte estos tres
endpoints en un sistema de registro real.
