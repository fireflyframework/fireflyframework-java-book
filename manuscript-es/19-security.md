Hasta ahora, en todos los capítulos cada petición llegaba directamente al manejador. Eso
estaba bien mientras aprendías la pila reactiva, pero una plataforma de préstamos no puede
salir a producción así: crear una solicitud de préstamo es un acto privilegiado, y leer una
expone la vida financiera de un cliente. La capa de experiencia —el BFF orientado al canal
que conociste en el Capítulo 17— es donde Lumen decide *quién puede hacer qué*. Este capítulo
muestra cómo lo hace, sobre WebFlux, con dos piezas que trabajan juntas.

La primera pieza es Spring convencional: una `SecurityWebFilterChain` reactiva en el borde
del pipeline HTTP. La segunda es de Firefly: una anotación declarativa `@Secure` sobre el
método del controlador, aplicada no por la cadena de filtros sino por un aspecto AOP del
framework que lee el principal autenticado y el tenant de un contexto de aplicación. Repartir
la autorización de este modo —reglas de transporte de grano grueso en el filtro, comprobaciones
finas de permisos en el método— es el modelo que fomenta el starter de aplicación, y es lo que
permite que el mismo controlador se ejecute sin cambios tanto si la identidad procede de
Keycloak, de Cognito o de un directorio interno.

Vamos a recortar el `ApplicationController` real y su `WebSecurityConfig` de `exp-lending`,
seguir cómo se aplica `@Secure` (y cómo una propiedad apaga esa aplicación para los tests),
esbozar el `AppContext` que transporta el principal, y terminar en el puerto de identidad
agnóstico al proveedor que el Apéndice B cataloga. Luego ejecutamos el test de rebanada que
ejercita los métodos protegidos con la aplicación desactivada.

## Spring Security sobre WebFlux es una cadena de filtros, no un filtro de servlet

Si has protegido una aplicación Spring MVC, habrás echado mano de `WebSecurityConfigurerAdapter`
o de una `SecurityFilterChain` construida sobre la pila de `Filter` de servlet. WebFlux es un
runtime distinto —no hay servlet, ni `SecurityContext` ligado a un `ThreadLocal`— de modo que
Spring Security expone una API paralela y reactiva. Anotas una clase de configuración con
`@EnableWebFluxSecurity` y publicas un bean `SecurityWebFilterChain` construido a partir de un
`ServerHttpSecurity`. Aquí tienes la de Lumen, entera.

::: listing exp-lending/src/main/java/com/firefly/lumen/exp/config/WebSecurityConfig.java | Listado 19.1 — la cadena de filtros de seguridad reactiva del BFF
@Configuration
@EnableWebFluxSecurity
public class WebSecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs",
                                "/v3/api-docs/**",
                                "/webjars/**",
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info",
                                "/actuator/prometheus"
                        ).permitAll()
                        .anyExchange().permitAll()
                )
                .build();
    }
}
:::

Lee lo que esta cadena hace realmente, porque la sorpresa está en lo que *no* hace. Desactiva
cuatro cosas que Spring Security enciende por defecto en cuanto `spring-boot-starter-security`
aterriza en el classpath —y aterriza, porque `fireflyframework-starter-application` lo arrastra
transitivamente—. HTTP Basic, el login por formulario, CSRF y logout son los valores por defecto
correctos para una aplicación web renderizada en servidor y exactamente equivocados para un BFF
JSON sin estado: HTTP Basic abriría un diálogo de autenticación del navegador, y CSRF rechazaría
tu `POST` sin un token. Desactivarlos despeja ese ruido.

Luego `authorizeExchange` permite todo —las rutas documentadas de forma explícita, y
`anyExchange().permitAll()` para el resto—. Eso resulta alarmante hasta que ves la segunda mitad
del diseño. Esta cadena de filtros está deliberadamente *abierta* en la capa de transporte porque
la autorización en Lumen ocurre una capa más adentro, en el método, a través de `@Secure`. El
trabajo de la cadena de filtros aquí es impedir que los valores por defecto de Spring Security
interfieran; la *decisión* se delega. Otro despliegue podría endurecer esta cadena para exigir un
token bearer en `anyExchange()` y validar un JWT —el hueco está justo ahí— pero Lumen mantiene las
reglas de transporte permisivas y pone la comprobación real en el manejador.

!!! spring "Equivalente en Spring"
    Todos los tipos de este listado son Spring Security para WebFlux de serie:
    `@EnableWebFluxSecurity`, `ServerHttpSecurity`, `SecurityWebFilterChain`, las lambdas
    `CsrfSpec`/`HttpBasicSpec`/`FormLoginSpec`/`LogoutSpec`, y `authorizeExchange`. No hay
    ningún tipo de Firefly en esta página. Si has escrito una configuración de seguridad
    reactiva, has escrito esta. Lo que Firefly añade *no* está aquí —es el modelo `@Secure` a
    nivel de método que introduce la siguiente sección, que cabalga sobre esta cadena en lugar
    de reemplazarla.

!!! note "Término clave — `SecurityWebFilterChain`"
    El equivalente en WebFlux de una `SecurityFilterChain` de servlet. Es un pipeline de
    `WebFilter` que se ejecuta sobre el bucle de eventos de Reactor, construido de forma fluida a
    partir de un `ServerHttpSecurity`. Como es reactiva, el principal que resuelve vive en el
    `Context` de Reactor, no en un `ThreadLocal` —y por eso una búsqueda bloqueante en
    `SecurityContextHolder` no funciona en esta pila, y por eso el propio contexto de Firefly
    (más abajo) se propaga a través de la cadena reactiva en su lugar.

## La comprobación declarativa: `@Secure` en el método

Ahora el controlador. Es un `@RestController` reactivo corriente —dos métodos, un `POST` para
crear una solicitud y un `GET` para leerla— pero cada método lleva una anotación `@Secure` de
Firefly que declara el permiso que el llamante debe poseer.

::: listing exp-lending/src/main/java/com/firefly/lumen/exp/web/ApplicationController.java | Listado 19.2 — autorización declarativa a nivel de método con @Secure
@RestController
@RequestMapping("/api/v1/experience/lending/applications")
@Tag(name = "Lending - Applications")
public class ApplicationController {

    private final ApplicationService applicationService;

    public ApplicationController(ApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "createApplication", summary = "Create Application",
            description = "Creates a new loan application via the domain origination service.")
    @Secure(permissions = {"lending:application:create"},
            description = "Create a loan application")
    public Mono<ResponseEntity<ApplicationDetailDTO>> createApplication(
            @Valid @RequestBody CreateApplicationRequest request) {
        return applicationService.createApplication(request)
                .map(result -> ResponseEntity.status(HttpStatus.CREATED).body(result));
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getApplication", summary = "Get Application",
            description = "Retrieves the full details of a loan application by its identifier.")
    @Secure(permissions = {"lending:application:read"},
            description = "Read a loan application")
    public Mono<ResponseEntity<ApplicationDetailDTO>> getApplication(@PathVariable UUID id) {
        return applicationService.getApplication(id)
                .map(ResponseEntity::ok);
    }
}
:::

La regla de autorización se lee como documentación: `createApplication` requiere
`lending:application:create`, `getApplication` requiere `lending:application:read`. Las cadenas
de permiso están dentro de un ámbito —`resource:action`, con espacio de nombres por dominio—
de modo que una definición de rol en tu proveedor de identidad puede conceder
`lending:application:read` sin repartir por accidente derechos de creación. El `description` no
es decoración: alimenta el registro de seguridad de endpoints que el framework construye en el
arranque, que es como una pantalla de back-office o una exportación de auditoría pueden listar
cada método protegido y el permiso que exige sin leer el código fuente.

Fíjate en el import del fichero fuente: `@Secure` es
`org.fireflyframework.common.application.security.annotation.Secure`, una anotación del framework,
no de Spring Security. La seguridad de métodos de Spring usa `@PreAuthorize("hasAuthority('...')")`
con una expresión SpEL; el `@Secure` de Firefly toma una lista tipada de cadenas de permiso y se
aplica mediante un aspecto del framework en lugar del `MethodSecurityInterceptor` de Spring. Las
dos resuelven el mismo problema; la versión de Firefly está afinada para el mundo multi-tenant,
reactivo y de cadenas de permiso en el que vive la plataforma.

!!! note "Término clave — `@Secure`"
    Una anotación de autorización a nivel de método de Firefly. Declaras los `permissions` (y
    opcionalmente los roles) que un llamante debe poseer; el `SecurityAspect` del framework
    intercepta la llamada y los comprueba contra el principal autenticado transportado en el
    contexto de aplicación. Es declarativa —nada de `if (user.hasPermission(...))` en tu
    manejador— y vive en el *método*, así que el mismo controlador puede mezclar endpoints
    públicos y protegidos sin repartirlos entre varias clases.

## Cómo se aplica `@Secure`: el SecurityAspect

`@Secure` es una anotación, lo que significa que por sí sola no hace nada —algo tiene que
*leerla* y actuar—. Ese algo es el `SecurityAspect` del starter de aplicación, un aspecto AOP
de Spring corriente que envuelve todos los métodos `@Secure`. Cuando se invoca un método
protegido, el aspecto se ejecuta primero: localiza el contexto de ejecución de la aplicación,
resuelve a partir de él los permisos del llamante, los compara con el conjunto requerido por la
anotación, y o bien continúa hacia tu manejador o bien corta el paso con un fallo de autorización
que la capa web renderiza como un `403`. El cuerpo de tu manejador nunca se ejecuta si la
comprobación falla.

Puedes verlo ocurrir. Con el logging en debug, el servicio en ejecución imprime una línea por
cada llamada interceptada:

```text
DEBUG o.f.c.application.aop.SecurityAspect : Intercepting @Secure method: createApplication
```

Esa línea es salida real del test de rebanada de este capítulo —el aspecto está cableado y
disparándose, no es teórico—. Lo que hace que el test *pase* sin acuñar ningún token es el
interruptor de aplicación.

### El interruptor de aplicación: `firefly.application.security.enabled`

El `SecurityAspect` respeta una única propiedad, `firefly.application.security.enabled`. En
producción es `true` y el aspecto aplica cada regla `@Secure`. En un test de rebanada la pones a
`false`, y el aspecto degrada a un no-op: sigue interceptando el método —de modo que la anotación
se ejercita de verdad— pero registra que la seguridad está desactivada y continúa directamente
hacia el manejador sin comprobar permisos. El perfil de test de Lumen establece exactamente eso:

::: listing exp-lending/src/test/resources/application.yml | Listado 19.3 — desactivar la aplicación para el test de rebanada, sin cambiar el controlador
firefly:
  application:
    security:
      enabled: false
  cqrs:
    enabled: false
:::

Este es el detalle honesto e importante de todo el capítulo. El test de rebanada **no** elimina
`@Secure`, ni simula un principal, ni reemplaza el aspecto. Conserva la anotación real y el
`SecurityAspect` real, y desactiva solo la *aplicación* mediante configuración. El aspecto se
ejecuta, no encuentra ningún contexto de ejecución de la aplicación en los argumentos del método
—porque el test llama al endpoint a través de `WebTestClient` sin uno— y, con la aplicación
apagada, deja pasar la llamada. En los logs del test puedes ver ambas mitades de esa decisión:

```text
DEBUG SecurityAspect : Intercepting @Secure method: getApplication
WARN  SecurityAspect : No ApplicationExecutionContext found in method arguments, skipping security check
```

La primera línea prueba que el camino de la anotación está vivo; la segunda es el aspecto
declinando aplicar. Vuelve a poner la propiedad a `true` sin suministrar un contexto, y la misma
llamada sería rechazada —que es precisamente el comportamiento que quieres en producción y el
comportamiento que el test sortea deliberadamente para poder afirmar la lógica de negocio de
forma aislada.

!!! warning "`security.enabled=false` es un mando solo para tests"
    Desactivar la aplicación es la jugada correcta para un test de rebanada que afirma el mapeo y
    los códigos de estado, y la jugada equivocada en cualquier sitio donde pueda llegar un llamante
    real. Mantenlo acotado a `src/test/resources` (o a un perfil de test) como hace Lumen. Enviar a
    producción un servicio con `firefly.application.security.enabled=false` convierte cada anotación
    `@Secure` en decorativa —el aspecto intercepta, registra y deja pasar a todo el mundo—. El valor
    por defecto es `true` por exactamente esta razón; nunca lo sobreescribas en un perfil desplegado.

!!! spring "Equivalente en Spring"
    El `@EnableMethodSecurity` de Spring más `@PreAuthorize` también se aplica mediante un
    interceptor AOP —el mecanismo tiene la misma forma—. La diferencia de Firefly es el *interruptor*:
    hay una única propiedad para toda la flota que enciende o apaga la autorización de métodos, de
    modo que los tests de cada servicio desactivan la aplicación de la misma manera en lugar de que
    cada uno invente un baile con `@WithMockUser` o una configuración de seguridad de test a medida.
    Sigues usando seguridad de métodos basada en AOP; Firefly estandariza las costuras alrededor.

## `AppContext`: el principal y el tenant, transportados de forma reactiva

El aspecto necesita saber *quién* llama y a *qué tenant* pertenece. En la pila de servlet eso vive
en un `SecurityContextHolder` con `ThreadLocal`; en la pila reactiva un `ThreadLocal` es una trampa,
porque Reactor salta de hilo entre operadores y el valor no lo seguiría. La respuesta de Firefly es
un objeto de contexto de aplicación —llámalo el `AppContext` (su mitad de seguridad es el
`AppSecurityContext`)— que viaja *con* la petición a través del `Context` de Reactor, de modo que el
principal autenticado y el id de tenant están disponibles en cualquier punto de la cadena reactiva,
en cualquier hilo.

El starter de aplicación rellena este contexto en el borde a partir de la autenticación que
trajera la petición —un token bearer validado, una cabecera de un gateway aguas arriba— y el
`SecurityAspect` lee de él los permisos del llamante para satisfacer `@Secure`. La forma que pasarías
a un método protegido, o leerías dentro de uno, tiene este aspecto:

```java
// Illustrative: the application context carrying principal and tenant through a reactive call.
public Mono<ApplicationDetailDTO> createApplication(AppContext ctx, CreateApplicationRequest request) {
    String userId   = ctx.security().getPrincipalId();   // who is acting
    String tenantId = ctx.security().getTenantId();       // which tenant they belong to
    // @Secure already checked ctx.security().getPermissions() against the required set
    return applicationService.createApplication(tenantId, request);
}
```

Eso es ilustrativo —el controlador de rebanada de Lumen no toma el contexto como parámetro, que es
exactamente por lo que el test registró "No ApplicationExecutionContext found in method arguments"—.
En un despliegue completamente cableado el contexto se enhebra (o se resuelve desde el `Context` de
Reactor), el aspecto lo encuentra, y la comprobación de permisos tiene algo contra lo que comparar.
El id de tenant importa tanto como el principal: una plataforma de préstamos es multi-tenant, y la
misma llamada `getApplication` debe acotar su lectura al tenant del llamante para que el operador de
un banco no pueda recuperar el préstamo de otro. El contexto es cómo esa frontera de tenant sigue a
la petición sin un parámetro en cada método.

!!! note "Término clave — `AppContext` / `AppSecurityContext`"
    El objeto con ámbito de petición que la capa de aplicación transporta a través de una llamada
    reactiva, conteniendo el id del principal autenticado, el id de tenant, los permisos y roles del
    llamante, y metadatos de correlación. `AppSecurityContext` es su vista centrada en la seguridad.
    Es el reemplazo seguro para reactivo de `SecurityContextHolder`: se rellena una vez en el borde,
    se propaga a través del `Context` de Reactor, y lo lee el `SecurityAspect` (y tus manejadores)
    aguas abajo. Es la misma idea que el `ExecutionContext` de CQRS del Capítulo 10, acotada a la
    capa de experiencia y centrada en la identidad.

!!! spring "Equivalente en Spring"
    Spring Security WebFlux expone el principal a través de
    `ReactiveSecurityContextHolder.getContext()`, que lee desde el `Context` de Reactor —la primitiva
    reactiva correcta—. El `AppContext` de Firefly se construye sobre esa misma primitiva pero
    transporta más que un principal: tenant, permisos como cadenas con ámbito, y correlación, en un
    único objeto que toda la capa de aplicación comparte. Aún puedes alcanzar el
    `ReactiveSecurityContextHolder` de Spring; `AppContext` es el sobre estándar de la flota a su
    alrededor.

## `@RequireContext`: exigir que el contexto esté presente

`@Secure` responde a "¿puede este llamante hacer esto?" Una anotación complementaria,
`@RequireContext`, responde a una pregunta previa: "¿hay siquiera un contexto autenticado?" La
pones sobre un método (o una clase) que no debe ejecutarse de forma anónima —le dice al aspecto que
rechace la llamada cuando no haya ningún `AppContext` presente, antes de cualquier comprobación de
permisos—. Es la forma explícita de decir "este endpoint nunca es público", y se combina de manera
natural con `@Secure`:

```java
// Illustrative: require an authenticated context, then a specific permission.
@RequireContext
@Secure(permissions = {"lending:application:read"})
public Mono<ResponseEntity<ApplicationDetailDTO>> getApplication(AppContext ctx, @PathVariable UUID id) {
    return applicationService.getApplication(ctx.security().getTenantId(), id).map(ResponseEntity::ok);
}
```

La rebanada de Lumen usa `@Secure` a solas y se apoya en el interruptor de test para saltarse la
aplicación, así que `@RequireContext` no aparece en el reactor —trátala como la compañera de
cómo-funciona a la que recurres cuando un endpoint debe fallar en seco ante una identidad ausente en
lugar de depender de la lista de permisos de `@Secure` para atraparlo—. El Capítulo 17 cubre la
historia más amplia del `AppContext` para la capa de experiencia; aquí lo importante es solo que las
dos anotaciones componen: exige el contexto, y luego restringe lo que puede hacer.

## De dónde viene la identidad: el puerto IDP agnóstico al proveedor

Todo lo anterior daba por supuesto que llegaba un principal autenticado. *Producir* ese principal
—autenticar a un usuario, validar un token, refrescar una sesión, buscar los roles de un usuario—
es trabajo de un proveedor de identidad, y Firefly lo mantiene tras un puerto exactamente como
cualquier otra preocupación de proveedor en la plataforma. El núcleo IDP define una única interfaz
`IdpAdapter` (login, refresco, introspección de tokens, CRUD de usuarios, MFA, sesiones), y un
adaptador concreto se elige mediante una única propiedad:

```yaml
# Illustrative: pick an identity provider with one property; the adapter jar registers the rest.
firefly:
  idp:
    provider: keycloak   # keycloak | cognito | azure-ad | internal-db
```

Cambiar de Keycloak a AWS Cognito es un intercambio de dependencia más esta única línea —tu
`SecurityWebFilterChain`, tus anotaciones `@Secure`, y tus manejadores conscientes de `AppContext`
no cambian, porque dependen de la noción de principal y permisos *del puerto*, nunca de un SDK de
proveedor—. Ese es el mismo patrón hexagonal que el Capítulo 1 prometió y que el intercambio de
transporte de EDA del Capítulo 11 demostró, aplicado a la identidad. El núcleo IDP no se ejercita
en la rebanada de este capítulo —el test desactiva la aplicación en lugar de acuñar un token real—
así que trata la tabla de proveedores como dónde se enchufa, no como algo que esta build ejecuta.

!!! note "Término clave — puerto IDP (`IdpAdapter`)"
    Una única interfaz de la que la plataforma depende para todas las operaciones de identidad
    —login, refresco, introspección, gestión de usuarios y grupos, MFA, manejo de sesiones—. Cada
    proveedor (Keycloak, Cognito, Microsoft Entra ID, una base de datos interna) entrega un adaptador
    que se activa mediante `@ConditionalOnProperty` cuando `firefly.idp.provider` lo nombra. Tu código
    de seguridad apunta al puerto; la propiedad elige la implementación. El Apéndice B lista los
    adaptadores y las dependencias exactas.

!!! spring "Equivalente en Spring"
    En Spring puro cablearías `spring-security-oauth2-resource-server` a un emisor, o un adaptador de
    Keycloak, directamente en tu configuración de seguridad —y cambiar de proveedor significa editar
    esa configuración en cada servicio—. El puerto IDP de Firefly convierte el proveedor en
    configuración: el cableado del servidor de recursos o del adaptador vive en el jar del adaptador
    elegido, seleccionado por `firefly.idp.provider`, de modo que el intercambio es para toda la flota
    y sin código. Consulta el Apéndice B para el catálogo completo de adaptadores.

## Ejecútalo

El test de rebanada arranca la aplicación `exp-lending` real —el `ApplicationController` con sus
anotaciones `@Secure`, la cadena de filtros `WebSecurityConfig`, el `SecurityAspect`, y el
`GlobalExceptionHandler` de `fireflyframework-web`— y la conduce a través de `WebTestClient`,
satisfaciendo la costura del SDK con un stub en memoria de modo que no hay servicio de dominio ni
Docker. La aplicación está desactivada mediante el `firefly.application.security.enabled=false` del
perfil de test, así que los métodos protegidos se ejecutan mientras el `SecurityAspect` corta el
paso. Desde el directorio `samples/lumen-lending`:

```text
mvn -q -pl exp-lending test
```

Deberías ver pasar los tests del módulo:

```text
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Los cuatro tests de `ApplicationControllerTest` son los que importan aquí. Hacen `POST` de una
solicitud válida y afirman `201`, hacen el viaje de ida y vuelta de crear-y-luego-`GET`, afirman que
un id desconocido devuelve un detalle de problema `404`, y rechazan un importe cero con `400` —y cada
una de esas peticiones pasa por un método `@Secure` cuyo aspecto intercepta, registra y (con la
aplicación apagada) continúa. La build prueba dos cosas a la vez: las anotaciones de autorización
están cableadas y vivas sobre los manejadores reales, y desactivar la aplicación es un cambio de
configuración, no de código.

!!! tip "Punto de control"
    Ejecuta el comando de arriba y confirma `Tests run: 9, Failures: 0`. Luego ejecútalo con la
    aplicación activada —`mvn -q -pl exp-lending -Dfirefly.application.security.enabled=true test`—
    y observa cómo las llamadas protegidas fallan la autorización: sin ningún `AppContext`
    suministrado, el `SecurityAspect` no tiene nada contra lo que comprobar los permisos requeridos
    y rechaza la petición. Restaura el valor por defecto del test y vuelve a pasar. Acabas de ver al
    interruptor hacer su único trabajo, desde ambos lados.

## Lo que has aprendido {.recap}

- Spring Security sobre WebFlux es una **`SecurityWebFilterChain`** reactiva, construida a partir
  de `ServerHttpSecurity` bajo `@EnableWebFluxSecurity` —no la pila de filtros de servlet—. La
  cadena de Lumen desactiva los valores por defecto hostiles a la ausencia de estado (HTTP Basic,
  login por formulario, CSRF, logout) y permite todos los exchanges, *delegando* la autorización en
  la capa de método.
- **`@Secure`** es la autorización declarativa a nivel de método de Firefly: una lista tipada de
  cadenas de permiso con ámbito (`lending:application:create`) sobre el manejador, aplicada por el
  **`SecurityAspect`** del starter de aplicación (AOP), no por el `@PreAuthorize` de Spring.
- Una propiedad, **`firefly.application.security.enabled`**, enciende la aplicación (el valor por
  defecto de producción) o la apaga (el perfil de test). Con ella a `false`, el aspecto sigue
  interceptando el método `@Secure` pero se salta la comprobación de permisos —que es exactamente
  cómo el test de rebanada conserva las anotaciones reales mientras afirma la lógica de negocio.
- **`AppContext`/`AppSecurityContext`** transporta el principal autenticado, el id de tenant y los
  permisos a través de la cadena reactiva vía el `Context` de Reactor —el reemplazo seguro para
  reactivo de `SecurityContextHolder`— y **`@RequireContext`** exige que esté presente antes de que
  un método se ejecute.
- La identidad se produce tras un puerto **`IdpAdapter`** agnóstico al proveedor, seleccionado por
  `firefly.idp.provider` (Keycloak, Cognito, Entra ID, BD interna) —un intercambio de una sola línea
  con referencia cruzada al Apéndice B.
- Una build de módulo que pasa (`Tests run: 9, Failures: 0`) en la que cada llamada del controlador
  atraviesa un aspecto `@Secure` real con la aplicación desactivada mediante configuración.

## Pruébalo tú mismo {.exercises}

1. **Demuestra que el aspecto es real.** Ejecuta `mvn -pl exp-lending test` y lee la salida del test
   buscando las líneas de log del `SecurityAspect` (`Intercepting @Secure method: ...` y
   `skipping security check`). Encuentra ambas para `createApplication` y `getApplication`, y explica
   en una frase por qué la segunda línea significa que la anotación se ejercita pero no se aplica.
2. **Acciona el interruptor.** Ejecuta el módulo con `-Dfirefly.application.security.enabled=true` y
   confirma que los tests protegidos ahora fallan la autorización. Lee el fallo, y luego restaura el
   valor por defecto del test. ¿Qué línea de `src/test/resources/application.yml` es el único mando
   que cambiaste?
3. **Añade un permiso.** Da a `getApplication` un segundo permiso requerido (por ejemplo
   `lending:application:read` *y* `lending:tenant:member`) ampliando el array `permissions` de
   `@Secure`. Vuelve a ejecutar el test de rebanada —sigue pasando, porque la aplicación está
   apagada— y luego escribe una frase sobre qué tendría que ser cierto del `AppContext` del llamante
   para que pasara con la aplicación *encendida*.
4. **Endurece la cadena de filtros.** En `WebSecurityConfig`, cambia el `.anyExchange().permitAll()`
   final por `.anyExchange().authenticated()` y añade un servidor de recursos con token bearer
   (`http.oauth2ResourceServer(...)`). Esboza —sin necesidad de ejecutar un IDP real— cómo esta
   comprobación a nivel de transporte y el `@Secure` a nivel de método rechazarían cada uno una
   petición no autorizada, y cuál se dispara primero.
5. **Sigue el rastro de un intercambio de proveedor.** Sin cambiar ningún código `@Secure` ni
   `SecurityWebFilterChain`, lista qué cambiarías para mover la identidad de Lumen de Keycloak a AWS
   Cognito: qué valor de `firefly.idp.provider`, qué dependencia de adaptador. Confirma contra el
   Apéndice B que el controlador y el aspecto quedan intactos —esa invariancia es el sentido del
   puerto.

## Adónde ir ahora

Ahora tienes peticiones que están autenticadas, autorizadas y acotadas por tenant —pero un endpoint
de lectura como `getApplication` aún golpea la capa de dominio en cada llamada, incluso cuando nada
ha cambiado—. El Capítulo 20 se vuelca en el **cacheo**: cómo el puerto reactivo `CacheAdapter` de
Firefly permite que las capas de experiencia y de consulta sirvan lecturas repetidas desde una capa
local de Caffeine (y un L2 distribuido opcional) sin un SDK de proveedor en tu código —el mismo
patrón hexagonal que acabas de ver para la identidad, aplicado a la velocidad.
