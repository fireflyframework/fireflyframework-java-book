El capítulo 1 expuso los argumentos; este capítulo los pone en marcha. En los
próximos minutos pasarás de una carpeta vacía a un servicio Firefly que arranca,
imprime un banner que te dice en qué capa estás ejecutándote, informa de su propia
salud, sirve un documento OpenAPI que generó para sí mismo y responde a una petición
real: una solicitud de préstamo que vuelve marcada como `SUBMITTED`. Todavía no
entenderás cada línea, y ese es el objetivo. Aquí la meta es ver la *forma completa*
una vez, rápido, para que los capítulos profundos que siguen tengan algo concreto que
profundizar.

Todo lo que ejecutes en este capítulo vive en el reactor de acompañamiento, bajo
`core-lending-loan-origination`: el servicio de la capa **core**, el sistema de
registro para la originación de préstamos. Es el mismo módulo que el resto de la
Parte II hace crecer orgánicamente. Aquí lo tratamos como algo terminado y lo
sacamos a pasear, de arriba abajo, en seis pasos guiados: andamiar, leer el punto de
entrada, leer la única dependencia que importa, arrancarlo y *leer cada línea que
imprime el arranque*, ejercitar la API a mano y demostrarlo todo con una sola prueba.

Una palabra sobre honestidad antes de empezar, porque este libro vive o muere por
ella. Los comandos que *andamian* un proyecto nuevo con la CLI `flywork` se muestran
de forma ilustrativa, porque el reactor que estás leyendo se generó una vez y luego
se confirmó: volver a ejecutar el generador simplemente recrearía lo que ya está en
disco. Todo lo demás es real. El banner es el banner real del framework, extraído del
starter de tu classpath. Las líneas del log de arranque son salida real capturada con
la forma JSON real del framework. Los cuerpos de respuesta son exactamente lo que
devuelve el servicio en ejecución, byte a byte. Y la prueba que lo demuestra todo es
la que ejecutas al final. Los bloques ilustrativos usan vallas de código sencillas;
los fragmentos de fuente verificados y verbatim usan los listados con pestaña de
archivo que conociste en la página de convenciones.

## Paso 1 — Andamiar un servicio con flywork

Firefly incluye una CLI de acompañamiento, `flywork`, que andamia un proyecto a
partir de un arquetipo de capa y arranca la construcción del framework. Tú eliges la
capa (`core`, `domain`, `data` o `application`) y `flywork` deja un módulo Maven
cableado al starter correspondiente, un punto de entrada `@SpringBootApplication` y
la estructura de paquetes convencional. Un comando, y tienes un esqueleto de servicio
que ya hereda toda la base opinada.

```text
$ flywork create \
    --archetype core \
    --group com.firefly.lumen \
    --artifact core-lending-loan-origination \
    --package com.firefly.lumen.core

  Firefly · flywork

  resolved archetype 'core' (Firefly starter-core)
  wrote pom.xml            (parent + 1 tier starter, no versions)
  wrote CoreLendingApplication.java
  created src/main/java/com/firefly/lumen/core
  created src/main/resources/application.yml

  Done. Next:
    cd core-lending-loan-origination
    mvn spring-boot:run
```

Fíjate en lo que `flywork` *no* escribió: una lista de versiones de dependencias
fijadas, un manejador de errores hecho a mano, una configuración de logging JSON, un
endpoint de salud, un banner. Eso viene del starter de la capa, coherente en
versiones y precableado. El andamiaje es deliberadamente fino: un punto de entrada
real y un `pom.xml` real, un `application.yml` casi vacío y casi nada más que tengas
que mantener. Toda la apuesta de Firefly es que cuanto *menos* escriba un andamiaje,
*menos* hay que pueda divergir, auditar y mantener sincronizado en toda una flota. Lo
que no posees, no lo puedes romper.

El cuarto archivo que escribió `flywork`, `application.yml`, merece un vistazo ahora
porque es muy pequeño. Toda la configuración confirmada para este servicio son tres
líneas:

```text
spring:
  application:
    name: core-lending-loan-origination
```

Esa única propiedad —el *nombre* de la aplicación— es la única configuración que el
servicio necesita para arrancar, y la verás aflorar dos veces en este capítulo: una
en el banner, otra en el título OpenAPI generado. Todo lo demás tiene un valor por
defecto sensato horneado en el starter.

!!! note "Término clave — arquetipo de capa"
    Un **arquetipo de capa** es la plantilla de `flywork` para una de las cuatro
    capas de servicio de Firefly. Elegir `core` selecciona `starter-core` y una
    estructura de sistema de registro; elegir `application` selecciona
    `starter-application` y una estructura BFF sin estado. El arquetipo decide qué
    starter heredas y con qué valores por defecto arrancas. Las cuatro capas del
    capítulo 1 se corresponden una a una con cuatro arquetipos.

!!! spring "Equivalente en Spring"
    `flywork create` es la contrapartida de Firefly a Spring Initializr
    (`start.spring.io`). Initializr te pide marcar starters individuales; `flywork` te
    pide elegir una *capa* y luego selecciona por ti el starter de Firefly correcto y
    el POM padre, de modo que una flota de servicios parte de la misma base opinada en
    lugar de cien casillas ligeramente distintas.

## Paso 2 — Un único punto de entrada

Abre la clase que generó `flywork`. Es una aplicación Spring Boot corriente: una
anotación, un método `main`, sin nada de código específico de Firefly.

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/CoreLendingApplication.java | Listado 2.1 — el punto de entrada completo
package com.firefly.lumen.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Boots the core (system-of-record) loan-origination service.
 *
 * <p>Skeleton entry point: later chapters add entities, repositories, and
 * REST controllers under {@code com.firefly.lumen.core}.
 */
@SpringBootApplication
public class CoreLendingApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreLendingApplication.class, args);
    }
}
:::

Vale la pena detenerse aquí, porque es toda la tesis del capítulo 1 hecha concreta. No
hay `@EnableFirefly`, ni bootstrap a medida, ni clase del framework que extender, ni
clase base que heredar. `@SpringBootApplication` y `SpringApplication.run(...)` son
*exactamente* lo que escribirías para cualquier servicio Spring Boot: copia este
archivo a un proyecto vainilla y compila sin cambios.

¿Dónde se engancha entonces el framework? Aquí no. `@SpringBootApplication` agrupa
`@EnableAutoConfiguration`, y al arrancar Spring escanea el classpath en busca de
entradas de autoconfiguración (los archivos `META-INF/spring/...AutoConfiguration.imports`
que envía cada módulo de Firefly). Cada uno es una receta de bean condicional: «si
esta clase está en el classpath y el usuario no ha definido ya este bean, cabléalo».
Todo lo que Firefly añade —los buses CQRS, el logger JSON, el manejador global de
errores, el banner— llega de esa manera, *activado por el starter que estás a punto de
leer*, nunca por código que escribas aquí. Por eso el punto de entrada puede quedarse
tan vacío: el comportamiento está en el classpath, no en la clase.

!!! note "Término clave — autoconfiguración"
    La **autoconfiguración** es el mecanismo de Spring Boot para cablear beans en
    función de lo que hay en el classpath. Una librería envía una clase anotada con
    `@AutoConfiguration` más guardas `@Conditional...` y la registra en `META-INF`;
    Boot la evalúa en el arranque y la aplica solo cuando se cumplen sus condiciones.
    Firefly es, en esencia, un gran conjunto coherente de estas, y por eso «añadir una
    dependencia» equivale a «activar una capacidad».

## Paso 3 — Añade un único starter de capa

El comportamiento vive todo en un solo sitio: las dependencias. Aquí tienes un
fragmento contiguo del `pom.xml` del servicio, desde el padre heredado hasta las
dependencias del framework que arrastra.

::: listing core-lending-loan-origination/pom.xml | Listado 2.2 — el padre y el starter de la capa
    <parent>
        <groupId>com.firefly.lumen</groupId>
        <artifactId>lumen-lending</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <artifactId>core-lending-loan-origination</artifactId>
    <packaging>jar</packaging>

    <name>Lumen Lending - Core (Loan Origination)</name>
    <description>System-of-record tier: persistence + REST. Built on the Firefly core starter.</description>

    <dependencies>
        <!-- Core/infrastructure-layer microservice starter (WebFlux, EDA, CQRS, resilience). -->
        <dependency>
            <groupId>org.fireflyframework</groupId>
            <artifactId>fireflyframework-starter-core</artifactId>
        </dependency>
        <!-- Reactive persistence (R2DBC) abstractions. -->
        <dependency>
            <groupId>org.fireflyframework</groupId>
            <artifactId>fireflyframework-r2dbc</artifactId>
        </dependency>
        <!-- Reactive web layer helpers (controllers, error handling). -->
        <dependency>
            <groupId>org.fireflyframework</groupId>
            <artifactId>fireflyframework-web</artifactId>
        </dependency>
        <!-- Finance-aware Jakarta validation constraints (@ValidAmount, @ValidCurrencyCode). -->
        <dependency>
            <groupId>org.fireflyframework</groupId>
            <artifactId>fireflyframework-validators</artifactId>
        </dependency>
:::

Tres cosas que leer en este fragmento. Primero, las entradas `<dependency>` no llevan
**ningún `<version>`**: el padre heredado (que importa el BOM de Firefly) fija cada
versión de forma centralizada, la historia de coherencia de versiones que el capítulo
3 desgrana por completo. Segundo, la línea destacada es `fireflyframework-starter-core`:
ese único starter es lo que convierte esto de «una app Spring Boot» en «un servicio
core de Firefly», agrupando WebFlux, los buses CQRS de comandos y de consultas, la
fontanería dirigida por eventos, la resiliencia, el logging JSON, el filtrado de
idempotencia y el banner de arranque. Tercero, los módulos de acompañamiento
—`r2dbc`, `web`, `validators`— añaden persistencia reactiva, el modelo de errores web
y las restricciones de validación conscientes de finanzas (`@ValidAmount`,
`@ValidCurrencyCode`) que usan los capítulos posteriores.

Añadiste comportamiento añadiendo una dependencia. Cambiarás el comportamiento, cuando
lo necesites, declarando un bean propio: la autoconfiguración se aparta en cuanto lo
haces. Aquí nada está bloqueado.

!!! spring "Equivalente en Spring"
    `fireflyframework-starter-core` es un starter de Spring Boot como
    `spring-boot-starter-webflux`: una dependencia curada que arrastra un conjunto
    coherente y dispara la autoconfiguración. La diferencia es de altitud: un starter
    vainilla cablea *una* capacidad (la pila web); un starter de capa de Firefly
    cablea la *base opinada completa* para un tipo de servicio. Mismo mecanismo, más
    cosas dentro de la caja.

## Paso 4 — Arráncalo

Con el padre instalado, arranca el servicio a la manera corriente de Spring Boot.
Desde el directorio del módulo:

```text
$ mvn spring-boot:run
```

Lo primero que imprime es el banner, y este es un banner real, no un adorno. El
starter envía un `banner.txt`, y Spring Boot lo renderiza con los valores en vivo de
tu aplicación antes de que se ejecute una sola línea de negocio. Aquí lo tienes, con
los marcadores resueltos a este servicio (un servicio de capa `core` llamado
`core-lending-loan-origination`, sobre Spring Boot 3.5.10):

```text

  _____.__                _____.__
_/ ____\__|______   _____/ ____\  | ___.__.
\   __\|  \_  __ \_/ __ \   __\|  |<   |  |
 |  |  |  ||  | \/\  ___/|  |  |  |_\___  |
 |__|  |__||__|    \___  >__|  |____/ ____|
                       \/           \/
:: firefly-core ::               (v0.1.0-SNAPSHOT)

(c)2025 Firefly Software Foundation
Licensed under Apache 2.0

Spring Boot Version: 3.5.10
Application: core-lending-loan-origination
Application Description: Unknown
Application SwaggerUI: http://localhost:8080/swagger-ui.html
⇩⇩⇩ Logs start below ⇩⇩⇩
```

Léelo línea a línea, porque cada línea te dice algo. Las letras figlet deletrean
*firefly*. La línea `:: firefly-core ::` es el starter anunciando qué **base de capa**
arrancó: un servicio `domain` diría aquí `firefly-domain`, un servicio `application`
diría `firefly-application`; esta es tu prueba de un vistazo de que el starter correcto
está en el classpath. `Application:` hace eco de la única propiedad que fijaste en
`application.yml`. `Application SwaggerUI:` te entrega una URL clicable a la
documentación de la API generada antes de que el servicio haya terminado de arrancar. Y
el marcador `⇩⇩⇩ Logs start below ⇩⇩⇩` es un divisor deliberado: todo lo de arriba es
el banner, todo lo de abajo es salida de log estructurada.

!!! note "Término clave — el banner de Firefly"
    El banner es `banner.txt` dentro del starter de la capa, renderizado por Spring
    Boot en el arranque con marcadores como `${spring.application.name}` y
    `${spring-boot.version}` sustituidos en vivo. No es cosmético: la línea
    `:: firefly-<tier> ::` es una afirmación de un vistazo de qué base opinada está
    ejecutando este proceso, y la línea SwaggerUI es un enlace funcional a la
    documentación. Puedes sobrescribirlo con tu propio `banner.txt`, pero por defecto
    cada servicio de la flota muestra la misma forma.

Debajo del divisor vienen los logs estructurados del framework. Firefly registra como
**JSON por defecto**: un objeto JSON por línea, listo para un agregador de logs, con
un `timestamp`, el `message`, el `logger` abreviado, el `level` y (en los hilos de
petición) `traceId`/`spanId`. Aquí tienes un fragmento recortado y ordenado de la
secuencia de arranque real:

```text
{"timestamp":"2026-06-17T08:21:43.069+0000","message":"FIREFLY EDA - EVENT-DRIVEN ARCHITECTURE LIBRARY","logger":"o.f.e.c.FireflyEdaAutoConfiguration","level":"INFO"}
{"timestamp":"2026-06-17T08:21:44.077+0000","message":"CQRS Query Bus configured with cache support via fireflyframework-cache","logger":"o.f.c.config.CqrsAutoConfiguration","level":"INFO"}
{"timestamp":"2026-06-17T08:21:44.112+0000","message":"Exposing 4 endpoints beneath base path '/actuator'","logger":"o.s.b.a.e.web.EndpointLinksResolver","level":"INFO"}
{"timestamp":"2026-06-17T08:21:44.319+0000","message":"Reactor automatic context propagation enabled — ThreadLocal/MDC values will automatically bridge to Reactor Context across thread boundaries","logger":"o.f.o.t.ReactiveContextPropagationAutoConfiguration","level":"INFO"}
{"timestamp":"2026-06-17T08:21:44.748+0000","message":"Netty started on port 8080 (http)","logger":"o.s.b.w.e.netty.NettyWebServer","level":"INFO"}
{"timestamp":"2026-06-17T08:21:44.772+0000","message":"DefaultCommandBus ready with 0 registered handlers","logger":"o.f.cqrs.command.DefaultCommandBus","level":"INFO"}
{"timestamp":"2026-06-17T08:21:44.772+0000","message":"Started CoreLendingApplication in 2.346 seconds","logger":"c.f.l.core.CoreLendingApplication","level":"INFO"}
```

Ese puñado de líneas *es* la historia de la autoconfiguración del Paso 2, ocurriendo
delante de ti. La línea EDA es la librería dirigida por eventos cableándose a sí
misma. La línea CQRS es el bus de consultas configurándose con soporte de caché; fíjate
en que encontró `0` manejadores registrados, porque este esqueleto aún no ha declarado
ninguno; los capítulos posteriores sí lo harán, y el recuento subirá.
`Exposing 4 endpoints beneath base path '/actuator'` es Actuator encendiéndose. La
línea de propagación de contexto de Reactor es el framework asegurándose de que tu
`traceId` sobreviva a los saltos entre hilos reactivos (el problema del que advertía el
capítulo 1, resuelto por ti). `Netty started on port 8080` significa que el servidor
reactivo está escuchando. Y `Started CoreLendingApplication` es la línea de meta: menos
de tres segundos, sin más código tuyo que el `main` vacío.

!!! note "Término clave — logging estructurado (JSON)"
    Firefly configura el **logging JSON** de serie: cada evento de log es un objeto
    JSON con campos estables (`timestamp`, `level`, `logger`, `message` y
    `traceId`/`spanId` en los hilos de petición). Las máquinas lo parsean sin regex, y
    un identificador de correlación atraviesa toda una petición, incluso a través de
    las fronteras reactivas que lo perderían con un logging ingenuo. No configuraste
    nada de esto; lo hizo el starter.

Dos endpoints vienen gratis con el starter. La **salud de Actuator** informa de si el
servicio *y cada uno de sus subsistemas* están operativos. Firefly registra
indicadores de salud para las piezas que autoconfiguró, de modo que una sola llamada
reúne la caché, los buses CQRS, la librería EDA y la conexión R2DBC (recortado aquí por
espacio):

```text
$ curl -s http://localhost:8080/actuator/health
{"status":"UP","groups":["liveness","readiness"],"components":{
  "cqrs":{"status":"UP","details":{"command_bus":"UP","query_bus":"UP","command_handlers":0,"query_handlers":0}},
  "eda":{"status":"UP","details":{"enabled":true,"message":"All EDA components are healthy"}},
  "r2dbc":{"status":"UP","details":{"database":"H2"}},
  "ping":{"status":"UP"}}}
```

Y el **documento OpenAPI** describe la API HTTP —generada a partir de los controladores,
no escrita a mano— con una Swagger UI servida junto a él en la URL que imprimió el
banner. Fíjate en el `title`: el framework lo construye a partir de tu
`spring.application.name`, así que la única propiedad de tu `application.yml` de tres
líneas aflora por segunda vez, con el sufijo `API`:

```text
$ curl -s http://localhost:8080/v3/api-docs
{"openapi":"3.1.0","info":{"title":"core-lending-loan-origination API","description":"core-lending-loan-origination API Documentation","license":{"name":"Apache 2.0","url":"https://www.apache.org/licenses/LICENSE-2.0"},"version":"1.0.0"}, ... }
```

!!! note "Término clave — salud de Actuator"
    **Actuator** es el conjunto de endpoints de producción de Spring Boot:
    `/actuator/health`, `/actuator/info`, métricas y más. El starter de Firefly
    enciende los adecuados por defecto (el log de arranque contó cuatro) y aporta
    indicadores de salud para los subsistemas que cableó, de modo que cada servicio de
    la flota es observable de la misma manera. Un `"status":"UP"` verde de nivel
    superior es tu primera prueba de vida.

## Paso 5 — Ejercítalo: solicita un préstamo

Un servicio que arranca pero no hace nada no es muy convincente. El servicio core
expone una API de originación de préstamos; vamos a crear una solicitud y a leerla de
vuelta. Haz POST de un cuerpo de petición con el solicitante, el importe, la divisa, el
plazo en meses y el propósito:

```text
$ curl -s -X POST http://localhost:8080/api/v1/loan-applications \
    -H 'Content-Type: application/json' \
    -d '{
          "applicantId": "8b1d0d3c-1f2a-4f7e-9a3b-7d2c4e5f6a7b",
          "requestedAmount": "12500.00",
          "currency": "EUR",
          "termMonths": 36,
          "purpose": "HOME_IMPROVEMENT"
        }'
```

Antes incluso de que se ejecute el método del controlador, la petición pasa por los
filtros del framework, y los registran. En el hilo de petición verás un identificador
de transacción nuevo y el filtro de idempotencia inspeccionando la llamada (estas son
líneas `DEBUG` reales, que muestran el `traceId`/`spanId` que ahora decoran cada log de
este hilo):

```text
{"timestamp":"2026-06-17T08:21:44.132+0000","message":"Generated new transaction ID: ce0c2ede-0e81-430f-9c99-7464a1613884","logger":"o.f.core.config.TransactionFilter","level":"DEBUG","traceId":"bfa32cdc5313c5951ec124b491f07687","spanId":"78466db40897c823"}
{"timestamp":"2026-06-17T08:21:44.134+0000","message":"IdempotencyWebFilter.filter: Processing request POST /api/v1/loan-applications","logger":"o.f.w.i.filter.IdempotencyWebFilter","level":"DEBUG","traceId":"bfa32cdc5313c5951ec124b491f07687","spanId":"78466db40897c823"}
```

El servicio valida el payload, persiste la solicitud y la envía en un solo paso.
Responde `201 Created` con el recurso almacenado. Fíjate en el `loanApplicationId` y el
`applicationNumber` generados, las marcas de tiempo de ciclo de vida que el servicio
estampó y —lo más destacado— el `status`, que es `SUBMITTED`, no `DRAFT`, porque el
servicio envía la solicitud como parte de la creación:

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

Ahora léela de vuelta por su id con un `GET`, usando el `loanApplicationId` que devolvió
la llamada de creación:

```text
$ curl -s http://localhost:8080/api/v1/loan-applications/6fb206b6-288f-4559-a403-460662a32329
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

Ese viaje de ida y vuelta —POST crea y envía, GET lee de vuelta— es la columna
vertebral del servicio core. Ahora ejercita los caminos infelices, porque cómo *falla*
un framework te dice más que cómo tiene éxito.

Pide una solicitud que no existe y no obtienes una traza de pila ni un mazacote a
medida; obtienes un **problem detail RFC 7807** estándar, la misma forma en cada
servicio Firefly. Los miembros de nivel superior son el `type`, `title`, `status`,
`detail` e `instance` del RFC; Firefly añade un objeto `extensions` que lleva el
contexto de traza y una `suggestion` de remediación:

```text
$ curl -s http://localhost:8080/api/v1/loan-applications/00000000-0000-0000-0000-000000000000
```

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

Envía un payload incorrecto —un `requestedAmount` negativo, que viola `@ValidAmount`— y
la validación no llega siquiera a tu servicio. El framework lo rechaza con `400` y la
misma forma de problem detail, esta vez con una URI `type` tipada y un array `errors`
bajo `extensions` que señala con precisión el campo ofensor:

```text
$ curl -s -X POST http://localhost:8080/api/v1/loan-applications \
    -H 'Content-Type: application/json' \
    -d '{"applicantId":"8b1d0d3c-1f2a-4f7e-9a3b-7d2c4e5f6a7b","requestedAmount":"-5.00","currency":"EUR","termMonths":36,"purpose":"HOME_IMPROVEMENT"}'
```

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

No escribiste ese manejador de errores, ni escribiste ninguna de las dos formas de
respuesta. Lo hizo el starter, una vez, para toda la flota, de modo que un 404 de este
servicio tiene exactamente el mismo aspecto que un 404 de cualquier otro servicio
Firefly, con contexto de traza incluido.

!!! note "Término clave — problem detail RFC 7807"
    **RFC 7807** («Problem Details for HTTP APIs») es el estándar del IETF para cuerpos
    de error legibles por máquina, servidos como `application/problem+json`. Sus
    miembros son `type` (una URI para el tipo de error), `title`, `status`, `detail` e
    `instance`. El `GlobalExceptionHandler` de Firefly emite esta forma para cada error
    no manejado y mete los extras del framework —identificadores de traza, una pista de
    reintento, una `suggestion` de remediación— en el objeto estándar `extensions`, de
    modo que los clientes pueden confiar en un único contrato de errores en todas
    partes.

!!! spring "Equivalente en Spring"
    Spring Framework 6 trae su propio `ProblemDetail` y permite que un
    `@ControllerAdvice` mapee excepciones a él, pero sigues escribiendo ese advice en
    cada servicio. Firefly registra un único `GlobalExceptionHandler` en el starter, de
    modo que el contrato RFC 7807 es de toda la flota por defecto y está enriquecido con
    contexto de traza. Mismo estándar, cero cableado por servicio.

## Paso 6 — Demuéstralo

No necesitas un servidor en ejecución ni Docker para demostrar todo esto: el reactor
incluye una prueba de fragmento que arranca el contexto reactivo completo contra una H2
en memoria (runtime R2DBC más una migración Flyway), conduce la API real con
`WebTestClient` y comprueba el viaje de ida y vuelta crear-y-leer, el 404 RFC 7807 y el
rechazo de validación. Son los *mismos* caminos de código que acabas de ejercitar a
mano, ejecutados sin interfaz. Desde `samples/lumen-lending`, ejecuta:

```text
$ mvn -q -pl core-lending-loan-origination test
```

Las pruebas de todo el módulo pasan: dieciocho de ellas repartidas entre el modelo de
dominio, los ayudantes reactivos y la capa web:

```text
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in com.firefly.lumen.core.web.LoanApplicationControllerTest
[INFO] Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Los tres casos de `LoanApplicationControllerTest` son exactamente las tres cosas que
acabas de hacer con `curl`: crear-y-leer-de-vuelta (comprobando el estado `SUBMITTED`),
el 404 por id inexistente y el rechazo del payload incorrecto. Los otros quince
ejercitan el modelo de dominio y la fontanería reactiva que conocerás en los capítulos
5 y siguientes.

!!! tip "Punto de control"
    Ejecuta `mvn -q -pl core-lending-loan-origination test` desde
    `samples/lumen-lending`. Un `Tests run: 18, Failures: 0` verde y un `BUILD SUCCESS`
    final significan que toda la forma —arrancar, validar, persistir, enviar, leer de
    vuelta y los errores RFC 7807— funciona en tu máquina. Esa línea verde es el
    contrato contra el que se comprueba cada listado de este libro.

## Lo que has construido {.recap}

- Andamiaste un servicio **core** con `flywork` (de forma ilustrativa), viste que el
  punto de entrada generado es un `@SpringBootApplication` sencillo con un método
  `main` y un `application.yml` de tres líneas, y que todo el comportamiento llega a
  través de un único **starter de capa** en el classpath vía **autoconfiguración**.
- Leíste el fragmento del `pom.xml` y viste el movimiento del capítulo 1 en la
  práctica: **heredar un padre, añadir `fireflyframework-starter-core`, omitir las
  versiones.**
- Arrancaste el servicio y leíste el arranque, línea a línea: el banner real
  `:: firefly-core ::`, los logs JSON de autoconfiguración (EDA, CQRS, propagación de
  contexto de Reactor), `Netty started on port 8080`, cuatro endpoints de Actuator, el
  documento OpenAPI generado, y un final `Started ... in 2.346 seconds`.
- Ejercitaste la API de originación de préstamos: un POST que crea y **envía** una
  solicitud (estado `SUBMITTED`), un GET que la lee de vuelta y dos errores **RFC 7807**
  consistentes —un 404 por un id inexistente y un 400 por un importe incorrecto—, ambos
  llevando contexto de traza en `extensions`.
- Ejecutaste las pruebas del reactor y viste `Tests run: 18, Failures: 0` con
  `BUILD SUCCESS`: el mismo viaje de ida y vuelta, verificado de extremo a extremo
  contra una H2 en memoria.

## Pruébalo tú mismo {.exercises}

1. **Lee la prueba real.** Abre
   `core-lending-loan-origination/src/test/java/com/firefly/lumen/core/web/LoanApplicationControllerTest.java`
   y empareja cada `@Test` con un `curl` de este capítulo. ¿Qué aserción demuestra el
   estado `SUBMITTED`? ¿Cuál demuestra la forma RFC 7807 (pista: comprueba la ruta JSON
   `$.status`)?
2. **Rompe el payload.** El caso `rejectsAnInvalidPayload` de esa prueba envía un
   `requestedAmount` de `-5.00` y espera `400 Bad Request`. Cámbialo a un importe
   positivo y vuelve a ejecutar `mvn -q -pl core-lending-loan-origination test`: ¿qué
   falla, y qué te dice eso sobre `@ValidAmount` y sobre dónde se ejecuta la validación
   en la tubería de la petición?
3. **Observa cómo cambia el banner.** La línea `:: firefly-core ::` es la capa *core*
   anunciándose. Mira los módulos `domain` y `application` que hay en otra parte del
   reactor y predice qué dice la línea de capa de su banner. Luego confírmalo desde sus
   starters.
4. **Cuenta el comportamiento gratis.** Vuelve a leer el fragmento del `pom.xml` en
   `core-lending-loan-origination/pom.xml` y el log de arranque. Enumera cada capacidad
   que obtuviste *sin escribir código* —logging JSON, el banner, manejo de errores,
   validación, salud, OpenAPI, buses CQRS, idempotencia, propagación de traza— y anota
   en qué dependencia o línea de log llega cada una.
5. **Rastrea el punto de entrada.** Abre
   `core-lending-loan-origination/src/main/java/com/firefly/lumen/core/CoreLendingApplication.java`
   y confirma que no hay nada específico de Firefly en él. ¿Dónde se engancha entonces
   el framework? (Pista: la respuesta es `@EnableAutoConfiguration` y el classpath, no
   la clase.)

## Adónde ir ahora

Has visto la forma completa; ahora el resto del libro baja el ritmo y la construye como
es debido. El capítulo 3 explica el POM padre y el BOM que hicieron posibles las
dependencias sin versión del Listado 2.2: la historia de coherencia de versiones que
hay debajo de este inicio rápido. A partir de ahí, la Parte II reconstruye este mismo
servicio capa por capa, de la manera honesta, un fragmento verificado cada vez.
