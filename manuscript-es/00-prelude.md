Los capítulos que vienen dan por hecho que puedes leer Spring Boot moderno con
soltura. Este preludio se asegura de que así sea, aunque tu último contacto con
Spring fuese con MVC y JPA, o aunque llegues desde otro port de Firefly como
PyFly. **No** es un curso de Spring. Te enseña exactamente la porción de Spring
Boot, WebFlux, Project Reactor y R2DBC en la que se apoyan los capítulos
posteriores, más o menos en el orden en que se apoyan en ella. Cada sección
termina con una breve nota *Si vienes de…* que traduce la idea desde el lugar en
el que quizá te encuentres hoy.

Si ya despliegas Spring Boot reactivo a diario —controladores WebFlux,
repositorios R2DBC, `Mono`/`Flux` con los ojos cerrados—, salta directamente al
Capítulo 1. Nada de lo que hay aquí te va a sorprender.

Una nota sobre la profundidad: el modelo reactivo es la única idea sobre la que se
sostiene todo lo demás, así que este preludio lo introduce solo lo justo para
leer los primeros capítulos, y el Capítulo 5 lo enseña después como es debido,
operador a operador. Te encontrarás con `Mono` y `Flux` tres veces, con
profundidad creciente: aquí, de pasada; en el Capítulo 1, como término clave; y
en el Capítulo 5, por completo. Esa repetición es deliberada.

## Maven y el POM

Firefly se construye y se consume con **Maven**. Un proyecto Maven se describe
mediante un `pom.xml` —el Project Object Model—, que declara las coordenadas del
proyecto (grupo, artefacto, versión), sus dependencias y cómo se construye. Rara
vez ejecutas `javac` tú mismo; ejecutas `mvn verify`, y Maven compila, ejecuta
las pruebas y empaqueta un JAR ejecutable.

Dos ideas de Maven son importantes a lo largo de todo este libro. Primera, la
**gestión de dependencias**: en lugar de fijar una versión en cada dependencia,
un proyecto hereda un POM *padre* o importa un *BOM* (Bill of Materials) que fija
las versiones de forma centralizada, de modo que tus propias entradas
`<dependency>` pueden omitir `<version>` y aun así estar de acuerdo. Segunda, los
**starters**: un starter es una dependencia curada que arrastra todo lo necesario
para una capacidad. Añadir `spring-boot-starter-webflux` trae la pila web
reactiva —servidor, JSON, validación— en una sola línea.

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>
```

El Capítulo 3 está dedicado a cómo el POM padre y el BOM de Firefly hacen que una
flota entera de servicios sea coherente en versiones. Por ahora, quédate solo con
la forma: *hereda un padre, añade un starter, omite las versiones.*

!!! note "Si vienes de PyFly o Python"
    El `pom.xml` es el equivalente aproximado de `pyproject.toml`; un *starter* de
    Maven es como un grupo de extras que instala un conjunto coherente de
    paquetes; el padre/BOM es la historia del bloqueo de dependencias. `mvn verify`
    es tu `pytest` más una compilación.

## Spring Boot en una frase

**Spring Boot** convierte "ensambla un servicio Java a partir de piezas" en
"añade un starter y ejecuta". Una aplicación Spring Boot es una clase Java
corriente con una sola anotación y un método `main`:

```java
@SpringBootApplication
public class LumenApplication {
    public static void main(String[] args) {
        SpringApplication.run(LumenApplication.class, args);
    }
}
```

`SpringApplication.run(...)` arranca el **contexto de aplicación** —el contenedor
de objetos de Spring—, levanta un servidor web embebido y conecta todo entre sí.
Tres mecanismos hacen casi todo el trabajo:

- **Autoconfiguración.** Spring Boot inspecciona el classpath y configura valores
  por defecto sensatos: si ve WebFlux en el classpath, obtienes un servidor web
  reactivo; si ve R2DBC, obtienes un `ConnectionFactory` reactivo. Toda
  autoconfiguración se aparta en el momento en que defines tu propio bean, así que
  los valores por defecto son un punto de partida, nunca una jaula.
- **Configuración externalizada.** Los ajustes viven en `application.yml` (o
  `application.properties`), estratificados por *perfil* (`dev`, `prod`, …) y
  sobrescribibles mediante variables de entorno, de modo que el mismo JAR se
  ejecuta en todas partes.
- **Actuator.** Un conjunto de endpoints de producción —salud, métricas, info— que
  obtienes con solo añadir una dependencia.

Vinculas la configuración a objetos tipados con `@ConfigurationProperties`:

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

Firefly es, en el fondo, *más Spring Boot* —más autoconfiguración, más starters,
más convenciones— y el Capítulo 1 explica exactamente qué añade y por qué.

!!! spring "Si vienes de Spring MVC"
    Todo lo anterior es idéntico a lo que ya conoces: `@SpringBootApplication`,
    `application.yml`, Actuator y `@ConfigurationProperties` se trasladan sin
    cambios. Lo único que difiere es la pila web y de datos que hay debajo, que es
    *reactiva* en lugar de basada en servlets. Esa diferencia es el tema de las
    tres secciones siguientes.

## Beans e inyección de dependencias

Spring construye tus objetos por ti y les entrega sus colaboradores. Una clase
anotada como componente —`@Component`, o las más específicas `@Service`,
`@Repository`, `@RestController`— se convierte en un **bean** gestionado por el
contexto de aplicación. Declaras lo que un bean necesita como parámetros del
constructor, y Spring *inyecta* los beans correspondientes:

```java
@Service
public class LoanApplicationService {
    private final LoanApplicationRepository repository;

    public LoanApplicationService(LoanApplicationRepository repository) {
        this.repository = repository;   // injected by Spring
    }
}
```

Prefiere la inyección por constructor (la que se muestra aquí) frente a la
inyección por campo: hace explícitas las dependencias, mantiene los campos
`final` y vuelve trivial probar la clase de forma unitaria pasándole dobles
directamente. A lo largo de este libro, los propios estereotipos de Firefly
—`@CommandHandlerComponent`, `@QueryHandlerComponent` y compañía— no son más que
componentes especializados de Spring descubiertos de la misma manera.

!!! note "Si vienes de Spring MVC"
    Los beans, el contexto de aplicación y la inyección por constructor no cambian
    en el mundo reactivo. Un `@Service` es un `@Service`. Solo cambia lo que fluye
    *a través de* los beans: de valores bloqueantes a publicadores reactivos.

## El Java moderno que usa el libro

Los listados emplean unas cuantas características de Java moderno sin ceremonias:

- **Records** — portadores de datos concisos e inmutables.
  `record Money(long minorUnits) {}` genera el constructor, los accesores,
  `equals`, `hashCode` y `toString`. Los objetos de valor y los DTO son records en
  todo el libro.
- **Tipos sellados** — un conjunto cerrado de subtipos, ideal para modelar un
  conjunto fijo de estados o eventos que el compilador puede comprobar de forma
  exhaustiva en un `switch`.
- **`Optional<T>`** — un "quizá un valor" explícito en lugar de un `null` pelado.
- **Lambdas y referencias a métodos** — que se pasan a los operadores reactivos
  que conocerás a continuación, p. ej. `.map(this::toDto)`.

Ninguna de estas es exótica; si has escrito Java 17 o posterior, ya las has usado.

## De lo bloqueante a lo reactivo

La E/S clásica de Java es **bloqueante**: un hilo que llama a una base de datos o
a otro servicio *espera*, sin hacer nada, hasta que vuelve la respuesta. Bajo
carga, eso significa un hilo aparcado por cada petición en curso, y los hilos son
caros. `CompletableFuture` suavizó esto al permitirte describir trabajo que se
completa *más tarde*:

```java
CompletableFuture<Account> future = loadAccountAsync(id);
future.thenApply(Account::balance)
      .thenAccept(balance -> log.info("balance = {}", balance));
```

Fíjate en la forma: no *obtienes* el valor, *describes qué hacer cuando llegue*.
La programación reactiva generaliza exactamente esta idea: de "un valor, más
tarde" a "cero, uno o muchos valores, más tarde, con contrapresión y
cancelación". Esa generalización es **Project Reactor**, y sus dos tipos, `Mono` y
`Flux`, son el vocabulario de todo servicio Firefly.

!!! warning "La única regla del código reactivo: nunca bloquees"
    En la pila reactiva, un pequeño pool de hilos de bucle de eventos sirve
    *todas* las peticiones. Si llamas a una API bloqueante (una consulta JDBC,
    `Thread.sleep`, `.block()`) en uno de esos hilos, paralizas todas las
    peticiones que estuviera sirviendo. Todo el sentido de los capítulos que vienen
    es permanecer no bloqueante de extremo a extremo, que es exactamente la razón
    por la que la capa de datos es R2DBC y no JDBC.

## Spring WebFlux

**Spring WebFlux** es la contraparte reactiva de Spring MVC. Un controlador tiene
un aspecto casi idéntico, pero en lugar de devolver un valor, devuelve un
*publicador* de ese valor:

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

Devolver un `Mono<LoanApplicationDto>` en lugar de un `LoanApplicationDto` le dice
al framework: *aquí tienes una receta para una respuesta; suscríbete a ella y,
cuando el valor llegue, escríbelo*, sin aparcar un hilo mientras tanto. Un
endpoint de colección devuelve `Flux<T>` (de cero a muchos). WebFlux se ejecuta
por defecto sobre el bucle de eventos de Netty.

!!! spring "Si vienes de Spring MVC"
    Las anotaciones (`@RestController`, `@GetMapping`, `@PathVariable`,
    `@RequestBody`) son las mismas. El cambio está en el tipo de retorno: `T` pasa
    a ser `Mono<T>`, `List<T>` pasa a ser `Flux<T>`, y nunca debes bloquear dentro
    del manejador. Si has usado los tipos de retorno `DeferredResult` o
    `CompletableFuture` en MVC, esto es esa misma idea llevada hasta el fondo.

## Project Reactor: Mono y Flux de un vistazo

`Mono<T>` es un publicador de **como mucho un** elemento (piensa: una única
respuesta, o nada). `Flux<T>` es un publicador de **cero a muchos** elementos
(piensa: un flujo de filas o de eventos). Ambos son **perezosos**: no ocurre nada
hasta que algo *se suscribe*. En un servicio Firefly, el framework se suscribe por
ti cuando escribe la respuesta HTTP, así que casi nunca llamas tú a `.subscribe()`:
lo que haces es *componer*.

Transformas valores reactivos con operadores que reflejan la API de `Stream`:

```java
Mono<String> name =
    repository.findById(id)                // Mono<LoanApplication>
        .map(LoanApplication::applicant)   // Mono<Applicant>
        .map(Applicant::fullName)          // Mono<String>
        .defaultIfEmpty("unknown");
```

Con eso basta para leer los primeros capítulos: un método devuelve un `Mono` o un
`Flux`, y encadenas `.map`, `.flatMap`, `.filter` y compañía para describir el
resultado. El Capítulo 5 —la clave de bóveda reactiva— enseña el modelo como es
debido: publicadores fríos frente a calientes, operadores de error y de reintento,
schedulers, contrapresión, y cómo un ID de correlación sobrevive a través de las
fronteras entre operadores (un problema que Firefly resuelve por ti, y una fuente
frecuente de bugs en el código reactivo escrito a mano).

!!! note "Si vienes de PyFly o Python"
    `Mono<T>` es el primo espiritual de un `async def` que devuelve un valor, y
    `Flux<T>` el de un generador asíncrono, pero reactivos, perezosos y con
    contrapresión y cancelación de primera clase. Allí donde harías `await`, aquí
    haces `.map`/`.flatMap` y devuelves el publicador para que el framework espere
    en el borde.

## R2DBC: acceso a datos reactivo

Si la capa web es no bloqueante, la capa de datos también debe serlo; de lo
contrario, una llamada bloqueante a la base de datos paraliza el bucle de eventos.
**R2DBC** (Reactive Relational Database Connectivity) es la respuesta reactiva a
JDBC. Con Spring Data R2DBC, un repositorio devuelve publicadores:

```java
public interface LoanApplicationRepository
        extends ReactiveCrudRepository<LoanApplication, UUID> {
    Flux<LoanApplication> findByStatus(ApplicationStatus status);
}
```

`findById` devuelve `Mono<LoanApplication>`; `findAll` y las consultas derivadas
devuelven `Flux<LoanApplication>`. El esquema se gestiona con migraciones de
Flyway, y el Capítulo 8 construye así la capa de persistencia real de Lumen
Lending.

!!! warning "JPA/Hibernate es bloqueante, y no se usa aquí"
    Spring Data JPA, JDBC y Hibernate son todos bloqueantes y no tienen lugar en la
    pila reactiva. Este libro usa R2DBC en todo momento. Si tu instinto es echar
    mano de `@Entity` y de un `EntityManager`, ese instinto pertenece al mundo de
    los servlets; los equivalentes reactivos son las entidades R2DBC y los
    repositorios reactivos.

## Dónde te deja esto

Ya tienes el vocabulario de trabajo: Maven y los starters; la autoconfiguración,
la configuración y los beans de Spring Boot; el salto reactivo de lo bloqueante a
`Mono`/`Flux`; los controladores WebFlux; y los repositorios R2DBC. Esa es la
plataforma sobre la que se construye Firefly.

La siguiente pregunta es justamente la que responde todo este libro: si Spring
Boot ya te da todo esto, *¿por qué construir un metaframework encima de él?* El
Capítulo 1 lo argumenta.
