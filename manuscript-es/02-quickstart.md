El capítulo 1 expuso los argumentos; este capítulo los pone en marcha. En los
próximos minutos pasarás de una carpeta vacía a un servicio Firefly que arranca,
informa de su propia salud, sirve un documento OpenAPI y responde a una petición
real: una solicitud de préstamo que vuelve sellada como `SUBMITTED`. Todavía no
entenderás cada línea, y ese es precisamente el objetivo. Aquí la meta es ver la
*forma completa* una vez, rápido, para que los capítulos en profundidad que vienen
a continuación tengan algo concreto que profundizar.

Todo lo que ejecutas en este capítulo vive en el reactor de acompañamiento, bajo
`core-lending-loan-origination`: el servicio de la capa **core**, el sistema de
registro para la originación de préstamos. Es el mismo módulo que el resto de la
Parte II hace crecer orgánicamente. Aquí lo tratamos como algo terminado y lo
sacamos a dar una vuelta.

Una nota de honestidad antes de empezar. Algunos de los comandos de más abajo —los
que *generan el andamiaje* de un proyecto nuevo con la CLI `flywork`— se muestran a
título ilustrativo, porque el reactor que estás leyendo se generó una vez y luego
se confirmó. Los comandos que *arrancan y ejercitan* el servicio son reales, y la
prueba que lo demuestra es la que ejecutarás al final. Los bloques ilustrativos
usan vallas de código simples; los fragmentos verificados y literales usan los
listados con pestañas de fichero que conociste en la página de convenciones.

## Paso 1 — Generar el andamiaje de un servicio con flywork

Firefly incluye una CLI de acompañamiento, `flywork`, que genera el andamiaje de un
proyecto a partir de un arquetipo de capa y arranca la compilación del framework.
Eliges la capa —`core`, `domain`, `data` o `application`— y `flywork` despliega un
módulo Maven cableado al starter correspondiente, un punto de entrada
`@SpringBootApplication` y la distribución de paquetes convencional.

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
fijadas, un manejador de errores hecho a mano, una configuración de logging en
JSON, un endpoint de salud. Todo eso viene del starter de la capa, coherente en
versiones y precableado. El andamiaje es deliberadamente fino: un punto de entrada
real y un `pom.xml` real, y casi nada más que tengas que mantener.

!!! note "Termino clave — arquetipo de capa"
    Un **arquetipo de capa** es la plantilla de `flywork` para una de las cuatro
    capas de servicio de Firefly. Elegir `core` selecciona `starter-core` y una
    distribución de sistema de registro; elegir `application` selecciona
    `starter-application` y una distribución BFF sin estado. El arquetipo decide qué
    starter heredas y con qué valores por defecto arrancas. Las cuatro capas del
    capítulo 1 se corresponden una a una con cuatro arquetipos.

!!! spring "Equivalente en Spring"
    `flywork create` es la contrapartida en Firefly de Spring Initializr
    (`start.spring.io`). Initializr te pide marcar starters individuales; `flywork`
    te pide elegir una *capa* y luego selecciona por ti el starter de Firefly
    correcto y el POM padre, de modo que una flota de servicios parte de la misma
    base con criterio en lugar de cien casillas ligeramente distintas.

## Paso 2 — Un único punto de entrada

Abre la clase que generó `flywork`. Es una aplicación Spring Boot corriente: una
anotación, un método `main`, nada de código específico de Firefly.

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

Merece la pena detenerse aquí, porque es toda la tesis del capítulo 1 hecha
concreta. No hay `@EnableFirefly`, ni bootstrap personalizado, ni ninguna clase del
framework que extender. `@SpringBootApplication` y `SpringApplication.run(...)` son
exactamente lo que escribirías para cualquier servicio Spring Boot. Todo lo que
Firefly añade llega a través de la *autoconfiguración* en el classpath, activada por
el starter que estás a punto de leer, no a través de código que escribas aquí.

## Paso 3 — Añadir un único starter de capa

Todo el comportamiento vive en un solo sitio: las dependencias. Aquí tienes un
fragmento contiguo del `pom.xml` del servicio, desde el padre heredado hasta las
dependencias del framework que arrastra.

::: listing core-lending-loan-origination/pom.xml | Listado 2.2 — el padre y el starter de capa
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

Tres cosas que leer en este fragmento. Primera: las entradas `<dependency>` no
llevan **ningún `<version>`**: el padre heredado (que importa el BOM de Firefly)
fija centralmente cada versión, la historia de coherencia de versiones que el
capítulo 3 desgrana por completo. Segunda: la línea protagonista es
`fireflyframework-starter-core`; ese único starter es lo que convierte esto de «una
app Spring Boot» en «un servicio core de Firefly», empaquetando WebFlux, los buses
CQRS, la fontanería orientada a eventos, la resiliencia, el logging en JSON y el
banner de arranque. Tercera: los módulos de acompañamiento —`r2dbc`, `web`,
`validators`— añaden persistencia reactiva, el modelo de errores web y restricciones
de validación con criterio financiero (`@ValidAmount`, `@ValidCurrencyCode`) que los
capítulos posteriores usan.

Añadiste comportamiento añadiendo una dependencia. Cambiarás el comportamiento,
cuando lo necesites, declarando un bean. Aquí no hay nada bloqueado.

!!! spring "Equivalente en Spring"
    `fireflyframework-starter-core` es un starter de Spring Boot como
    `spring-boot-starter-webflux`: una dependencia curada que arrastra un conjunto
    coherente y dispara la autoconfiguración. La diferencia es la altitud: un starter
    corriente cablea *una* capacidad (la pila web); un starter de capa de Firefly
    cablea *toda* la base con criterio para un tipo de servicio. Mismo mecanismo, más
    cosas en la caja.

## Paso 4 — Arrancarlo

Con el padre instalado, arranca el servicio de la forma habitual de Spring Boot.

```text
$ mvn spring-boot:run

   _____ _           __ _
  |  ___(_)_ __ ___ / _| |_   _
  | |_  | | '__/ _ \ |_| | | | |
  |  _| | | | |  __/  _| | |_| |
  |_|   |_|_|  \___|_| |_|\__, |
                          |___/   Firefly Framework

  :: core-lending-loan-origination ::   (starter-core)

INFO  c.f.l.core.CoreLendingApplication        : Starting CoreLendingApplication
INFO  o.s.b.web.embedded.netty.NettyWebServer  : Netty started on port 8080
INFO  o.f.cqrs.command.DefaultCommandBus       : DefaultCommandBus ready with 0 registered handlers
INFO  c.f.l.core.CoreLendingApplication        : Started CoreLendingApplication in 2.5 seconds
```

Ese banner no es cosmético: es el starter anunciando qué base de capa arrancó, y la
línea de `DefaultCommandBus` es la infraestructura CQRS autoconfigurándose, lista
para los manejadores que los capítulos posteriores registran. El servicio está ahora
escuchando en el bucle de eventos de Netty.

Dos endpoints vienen gratis con el starter. La **salud de Actuator** informa de si
el servicio y sus dependencias están en marcha:

```text
$ curl -s http://localhost:8080/actuator/health
{"status":"UP"}
```

Y el **documento OpenAPI** describe la API HTTP —generado, no escrito a mano— con una
Swagger UI servida junto a él:

```text
$ curl -s http://localhost:8080/v3/api-docs
{"openapi":"3.0.1","info":{"title":"core-lending-loan-origination", ... }}
```

!!! note "Termino clave — salud de Actuator"
    **Actuator** es el conjunto de endpoints de producción de Spring Boot:
    `/actuator/health`, `/actuator/info`, métricas y más. El starter de Firefly
    activa los adecuados por defecto, de modo que cada servicio de la flota es
    observable de la misma manera. Un `{"status":"UP"}` en verde es tu primera prueba
    de vida.

## Paso 5 — Ejercitarlo: solicitar un préstamo

Un servicio que arranca pero no hace nada no resulta muy convincente. El servicio
core expone una API de originación de préstamos; vamos a crear una solicitud y a
leerla de vuelta. Envía con POST un cuerpo de petición con el prestatario, el
importe, el plazo y la finalidad:

```text
$ curl -s -X POST http://localhost:8080/api/v1/loan-applications \
    -H 'Content-Type: application/json' \
    -d '{
          "borrowerPartyId": "8b1d0d3c-1f2a-4f7e-9a3b-7d2c4e5f6a7b",
          "requestedAmount": "12500.00",
          "currency": "EUR",
          "termInMonths": 36,
          "purpose": "HOME_IMPROVEMENT"
        }'
```

El servicio valida el payload, persiste la solicitud y la envía en un solo paso.
Responde `201 Created` con el recurso almacenado; fíjate en el `loanApplicationId`
generado y en el estado `SUBMITTED`:

```json
{
  "loanApplicationId": "55ccb890-e344-4bcb-ba5c-0dfbdec05a95",
  "borrowerPartyId": "8b1d0d3c-1f2a-4f7e-9a3b-7d2c4e5f6a7b",
  "requestedAmount": "12500.00",
  "currency": "EUR",
  "termInMonths": 36,
  "purpose": "HOME_IMPROVEMENT",
  "status": "SUBMITTED"
}
```

Ahora léela de vuelta por su id con un `GET`:

```text
$ curl -s http://localhost:8080/api/v1/loan-applications/55ccb890-e344-4bcb-ba5c-0dfbdec05a95
```

```json
{
  "loanApplicationId": "55ccb890-e344-4bcb-ba5c-0dfbdec05a95",
  "currency": "EUR",
  "purpose": "HOME_IMPROVEMENT",
  "status": "SUBMITTED"
}
```

Ese viaje de ida y vuelta —POST crea y envía, GET lee de vuelta— es la columna
vertebral del servicio core. Pide algo que no existe y no obtienes una traza de pila
ni un mazacote a medida; obtienes un problem detail estándar **RFC 7807**, con la
misma forma en cada servicio Firefly:

```text
$ curl -s http://localhost:8080/api/v1/loan-applications/00000000-0000-0000-0000-000000000000
```

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "Loan application not found: 00000000-0000-0000-0000-000000000000"
}
```

Tú no escribiste ese manejador de errores. Lo escribió el starter, una vez, para
toda la flota.

## Ejecútalo

No necesitas un servidor en marcha ni Docker para demostrar todo esto: el reactor
incluye una prueba de corte que arranca el contexto reactivo completo contra una H2
en memoria, ejercita la API real con `WebTestClient` y comprueba el viaje de ida y
vuelta crear-luego-leer, el problem detail 404 y el rechazo de validación. Desde
`samples/lumen-lending`, ejecuta:

```text
$ mvn -q -pl core-lending-loan-origination test
```

Las pruebas del módulo pasan, incluidos los tres casos de la capa web que acabas de
ejercitar a mano:

```text
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in com.firefly.lumen.core.web.LoanApplicationControllerTest
```

!!! tip "Punto de control"
    Ejecuta `mvn -q -pl core-lending-loan-origination test` desde
    `samples/lumen-lending`. Un `Tests run: 3, Failures: 0` en verde sobre
    `LoanApplicationControllerTest` significa que toda la forma —arrancar, validar,
    persistir, enviar, leer de vuelta y errores RFC 7807— funciona en tu máquina. Esa
    línea verde es el contrato contra el que se comprueba cada listado de este libro.

## Lo que has construido {.recap}

- Generaste el andamiaje de un servicio **core** con `flywork` (a título
  ilustrativo), viste que el punto de entrada generado es un `@SpringBootApplication`
  simple con un método `main`, y que todo el comportamiento llega a través de un
  único **starter de capa** en el classpath.
- Leíste el fragmento del `pom.xml` y viste en la práctica el movimiento del capítulo
  1: **hereda un padre, añade `fireflyframework-starter-core`, omite las versiones.**
- Arrancaste el servicio —banner, salud de Actuator, OpenAPI generado— y ejercitaste
  su API de originación de préstamos: un POST que crea y **envía** una solicitud
  (estado `SUBMITTED`), un GET que la lee de vuelta y un 404 **RFC 7807** coherente.
- Ejecutaste la prueba de corte del reactor y viste `Tests run: 3, Failures: 0`: el
  mismo viaje de ida y vuelta, verificado de extremo a extremo contra una H2 en
  memoria.

## Pruebalo tu mismo {.exercises}

1. **Lee la prueba real.** Abre
   `core-lending-loan-origination/src/test/java/com/firefly/lumen/core/web/LoanApplicationControllerTest.java`
   y empareja cada `@Test` con un `curl` de este capítulo. ¿Qué aserción demuestra el
   estado `SUBMITTED`? ¿Cuál demuestra la forma RFC 7807?
2. **Rompe el payload.** El caso `rejectsAnInvalidPayload` de esa prueba envía un
   `requestedAmount` de `-5.00` y espera `400 Bad Request`. Cámbialo por un importe
   positivo y vuelve a ejecutar `mvn -q -pl core-lending-loan-origination test`: ¿qué
   falla, y qué te dice eso sobre `@ValidAmount`?
3. **Cuenta el comportamiento gratuito.** Vuelve a leer el fragmento del `pom.xml` en
   `core-lending-loan-origination/pom.xml`. Enumera cada capacidad que conseguiste
   *sin escribir código* —manejo de errores, validación, logging, salud, OpenAPI— y
   anota en qué dependencia llega cada una.
4. **Rastrea el punto de entrada.** Abre
   `core-lending-loan-origination/src/main/java/com/firefly/lumen/core/CoreLendingApplication.java`
   y confirma que no hay nada específico de Firefly en él. Entonces, ¿dónde se
   engancha el framework? (Pista: la respuesta está en el classpath, no en la clase.)

## Adonde ir ahora

Has visto la forma completa; ahora el resto del libro baja el ritmo y la construye
como es debido. El capítulo 3 explica el POM padre y el BOM que hicieron posibles las
dependencias sin versión del Listado 2.2: la historia de coherencia de versiones que
hay debajo de este quickstart. A partir de ahí, la Parte II reconstruye este mismo
servicio capa por capa, de la forma honesta, un fragmento verificado cada vez.
