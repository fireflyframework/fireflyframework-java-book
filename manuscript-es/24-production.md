Hace veintitrés capítulos Lumen Lending era una carpeta vacía. Ahora es una flota:
una capa de experiencia que da forma a las peticiones de los canales, una capa de
dominio que orquesta una saga y emite eventos, y una capa core que posee el esquema y
sirve problem details RFC 7807 — todo reactivo, todo coherente en versiones, todo
construido a partir de starters de capa que añadiste en una sola línea. Has estado
*consumiendo* Firefly durante todo el camino. Este capítulo de cierre gira la lente
dos veces: primero para mostrar cómo *extiendes* el framework con tu propia
capacidad, y después cómo llevas cualquiera de estos servicios a producción como una
imagen nativa con una lista de materiales firmada.

Las dos mitades riman. Extender Firefly significa escribir el mismo tipo de
autoconfiguración conmutable y amigable con las sustituciones que el framework escribe
para sí mismo — de modo que tu código se comporte como una capacidad de primera clase,
no como un añadido. Ir a producción significa apoyarse en el *POM padre* que conociste
en el Capítulo 3, que ya lleva un perfil nativo de GraalVM y un paso de SBOM CycloneDX,
de modo que toda la flota se despliega de la misma manera. Ninguna de las dos mitades
añade un nuevo test compañero; este capítulo es un recorrido guiado por patrones y
comandos de build que, de hecho, has estado pisando desde el Capítulo 3. Allí donde
aparece un listado, sigue siendo una porción literal del reactor de Lumen — la costura
de cliente de la capa de experiencia resulta ser el ejemplo más limpio de cada patrón
de extensión a la vez.

Al final sabrás cómo añadir una capacidad al estilo Firefly, ocultar un proveedor
detrás de un port, empaquetar el resultado como un starter y convertir `mvn -Pnative`
en un contenedor que arranca en milisegundos. Después, una breve palabra sobre lo que
vive *más allá* de este libro, y una mirada atrás sobre todo el recorrido.

## Extender Firefly es escribir autoconfiguración de Spring Boot

He aquí la verdad tranquilizadora hacia la que ha apuntado todo el libro: no hay una
API secreta de extensión de Firefly. Una capacidad de Firefly *es* una autoconfiguración
de Spring Boot — una clase `@Configuration`, cerrada por condiciones, registrada de modo
que Spring Boot la encuentre en el classpath, que se retira en el instante en que defines
tu propio bean. Todo lo que aprendiste sobre `@ConditionalOnProperty` y
`@ConditionalOnMissingBean` en la lente del «superconjunto, nunca un fork» del Capítulo 1
es el mecanismo de extensión. Para añadir una capacidad a la flota, escribes exactamente
lo que el framework escribe para sí mismo.

Una capacidad tiene tres piezas móviles, y el resto de esta sección recorre cada una:

1. Un **port** — una interfaz de la que depende el código de tu aplicación, nunca un SDK
   de proveedor.
2. Un **adaptador** — una implementación de ese port, más una **`@Configuration`**
   que lo aporta como bean, cerrada de modo que se active por propiedad y ceda ante
   cualquier bean que definas.
3. Un **registro** — un fichero de import en `META-INF` para que Spring Boot descubra la
   configuración sin un `@Import` explícito, de la misma manera que se descubre cada
   capacidad del framework.

La capa de experiencia de Lumen ya demuestra las tres para su costura con el SDK de
dominio. Es el cableado de producción que el BFF usa para llegar al servicio de dominio,
y está construido precisamente como se construye una capacidad del framework — así que
leerlo es leer el patrón de extensión.

!!! note "Término clave — autoconfiguración"
    Una **autoconfiguración de Spring Boot** es una clase `@Configuration` que Spring
    Boot aplica automáticamente cuando está en el classpath y sus condiciones pasan —
    sin que la aplicación la importe explícitamente. Boot encuentra candidatas leyendo
    un fichero de registro (consulta *registro* más abajo) y luego evalúa las guardas
    `@Conditional...` de cada una. Las ~70 capacidades de Firefly son todas
    autoconfiguraciones; la que escribes para extender la flota no es diferente.

## Paso 1 — Depende de un port, nunca de un proveedor

El primer movimiento es el que el Capítulo 1 llamó «proveedores detrás de ports» y que
el Apéndice B recopiló por completo: el código de tu aplicación habla con una *interfaz*,
y el proveedor concreto nunca aparece en un manejador o servicio. El contrato de la capa
de experiencia con el servicio de dominio es un port reactivo hecho a mano — dos métodos,
ambos devolviendo `Mono`, sin `WebClient`, sin tipo de SDK, sin HTTP en ninguna parte de
la firma:

::: listing exp-lending/src/main/java/com/firefly/lumen/exp/client/LoanOriginationDomainClient.java | Listado 24.1 — el port: una interfaz de la que depende tu código, libre de proveedor
public interface LoanOriginationDomainClient {

    /**
     * Submits a new loan application to the domain origination service.
     *
     * @param request        the channel-shaped create request, already validated by the BFF
     * @param idempotencyKey deterministic key so retries of the same logical request dedupe
     * @return the created application's detail view
     */
    Mono<ApplicationDetailDTO> submitApplication(CreateApplicationRequest request, String idempotencyKey);

    /**
     * Fetches a single loan application by its identifier.
     *
     * @param applicationId  the application's server-assigned identifier
     * @param idempotencyKey deterministic key for safe read retries
     * @return the application's detail view, or an empty {@link Mono} if it does not exist
     */
    Mono<ApplicationDetailDTO> getApplication(UUID applicationId, String idempotencyKey);
}
:::

Esta es la costura. El `ApplicationService` de la capa de experiencia inyecta
`LoanOriginationDomainClient` y nunca se entera de si la llamada va por HTTP, por un stub
en memoria o — en el servicio real de firefly-oss — por un SDK de OpenAPI generado. El
port posee el *contrato*; el adaptador posee el *mecanismo*. Cuando extiendas Firefly con
una nueva integración — un proveedor de fraude, un almacén de documentos, un motor de
pricing — empiezas aquí: escribe la interfaz que tu dominio quiere invocar, en el
vocabulario de tu dominio, y resiste la tentación de dejar que un tipo de proveedor se
filtre en ella.

!!! note "Término clave — port y adaptador (hexagonal)"
    Un **port** es una interfaz que expresa lo que tu aplicación necesita en sus propios
    términos; un **adaptador** es una implementación concreta que cumple el port contra
    una tecnología específica. La aplicación depende solo del port, así que cambiar el
    adaptador — HTTP por un stub, un proveedor por otro — nunca toca el código de negocio.
    Esta es la arquitectura hexagonal que Firefly usa para cada capacidad de integración,
    y la forma que copias cuando añades la tuya.

## Paso 2 — Aporta el adaptador como un bean cerrado por condiciones

Ahora la implementación. El adaptador de producción es una clase corriente que implementa
el port reenviando a un `WebClient`, propagando la clave de idempotencia determinista como
la cabecera estándar `Idempotency-Key` en cada llamada:

::: listing exp-lending/src/main/java/com/firefly/lumen/exp/config/WebClientLoanOriginationDomainClient.java | Listado 24.2 — el adaptador: un mecanismo detrás del port
class WebClientLoanOriginationDomainClient implements LoanOriginationDomainClient {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final String APPLICATIONS_PATH = "/api/v1/applications";

    private final WebClient webClient;

    WebClientLoanOriginationDomainClient(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public Mono<ApplicationDetailDTO> submitApplication(CreateApplicationRequest request, String idempotencyKey) {
        return webClient.post()
                .uri(APPLICATIONS_PATH)
                .header(IDEMPOTENCY_HEADER, idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(ApplicationDetailDTO.class);
    }

    @Override
    public Mono<ApplicationDetailDTO> getApplication(UUID applicationId, String idempotencyKey) {
        return webClient.get()
                .uri(APPLICATIONS_PATH + "/{id}", applicationId)
                .header(IDEMPOTENCY_HEADER, idempotencyKey)
                .retrieve()
                .bodyToMono(ApplicationDetailDTO.class);
    }
}
:::

Fíjate en que el adaptador es **package-private** — `class`, no `public class`. Nada fuera
del paquete de configuración puede referenciarlo por tipo; los llamadores solo ven el port.
Eso es intencionado, y es exactamente como se despliegan los propios adaptadores de Firefly:
la implementación es un detalle interno, el port es la superficie pública.

El bean que aporta este adaptador es donde la capacidad se gana la frase «se comporta como
la del propio framework». Está cerrado por las mismas dos condiciones que usa cada
autoconfiguración de Firefly:

::: listing exp-lending/src/main/java/com/firefly/lumen/exp/config/LoanOriginationClientConfig.java | Listado 24.3 — el cableado cerrado: se activa por propiedad, cede ante tu bean
    @Bean
    @ConditionalOnProperty(prefix = "lumen.exp.loan-origination", name = "base-path")
    @ConditionalOnMissingBean
    public WebClient loanOriginationWebClient(LoanOriginationClientProperties properties) {
        log.info("Building Loan Origination WebClient basePath={} timeout={}",
                properties.basePath(), properties.timeout());
        return WebClient.builder()
                .baseUrl(properties.basePath())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_SIZE))
                .build();
    }

    /**
     * Production {@link LoanOriginationDomainClient}. In the real service this wraps the generated
     * domain SDK {@code LoanOriginationApi}; here it is a {@link WebClient}-backed adapter so the
     * wiring is faithful and chapters can slice it verbatim. Only created when a base path is set
     * and no other client bean (e.g. a test stub) is present.
     */
    @Bean
    @ConditionalOnProperty(prefix = "lumen.exp.loan-origination", name = "base-path")
    @ConditionalOnMissingBean
    public LoanOriginationDomainClient loanOriginationDomainClient(WebClient loanOriginationWebClient) {
        return new WebClientLoanOriginationDomainClient(loanOriginationWebClient);
    }
:::

Lee las dos anotaciones del bean `loanOriginationDomainClient`, porque entre las dos *son*
el contrato de Firefly:

- **`@ConditionalOnProperty(prefix = "lumen.exp.loan-origination", name = "base-path")`**
  — el adaptador de producción se materializa solo cuando un operador apunta la capa de
  experiencia a un servicio de dominio real estableciendo
  `lumen.exp.loan-origination.base-path`. Sin base path, no hay bean. Esto es «te apuntas
  añadiendo configuración».
- **`@ConditionalOnMissingBean`** — si *cualquier otra cosa* ya ha aportado un
  `LoanOriginationDomainClient`, este método no se ejecuta. Los tests de Lumen registran
  un stub en memoria, que gana automáticamente; un equipo aguas abajo podría declarar su
  propio bean cliente y sustituir el del framework, sin necesidad de fork. Esto es «el
  framework se retira en el instante en que defines tu propio bean».

El bean también está vinculado a un record `@ConfigurationProperties` tipado,
`LoanOriginationClientProperties`, de modo que el base path y el timeout se configuran bajo
el namespace `lumen.exp.loan-origination.*` — el mismo árbol de propiedades estilo
`firefly.*` que expone cada capacidad. Una capacidad que escribas debería vincular sus
propias `@ConfigurationProperties` exactamente por esta razón: la configuración es datos,
no código.

!!! spring "Equivalente en Spring"
    No hay nada específico de Firefly en ninguno de los tres listados — `@Configuration`,
    `@Bean`, `@ConditionalOnProperty`, `@ConditionalOnMissingBean` y
    `@EnableConfigurationProperties` son todo Spring Boot de serie. Ese es justamente el
    propósito. Firefly no te da una nueva API de extensión que aprender; te da un *estilo
    de la casa* para usar el propio mecanismo de autoconfiguración de Spring Boot, de modo
    que las capacidades de cada equipo estén cerradas por condiciones, sean sustituibles y
    descubribles de la misma manera. Si sabes escribir un starter de Spring Boot, sabes
    extender Firefly.

## Paso 3 — Registra la autoconfiguración para que Boot la encuentre

El ejemplo de Lumen coloca `LoanOriginationClientConfig` en el propio paquete de la
aplicación, de modo que el escaneo de componentes lo recoge directamente. Una capacidad
*reutilizable* — una que publicas como jar para que otros servicios dependan de ella — no
puede confiar en que la aplicación consumidora escanee tu paquete. En su lugar, la
registras de la manera en que Spring Boot 3 descubre las autoconfiguraciones: un fichero de
import en texto plano en el classpath.

Crea `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
y lista tu clase de configuración, un nombre completamente cualificado por línea:

```text
# META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
com.acme.fraud.FraudCheckAutoConfiguration
```

Anota la clase con `@AutoConfiguration` (una especialización de `@Configuration` que además
controla el orden relativo a otras autoconfiguraciones) y mantén las guardas
`@ConditionalOnProperty` / `@ConditionalOnMissingBean` del Paso 2:

```java
// Illustrative: a reusable capability, discovered by the imports file above.
@AutoConfiguration
@EnableConfigurationProperties(FraudCheckProperties.class)
@ConditionalOnProperty(prefix = "acme.fraud", name = "enabled", havingValue = "true")
public class FraudCheckAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public FraudCheckPort fraudCheckPort(FraudCheckProperties props, WebClient.Builder builder) {
        return new HttpFraudCheckAdapter(builder.baseUrl(props.baseUrl()).build());
    }
}
```

Ahora cualquier servicio que añada tu jar obtiene la capacidad automáticamente: Boot lee el
fichero de imports, evalúa las condiciones y — si `acme.fraud.enabled` es `true` y no existe
ya un `FraudCheckPort` — aporta el adaptador. Quita el jar y la capacidad desaparece. Pon la
propiedad a `false` y permanece latente. Declara tu propio bean `FraudCheckPort` y gana el
tuyo. Ese comportamiento aditivo, reversible y sustituible en cualquier punto no es algo que
hayas añadido a posteriori — se desprende de usar el mismo mecanismo que usa el framework.

!!! warning "La ruta y el nombre del fichero de imports son exactos"
    Spring Boot 3 busca exactamente
    `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
    Un error tipográfico en el directorio, el nombre del fichero o el nombre completamente
    cualificado de la clase falla *en silencio* — Boot simplemente nunca encuentra tu
    configuración, las condiciones nunca se evalúan y tu bean nunca aparece, sin ningún error
    al que apuntar. Si una capacidad «no se activa», comprueba este fichero primero. (Esto
    sustituye al antiguo mecanismo `META-INF/spring.factories`, que Boot 3 ha eliminado para
    la autoconfiguración.)

## Paso 4 — Empaquétala como un starter al estilo de las capas

Una capacidad es un jar del que un equipo puede depender. Un **starter** es la idea Firefly
un nivel por encima: un módulo fino, solo de dependencias, que agrupa un *conjunto* coherente
de capacidades más sus valores por defecto sensatos, de modo que un servicio obtenga toda una
postura a partir de una única dependencia. Conociste cuatro de ellos — `starter-core`,
`starter-domain`, `starter-data`, `starter-application` — a lo largo del libro; cada uno trae
el módulo web, las capacidades adecuadas y los valores por defecto de producción (clientes
resilientes, idempotencia, enmascarado de PII, logging en JSON) para su capa.

Empaquetas tu propio starter de la misma manera: un módulo Maven cuyo `pom.xml` declara
dependencias y no despliega *código propio* (o solo una pequeña autoconfiguración). La
convención hasta lo nombra por ti — los módulos de Firefly son `fireflyframework-starter-*`;
una organización que extienda la flota publicaría, por ejemplo, `acme-starter-fraud`:

```xml
<!-- Illustrative: acme-starter-fraud/pom.xml — a starter is dependencies, not code. -->
<dependencies>
    <!-- The capability jar with the AutoConfiguration.imports file from Step 3. -->
    <dependency>
        <groupId>com.acme</groupId>
        <artifactId>acme-fraud-check</artifactId>
    </dependency>
    <!-- Pull in the Firefly web + resilience defaults the capability assumes. -->
    <dependency>
        <groupId>org.fireflyframework</groupId>
        <artifactId>fireflyframework-starter-domain</artifactId>
    </dependency>
</dependencies>
```

Como el reactor de Lumen hereda el **POM padre** de Firefly e importa el **BOM**
(Capítulo 3), ni el jar de la capacidad ni el starter declaran una sola versión para una
dependencia del framework — el BOM las fija todas en el mismo conjunto libre de conflictos.
Un servicio consumidor añade `acme-starter-fraud`, sin versión, y hereda una comprobación de
fraude completamente cableada que se activa con una sola propiedad. Ese es el quinto
movimiento Firefly del Capítulo 1 — «servicios correctos en una dependencia» — aplicado a
*tu* capacidad en lugar de a la del framework.

!!! tip "Punto de control"
    No necesitas un nuevo test para confirmar el patrón de extensión — ya lo has ejecutado.
    Cada capítulo desde el 6 en adelante pasó porque las autoconfiguraciones del framework se
    activaban por la presencia en el classpath y los stubs de test del ejemplo las sustituían
    vía `@ConditionalOnMissingBean`. Abre `LoanOriginationClientConfig` (Listado 24.3) junto a
    cualquier test en `exp-lending/src/test`, y estarás mirando el bean de producción y la
    sustitución de test que prueba la retirada — el mecanismo exacto que hereda tu propia
    capacidad.

## Ir a producción: imágenes nativas vía el `-Pnative` del padre

Un servicio Firefly es una aplicación Spring Boot, así que se despliega como una aplicación
Spring Boot: un fat jar ejecutable de `mvn package`, o una imagen OCI por capas del plugin
Maven de Spring Boot. La opción de producción interesante — y una que el POM padre ya cablea
por ti — es una **imagen nativa de GraalVM**: la aplicación compilada de antemano a un
ejecutable autónomo que arranca en decenas de milisegundos y usa una fracción del heap, a
cambio de un build más largo y supuestos de mundo cerrado.

No configuras nada de esto por servicio. El POM padre de Firefly que heredaste en el
Capítulo 3 lleva un perfil `native` que activa los dos plugins que necesita un build nativo —
el procesamiento AOT de Spring Boot y el `native-maven-plugin` de GraalVM — y apunta el build
de imagen de Spring Boot a un buildpack de Paketo. Activarlo es una sola bandera:

```text
mvn -Pnative -pl core-lending-loan-origination spring-boot:build-image
```

Ese comando hace tres cosas que el perfil precableó por ti. Primero, la meta `process-aot`
de Spring Boot se ejecuta en tiempo de build: evalúa tus definiciones de bean, tus
autoconfiguraciones condicionales y tus vinculaciones de propiedades *una vez*, de antemano,
y emite las pistas de reflexión, recursos y proxies que GraalVM necesita. Segundo, el buildpack
`builder-jammy-tiny` de Paketo construye el contenedor con `BP_NATIVE_IMAGE` activado, de modo
que el compilador de GraalVM produce un ejecutable nativo en lugar de una capa de JVM. Tercero,
el resultado es una imagen OCI mínima que contiene un único binario casi estático — sin JVM que
calentar.

```text
# The parent's `native` profile sets these for you; you only pass -Pnative.
image:
  builder: paketobuildpacks/builder-jammy-tiny
  env:
    BP_NATIVE_IMAGE: true
```

La recompensa es operativa: un servicio Lumen nativo arranca en frío en aproximadamente el
tiempo que un servicio JVM dedica a cargar clases, lo que hace prácticos el escalado a cero y
el escalado horizontal rápido. El coste es real y merece nombrarse. La compilación AOT cierra
el mundo — cualquier cosa hecha por reflexión en tiempo de ejecución, proxies dinámicos o carga
de recursos que el paso AOT no pudo ver debe declararse con pistas. Como las capacidades de
Firefly son autoconfiguraciones corrientes de Spring Boot y el motor AOT de Spring Boot las
entiende, la mayor parte del cableado de la flota se gestiona automáticamente; una capacidad que
*tú* escribas debería ejercitarse bajo el perfil nativo antes de confiar en ella en producción.

!!! warning "Las imágenes nativas son de mundo cerrado; prueba el binario nativo"
    Un servicio que pasa todos los tests de JVM aún puede fallar como imagen nativa, porque
    GraalVM no puede ver la reflexión o el acceso a recursos que ocurre solo en tiempo de
    ejecución. El fallo aparece en el arranque o en la primera petición del binario nativo, no
    en tus tests de JVM. Trata `-Pnative` como un objetivo de build distinto: construye la
    imagen, ejecuta los tests de integración del servicio *contra el contenedor nativo en
    ejecución* y solo entonces despliégalo. El paso AOT más las pistas de Spring Boot cubren el
    framework; tu propio código reflexivo es tu responsabilidad de declarar con pistas y
    verificar.

!!! spring "Equivalente en Spring"
    El perfil nativo es Spring Boot 3 puro más GraalVM — `process-aot`, el `native-maven-plugin`
    y los buildpacks de Paketo son exactamente lo que cablearías en un proyecto Spring Boot
    normal. La única contribución de Firefly es que el POM padre lleva el perfil una vez, de
    forma idéntica, para cada servicio de la flota, de modo que no vuelves a derivar la
    configuración del plugin en cada `pom.xml`. Lo activas con `-Pnative`; todo lo de debajo es
    Boot de serie.

## Una lista de materiales, automáticamente

Las plataformas reguladas deben responder cada vez más a «¿qué hay, exactamente, dentro de este
artefacto?» — cada dependencia transitiva y su versión — para el escaneo de vulnerabilidades y
las auditorías de cadena de suministro. La respuesta es una **lista de materiales de software
(SBOM)**, y el POM padre de Firefly genera una en cada build sin que lo pidas.

El padre vincula el plugin Maven de CycloneDX a la fase `package`. Su ejecución `generate-sbom`
corre `makeAggregateBom` y escribe un documento CycloneDX — JSON, llamado `application.cdx`, bajo
`META-INF/sbom/` *dentro del artefacto construido* — de modo que el SBOM viaja con el jar o la
imagen en lugar de como un fichero suelto:

```text
# Inherited from the parent POM — produced by an ordinary `mvn package`.
target/classes/META-INF/sbom/application.cdx.json
```

Como el documento es CycloneDX, los escáneres estándar (Grype, Trivy, Dependency-Track) lo
ingieren directamente: puedes condicionar una release a «ningún CVE crítico conocido en el SBOM»
y demostrar, artefacto a artefacto, qué se desplegó. Combínalo con la imagen nativa y tu forma de
producción es un pequeño contenedor OCI que arranca en milisegundos y lleva un inventario legible
por máquina y firmable de todo lo que contiene — la postura de despliegue a la que se somete una
plataforma bancaria, producida por el build que ya ejecutas.

!!! note "Término clave — SBOM (software bill of materials)"
    Un **SBOM** es un inventario completo y legible por máquina de los componentes de un artefacto
    de software — cada dependencia, su versión y su procedencia. **CycloneDX** es uno de los dos
    estándares comunes de SBOM (SPDX es el otro). El padre de Firefly emite un SBOM CycloneDX en
    `package`, embebido en el artefacto, de modo que cada servicio de la flota despliega un
    inventario auditable por defecto en lugar de por acordarse.

## Más allá de este libro

Dos esfuerzos paralelos se sitúan justo fuera de estas páginas. El
**`fireflyframework-agentic-bridge`** conecta el framework Java con una plataforma agéntica de
Python documentada por separado, de modo que un servicio Firefly pueda participar en flujos de
trabajo de agentes sin abandonar el modelo reactivo que aprendiste aquí. Y el framework tiene
**ports a otros lenguajes** hermanos — PyFly (Python), y las implementaciones en curso de Rust,
Go y .NET — que llevan las mismas opiniones a otros ecosistemas. Los cuatro son sus propios
proyectos con su propia documentación; este libro se queda en Java, en la JVM, porque ahí es
donde vive el recorrido que acabas de terminar.

## Lo que has aprendido {.recap}

- **Extender Firefly es escribir autoconfiguración de Spring Boot.** Una capacidad es una clase
  `@Configuration` (o `@AutoConfiguration`) cerrada por `@ConditionalOnProperty` y
  `@ConditionalOnMissingBean` — no hay una API de extensión separada que aprender.
- Una capacidad tiene tres partes: un **port** del que depende tu código (libre de proveedor), un
  **adaptador** que lo cumple (package-private, detrás del port) y un **bean cerrado por
  condiciones** que se activa por propiedad y cede ante cualquier bean que definas — exactamente la
  forma del `LoanOriginationDomainClient` de la capa de experiencia, su adaptador `WebClient` y su
  `LoanOriginationClientConfig`.
- Una capacidad reutilizable se **registra** vía
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
  para que Boot la descubra; un **starter** agrupa un conjunto coherente de capacidades más valores
  por defecto en un único módulo solo de dependencias, igual que hacen los cuatro starters de capa.
- La **producción** cabalga sobre el POM padre: `-Pnative` construye una imagen nativa de GraalVM
  vía el AOT de Spring Boot y un buildpack de Paketo (arranque en frío rápido, mundo cerrado —
  verifica el binario nativo), y un **SBOM CycloneDX** se emite en cada artefacto en `package` para
  la auditoría de cadena de suministro.
- El **agentic bridge** y los **ports a PyFly/Rust/Go/.NET** son proyectos paralelos, documentados
  por separado y fuera del alcance aquí.

## Pruébalo tú mismo {.exercises}

1. **Añade una propiedad a la costura.** Dale a `LoanOriginationClientProperties` un nuevo campo
   vinculado — digamos un `maxRetries` int con un valor por defecto — y léelo en
   `LoanOriginationClientConfig` para configurar el `WebClient`. Confirma que se vincula bajo
   `lumen.exp.loan-origination.max-retries` y que toma el valor por defecto cuando está ausente.
   Acabas de extender la superficie de configuración de una capacidad al estilo Firefly.
2. **Prueba la retirada.** En un test de `exp-lending`, define tu propio `@Bean`
   `LoanOriginationDomainClient`, establece `lumen.exp.loan-origination.base-path` de modo que el
   bean de producción *se activaría* normalmente, y afirma que el inyectado es el tuyo. Has
   demostrado que `@ConditionalOnMissingBean` sustituye el valor por defecto del framework sin un
   fork.
3. **Escribe el fichero de imports.** Esboza una pequeña capacidad reutilizable — un port, un
   adaptador package-private y una clase `@AutoConfiguration` — y escribe la línea exacta de
   `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
   que la registra. Nombra el modo de fallo si escribes mal la ruta.
4. **Inspecciona un SBOM real.** Ejecuta `mvn -q -pl core-lending-loan-origination package` y abre
   `target/classes/META-INF/sbom/application.cdx.json`. Encuentra tres módulos de Firefly y confirma
   que sus versiones coinciden con el BOM del Capítulo 3 — el SBOM es la coherencia de versiones del
   Capítulo 1 hecha auditable.
5. **Planifica un build nativo.** Sin ejecutarlo, enumera qué cambia `-Pnative` frente a un
   `package` normal: qué dos plugins se activan, qué produce `process-aot` y un patrón reflexivo en
   una capacidad propia que necesitaría una pista de GraalVM.

## Adónde ir ahora

Este es el último capítulo, así que «ahora» es tu propia flota. Has construido Lumen Lending de
extremo a extremo — un cliente puede **solicitar** un préstamo a través de la capa de experiencia,
hacer que sea **puntuado** y **decidido** por una saga de dominio, recibir **ofertas** y **aceptar**
una, con la capa core como sistema de registro y eventos de dominio anunciando cada paso — a través
de cuatro capas que se integran sobre contratos, nunca una base de datos compartida. Cada línea que
leíste fue una porción verificada de un reactor en ejecución, y cada preocupación transversal —
errores RFC 7807, idempotencia, enmascarado de PII, propagación de transacciones, contexto que
sobrevive a las fronteras de los operadores — vino de un starter que añadiste en una línea. El
impuesto empresarial que el Capítulo 1 nombró se paga una vez, en el framework, y lo hereda cada
servicio. Ahora ve a codificar las decisiones tan duramente ganadas de tu propia plataforma de la
misma manera: como una capacidad que toda la flota obtiene gratis.
