Hasta ahora, cada capítulo ha dejado pasar una petición directamente hasta el manejador.
Eso estaba bien mientras aprendías la pila reactiva, pero una plataforma de préstamos no
puede salir a producción así: crear una solicitud de préstamo es un acto privilegiado, y
leer una expone la vida financiera de un cliente. La capa de experiencia —el BFF orientado
al canal que conociste en el Capítulo 17, el que da la cara al flujo en vivo de envío
`exp → domain → core`— es donde Lumen decide *quién puede hacer qué*. Este capítulo muestra
cómo lo hace, sobre WebFlux, con dos piezas que trabajan juntas.

La primera pieza es Spring convencional: una `SecurityWebFilterChain` reactiva en el borde
de la canalización HTTP. La segunda es de Firefly: una anotación `@Secure` declarativa sobre
el método del controlador, aplicada no por la cadena de filtros sino por un aspecto AOP del
framework que lee el principal autenticado y el tenant de un contexto de aplicación. Dividir
la autorización de esta forma —reglas de transporte gruesas en el filtro, comprobaciones de
permisos finas en el método— es el modelo que fomenta el starter de aplicación, y es lo que
permite que el mismo controlador se ejecute sin cambios tanto si la identidad procede de
Keycloak, de Cognito o de un directorio interno.

Vamos a recortar el `ApplicationController` real y su `WebSecurityConfig` de `exp-lending`,
seguir cómo se aplica `@Secure` (y cómo una propiedad desactiva esa aplicación —la misma
propiedad que te permite alcanzar el BFF en `localhost:8080` durante una ejecución local),
esbozar el `AppContext` que transporta el principal y terminar en el puerto de identidad
agnóstico del proveedor que cataloga el Apéndice B. Después ejecutamos el test de rebanada
que ejercita los métodos protegidos con la aplicación desactivada. Al final serás capaz de
leer cada decisión de autorización que toma Lumen, nombrar la propiedad que la gobierna y
explicar por qué las mismas anotaciones protegen producción a la vez que no estorban a un
test con `WebTestClient`.

## Por qué la autorización vive en dos lugares a la vez

Antes del código, la forma. Una petición para crear una solicitud de préstamo cruza dos
puntos de control en Lumen, y responden a preguntas distintas.

El **punto de control de transporte** —la `SecurityWebFilterChain`— se ejecuta primero, en
el borde mismo de la canalización HTTP, antes del enrutamiento. Responde a preguntas de
*nivel de transporte*: ¿es pública esta ruta?, ¿lleva este intercambio un token válido?,
¿debería aplicarse el diálogo de autenticación del navegador o el token CSRF? Trabaja con
URLs y verbos HTTP, no sabe nada de tu dominio y o bien rechaza el intercambio o bien lo deja
continuar hacia un controlador.

El **punto de control de método** —el aspecto `@Secure`— se ejecuta después, una vez que el
enrutamiento ha elegido un manejador, y responde a una pregunta de *dominio*: ¿posee este
llamador autenticado `lending:application:create`? Trabaja con cadenas de permisos con
ámbito, sabe exactamente qué operación de negocio está a punto de ejecutarse y o bien procede
hacia tu manejador o bien cortocircuita con un `403`.

Mantenerlos separados es deliberado. La cadena de filtros es el lugar adecuado para las
reglas generales que aplican a franjas enteras de URLs; el método es el lugar adecuado para
la regla fina "esta operación necesita este permiso", que pertenece *junto a* la operación.
Lumen se apoya con fuerza en la segunda: su cadena de filtros es permisiva a propósito, y la
decisión real ocurre en el método. Las dos secciones que siguen abordan cada punto de control
por turnos.

## Spring Security en WebFlux es una cadena de filtros, no un filtro de servlet

Si has protegido una aplicación Spring MVC, recurriste a `WebSecurityConfigurerAdapter` o a
una `SecurityFilterChain` construida sobre la pila de `Filter` de servlet. WebFlux es un
runtime distinto —no hay servlet, no hay `SecurityContext` ligado a `ThreadLocal`— de modo
que Spring Security expone una API paralela y reactiva. Anotas una clase de configuración con
`@EnableWebFluxSecurity` y publicas un bean `SecurityWebFilterChain` construido a partir de
un `ServerHttpSecurity`. Aquí está la de Lumen, entera.

::: listing exp-lending/src/main/java/com/firefly/lumen/exp/config/WebSecurityConfig.java | Listado 19.1 — la cadena de filtros de seguridad reactiva para el BFF
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

Lee lo que esta cadena hace en realidad, porque la sorpresa está en lo que *no* hace.
Desactiva cuatro cosas que Spring Security activa por defecto en cuanto
`spring-boot-starter-security` aterriza en el classpath —y aterriza, porque
`fireflyframework-starter-application` lo arrastra de forma transitiva—. (Puedes confirmarlo:
nada en el `pom.xml` de `exp-lending` declara `spring-boot-starter-security` directamente, y
sin embargo las cuatro llamadas a `disable` solo compilan porque los tipos WebFlux de Spring
Security están presentes.) HTTP Basic, el login por formulario, CSRF y el logout son los
valores por defecto adecuados para una aplicación web renderizada en servidor y exactamente
equivocados para un BFF JSON sin estado: HTTP Basic abriría un diálogo de autenticación del
navegador, CSRF rechazaría tu `POST` sin un token, el login por formulario redirigiría a un
cliente de API a una página HTML de inicio de sesión. Desactivarlos limpia ese ruido.

A continuación, `authorizeExchange` permite todo —las rutas documentadas de forma explícita
(Swagger UI, el documento OpenAPI, los webjars que arrastra y los endpoints operativos de
Actuator) y `anyExchange().permitAll()` para el resto—. Eso parece alarmante hasta que ves la
segunda mitad del diseño. Esta cadena de filtros está deliberadamente *abierta* en la capa de
transporte porque la autorización en Lumen ocurre una capa más adentro, en el método, a
través de `@Secure`. La tarea de la cadena de filtros aquí es impedir que los valores por
defecto de Spring Security interfieran; la *decisión* se delega.

¿Por qué permitir las rutas de Swagger y de Actuator *explícitamente* si el comodín ya
permite todo? Dos razones, ambas sobre lo que ocurre cuando más adelante endurezcas la
cadena. Primero, la lista explícita documenta la intención: estas rutas son públicas *por
diseño*, no por accidente, y sobreviven a una edición futura que cambie `anyExchange()` por
`authenticated()`. Segundo, es la forma segura de copiar: cuando un despliegue real
intercambie la última línea por una regla de token bearer, la sonda de salud y la
documentación siguen siendo alcanzables sin que nadie tenga que acordarse de volver a
añadirlas. Un despliegue distinto podría endurecer esta cadena para exigir un token bearer en
`anyExchange()` y validar un JWT —el hueco está justo ahí, y el Ejercicio 4 te guía para
esbozarlo—, pero Lumen mantiene las reglas de transporte permisivas y pone la comprobación
real en el manejador.

!!! spring "Equivalente en Spring"
    Cada tipo de este listado es Spring Security para WebFlux de serie:
    `@EnableWebFluxSecurity`, `ServerHttpSecurity`, `SecurityWebFilterChain`, los lambdas
    `CsrfSpec`/`HttpBasicSpec`/`FormLoginSpec`/`LogoutSpec` y `authorizeExchange`. No hay
    ningún tipo de Firefly en esta página. Si has escrito una configuración de seguridad
    reactiva, has escrito esta. Lo que Firefly añade *no* está aquí —es el modelo `@Secure` a
    nivel de método que presenta la siguiente sección, que cabalga sobre esta cadena en lugar
    de reemplazarla—. El modelo mental del Spring de servlet se traslada con una sola
    sustitución: `SecurityFilterChain` se convierte en `SecurityWebFilterChain`, y
    `HttpSecurity` se convierte en `ServerHttpSecurity`.

!!! note "Término clave — `SecurityWebFilterChain`"
    El equivalente WebFlux de una `SecurityFilterChain` de servlet. Es una canalización de
    `WebFilter` que se ejecuta sobre el bucle de eventos de Reactor, construida de forma
    fluida a partir de un `ServerHttpSecurity`. Como es reactiva, el principal que resuelve
    vive en el `Context` de Reactor, no en un `ThreadLocal` —razón por la cual una búsqueda
    bloqueante con `SecurityContextHolder` no funciona en esta pila, y por la cual el propio
    contexto de Firefly (más abajo) se propaga a través de la cadena reactiva en su lugar—.

!!! note "Término clave — `permitAll` frente a `authenticated`"
    Las dos reglas terminales en las que acaba una cláusula `pathMatchers(...)` o
    `anyExchange()`. `permitAll()` deja pasar el intercambio sin importar la autenticación; *no*
    es "sin seguridad" —es "la capa de transporte no controla esta ruta" (la capa de método
    todavía podría hacerlo)—. `authenticated()` exige una autenticación correcta para que el
    intercambio proceda siquiera. Lumen usa `permitAll()` en todas partes precisamente porque
    su barrera está una capa más adentro.

## La comprobación declarativa: `@Secure` sobre el método

Ahora el controlador. Es un `@RestController` reactivo corriente —dos métodos, un `POST` para
crear una solicitud y un `GET` para leerla— pero cada método lleva una anotación `@Secure` de
Firefly que declara el permiso que el llamador debe poseer.

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
`lending:application:create`, `getApplication` requiere `lending:application:read`. Las
cadenas de permisos tienen ámbito —`resource:action`, con espacio de nombres por dominio— de
modo que una definición de rol en tu proveedor de identidad puede conceder
`lending:application:read` sin repartir accidentalmente derechos de creación. Esa convención
de nombres no la impone el framework —`@Secure` acepta cualquier cadena— pero adoptar
`domain:resource:action` en toda la flota es lo que permite que un único catálogo de roles
cubra cada servicio sin colisiones.

La `description` no es decoración: alimenta el registro de seguridad de endpoints que el
framework construye al arrancar —el `EndpointSecurityRegistry` nombrado en el Javadoc del
controlador— que es cómo una pantalla de back-office o una exportación de auditoría pueden
listar cada método protegido y el permiso que exige sin leer el código fuente. Cada método
`@Secure` aporta una entrada a ese registro a medida que la aplicación arranca, de modo que el
registro es el inventario legible por máquina de "qué está protegido y por qué".

Fíjate en el import del archivo fuente: `@Secure` es
`org.fireflyframework.common.application.security.annotation.Secure`, una anotación del
framework, no de Spring Security. La seguridad de métodos de Spring usa
`@PreAuthorize("hasAuthority('...')")` con una expresión SpEL; el `@Secure` de Firefly toma
una lista tipada de cadenas de permisos y se aplica mediante un aspecto del framework en lugar
del `MethodSecurityInterceptor` de Spring. Ambos resuelven el mismo problema; la versión de
Firefly está afinada para el mundo multi-tenant, reactivo y de cadenas de permisos en el que
vive la plataforma —no hay SpEL que parsear en tiempo de ejecución, los permisos son cadenas
sencillas que el registro puede enumerar, y la misma anotación sirve tanto para la
comprobación en tiempo de ejecución como para el inventario de arranque—.

¿Por qué anotar el *método* en lugar de la clase? Porque un único controlador suele mezclar
operaciones de sensibilidad distinta —un resumen de salud público junto a una escritura
privilegiada— y las anotaciones a nivel de método permiten que coexistan sin dividir el
controlador en clases "protegidas" y "abiertas". Aquí ambos métodos resultan estar protegidos,
pero cada uno declara su propio permiso, de modo que los derechos de creación y de lectura son
independientes.

!!! note "Término clave — `@Secure`"
    Una anotación de autorización a nivel de método de Firefly. Declaras los `permissions` (y
    opcionalmente roles) que un llamador debe poseer; el `SecurityAspect` del framework
    intercepta la llamada y los comprueba contra el principal autenticado transportado en el
    contexto de aplicación. Es declarativa —sin `if (user.hasPermission(...))` en tu
    manejador— y vive sobre el *método*, de modo que el mismo controlador puede mezclar
    endpoints públicos y protegidos sin repartirlos por varias clases. La `description`
    opcional alimenta el `EndpointSecurityRegistry` de arranque.

!!! note "Término clave — cadena de permiso con ámbito"
    Un permiso expresado como `domain:resource:action` (aquí `lending:application:create`). El
    ámbito separado por dos puntos permite que el rol de un proveedor de identidad conceda una
    porción estrecha —lectura sin creación, este recurso pero no aquel— y permite que el
    registro del framework agrupe los permisos por dominio. Es una convención, no un parser:
    `@Secure` compara las cadenas literalmente, de modo que la estructura es para *tu* diseño
    de roles, no para el emparejamiento del framework.

## Cómo se aplica `@Secure`: el SecurityAspect

`@Secure` es una anotación, lo que significa que no hace nada por sí sola —algo tiene que
*leerla* y actuar—. Ese algo es el `SecurityAspect` del starter de aplicación, un aspecto AOP
de Spring corriente que envuelve cada método `@Secure`. Cuando se invoca un método protegido,
el aspecto se ejecuta primero: localiza el contexto de ejecución de la aplicación, resuelve a
partir de él los permisos del llamador, los compara con el conjunto requerido de la anotación
y o bien procede hacia tu manejador o bien cortocircuita con un fallo de autorización que la
capa web renderiza como un `403`. El cuerpo de tu manejador nunca se ejecuta si la
comprobación falla —el aspecto es una barrera delante del método, no un gancho dentro de él—.

Esta es la misma maquinaria AOP que has visto en otras partes del framework: un pointcut que
casa con la anotación, advice que se ejecuta alrededor del join point casado. Lo que merece la
pena interiorizar es el *orden*. La cadena de filtros de transporte se ejecutó en el borde;
este aspecto se ejecuta después de que Spring WebFlux haya vinculado la petición a un método
manejador pero antes de que el cuerpo del método se ejecute. Así que, para cuando el aspecto
dispara, el enrutamiento ya ha elegido `createApplication` sobre `getApplication`, y el
aspecto sabe exactamente qué conjunto de permisos exigir.

El aspecto respeta una única propiedad que gobierna si aplica algo en absoluto, y esa
propiedad es el eje de todo este capítulo.

### El interruptor de aplicación: `firefly.application.security.enabled`

El `SecurityAspect` lee `firefly.application.security.enabled`. En producción es `true` y el
aspecto aplica cada regla `@Secure`. Ponla en `false` y el aspecto degrada a un no-op
deliberado: sigue interceptando el método —de modo que la anotación se ejercita de verdad—
pero registra que la seguridad está desactivada y procede directamente al manejador sin
comprobar permisos. Puedes verlo ocurrir. Con la propiedad en `false`, el servicio en
ejecución imprime, por cada llamada interceptada:

```text
{"timestamp":"2026-06-17T11:46:21.018+0000","message":"Security is disabled, allowing access","logger":"o.f.c.application.aop.SecurityAspect","level":"DEBUG"}
```

Esa única línea es todo el comportamiento del interruptor hecho visible: el aspecto se
ejecutó (de modo que `@Secure` está vivo), vio la aplicación desactivada y dejó pasar la
llamada. Esto no es una curiosidad solo de tests —es cómo funciona la *ejecución local* de
Lumen—. Tanto el perfil de test como el perfil ejecutable ponen la propiedad en `false` para
que puedas manejar el BFF sin levantar un proveedor de identidad. Aquí está el perfil de test:

::: listing exp-lending/src/test/resources/application.yml | Listado 19.3 — desactivar la aplicación para el test de rebanada, sin cambiar el controlador
firefly:
  application:
    security:
      enabled: false
  cqrs:
    enabled: false
:::

Y aquí está el mismo interruptor en el perfil *ejecutable*, que es lo que permite que un
`curl` a `localhost:8080` alcance el BFF durante el flujo en vivo `exp → domain → core` del
Capítulo 17:

```text
# exp-lending/src/main/resources/application.yml — runnable profile
firefly:
  application:
    security:
      enabled: false
```

Este es el detalle honesto e importante de todo el capítulo. Ninguno de los perfiles elimina
`@Secure`, simula un principal ni mockea el aspecto. Mantienen la anotación real y el
`SecurityAspect` real, y desactivan solo la *aplicación* mediante configuración. El aspecto se
ejecuta, ve que la propiedad está desactivada, registra la línea de arriba y procede. El test
de rebanada mantiene los métodos protegidos alcanzables para poder afirmar el mapeo y los
códigos de estado; la ejecución local mantiene el BFF alcanzable para que puedas ejercitar la
saga a mano. Misma propiedad, misma razón: quitar la barrera de la puerta mientras todavía
estás construyendo la casa.

¿Qué cambiaría con la propiedad en `true`? El aspecto dejaría de dejar pasar las llamadas y
empezaría a buscar un contexto de ejecución de la aplicación del que leer permisos. Sin un
contexto suministrado —como en el test de rebanada, que maneja `WebTestClient` sin uno— el
aspecto no tiene nada contra lo que comprobar los permisos requeridos y rechaza la llamada. Ese
es exactamente el comportamiento de producción que quieres, y exactamente el comportamiento
que el test y la ejecución local sortean para poder centrarse en la lógica de negocio y el
flujo entre capas.

!!! warning "`security.enabled=false` es un mando de local/test"
    Desactivar la aplicación es la jugada correcta para un test de rebanada que afirma el
    mapeo y los códigos de estado, y para una ejecución local donde quieres ejercitar el BFF a
    mano sin un IDP. Es la jugada equivocada en cualquier lugar al que pueda llegar un llamador
    real. Mantenla acotada a `src/test/resources` y al perfil ejecutable local como hace Lumen.
    Sacar un servicio *desplegado* con `firefly.application.security.enabled=false` vuelve
    decorativa cada anotación `@Secure` —el aspecto intercepta, registra "Security is disabled,
    allowing access" y deja pasar a todo el mundo—. El valor por defecto del framework es
    `true` precisamente por esta razón; nunca lleves el override `false` a un perfil de
    producción.

!!! spring "Equivalente en Spring"
    El `@EnableMethodSecurity` de Spring junto con `@PreAuthorize` también se aplica mediante un
    interceptor AOP —el mecanismo tiene la misma forma—. La diferencia de Firefly es el
    *interruptor*: hay una única propiedad para toda la flota que activa o desactiva la
    autorización de métodos, de modo que los tests de cada servicio (y las ejecuciones locales)
    desactivan la aplicación de la misma manera en lugar de que cada uno invente un baile de
    `@WithMockUser` o una configuración de seguridad de test a medida. Sigues usando seguridad
    de métodos basada en AOP; Firefly estandariza las costuras a su alrededor para que el
    interruptor de encendido/apagado sea idéntico en toda la flota.

## `AppContext`: el principal y el tenant, transportados de forma reactiva

El aspecto necesita saber *quién* está llamando y *a qué tenant* pertenece. En la pila de
servlet eso vive en un `SecurityContextHolder` de `ThreadLocal`; en la pila reactiva un
`ThreadLocal` es una trampa, porque Reactor salta de hilo entre operadores y el valor no lo
seguiría. (Este es el mismo peligro de salto de hilo que el Capítulo 1 señaló y que la línea
"Reactor automatic context propagation enabled" del log de arranque aborda para el trazado.)
La respuesta de Firefly es un objeto de contexto de aplicación —llamémoslo `AppContext` (su
mitad de seguridad es el `AppSecurityContext`)— que viaja *con* la petición a través del
`Context` de Reactor, de modo que el principal autenticado y el id de tenant están disponibles
en cualquier punto de la cadena reactiva, en cualquier hilo.

El starter de aplicación puebla este contexto en el borde a partir de cualquier autenticación
que llevara la petición —un token bearer validado, una cabecera de un gateway aguas arriba— y
el `SecurityAspect` lee de él los permisos del llamador para satisfacer `@Secure`. La forma que
pasarías a un método protegido, o leerías dentro de uno, tiene este aspecto:

```java
// Illustrative: the application context carrying principal and tenant through a reactive call.
public Mono<ApplicationDetailDTO> createApplication(AppContext ctx, CreateApplicationRequest request) {
    String userId   = ctx.security().getPrincipalId();   // who is acting
    String tenantId = ctx.security().getTenantId();       // which tenant they belong to
    // @Secure already checked ctx.security().getPermissions() against the required set
    return applicationService.createApplication(tenantId, request);
}
```

Eso es ilustrativo —el controlador de rebanada de Lumen no toma el contexto como parámetro, lo
cual es exactamente por qué la aplicación está desactivada en lugar de satisfecha: sin
contexto que leer, un aspecto que aplique no tendría nada contra lo que comprobar—. En un
despliegue completamente cableado el contexto se enhebra (o se resuelve desde el `Context` de
Reactor), el aspecto lo encuentra, y la comprobación de permisos tiene algo contra lo que
comprobar. El id de tenant importa tanto como el principal: una plataforma de préstamos es
multi-tenant, y la misma llamada `getApplication` debe acotar su lectura al tenant del llamador
para que el operador de un banco no pueda recuperar el préstamo de otro. El contexto es cómo esa
frontera de tenant sigue a la petición sin un parámetro en cada método, y por qué la tarea de
`@Secure` termina en "¿puede actuar este llamador?" mientras que el acotamiento por tenant
ocurre en el servicio contra `ctx.security().getTenantId()`.

!!! note "Término clave — `AppContext` / `AppSecurityContext`"
    El objeto con ámbito de petición que la capa de aplicación transporta a través de una
    llamada reactiva, que contiene el id del principal autenticado, el id de tenant, los
    permisos y roles del llamador, y los metadatos de correlación. `AppSecurityContext` es su
    vista centrada en la seguridad. Es el reemplazo reactivo-seguro de `SecurityContextHolder`:
    poblado una vez en el borde, propagado a través del `Context` de Reactor, y leído por el
    `SecurityAspect` (y por tus manejadores) aguas abajo. Es la misma idea que el
    `ExecutionContext` de CQRS del Capítulo 10, acotado a la capa de experiencia y centrado en
    la identidad.

!!! spring "Equivalente en Spring"
    Spring Security WebFlux expone el principal a través de
    `ReactiveSecurityContextHolder.getContext()`, que lee del `Context` de Reactor —la
    primitiva reactiva correcta—. El `AppContext` de Firefly se apoya en esa misma primitiva
    pero transporta más que un principal: tenant, permisos como cadenas con ámbito y
    correlación, en un único objeto que comparte toda la capa de aplicación. Todavía puedes
    alcanzar el `ReactiveSecurityContextHolder` de Spring; `AppContext` es el sobre estándar de
    la flota a su alrededor, de modo que un manejador lee la identidad de la misma forma en cada
    servicio en lugar de reensamblarla a partir de un `Principal` y una cabecera a medida.

## `@RequireContext`: exigir que el contexto esté presente

`@Secure` responde a "¿puede este llamador hacer esto?". Una anotación complementaria,
`@RequireContext`, responde a una pregunta previa: "¿hay algún contexto autenticado en
absoluto?". La pones sobre un método (o clase) que no debe ejecutarse de forma anónima —le dice
al aspecto que rechace la llamada cuando no haya ningún `AppContext` presente, antes de
cualquier comprobación de permisos—. Es la manera explícita de decir "este endpoint nunca es
público", y se empareja de forma natural con `@Secure`:

```java
// Illustrative: require an authenticated context, then a specific permission.
@RequireContext
@Secure(permissions = {"lending:application:read"})
public Mono<ResponseEntity<ApplicationDetailDTO>> getApplication(AppContext ctx, @PathVariable UUID id) {
    return applicationService.getApplication(ctx.security().getTenantId(), id).map(ResponseEntity::ok);
}
```

¿Por qué querrías ambas? `@Secure` ya hace fracasar a un llamador que carece del permiso, así
que en el caso común un contexto ausente hace fracasar la comprobación de permisos de todos
modos. La diferencia es *la intención y la claridad del error*: `@RequireContext` convierte "no
hay identidad" en un rechazo distinto y de primera clase ("eres anónimo") separado de "permiso
incorrecto" ("eres conocido pero no estás autorizado"). En un endpoint que nunca debe ser
alcanzable de forma anónima —piensa en una operación de movimiento de fondos— declarar
`@RequireContext` documenta esa invariante en el código fuente y produce el fallo más claro, en
lugar de confiar en que la lista de permisos atrape de forma incidental el caso anónimo.

La rebanada de Lumen usa `@Secure` en solitario y se apoya en el interruptor de local/test para
saltarse la aplicación, de modo que `@RequireContext` no aparece en el reactor —trátala como la
compañera de "cómo funciona" a la que recurres cuando un endpoint debe fallar de forma dura ante
una identidad ausente—. El Capítulo 17 cubre la historia más amplia del `AppContext` para la
capa de experiencia; aquí el punto es simplemente que las dos anotaciones se componen: exige el
contexto, y luego restringe lo que puede hacer.

## De dónde viene la identidad: el puerto IDP agnóstico del proveedor

Todo lo anterior daba por supuesto que llegaba un principal autenticado. *Producir* ese
principal —iniciar sesión a un usuario, validar un token, refrescar una sesión, consultar los
roles de un usuario— es la tarea de un proveedor de identidad, y Firefly lo mantiene tras un
puerto exactamente igual que cualquier otra preocupación de proveedor en la plataforma. El core
de IDP define una única interfaz `IdpAdapter` (login, refresco, introspección de tokens, CRUD
de usuarios, MFA, sesiones), y un adaptador concreto se elige mediante una única propiedad:

```yaml
# Illustrative: pick an identity provider with one property; the adapter jar registers the rest.
firefly:
  idp:
    provider: keycloak   # keycloak | cognito | azure-ad | internal-db
```

Cambiar de Keycloak a AWS Cognito es un intercambio de dependencia más esta línea —tu
`SecurityWebFilterChain`, tus anotaciones `@Secure` y tus manejadores conscientes de
`AppContext` no cambian, porque dependen de la noción de principal y permisos del *puerto*,
nunca de un SDK de proveedor—. Ese es el mismo patrón hexagonal que prometió el Capítulo 1 y que
el intercambio de transporte EDA del Capítulo 11 demostró, aplicado a la identidad: el puerto es
el contrato estable, el adaptador es el detalle reemplazable, y un `@ConditionalOnProperty`
sobre el nombre del proveedor decide qué adaptador cablea el contexto.

Sé preciso sobre lo que esta build ejecuta y lo que no: el core de IDP **no** lo ejercita la
rebanada de este capítulo. El test desactiva la aplicación en lugar de acuñar un token real, y
la ejecución local hace lo mismo, de modo que no hay ningún adaptador en el classpath y no se
contacta a ningún proveedor. Trata la tabla de proveedores como *dónde encaja* —la costura que
rellena la plataforma real— no como algo que este reactor arranque. Cuando sí lo cablees, el
adaptador es lo que puebla el `AppContext` que el `SecurityAspect` lee después; la cadena de
"llega el token" a "permiso comprobado" pasa a través del puerto, no a su alrededor.

!!! note "Término clave — puerto IDP (`IdpAdapter`)"
    Una única interfaz de la que depende la plataforma para todas las operaciones de identidad
    —login, refresco, introspección, gestión de usuarios y grupos, MFA, manejo de sesiones—.
    Cada proveedor (Keycloak, Cognito, Microsoft Entra ID, una base de datos interna) entrega un
    adaptador que se activa por `@ConditionalOnProperty` cuando `firefly.idp.provider` lo
    nombra. Tu código de seguridad apunta al puerto; la propiedad elige la implementación. El
    Apéndice B lista los adaptadores y las dependencias exactas.

!!! spring "Equivalente en Spring"
    En Spring puro cablearías `spring-security-oauth2-resource-server` a un emisor, o un
    adaptador de Keycloak, directamente en tu configuración de seguridad —y cambiar de proveedor
    significa editar esa configuración en cada servicio—. El puerto IDP de Firefly convierte el
    proveedor en configuración: el cableado del servidor de recursos o del adaptador vive en el
    jar del adaptador elegido, seleccionado por `firefly.idp.provider`, de modo que el
    intercambio es para toda la flota y sin código. Consulta el Apéndice B para el catálogo
    completo de adaptadores.

## Ejecútalo

El test de rebanada arranca la aplicación `exp-lending` real —el `ApplicationController` con sus
anotaciones `@Secure`, la cadena de filtros `WebSecurityConfig`, el `SecurityAspect` y el
`GlobalExceptionHandler` de `fireflyframework-web`— y la maneja a través de `WebTestClient`,
satisfaciendo la costura del SDK con un bean `StubLoanOriginationDomainClient` en memoria de
modo que no hay servicio de dominio ni Docker. La aplicación está desactivada mediante el
`firefly.application.security.enabled=false` del perfil de test, de modo que los métodos
protegidos se ejecutan mientras el `SecurityAspect` cortocircuita con "Security is disabled,
allowing access". Desde el directorio `samples/lumen-lending`:

```text
mvn -q -pl exp-lending test
```

Deberías ver pasar los tests del módulo:

```text
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in com.firefly.lumen.exp.web.ApplicationControllerTest
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Los cuatro tests de `ApplicationControllerTest` son los que importan aquí. Hacen `POST` de una
solicitud válida y afirman `201` con el detalle mapeado, hacen un viaje de ida y vuelta de
crear-y-luego-`GET`, afirman que un id desconocido devuelve un problem detail `404` (su cuerpo
lleva `APPLICATION_NOT_FOUND`, renderizado por el `GlobalExceptionHandler`) y rechazan un
importe cero con `400` —y cada una de esas peticiones pasa por un método `@Secure` cuyo aspecto
intercepta, registra y (con la aplicación desactivada) procede—. Los otros cinco tests del
módulo ejercitan el mapeo del servicio y el contexto de aplicación. La build demuestra dos cosas
a la vez: las anotaciones de autorización están cableadas y vivas en los manejadores reales, y
desactivar la aplicación es un cambio de configuración, no un cambio de código.

Una nota honesta sobre el caso de creación: el `StubLoanOriginationDomainClient` de la rebanada
devuelve una solicitud en `DRAFT`, de modo que el test de rebanada afirma `status == "DRAFT"`.
Esa es la respuesta del *stub*. En la pila completa en vivo del Capítulo 17, el mismo `POST`
fluye `exp → domain → core`, la saga escribe en el sistema de registro, y el BFF devuelve
`SUBMITTED` —la forma que captura el README—. La rebanada prueba el mapeo y la seguridad del BFF
de forma aislada; la ejecución en vivo demuestra el estado entre capas. Ambas son reales;
difieren porque una se detiene en la costura y la otra la cruza.

!!! tip "Punto de control"
    Ejecuta el comando de arriba y confirma `Tests run: 9, Failures: 0` para el módulo (con `4`
    en `ApplicationControllerTest`). Después ejecútalo con la aplicación activada —
    `mvn -q -pl exp-lending -Dfirefly.application.security.enabled=true test`— y observa cómo
    las llamadas protegidas fallan la autorización: sin un `AppContext` suministrado, el
    `SecurityAspect` no tiene nada contra lo que comprobar los permisos requeridos y rechaza la
    petición. Restaura el valor por defecto del test y vuelve a pasar. Acabas de ver al
    interruptor hacer su única tarea, desde ambos lados —y ver por qué el valor por defecto
    `false` en los perfiles de test y local es lo que mantiene el BFF alcanzable—.

## Lo que has aprendido {.recap}

- La autorización en Lumen vive en **dos puntos de control**: una `SecurityWebFilterChain` a
  nivel de transporte en el borde HTTP, y un aspecto `@Secure` a nivel de método tras el
  enrutamiento. El filtro responde a "¿se deja pasar esta ruta/intercambio?"; el aspecto
  responde a "¿puede este llamador ejecutar esta operación?". Mantenerlos separados pone las
  reglas generales en el filtro y las reglas finas junto a la operación.
- Spring Security en WebFlux es una **`SecurityWebFilterChain`** reactiva, construida a partir
  de `ServerHttpSecurity` bajo `@EnableWebFluxSecurity` —no la pila de filtros de servlet—. La
  cadena de Lumen desactiva los valores por defecto hostiles a la ausencia de estado (HTTP
  Basic, login por formulario, CSRF, logout) y permite todos los intercambios, *delegando* la
  autorización a la capa de método.
- **`@Secure`** es la autorización declarativa a nivel de método de Firefly: una lista tipada de
  cadenas de permisos con ámbito (`lending:application:create`) sobre el manejador, aplicada por
  el **`SecurityAspect`** (AOP) del starter de aplicación, no por el `@PreAuthorize` de Spring.
  Su `description` alimenta el **`EndpointSecurityRegistry`** de arranque.
- Una propiedad, **`firefly.application.security.enabled`**, activa la aplicación (el valor por
  defecto del framework) o la desactiva. Con ella en `false`, el aspecto sigue interceptando el
  método `@Secure` pero registra "Security is disabled, allowing access" y se salta la
  comprobación —que es cómo tanto el test de rebanada *como* la ejecución local mantienen las
  anotaciones reales mientras alcanzan el BFF—.
- **`AppContext`/`AppSecurityContext`** transporta el principal autenticado, el id de tenant y
  los permisos a través de la cadena reactiva mediante el `Context` de Reactor —el reemplazo
  reactivo-seguro de `SecurityContextHolder`— y **`@RequireContext`** exige que esté presente
  antes de que un método se ejecute, separando "anónimo" de "prohibido".
- La identidad se produce tras un puerto **`IdpAdapter`** agnóstico del proveedor, seleccionado
  por `firefly.idp.provider` (Keycloak, Cognito, Entra ID, BD interna) —un intercambio de una
  línea referenciado en el Apéndice B, no ejercitado por esta rebanada—.
- Una build de módulo que pasa (`Tests run: 9, Failures: 0`, con `4` en
  `ApplicationControllerTest`) en la que cada llamada del controlador atraviesa un aspecto
  `@Secure` real con la aplicación desactivada por configuración.

## Pruébalo tú mismo {.exercises}

1. **Demuestra que el aspecto es real.** Ejecuta `mvn -pl exp-lending test` en modo debug y lee
   la salida en busca de la línea de log del `SecurityAspect` (`Security is disabled, allowing
   access`, del logger `o.f.c.application.aop.SecurityAspect`). Encuéntrala para una llamada a
   `createApplication` y otra a `getApplication`, y explica en una frase por qué esa línea
   significa que la anotación se ejercita pero no se aplica.
2. **Acciona el interruptor.** Ejecuta el módulo con
   `-Dfirefly.application.security.enabled=true` y confirma que los tests protegidos ahora
   fallan la autorización. Lee el fallo, y luego restaura el valor por defecto del test. ¿Qué
   línea de `src/test/resources/application.yml` es el único mando que cambiaste —y qué línea de
   `src/main/resources/application.yml` es su gemela que mantiene la *ejecución local*
   alcanzable?
3. **Añade un permiso.** Da a `getApplication` un segundo permiso requerido (por ejemplo
   `lending:application:read` *y* `lending:tenant:member`) extendiendo el array `permissions` de
   `@Secure`. Vuelve a ejecutar el test de rebanada —sigue pasando, porque la aplicación está
   desactivada— y luego escribe una frase sobre qué tendría que ser cierto del `AppContext` del
   llamador para que pasara con la aplicación *activada*.
4. **Endurece la cadena de filtros.** En `WebSecurityConfig`, cambia el `.anyExchange().permitAll()`
   final por `.anyExchange().authenticated()` y añade un servidor de recursos de token bearer
   (`http.oauth2ResourceServer(...)`). Esboza —sin necesidad de ejecutar un IDP real— cómo esta
   comprobación a nivel de transporte y la `@Secure` a nivel de método rechazarían cada una una
   petición no autorizada, y cuál dispara primero. (Pista: vuelve a leer "Por qué la autorización
   vive en dos lugares a la vez".)
5. **Sigue un intercambio de proveedor.** Sin cambiar ningún código de `@Secure` ni de
   `SecurityWebFilterChain`, lista lo que cambiarías para mover la identidad de Lumen de Keycloak
   a AWS Cognito: qué valor de `firefly.idp.provider`, qué dependencia de adaptador. Confirma
   contra el Apéndice B que el controlador y el aspecto quedan intactos —esa invariancia es el
   sentido del puerto— y nombra el objeto que el adaptador elegido puebla y que el
   `SecurityAspect` lee después.
6. **Reconcilia los dos estados.** El test de rebanada afirma que la llamada de creación devuelve
   `DRAFT`, pero la ejecución en vivo `exp → domain → core` del README devuelve `SUBMITTED`. Lee
   `StubLoanOriginationDomainClient` y explica en dos frases por qué la rebanada y la pila en vivo
   discrepan —y por qué ambas son honestas—.

## Adónde ir ahora

Ahora tienes peticiones que están autenticadas, autorizadas y acotadas por tenant —pero un
endpoint de lectura como `getApplication` todavía golpea la capa de dominio en cada llamada,
incluso cuando nada cambió—. El Capítulo 20 se vuelca en el **cacheo**: cómo el puerto reactivo
`CacheAdapter` de Firefly permite a las capas de experiencia y de consulta servir lecturas
repetidas desde una capa local Caffeine (y una L2 distribuida opcional) sin un SDK de proveedor
en tu código —el mismo patrón hexagonal que acabas de ver para la identidad, aplicado a la
velocidad—.
