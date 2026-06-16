En el Capítulo 6 construiste un controlador REST reactivo y lo viste superar una
prueba de extremo a extremo. Ese controlador es más que un endpoint: es un
*contrato*. Cada servicio de préstamos de Lumen publica uno, y la capa superior
nunca accede a su base de datos: invoca ese contrato por HTTP a través de un
cliente generado. Este capítulo trata sobre el contrato: cómo un controlador se
convierte en un documento **OpenAPI** legible por máquinas, cómo ese documento se
transforma en documentación navegable para humanos y en un **SDK** tipado, y dónde
encaja cada pieza.

Conviene aclarar el alcance antes de empezar. El módulo
`core-lending-loan-origination` del reactor de acompañamiento es un servicio único
y autocontenido. Lleva las *anotaciones* OpenAPI en su controlador —dentro de un
momento extraerás las reales—, pero **no** levanta una página de Swagger UI ni
ejecuta el generador de SDK como parte de su build, porque nada en este único
módulo consume un cliente generado. La historia del SDK es el patrón del que
depende el build multicapa del Capítulo 14. Así que este capítulo extrae lo que el
reactor contiene realmente, y enseña la maquinaria de generación y consumo con
fragmentos ilustrativos claramente marcados. Cuando un bloque de código es un
boceto en lugar de un fragmento verificado, así lo indica.

Este es un capítulo más breve y conceptual. No hay un nuevo build práctico —el
build es el controlador que ya escribiste—, pero al final hay un momento Ejecútalo
que confirma que el controlador anotado sigue pasando.

## El contrato que un controlador ya lleva consigo

Un controlador WebFlux, por sí solo, basta para describir una API. El método HTTP,
la ruta, los parámetros de ruta y de consulta, el tipo del cuerpo de la petición y
el tipo de la respuesta están todos ahí, en las anotaciones y en las firmas de los
métodos. Un generador de documentos puede leerlos por reflexión y emitir una
descripción OpenAPI sin que escribas un archivo de especificación aparte. Lo que el
código *no puede* inferir es la intención humana: un resumen, una descripción más
larga, una agrupación lógica. Eso es lo que añaden las anotaciones de Swagger.

Abre el controlador de loan-origination y observa los metadatos que ya lleva.

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/web/LoanApplicationController.java | Listado 7.1 — las anotaciones OpenAPI que trae el reactor
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

Dos anotaciones de `io.swagger.v3.oas.annotations` hacen el trabajo. `@Tag` en la
clase agrupa las tres operaciones bajo un único encabezado —"LoanApplication"— de
modo que la documentación renderizada se lee como un recurso coherente en lugar de
una lista plana de rutas. `@Operation` en cada manejador aporta el `summary` (el
título de una línea que se muestra en el índice de la documentación) y la
`description` más larga. El propio comentario del reactor nombra esta intención:
refleja el estilo de controlador de firefly-oss, "inyección por constructor,
metadatos OpenAPI con `@Tag`/`@Operation`, tipos de retorno reactivos".

Todo lo demás que el generador necesita lo lee de las firmas que ya escribiste.
`@PostMapping` junto con `@RequestBody CreateLoanApplicationRequest` le indica el
verbo, la ruta y el esquema de entrada. `Mono<LoanApplicationResponse>` le indica
el esquema de respuesta: el generador desenvuelve el `Mono` y describe los campos
del record `LoanApplicationResponse`. `@RequestParam(required = false)
ApplicationStatus status` se convierte en un parámetro de consulta `status`
opcional cuyos valores permitidos son las constantes del enum. Tú anotas la
*intención*; el framework infiere la *forma*.

!!! note "Termino clave — OpenAPI"
    **OpenAPI** (antes Swagger) es una especificación neutral respecto al proveedor
    y legible por máquinas para una API HTTP: sus rutas, operaciones, parámetros,
    esquemas de petición y respuesta y respuestas de error, expresados como un único
    documento JSON o YAML. Es el contrato en el que dos servicios se ponen de
    acuerdo. Como son datos estructurados, las herramientas pueden renderizarlos como
    documentación, validar peticiones contra ellos y generar código de cliente a
    partir de ellos, que es precisamente de lo que trata todo el resto de este
    capítulo.

!!! spring "Equivalente en Spring"
    No hay nada específico de Firefly en el Listado 7.1. `@Tag` y `@Operation` son
    las anotaciones estándar de `swagger-core`, y se comportan en un servicio Firefly
    exactamente igual que en cualquier servicio WebFlux de Spring Boot. La
    contribución de Firefly está aguas arriba del controlador: su starter web es la
    capa que —cuando lo habilitas— conecta un generador sobre estas anotaciones y
    sirve el resultado, de modo que cada servicio de la flota expone su contrato de
    la misma manera.

## De las anotaciones a un documento OpenAPI

Las anotaciones son inertes hasta que algo las lee. En el ecosistema de Spring ese
algo es **springdoc-openapi**: una biblioteca que, en el arranque, escanea tus
controladores, construye el modelo OpenAPI a partir de las anotaciones y firmas
anteriores y lo sirve en una URL bien conocida. Firefly empaqueta y preconfigura
esto para que un servicio exponga su contrato por convención en lugar de por
cableado manual.

El módulo loan-origination no incorpora springdoc a su classpath —no tiene ningún
consumidor que necesite el documento—, así que lo siguiente es **ilustrativo**:
muestra lo que añades a un servicio que *debería* publicar su contrato, y lo que
obtienes.

```xml
<!-- Illustrative: the dependency that turns annotations into a served document. -->
<dependency>
  <groupId>org.springdoc</groupId>
  <artifactId>springdoc-openapi-starter-webflux-ui</artifactId>
</dependency>
```

Con eso en el classpath y la característica habilitada, el servicio en ejecución
sirve dos cosas. La primera es el documento OpenAPI en bruto —el contrato en sí—
como JSON:

```text
GET /v3/api-docs

{
  "openapi": "3.0.1",
  "info": { "title": "core-lending-loan-origination", "version": "v1" },
  "paths": {
    "/api/v1/loan-applications": {
      "post": {
        "tags": ["LoanApplication"],
        "summary": "Create a loan application",
        "description": "Validates the request, opens and submits a new application.",
        "responses": { "201": { "$ref": "#/components/schemas/LoanApplicationResponse" } }
      }
    }
  }
}
```

Lee ese JSON frente al Listado 7.1 y la correspondencia es exacta: el nombre del
`@Tag` se convirtió en `tags`, el summary y la description de `@Operation` pasaron
tal cual, y el `201` vino de `@ResponseStatus(HttpStatus.CREATED)`. No hay una
segunda fuente de verdad: el documento se deriva del código que sirve las
peticiones, de modo que nunca puede desviarse en silencio del comportamiento en
ejecución.

La segunda cosa que sirve el servicio es una cara humana para ese documento.

!!! note "Termino clave — Swagger UI y ReDoc"
    **Swagger UI** y **ReDoc** son dos renderizadores que convierten un documento
    OpenAPI en una página web navegable. Swagger UI (normalmente en
    `/swagger-ui.html`) es interactivo: lista cada operación y te permite rellenar
    los parámetros y *probar la petición* en vivo contra el servicio en ejecución.
    ReDoc renderiza el mismo documento como documentación de referencia limpia y de
    solo lectura. Ambos consumen exactamente el mismo JSON de `/v3/api-docs` de
    arriba; son vistas, no especificaciones aparte.

La convención de Firefly es que habilitar la generación de API a través de las
propiedades del framework los activa juntos, de modo que un desarrollador que
arranque cualquier servicio de Lumen puede abrir la UI y ejercitar la API sin un
cliente REST. En una flota, esa consistencia es el valor: la documentación vive en
la misma ruta en cada servicio.

!!! spring "Equivalente en Spring"
    En Spring Boot puro tendrías que añadir tú mismo el starter de springdoc, quizá
    definir un bean `OpenAPI` para fijar el título y la versión, y aceptar las URL
    por defecto de springdoc. La capa web de Firefly pliega eso en una
    autoconfiguración controlada por una propiedad —el interruptor estilo
    `@EnableOpenApiGen` del framework—, de modo que la generación es un conmutador, no
    un ensamblaje por servicio. En cualquier caso, las anotaciones de tu controlador
    son idénticas; solo cambia quién conecta el generador.

## El SDK sobre HTTP: cómo hablan las capas

Ahora la recompensa. Recuerda la regla del Capítulo 1: las capas nunca comparten una
base de datos; se integran sobre contratos. Cuando la capa de **dominio** necesita
crear una solicitud de préstamo, no importa el módulo core ni toca sus tablas: hace
una llamada HTTP a `POST /api/v1/loan-applications`. La cuestión es *cómo* hace esa
llamada sin escribir a mano un `WebClient`, una URL y un par de DTOs que dupliquen
los del propio servicio core.

La respuesta es el **SDK** generado. El mismo documento OpenAPI que alimenta a
Swagger UI también alimenta a **openapi-generator**, una herramienta que emite una
biblioteca cliente tipada a partir del contrato. Apúntala al `/v3/api-docs` de un
servicio, elige el generador de WebClient reactivo, y produce una pequeña biblioteca
Java: una clase cliente por tag, un método por operación, y clases de modelo para
cada esquema. La capa superior añade esa biblioteca como dependencia y llama a un
método en lugar de elaborar una petición HTTP.

!!! note "Termino clave — SDK generado (el contrato del SDK sobre HTTP)"
    Un **SDK generado** es una biblioteca cliente producida *a partir* del documento
    OpenAPI de un servicio, no escrita a mano. Cada servicio core de Lumen trae uno:
    un cliente reactivo basado en `WebClient` cuyos métodos y tipos de modelo reflejan
    su controlador uno a uno. La capa superior depende del SDK e invoca métodos
    tipados; bajo el capó cada llamada es una petición HTTP al contrato. El contrato
    es el único acoplamiento. Regenera el SDK cuando cambie el contrato, y el
    compilador te dice qué se rompió.

Este módulo no ejecuta el generador —aquí no hay ningún consumidor—, así que la
configuración del generador de abajo es **ilustrativa**. En el build multicapa del
Capítulo 14, un plugin como este vive en el `pom.xml` de cada servicio core y
produce el SDK que consume la capa de dominio.

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

La biblioteca `webclient` con `<reactive>true</reactive>` es la parte que mantiene
al cliente fiel a la pila reactiva: cada método generado devuelve un `Mono` o un
`Flux`, nunca un valor bloqueante, de modo que una llamada desde la capa de dominio
se compone dentro de su propia tubería reactiva sin aparcar un hilo. Un método que
mapea a la operación `create` del Listado 7.1 devuelve `Mono<LoanApplicationResponse>`
—el mismo tipo de retorno que declara el controlador—, ahora en el lado del llamante
de la red.

Así es como se ve consumir ese SDK desde la capa de dominio. Este es un boceto
**ilustrativo** —los nombres de las clases generadas siguen las convenciones de
openapi-generator, y el cliente real aparece en el Capítulo 16—, pero la forma es
exactamente lo que escribirás:

```java
// Illustrative: the domain tier calls core through the generated SDK, not a raw WebClient.
public Mono<LoanApplicationResponse> openApplication(CreateLoanApplicationRequest request) {
    return loanApplicationApi.create(request)   // typed method -> POST /api/v1/loan-applications
            .doOnNext(app -> log.info("opened application {}", app.loanApplicationId()));
}
```

Fíjate en lo que está *ausente*: ninguna cadena de URL, ningún builder de
`WebClient`, ningún mapeo JSON, ningún DTO de petición escrito a mano.
`loanApplicationApi.create(...)` es un método generado cuyos tipos de argumento y de
retorno se generan a partir del contrato del servicio core. Si el equipo de core
renombra un campo o cambia un código de estado, regenerar el SDK desplaza la rotura
al tiempo de compilación en cada consumidor, que es precisamente la propiedad que
una flota quiere. Compáralo con la alternativa que cada equipo escribe en caso
contrario:

```java
// The hand-rolled alternative: a URL, a builder, and DTOs duplicated per consumer.
webClient.post()
        .uri("http://core-lending-loan-origination/api/v1/loan-applications")
        .bodyValue(request)
        .retrieve()
        .bodyToMono(LoanApplicationResponse.class);   // and you maintain this DTO by hand
```

Ambas hacen la misma llamada HTTP. El SDK generado hace del contrato la única fuente
de verdad; la versión escrita a mano hace una copia de él en cada llamante, que se
mantiene sincronizada a base de esperanza. El Capítulo 16 construye la capa de
cliente resiliente real —tiempos de espera, reintentos, cortacircuitos— *alrededor*
de SDKs generados como este.

!!! spring "Equivalente en Spring"
    Nada de esto es exclusivo de Firefly: openapi-generator y el generador de
    WebClient son herramientas estándar que cualquier tienda de Spring Boot puede
    adoptar. Lo que Firefly añade es la *convención*: cada servicio core expone su
    contrato en la misma ruta y trae un SDK generado de la misma manera, de modo que
    la capa de dominio consume una flota de servicios a través de un único estilo de
    cliente reactivo y uniforme en lugar de un cajón de sastre por servicio.

## Ejecútalo

No hay una nueva prueba en este capítulo: el contrato vive en el controlador que ya
construiste. Pero las anotaciones OpenAPI son código fuente real, así que demuestra
que el controlador anotado sigue comportándose ejecutando su prueba de extremo a
extremo desde la raíz del reactor.

```text
mvn -q -pl core-lending-loan-origination test \
    -Dtest=LoanApplicationControllerTest
```

Deberías ver pasar las tres pruebas de la capa web:

```text
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
```

!!! tip "Punto de control"
    Abre `LoanApplicationController.java` y confirma el `@Tag` en la clase y el
    `@Operation` en cada uno de los tres manejadores. Esas cuatro anotaciones son la
    mitad entera escrita por humanos del contrato de este servicio; la máquina lee el
    resto de las firmas de los métodos. Si la prueba de arriba está en verde, el
    controlador anotado es exactamente el que el generador (ilustrativo) describiría.

## Lo que has aprendido {.recap}

- Un controlador WebFlux *es* un contrato de API. El framework infiere las rutas,
  los parámetros y los esquemas de petición/respuesta de las anotaciones y firmas que
  ya escribiste; tú solo añades la intención humana con `@Tag` y `@Operation`.
- El controlador de loan-origination lleva metadatos `@Tag` y `@Operation` reales
  (Listado 7.1). **No** trae Swagger UI ni ejecuta el generador de SDK por sí mismo:
  esa maquinaria es el patrón en el que se apoya el build multicapa del Capítulo 14.
- **springdoc-openapi** convierte esas anotaciones en un documento OpenAPI servido en
  `/v3/api-docs`, y **Swagger UI**/**ReDoc** lo renderizan como documentación
  interactiva y de solo lectura. Firefly conecta esto como un conmutador controlado
  por una propiedad.
- **openapi-generator** convierte el mismo documento en un **SDK** tipado y reactivo
  basado en `WebClient`. Cada servicio core trae uno; la capa superior depende de él e
  invoca métodos tipados que devuelven `Mono`/`Flux`. El contrato es el único
  acoplamiento: la regla del **SDK sobre HTTP** que permite a una flota integrarse sin
  compartir un esquema.

## Pruébalo tú mismo {.exercises}

1. **Añade la intención de una tercera operación.** El manejador `list` de
   `LoanApplicationController.java` tiene un summary en `@Operation` pero no documenta
   su parámetro de consulta `status`. Añade una anotación `@Parameter` al argumento
   `status` describiendo el filtro, y vuelve a ejecutar la prueba del controlador para
   confirmar que el servicio sigue arrancando y pasando.
2. **Predice el documento.** Sin ejecutar un generador, escribe a mano la entrada
   `paths` que springdoc emitiría para la operación
   `GET /api/v1/loan-applications/{id}` del Listado 7.1: el tag, el summary, el
   parámetro de ruta `id` y el esquema de respuesta `200`. Luego comprueba tu
   predicción frente a la firma.
3. **Describe un esquema de respuesta.** Abre `LoanApplicationResponse.java` y lista
   cuáles de sus once campos marcaría el generador como obligatorios frente a
   opcionales. ¿Qué hay en el record que le indica que `decisionReason` puede estar
   ausente pero `loanApplicationId` siempre está presente?
4. **Detecta la duplicación.** Compara el fragmento de `WebClient` escrito a mano de
   este capítulo con la llamada al SDK generado de encima. Nombra cada artefacto que
   la versión escrita a mano obliga a mantener a mano a cada consumidor, y por cuál
   única fuente de verdad los reemplaza el SDK generado.

## Adónde ir ahora

Ya tienes el contrato que une las capas de Lumen: un documento derivado de
controladores, renderizado como documentación y consumido como un SDK generado. El
Capítulo 8 pasa al otro lado de un servicio core: la capa de persistencia R2DBC
detrás de ese controlador, donde `LoanApplicationResponse` se ensambla a partir de
filas reales. El cableado multicapa completo, donde el SDK generado de un servicio se
convierte en la dependencia de otro, llega en el Capítulo 16.
