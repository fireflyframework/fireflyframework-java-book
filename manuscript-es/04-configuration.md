Un servicio que se ejecuta en tu portátil, en un arnés de pruebas, en un clúster
de staging y en producción es *un único* artefacto de compilación: el mismo JAR,
byte a byte. Lo que cambia entre esos cuatro lugares nunca es el código; es la
configuración. La URL de la base de datos, la dirección del broker, el nivel de
log, el puerto en el que escucha, el secreto que desbloquea una API ascendente:
todo ello vive fuera del JAR, en capas, de modo que cada entorno aporta solo lo
que difiere de los valores por defecto.

Esto es la **configuración externalizada**, y es una de las ideas portantes que
heredas directamente de Spring Boot. Firefly no cambia las reglas; añade una
jerarquía por encima de ellas: un servidor de configuración que sirve ajustes
comunes, de núcleo y de dominio a toda una flota, con los secretos cifrados en
reposo. Este capítulo empieza con los dos ficheros contra los que el servicio de
núcleo realmente se ejecuta —el que arranca un servidor en vivo y el casi idéntico
que usan sus pruebas— y luego avanza hacia fuera: la jerarquía de resolución, el
enlace tipado con `@ConfigurationProperties` y, por último, dónde encajan los
secretos y el servidor de configuración.

## El fichero contra el que arranca el servicio

En el capítulo 2 arrancaste `core-lending-loan-origination` con `mvn spring-boot:run`,
viste el banner e hiciste un POST de una solicitud de préstamo. La configuración
que hizo posible esa ejecución es un único fichero corto. Abre la configuración
*ejecutable* —la que está en el classpath principal, la que lee un
`mvn spring-boot:run` o un `java -jar`—:

::: listing core-lending-loan-origination/src/main/resources/application.yml | Listado 4.1 — la configuración ejecutable: un puerto de servidor, H2, Flyway y R2DBC apuntando a una única base de datos en memoria
server:
  port: 8081

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

Léelo de arriba abajo y habrás leído el contrato completo de una capa que sirve
CRUD real sin nada instalado salvo una JVM.

`server.port: 8081` es la primera línea, y se gana su sitio: la capa de núcleo
escucha en el **8081**, la capa de dominio en el **8082** y el BFF de experiencia
en el **8080**, de modo que las tres pueden ejecutarse en un mismo portátil a la
vez sin colisionar. Si omites esta línea, el starter deja el servidor reactivo en
su valor por defecto, `8080` —correcto para un único servicio, un choque de
puertos en el momento en que arranca una segunda capa—. (Conociste el `8080` como
valor por defecto del banner en el capítulo 2, antes de que esta propiedad se
fijara; aquí la propiedad mueve el listener al `8081`, que es por lo que los
comandos de ejecución y los `curl` de este libro acceden a `localhost:8081` para
el núcleo.)

`spring.application.name` nombra el servicio. Es la identidad sobre la que se basa
cualquier otro subsistema: es el nombre en el banner de arranque, el `title` del
documento OpenAPI generado, la etiqueta de las métricas y —como verás al final de
este capítulo— la carpeta en la que el servidor de configuración busca cuando le
sirve a este servicio sus ajustes. Una propiedad, asomando en cuatro sitios;
trazamos dos de ellos en la siguiente sección.

`spring.r2dbc.*` configura la ruta de acceso a datos **reactiva**. La URL
`r2dbc:h2:mem:///lumen;DB_CLOSE_DELAY=-1` dice: una base de datos H2 en memoria
llamada `lumen`, mantenida viva durante toda la vida de la JVM. Esto es a través
de lo que tus repositorios leen y escriben, sobre el bucle de eventos, sin
bloquear.

`spring.flyway.*` configura la migración del esquema, que se ejecuta sobre
**JDBC** —`jdbc:h2:mem:lumen;DB_CLOSE_DELAY=-1`—. Las dos URL usan drivers
distintos (`r2dbc:` frente a `jdbc:`) pero nombran la *misma* base de datos en
memoria, `lumen`. Ese nombre compartido es todo el truco: Flyway crea la tabla
sobre JDBC en el arranque, y R2DBC lee esa misma tabla en tiempo de ejecución.
`baseline-on-migrate: true` permite que Flyway adopte limpiamente una base de
datos vacía; `locations: classpath:db/migration` lo apunta a la migración que vive
junto al código.

!!! note "Término clave — configuración externalizada"
    La **configuración externalizada** significa que los ajustes viven fuera del
    artefacto compilado —en `application.yml`, ficheros de perfil y variables de
    entorno— de modo que el mismo JAR se ejecuta sin cambios en todos los entornos.
    El código lee propiedades con *nombre*; el entorno aporta los *valores*.

!!! warning "Dos URL, una base de datos: mantén el nombre sincronizado"
    La URL de R2DBC y la URL de Flyway deben nombrar la misma base de datos en
    memoria (`lumen` aquí). Si se desincronizan, Flyway migra una base de datos y
    tus repositorios leen otra vacía —y el fallo parece una tabla que falta, no una
    errata de configuración—. Cuando copies este patrón a un módulo nuevo, cambia
    ambos nombres a la vez.

!!! spring "Equivalente en Spring"
    Nada del Listado 4.1 es específico de Firefly. `server.port`,
    `spring.application.name`, `spring.r2dbc.*` y `spring.flyway.*` son claves de
    propiedad estándar de Spring Boot, enlazadas por la propia autoconfiguración de
    Spring Boot. Firefly hereda intacto todo el mecanismo de configuración
    externalizada y superpone su jerarquía encima; nunca lo reemplaza.

## Una propiedad, dos superficies: el banner y el título de OpenAPI

Vale la pena detenerse en `spring.application.name`, porque es la prueba pequeña
más clara de que la configuración *fluye hacia dentro* del framework en lugar de
quedarse en un fichero que el framework ignora. La fijas una vez; aparece dos veces
en el arranque que ejecutaste en el capítulo 2.

Primero, el banner. El starter incluye un `banner.txt` con
`${spring.application.name}` como marcador de posición, y Spring Boot sustituye el
valor en vivo antes de que se ejecute cualquier línea de negocio. Con
`server.port: 8081` y el nombre del Listado 4.1, el arranque imprime el banner real
del framework:

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

La línea `Application:` es tu propiedad, devuelta como eco. (La línea `SwaggerUI:`
muestra el marcador por defecto `8080` del starter; el servidor en vivo está en el
`8081`, así que la URL de documentación que funciona es
`http://localhost:8081/swagger-ui.html` —un recordatorio de que la plantilla del
banner se renderiza a partir de los valores por defecto, mientras que `server.port`
redirige el listener real.)

Segundo, el documento OpenAPI. El framework construye el `title` del documento a
partir de la misma propiedad, con el sufijo `API`. Pídele su especificación al
servicio en ejecución y la propiedad asoma una segunda vez:

```text
$ curl -s http://localhost:8081/v3/api-docs
{"openapi":"3.1.0","info":{"title":"core-lending-loan-origination API","description":"core-lending-loan-origination API Documentation","license":{"name":"Apache 2.0","url":"https://www.apache.org/licenses/LICENSE-2.0"},"version":"1.0.0"}, ... }
```

Así que una única línea de YAML —`name: core-lending-loan-origination`— nombra el
proceso en su banner, titula su contrato de API autogenerado, etiqueta sus métricas
y (a escala de flota) selecciona su carpeta del servidor de configuración. Ese es
el beneficio de las propiedades con *nombre* leídas por el framework: cambias un
valor y todos los subsistemas que se basan en él lo siguen. Renombra el servicio y
el banner, el título de OpenAPI y la búsqueda de configuración se mueven todos a la
vez, sin una segunda edición.

!!! note "Término clave — enlace relajado"
    Spring Boot enlaza las propiedades con **enlace relajado** (relaxed binding):
    una clave puede escribirse en kebab-case, camelCase o con guiones bajos de
    variable de entorno y aun así enlazar al mismo destino. `server.port`,
    `SERVER_PORT` y `server.port` en YAML fijan todos el mismo valor. Por eso el
    YAML se mantiene idiomático en kebab-case mientras que el Java que lo lee se
    mantiene en camelCase —te apoyarás en ello otra vez con
    `@ConfigurationProperties` más abajo—.

## Ejecútalo

Dos formas de ejecutar el fichero del Listado 4.1, ambas de primera clase. Desde el
directorio del módulo, el plugin de Maven lo arranca in situ:

```text
$ mvn spring-boot:run
```

O, como el goal de repackage de Spring Boot está cableado en el padre, construye el
JAR ejecutable y ejecútalo como cualquier artefacto —el mismo JAR byte a byte que
desplegarías—:

```text
$ mvn -q -pl core-lending-loan-origination package
$ java -jar core-lending-loan-origination/target/core-lending-loan-origination-0.1.0-SNAPSHOT.jar
```

De una forma u otra, H2 se levanta en memoria, Flyway aplica la migración sobre
JDBC, Netty enlaza el **8081** y el servicio está listo para servir la API de
loan-origination que ejercitaste en el capítulo 2. Una vez arriba, una rápida
comprobación de salud confirma el cableado —fíjate en que el indicador de R2DBC
reporta la base de datos `H2` en vivo que esa misma configuración acaba de
configurar (recortado por espacio)—:

```text
$ curl -s http://localhost:8081/actuator/health
{"status":"UP","groups":["liveness","readiness"],"components":{
  "cqrs":{"status":"UP","details":{"command_bus":"UP","query_bus":"UP","command_handlers":0,"query_handlers":0}},
  "eda":{"status":"UP","details":{"enabled":true,"message":"All EDA components are healthy"}},
  "r2dbc":{"status":"UP","details":{"database":"H2"}},
  "ping":{"status":"UP"}}}
```

### La misma configuración, en el classpath de pruebas

Hay una segunda copia de este fichero, y la diferencia entre ambas es el quid de la
configuración externalizada. Junto a la configuración ejecutable vive la
configuración de *pruebas*, que las pruebas del módulo recogen automáticamente
porque está en el classpath de pruebas. Es el mismo contrato H2/R2DBC/Flyway, menos
la única línea que una prueba no necesita:

::: listing core-lending-loan-origination/src/test/resources/application.yml | Listado 4.2 — la configuración de pruebas: la misma pila H2, sin puerto de servidor
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

Fíjate en lo que *falta*: no hay bloque `server:`. Una prueba de slice maneja la
API a través de `WebTestClient`, que enlaza el contexto de aplicación directamente
sin socket, de modo que no hay puerto en el que escuchar —y fijar uno solo
arriesgaría un choque cuando las pruebas se ejecutan en paralelo—. El contrato de
datos es idéntico al del fichero ejecutable, así que las pruebas ejercitan
exactamente la misma pila reactiva que usa el servidor en vivo. Esta es la lección
en miniatura: *el cableado de datos se comparte; solo la línea con forma de
despliegue (el puerto) difiere entre ejecutar y probar.* Ejecuta las pruebas y
Maven reporta el recuento del módulo de núcleo:

```text
$ mvn -q -pl core-lending-loan-origination test
```

```text
Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Esas dieciocho son la parte de la capa de núcleo de las **33** del reactor (núcleo
18, dominio 6, experiencia 9). Arrancan el contexto reactivo completo contra H2 en
memoria y manejan la API real sin cabeza (headless) —las mismas rutas de código que
el Listado 4.1 sirve sobre un socket—.

!!! tip "Punto de control"
    Si ves `Tests run: 18, Failures: 0`, la configuración de pruebas cableó una pila
    de datos reactiva real sin nada instalado salvo una JVM. Si en cambio una prueba
    reporta una tabla que falta, las dos URL del Listado 4.2 se han desincronizado
    —vuelve a comprobar que las URL de R2DBC y de Flyway nombran la misma base de
    datos—.

!!! note "Término clave — recursos main vs. test"
    Spring Boot lee `src/main/resources/application.yml` cuando la aplicación
    *se ejecuta*, y deja que `src/test/resources/application.yml` lo sobrescriba
    cuando la aplicación se carga desde una *prueba* (el classpath de pruebas va
    primero). Mantener ambos sincronizados en el cableado de datos —y dejar que
    difieran solo en la forma de despliegue, como el puerto— es como el reactor
    demuestra bajo prueba exactamente lo que sirve en producción.

## La jerarquía de resolución

Dos ficheros para un servicio ya es la jerarquía en funcionamiento, pero es solo el
principio. Spring Boot resuelve una propiedad consultando una serie de fuentes en
un orden fijo, y **las fuentes posteriores ganan**. Tres de esas fuentes llevan
casi todo el peso en la práctica:

1. **`application.yml`** — la línea base. Valores por defecto sensatos que son
   ciertos en todas partes, como el nombre del servicio y el puerto.
2. **Ficheros específicos de perfil** — `application-{profile}.yml`. Activados por
   nombre, sobrescriben la línea base para un entorno o una forma de ejecución.
3. **Variables de entorno y propiedades de sistema de la JVM** — aportadas por la
   plataforma en el lanzamiento. Sobrescriben todo lo demás, que es exactamente lo
   que quieres para valores que difieren por despliegue o que nunca deben estar en
   un fichero.

Un **perfil** es simplemente una porción de configuración con nombre. Activas uno
con la propiedad `spring.profiles.active` —ella misma fijada normalmente por una
variable de entorno— y Spring carga el `application-{profile}.yml` correspondiente
encima de la línea base. Un fichero de producción podría tener este aspecto
(ilustrativo —el reactor incluye el fichero H2 ejecutable del Listado 4.1, no un
fichero de prod—):

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

Fíjate en lo que está *ausente*: aquí no hay `spring.application.name` ni
`server.port`, porque la línea base ya los fijó y producción no los cambia. Un
fichero de perfil declara solo el delta. Ejecuta con `--spring.profiles.active=prod`
y las URL de Postgres ganan sobre la línea base de H2; ejecuta sin perfil y
obtienes los valores por defecto en memoria del Listado 4.1. El mismo JAR, dos bases
de datos, decididas enteramente fuera del código.

La capa final —las variables de entorno— sigue una regla mecánica de nomenclatura:
pon la propiedad en mayúsculas, sustituye puntos y guiones por guiones bajos. Así
`spring.r2dbc.password` se sobrescribe con la variable de entorno
`SPRING_R2DBC_PASSWORD`, y `server.port` con `SERVER_PORT`. Así es como una
plataforma de despliegue inyecta un valor que el fichero nunca contiene, o mueve el
listener sin tocar una línea de YAML:

```text
export SPRING_PROFILES_ACTIVE=prod
export SERVER_PORT=9443                  # platform decides the port at deploy time
export SPRING_R2DBC_PASSWORD=...         # injected by the platform, never in git
java -jar core-lending-loan-origination.jar
```

La línea base nombra la propiedad, el perfil la remodela para un entorno y la
variable de entorno aporta el único valor que nunca confiarías al repositorio. El
mismo JAR, tres lugares, tres comportamientos.

!!! note "Término clave — perfil"
    Un **perfil** es una porción de configuración con nombre activada por
    `spring.profiles.active`. Spring carga `application-{profile}.yml` sobre la línea
    base `application.yml`, de modo que un fichero de perfil declara solo lo que
    difiere. Los perfiles se superponen, no son excluyentes: puedes activar varios a
    la vez.

!!! warning "Los secretos no pertenecen a los ficheros de perfil"
    Un fichero de perfil se confía al control de versiones como cualquier otro
    recurso. Pon una contraseña de base de datos en `application-prod.yml` y ya está
    en tu historial de git para siempre. (Esto es exactamente por lo que la
    contraseña de H2 en el Listado 4.1 es una cadena vacía —no hay secreto que
    filtrar en una base de datos de desarrollo en memoria—.) Los secretos reales
    llegan a través de variables de entorno o —mejor, a escala de flota— del
    servidor de configuración cifrado que se trata al final de este capítulo.

## Cómo se configuran las demás capas

El fichero de núcleo del Listado 4.1 es el más simple de los tres porque la capa de
núcleo es dueña de la persistencia. Las otras dos capas del reactor configuran
capacidades *distintas* a través del mismísimo mecanismo, y un vistazo a ellas
muestra que la "configuración externalizada" escala desde el cableado de datos
hasta los interruptores del framework y las URL servicio-a-servicio sin cambiar las
reglas.

La capa de **dominio** (`domain-lending-loan-origination`, puerto **8082**) no es
dueña de ninguna base de datos; orquesta. Su `application.yml` fija
`server.port: 8082`, nombra el servicio y luego acciona los interruptores de
*capacidad* de Firefly —`firefly.cqrs.enabled`, `firefly.orchestration.enabled`,
`firefly.eda.enabled` con `default-publisher-type: APPLICATION_EVENT` (el
transporte de eventos dentro de la JVM, sin Kafka, sin Docker)— y apunta la costura
de su SDK de núcleo al sistema de registro con
`firefly.lumen.core.loan-origination.base-path: http://localhost:8081`. Esa última
propiedad es como la saga que conocerás en capítulos posteriores sabe dónde vive el
servicio de núcleo; cambia la URL y la saga escribe en otro sitio, sin cambio de
código.

El BFF de **experiencia** (`exp-lending`, puerto **8080**) configura otra
preocupación más: fija `firefly.application.security.enabled: false` para que el
BFF sea accesible localmente sin un token (los controladores conservan sus
anotaciones `@Secure` reales; el interruptor solo cortocircuita la aplicación
forzosa —producción lo deja en `true`—), y apunta *su* costura a la capa de dominio
con `lumen.exp.loan-origination.base-path: http://localhost:8082`.

Tres capas, tres necesidades de configuración completamente distintas —una base de
datos, un conjunto de interruptores del framework, una URL descendente— todas
expresadas como propiedades externalizadas en la misma forma plana de YAML. Nada de
ello es a medida; es el mismo enlace de Spring Boot aplicado a claves que el
framework resulta que lee. (Estas claves `firefly.*` y `lumen.*` son el tema de la
siguiente sección y de capítulos posteriores; lo importante aquí es solo que
cabalgan la misma jerarquía de resolución idéntica que `server.port`.)

!!! spring "Equivalente en Spring"
    Los interruptores `firefly.*` que fijan las capas de dominio y experiencia
    —`firefly.cqrs.enabled`, `firefly.eda.enabled`,
    `firefly.application.security.enabled`— son propiedades ordinarias de Spring Boot
    enlazadas a tipos `@ConfigurationProperties` del framework. No hay un canal de
    configuración especial: una capacidad de Firefly se conmuta con la misma clave de
    `application.yml`, sobrescritura de perfil o variable de entorno que cualquier
    ajuste de Spring Boot. El mismo mecanismo, más mandos.

## Enlazar la configuración a objetos tipados

Leer propiedades de una en una con `@Value("${...}")` funciona para un valor o dos,
pero desparrama claves de cadena por tu código y no te da seguridad de tipos. La
mejor herramienta es `@ConfigurationProperties`, que enlaza todo un grupo de
propiedades relacionadas a un único objeto inmutable y validado que el resto de tu
código inyecta como cualquier otro bean. Esto no es hipotético: el
`firefly.lumen.core.loan-origination.base-path` de la capa de dominio y el
`lumen.exp.loan-origination.base-path` de la capa de experiencia están ambos
enlazados a tipos de propiedades exactamente de esta manera, que es como una errata
en una de esas URL se detecta en el arranque y no en la primera saga.

El slice de loan-origination del núcleo no incluye un tipo de propiedades propio a
medida —su configuración son las claves `server.*` y `spring.*`, propiedad del
framework, que viste en el Listado 4.1— así que lo que sigue es ilustrativo: la
forma que añadirías cuando un servicio hace crecer sus propios ajustes. Supón que
Lumen necesita mandos de política de préstamos —el plazo máximo que suscribirá y la
moneda por defecto—. Los modelas como un record:

```java
@ConfigurationProperties(prefix = "lumen.lending")
public record LendingProperties(
        int maxTermMonths,
        String defaultCurrency) {
}
```

Habilitas el enlace una vez, en una clase de configuración, con
`@EnableConfigurationProperties`:

```java
@Configuration
@EnableConfigurationProperties(LendingProperties.class)
public class LendingConfig {
}
```

Después aportas los valores en YAML, bajo el prefijo que declaró el record. Spring
mapea el `max-term-months` en kebab-case al componente del record `maxTermMonths`
automáticamente —el enlace relajado que conociste arriba, de modo que el YAML se
mantiene idiomático y el Java se mantiene en camelCase—:

```yaml
lumen:
  lending:
    max-term-months: 84
    default-currency: EUR
```

Ahora cualquier bean puede pedir el objeto tipado por inyección de constructor y
leer `properties.maxTermMonths()` —un `int` real, comprobado en el arranque, sin
ninguna clave de cadena a la vista—:

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

Un record enlazado de esta manera es inmutable, así que la configuración no puede
mutarse en tiempo de ejecución por accidente. Y como el enlace ocurre en el
arranque, un valor que falta o está mal formado falla rápido —la aplicación se
niega a arrancar en lugar de lanzar una excepción en lo profundo de una petición
meses después—. Las propias capacidades de Firefly se configuran exactamente de
esta manera: cada propiedad `firefly.*` que conocerás en capítulos posteriores está
enlazada a un tipo `@ConfigurationProperties` del framework, que es por lo que una
errata en una clave `firefly.*` (o en esa URL `base-path` que fija la capa de
dominio) se detecta en el arranque, no en producción.

!!! note "Término clave — @ConfigurationProperties"
    `@ConfigurationProperties(prefix = "...")` enlaza un árbol de propiedades bajo un
    prefijo a un objeto tipado —comúnmente un `record` inmutable—. Obtienes
    seguridad de tipos, enlace relajado (de kebab-case a camelCase) y validación en
    tiempo de arranque, en lugar de claves de cadena `@Value` desparramadas.

!!! spring "Equivalente en Spring"
    `@ConfigurationProperties` es Spring Boot puro. Firefly no añade ningún mecanismo
    de enlace nuevo: simplemente usa el mismo para sus ajustes `firefly.*` que el que
    tú usas para los tuyos `lumen.*`. Cuando lees `firefly.cqrs.enabled` desde la
    configuración de la capa de dominio, se está enlazando a un record del framework
    a través de esta misma maquinaria.

## Secretos y el servidor de configuración

Quedan dos preguntas. ¿Dónde viven los secretos cuando una variable de entorno por
secreto por servicio no escala? ¿Y cómo comparte una flota de docenas de servicios
los ajustes que tienen en común sin copiar y pegar YAML en cada repositorio?

La respuesta de Firefly a ambas es el **servidor de configuración** —un servidor
Spring Cloud Config que sirve configuración sobre HTTP, y para el que los starters
de capa cablean un cliente—. Un servicio no lee todos sus ajustes de ficheros
locales; en el arranque le pide su configuración al servidor de configuración y
fusiona la respuesta en la misma jerarquía que ya conoces. El `application.yml`
local sigue ganando para lo que declara; el servidor rellena el resto.

El reactor de este libro se ejecuta contra ficheros locales —el Listado 4.1 es toda
la configuración de la ejecución de núcleo, sin servidor en el bucle— así que trata
esta sección como *cómo encaja* a escala de flota, no como algo que el servicio de
loan-origination ejercite hoy.

El valor del servidor es la **jerarquía**. En lugar de que cada servicio repita la
misma dirección de broker, formato de log y ajustes de trazado, el servidor
organiza la configuración en capas y las compone por servicio:

- **common** — ajustes ciertos para toda la flota (formato de log, endpoint de
  trazado, timeouts por defecto).
- **core / domain / experience** — ajustes compartidos por cada servicio de una
  capa (este es el hogar natural de cosas como el bloque `firefly.eda.*` que la capa
  de dominio fija localmente hoy).
- **per-service** — los deltas únicos de un servicio, indexados por su
  `spring.application.name` —la misma propiedad que nombró el banner y tituló el
  documento OpenAPI ahora selecciona la carpeta—.

Un servicio recibe la unión, ganando la capa más específica —la misma regla de "la
fuente posterior gana", aplicada a través de una flota en lugar de dentro de un
fichero—. Cambia el endpoint de trazado una vez en **common**, y cada servicio lo
recoge en su siguiente refresco. Esta es la versión a escala de flota del principio
del delta que viste con los perfiles: cada capa declara solo lo que añade.

Los secretos cabalgan el mismo canal, **cifrados en reposo**. El servidor de
configuración guarda una clave de cifrado; los valores sensibles se almacenan como
texto cifrado marcado con un prefijo `{cipher}` y se descifran al vuelo conforme el
servidor los entrega:

```yaml
# stored in the config server — the value is ciphertext, not the password
spring:
  r2dbc:
    password: '{cipher}AQB4f2c1e9...d0a7'
```

El valor confiado al repositorio es texto cifrado opaco; solo el servidor, que tiene
la clave, puede volver a convertirlo en la contraseña, y el servicio recibe el texto
plano solo por el cable en el arranque. Ningún secreto reside jamás en el
repositorio de git de un servicio, y rotar uno es un único cambio en un único lugar.
(Contrasta eso con la contraseña vacía de H2 del Listado 4.1: una base de datos de
desarrollo no tiene nada que proteger, así que se queda en claro; una contraseña
real de Postgres sería un valor `{cipher}` que el servidor descifra en la entrega.)

Junta las tres capas y el cuadro queda completo: `application.yml` local y perfiles
para lo que un servicio posee, variables de entorno para los valores de última milla
de la plataforma, y el servidor de configuración para todo lo que comparte una flota
—con `{cipher}` manteniendo los secretos fuera de cada repositorio—.

!!! note "Término clave — servidor de configuración"
    Un **servidor de configuración** es un servicio central que sirve configuración
    sobre HTTP. Firefly lo usa para componer ajustes jerárquicos comunes, de capa y
    por servicio para una flota, y para guardar secretos como valores cifrados con
    `{cipher}` descifrados en la entrega —de modo que la configuración compartida
    vive en un único lugar y ningún secreto vive en un repositorio—.

!!! spring "Equivalente en Spring"
    El servidor de configuración es **Spring Cloud Config**, intacto. Spring Cloud a
    secas te da el servidor, el cliente y el cifrado `{cipher}`. Lo que Firefly añade
    es la *convención*: la jerarquía common/core/domain/experience y los starters de
    capa que cablean el cliente por ti, de modo que un servicio nuevo se une a la
    jerarquía declarando su nombre en lugar de configurando un cliente a mano.

## Lo que has aprendido {.recap}

- Un servicio Firefly es **un artefacto configurado de muchas maneras**. El mismo
  JAR se ejecuta en pruebas y en producción mediante `mvn spring-boot:run` o
  `java -jar`; solo difiere la configuración externalizada.
- La configuración ejecutable del servicio de núcleo (Listado 4.1) cablea una pila
  reactiva real sin Docker: un `server.port` de 8081, H2 en memoria, Flyway migrando
  sobre JDBC y R2DBC leyendo en tiempo de ejecución, ambos apuntando al *mismo*
  nombre de base de datos para que la tabla migrada sea la que leen los repositorios.
  La configuración de pruebas (Listado 4.2) es el mismo contrato de datos menos el
  puerto, porque `WebTestClient` no necesita socket.
- Una propiedad hace mucho trabajo: `spring.application.name` asoma en la línea
  `Application:` del banner de arranque y como el `title` del documento OpenAPI
  (`core-lending-loan-origination API`), y a escala de flota selecciona la carpeta
  del servidor de configuración.
- Las propiedades se resuelven en una jerarquía fija —**la línea base
  `application.yml`, luego los ficheros de perfil, luego las variables de entorno**—,
  ganando las fuentes posteriores. Un perfil y una variable de entorno declaran cada
  uno solo el delta.
- Las tres capas configuran preocupaciones distintas a través del mismo mecanismo:
  el núcleo fija una base de datos, el dominio acciona interruptores de capacidad
  `firefly.*` y un `base-path` de núcleo, la experiencia desactiva la aplicación
  forzosa de seguridad y apunta al dominio —todo ello, propiedades externalizadas
  ordinarias—.
- `@ConfigurationProperties` enlaza un grupo de propiedades a un objeto inmutable,
  tipado y validado en el arranque —el mismo mecanismo que Firefly usa para cada
  ajuste `firefly.*` y para las costuras `base-path` entre capas—.
- El **servidor de configuración** sirve a una flota una configuración jerárquica
  common/capa/por-servicio y guarda los secretos como valores cifrados con
  `{cipher}`. El reactor en sí se ejecuta sobre ficheros locales; el servidor es como
  esto escala más allá de un único módulo.

## Pruébalo tú mismo {.exercises}

1. **Mueve el puerto.** En
   `core-lending-loan-origination/src/main/resources/application.yml`, cambia
   `server.port` a `9081`, ejecuta `mvn spring-boot:run` y confirma que el servicio
   ahora responde en `http://localhost:9081/actuator/health`. Luego sobrescríbelo sin
   editar el fichero: para la aplicación y arráncala con
   `SERVER_PORT=9091 mvn spring-boot:run`. ¿Qué valor gana, y por qué?
2. **Traza el nombre compartido.** En
   `core-lending-loan-origination/src/test/resources/application.yml`, las URL de
   R2DBC y Flyway nombran ambas la base de datos `lumen`. Cambia *solo* la URL de
   Flyway para que nombre `lumen2`, ejecuta
   `mvn -q -pl core-lending-loan-origination test` y lee el fallo. Luego restáuralo y
   confirma que las 18 pruebas vuelven a pasar. Acabas de sentir por qué los dos
   nombres deben coincidir.
3. **Sigue una propiedad hasta dos superficies.** Arranca el servicio de núcleo y
   confirma que `spring.application.name` aparece en la línea `Application:` del
   banner *y* como el `title` en `curl -s http://localhost:8081/v3/api-docs`. Luego
   cambia el nombre a `lumen-core-demo`, reinicia y observa cómo ambas superficies lo
   siguen. ¿Dónde más repercutiría este renombrado en una flota?
4. **Añade un delta de perfil.** Crea `application-fast.yml` junto a la configuración
   ejecutable que fije `spring.flyway.baseline-on-migrate: false`, y razona sobre qué
   valor gana cuando ejecutas con `spring.profiles.active=fast`. ¿Qué fichero aporta
   `server.port` y `spring.application.name` en esa ejecución, y por qué?
5. **Sobrescribe desde el entorno.** Deduce el nombre de la variable de entorno que
   sobrescribiría `spring.r2dbc.username` del Listado 4.1 (aplica la regla de
   mayúsculas y guiones bajos), y el correspondiente a `spring.flyway.locations`.
6. **Diseña un record de propiedades tipado.** Esboza un record
   `@ConfigurationProperties` para dos mandos de préstamos propios —digamos un importe
   mínimo de préstamo y una lista de monedas soportadas— y el YAML correspondiente
   bajo un prefijo `lumen.lending`. ¿Qué tipo de componente mapea a una lista de YAML?
7. **Coloca un secreto correctamente.** Dada la contraseña de Postgres de producción
   de la sección de la jerarquía, enumera los tres lugares en los que podría vivir
   —fichero de perfil, variable de entorno, valor `{cipher}` del servidor de
   configuración— y ordénalos de peor a mejor para una flota de cincuenta servicios.
   Justifica el orden en una frase cada uno.

## Adónde ir ahora

Ahora ya puedes configurar un servicio de la forma en que lo hace la build:
arrancarlo con un puerto real en el 8081, ejecutar el mismo contrato de datos bajo
prueba, superponer entornos con perfiles, enlazar ajustes a objetos tipados y
colocar los secretos donde corresponde. Los próximos capítulos se apoyan en esto
constantemente —cada propiedad `firefly.*` que conmuta una capacidad enlaza a través
de la maquinaria que acabas de aprender—. El capítulo 5 se vuelca al modelo reactivo
por completo, la clave de bóveda sobre la que se sostiene el resto del libro.
