Abre cualquier servicio reactivo de Spring Boot y lo primero con lo que te topas,
antes de una sola línea de código de negocio, es el `pom.xml` — y el primer sitio
donde una flota empieza a torcerse. Dos servicios traen `spring-boot-starter-webflux`
en versiones ligeramente distintas; un tercero arrastra un parche de Reactor que no
concuerda con ninguno de los dos; un cuarto fija Netty a mano para callar a un escáner
de CVE. Nada de esto es dramático por sí solo. Juntos son la deriva de dependencias
que el Capítulo 1 calificó de parte del impuesto empresarial, y cuestan horas reales
en errores de convergencia y misterios de "en mi máquina funciona".

La respuesta de Firefly es el tipo de buena ingeniería más aburrido: fíjalo todo,
una sola vez, en un lugar que cada servicio herede. Este capítulo es corto porque la
recompensa es corta — un POM padre, un BOM, una propiedad — y a partir de esta página
tus módulos declaran las dependencias del framework *sin versión alguna*. Ya viste la
forma en el preludio ("hereda un padre, añade un starter, omite las versiones") y de
nuevo en el quickstart del Capítulo 2, donde `core-lending-loan-origination` arrancó
con exactamente este cableado. Aquí ves la fontanería reactor real que lo hace cierto,
aprendes cuándo heredar y cuándo importar y — novedad en esta pasada — ves el único
plugin de build que convierte cada módulo en un jar ejecutable que puedes correr.

Trabajamos íntegramente en los ficheros de build de Lumen Lending. Al final serás
capaz de leer cada `pom.xml` del reactor y saber con exactitud de dónde viene cada
versión, y cómo cada capa se convierte en algo que puedes lanzar con `java -jar`.

## Los dos ficheros de coordinación

Un build multi-módulo de Maven tiene una **raíz del reactor** — el `pom.xml` de más
arriba que lista los módulos y fija la política compartida — y un `pom.xml` por
módulo. La coherencia de versiones vive casi por completo en la raíz. La raíz de
Lumen Lending hace tres cosas que importan, y las tomaremos de una en una: *hereda*
un padre de Firefly, *importa* un BOM de Firefly y fija una propiedad de versión que
los ata entre sí.

Aquí tienes la declaración del padre en lo alto de la raíz del reactor.

::: listing pom.xml | Listado 3.1 — el reactor hereda fireflyframework-parent
    <parent>
        <groupId>org.fireflyframework</groupId>
        <artifactId>fireflyframework-parent</artifactId>
        <version>26.06.01</version>
        <relativePath/>
    </parent>
:::

Ese único bloque es el cimiento. Al heredar `fireflyframework-parent`, el reactor
asume una gran cantidad de política que jamás tiene que detallar él mismo. La etiqueta
vacía `<relativePath/>` merece una segunda mirada: le dice a Maven que *no* busque el
padre en el árbol de directorios local, sino que lo resuelva desde el repositorio como
cualquier otro artefacto — correcto aquí porque el padre de Firefly es un POM publicado,
no una carpeta hermana. El comentario del fichero nombra lo que aporta el padre, y
vale la pena leerlo como la tesis del capítulo.

::: listing pom.xml | Listado 3.2 — lo que aporta el padre (comentario en la raíz del reactor)
    <!--
      Lumen Lending — trimmed reactor mirroring the firefly-oss lending vertical.

      We inherit the Firefly Framework parent directly (the real firefly-oss services
      sit on a thin internal `firefly-parent` that in turn inherits this one). The parent
      brings the Spring Boot/Cloud BOMs, the compiler/enforcer/surefire plugin config,
      the Java 25 baseline, and the `java21` profile.
    -->
:::

Lee esa lista otra vez, porque es toda la propuesta de valor en cinco cláusulas. El
padre suministra los BOM de Spring Boot y Spring Cloud (de modo que Firefly se mantenga
alineado con las versiones de Spring sobre las que se construye), la configuración de
los plugins de build (compiler, enforcer, Surefire), la **base Java 25** y un perfil
`java21` para las casas que aún no están en 25. Heredas todo ello escribiendo las cinco
líneas del Listado 3.1. El comentario también revela un detalle honesto sobre el mundo
real: los servicios de producción `firefly-oss` se asientan sobre un fino `firefly-parent`
interno que *él mismo* hereda este padre del framework. Lumen se salta ese intermediario
y hereda el padre del framework directamente, porque una muestra de libro no tiene
convenciones corporativas que intercalar — la cadena tiene un eslabón menos, pero la
mecánica es idéntica.

!!! spring "Equivalente en Spring"
    En un proyecto típico de Spring Boot escribirías
    `<parent>spring-boot-starter-parent</parent>` para heredar la gestión de plugins y
    las versiones de dependencias de Spring. `fireflyframework-parent` cumple el mismo
    papel, un nivel más arriba: *importa* el BOM de Spring Boot internamente en lugar de
    extender `spring-boot-starter-parent`, que es precisamente por lo que puede convivir
    con un padre corporativo. Obtienes las versiones cuidadas de Spring Boot más las de
    Firefly, sin renunciar al POM padre propio de tu organización.

## Importar el BOM

Heredar el padre fija Spring y los plugins de build. **No** fija, por sí solo, los
~70 módulos `org.fireflyframework` — los starters, los ayudantes web y de R2DBC, los
validadores, los módulos de CQRS y de eventos que conocerás más adelante. Esas versiones
vienen de un **Bill of Materials** aparte, importado en el `dependencyManagement` de la
raíz del reactor.

::: listing pom.xml | Listado 3.3 — importar fireflyframework-bom fija cada módulo del framework
    <properties>
        <firefly.version>26.06.01</firefly.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <!-- Firefly Framework BOM: pins all org.fireflyframework module versions. -->
            <dependency>
                <groupId>org.fireflyframework</groupId>
                <artifactId>fireflyframework-bom</artifactId>
                <version>${firefly.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
:::

Dos detalles cargan con el peso. El `<type>pom</type>` con `<scope>import</scope>` es
el modismo de Maven para *traer el `dependencyManagement` de otro POM al tuyo* — el
BOM es una larga tabla de entradas `groupId:artifactId → versión`, e importarlo
fusiona esa tabla con la tuya sin añadir una sola dependencia al build. Y la versión es
`${firefly.version}`, una propiedad declarada un bloque más arriba, así que la versión
del padre y la del BOM se enuncian en exactamente un solo sitio cada una y se leen de
un vistazo.

Un lector nuevo en Maven suele preguntar: si el padre ya importa los BOM de Spring,
¿por qué no choca con él importar el BOM de Firefly? No choca, porque las importaciones
de BOM son *aditivas*. El BOM de Spring del padre fija las coordenadas de Spring; este
BOM de Firefly fija las coordenadas de Firefly; las dos tablas cubren artefactos
distintos y simplemente coexisten en el mapa de gestión de dependencias fusionado.
Cuando dos BOM *sí* nombran el mismo artefacto, gana la declaración más cercana — y un
módulo de Firefly que vuelva a fijar, pongamos, Reactor lo haría deliberadamente y al
unísono con la línea de Spring que estableció el padre.

!!! note "Término clave — BOM (Bill of Materials)"
    Un **BOM** es un POM cuyo único cometido es su sección `dependencyManagement`: una
    lista cuidada que fija las versiones de una familia de artefactos. Lo *importas* (en
    lugar de depender de él), y a partir de entonces referencias esos artefactos sin
    `<version>` propia — el BOM la suministra. `spring-boot-dependencies` es el BOM en
    el que ya te apoyas; `fireflyframework-bom` es el mismo patrón para los módulos de
    Firefly.

## ¿Heredar el padre o importar el BOM?

Has visto ya ambos mecanismos en un solo fichero, lo que plantea la pregunta obvia: si
los dos fijan versiones, ¿cuándo usas cuál? No son redundantes — resuelven mitades
distintas del problema, y la mayoría de los servicios quieren ambos, como hace Lumen.

- **Hereda `fireflyframework-parent`** cuando quieres *también la política de build*:
  los BOM de Spring/Cloud, la configuración de compiler, enforcer y Surefire, la base
  de Java y el perfil `java21`. La herencia es todo o nada y única — un POM tiene
  exactamente un padre — así que heredas el padre cuando a Firefly se le permite ser
  dueño de tus convenciones de build.
- **Importa `fireflyframework-bom`** cuando quieres *solo coherencia de versiones* para
  los módulos del framework, sin opinión alguna sobre plugins o nivel de Java. La
  importación es aditiva e ilimitada — puedes importar varios BOM en paralelo — así que
  un servicio que ya hereda un padre corporativo que no puede reemplazar simplemente
  importa el BOM de Firefly y conserva su propia política de build.

Dicho de otro modo: la herencia te da política *y* versiones pero te cuesta tu único
hueco de padre; la importación te da solo versiones pero compone con libertad. Lumen
Lending toma el padre porque es un reactor de campo nuevo y quiere las convenciones de
build de Firefly, y *además* importa el BOM porque el padre por sí solo no fija los
módulos del framework. Si tu organización impone su propio padre, elimina el Listado
3.1, conserva el Listado 3.3 y aun así obtienes dependencias de Firefly coherentes en
versión — solo que cableas el nivel de Java y los plugins tú mismo.

!!! spring "Equivalente en Spring"
    Esta es la misma elección que ofrece Spring Boot. Heredar
    `spring-boot-starter-parent` te da la gestión de plugins más el BOM de dependencias;
    importar `spring-boot-dependencies` como BOM te da solo las versiones, dejándote
    libre de conservar otro padre. Firefly refleja el patrón con exactitud, así que la
    decisión que ya sabes tomar para Spring Boot es la decisión que tomas para Firefly.

## CalVer: leer 26.06.01

La versión que sigues viendo — `26.06.01` — no es SemVer. Firefly usa **CalVer**,
versionado por calendario, en un esquema `YY.MM.PATCH`: el `26.06` dice que esta línea
de release se cortó en junio de 2026, y `01` es el parche dentro de esa línea. Un parche
posterior en la misma línea sería `26.06.02`; la siguiente línea mensual sería `26.07.0x`.

El sentido de CalVer aquí es la coordinación, no la novedad. Todo artefacto de Firefly
en una línea dada comparte el mismo `YY.MM.PATCH`, de modo que "¿estos módulos son del
mismo release?" se responde a ojo, y actualizar todo el stack es una edición de un solo
token. En Lumen ese token es la propiedad `firefly.version` del Listado 3.3 — súbela una
vez, y el BOM (y a través de él cada módulo del framework) se mueve junto. Como el padre
y el BOM son la misma línea, los mantienes al unísono: cuando elevas `firefly.version`,
eleva la versión de `<parent>` para que coincida. (Deliberadamente *no* se pliegan en una
sola propiedad: Maven lee la versión del padre antes de interpolar las propiedades, así
que el bloque `<parent>` debe llevar una versión literal. Por eso editas dos sitios —
Listado 3.1 y Listado 3.3 — y por eso el ejercicio 2 te pide cambiar ambos.)

!!! note "Término clave — CalVer (versionado por calendario)"
    **CalVer** codifica *cuándo* se hizo un release en lugar de la promesa de SemVer
    sobre *qué cambió*. El `YY.MM.PATCH` de Firefly (aquí `26.06.01`) significa: año 26,
    mes 06, parche 01. Hace legible una flota — un servicio que corre `26.06.x` se sabe
    de inmediato que está en la línea de junio de 2026 — y convierte "actualízalo todo"
    en un único paso coherente en lugar de una negociación módulo a módulo.

## La base Java 25 y el perfil java21

El padre fija la base del lenguaje en **Java 25** (Listado 3.2). Esa es la base por
defecto contra la que compila el reactor, y es por lo que los listados a lo largo de
este libro usan Java moderno — records, tipos sellados, `switch` con coincidencia de
patrones — sin disculpas. El reactor que arrancaste en el Capítulo 2 corría sobre ella.

No toda casa está en Java 25 la semana en que sale, así que el padre también define un
perfil `java21`. Activarlo con `-Pjava21` reorienta el build a Java 21 — el release de
soporte a largo plazo anterior — de modo que un equipo aún en 21 puede consumir la misma
línea de Firefly sin bifurcar nada. El perfil es la salida de emergencia; Java 25 es la
carretera.

```text
# default: build against the Java 25 baseline
mvn verify

# opt down to the Java 21 LTS baseline
mvn -Pjava21 verify
```

!!! warning "El perfil cambia el target, no el JDK con el que ejecutas"
    `-Pjava21` baja el *nivel de bytecode target y de source* contra el que compila el
    build. No degrada tu JDK instalado, y no puede conjurar características de Java 25 en
    un runtime de Java 21 — código que usa una API exclusiva de 25 seguirá sin correr en
    una JVM 21. Trata el perfil como "produce artefactos compatibles con 21", no como una
    forma de mezclar niveles de lenguaje dentro de un mismo build.

## La recompensa: dependencias del framework sin versión

Todo lo de hasta ahora ha sido preparación en la raíz. Ahora abre un módulo y mira lo
que te compra. `core-lending-loan-origination` es el servicio sistema-de-registro que
el Capítulo 2 llevó a dar una vuelta y que los capítulos posteriores desarrollan; aquí
leemos su bloque de dependencias.

::: listing core-lending-loan-origination/pom.xml | Listado 3.4 — dependencias del framework, declaradas sin versión
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
:::

Mira lo que *no* hay ahí: ningún `<version>` en ninguna de ellas. `starter-core`,
`fireflyframework-r2dbc`, `fireflyframework-web` — cada una nombra un `groupId` y un
`artifactId` y se detiene. La versión la suministra el BOM que importaste en la raíz
(Listado 3.3), resuelta a través de la cadena de padres del módulo. Esto es el sentido
entero del capítulo hecho concreto: el módulo declara *qué* necesita, nunca *qué
versión*, y una sola propiedad en un solo fichero decide por todas ellas.

Por eso también el propio `pom.xml` del módulo no lleva `<version>` ni `<groupId>`
propio en lo alto — los hereda ambos de la raíz del reactor, así que solo enuncia su
`<artifactId>`. Cuanto más abajo bajas en el árbol, menos información de versión
escribes, hasta que en la hoja casi no escribes ninguna.

!!! spring "Equivalente en Spring"
    Así es exactamente como se ve una dependencia `spring-boot-starter-*` una vez que
    heredas o importas el BOM de Spring — `groupId` y `artifactId`, sin versión. Firefly
    extiende la misma comodidad a sus ~70 módulos. Si omitir versiones en los starters de
    Spring ya te resulta natural, Firefly no te pide nada nuevo; solo amplía el conjunto
    de artefactos al que se aplica el truco.

## Un bloque de build, tres jars ejecutables

La coherencia de versiones pone las *clases* correctas en el classpath. No convierte,
por sí sola, un módulo en algo que puedas entregar a operaciones y ejecutar con
`java -jar`. Ese último paso es un plugin de Spring Boot, y cada capa de este reactor
lo cablea. Aquí tienes el bloque `<build>` del módulo core, literal.

::: listing core-lending-loan-origination/pom.xml | Listado 3.5 — el goal repackage crea un jar ejecutable
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <executions>
                    <execution>
                        <goals><goal>repackage</goal></goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
:::

El goal `repackage` es lo que convierte el jar de librería corriente que `mvn package`
produciría de otro modo en un **jar ejecutable (fat) de Spring Boot** — el código de la
aplicación, cada dependencia y un pequeño lanzador, todo en un único fichero
autocontenido que puedes ejecutar con `java -jar`. Las coordenadas del plugin en sí no
llevan `<version>`: la gestión de plugins del padre heredado la fija, la misma historia
de coherencia aplicada a los plugins de build en lugar de a las dependencias. Los
módulos `domain-lending-loan-origination` y `exp-lending` llevan el bloque *idéntico*,
así que las tres capas se empaquetan de la misma forma — una garantía uniforme de "esto
construye un artefacto ejecutable" a lo largo de la flota.

Por eso cada capa ofrece dos formas equivalentes de arrancar, las que documenta el
`README.md` del reactor. Durante el desarrollo corres in situ con el plugin de Maven;
para un artefacto construido corres el jar que produjo `repackage`:

```text
# run in place from the module directory
mvn spring-boot:run

# or build the executable jar and run it standalone
mvn -q -pl core-lending-loan-origination package
java -jar core-lending-loan-origination/target/core-lending-loan-origination-0.1.0-SNAPSHOT.jar
```

Ambas aterrizan en el mismo servicio arrancando — la capa core en el puerto **8081**,
sobre H2 en memoria con una migración de Flyway, sin Docker. (El quickstart del Capítulo
2 mostró la salida de arranque en el puerto por defecto; en el reactor vivo cada capa
toma un puerto fijo de su `application.yml`, que el Capítulo 4 desgrana.) La capa domain
sirve en **8082**, el BFF de experiencia en **8080**.

!!! note "Término clave — jar ejecutable (fat)"
    Un **jar ejecutable** de Spring Boot empaqueta tus clases compiladas, todas las
    dependencias transitivas y un fino lanzador en un único fichero ejecutable, de modo
    que `java -jar app.jar` arranca el servicio entero sin ningún classpath externo que
    ensamblar. El goal `repackage` del `spring-boot-maven-plugin` lo construye durante
    `mvn package`. Es la unidad de despliegue cloud-native estándar — un fichero dentro
    de una imagen de contenedor, sin servidor de aplicaciones requerido.

!!! spring "Equivalente en Spring"
    En un proyecto `spring-boot-starter-parent` el goal `repackage` está enlazado por ti
    por la configuración de plugins por defecto de Spring, así que rara vez escribes este
    bloque. Como el padre de Firefly importa el BOM de Spring en lugar de extender el
    starter parent, cada módulo ejecutable declara el `spring-boot-maven-plugin`
    explícitamente — cinco líneas que apuntan el módulo al ciclo de vida del jar
    ejecutable. Mismo goal, mismo resultado; solo lo nombras una vez por desplegable.

## Ejecútalo

No necesitas escribir código para demostrar que el cableado funciona — construir el
reactor *es* la prueba. Desde la raíz del reactor, lanza un verify completo:

```text
mvn -q verify
```

Maven lee el `pom.xml` raíz, resuelve `fireflyframework-parent` y el
`fireflyframework-bom` importado, y los usa para dar una versión concreta a cada
dependencia sin versión del Listado 3.4 y al plugin de build del Listado 3.5. Si un solo
artefacto no pudiera fijarse — una coordenada con una errata, un BOM que no importó — el
build fallaría en la resolución, antes de que corriera ningún test. No falla. Los tres
módulos compilan, el goal `repackage` produce un jar ejecutable para cada uno y el
reactor entero termina en verde — 33 tests a lo largo de las tres capas (core 18, domain
6, experience 9):

```text
[INFO] Building Lumen Lending - Core (Loan Origination) 0.1.0-SNAPSHOT    [2/4]
[INFO] Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
...
[INFO] Reactor Summary for Lumen Lending 0.1.0-SNAPSHOT:
[INFO] Lumen Lending ...................................... SUCCESS
[INFO] Lumen Lending - Core (Loan Origination) ............ SUCCESS
[INFO] Lumen Lending - Domain (Loan Origination) .......... SUCCESS
[INFO] Lumen Lending - Experience (Lending BFF) ........... SUCCESS
[INFO] BUILD SUCCESS
```

!!! tip "Punto de control"
    Lanza `mvn -q verify` desde `samples/lumen-lending` y confirma que ves
    `BUILD SUCCESS` y `Tests run: 18` para el módulo core (33 a lo largo del reactor).
    Esa es la garantía de coherencia de versiones rindiendo frutos: un padre, un BOM, un
    `firefly.version`, y cada dependencia del framework a lo largo de tres módulos
    resuelta a un único conjunto sin conflictos — sin un solo `<version>` en un artefacto
    del framework, y tres jars ejecutables en los directorios `target/` que lo
    demuestran.

## Lo que has aprendido {.recap}

- Un reactor de Firefly coordina las versiones en su **`pom.xml` raíz** a través de tres
  piezas: un `fireflyframework-parent` heredado, un `fireflyframework-bom` importado y
  una única propiedad `firefly.version` que los ata entre sí.
- **Hereda el padre** para obtener la política de build *y* las versiones (BOM de
  Spring/Cloud, plugins, la base de Java, el perfil `java21`); **importa el BOM** para
  obtener *solo* las versiones de los módulos del framework cuando debes conservar otro
  padre. Lumen hace ambas cosas; una casa con padre corporativo se queda solo con la
  importación.
- Firefly usa **CalVer** (`YY.MM.PATCH`, aquí `26.06.01`), así que toda una línea de
  release se mueve junta y actualizar el stack es una edición de un solo token — sube
  `firefly.version` y haz coincidir la versión de `<parent>`.
- La base es **Java 25**, con `-Pjava21` como perfil de descenso para equipos en el LTS
  anterior.
- La recompensa es que los módulos declaran las dependencias del framework —
  `starter-core`, `fireflyframework-r2dbc`, `fireflyframework-web` — **sin `<version>`**,
  y el reactor aun así construye hasta `BUILD SUCCESS`.
- Cada capa también cablea el **goal `repackage` del `spring-boot-maven-plugin`**, de
  modo que `mvn package` produce un **jar ejecutable** y cada capa corre igual de bien
  con `mvn spring-boot:run` o `java -jar` — core en **8081**, domain en **8082**,
  experience en **8080**.

## Pruébalo tú mismo {.exercises}

1. **Rastrea una versión hasta su origen.** En `core-lending-loan-origination/pom.xml`,
   elige `fireflyframework-web` y sigue cómo obtiene una versión: qué fichero la fija y
   qué propiedad alimenta esa fijación. Escribe la cadena en una sola frase.
2. **Sube la línea.** Cambia `firefly.version` en `samples/lumen-lending/pom.xml` a un
   parche posterior (por ejemplo `26.06.02`), y actualiza la versión de `<parent>` para
   que coincida. Lanza `mvn -q dependency:tree` y observa cómo los artefactos del
   framework se mueven juntos. Luego revierte. (¿Por qué tuviste que editar *dos* sitios?
   Relee la sección de CalVer.)
3. **Resuelve versiones efectivas.** Lanza `mvn -q help:effective-pom` en el módulo core
   y busca en la salida `fireflyframework-starter-core`. Encuentra la versión concreta
   que Maven inyectó desde el BOM, aunque el POM del módulo no nombre ninguna.
4. **Prueba el perfil LTS.** Lanza `mvn -q -Pjava21 verify` desde la raíz del reactor y
   confirma que aun así alcanza `BUILD SUCCESS`. Anota en el log que este es el mismo
   build reorientado, no un JDK distinto.
5. **Suelta el padre, conserva el BOM.** Sobre una copia desechable de
   `samples/lumen-lending/pom.xml`, borra el bloque `<parent>` y añade `<groupId>` y
   `<version>` explícitos a las propias coordenadas del reactor. Predice qué se rompe
   (pista: la base de Java y la configuración de plugins que suministraba el padre) antes
   de ejecutarlo.
6. **Construye un jar ejecutable.** Lanza `mvn -q -pl core-lending-loan-origination package`,
   luego lista `core-lending-loan-origination/target/` y encuentra el jar ejecutable.
   Arráncalo con `java -jar …` y confirma que la capa core levanta en el puerto 8081 con
   `curl -s localhost:8081/actuator/health`. ¿Qué línea del `pom.xml` hizo ese jar
   ejecutable? (Pista: Listado 3.5.)

## Adónde ir ahora

El build es coherente y cada capa es ejecutable; ahora haces que *haga* algo. El
Capítulo 4 se vuelve hacia la configuración — `application.yml`, perfiles y
`@ConfigurationProperties` — para que el mismo JAR repaquetado con versión bloqueada
pueda correr de forma distinta en dev y en producción, y para que cada capa sepa tomar
el puerto 8081, 8082 u 8080, sin recompilar.
