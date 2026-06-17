`flywork` es la herramienta de línea de comandos que acompaña a Firefly — la que
arranca el framework en una máquina nueva y genera la estructura de servicios
nuevos para que empiecen su vida con las convenciones correctas. (Resulta que está
escrita en Go y se distribuye como un único binario; ese es un detalle de
implementación: nunca escribes Go para usarla, y este sigue siendo un libro sobre
el framework de Java.)

Este apéndice es dos cosas: una guía de campo de la CLI y un registro de
resolución de problemas de los problemas *reales* que te encontrarás al ejecutar el
reactor que la acompaña — los mismos que el ejemplo de este libro
(`samples/lumen-lending`) sufrió y dejó identificados. Las secciones sobre la CLI
son ilustrativas; la sección de resolución de problemas está anclada en lo que
realmente salió mal y en lo que lo arregló.

## Arrancar el framework

El framework son muchos repositorios, y se compilan en orden de dependencias.
`flywork setup` los clona todos y los instala en tu repositorio local de Maven
(`~/.m2`) en la secuencia correcta, de modo que `fireflyframework-kernel` se compile
antes que los módulos que dependen de él, y así sucesivamente subiendo por el grafo.

```text
$ flywork setup
Resolving framework dependency graph … 41 repositories, 7 layers
Layer 1/7  kernel, utils, validators …            installed
Layer 2/7  observability, cache, eda …            installed
Layer 3/7  r2dbc, web, cqrs …                     installed
…
Layer 7/7  starter-core, starter-domain …         installed
Done. Framework 26.06.01 available in ~/.m2.
```

El grafo es un DAG, no una lista plana. `flywork setup` ordena topológicamente los
~41 repositorios en capas e instala cada capa antes que la siguiente, porque un
módulo de la capa 3 (digamos `fireflyframework-cqrs`) compila contra artefactos de
las capas 1 y 2. Dentro de una capa los módulos son independientes y podrían
compilarse en paralelo. La última capa son los starters de capa — `starter-core`,
`starter-domain`, `starter-application` — porque se sitúan en lo alto del grafo y
reúnen todo lo que tienen por debajo en una sola dependencia curada.

!!! note "La mayoría de los lectores no necesitan `flywork setup`"
    La versión a la que apunta este libro — **26.06.01** — está publicada en **Maven
    Central**. Una compilación normal resuelve `org.fireflyframework:*` directamente
    desde Central (y la cachea en `~/.m2`), así que puedes compilar y ejecutar el
    ejemplo sin clonar nunca el framework. Solo necesitas `flywork setup` cuando
    estás trabajando *sobre el propio framework*, o quieres una compilación
    totalmente offline de un `-SNAPSHOT` no publicado. Si una compilación resuelve
    `26.06.01` desde Central, ya está — sáltate la instalación por completo.

Una vez que el framework es resoluble — desde Central o desde un `flywork setup`
local —, un proyecto que herede de `fireflyframework-parent` o importe
`fireflyframework-bom` resuelve sus versiones de forma centralizada y coherente, la
historia de coherencia de versiones que el Capítulo 3 desarrolla en detalle.

## Generar la estructura de un servicio

`flywork create` genera un proyecto nuevo a partir de uno de cuatro arquetipos, cada
uno alineado con una capa (Capítulo 14):

```text
$ flywork create \
    --archetype domain \
    --group com.firefly.lumen \
    --artifact domain-lending-loan-origination \
    --package com.firefly.lumen.domain
```

| Arquetipo | Produce | Starter |
|---|---|---|
| `core` | un servicio sistema de registro (R2DBC, Flyway, web reactivo) | `fireflyframework-starter-core` |
| `domain` | un servicio de orquestación (CQRS, saga, EDA) | `fireflyframework-starter-domain` |
| `application` | un servicio de experiencia/BFF (`@Secure`, clientes SDK) | `fireflyframework-starter-application` |
| `library` | un módulo de biblioteca compartida (sin starter, sin `main`) | — |

Estos son los mismos cuatro arquetipos a partir de los cuales se generaron las tres
capas ejecutables del ejemplo: `core-lending-loan-origination` (core, sirve en
**:8081**), `domain-lending-loan-origination` (domain, **:8082**) y `exp-lending`
(application, **:8080**). El arquetipo `library` es la oveja negra — produce un
módulo JAR sencillo con el parent y las convenciones pero *sin* starter de capa y
sin `@SpringBootApplication`, para el código compartido (DTOs, interfaces de cliente)
que se sitúa entre capas.

El proyecto generado ya tiene el parent correcto, el starter de capa, una
disposición de paquetes sensata, un `application.yml` y un smoke test que pasa — la
misma forma que los módulos que fuiste construyendo a lo largo de este libro. Lo que
deliberadamente *no* escribe: versiones de dependencias fijadas, un manejador de
errores, una configuración de logging en JSON, un endpoint de salud o un banner.
Eso llega a través del starter de capa mediante autoconfiguración (Capítulo 2). El
andamiaje es delgado a propósito: lo que no posees, no lo puedes romper.

!!! spring "Equivalente en Spring"
    `flywork create` es la contrapartida en Firefly de Spring Initializr
    (`start.spring.io`). Initializr te pide marcar starters individuales; `flywork`
    te pide elegir una *capa*, y luego selecciona por ti el starter de Firefly
    correcto y el POM parent — de modo que una flota de servicios parte de la misma
    base opinada en lugar de un centenar de casillas ligeramente distintas.

!!! note "El reactor se generó una vez y luego se versionó"
    Los comandos `flywork create` de este libro se muestran de forma ilustrativa. El
    reactor del ejemplo se generó una vez y luego se versionó y se hizo crecer a
    mano — volver a ejecutar el generador simplemente recrearía lo que ya está en
    disco. Todo lo que *ejecutas* contra el ejemplo (`mvn spring-boot:run`, las
    llamadas `curl`, los tests) es real.

## Ejecutar un servicio generado

Cada servicio generado es una aplicación Spring Boot corriente, ejecutable de dos
maneras desde el directorio de su módulo:

```text
# during development — the Maven plugin compiles and runs in one step
$ mvn spring-boot:run

# as a standalone fat JAR — build once, run anywhere with a JDK
$ mvn clean package
$ java -jar target/<artifact>-<version>.jar
```

La vía `java -jar` solo funciona porque la compilación cablea el goal **repackage**
de Spring Boot — el paso que reescribe el JAR sencillo y lo convierte en un fat JAR
ejecutable con un launcher embebido y todas las dependencias dentro. En el ejemplo
esto es la ejecución `repackage` del `spring-boot-maven-plugin`; sin ella, `java
-jar` falla con `no main manifest attribute`. Si generas la estructura con `flywork`
la ejecución está presente por defecto; si escribes un módulo a mano, cablearla es
lo único que convierte la salida de `package` en algo ejecutable. Consulta la nota
de resolución de problemas más abajo.

El ejemplo se ejecuta de extremo a extremo **sin Docker, sin base de datos externa y
sin broker de mensajes**: la capa core persiste en **H2** en memoria (R2DBC en
tiempo de ejecución, JDBC para Flyway), y los eventos fluyen sobre el transporte
**`APPLICATION_EVENT`** dentro de la propia JVM. Con las tres capas levantadas, el
camino en vivo es un único POST al BFF:

```text
$ curl -s -X POST localhost:8080/api/v1/experience/lending/applications \
    -H 'Content-Type: application/json' \
    -d '{"productId":"11111111-1111-1111-1111-111111111111","requestedAmount":25000.00,"term":36,"purpose":"HOME_IMPROVEMENT","simulationId":"22222222-2222-2222-2222-222222222222"}'
# 201 -> {"applicationId":"...","status":"SUBMITTED",...}
```

Ese `201 SUBMITTED` es la capa de experiencia llamando al dominio por HTTP, el
dominio ejecutando la `RegisterApplicationSaga`, y el paso raíz de la saga
escribiendo en el sistema de registro de la capa core por HTTP — el flujo completo
**exp → domain → core**, todo sobre H2.

## Resolución de problemas

Una breve guía de campo de los problemas que es más probable que te encuentres,
ordenados aproximadamente según lo pronto que muerden. El primer grupo es lo que el
propio ejemplo sufrió.

!!! tip "Punto de control — ¿es resoluble el framework?"
    Si una compilación falla al resolver `org.fireflyframework:*`, confirma primero
    que estás conectado (Central tiene **26.06.01**) o que
    `~/.m2/repository/org/fireflyframework/` está poblado por `flywork setup`. Todos
    los demás problemas de más abajo asumen que los artefactos del framework
    resuelven.

- **`error: release version 25 not supported` (o `invalid target release: 25`).**
  El POM parent fija la línea base del lenguaje en **Java 25** (Capítulo 3), y tu JDK
  instalado es más antiguo — normalmente JDK 21. Tienes dos soluciones honestas:
  instalar un toolchain de JDK 25, o compilar contra el LTS con el perfil de rebaja
  del parent:

  ```text
  $ mvn -Pjava21 verify          # retargets source/target to Java 21
  $ mvn -Pjava21 spring-boot:run # run a single tier on Java 21
  ```

  `-Pjava21` rebaja únicamente el *target de bytecode y el nivel de source*; no
  degrada tu JDK y no puede conjurar características de lenguaje de Java 25. Es la vía
  de escape para las organizaciones que aún no están en 25 — Java 25 sigue siendo el
  valor por defecto. Este es, con diferencia, el fallo más común en la primera
  ejecución, y es puramente un desajuste de toolchain, no un problema del framework.

- **`no main manifest attribute` al ejecutar `java -jar target/...jar`.** El JAR no
  se reempaquetó en un fat JAR ejecutable, así que no tiene launcher. Confirma que la
  compilación del módulo cablea la ejecución `repackage` del plugin de Maven de
  Spring Boot (está presente en todos los módulos generados por `flywork` y en las
  tres capas del ejemplo). Con ella, `mvn clean package` produce un JAR ejecutable;
  sin ella, prefiere `mvn spring-boot:run`, que no necesita reempaquetado.

- **Los endpoints `@Secure` devuelven `401`/`403` cuando ejecutas en local.** El
  endpoint está genuinamente protegido — el BFF de la capa de experiencia custodia
  sus métodos con `@Secure` (Capítulo 19). En una ejecución local sin proveedor de
  identidad, cada llamada es anónima y se rechaza. Para el stack local del ejemplo, la
  *aplicación* de la seguridad está desactivada en la configuración ejecutable de la
  capa de experiencia:

  ```text
  firefly:
    application:
      security:
        enabled: false
  ```

  Esa es exactamente la clave `firefly.application.security.enabled=false` del
  `src/main/resources/application.yml` de `exp-lending`. Desactiva la aplicación de
  la seguridad para el uso local práctico; *no* existe en el perfil de test, donde el
  slice suministra en su lugar un contexto de seguridad de test permisivo. Nunca
  despliegues un servicio con la aplicación de la seguridad desactivada — esto es
  únicamente una comodidad para el desarrollo local.

- **Un servicio core no arranca sin una base de datos, o quiere un Docker que no
  tienes.** El ejemplo se ejecuta sobre **H2** en memoria sin contenedor. Dos cosas
  hacen que eso funcione: el driver **R2DBC H2** (`r2dbc-h2`) proporciona acceso
  reactivo en tiempo de ejecución, y el driver **H2 JDBC** ejecuta las migraciones de
  **Flyway** al arrancar. En el módulo core del ejemplo estos dos drivers se movieron
  del scope `test` al scope `runtime` para que `mvn spring-boot:run` y `java -jar`
  puedan arrancar sobre H2 sin una BD externa (el comportamiento de los tests no
  cambia — los tests ya tenían H2 en el classpath de test). Si tu servicio core exige
  un Postgres real al arrancar, comprueba que esos drivers estén presentes en
  `runtime` y que tu `application.yml` apunte R2DBC y Flyway a una URL de H2.

- **Un `@EventListener` nunca se dispara.** El runtime hace coincidir los
  `eventTypes` por el **nombre simple de la clase** del payload (p. ej.
  `LoanApplicationSubmitted`), no por el nombre totalmente cualificado. Un desajuste
  ahí es la causa habitual. Confirma también que EDA está habilitado
  (`firefly.eda.enabled=true`) con un transporte configurado — el ejemplo usa el
  transporte `APPLICATION_EVENT` dentro de la propia JVM, que no necesita broker. El
  Capítulo 11 cubre esto en profundidad.

- **`BUILD FAILURE` al resolver un artefacto del framework, o un confuso
  `NoSuchMethodError` en tiempo de ejecución.** La versión que declaraste no está
  disponible, o has mezclado dos versiones del framework. Alinéalo todo a una sola
  línea de release — **26.06.01** para este libro — heredando de
  `fireflyframework-parent` o importando el `fireflyframework-bom` correspondiente, y
  nunca fijes a mano la `<version>` de un artefacto de Firefly. Mezclar dos versiones
  del framework es la causa más común de un `NoSuchMethodError` que compila
  limpiamente pero revienta en tiempo de ejecución.

- **Un endpoint reactivo se bloquea bajo carga / paradas ocasionales.** Algo en el
  camino de la petición está bloqueando el bucle de eventos — una llamada JDBC, un
  `.block()`, un SDK síncrono de terceros. Sácalo del bucle de eventos
  (`subscribeOn(Schedulers.boundedElastic())`) o sustitúyelo por un cliente reactivo.
  Vuelve a leer la advertencia de "nunca bloquees" del Capítulo 5.

- **Falta un ID de correlación o de traza en un log de un servicio aguas abajo.**
  Confirma que `fireflyframework-observability` está presente — habilita la
  propagación automática del contexto de Reactor. Sin él, los valores de
  `ThreadLocal`/MDC no siguen a los operadores a través de los saltos de hilo
  reactivos, y tu `traceId`/`spanId` se caen de los logs en JSON.

- **Un endpoint de listado ignora un parámetro de filtro.** Los campos de ID se
  excluyen del motor de filtros genérico salvo que se anoten con `@FilterableId`.
  Consulta el Capítulo 8.

- **El flujo en vivo exp → domain → core devuelve, pero los campos del core parecen
  valores por defecto.** En el ejemplo, algunos campos del core (`currency`,
  `termMonths`, `purpose`) muestran valores por defecto en el camino de escritura de
  la saga porque la costura de escritura recortada solo lleva el solicitante y el
  importe; un mapeo más rico se deja al SDK generado en el servicio real. Esto es una
  propiedad de la costura recortada *del ejemplo*, no un bug del framework — el viaje
  de ida y vuelta en sí (id asignado por el core, leído de vuelta a través del BFF)
  es real.

- **Los tests necesitan un Docker que no tienes.** Prefiere los valores por defecto
  en proceso — H2 para R2DBC, el transporte de EDA `APPLICATION_EVENT` dentro de la
  propia JVM, Caffeine para la caché — exactamente como hace el reactor de este
  libro. Recurre a Testcontainers (Capítulo 23) solo cuando un capítulo requiera un
  backend real.

## Adónde ir ahora

Con `flywork setup` hecho una vez (o con Central haciendo el trabajo por ti) y
`flywork create` para cada servicio nuevo, levantar un microservicio correcto y
consistente es un único comando — y el registro de resolución de problemas de arriba
es la breve lista de los pocos lugares donde la realidad se entromete. Eso, menos
los inconvenientes, es la promesa entera del Capítulo 1, ahora al alcance de tu mano.
