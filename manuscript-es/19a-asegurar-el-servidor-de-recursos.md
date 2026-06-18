El Capítulo 19 protegió el BFF de la capa de experiencia de Lumen con un filtro de transporte
permisivo y un aspecto declarativo `@Secure`, y luego se apoyó en una propiedad —
`firefly.application.security.enabled=false`— para quitar el cerrojo de la puerta mientras aún
estabas construyendo la casa. Ese modelo enseñó la *forma* de la autorización, pero dejó dos
huecos honestos abiertos de par en par: nada en la página validaba la *firma* de un token, y la
postura por defecto era *abierta al fallo* —un método sin argumento de contexto pasaba sin
problemas—. Esos huecos son exactamente los que cierra la nueva plataforma
`fireflyframework-security`. Este capítulo reconstruye la seguridad de Lumen sobre ella: un
**servidor de recursos seguro por defecto** que valida la firma de cada JWT, mapea sus claims a
authorities, deniega cualquier cosa no permitida explícitamente y expone una única costura
pequeña —`TokenValidationPort`— donde se enchufa un proveedor de identidad real.

La diferencia respecto al Capítulo 19 es la diferencia entre *decorativo* y *aplicado*. Ya no
hay un flag `security.enabled` que apagar; el framework lo eliminó. En el momento en que el
starter del servidor de recursos está en el classpath, cada ruta exige un token bearer con la
firma validada salvo que la declares pública, un token falsificado vuelve con `401` y una
denegación de la política de autorización vuelve con `403` —con cabeceras de seguridad
endurecidas en cada respuesta, incluidas las públicas—. Levantaremos un servidor de recursos
Lumen diminuto, firmaremos un token RS256 real con la clave en memoria del framework, veremos
pasar los tokens válidos y rebotar los falsificados/caducados, añadiremos una regla de política
embebida que aísle una ruta sensible y trazaremos la única unión `TokenValidationPort` donde
Keycloak o Cognito tomarían el relevo. Todo en este capítulo procede del propio test de
integración de extremo a extremo de la plataforma, de modo que cada fragmento es código que se
ejecuta.

Esto no es un experimento de juguete. La plataforma `fireflyframework-security` se entregó como
catorce módulos nuevos —el hexágono (`api`/`spi`/`core`/`webflux`), la entrega
(`resource-server`, `method-policy`, `oauth2-client`, `authorization-server`), los adaptadores
(`opa`, `cerbos`, `openfga`, `vault`, `r2dbc`) y las utilidades de test— todos compilando en
verde y publicados en `github.com/fireflyframework`. Cada puerto SPI conducido tiene al menos un
adaptador real, y cinco tests de integración levantan contenedores Docker genuinos (Postgres,
OPA, Cerbos, OpenFGA y Vault). El servidor de recursos que vas a montar es la cara visible de
ese trabajo —la pieza que un servicio Lumen ve a diario— y es exactamente la misma que el test
de integración de la plataforma arranca.

## Qué significa de verdad "seguro por defecto, en serio"

Antes de cualquier código, la postura —porque es la cuestión entera—. El viejo modelo de la
capa de aplicación era *abierto al fallo*: la autorización era algo que *activabas*, y un
contexto ausente o una propiedad cambiada dejaban pasar peticiones en silencio. La nueva
plataforma invierte eso. Su diseño enuncia la regla con claridad: **denegar por defecto, bearer
validado, cerrado al fallo, cabeceras endurecidas, y opt-outs explícitos y ruidosos.** En
concreto, cuatro comportamientos la definen.

Primero, **denegar por defecto**. La cadena de filtros del servidor de recursos termina en un
`anyExchange()` enrutado a través de un gestor de autorización que exige una autenticación
*validada*; una ruta que no permitiste explícitamente devuelve `401` (sin token) o `403` (token
presente, la política deniega). No hay un "permitir todo" global.

Segundo, **las firmas de los JWT entrantes se verifican de verdad**. La vieja "introspección"
de keycloak y azure-ad decodificaba los tokens localmente sin comprobación de firma, emisor ni
audiencia —un token falsificado reportaba `active=true`—. El nuevo servidor de recursos ejecuta
un `NimbusReactiveJwtDecoder` real contra una clave pública, con validadores de
timestamp/emisor/audiencia superpuestos. Un token firmado por una clave en la que el servidor no
confía se rechaza, sin más.

Tercero, **sin identidad por cabecera de confianza**. La plataforma más amplia del Capítulo 19
permitió en su día que la identidad llegara en una cabecera `X-Party-Id` bajo la suposición de
que una pasarela había verificado el JWT —lo que significaba que cualquier llamador fuera de la
malla podía suplantarla—. Esa cabecera desapareció. La identidad procede *solo* de un token con
la firma validada, incluso para las llamadas internas de la malla.

Cuarto, **el interruptor de encendido/apagado desapareció**. No hay
`firefly.security.enabled=false`. La forma de hacer alcanzable una ruta sin un token es
nombrarla, explícitamente, en una lista de permitidos. Ese es el opt-out ruidoso y auditable
que el diseño exige.

!!! warning "Esto reemplaza el interruptor del Capítulo 19, no lo extiende"
    La propiedad `firefly.application.security.enabled` del Capítulo 19 y su comportamiento
    de `SecurityAspect` sin efecto pertenecen al esquema *anterior* de la capa de aplicación. En
    la nueva plataforma `fireflyframework-security` esa propiedad está **eliminada** —no hay un
    interruptor abierto al fallo que heredar—. Si estás migrando un servicio, el cambio mental
    es: deja de pensar "la autorización está apagada hasta que la enciendo", y empieza a pensar
    "cada ruta está denegada hasta que la permito o el llamador presenta un token válido". Los
    dos capítulos describen dos generaciones de la misma preocupación; este es el que llevas a
    producción.

!!! spring "Equivalente en Spring"
    Por debajo esto es Spring Security 6 estándar para WebFlux: `@EnableWebFluxSecurity`, una
    `SecurityWebFilterChain` construida a partir de `ServerHttpSecurity`, `oauth2ResourceServer(...).jwt(...)`,
    un `NimbusReactiveJwtDecoder` y un `ReactiveAuthorizationManager`. Si has cableado a mano un
    servidor de recursos reactivo, has escrito las piezas. Lo que Firefly añade es el *ensamblado*:
    autoconfigura la cadena entera segura por defecto, encaja un `SecurityPrincipal` agnóstico del
    producto en lugar del `JwtAuthenticationToken` de Spring, y enruta la decisión final de
    autorización a través de un puerto de política intercambiable —de modo que obtienes los valores
    por defecto endurecidos sin ensamblarlos a mano en cada servicio—.

## El recorrido de una petición, de principio a fin

Antes de mirar las piezas por separado, vale la pena tener el camino completo en la cabeza,
porque cada bean de las secciones siguientes ocupa un puesto en él. Una petición Lumen que llega
con un `Authorization: Bearer …` recorre la misma secuencia fija, y cada eslabón es *cerrado al
fallo* —si no puede tomar su decisión, deniega—:

1. **Extracción.** El `BearerTokenExtractor` parsea la cabecera `Authorization`, casa el esquema
   `Bearer` sin distinguir mayúsculas (RFC 6750) y produce un `BearerToken` clasificado. Sin
   cabecera o con un esquema desconocido, no hay token que validar.
2. **Validación.** El token cruza la única costura `TokenValidationPort`. Por la rama JWT, el
   `NimbusReactiveJwtDecoder` verifica la firma RS256 y luego los validadores de
   timestamp/emisor/audiencia. Por la rama opaca, se delega en la introspección RFC 7662 del IdP
   tras una caché. Cualquier fallo termina aquí, en un `401`.
3. **Proyección.** Un conjunto de claims validado no es todavía un principal. La `PrincipalFactory`
   lo proyecta en un `SecurityPrincipal` inmutable, normalizando authorities y scopes a través del
   `AuthorityMappingPort`. El principal se publica en el `ReactiveSecurityContextHolder` y el
   `Context` de Reactor.
4. **Autorización a nivel de URL (PEP).** El punto de aplicación de URL enruta la petición a
   través del `PolicyDecisionPort`, pasando el método HTTP como *acción* y la ruta como *recurso*.
   El PDP embebido combina reglas con deny-overrides; una denegación es `403`.
5. **Autorización a nivel de método (PEP).** Si el manejador lleva `@Secure` o `@PreAuthorize`, el
   interceptor de seguridad de método reactivo evalúa el requisito contra el mismo principal.
6. **Controlador.** Solo ahora corre tu código, con un `@AuthenticationPrincipal SecurityPrincipal`
   ya validado en la mano.

A lo largo de ese recorrido, el `AuditEventPort` puede emitir en cada punto de decisión —éxito,
fallo, denegación, eventos de token y de clave—, de modo que cada `401` y cada `403` deja rastro
para un SIEM. Lo que sigue desmonta esa cadena eslabón a eslabón, empezando por la forma que
todo lo demás produce: el principal.

## El principal: una única forma agnóstica del producto

Todo lo que el servidor de recursos produce aterriza en un único tipo inmutable, el
`SecurityPrincipal`. Es la proyección neutral respecto a la pila que hace el framework de una
autenticación validada —y, por diseño deliberado, no lleva *ningún* concepto del dominio de
producto—. No hay `party`, no hay `contract`, no hay `product`; la identidad de dominio de una
plataforma de préstamos se lee de los mapas genéricos `claims` y `attributes`, no se cuece dentro
del framework.

::: listing fireflyframework-security-api/src/main/java/org/fireflyframework/security/api/domain/SecurityPrincipal.java | Listado 19a.1 — el principal agnóstico del producto que lleva cada petición protegida
@Builder(toBuilder = true)
public record SecurityPrincipal(
        String subject,
        String issuer,
        String tenantId,
        Set<String> authorities,
        Set<String> scopes,
        Map<String, Object> claims,
        Instant authTime,
        String acr,
        Set<String> amr,
        Map<String, Object> attributes
) {

    /** @return {@code true} if the subject holds the given authority. */
    public boolean hasAuthority(String authority) {
        return authorities.contains(authority);
    }

    /** @return a claim value coerced to the requested type, or {@code null} if absent/mismatched. */
    public <T> T claim(String name, Class<T> type) {
        Object value = claims.get(name);
        return type.isInstance(value) ? type.cast(value) : null;
    }
}
:::

Lee los campos frente a lo que una petición de Lumen necesita. `subject` es el `sub` de OIDC —el
usuario que actúa—. `issuer` es quien acuñó el token. `tenantId` es el discriminador
multi-tenant genérico (nulable cuando es de un solo tenant) que permite que la lectura de un
operador quede acotada a un banco. `authorities` y `scopes` son los roles y scopes OAuth2
*normalizados* que la siguiente sección mapea desde los claims. `claims` es el conjunto de claims
validado en bruto, y `attributes` es donde un enriquecedor de producto —código propio de Lumen,
nunca el framework— puede colgar un `partyId` o un `contractId` que lee de esos claims. Los
métodos de ayuda (`hasAuthority`, `hasScope`, `claim`) son cómo un manejador o una política
interroga al principal sin recurrir a un tipo de proveedor.

La palabra crucial es *validada*. Un `SecurityPrincipal` solo existe porque un token pasó antes
las comprobaciones de firma, emisor, audiencia y expiración. El framework nunca construye uno a
partir de una fuente no verificada —esa es la garantía estructural que la vieja cabecera
`X-Party-Id` rompía y que este tipo restaura—.

Ese campo `attributes` es la bisagra agnóstica del producto, y merece una palabra extra para
Lumen. El framework jamás escribe en él. Si Lumen necesita que un `partyId` o un `contractId`
viaje con cada petición, lo reintroduce *en su propio código* a través del
`PrincipalAttributeContributorPort` —un SPI enriquecedor que lee los claims validados y cuelga
los atributos de producto en el principal—. Esa es la salida de toda la purga de dominio: lo que
antes eran campos del framework (`partyId`, `contractId`, `productId`, enums de rol de negocio)
ahora son datos que un producto añade sobre primitivas genéricas. El framework lleva `subject`,
`tenantId` y un mapa abierto; Lumen lleva su modelo de negocio encima, sin pedirle al framework
que conozca lo que es un préstamo.

!!! note "Término clave — `SecurityPrincipal`"
    El registro inmutable y agnóstico del producto que hace el framework de un sujeto autenticado:
    subject, issuer, tenant opcional, authorities y scopes normalizados, claims en bruto y un mapa
    de atributos para el enriquecimiento de producto. Es una proyección de una autenticación con
    *la firma validada*, propagada a través del `Context` de Reactor e inyectable en un manejador
    con `@AuthenticationPrincipal`. Reemplaza tanto el `JwtAuthenticationToken` de Spring como la
    vieja fachada de principal `AppContext` —y deliberadamente no contiene `party`/`contract`/`product`—.

### Leer la identidad sin acoplarte a Spring: `SecurityContextPort`

`@AuthenticationPrincipal` es la vía cómoda en un controlador, pero un servicio de dominio de
Lumen —digamos un componente que decide si un movimiento de fondos está permitido— no debería
importar un tipo de Spring solo para saber quién llama. Para eso la plataforma expone un acceso
neutral respecto a la pila, el `SecurityContextPort`, que la unión WebFlux implementa sobre el
`ReactiveSecurityContextHolder`:

::: listing fireflyframework-security-spi/src/main/java/org/fireflyframework/security/spi/SecurityContextPort.java | Listado 19a.2 — leer el principal validado sin depender de Spring
public interface SecurityContextPort {

    /** @return the current principal, or an empty {@link Mono} when unauthenticated. */
    Mono<SecurityPrincipal> currentPrincipal();
}
:::

Un método, un `Mono<SecurityPrincipal>` —vacío cuando no hay autenticación—. El código de
aplicación de Lumen depende de *este* puerto, no del `ReactiveSecurityContextHolder` de Spring;
de nuevo el patrón hexagonal, esta vez aplicado a la lectura de la identidad. La misma garantía
estructural se sostiene: el `Mono` que devuelve, cuando no está vacío, lleva un principal cuya
firma se validó, nunca uno fabricado a partir de una cabecera.

## Levantar el servidor de recursos

No hay casi nada que escribir. El starter del servidor de recursos está autoconfigurado: déjalo
caer en el classpath y una cadena completa y bloqueada se ensambla sola. Para verla de extremo a
extremo, aquí está la aplicación Lumen mínima que arranca el propio test de integración de la
plataforma —un controlador con una ruta pública, una ruta protegida que hace eco del llamador y
una ruta que una política aislará—.

::: listing fireflyframework-security-resource-server/src/test/java/org/fireflyframework/security/rs/ResourceServerIntegrationTest.java | Listado 19a.3 — una aplicación protegida completa: rutas pública, protegida y denegada por política
@SpringBootApplication
static class TestApp {

    @Bean
    PolicyRule denyDeniedPath() {
        return (principal, action, resource, context) ->
                "/api/denied".equals(resource)
                        ? Mono.just(Decision.deny("blocked by policy"))
                        : Mono.just(Decision.permit());
    }

    @RestController
    static class TestController {

        @GetMapping("/public/ping")
        Mono<String> ping() {
            return Mono.just("pong");
        }

        @GetMapping("/api/me")
        Mono<String> me(@AuthenticationPrincipal SecurityPrincipal principal) {
            return Mono.just(principal.subject());
        }

        @GetMapping("/api/denied")
        Mono<String> denied() {
            return Mono.just("secret");
        }
    }
}
:::

Lee lo que *está* —y lo que *no está*— aquí. El controlador es WebFlux puro. `/api/me` toma un
`@AuthenticationPrincipal SecurityPrincipal` y recibe el principal *validado* directamente, sin
parseo de cabeceras, sin fontanería de contexto. No hay `@EnableWebFluxSecurity`, no hay bean
`SecurityWebFilterChain`, no hay decoder, no hay cableado de validadores —el starter aportó todo
ello—. El único código de seguridad en toda la aplicación es el único bean `PolicyRule`, y
volveremos a él. Para hacer `/public/**` alcanzable sin un token, el test establece exactamente
una propiedad —el opt-out ruidoso y explícito—:

::: listing fireflyframework-security-resource-server/src/test/java/org/fireflyframework/security/rs/ResourceServerIntegrationTest.java | Listado 19a.4 — el único opt-out: un matcher de permiso explícito
@SpringBootTest(
        classes = ResourceServerIntegrationTest.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "firefly.security.resource-server.permit-matchers=/public/**",
                "spring.main.banner-mode=off"
        })
class ResourceServerIntegrationTest {
:::

Esa única línea —`firefly.security.resource-server.permit-matchers=/public/**`— es toda la
superficie pública. Cualquier otra ruta se deniega por defecto. En un servicio Lumen real
listarías las mismas rutas operativas que el Capítulo 19 enumeró —`/actuator/health`,
`/v3/api-docs/**`, la Swagger UI— y nada más. El contraste con la cadena del Capítulo 19 es la
historia entera: allí el comodín era `anyExchange().permitAll()` y la comprobación real vivía en
otro sitio; aquí el comodín *deniega*, y tallas las pocas rutas públicas por nombre.

!!! note "Término clave — `firefly.security.resource-server.permit-matchers`"
    La lista de permitidos explícita y nombrada de patrones de ruta al estilo Ant que son
    alcanzables *sin* un token validado. Es el único opt-out de denegar-por-defecto que ofrece el
    servidor de recursos —no hay un "permitir todo" global—. Cualquier cosa que no coincida exige
    un token bearer con la firma validada (y luego debe superar la decisión de la política).
    Mantenla en rutas operativas y de documentación; nunca la amplíes a `/**`.

### Con el starter de aplicación y sin él

Hay dos caminos de entrega hacia este mismo resultado seguro por defecto, y conviene saber cuál
estás recorriendo. El primero es *con el starter de aplicación*:
`fireflyframework-starter-application` depende transitivamente de `security-resource-server` y de
`security-method-policy`, de modo que cualquier servicio de la capa de aplicación de Lumen queda
bloqueado nada más nacer —denegar por defecto, validación de JWT, cabeceras endurecidas y
seguridad de método `@Secure`, con *cero* código de seguridad—. El servicio solo declara qué
rutas son públicas. Así fue como la capa de aplicación se cableó y se de-domainó en el cambio
real, con 180 tests en verde.

El segundo es *sin el starter*: un servicio que no vive sobre el starter de aplicación añade
`fireflyframework-security-resource-server` directamente (y `-method-policy` para `@Secure`), y
recibe la idéntica `ResourceServerAutoConfiguration`. La autoconfiguración es condicional a una
aplicación web reactiva y puede desactivarse con una única propiedad, de modo que compone
limpiamente con cualquier servicio. El test de integración de este capítulo recorre exactamente
ese segundo camino —un `@SpringBootApplication` autónomo con el starter del servidor de recursos
y nada más—, demostrando que la postura segura por defecto no depende del resto de la plataforma.

## Validar el JWT: la firma primero, luego iss/aud/exp

El corazón de la plataforma es que un token se *verifica criptográficamente* antes de convertirse
en un principal. La autoconfiguración construye un `NimbusReactiveJwtDecoder` a partir de la
mitad pública de la clave de firma activa y lo envuelve en una cadena de validadores. Aquí está
el bean decoder real.

::: listing fireflyframework-security-resource-server/src/main/java/org/fireflyframework/security/rs/ResourceServerAutoConfiguration.java | Listado 19a.5 — el decoder que verifica: firma RS256 más validadores de timestamp/emisor/audiencia
@Bean
@ConditionalOnMissingBean
public ReactiveJwtDecoder fireflyReactiveJwtDecoder(
        KeyManagementPort keyManagementPort, ResourceServerProperties properties) {
    SigningKey active = keyManagementPort.activeSigningKey().block();
    if (active == null || !(active.publicKey() instanceof RSAPublicKey rsaPublicKey)) {
        throw new IllegalStateException("No RSA signing key available to build the JWT decoder");
    }
    NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
            .withPublicKey(rsaPublicKey)
            .signatureAlgorithm(SignatureAlgorithm.RS256)
            .build();

    List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
    validators.add(new JwtTimestampValidator());
    if (properties.getIssuer() != null && !properties.getIssuer().isBlank()) {
        validators.add(new JwtIssuerValidator(properties.getIssuer()));
    }
    if (!properties.getAudiences().isEmpty()) {
        validators.add(audienceValidator(properties.getAudiences()));
    }
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
    return decoder;
}
:::

Tres cosas que interiorizar. El decoder se construye `withPublicKey(...)` y se fija a `RS256` —de
modo que la firma de un token debe verificar contra *esa* clave pública o la decodificación falla
de plano; esta es la línea que hace imposible un token falsificado—. El `JwtTimestampValidator`
rechaza los tokens caducados (y los aún-no-válidos). Y los validadores de `iss`/`aud` se añaden
*condicionalmente*: establece `firefly.security.resource-server.issuer` y se aplica una
comprobación de emisor; lista `audiences` y se aplica una comprobación de audiencia. El test de
integración se ejecuta sin ninguna de las dos, de modo que ejercita la ruta pura de
firma-más-expiración —pero un despliegue Lumen de producción fijaría ambas a su proveedor de
identidad—.

Repara en la guarda de arranque. Si no hay clave RSA disponible —porque ninguna fuente de claves
se resolvió— el bean lanza una `IllegalStateException` y el contexto *no arranca*. Eso es
deliberado: el diseño es enfático en que una fuente de claves ausente bajo un perfil de
producción es un *fallo de arranque*, no un repliegue silencioso a un secreto HS256 estático y
no rotable. Un servidor de recursos sin una clave en la que confiar no es un servidor degradado;
es uno que se niega a abrir.

¿De dónde viene la clave pública? De un `KeyManagementPort`. En dev/test el starter suministra un
`InMemoryKeyManagementAdapter` que genera un par RSA al arrancar; en producción lo intercambias
por Vault Transit o un KMS de nube, ganando rotación de claves basada en `kid` y un JWKS publicado
—sin tocar este código del decoder—.

!!! note "Término clave — `KeyManagementPort`"
    El SPI que posee las claves de firma de primera parte y el JWKS contra el que verifica el
    servidor de recursos. `activeSigningKey()` devuelve la clave actual (con una mitad privada para
    firmar); `verificationKeys()` mantiene la clave pública anterior durante el solapamiento de una
    rotación para que los tokens en vuelo sigan siendo válidos. Backends: un generador en memoria
    para dev (por defecto), HashiCorp Vault Transit y AWS/Azure KMS —seleccionados por
    configuración, con el decoder de arriba sin cambios—. El adaptador de Vault del catálogo se
    verificó contra un contenedor Vault real.

### Demostrarlo: válido pasa, falsificado y caducado fuera

El test de integración firma tokens reales con la propia clave activa del framework y los conduce
a través de `WebTestClient`. Primero, el camino feliz y la ruta pública.

::: listing fireflyframework-security-resource-server/src/test/java/org/fireflyframework/security/rs/ResourceServerIntegrationTest.java | Listado 19a.6 — una ruta pública no necesita token; una ruta protegida necesita uno válido
@Test
void publicRouteIsAccessibleWithoutToken() {
    client.get().uri("/public/ping").exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("pong");
}

@Test
void protectedRouteWithoutTokenIsUnauthorized() {
    client.get().uri("/api/me").exchange().expectStatus().isUnauthorized();
}

@Test
void protectedRouteWithValidTokenIsAuthorized() throws Exception {
    String token = signWith(keyManagementPort.activeSigningKey().block(), validClaims().build());
    client.get().uri("/api/me")
            .header("Authorization", "Bearer " + token)
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("alice");
}
:::

La ruta protegida devuelve `401` sin token y, con uno firmado correctamente, devuelve el subject
del principal —`alice`—, demostrando que el decoder se ejecutó, que el converter construyó un
`SecurityPrincipal` y que `@AuthenticationPrincipal` se lo entregó al método. Los ayudantes de
firma son Nimbus corriente, usando la clave que el propio framework publicó:

::: listing fireflyframework-security-resource-server/src/test/java/org/fireflyframework/security/rs/ResourceServerIntegrationTest.java | Listado 19a.7 — firmar un token RS256 real y los claims que viajan en él
private String signWith(SigningKey key, JWTClaimsSet claims) throws Exception {
    JWSSigner signer = new RSASSASigner((RSAPrivateKey) key.privateKey());
    SignedJWT jwt = new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.kid()).build(), claims);
    jwt.sign(signer);
    return jwt.serialize();
}

private JWTClaimsSet.Builder validClaims() {
    Instant now = Instant.now();
    return new JWTClaimsSet.Builder()
            .subject("alice")
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(300)))
            .claim("scope", "read write")
            .claim("roles", List.of("teller"));
}
:::

Fíjate en dos detalles del firmante. La cabecera JWS lleva `keyID(key.kid())` —el `kid` de la
clave activa—; esa es la pista que un decoder con un JWKS publicado usa para elegir la clave de
verificación correcta durante un solapamiento de rotación, y por eso el `KeyManagementPort`
expone `kid` como un concepto de primera clase. Y fíjate en los claims que lleva `alice`: un
`scope` de `read write` y un array `roles` con `teller`. Esas son las entradas en bruto que la
siguiente sección mapea a las `authorities` y los `scopes` del principal. Ahora las dos rutas de
rechazo —caducado, y falsificado por una clave desconocida—:

::: listing fireflyframework-security-resource-server/src/test/java/org/fireflyframework/security/rs/ResourceServerIntegrationTest.java | Listado 19a.8 — los tokens caducados y falsificados son ambos 401
@Test
void expiredTokenIsUnauthorized() throws Exception {
    Instant past = Instant.now().minusSeconds(3600);
    JWTClaimsSet expired = new JWTClaimsSet.Builder()
            .subject("alice")
            .issueTime(Date.from(past.minusSeconds(60)))
            .expirationTime(Date.from(past))
            .build();
    String token = signWith(keyManagementPort.activeSigningKey().block(), expired);
    client.get().uri("/api/me").header("Authorization", "Bearer " + token)
            .exchange().expectStatus().isUnauthorized();
}

@Test
void forgedTokenSignedByUnknownKeyIsUnauthorized() throws Exception {
    SigningKey foreignKey = new InMemoryKeyManagementAdapter().activeSigningKey().block();
    String token = signWith(foreignKey, validClaims().build());
    client.get().uri("/api/me").header("Authorization", "Bearer " + token)
            .exchange().expectStatus().isUnauthorized();
}
:::

El test del token falsificado es el que habría *pasado* bajo el viejo esquema de parseo local y
habría autorizado a un atacante en silencio. Aquí construye un *segundo*
`InMemoryKeyManagementAdapter` desechable —un par RSA distinto que el servidor nunca ha visto—,
firma un token cuyos claims son por lo demás perfectos, y obtiene `401`. La firma no verifica
contra la clave pública del servidor, así que el token nunca se convierte en principal. Ese único
test es toda la promesa "las firmas se validan de verdad ahora", hecha ejecutable.

## Mapear claims a authorities

Un token verificado es solo claims; convertir `roles: [teller]` y `scope: "read write"` en las
`authorities` y los `scopes` del principal es la tarea del `AuthorityMappingPort`. La
implementación por defecto lee *rutas de puntos* configuradas del conjunto de claims, que es cómo
un único mapeador maneja Keycloak, Cognito y Entra sin código por proveedor.

::: listing fireflyframework-security-core/src/main/java/org/fireflyframework/security/core/authority/AuthorityMappingProperties.java | Listado 19a.9 — las rutas de claim por defecto abarcan los principales IdP
public AuthorityMappingProperties {
    roleClaimPaths = roleClaimPaths == null || roleClaimPaths.isEmpty()
            ? List.of("roles", "realm_access.roles", "cognito:groups", "groups")
            : List.copyOf(roleClaimPaths);
    scopeClaimPaths = scopeClaimPaths == null || scopeClaimPaths.isEmpty()
            ? List.of("scope", "scp")
            : List.copyOf(scopeClaimPaths);
    authorityPrefix = authorityPrefix == null ? "" : authorityPrefix;
}
:::

De serie, los roles se leen de `roles`, `realm_access.roles` (la forma anidada de Keycloak),
`cognito:groups` (AWS) o `groups` (Entra) —el que esté presente— y los scopes de `scope` o `scp`.
Puedes sobrescribir las rutas y añadir un `authorityPrefix` (digamos `ROLE_`) por servicio vía
`firefly.security.resource-server.*` sin escribir un mapeador. El mapeador por defecto, el
`ConfigurableAuthorityMapper`, recorre esas rutas y aplica el prefijo —reemplazando el
duplicado y muerto `JwtClaimsRoleExtractor` que vivía en el viejo starter—:

::: listing fireflyframework-security-core/src/main/java/org/fireflyframework/security/core/authority/ConfigurableAuthorityMapper.java | Listado 19a.10 — el mapeador por defecto recorre las rutas de claim y normaliza
@Override
public Set<String> mapAuthorities(Map<String, Object> claims) {
    Set<String> out = new LinkedHashSet<>();
    for (String path : properties.roleClaimPaths()) {
        for (String role : ClaimPaths.readStringSet(claims, path)) {
            out.add(applyPrefix(role));
        }
    }
    return out;
}

@Override
public Set<String> mapScopes(Map<String, Object> claims) {
    Set<String> out = new LinkedHashSet<>();
    for (String path : properties.scopeClaimPaths()) {
        out.addAll(ClaimPaths.readStringSet(claims, path));
    }
    return out;
}
:::

La magia que hace que una única lista de rutas abarque tres proveedores con formas de claim
distintas vive en `ClaimPaths`. Una ruta como `realm_access.roles` *navega JSON anidado* —baja
por mapas anidados segmento a segmento—, y el lector también divide una cadena delimitada por
espacios o comas, de modo que el `scope: "read write"` de `alice` se parte en dos scopes:

::: listing fireflyframework-security-core/src/main/java/org/fireflyframework/security/core/authority/ClaimPaths.java | Listado 19a.11 — navegar la ruta de puntos y coaccionar a un conjunto de cadenas
/** Coerce a claim value (String, delimited String, or Collection) into a normalized string set. */
public static Set<String> toStringSet(Object value) {
    Set<String> out = new LinkedHashSet<>();
    if (value == null) {
        return out;
    }
    if (value instanceof String s) {
        for (String token : s.split("[\\s,]+")) {
            if (!token.isBlank()) {
                out.add(token);
            }
        }
    } else if (value instanceof Collection<?> c) {
        c.stream().filter(Objects::nonNull).map(Object::toString)
                .filter(t -> !t.isBlank()).forEach(out::add);
    } else {
        out.add(value.toString());
    }
    return out;
}
:::

El `roles: [teller]` de `alice` —una colección JSON— aterriza por tanto como la authority
`teller`, y su `scope: "read write"` —una cadena delimitada— como los scopes `read` y `write`.
Tres codificaciones (cadena suelta, cadena delimitada, colección) normalizadas a un único
`Set<String>`, que es precisamente lo que permite que la misma configuración por defecto absorba
las formas de Keycloak, Cognito y Entra a la vez.

### Proyectar los claims en un principal: `PrincipalFactory`

El mapeador no construye el principal por sí solo; alimenta una `PrincipalFactory`, que ensambla
el `SecurityPrincipal` final a partir de un mapa de claims plano. Esa última precisión —*plano*—
importa: la fábrica no sabe si los claims vinieron de un JWT decodificado o de una introspección
opaca, de modo que la *misma* proyección sirve a ambas ramas de validación.

::: listing fireflyframework-security-core/src/main/java/org/fireflyframework/security/core/context/PrincipalFactory.java | Listado 19a.12 — proyectar claims validados en el principal, normalizando authorities y scopes
public SecurityPrincipal fromClaims(String subject, String issuer, Map<String, Object> claims) {
    Map<String, Object> safe = claims == null ? Map.of() : claims;
    return SecurityPrincipal.builder()
            .subject(subject)
            .issuer(issuer)
            .authorities(authorityMapper.mapAuthorities(safe))
            .scopes(authorityMapper.mapScopes(safe))
            .claims(safe)
            .authTime(readInstant(safe.get("auth_time")))
            .acr(asString(safe.get("acr")))
            .amr(ClaimPaths.toStringSet(safe.get("amr")))
            .build();
}
:::

Aquí se cierra el círculo del recorrido de la petición: `subject` e `issuer` vienen del token
validado, las `authorities` y los `scopes` se normalizan a través del `AuthorityMappingPort`, y
los claims de step-up (`auth_time`, `acr`, `amr`) se leen para una eventual elevación de
autenticación. Un pequeño `JwtToFireflyPrincipalConverter` reemplaza el token por defecto de
Spring para que tu manejador reciba este rico principal de Firefly en lugar de un
`JwtAuthenticationToken` pelado. La cadena del `Jwt` en bruto al principal inyectable son tres
beans breves e intercambiables —mapeador, fábrica, converter—, todos autoconfigurados.

!!! note "Término clave — `AuthorityMappingPort`"
    El SPI que convierte los claims validados en las `authorities` y los `scopes` normalizados del
    principal. El `ConfigurableAuthorityMapper` por defecto lee ubicaciones de claim por ruta de
    puntos (`roles`, `realm_access.roles`, `cognito:groups`, …) y un prefijo de authority opcional
    —de modo que cambiar de proveedor de identidad es un cambio de configuración, no un cambio de
    código—. Absorbe el viejo y duplicado `JwtClaimsRoleExtractor`.

!!! spring "Equivalente en Spring"
    Spring estándar te da un `JwtAuthenticationConverter` y un `JwtGrantedAuthoritiesConverter` que
    configuras por servicio para encontrar tu claim de roles. El `AuthorityMappingPort` de Firefly
    es la misma idea con dos mejoras: un valor por defecto para toda la flota que ya conoce las
    formas de claim de los principales IdP, y un `SecurityPrincipal` agnóstico del producto como
    salida en lugar del token ligado a `Jwt` de Spring —de modo que el código de aplicación nunca
    importa un tipo OAuth2 para leer la identidad—.

## El punto de decisión de políticas embebido

La autenticación responde a *quién*; la autorización responde a *si puede*. Después de que un
token valide, el punto de aplicación de URL del servidor de recursos enruta la petición a través
de un `PolicyDecisionPort` —un punto de decisión ABAC externalizado que dice PERMIT, DENY o
INDETERMINATE para un sujeto, una acción y un recurso dados—. El framework incluye un PDP
**embebido** sin dependencias que combina beans `PolicyRule` en proceso con semántica de
deny-overrides, y es estrictamente cerrado al fallo. Aquí está el adaptador completo, no solo su
combinador:

::: listing fireflyframework-security-core/src/main/java/org/fireflyframework/security/core/policy/EmbeddedPolicyDecisionAdapter.java | Listado 19a.13 — el PDP embebido: deny-overrides, cerrado al fallo, los errores a INDETERMINATE
@Override
public Mono<Decision> authorize(SecurityPrincipal principal, String action, String resource, Map<String, Object> context) {
    if (rules.isEmpty()) {
        return Mono.just(Decision.permit());
    }
    return Flux.fromIterable(rules)
            .concatMap(rule -> rule.evaluate(principal, action, resource, context)
                    .onErrorResume(ex -> {
                        log.warn("Policy rule '{}' errored; treating as indeterminate: {}", rule.name(), ex.getMessage());
                        return Mono.just(Decision.indeterminate("rule error: " + rule.name()));
                    }))
            .collectList()
            .map(this::combine);
}

private Decision combine(List<Decision> decisions) {
    if (decisions.stream().anyMatch(d -> d.effect() == Decision.Effect.DENY)) {
        return Decision.deny("denied by policy");
    }
    if (decisions.stream().anyMatch(Decision::granted)) {
        return Decision.permit();
    }
    return Decision.deny("no policy rule permitted access");
}
:::

Lee las reglas de combinación. Si *cualquier* regla registrada deniega, la petición se deniega
(deny overrides). En caso contrario, si al menos una regla permite, se permite. Si hay reglas
registradas pero ninguna permite —todas se abstienen— la petición se *deniega*. Y la línea
crucial está arriba, en el `onErrorResume`: una regla que lanza una excepción se mapea a
INDETERMINATE, no a un permiso, y como INDETERMINATE no es ni DENY ni `granted`, una regla que
revienta no puede *jamás* producir un permiso. Hay una conveniencia deliberada: si *no* hay
ninguna regla registrada en absoluto, el PDP embebido permite, difiriendo la autorización a RBAC
en el punto de aplicación de políticas (el requisito de token validado sigue aplicándose). La
única regla de Lumen aísla la ruta sensible:

::: listing fireflyframework-security-resource-server/src/test/java/org/fireflyframework/security/rs/ResourceServerIntegrationTest.java | Listado 19a.14 — una regla de política embebida deniega un recurso sensible
@Bean
PolicyRule denyDeniedPath() {
    return (principal, action, resource, context) ->
            "/api/denied".equals(resource)
                    ? Mono.just(Decision.deny("blocked by policy"))
                    : Mono.just(Decision.permit());
}
:::

Una `PolicyRule` es una interfaz funcional sobre `(principal, action, resource, context)` →
`Mono<Decision>`, donde el punto de aplicación de URL pasa el método HTTP como la *acción* y la
ruta de la petición como el *recurso*. Esta regla deniega `/api/denied` y permite todo lo demás.
Crucialmente, se ejecuta *después* de la autenticación —de modo que el llamador es una `alice`
plenamente validada— y aun así deniega. Eso es la autenticación y la autorización haciendo
trabajos distintos:

::: listing fireflyframework-security-resource-server/src/test/java/org/fireflyframework/security/rs/ResourceServerIntegrationTest.java | Listado 19a.15 — un token válido aun así recibe 403 cuando la política deniega
@Test
void policyDeniedRouteIsForbiddenEvenWithValidToken() throws Exception {
    String token = signWith(keyManagementPort.activeSigningKey().block(), validClaims().build());
    client.get().uri("/api/denied").header("Authorization", "Bearer " + token)
            .exchange().expectStatus().isForbidden();
}
:::

`alice` presenta un token perfectamente válido y recibe `403` —no `401`—. La distinción es la
cuestión: `401` significa "no sé quién eres", `403` significa "sé exactamente quién eres y no
puedes hacer esto". Para Lumen, una regla como esta es donde codificas "un `teller` puede leer una
solicitud pero no puede mover fondos", leyendo las `authorities` del principal y la ruta del
recurso para decidir. Cuando superes las reglas en proceso, el mismo `PolicyDecisionPort` lo
satisface un adaptador de OPA, Cerbos u OpenFGA —tus beans de regla se vuelven política externa, y
ni una línea de código de controlador cambia—. Esos tres adaptadores no son promesas: el de OPA y
el de Cerbos se verificaron contra contenedores OPA y Cerbos reales (ABAC), y el de OpenFGA contra
un contenedor OpenFGA real para las relaciones al estilo Zanzibar (ReBAC), a través del
`RelationshipPort` hermano.

!!! note "Término clave — `PolicyDecisionPort` / `PolicyRule`"
    El `PolicyDecisionPort` es el SPI que el servidor de recursos consulta para cada decisión de
    autorización; el valor por defecto embebido combina beans `PolicyRule` con **deny-overrides** y
    es **cerrado al fallo** (los errores y la abstención total ambos deniegan). Una `PolicyRule` es
    una función `(principal, action, resource, context) → Mono<Decision>`. Intercambia el puerto
    por OPA/Cerbos/OpenFGA para externalizar la política sin cambio alguno en los manejadores ni en
    los puntos de llamada de las reglas.

!!! warning "Cerrado al fallo no es negociable"
    Toda ruta de error en la cadena de autorización deniega: un motor de política que agota su
    tiempo, una regla que lanza una excepción, una decisión que vuelve como INDETERMINATE —todas
    mapean a `403`, nunca a un permiso—. Esto es lo opuesto de la vieja postura abierta al fallo, y
    es deliberado. Cuando escribas una `PolicyRule`, no captures-y-permitas ante un error para
    "mantener el sitio en pie"; una decisión de seguridad que no puedes tomar es una denegación,
    sin más.

## Cabeceras endurecidas en cada respuesta

Seguro por defecto se extiende más allá de la petición: el servidor de recursos escribe un
conjunto de cabeceras endurecidas en *todas* las respuestas, incluidas las públicas. La misma
`SecurityWebFilterChain` que cablea el decoder también configura los escritores de cabeceras.

::: listing fireflyframework-security-resource-server/src/main/java/org/fireflyframework/security/rs/ResourceServerAutoConfiguration.java | Listado 19a.16 — cabeceras de seguridad endurecidas, configuradas una vez para toda la aplicación
.headers(headers -> headers
        .frameOptions(frame -> frame.mode(XFrameOptionsServerHttpHeadersWriter.Mode.DENY))
        .referrerPolicy(referrer -> referrer.policy(
                ReferrerPolicyServerHttpHeadersWriter.ReferrerPolicy.NO_REFERRER))
        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
        .hsts(hsts -> hsts.includeSubdomains(true).maxAge(Duration.ofDays(365))));
:::

`X-Frame-Options: DENY` bloquea el clickjacking, un `Content-Security-Policy` estricto y un
`Referrer-Policy: no-referrer` cierran el contenido y los referrers, y una política HSTS de un año
(con subdominios) fuerza HTTPS. Para una API bearer pura como la de Lumen, el CSRF se desactiva
—no hay sesión por cookie que falsificar—, mientras que los flujos de cookie/BFF del cliente
OAuth2 reciben en su lugar el doble envío de un `CookieServerCsrfTokenRepository`. El test afirma
que las cabeceras endurecidas aterrizan incluso en la ruta *pública* de ping, porque el
endurecimiento no debería depender de la autenticación:

::: listing fireflyframework-security-resource-server/src/test/java/org/fireflyframework/security/rs/ResourceServerIntegrationTest.java | Listado 19a.17 — las cabeceras endurecidas están presentes incluso en rutas públicas
@Test
void hardenedSecurityHeadersArePresent() {
    client.get().uri("/public/ping").exchange()
            .expectStatus().isOk()
            .expectHeader().valueEquals("X-Frame-Options", "DENY")
            .expectHeader().valueEquals("Referrer-Policy", "no-referrer")
            .expectHeader().exists("Content-Security-Policy");
}
:::

## `@Secure` sobre el método, ahora cerrado al fallo

El PDP a nivel de URL es grueso; para las reglas a nivel de método la plataforma conserva la
anotación `@Secure` del Capítulo 19 —pero reelaborada—. Es la misma declaración ergonómica de
roles, scopes y permisos requeridos, y ahora se asienta *encima* de la autenticación validada con
**semántica AND fija** y **denegar por defecto**: sin escape de `security.enabled`, sin apertura
al fallo cuando falta un contexto.

::: listing fireflyframework-security-api/src/main/java/org/fireflyframework/security/api/annotation/Secure.java | Listado 19a.18 — la @Secure reelaborada: requisitos tipados, AND entre dimensiones
public @interface Secure {

    /** Required authorities/roles. */
    String[] roles() default {};

    /** Required OAuth2 scopes. */
    String[] scopes() default {};

    /** Required fine-grained permissions. */
    String[] permissions() default {};

    /** If {@code true}, every declared role must be held; otherwise any one suffices. */
    boolean requireAllRoles() default false;

    boolean requireAllScopes() default false;
    boolean requireAllPermissions() default false;

    /** Optional SpEL expression evaluated against the principal; must resolve to {@code true}. */
    String expression() default "";
}
:::

La anotación es solo la *declaración*; el comportamiento vive en el `SecureAuthorizationEvaluator`,
y es inflexible. Vale la pena leerlo porque cada rama es una de las correcciones que el Capítulo
19 prometía y no entregaba:

::: listing fireflyframework-security-core/src/main/java/org/fireflyframework/security/core/authz/SecureAuthorizationEvaluator.java | Listado 19a.19 — el evaluador: principal nulo deniega, AND entre dimensiones, ANY/ALL dentro, SpEL cerrado al fallo
public Decision evaluate(SecurityPrincipal principal, SecureRequirement requirement) {
    if (principal == null) {
        return Decision.deny("no authenticated principal");
    }
    if (!requirement.roles().isEmpty()) {
        boolean ok = requirement.requireAllRoles()
                ? principal.hasAllAuthorities(requirement.roles())
                : principal.hasAnyAuthority(requirement.roles());
        if (!ok) {
            return Decision.deny("missing required roles");
        }
    }
    if (!requirement.scopes().isEmpty()) {
        boolean ok = requirement.requireAllScopes()
                ? requirement.scopes().stream().allMatch(principal::hasScope)
                : requirement.scopes().stream().anyMatch(principal::hasScope);
        if (!ok) {
            return Decision.deny("missing required scopes");
        }
    }
    if (!requirement.permissions().isEmpty()) {
        boolean ok = requirement.requireAllPermissions()
                ? principal.hasAllAuthorities(requirement.permissions())
                : principal.hasAnyAuthority(requirement.permissions());
        if (!ok) {
            return Decision.deny("missing required permissions");
        }
    }
    if (!requirement.expression().isBlank() && !evaluateExpression(principal, requirement.expression())) {
        return Decision.deny("authorization expression not satisfied");
    }
    return Decision.permit();
}
:::

Cuatro garantías, todas cerradas al fallo. Un principal `null` siempre se deniega —no hay
"sin contexto, déjalo pasar"—. Entre dimensiones (roles, scopes, permisos, expresión) *todas* las
dimensiones declaradas deben pasar: una guarda por dimensión que sale con DENY en cuanto una
falla, lo que es exactamente la semántica AND. Dentro de una dimensión, `requireAll*` alterna
entre ANY (por defecto, `hasAnyAuthority`) y ALL (`hasAllAuthorities`). Y una expresión SpEL que
falla o lanza se trata como denegación —el `evaluateExpression` captura cualquier `RuntimeException`,
registra el motivo y devuelve `false`—. El modelo del Capítulo 19 trataba `requireAll*` como ANY
en silencio y se saltaba la comprobación cuando no había contexto presente; ambas cosas están
corregidas aquí, y ese es el cambio "abierto al fallo → cerrado al fallo" en una sola pieza de
código.

En un manejador de Lumen que lee una solicitud, escribirías `@Secure(scopes = "read")`; en uno
que envía una decisión de crédito, `@Secure(roles = "underwriter", scopes = "write")`, y el
llamador debe poseer *ambas* dimensiones —`underwriter` como authority Y `write` como scope—.
Una anotación que el Capítulo 19 llevaba desapareció: `@RequireContext` se eliminó, porque solo
existía para condicionar el viejo contexto de party/contract —con denegar-por-defecto y un
principal validado, "¿hay alguna identidad en absoluto?" ya no es una pregunta separada que
hacer—.

!!! spring "Equivalente en Spring"
    `@Secure` es azúcar sobre `@EnableReactiveMethodSecurity` + `@PreAuthorize`. Puedes usar el
    `@PreAuthorize("hasAuthority('underwriter') and hasAuthority('SCOPE_write')")` de Spring
    directamente contra el mismo `SecurityPrincipal`; `@Secure` es la forma tipada y enumerable que
    evita escribir SpEL a mano y mantiene el requisito legible por máquina. Ambas se aplican en el
    interceptor de seguridad de método reactivo —el mecanismo es idéntico; la ergonomía difiere—.

## La única costura hacia un IdP: `TokenValidationPort`

Hasta ahora el servidor de recursos validaba los tokens contra su propia clave en memoria —bien
para los tests, pero Lumen en producción confía en un proveedor de identidad externo—. La
plataforma mantiene la capa del IdP y la capa de seguridad desacopladas, unidas por exactamente
*un* SPI: el `TokenValidationPort`.

::: listing fireflyframework-security-spi/src/main/java/org/fireflyframework/security/spi/TokenValidationPort.java | Listado 19a.20 — la única unión entre la capa del IdP y la capa de seguridad
public interface TokenValidationPort {

    Mono<SecurityPrincipal> validate(BearerToken token);
}
:::

Un método: toma un token bearer, devuelve un `SecurityPrincipal` validado —o falla—. Su contrato
es toda la postura de seguridad en una frase: valida firma/emisor/audiencia/expiración para los
JWT (o introspección RFC 7662 para los tokens opacos) y *nunca devuelve un principal no validado*.
La ruta del JWT ya la has visto —decoder más JWKS más validadores—. La ruta opaca delega en el
endpoint de introspección del IdP tras una caché. De cualquier forma, este puerto es el *único*
lugar donde la identidad cruza de "el token de un proveedor" a "el principal del framework".

### La rama opaca y los puertos de capacidad del IdP

La rama JWT es autónoma; la rama opaca es donde la capa del IdP entra realmente en juego. Detrás
de la antigua "introspección" de un único `IdpAdapter` gordo —21 métodos en una sola interfaz—
ahora hay puertos de capacidad segregados, uno por responsabilidad. El que respalda la validación
de tokens opacos es el `TokenIntrospectionPort`:

::: listing fireflyframework-idp/src/main/java/org/fireflyframework/idp/port/TokenIntrospectionPort.java | Listado 19a.21 — el puerto de capacidad del IdP que respalda la validación de tokens opacos
public interface TokenIntrospectionPort {

    default Mono<ResponseEntity<IntrospectionResponse>> introspect(String accessToken) {
        return Mono.error(new UnsupportedOperationException("introspect is not supported by this IdP provider"));
    }

    default Mono<ResponseEntity<UserInfoResponse>> getUserInfo(String accessToken) {
        return Mono.error(new UnsupportedOperationException("getUserInfo is not supported by this IdP provider"));
    }
}
:::

Dos detalles cuentan la historia del rediseño del IdP. Primero, los métodos por defecto devuelven
un `Mono.error(UnsupportedOperationException)` en lugar de obligar a cada adaptador a implementar
capacidades que no soporta —ese es el patrón "NotSupported" que arregla la vieja violación del
principio de segregación de interfaces: un adaptador implementa solo lo que ofrece—. El viejo
`IdpAdapter` gordo se partió en seis puertos de capacidad de este estilo —`AuthenticationPort`,
`TokenIntrospectionPort`, `UserAdminPort`, `RoleScopePort`, `SessionPort`, `MfaPort`— con una
fachada agregada opcional para quien la quiera. Segundo, las respuestas ya están de-domainadas: el
`IntrospectionResponse` ya no lleva un campo `partyId`, y `UserRoleEnum` se borró por completo a
favor de roles genéricos `Set<String>`. Cuando Lumen valida un token opaco, la introspección RFC
7662 corre tras una caché acotada por TTL (`TokenIntrospectionCachePort`), cuyo TTL nunca excede
el `exp` del token —de modo que un token revocado no se queda cacheado más allá de su vida útil—.

### Revocación: rechazar un token todavía vigente

Hay un caso que ni la firma ni el `exp` capturan: un token válido, no caducado y bien firmado que,
sin embargo, debe rechazarse —porque el usuario cerró sesión, un operador lo revocó, o un
incidente de seguridad lo invalidó—. Para eso existe el `RevocationPort`:

::: listing fireflyframework-security-spi/src/main/java/org/fireflyframework/security/spi/RevocationPort.java | Listado 19a.22 — rechazar un token vigente pero revocado
public interface RevocationPort {

    Mono<Boolean> isRevoked(String tokenId);

    Mono<Void> revoke(String tokenId, Instant expiresAt);
}
:::

El adaptador por defecto se respalda con R2DBC (una base de datos relacional), con las entradas
expirando al `exp` del token de modo que la lista de revocación no crece sin límite —y se
verificó contra un contenedor PostgreSQL real, cuatro tests en verde—. Para Lumen esto es lo que
hace que "cerrar sesión" signifique algo de verdad en un mundo de tokens bearer sin estado: el
`jti` del token revocado entra en el store, y el siguiente intento de usarlo se rechaza pese a una
firma impecable.

### Auditar cada decisión

Por último, toda esta cadena de decisiones querría dejar rastro. El `AuditEventPort` es el sumidero
de los eventos de seguridad —autenticación, autorización, ciclo de vida de tokens y de claves—:

::: listing fireflyframework-security-spi/src/main/java/org/fireflyframework/security/spi/AuditEventPort.java | Listado 19a.23 — el sumidero de eventos de auditoría de seguridad
public interface AuditEventPort {

    Mono<Void> emit(SecurityAuditEvent event);
}
:::

La regla de oro del contrato está en su Javadoc: la emisión *nunca debe bloquear la ruta de la
petición ni lanzar*. Un adaptador puede abanicar los eventos a un log, a JDBC, a Kafka o a un SIEM
—el `LoggingAuditEventAdapter` por defecto solo registra—, pero un sumidero de auditoría que se
cae no puede tumbar una petición. Cada `401` que devuelve el decoder, cada `403` del PDP y cada
rotación de clave del `KeyManagementPort` puede emitir aquí, con correlación y tenant tomados del
`Context` de Reactor, de modo que el rastro de seguridad de Lumen es completo sin enmarañar el
camino caliente.

Eso es lo que hace al proveedor intercambiable. Para mover Lumen de la clave de dev en memoria a
Keycloak, añades el adaptador de Keycloak (que da la cara al JWKS de Keycloak a través de este
puerto y registra el emisor de confianza) y estableces las propiedades de emisor/audiencia —y tus
controladores, tus anotaciones `@Secure`, tus beans `PolicyRule` y tus parámetros
`@AuthenticationPrincipal` no cambian, porque dependen del `SecurityPrincipal` que el puerto
produce, nunca de un tipo de Keycloak—. Cognito y Entra se enchufan de la misma forma. Este es el
patrón hexagonal que el libro entero ha mostrado —puerto estable, adaptador reemplazable—,
aplicado a la costura más sensible de la plataforma, y refleja cómo los ports de Python y Rust ya
estructuran su capa de seguridad.

!!! note "Término clave — `TokenValidationPort`"
    El único SPI que une la capa del IdP y la capa de seguridad: `validate(BearerToken)` →
    `Mono<SecurityPrincipal>`, fallando ante cualquier error de validación. Los JWT pasan por el
    decoder que verifica + JWKS; los tokens opacos pasan por la introspección del IdP + una caché
    acotada por TTL. Como es la *única* unión, elegir o cambiar un proveedor de identidad es un
    cambio de adaptador + propiedad sin impacto en los manejadores, las políticas ni las reglas
    `@Secure`. Consulta el Apéndice B para el catálogo de adaptadores.

!!! info "Una costura, limpiamente"
    La capa del IdP (`fireflyframework-idp` más los proveedores Keycloak / Cognito / Entra /
    internal-db) permanece desacoplada de la capa de seguridad. Se encuentran solo en
    `TokenValidationPort` —la misma forma que los ports de Python (pyfly) y Rust ya validaron—.
    Esa simetría entre lenguajes no es casual: es el objetivo de alineación que guió el rediseño en
    Java.

## La postura, demostrada contra infraestructura real

Vale la pena salir un momento del servidor de recursos para situar lo que acabas de montar dentro
del cambio completo, porque la confianza en "seguro por defecto" no se gana con argumentos sino
con evidencia. La plataforma no se *afirma* que funciona —se *muestra*—. Más allá de los tests
unitarios, ocho tests de integración ejercitan criptografía real y servicios reales; cinco
levantan contenedores Docker genuinos vía Testcontainers:

- **Servidor de recursos** (los siete escenarios de este capítulo) contra criptografía RS256 en
  proceso: JWT firmado → `200`; sin token / falsificado / caducado → `401`; denegado por política
  → `403`; cabeceras presentes.
- **Revocación de tokens** (`RevocationPort`) contra un contenedor PostgreSQL real — 4 tests.
- **Política ABAC** (`PolicyDecisionPort`) contra un contenedor OPA real — 4 tests — y contra un
  contenedor Cerbos real — 2 tests.
- **Relaciones ReBAC** (`RelationshipPort`) contra un contenedor OpenFGA real — 2 tests.
- **Secretos** (`SecretsPort`) contra un contenedor HashiCorp Vault real — 3 tests.
- **Servidor de autorización**: descubrimiento OIDC, JWKS, `client_credentials` — 3 tests.
- **Cliente OAuth2**: intercambio `client_credentials` — 1 test.
- **Seguridad de método**: `@Secure` / `@PreAuthorize` contra el contexto de seguridad reactivo —
  5 tests.

Y el de-domainado tampoco se afirma: la capa de aplicación se cableó con la seguridad y se
de-domainó con 180 tests en verde, y la capa de backoffice se de-domainó con 13 tests en verde.
Los catorce módulos de seguridad instalan juntos con cero fallos. Cada afirmación de este capítulo
descansa sobre una compilación que pasa.

!!! warning "Romper al actualizar (por diseño)"
    Como la postura voltea a denegar-por-defecto y a validación real, los servicios que antes se
    apoyaban en el comportamiento abierto al fallo o en la cabecera de confianza `X-Party-Id`
    empezarán a devolver `401`/`403` hasta que se configuren. Ese es el resultado *buscado* de
    cerrar los agujeros de seguridad —una ruptura limpia, sin shims—. Cada cambio rompedor (split
    del `IdpAdapter` gordo, DTOs de-domainados, HS256 → RS256/JWKS, parseo-local → decodificación
    verificada, `X-Party-Id` eliminado, denegar-por-defecto, `requireAll*` ahora respetado) llega
    con su nota de remediación en el `UPGRADE.md` del operador.

## Ejecútalo

El capítulo entero es un test de integración, y arranca un servidor de recursos real en un puerto
aleatorio —cadena autoconfigurada, decoder que verifica, clave en memoria, PDP embebido, cabeceras
endurecidas— y luego conduce tokens firmados a través de `WebTestClient`. Desde el módulo de
seguridad:

```text
mvn -q -pl fireflyframework-security-resource-server test
```

Deberías ver pasar los siete escenarios:

```text
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- in org.fireflyframework.security.rs.ResourceServerIntegrationTest
[INFO] BUILD SUCCESS
```

Esos siete son el capítulo en forma ejecutable: una ruta pública pasa sin token; una ruta
protegida da `401` sin él y `200` con uno válido (haciendo eco de `alice`); un token caducado da
`401`; un token falsificado firmado por una clave desconocida da `401`; una ruta denegada por
política da `403` *pese* a un token válido; y las cabeceras endurecidas están presentes incluso en
la ruta pública. Léelos como una lista de verificación de la postura —cada uno es una propiedad que
el viejo modelo no podía garantizar—.

!!! tip "Punto de control"
    Ejecuta el comando y confirma `Tests run: 7, Failures: 0`. Después demuéstrate la postura de
    denegar-por-defecto tú mismo: comenta la propiedad `permit-matchers` y vuelve a ejecutar —el
    test `publicRouteIsAccessibleWithoutToken` ahora falla con `401`, porque *nada* es público
    salvo que lo nombres—. Restaura la propiedad y vuelve a pasar. Acabas de ver a "seguro por
    defecto" negarse a dejar pasar una ruta hasta que la sacaste con un opt-out, ruidosamente.

## Lo que has aprendido {.recap}

- Un **servidor de recursos seguro por defecto** que no necesita código de seguridad: dejar caer
  el starter del servidor de recursos en el classpath autoconfigura la cadena completa —decoder
  JWT que verifica, mapeo de claims a authorities, una cadena de filtros de denegar-por-defecto y
  cabeceras endurecidas—.
- El **recorrido completo de una petición** —extraer, validar, proyectar, autorizar en URL,
  autorizar en método, controlador— con auditoría posible en cada punto de decisión, y cada
  eslabón cerrado al fallo.
- **Validación de firma real**: un `NimbusReactiveJwtDecoder` fijado a la clave pública RS256 del
  `KeyManagementPort`, con validadores de timestamp y de emisor/audiencia opcionales y una guarda
  de arranque que se niega a abrir sin clave —de modo que un token caducado y un token falsificado
  por una clave desconocida ambos vuelven con `401`, el caso exacto que el viejo esquema de parseo
  local dejaba pasar—.
- **Claims mapeados a authorities** a través del `AuthorityMappingPort` y su `ClaimPaths`, cuyas
  rutas de puntos por defecto (`roles`, `realm_access.roles`, `cognito:groups`, `scope`/`scp`)
  abarcan Keycloak, Cognito y Entra, navegan JSON anidado y dividen cadenas delimitadas —y luego
  la `PrincipalFactory` proyecta esos claims en el `SecurityPrincipal` final, igual para la rama
  JWT y la opaca—.
- **Denegar por defecto** como postura: una única línea explícita `permit-matchers` es toda la
  superficie pública; cualquier otra ruta exige un token validado, y el interruptor
  `security.enabled` desapareció para siempre.
- El **punto de decisión de políticas embebido** —beans `PolicyRule` combinados con
  deny-overrides, los errores mapeados a INDETERMINATE, estrictamente cerrado al fallo— aislando
  `/api/denied` de modo que incluso un token válido recibe `403`, intercambiable por
  OPA/Cerbos/OpenFGA (verificados contra contenedores reales) a través del mismo
  `PolicyDecisionPort`.
- La **`@Secure`** reelaborada y su `SecureAuthorizationEvaluator` —principal nulo deniega,
  semántica AND fija entre dimensiones, ANY/ALL dentro, SpEL cerrado al fallo, sin apertura al
  fallo— para reglas a nivel de método encima de la autenticación validada.
- La única costura **`TokenValidationPort`** donde se enchufa un IdP real —con la rama opaca
  respaldada por los puertos de capacidad segregados del IdP, la revocación de tokens vigentes vía
  `RevocationPort` y la auditoría no bloqueante vía `AuditEventPort`—, dejando intactos los
  controladores, las políticas y las anotaciones `@Secure` cuando Lumen pasa de la clave de dev a
  Keycloak.
- Una ejecución de integración verde **`Tests run: 7, Failures: 0`** que demuestra
  válido-pasa / falsificado-fuera / caducado-fuera / denegado-por-política / cabeceras-presentes,
  de extremo a extremo, dentro de una plataforma de 14 módulos verificada contra infraestructura
  real (Postgres, OPA, Cerbos, OpenFGA, Vault).

## Pruébalo tú mismo {.exercises}

1. **Rompe la ruta pública.** Quita la propiedad `firefly.security.resource-server.permit-matchers`
   del test y vuelve a ejecutar. Observa cómo `publicRouteIsAccessibleWithoutToken` falla con
   `401`, y luego explica en una frase por qué "nada es público" es el valor por defecto seguro y
   por qué la lista de permitidos es la *única* forma de sacar una ruta con un opt-out.
2. **Fija el emisor.** Establece `firefly.security.resource-server.issuer` a un valor que los
   tokens del test no lleven, y luego añade un claim `issuer` a `validClaims()`. Confirma que un
   token con el emisor *equivocado* ahora devuelve `401` aunque su firma sea válida —y nombra el
   validador de `fireflyReactiveJwtDecoder` que lo hizo.
3. **Añade una política basada en authority.** Reemplaza la regla `denyDeniedPath` por una que
   permita `/api/me` solo cuando `principal.hasAuthority("teller")` y deniegue en caso contrario.
   Firma un token cuyo claim `roles` sea `[viewer]` en lugar de `[teller]` y confirma que ahora
   recibe `403`, demostrando que el mapeo de claims a authorities y el PDP están cableados juntos.
4. **Observa cerrado al fallo.** Escribe una `PolicyRule` cuyo `evaluate` lance una
   `RuntimeException` para una ruta. Confirma que una petición a esa ruta se deniega (`403`), no se
   permite —luego lee el `onErrorResume` y el `combine` de `EmbeddedPolicyDecisionAdapter` y
   explica por qué una regla que lanza una excepción se mapea a INDETERMINATE y nunca puede
   producir un permiso—.
5. **Protege un método.** Añade una cuarta ruta al controlador de test anotada con
   `@Secure(roles = "underwriter", scopes = "write")` y `@EnableReactiveMethodSecurity` a la
   aplicación. Firma un token que lleve solo `roles: [underwriter]` (sin scope `write`) y confirma
   que se deniega —luego añade el scope y confirma que pasa, demostrando la regla de AND entre
   dimensiones del `SecureAuthorizationEvaluator`—.
6. **Lee la identidad sin Spring.** En un componente de servicio (no un controlador), inyecta el
   `SecurityContextPort` y obtén el principal con `currentPrincipal()`. Confirma que devuelve un
   `Mono` vacío para una petición no autenticada y el principal validado para una autenticada —y
   explica por qué depender de este puerto, y no del `ReactiveSecurityContextHolder`, mantiene tu
   código de dominio agnóstico de Spring.
7. **Traza el intercambio de proveedor.** Sin cambiar el controlador, la `PolicyRule` ni ninguna
   anotación `@Secure`, lista exactamente qué cambiarías para validar tokens de Keycloak en lugar
   de la clave en memoria: qué adaptador satisface `TokenValidationPort`, qué dos propiedades fijan
   el emisor y la audiencia, y qué backend de `KeyManagementPort` usarías en producción. Confirma
   contra el Apéndice B que el manejador queda intacto.

## Adónde ir ahora

Ahora tienes un servidor de recursos que valida cada firma, deniega por defecto, mapea claims a
authorities y decide la autorización a través de un punto de política intercambiable —con la única
costura `TokenValidationPort` lista para un IdP real—. Pero un endpoint de lectura bloqueado aún
golpea la capa de abajo en cada llamada. El Capítulo 20 se vuelca en el **cacheo**: cómo el puerto
reactivo `CacheAdapter` de Firefly sirve lecturas repetidas desde una capa local Caffeine (y una
L2 distribuida opcional) sin un SDK de proveedor en tu código —el mismo patrón hexagonal que
acabas de aplicar a la identidad, aplicado ahora a la velocidad—.
