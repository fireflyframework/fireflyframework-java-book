Abre cualquier servicio reactivo de Spring Boot y lo primero con lo que te topas,
antes de una sola línea de código de negocio, es el `pom.xml` — y es el primer
lugar donde una flota de servicios se tuerce. Dos servicios traen
`spring-boot-starter-webflux` en versiones ligeramente distintas; un tercero
arrastra un parche de Reactor que no encaja con ninguno de los dos; un cuarto fija
Netty a mano para acallar a un escáner de CVE. Nada de esto es dramático por sí
solo. Juntos forman la deriva de dependencias que el Capítulo 1 llamó parte del
impuesto empresarial, y cuestan horas reales en errores de convergencia y misterios
del estilo "en mi máquina funciona".

La respuesta de Firefly es la clase más aburrida de buena ingeniería: fíjalo todo,
una sola vez, en un lugar que cada servicio hereda. Este capítulo es corto porque la
recompensa es corta — un POM padre, un BOM, una propiedad — y, tras esta página, tus
módulos declaran las dependencias del framework *sin versión alguna*. Ya viste la
forma en el preludio ("hereda un padre, añade un starter, omite las versiones").
Aquí ves el cableado real del reactor que lo hace posible, y aprendes cuándo heredar
y cuándo importar.

Trabajamos íntegramente en los archivos de compilación de Lumen Lending. Al final
serás capaz de leer cada `pom.xml` del reactor y saber con exactitud de dónde sale
cada versión.

## Los dos archivos de coordinación

Una compilación multimódulo de Maven tiene una **raíz del reactor** — el `pom.xml`
superior que enumera los módulos y fija la política compartida — y un `pom.xml` por
módulo. La coherencia de versiones vive casi por completo en la raíz. La raíz de
Lumen Lending hace tres cosas que importan, y las abordaremos una a una: *hereda* un
padre de Firefly, *importa* un BOM de Firefly y fija una propiedad de versión que los
ata entre sí.

Aquí tienes la declaración del padre en la parte superior de la raíz del reactor.

::: listing pom.xml | Listado 3.1 — el reactor hereda fireflyframework-parent
    <parent>
        <groupId>org.fireflyframework</groupId>
        <artifactId>fireflyframework-parent</artifactId>
        <version>26.06.01</version>
        <relativePath/>
    </parent>
:::

Ese único bloque es el cimiento. Al heredar `fireflyframework-parent`, el reactor
asume una gran cantidad de política que nunca tiene que detallar por sí mismo. El
comentario del archivo nombra lo que el padre aporta, y merece leerse como la tesis
del capítulo.

::: listing pom.xml | Listado 3.2 — lo que aporta el padre (comentario de la raíz del reactor)
    <!--
      Lumen Lending — trimmed reactor mirroring the firefly-oss lending vertical.

      We inherit the Firefly Framework parent directly (the real firefly-oss services
      sit on a thin internal `firefly-parent` that in turn inherits this one). The parent
      brings the Spring Boot/Cloud BOMs, the compiler/enforcer/surefire plugin config,
      the Java 25 baseline, and the `java21` profile.
    -->
:::

Lee esa lista otra vez, porque es toda la propuesta de valor en cinco cláusulas. El
padre suministra los BOMs de Spring Boot y Spring Cloud (para que Firefly se mantenga
alineado con las versiones de Spring sobre las que se construye), la configuración de
los plugins de compilación (compiler, enforcer, Surefire), la **base de Java 25** y un
perfil `java21` para los equipos que aún no están en la 25. Heredas todo ello
escribiendo las cinco líneas del Listado 3.1.

!!! spring "Equivalente en Spring"
    En un proyecto típico de Spring Boot escribirías
    `<parent>spring-boot-starter-parent</parent>` para heredar la gestión de plugins
    y las versiones de dependencias de Spring. `fireflyframework-parent` cumple el
    mismo papel, un nivel por encima: *importa* el BOM de Spring Boot internamente en
    lugar de extender `spring-boot-starter-parent`, que es precisamente la razón por
    la que puede convivir con un padre corporativo. Obtienes las versiones curadas de
    Spring Boot más las de Firefly, sin renunciar al propio POM padre de tu
    organización.

## Importar el BOM

Heredar el padre fija Spring y los plugins de compilación. **No** fija, por sí solo,
los ~70 módulos `org.fireflyframework` — los starters, los ayudantes de web y R2DBC,
los validadores, los módulos de CQRS y eventos que conocerás más adelante. Esas
versiones vienen de un **Bill of Materials** aparte, importado en el
`dependencyManagement` de la raíz del reactor.

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
el idioma de Maven para *traer el `dependencyManagement` de otro POM al tuyo* — el BOM
es una larga tabla de entradas `groupId:artifactId → version`, e importarlo fusiona
esa tabla en la tuya sin añadir una sola dependencia a la compilación. Y la versión es
`${firefly.version}`, una propiedad declarada un bloque más arriba, de modo que la
versión del padre y la versión del BOM se enuncian en exactamente un único lugar cada
una y se leen de un vistazo.

!!! note "Término clave — BOM (Bill of Materials)"
    Un **BOM** es un POM cuyo único cometido es su sección `dependencyManagement`: una
    lista curada que fija las versiones de una familia de artefactos. Lo *importas* (en
    lugar de depender de él) y, a partir de ahí, referencias esos artefactos sin
    `<version>` propia — el BOM la suministra. `spring-boot-dependencies` es el BOM en
    el que ya confías; `fireflyframework-bom` es el mismo patrón para los módulos de
    Firefly.

## ¿Heredar el padre o importar el BOM?

Ya has visto ambos mecanismos en un mismo archivo, lo que plantea la pregunta obvia:
si los dos fijan versiones, ¿cuándo usar cada uno? No son redundantes — resuelven
mitades distintas del problema, y la mayoría de los servicios quieren ambos, como hace
Lumen.

- **Hereda `fireflyframework-parent`** cuando quieres también la *política de
  compilación*: los BOMs de Spring/Cloud, la configuración de compiler, enforcer y
  Surefire, la base de Java y el perfil `java21`. La herencia es todo-o-nada y única
  — un POM tiene exactamente un padre — así que heredas el padre cuando a Firefly se
  le permite ser dueño de tus convenciones de compilación.
- **Importa `fireflyframework-bom`** cuando quieres *solo coherencia de versiones*
  para los módulos del framework, sin opinión alguna sobre los plugins o el nivel de
  Java. La importación es aditiva e ilimitada — puedes importar varios BOMs en
  paralelo — de modo que un servicio que ya hereda un padre corporativo que no puede
  reemplazar simplemente importa el BOM de Firefly y conserva su propia política de
  compilación.

Dicho de otro modo: la herencia te da política *y* versiones pero te cuesta tu único
hueco de padre; la importación te da solo versiones pero compone con libertad. Lumen
Lending toma el padre porque es un reactor desde cero y quiere las convenciones de
compilación de Firefly, y *además* importa el BOM porque el padre por sí solo no fija
los módulos del framework. Si tu organización impone su propio padre, elimina el
Listado 3.1, conserva el Listado 3.3 y aun así obtienes dependencias de Firefly
coherentes en versión — solo que cableas el nivel de Java y los plugins tú mismo.

!!! spring "Equivalente en Spring"
    Esta es la misma elección que ofrece Spring Boot. Heredar
    `spring-boot-starter-parent` te da la gestión de plugins más el BOM de
    dependencias; importar `spring-boot-dependencies` como BOM te da solo las
    versiones, dejándote libre para conservar otro padre. Firefly refleja el patrón
    con exactitud, así que la decisión que ya sabes tomar para Spring Boot es la
    decisión que tomas para Firefly.

## CalVer: leer 26.06.01

La versión que sigues viendo — `26.06.01` — no es SemVer. Firefly usa **CalVer**,
versionado de calendario, en un esquema `YY.MM.PATCH`: el `26.06` dice que esta línea
de versión se cortó en junio de 2026, y `01` es el parche dentro de esa línea. Un
parche posterior en la misma línea sería `26.06.02`; la siguiente línea mensual sería
`26.07.0x`.

El sentido de CalVer aquí es la coordinación, no la novedad. Cada artefacto de Firefly
de una línea dada comparte el mismo `YY.MM.PATCH`, de modo que "¿son estos módulos de
la misma versión?" se responde a ojo, y actualizar la pila entera es una edición de un
solo token. En Lumen ese token es la propiedad `firefly.version` del Listado 3.3 —
súbela una vez y el BOM (y a través de él cada módulo del framework) se mueve en
bloque. Como el padre y el BOM son de la misma línea, los mantienes acompasados: cuando
elevas `firefly.version`, eleva la versión del `<parent>` para que coincida.

!!! note "Término clave — CalVer (versionado de calendario)"
    **CalVer** codifica *cuándo* se hizo una versión en lugar de la promesa de SemVer
    sobre *qué cambió*. El `YY.MM.PATCH` de Firefly (aquí `26.06.01`) significa: año
    26, mes 06, parche 01. Hace legible una flota — se sabe de inmediato que un
    servicio que corre `26.06.x` está en la línea de junio de 2026 — y convierte
    "actualizar todo" en un único paso coherente en lugar de una negociación módulo a
    módulo.

## La base de Java 25 y el perfil java21

El padre fija la base del lenguaje en **Java 25** (Listado 3.2). Esa es la base contra
la que compila el reactor por defecto, y es la razón por la que los listados de todo
este libro usan Java moderno — records, tipos sellados, `switch` con coincidencia de
patrones — sin disculparse. El reactor que acabas de construir corrió sobre ella.

No todos los equipos están en Java 25 la semana en que se publica, así que el padre
define también un perfil `java21`. Activarlo con `-Pjava21` reorienta la compilación a
Java 21 — la anterior versión de soporte a largo plazo — de modo que un equipo aún en
21 puede consumir la misma línea de Firefly sin bifurcar nada. El perfil es la salida
de emergencia; Java 25 es el camino.

```text
# default: build against the Java 25 baseline
mvn verify

# opt down to the Java 21 LTS baseline
mvn -Pjava21 verify
```

!!! warning "El perfil cambia el objetivo, no el JDK con el que ejecutas"
    `-Pjava21` baja el *objetivo de bytecode y el nivel de fuente* a los que compila
    la build. No degrada tu JDK instalado, y no puede conjurar funciones de Java 25 en
    un runtime de Java 21 — el código que usa una API exclusiva de la 25 sigue sin
    poder ejecutarse en una JVM 21. Trata el perfil como "produce artefactos
    compatibles con la 21", no como una forma de mezclar niveles de lenguaje dentro de
    una sola build.

## La recompensa: dependencias del framework sin versión

Todo lo anterior ha sido configuración en la raíz. Ahora abre un módulo y mira lo que
te compra. `core-lending-loan-origination` es el servicio sistema-de-registro que irás
construyendo en capítulos posteriores; aquí solo leemos su bloque de dependencias.

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

Fíjate en lo que *no* está ahí: ningún `<version>` en ninguna de ellas. `starter-core`,
`fireflyframework-r2dbc`, `fireflyframework-web` — cada una nombra un `groupId` y un
`artifactId` y se detiene. La versión la suministra el BOM que importaste en la raíz
(Listado 3.3), resuelta a través de la cadena de padres del módulo. Esto es todo el
propósito del capítulo hecho concreto: el módulo declara *qué* necesita, nunca *qué
versión*, y una única propiedad en un solo archivo decide por todas ellas.

Por eso también el propio `pom.xml` del módulo no lleva `<version>` ni `<groupId>`
propios en la parte superior — hereda ambos de la raíz del reactor, así que solo enuncia
su `<artifactId>`. Cuanto más bajas por el árbol, menos información de versión escribes,
hasta que en la hoja no escribes casi ninguna.

!!! spring "Equivalente en Spring"
    Así es exactamente como se ve una dependencia `spring-boot-starter-*` una vez que
    heredas o importas el BOM de Spring — `groupId` y `artifactId`, sin versión.
    Firefly extiende la misma comodidad a sus ~70 módulos. Si omitir versiones en los
    starters de Spring ya te resulta natural, Firefly no te pide nada nuevo; solo
    amplía el conjunto de artefactos a los que se aplica el truco.

## Ejecútalo

No necesitas escribir código para probar que el cableado funciona — compilar el
reactor *es* la prueba. Desde la raíz del reactor, lanza un verify completo:

```text
mvn -q verify
```

Maven lee el `pom.xml` de la raíz, resuelve `fireflyframework-parent` y el
`fireflyframework-bom` importado, y los usa para dar una versión concreta a cada
dependencia sin versión del Listado 3.4. Si un solo artefacto no pudiera fijarse — una
coordenada mal tecleada, un BOM que no se importó — la build fallaría en la resolución,
antes de que se ejecutara ningún test. No falla. El módulo core compila y corre sus
tests, y el reactor termina en verde.

```text
[INFO] Building Lumen Lending - Core (Loan Origination) 0.1.0-SNAPSHOT    [2/4]
[INFO] Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
...
[INFO] BUILD SUCCESS
```

!!! tip "Punto de control"
    Ejecuta `mvn -q verify` desde `samples/lumen-lending` y confirma que ves
    `BUILD SUCCESS`. Esa única línea es la garantía de coherencia de versiones dando
    sus frutos: un padre, un BOM, un `firefly.version`, y cada dependencia del
    framework a lo largo de tres módulos resuelta a un único conjunto sin conflictos —
    sin un solo `<version>` en un artefacto del framework.

## Lo que has aprendido {.recap}

- Un reactor de Firefly coordina las versiones en su **`pom.xml` raíz** a través de
  tres piezas: un `fireflyframework-parent` heredado, un `fireflyframework-bom`
  importado y una única propiedad `firefly.version` que los ata entre sí.
- **Hereda el padre** para obtener política de compilación *y* versiones (BOMs de
  Spring/Cloud, plugins, la base de Java, el perfil `java21`); **importa el BOM** para
  obtener *solo* las versiones de los módulos del framework cuando debes conservar
  otro padre. Lumen hace ambas cosas; un equipo con padre corporativo se queda solo
  con la importación.
- Firefly usa **CalVer** (`YY.MM.PATCH`, aquí `26.06.01`), de modo que toda una línea
  de versión se mueve en bloque y actualizar la pila es una edición de un solo token.
- La base es **Java 25**, con `-Pjava21` como perfil de descenso para equipos en la
  LTS anterior.
- La recompensa es que los módulos declaran las dependencias del framework —
  `starter-core`, `fireflyframework-r2dbc`, `fireflyframework-web` — **sin
  `<version>`**, y el reactor sigue compilando hasta `BUILD SUCCESS`.

## Pruébalo tú mismo {.exercises}

1. **Rastrea una versión hasta su origen.** En `core-lending-loan-origination/pom.xml`,
   elige `fireflyframework-web` y sigue cómo obtiene una versión: qué archivo la fija y
   qué propiedad alimenta esa fijación. Escribe la cadena en una sola frase.
2. **Sube la línea.** Cambia `firefly.version` en `samples/lumen-lending/pom.xml` a un
   parche posterior (por ejemplo `26.06.02`) y actualiza la versión del `<parent>` para
   que coincida. Ejecuta `mvn -q dependency:tree` y observa cómo los artefactos del
   framework se mueven en bloque. Luego revierte.
3. **Resuelve las versiones efectivas.** Ejecuta `mvn -q help:effective-pom` en el
   módulo core y busca en la salida `fireflyframework-starter-core`. Encuentra la
   versión concreta que Maven inyectó desde el BOM, aunque el POM del módulo no nombre
   ninguna.
4. **Prueba el perfil LTS.** Ejecuta `mvn -q -Pjava21 verify` desde la raíz del reactor
   y confirma que sigue llegando a `BUILD SUCCESS`. Observa en el log que se trata de la
   misma build reorientada, no de un JDK distinto.
5. **Quita el padre, conserva el BOM.** Sobre una copia desechable de
   `samples/lumen-lending/pom.xml`, borra el bloque `<parent>` y añade `<groupId>` y
   `<version>` explícitos a las propias coordenadas del reactor. Predice qué se rompe
   (pista: la base de Java y la configuración de plugins que aportaba el padre) antes de
   ejecutarlo.

## Adónde ir ahora

La build es coherente; ahora haces que *haga* algo. El Capítulo 4 se vuelca en la
configuración — `application.yml`, perfiles y `@ConfigurationProperties` — para que el
mismo JAR con versión fijada pueda ejecutarse de forma distinta en desarrollo y en
producción sin recompilar.
