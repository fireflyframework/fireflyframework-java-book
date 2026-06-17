Los capítulos que siguen dan por hecho que puedes leer Spring Boot moderno con
comodidad. Este preludio se asegura de que así sea — incluso si tu último Spring
fue MVC y JPA, o si llegas desde otro port de Firefly como PyFly. **No** es un
curso de Spring. Enseña exactamente la porción de Spring Boot, WebFlux, Project
Reactor y R2DBC en la que se apoyan los capítulos posteriores, más o menos en el
orden en que se apoyan en ella. Cada sección termina con una breve nota *Si vienes
de…* que traduce la idea desde el lugar en el que podrías estar hoy.

Si ya entregas Spring Boot reactivo a diario — controladores WebFlux, repositorios
R2DBC, `Mono`/`Flux` dormido — pasa directamente al Capítulo 1. Nada de lo que hay
aquí te sorprenderá. Si lo reactivo es la parte que no te resulta familiar, sigue
leyendo; la rampa de acceso de más abajo está construida precisamente para ti, y la
única regla que más importa ("nunca bloquees") se destaca en su propio recuadro para
que no se te pase.

Una nota sobre la profundidad antes de empezar. El modelo reactivo es la única idea
sobre la que se sostiene todo lo demás, así que este preludio lo presenta justo lo
suficiente para leer los primeros capítulos, y el Capítulo 5 lo enseña entonces como
es debido, operador a operador. Te encontrarás con `Mono` y `Flux` tres veces, con
profundidad creciente — aquí, de pasada; en el Capítulo 1, como término clave; y en
el Capítulo 5, en su totalidad. Esa repetición es deliberada: cada pasada da por
supuesto un poco más que la anterior, de modo que para el Capítulo 5 el vocabulario
ya resulta familiar y el capítulo puede gastar su presupuesto en las partes difíciles
(planificadores, contrapresión, propagación de contexto) en lugar de en volver a
presentar los tipos.

Una tranquilidad más por adelantado. El proyecto de acompañamiento sobre el que se
construye este libro — el reactor **Lumen Lending** que hay bajo
`samples/lumen-lending` — se ejecuta de principio a fin en tu portátil **sin Docker,
sin base de datos externa y sin broker de mensajes**. La capa de datos es H2 en
memoria, y los eventos viajan sobre un transporte dentro de la JVM. Así que cada
fragmento que estás a punto de leer en abstracto se convierte en un servicio que
realmente puedes arrancar uno o dos capítulos más adelante, con un único
`mvn spring-boot:run`. Tenlo presente mientras lees: nada de esto es teoría que
tengas que aceptar a ciegas.

## Maven y el POM

Firefly se construye y se consume con **Maven**. Un proyecto Maven se describe
mediante un `pom.xml` — el Project Object Model — que declara las coordenadas del
proyecto (group, artifact, version), sus dependencias y cómo se construye. Rara vez
ejecutas `javac` tú mismo; ejecutas `mvn verify`, y Maven compila, ejecuta las
pruebas y empaqueta un JAR ejecutable.

Dos ideas de Maven importan a lo largo de todo este libro. Primera, la **gestión de
dependencias**: en lugar de fijar una versión en cada dependencia, un proyecto
hereda un POM *padre* o importa un *BOM* (Bill of Materials) que fija las versiones
de forma centralizada, de modo que tus propias entradas `<dependency>` pueden omitir
`<version>` y aun así estar de acuerdo. Segunda, los **starters**: un starter es una
dependencia curada que arrastra todo lo necesario para una capacidad. Añadir
`spring-boot-starter-webflux` trae la pila web reactiva — servidor, JSON, validación
— en una sola línea.

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>
```

Fíjate en lo que *falta*: ninguna `<version>`. La versión la resuelve por ti un
padre heredado — para un proyecto Spring Boot, `spring-boot-starter-parent`; para un
proyecto Firefly, el propio padre de Firefly, que a su vez importa el BOM de Spring
Boot más el de Firefly. Por eso toda una flota de servicios puede ponerse de acuerdo
sobre un único conjunto de versiones de bibliotecas sin que nadie tenga que editar
cien POM: las versiones viven en un solo lugar, aguas arriba, y cada servicio las
hereda.

El Capítulo 3 está dedicado a cómo el POM padre y el BOM de Firefly hacen que una
flota entera de servicios sea coherente en versiones. Por ahora, basta con que
retengas la forma: *hereda un padre, añade un starter, omite las versiones.* Verás
esa misma forma dar sus frutos en el Capítulo 2, donde el `pom.xml` del servicio de
inicio rápido lista dependencias de Firefly sin ningún número de versión y aun así
construye.

!!! note "Si vienes de PyFly o Python"
    El `pom.xml` es el equivalente aproximado de `pyproject.toml`; un *starter* de
    Maven es como un grupo de extras que instala un conjunto coherente de paquetes;
    el padre/BOM es la historia del bloqueo de dependencias (tu `poetry.lock` o
    `requirements.txt`, pero resuelta por herencia en lugar de por un lockfile).
    `mvn verify` es tu `pytest` más una build — compilar, probar y empaquetar en un
    solo comando.

## Spring Boot de un respiro

**Spring Boot** convierte "ensamblar un servicio Java a partir de piezas" en "añade
un starter y ejecuta". Una aplicación Spring Boot es una clase Java corriente con una
anotación y un método `main`:

```java
@SpringBootApplication
public class LumenApplication {
    public static void main(String[] args) {
        SpringApplication.run(LumenApplication.class, args);
    }
}
```

`SpringApplication.run(...)` arranca el **contexto de aplicación** — el contenedor de
objetos de Spring —, inicia un servidor web embebido y conecta todo entre sí. Tres
mecanismos hacen casi todo el trabajo:

- **Autoconfiguración.** Spring Boot inspecciona el classpath y configura valores
  por defecto sensatos: si ve WebFlux en el classpath, obtienes un servidor web
  reactivo; si ve R2DBC, obtienes una `ConnectionFactory` reactiva. Toda
  autoconfiguración se retira en el momento en que defines tu propio bean, así que
  los valores por defecto son un punto de partida, nunca una jaula.
- **Configuración externalizada.** Los ajustes viven en `application.yml` (o
  `application.properties`), estratificados por *perfil* (`dev`, `prod`, …) y
  anulables mediante variables de entorno — de modo que el mismo JAR se ejecuta en
  cualquier sitio.
- **Actuator.** Un conjunto de endpoints de producción — salud, métricas, info — que
  obtienes añadiendo una dependencia.

Vale la pena ser preciso sobre *cuándo* sucede esto, porque explica el punto de
entrada vacío que verás una y otra vez. `@SpringBootApplication` agrupa
`@EnableAutoConfiguration`, y al arrancar Spring lee las entradas de
autoconfiguración que cada dependencia envía en `META-INF`, evalúa las condiciones
de cada una contra tu classpath y tus beans, y aplica solo las que encajan. Así que
"añadir una dependencia" y "activar un comportamiento" son el mismo acto — una
propiedad de Spring Boot en la que Firefly se apoya con fuerza. Lo verás en marcha,
línea a línea, en el log de arranque del Capítulo 2.

Vinculas la configuración a objetos tipados con `@ConfigurationProperties`, de modo
que los ajustes se leen una vez en un objeto inmutable en lugar de pescarse de un
mapa por clave de cadena:

```java
@ConfigurationProperties(prefix = "lumen.lending")
public record LendingProperties(int maxTermMonths, String defaultCurrency) {}
```

```yaml
lumen:
  lending:
    max-term-months: 84
    default-currency: EUR
```

_(Ese record es un esbozo ilustrativo del patrón; el reactor de acompañamiento usa
el mismo mecanismo `@ConfigurationProperties` para sus ajustes reales — por ejemplo,
la capa de experiencia vincula de este modo la URL base y los tiempos de espera de su
cliente aguas abajo.)_

Firefly es, en el fondo, *más Spring Boot* — más autoconfiguración, más starters, más
convenciones —, y el Capítulo 1 explica exactamente qué añade y por qué.

!!! spring "Si vienes de Spring MVC"
    Todo lo anterior es idéntico a lo que conoces — `@SpringBootApplication`,
    `application.yml`, perfiles, Actuator, `@ConfigurationProperties`: todo se
    traslada sin cambios. Lo único que difiere es la pila web y de datos que hay
    debajo, que es *reactiva* en lugar de basada en servlets. Esa diferencia es el
    tema de las cuatro secciones siguientes.

## Beans e inyección de dependencias

Spring construye tus objetos por ti y les entrega sus colaboradores. Una clase
anotada como un componente — `@Component`, o los más específicos `@Service`,
`@Repository`, `@RestController` — se convierte en un **bean** gestionado por el
contexto de aplicación. Declaras lo que un bean necesita como parámetros del
constructor, y Spring *inyecta* los beans que coinciden:

```java
@Service
public class LoanApplicationService {
    private final LoanApplicationRepository repository;

    public LoanApplicationService(LoanApplicationRepository repository) {
        this.repository = repository;   // injected by Spring
    }
}
```

Prefiere la inyección por constructor (mostrada aquí) frente a la inyección por
campo: hace explícitas las dependencias, mantiene los campos `final` y hace que la
clase sea trivial de probar unitariamente pasando dobles directamente — sin
necesidad de un contexto de Spring. En el reactor de acompañamiento verás esto a
menudo escrito de forma aún más escueta con la anotación `@RequiredArgsConstructor`
de Lombok, que genera exactamente el constructor de arriba a partir de los campos
`final`; es la misma idea de inyección por constructor con el código repetitivo
eliminado. A lo largo de este libro, los propios estereotipos de Firefly —
`@CommandHandlerComponent`, `@QueryHandlerComponent` y compañía — son simplemente
componentes de Spring especializados, descubiertos e inyectados de la misma manera.

!!! note "Si vienes de Spring MVC"
    Los beans, el contexto de aplicación y la inyección por constructor no cambian
    en el mundo reactivo. Un `@Service` es un `@Service`. Solo cambia lo que fluye *a
    través* de los beans — de valores bloqueantes a publicadores reactivos.

## El Java moderno que usa el libro

Los listados usan unas cuantas características de Java moderno sin ceremonias.
Ninguna es exótica; si has escrito Java 17 o posterior, las has usado todas:

- **Records** — portadores de datos concisos e inmutables.
  `record Money(long minorUnits) {}` genera el constructor, los accesores, `equals`,
  `hashCode` y `toString`. Los objetos de valor y los DTO de petición/respuesta son
  records en todo el libro. (El `Money` del reactor es exactamente esta idea —
  dinero mantenido como unidades menores enteras para esquivar el redondeo de punto
  flotante.)
- **Tipos sellados** — un conjunto cerrado de subtipos, ideal para modelar un
  conjunto fijo de estados o eventos que el compilador puede comprobar de forma
  exhaustiva en un `switch`.
- **Enums con comportamiento** — un conjunto fijo de valores con nombre que pueden
  llevar métodos. El `ApplicationStatus` del reactor (`DRAFT`, `SUBMITTED`,
  `UNDER_REVIEW`, `APPROVED`, `REJECTED`, `CANCELLED`) es uno, con un pequeño
  ayudante para preguntar si un estado es terminal.
- **`Optional<T>`** — un "quizá un valor" explícito en lugar de un `null` pelado.
- **Lambdas y referencias a métodos** — pasadas a los operadores reactivos que verás
  a continuación, p. ej. `.map(this::toResponse)`.

## De lo bloqueante a lo reactivo

La E/S clásica de Java es **bloqueante**: un hilo que llama a una base de datos o a
otro servicio *espera*, sin hacer nada, hasta que vuelve la respuesta. Bajo carga,
eso significa un hilo aparcado por cada petición en vuelo, y los hilos son caros.
`CompletableFuture` suavizó esto al permitirte describir trabajo que se completa *más
tarde*:

```java
CompletableFuture<Account> future = loadAccountAsync(id);
future.thenApply(Account::balance)
      .thenAccept(balance -> log.info("balance = {}", balance));
```

Fíjate en la forma: no *obtienes* el valor, *describes qué hacer cuando llegue*. La
programación reactiva generaliza exactamente esta idea — de "un valor, más tarde" a
"cero, uno o muchos valores, más tarde, con contrapresión y cancelación". La
*contrapresión* es la pieza que falta y a la que un `CompletableFuture` pelado no
tiene respuesta: cuando un productor rápido adelanta a un consumidor lento, un flujo
reactivo permite que el consumidor señale cuánto puede aceptar, de modo que la
memoria no se dispare. Toda esa generalización es **Project Reactor**, y sus dos
tipos, `Mono` y `Flux`, son el vocabulario de cada servicio Firefly.

!!! warning "La única regla del código reactivo: nunca bloquees"
    En la pila reactiva, un pequeño pool de hilos de bucle de eventos sirve *todas*
    las peticiones. Si llamas a una API bloqueante (una consulta JDBC,
    `Thread.sleep`, `.block()`) en uno de esos hilos, paralizas todas las peticiones
    que estaba sirviendo — un puñado de llamadas bloqueantes puede congelar el
    servicio entero. Todo el propósito de los capítulos que siguen es mantenerse no
    bloqueante de principio a fin, que es exactamente por qué la capa de datos es
    R2DBC y no JDBC, y por qué casi nunca verás `.block()` en este libro.

## Spring WebFlux

**Spring WebFlux** es la contraparte reactiva de Spring MVC. Un controlador tiene un
aspecto casi idéntico — pero en lugar de devolver un valor, devuelve un *publicador*
de ese valor:

```java
@RestController
@RequestMapping("/api/v1/applications")
public class LoanApplicationController {

    private final LoanApplicationService service;

    public LoanApplicationController(LoanApplicationService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public Mono<LoanApplicationDto> byId(@PathVariable UUID id) {
        return service.findById(id);   // a Mono, not a value
    }
}
```

_Este es un esbozo deliberadamente simplificado para mostrar la forma. El
`LoanApplicationController` real que conocerás en el Capítulo 6 vive en
`/api/v1/loan-applications`, devuelve un `Mono<LoanApplicationResponse>` para una
única solicitud y un `Flux<LoanApplicationResponse>` para la lista, y se ejecuta en
la capa core en el puerto `8081` — arrancado con `mvn spring-boot:run` y sin Docker.
Misma forma, nombres reales._

Devolver un `Mono<LoanApplicationDto>` en lugar de un `LoanApplicationDto` le dice al
framework: *aquí tienes una receta para una respuesta; suscríbete a ella, y cuando
llegue el valor, escríbelo* — sin aparcar un hilo entretanto. Un endpoint de
colección devuelve `Flux<T>` (de cero a muchos). WebFlux se ejecuta sobre el bucle de
eventos de Netty por defecto; en el log de arranque del Capítulo 2 verás la línea
exacta — `Netty started on port 8081` — que demuestra que lo que se levantó es el
servidor reactivo, no un contenedor de servlets.

!!! spring "Si vienes de Spring MVC"
    Las anotaciones (`@RestController`, `@RequestMapping`, `@GetMapping`,
    `@PostMapping`, `@PathVariable`, `@RequestBody`, `@Valid`) son todas iguales. El
    cambio es el tipo de retorno: `T` se convierte en `Mono<T>`, `List<T>` se
    convierte en `Flux<T>`, y nunca debes bloquear dentro del manejador. Si has usado
    los tipos de retorno `DeferredResult` o `CompletableFuture` en MVC, esto es esa
    idea llevada hasta el final — y el contenedor de servlets (Tomcat) queda
    reemplazado por Netty.

## Project Reactor: Mono y Flux de un vistazo

`Mono<T>` es un publicador de **como mucho un** elemento (piensa: una única
respuesta, o nada). `Flux<T>` es un publicador de **cero a muchos** elementos
(piensa: un flujo de filas o eventos). Ambos son **perezosos**: no ocurre nada hasta
que algo se *suscribe*. Esa pereza es la fuente de la mayoría de las sorpresas
reactivas para los recién llegados — un `Mono` que construyes pero nunca devuelves (y
al que, por tanto, nunca te suscribes) simplemente no hace ningún trabajo. En un
servicio Firefly el framework se suscribe por ti cuando escribe la respuesta HTTP,
así que casi nunca llamas a `.subscribe()` tú mismo — *compones* un pipeline y lo
devuelves, y el borde del framework lo conduce.

Transformas valores reactivos con operadores que reflejan la API `Stream`:

```java
Mono<String> name =
    repository.findById(id)                // Mono<LoanApplication>
        .map(LoanApplication::applicant)   // Mono<Applicant>
        .map(Applicant::fullName)          // Mono<String>
        .defaultIfEmpty("unknown");
```

Una orientación rápida sobre los operadores que verás más a menudo:

- `.map(fn)` — transforma cada elemento de forma síncrona (entra un valor, sale un
  valor).
- `.flatMap(fn)` — transforma cada elemento en *otro publicador* y aplana el
  resultado; así es como encadenas una llamada reactiva tras otra (llama al
  repositorio y luego llama a un servicio aguas abajo con el resultado).
- `.filter(pred)` — descarta los elementos que no superan un predicado.
- `.defaultIfEmpty(x)` / `.switchIfEmpty(pub)` — proporciona un valor de reserva
  cuando un `Mono` se completa vacío (la forma reactiva de manejar "no encontrado").

Con eso basta para leer los primeros capítulos: un método devuelve un `Mono` o
`Flux`, y encadenas `.map`, `.flatMap`, `.filter` y compañía para describir el
resultado. La regla práctica es "`map` para valores planos, `flatMap` cuando el
siguiente paso es a su vez reactivo". El Capítulo 5 — la piedra angular reactiva —
enseña el modelo como es debido: publicadores fríos frente a calientes, operadores de
error y reintento, planificadores, contrapresión, y cómo un identificador de
correlación sobrevive a través de las fronteras entre operadores (un problema que
Firefly resuelve por ti, y una fuente frecuente de errores en el código reactivo
hecho a mano).

!!! note "Si vienes de PyFly o Python"
    `Mono<T>` es el primo espiritual de una `async def` que devuelve un valor, y
    `Flux<T>` el de un generador asíncrono — pero reactivo, perezoso y con
    contrapresión y cancelación de primera clase. Donde harías `await`, aquí haces
    `.map`/`.flatMap` y devuelves el publicador para que el framework haga el await
    en el borde. El mayor cambio mental: una `async def` se ejecuta cuando se le hace
    await, pero un `Mono` se ejecuta solo cuando se le *suscribe*, y en un controlador
    es el framework — no tu código — quien se suscribe.

## R2DBC: acceso reactivo a datos

Si la capa web no es bloqueante, la capa de datos tampoco puede serlo — de lo
contrario, una llamada bloqueante a la base de datos paraliza el bucle de eventos.
**R2DBC** (Reactive Relational Database Connectivity) es la respuesta reactiva a
JDBC. Con Spring Data R2DBC, un repositorio devuelve publicadores:

```java
public interface LoanApplicationRepository
        extends ReactiveCrudRepository<LoanApplication, UUID> {
    Flux<LoanApplication> findByStatus(ApplicationStatus status);
}
```

`findById` devuelve `Mono<LoanApplication>`; `findAll` y las consultas derivadas como
`findByStatus` devuelven `Flux<LoanApplication>`. Spring Data sigue implementando la
consulta *a partir del nombre del método* — `findByStatus` se convierte en un
`WHERE status = ?` — exactamente igual que en el mundo bloqueante; solo cambió el
tipo de retorno. Este esbozo es, de hecho, casi el repositorio real que conocerás en
el Capítulo 8, que extiende el mismo `ReactiveCrudRepository<LoanApplication, UUID>`
y añade una consulta derivada más (`findByApplicationNumber`).

El esquema se gestiona con migraciones de **Flyway** en lugar de autogenerarse, y
aquí hay un detalle que importa para el proyecto de acompañamiento: Flyway se ejecuta
sobre JDBC mientras que el acceso a datos en tiempo de ejecución se ejecuta sobre
R2DBC, ambos apuntando a la *misma* base de datos H2 en memoria. Así es como una
porción puede servir peticiones reales y persistidas en tu portátil sin ninguna base
de datos que instalar y sin Docker — Flyway crea la tabla en el arranque, y R2DBC la
lee y escribe durante toda la vida de la JVM. El Capítulo 8 construye así la capa de
persistencia real de Lumen Lending.

!!! warning "JPA/Hibernate es bloqueante — y no se usa aquí"
    Spring Data JPA, JDBC e Hibernate son todos bloqueantes y no tienen lugar en la
    pila reactiva. Este libro usa R2DBC en todo momento. Si tu instinto es echar mano
    de `@Entity` y un `EntityManager`, ese instinto pertenece al mundo de los
    servlets; los equivalentes reactivos son las entidades de Spring Data R2DBC
    (`@Table`, `@Id`, `@Column`) y los repositorios reactivos que devuelven
    `Mono`/`Flux`.

!!! note "Si vienes de PyFly o Python"
    R2DBC es el análogo más cercano a un driver de BD asíncrono detrás de un ORM
    asíncrono — `asyncpg`/`databases` emparejado con una sesión asíncrona de
    SQLAlchemy. La forma es la misma: las consultas devuelven awaitables que compones
    en lugar de valores sobre los que bloqueas. Flyway hace el papel de Alembic —
    migraciones versionadas, solo hacia adelante, registradas en el repositorio.

## Dónde te deja esto

Ahora tienes el vocabulario de trabajo: Maven y starters; la autoconfiguración, la
configuración externalizada y los beans de Spring Boot; el giro reactivo de lo
bloqueante a `Mono`/`Flux`; los controladores WebFlux; y los repositorios R2DBC
respaldados por Flyway. Esa es la plataforma sobre la que se construye Firefly y —lo
que es crucial— es una plataforma que puedes ejecutar localmente en minutos, porque
el reactor de acompañamiento no necesita nada más que un JDK y Maven.

La siguiente pregunta es la que responde todo este libro: si Spring Boot ya te da
todo esto, *¿por qué construir un metaframework encima?* El Capítulo 1 expone los
argumentos; el Capítulo 2 te hace entonces arrancar un servicio Firefly real y ver
cómo cada pieza de este preludio aparece en el log.
