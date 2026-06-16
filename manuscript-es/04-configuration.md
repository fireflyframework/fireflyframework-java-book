Un servicio que se ejecuta en tu portátil, en un entorno de pruebas, en un clúster
de staging y en producción es *un* único artefacto de compilación: el mismo JAR,
byte a byte. Lo que cambia entre esos cuatro lugares nunca es el código; es la
configuración. La URL de la base de datos, la dirección del broker, el nivel de
log, el secreto que desbloquea una API ascendente: todo ello vive fuera del JAR,
dispuesto en capas de modo que cada entorno aporte únicamente lo que difiere de
los valores por defecto.

Esto es la **configuración externalizada**, y es una de las ideas portantes que
heredas directamente de Spring Boot. Firefly no cambia las reglas; añade una
jerarquía sobre ellas: un servidor de configuración que sirve ajustes comunes,
de núcleo y de dominio a toda una flota, con los secretos cifrados en reposo. Este
capítulo empieza por el fichero contra el que realmente se ejecuta la compilación,
y luego avanza hacia fuera: la jerarquía de resolución, el enlazado tipado con
`@ConfigurationProperties` y, por último, dónde encajan los secretos y el servidor
de configuración.

## El fichero contra el que se ejecutan las pruebas

Abre la configuración de pruebas del servicio de núcleo de originación de
préstamos. Es pequeña a propósito: tiene exactamente una misión, que es hacer que
las pruebas del módulo se ejecuten sin Docker, sin base de datos externa y sin red,
y aun así contra una pila de datos reactiva real.

::: listing core-lending-loan-origination/src/test/resources/application.yml | Listado 4.1 — la configuración de pruebas: H2, Flyway y R2DBC apuntando a una única base de datos en memoria
# Test configuration: in-memory H2 so the slice runs with no Docker.
#
# R2DBC is the runtime data access (reactive); Flyway migrates over JDBC. Both
# point at the SAME in-memory H2 database (name "lumen", DB_CLOSE_DELAY=-1 keeps
# it alive for the whole JVM) so the table Flyway creates is the one R2DBC reads.
spring:
  application:
    name: core-lending-loan-origination
  r2dbc:
    url: r2dbc:h2:mem:///lumen;DB_CLOSE_DELAY=-1
    username: sa
    password: ""
  flyway:
    enabled: true
    baseline-on-migrate: true
    locations: classpath:db/migration
    url: jdbc:h2:mem:lumen;DB_CLOSE_DELAY=-1
    user: sa
    password: ""
:::

Léelo de arriba abajo y habrás leído el contrato completo.

`spring.application.name` da nombre al servicio. Es la identidad de la que parte
cualquier otro subsistema: es el nombre del banner de arranque, la etiqueta de las
métricas y, como verás al final de este capítulo, la carpeta en la que el servidor
de configuración busca cuando le sirve a este servicio sus ajustes.

`spring.r2dbc.*` configura la ruta de acceso a datos **reactiva**. La URL
`r2dbc:h2:mem:///lumen;DB_CLOSE_DELAY=-1` indica: una base de datos H2 en memoria
llamada `lumen`, mantenida con vida durante toda la vida de la JVM. Esto es lo que
tus repositorios leen y escriben, sobre el bucle de eventos, sin bloquear.

`spring.flyway.*` configura la migración del esquema, que se ejecuta sobre **JDBC**:
`jdbc:h2:mem:lumen;DB_CLOSE_DELAY=-1`. Las dos URLs usan controladores distintos
(`r2dbc:` frente a `jdbc:`) pero nombran la *misma* base de datos en memoria,
`lumen`. Ese nombre compartido es todo el truco: Flyway crea la tabla sobre JDBC al
arrancar, y R2DBC lee esa misma tabla en tiempo de ejecución. `baseline-on-migrate: true`
permite que Flyway adopte limpiamente una base de datos vacía;
`locations: classpath:db/migration` la apunta a la migración que vive junto al
código.

!!! note "Término clave — configuración externalizada"
    La **configuración externalizada** significa que los ajustes viven fuera del
    artefacto compilado —en `application.yml`, ficheros de perfil y variables de
    entorno— de modo que el mismo JAR se ejecuta sin cambios en todos los entornos.
    El código lee propiedades *con nombre*; el entorno aporta los *valores*.

!!! warning "Dos URLs, una base de datos: mantén el nombre sincronizado"
    La URL de R2DBC y la URL de Flyway deben nombrar la misma base de datos en
    memoria (`lumen` aquí). Si se separan, Flyway migra una base de datos y tus
    repositorios leen otra vacía, y el fallo parece una tabla que falta, no una
    errata de configuración. Cuando copies este patrón a un nuevo módulo, cambia
    ambos nombres a la vez.

!!! spring "Equivalente en Spring"
    Nada en el Listado 4.1 es específico de Firefly. `spring.application.name`,
    `spring.r2dbc.*` y `spring.flyway.*` son claves de propiedad de Spring Boot
    puro, enlazadas por la propia autoconfiguración de Spring Boot. Firefly hereda
    sin cambios todo el mecanismo de configuración externalizada y dispone su
    jerarquía encima: nunca lo reemplaza.

## Ejecútalo

Como esta configuración está en el classpath de pruebas, las pruebas del módulo la
recogen automáticamente. Ejecútalas:

```text
mvn -q -pl core-lending-loan-origination test
```

La base de datos H2 se levanta en memoria, Flyway aplica la migración, las pruebas
reactivas ejercitan las capas de datos y web, y Maven informa:

```text
Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

!!! tip "Punto de control"
    Si ves `Tests run: 18, Failures: 0`, tu configuración cableó una pila de datos
    reactiva real sin nada instalado salvo una JVM. Si, en cambio, una prueba
    informa de una tabla que falta, las dos URLs del Listado 4.1 se han separado:
    vuelve a comprobar que las URLs de R2DBC y Flyway nombren la misma base de
    datos.

## La jerarquía de resolución

Un único fichero es el punto de partida, nunca la historia completa. Spring Boot
resuelve una propiedad consultando una serie de fuentes en un orden fijo, y **las
fuentes posteriores ganan**. Tres de esas fuentes cargan en la práctica con casi
todo el peso:

1. **`application.yml`** — la base. Valores por defecto sensatos que son ciertos en
   todas partes, como el nombre del servicio.
2. **Ficheros específicos de perfil** — `application-{profile}.yml`. Activados por
   nombre, sobrescriben la base para un entorno o forma de ejecución concreta.
3. **Variables de entorno y propiedades de sistema de la JVM** — aportadas por la
   plataforma en el arranque. Sobrescriben todo lo demás, que es exactamente lo que
   quieres para valores que difieren por despliegue o que nunca deben residir en un
   fichero.

Un **perfil** es simplemente una porción con nombre de la configuración. Activas
uno con la propiedad `spring.profiles.active` —ella misma normalmente establecida
por una variable de entorno— y Spring carga el `application-{profile}.yml`
correspondiente sobre la base. Un fichero de producción podría tener este aspecto
(ilustrativo: el reactor incluye el perfil de pruebas mostrado en el Listado 4.1,
no un fichero de producción):

```yaml
# application-prod.yml — overrides only what differs in production
spring:
  r2dbc:
    url: r2dbc:postgresql://db.internal:5432/lumen
    username: lumen_app
  flyway:
    url: jdbc:postgresql://db.internal:5432/lumen
    user: lumen_app
```

Fíjate en lo que *falta*: aquí no hay ningún `spring.application.name`, porque la
base ya lo estableció y producción no lo cambia. Un fichero de perfil declara solo
el delta. Ejecuta con `--spring.profiles.active=prod` y las URLs de Postgres ganan
sobre la base de H2; ejecuta sin perfil y obtienes los valores por defecto en
memoria.

La capa final —las variables de entorno— sigue una regla de nombrado mecánica:
pon la propiedad en mayúsculas, sustituye los puntos y guiones por guiones bajos.
Así, `spring.r2dbc.password` se sobrescribe con la variable de entorno
`SPRING_R2DBC_PASSWORD`. Así es como una plataforma de despliegue inyecta un valor
que el fichero nunca contiene:

```text
export SPRING_PROFILES_ACTIVE=prod
export SPRING_R2DBC_PASSWORD=...        # injected by the platform, never in git
java -jar core-lending-loan-origination.jar
```

La base da nombre a la propiedad, el perfil la remodela para un entorno y la
variable de entorno aporta el único valor que nunca subirías al repositorio. El
mismo JAR, tres lugares, tres comportamientos.

!!! note "Término clave — perfil"
    Un **perfil** es una porción de configuración con nombre activada por
    `spring.profiles.active`. Spring carga `application-{profile}.yml` sobre la base
    `application.yml`, de modo que un fichero de perfil declara solo lo que difiere.
    Los perfiles se disponen en capas, no son excluyentes: puedes activar varios a
    la vez.

!!! warning "Los secretos no van en los ficheros de perfil"
    Un fichero de perfil se sube al control de versiones como cualquier otro
    recurso. Pon una contraseña de base de datos en `application-prod.yml` y ya está
    para siempre en tu historial de git. Los secretos de verdad llegan a través de
    variables de entorno o —mejor, a escala de flota— del servidor de configuración
    cifrado que se cubre al final de este capítulo.

## Enlazar la configuración a objetos tipados

Leer propiedades una clave cada vez con `@Value("${...}")` funciona para un valor o
dos, pero dispersa claves de cadena por todo tu código y no te da seguridad de
tipos. La mejor herramienta es `@ConfigurationProperties`, que enlaza todo un grupo
de propiedades relacionadas a un único objeto inmutable y validado que el resto de
tu código inyecta como cualquier otro bean.

La porción de originación de préstamos no incluye un tipo de propiedades propio
—su configuración son las claves `spring.*` propiedad del framework que viste en el
Listado 4.1—, así que lo que sigue es ilustrativo: la forma que añadirías cuando un
servicio desarrolla sus propios ajustes. Supón que Lumen necesita parámetros de
política de préstamo: el plazo máximo que aseguraría y la moneda por defecto. Los
modelas como un record:

```java
@ConfigurationProperties(prefix = "lumen.lending")
public record LendingProperties(
        int maxTermMonths,
        String defaultCurrency) {
}
```

Habilitas el enlazado una sola vez, en una clase de configuración, con
`@EnableConfigurationProperties`:

```java
@Configuration
@EnableConfigurationProperties(LendingProperties.class)
public class LendingConfig {
}
```

Después aportas los valores en YAML, bajo el prefijo que el record declaró. Spring
mapea automáticamente el `max-term-months` en kebab-case al componente del record
`maxTermMonths` —enlazado relajado, de modo que el YAML se mantiene idiomático y el
Java se mantiene en camelCase:

```yaml
lumen:
  lending:
    max-term-months: 84
    default-currency: EUR
```

Ahora cualquier bean puede pedir el objeto tipado por inyección de constructor y
leer `properties.maxTermMonths()` —un `int` de verdad, comprobado en el arranque,
sin ninguna clave de cadena a la vista:

```java
@Service
public class UnderwritingService {

    private final LendingProperties properties;

    public UnderwritingService(LendingProperties properties) {
        this.properties = properties;   // injected, fully typed
    }

    public boolean termIsAcceptable(int requestedMonths) {
        return requestedMonths <= properties.maxTermMonths();
    }
}
```

Un record enlazado de esta forma es inmutable, de modo que la configuración no
puede mutarse en tiempo de ejecución por accidente. Y como el enlazado ocurre en el
arranque, un valor que falta o está mal formado falla rápido: la aplicación se
niega a arrancar en vez de lanzar un error en lo profundo de una petición meses
después. Las propias capacidades de Firefly se configuran exactamente así: cada
propiedad `firefly.*` que conocerás en capítulos posteriores se enlaza a un tipo
`@ConfigurationProperties` del framework, y por eso una errata en una clave
`firefly.*` se detecta en el arranque, no en producción.

!!! note "Término clave — @ConfigurationProperties"
    `@ConfigurationProperties(prefix = "...")` enlaza un árbol de propiedades bajo
    un prefijo a un objeto tipado —habitualmente un `record` inmutable. Obtienes
    seguridad de tipos, enlazado relajado (de kebab-case a camelCase) y validación
    en tiempo de arranque, en lugar de claves de cadena `@Value` dispersas.

!!! spring "Equivalente en Spring"
    `@ConfigurationProperties` es Spring Boot puro. Firefly no añade ningún
    mecanismo de enlazado nuevo: simplemente usa el mismo para sus ajustes
    `firefly.*` que tú usas para los tuyos `lumen.*`. Cuando leas
    `firefly.cqrs.enabled` en un capítulo posterior, se está enlazando a un record
    del framework a través de esta misma maquinaria.

## Los secretos y el servidor de configuración

Quedan dos preguntas. ¿Dónde viven los secretos cuando una variable de entorno por
secreto y por servicio no escala? ¿Y cómo comparte una flota de docenas de servicios
los ajustes que tienen en común sin copiar y pegar YAML en cada repositorio?

La respuesta de Firefly a ambas es el **servidor de configuración**: un servidor
Spring Cloud Config que sirve configuración sobre HTTP, y para el que los starters
de capa cablean un cliente. Un servicio no lee todos sus ajustes de ficheros
locales; en el arranque le pide al servidor de configuración su configuración y
fusiona la respuesta en la misma jerarquía que ya conoces. El `application.yml`
local sigue ganando para lo que declara; el servidor rellena el resto.

El reactor de este libro se ejecuta contra ficheros locales —el Listado 4.1 es toda
la configuración para la ejecución de pruebas, sin servidor en el bucle—, así que
trata esta sección como *cómo encaja* a escala de flota, no como algo que ejerciten
las pruebas de originación de préstamos.

El valor del servidor es la **jerarquía**. En lugar de que cada servicio repita la
misma dirección de broker, el mismo formato de log y los mismos ajustes de trazado,
el servidor organiza la configuración en capas y las compone por servicio:

- **común** — ajustes ciertos para toda la flota (formato de log, endpoint de
  trazado, timeouts por defecto).
- **núcleo / dominio / experiencia** — ajustes compartidos por todos los servicios
  de una misma capa.
- **por servicio** — los deltas únicos de un servicio, clavados por su
  `spring.application.name`.

Un servicio recibe la unión, ganando la capa más específica: la misma regla de "la
fuente posterior gana", aplicada en toda una flota en lugar de dentro de un solo
fichero. Cambia el endpoint de trazado una sola vez en **común** y todos los
servicios lo recogen en su siguiente refresco. Esta es la versión a escala de flota
del principio del delta que viste con los perfiles: cada capa declara solo lo que
añade.

Los secretos viajan por el mismo canal, **cifrados en reposo**. El servidor de
configuración guarda una clave de cifrado; los valores sensibles se almacenan como
texto cifrado marcado con un prefijo `{cipher}` y se descifran al vuelo a medida
que el servidor los entrega:

```yaml
# stored in the config server — the value is ciphertext, not the password
spring:
  r2dbc:
    password: '{cipher}AQB4f2c1e9...d0a7'
```

El valor que se sube es texto cifrado opaco; solo el servidor, que posee la clave,
puede convertirlo de nuevo en la contraseña, y el servicio recibe el texto plano
únicamente por la red en el arranque. Ningún secreto reside jamás en el repositorio
git de un servicio, y rotar uno es un único cambio en un único lugar.

Junta las tres capas y el cuadro queda completo: `application.yml` local y perfiles
para lo que un servicio posee, variables de entorno para los valores de plataforma
de última milla, y el servidor de configuración para todo lo que una flota comparte
—con `{cipher}` manteniendo los secretos fuera de todos los repositorios.

!!! note "Término clave — servidor de configuración"
    Un **servidor de configuración** es un servicio central que sirve configuración
    sobre HTTP. Firefly lo usa para componer ajustes jerárquicos comunes, de capa y
    por servicio para una flota, y para guardar secretos como valores cifrados con
    `{cipher}` que se descifran en la entrega, de modo que la configuración
    compartida vive en un único lugar y ningún secreto vive en un repositorio.

!!! spring "Equivalente en Spring"
    El servidor de configuración es **Spring Cloud Config**, sin cambios. Spring
    Cloud puro te da el servidor, el cliente y el cifrado `{cipher}`. Lo que Firefly
    añade es la *convención*: la jerarquía común/núcleo/dominio/experiencia y los
    starters de capa que cablean el cliente por ti, de modo que un nuevo servicio se
    une a la jerarquía declarando su nombre en lugar de configurando un cliente a
    mano.

## Lo que has aprendido {.recap}

- Un servicio Firefly es **un artefacto configurado de muchas maneras**. El mismo
  JAR se ejecuta en pruebas y en producción; solo difiere la configuración
  externalizada.
- La configuración de pruebas de originación de préstamos (Listado 4.1) cablea una
  pila reactiva real sin Docker: H2 en memoria, Flyway migrando sobre JDBC y R2DBC
  leyendo en tiempo de ejecución, ambos apuntando al *mismo* nombre de base de
  datos para que la tabla migrada sea la que leen los repositorios.
- Las propiedades se resuelven en una jerarquía fija —**base `application.yml`,
  luego ficheros de perfil, luego variables de entorno**, ganando las fuentes
  posteriores. Un perfil y una variable de entorno declaran cada uno solo el delta.
- `@ConfigurationProperties` enlaza un grupo de propiedades a un objeto inmutable,
  tipado y validado en el arranque: el mismo mecanismo que Firefly usa para cada
  ajuste `firefly.*`.
- El **servidor de configuración** sirve a una flota configuración jerárquica
  común/de capa/por servicio y guarda los secretos como valores cifrados con
  `{cipher}`, manteniendo la configuración compartida en un único lugar y los
  secretos fuera de todos los repositorios. El reactor en sí se ejecuta sobre
  ficheros locales; el servidor es cómo esto escala más allá de un módulo.

## Pruébalo tú mismo {.exercises}

1. **Rastrea el nombre compartido.** En `core-lending-loan-origination/src/test/resources/application.yml`,
   las URLs de R2DBC y Flyway nombran ambas la base de datos `lumen`. Cambia *solo*
   la URL de Flyway para que nombre `lumen2`, ejecuta
   `mvn -q -pl core-lending-loan-origination test` y lee el fallo. Luego restáuralo
   y confirma que las 18 pruebas vuelven a pasar. Acabas de sentir por qué los dos
   nombres deben coincidir.
2. **Añade un delta de perfil.** Crea `application-fast.yml` junto a la configuración
   de pruebas que establezca `spring.flyway.baseline-on-migrate: false`, y razona
   qué valor gana cuando ejecutas con `spring.profiles.active=fast`. ¿Qué fichero
   aporta `spring.application.name` en esa ejecución, y por qué?
3. **Sobrescribe desde el entorno.** Deduce el nombre de la variable de entorno que
   sobrescribiría `spring.r2dbc.username` del Listado 4.1 (aplica la regla de
   mayúsculas y guiones bajos), y el de `spring.flyway.locations`.
4. **Diseña un record de propiedades tipado.** Esboza un record
   `@ConfigurationProperties` para dos parámetros de préstamo propios —pongamos un
   importe mínimo de préstamo y una lista de monedas admitidas— y el YAML
   correspondiente bajo un prefijo `lumen.lending`. ¿Qué tipo de componente mapea a
   una lista YAML?
5. **Coloca un secreto correctamente.** Dada la contraseña de producción de la
   sección de jerarquía, enumera los tres lugares donde podría vivir —fichero de
   perfil, variable de entorno, valor `{cipher}` del servidor de configuración— y
   ordénalos de peor a mejor para una flota de cincuenta servicios. Justifica la
   ordenación en una frase cada uno.

## Adónde ir ahora

Ya puedes configurar un servicio igual que lo hace la compilación, disponer
entornos en capas con perfiles, enlazar ajustes a objetos tipados y colocar los
secretos donde corresponde. Los próximos capítulos se apoyan en esto
constantemente: cada propiedad `firefly.*` que activa una capacidad se enlaza a
través de la maquinaria que acabas de aprender. El capítulo 5 se adentra de lleno
en el modelo reactivo, la clave de bóveda sobre la que se sostiene el resto del
libro.
