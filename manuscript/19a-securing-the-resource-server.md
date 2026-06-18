Chapter 19 secured Lumen's experience-tier BFF with a permissive transport filter and a
declarative `@Secure` aspect, then leaned on a property —
`firefly.application.security.enabled=false` — to take the gate off the door while you
were still building the house. That model taught the *shape* of authorization, but it
left two honest gaps wide open: nothing on the page validated a token's *signature*, and
the default posture was fail-*open* — a method with no context argument sailed through.
Those gaps are exactly what the new `fireflyframework-security` platform closes. This
chapter rebuilds Lumen's security on it: a **secure-by-default resource server** that
validates every JWT's signature, maps its claims to authorities, denies anything not
explicitly permitted, and exposes one small seam — `TokenValidationPort` — where a real
identity provider plugs in.

The difference from Chapter 19 is the difference between *decorative* and *enforced*. There
is no `security.enabled` flag to flip off anymore; the framework removed it. The moment the
resource-server starter is on the classpath, every route demands a signature-validated
bearer token unless you name it public, a forged token comes back `401`, and an
authorization-policy denial comes back `403` — with hardened security headers on every
response, including the public ones. We will stand up a tiny Lumen resource server, sign a
real RS256 token with the framework's in-memory key, watch valid tokens through and
forged/expired ones bounce, add one embedded policy rule that fences off a sensitive route,
and trace the single `TokenValidationPort` join where Keycloak or Cognito would take over.
Everything in this chapter is drawn from the platform's own end-to-end integration test, so
every snippet is code that runs.

This is also the chapter where the *whole* security platform comes into view, not just one
starter. Behind the resource server sits a clean hexagon: a product-agnostic API, a set of
driven **SPI ports**, a vendor-neutral core engine, a reactive Spring Security binding, the
delivery starters, and a fleet of pluggable **adapters** — OPA, Cerbos, OpenFGA, Vault, and
R2DBC, every one backed by a real container test. We will read the resource server top to
bottom, then widen out to the ports and adapters that make it swappable, and finish by
securing a Lumen service two ways: *with* the application starter (secure-by-default, zero
security code) and *without* it (a standalone resource server you add by hand).

## The shape of the platform: a security hexagon

Everything in this chapter is one instance of a pattern the whole book has hammered: a
**port** is a stable interface the framework owns; an **adapter** is a replaceable
implementation behind it. The security platform is fourteen Maven modules arranged into that
hexagon, and it is worth seeing the bands before the code, because every later section is
"which band is this, and which port does it cross."

- **`security-api`** — the *driving* side: the `SecurityPrincipal` your handler receives, the
  `@Secure` annotation, the `Decision`/`BearerToken`/`SigningKey` domain records, and the
  inbound use-case interfaces (`AuthenticateUseCase`, `AuthorizeUseCase`). No Spring web, no
  provider SDK. This is the only module your *business* code imports.
- **`security-spi`** — the *driven* side: thirteen outbound port interfaces the engine calls —
  `TokenValidationPort`, `PolicyDecisionPort`, `RelationshipPort`, `KeyManagementPort`,
  `AuthorityMappingPort`, `SecretsPort`, `RevocationPort`, `SecurityContextPort`, and the rest.
  Interfaces only; no implementations.
- **`security-core`** — the vendor-neutral *engine*: the embedded fail-closed policy adapter,
  the claim-to-authority mapper, the `@Secure` evaluator, the introspection cache, and the
  in-memory dev key source. It depends on `api` and `spi` and imports **no** provider SDK and
  **no** web stack.
- **`security-webflux`** — the reactive Spring Security *binding*: the URL policy enforcement
  point, the `ReactiveSecurityContextHolder` accessor, and the `FireflyAuthenticationToken`
  that carries the principal.
- **Delivery starters** — `security-resource-server` (this chapter), `security-method-policy`
  (`@Secure` on methods), `security-oauth2-client` (BFF login), and the servlet-only
  `security-authorization-server` (a first-party OIDC issuer).
- **Adapters** — one SDK each, depending only on `spi`: `adapter-opa` and `adapter-cerbos`
  (ABAC `PolicyDecisionPort`), `adapter-openfga` (ReBAC `RelationshipPort`), `adapter-vault`
  (`SecretsPort`), and `adapter-r2dbc` (`RevocationPort`). Plus `security-test` fixtures.

The rule that makes this a hexagon and not just a pile of modules: **dependencies point
inward**. Adapters depend on `spi`; the engine depends on `api`/`spi`; nothing in the core
ever imports OPA, Cerbos, Vault, or Spring Security. That is why you can move Lumen from the
embedded policy engine to OPA, or from the in-memory key to Vault, by adding a jar and
declaring a bean — the engine above the port does not change. This mirrors the Python
(`pyfly`) and Rust ports, which already ship the same security-tier / idp-tier split joined by
one token-validation seam.

!!! note "Key term — port vs adapter (in security)"
    A **port** is an interface in `security-api` (driving) or `security-spi` (driven) that the
    framework owns and the engine programs against. An **adapter** is a concrete implementation
    of a driven port — embedded, OPA, Cerbos, OpenFGA, Vault, R2DBC — selected by what is on the
    classpath and which beans you declare. Every SPI port in the platform ships with at least one
    real adapter, and the most security-sensitive ones ship with a container-tested adapter.

!!! spring "Spring parity"
    The whole platform is stock Spring Security 6 for WebFlux underneath:
    `@EnableWebFluxSecurity`, a `SecurityWebFilterChain` from `ServerHttpSecurity`,
    `oauth2ResourceServer(...).jwt(...)`, a `NimbusReactiveJwtDecoder`, and
    `ReactiveAuthorizationManager`s. The hexagon is *where Firefly adds value*: it pins those
    pieces into a secure-by-default assembly, swaps Spring's `JwtAuthenticationToken` for a
    product-agnostic `SecurityPrincipal`, and routes the final authorization decision through a
    port you can repoint at OPA or Cerbos without touching a controller.

## What "secure-by-default, hard" actually means

Before any code, the posture — because it is the whole point. The old application-tier
model was *fail-open*: authorization was a thing you switched *on*, and a missing context or
a flipped property quietly let requests through. The new platform inverts that. Its design
states the rule plainly: **default-deny, validated bearer, fail-closed, hardened headers,
and opt-outs that are explicit and loud.** Concretely, four behaviors define it.

First, **default-deny**. The resource server's filter chain ends in `anyExchange()` routed
through an authorization manager that requires a *validated* authentication; a route you did
not explicitly permit returns `401` (no token) or `403` (token present, policy denies).
There is no global "allow all."

Second, **inbound JWT signatures are actually verified**. The old keycloak and azure-ad
"introspection" decoded tokens locally with no signature, issuer, or audience check — a
forged token reported `active=true`. The new resource server runs a real
`NimbusReactiveJwtDecoder` against a public key, with timestamp/issuer/audience validators
layered on. A token signed by a key the server does not trust is rejected, full stop.

Third, **no trusted-header identity**. Chapter 19's broader platform once let identity
arrive in an `X-Party-Id` header on the assumption a gateway had verified the JWT — which
meant any off-mesh caller could spoof it. That header is gone. Identity comes *only* from a
signature-validated token, even for mesh-internal calls.

Fourth, **the on/off switch is gone**. There is no `firefly.security.enabled=false`. The way
you make a route reachable without a token is to name it, explicitly, in a permit list. That
is the loud, auditable opt-out the design demands.

!!! warning "This replaces Chapter 19's toggle, it does not extend it"
    Chapter 19's `firefly.application.security.enabled` property and its no-op
    `SecurityAspect` behavior belong to the *previous* application-tier scheme. On the new
    `fireflyframework-security` platform that property is **removed** — there is no fail-open
    switch to inherit. If you are migrating a service, the mental move is: stop thinking
    "authorization is off until I turn it on," and start thinking "every route is denied
    until I permit it or the caller presents a valid token." The two chapters describe two
    generations of the same concern; this is the one you ship.

### The 401/403 matrix

"Secure-by-default" only means something if you can predict the status code for every kind of
request. The resource server's behavior collapses to a small, total matrix — memorize it and
you can read any security failure at a glance:

| Request | Route is permit-listed? | Token | Outcome |
|---|---|---|---|
| `GET /public/ping` | yes | none | **200** — public route, no auth required |
| `GET /api/me` | no | none / malformed | **401** — bearer required, identity unknown |
| `GET /api/me` | no | expired | **401** — `JwtTimestampValidator` rejects |
| `GET /api/me` | no | wrong issuer/audience | **401** — `iss`/`aud` validator rejects |
| `GET /api/me` | no | signed by an untrusted key | **401** — signature does not verify |
| `GET /api/me` | no | valid, policy permits | **200** — principal handed to the handler |
| `GET /api/denied` | no | valid, policy denies | **403** — identity known, access refused |

The two columns that matter are *token validity* and *policy*. `401` is the family of "I
cannot establish who you are" — no token, bad signature, expired, wrong `iss`/`aud`. `403` is
the family of "I know exactly who you are and you may not do this" — the policy decision point
denied. Notice there is no row that *permits* on an error: a malformed token, a missing key, a
policy engine that throws — all deny. That is the fail-closed guarantee, expressed as a table.

## The principal: one product-agnostic shape

Everything the resource server produces lands in a single immutable type, the
`SecurityPrincipal`. It is the framework's stack-neutral projection of a validated
authentication — and, by deliberate design, it carries *no* product-domain concepts. There
is no `party`, no `contract`, no `product`; a lending platform's domain identity is read off
the generic `claims` and `attributes` maps, not baked into the framework.

::: listing fireflyframework-security-api/src/main/java/org/fireflyframework/security/api/domain/SecurityPrincipal.java | Listing 19a.1 — the product-agnostic principal every secured request carries
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

    /** @return {@code true} if the subject holds at least one of the given authorities. */
    public boolean hasAnyAuthority(Collection<String> candidates) { /* ... */ }

    /** @return {@code true} if the subject holds every one of the given authorities. */
    public boolean hasAllAuthorities(Collection<String> candidates) { /* ... */ }

    /** @return {@code true} if the token carries the given OAuth2 scope. */
    public boolean hasScope(String scope) {
        return scopes.contains(scope);
    }

    /** @return a claim value coerced to the requested type, or {@code null} if absent/mismatched. */
    public <T> T claim(String name, Class<T> type) {
        Object value = claims.get(name);
        return type.isInstance(value) ? type.cast(value) : null;
    }
}
:::

Read the fields against what a Lumen request needs. `subject` is the OIDC `sub` — the
acting user. `issuer` is who minted the token. `tenantId` is the generic multi-tenant
discriminator (nullable when single-tenant) that lets one operator's read stay scoped to one
bank. `authorities` and `scopes` are the *normalized* roles and OAuth2 scopes the next
section maps from claims. `claims` is the raw validated claim set, and `attributes` is where
a product enricher — Lumen's own code, never the framework — can hang a `partyId` or a
`contractId` it reads off those claims. The helper methods (`hasAuthority`, `hasAnyAuthority`,
`hasAllAuthorities`, `hasScope`, `claim`) are how a handler or policy interrogates the
principal without reaching for a vendor type — and, as you will see, they are exactly what the
`@Secure` evaluator calls under the hood.

The crucial word is *validated*. A `SecurityPrincipal` only ever exists because a token
passed signature, issuer, audience, and expiry checks first. The framework never constructs
one from an unverified source — that is the structural guarantee the old `X-Party-Id` header
broke and this type restores.

### Why there is no `X-Party-Id` (and what replaced it)

It is worth being precise about *what was removed*, because the de-domaining is the other half
of "secure-by-default." The previous platform leaked firefly-oss product concepts directly
into framework code, and identity could enter through a header a gateway was *trusted* to have
set. The refactor purged all of it. The substitutions are mechanical:

| Removed (product leakage) | Replaced with (generic) |
|---|---|
| `X-Party-Id` trusted header | identity from the validated `SecurityContextPort` / `SecurityPrincipal` |
| `partyId` (UUID) | `subject` (String) from the token |
| `contractId` / `productId` scoping | generic `tenantId` + open `attributes` map |
| `UserRoleEnum` business roles (AGENT/ADMIN/…) | plain `Set<String>` authorities |
| Security Center `SessionManager` + DTOs | `PolicyDecisionPort` + `AuthorityMappingPort` |

The point is not that Lumen *loses* party and contract — it is that the **framework** no
longer knows about them. A product re-introduces its own domain through
`SecurityPrincipal.attributes` and a `PrincipalAttributeContributorPort`, reading party and
contract off the *validated* claims. The framework ships none of those fields, so it can
secure a lending platform, a payments rail, and a brokerage with the same code.

!!! note "Key term — `SecurityPrincipal`"
    The framework's immutable, product-agnostic record of an authenticated subject: subject,
    issuer, optional tenant, normalized authorities and scopes, raw claims, and an attributes
    map for product enrichment. It is a projection of a *signature-validated* authentication,
    propagated through the Reactor `Context`, and injectable into a handler with
    `@AuthenticationPrincipal`. It replaces both Spring's `JwtAuthenticationToken` and the
    old `AppContext` principal facade — and it deliberately holds no `party`/`contract`/`product`.

## Standing up the resource server

There is almost nothing to write. The resource-server starter is auto-configured: drop it on
the classpath and a complete, locked-down chain assembles itself. To see it end to end, here
is the minimal Lumen application the platform's own integration test boots — a controller
with one public route, one protected route that echoes the caller, and one route a policy
will fence off.

::: listing fireflyframework-security-resource-server/src/test/java/org/fireflyframework/security/rs/ResourceServerIntegrationTest.java | Listing 19a.2 — a complete secured app: public, protected, and policy-denied routes
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

Read what is — and is not — here. The controller is plain WebFlux. `/api/me` takes a
`@AuthenticationPrincipal SecurityPrincipal` and gets the *validated* principal handed to it,
no header parsing, no context plumbing. There is no `@EnableWebFluxSecurity`, no
`SecurityWebFilterChain` bean, no decoder, no validator wiring — the starter contributed all
of it. The only security code in the whole app is the one `PolicyRule` bean, and we will come
back to it. To make `/public/**` reachable without a token, the test sets exactly one
property — the loud, explicit opt-out:

::: listing fireflyframework-security-resource-server/src/test/java/org/fireflyframework/security/rs/ResourceServerIntegrationTest.java | Listing 19a.3 — the only opt-out: an explicit permit matcher
@SpringBootTest(
        classes = ResourceServerIntegrationTest.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "firefly.security.resource-server.permit-matchers=/public/**",
                "spring.main.banner-mode=off"
        })
class ResourceServerIntegrationTest {
:::

That single line — `firefly.security.resource-server.permit-matchers=/public/**` — is the
entire public surface. Every other path is denied by default. In a real Lumen service you
would list the same operational routes Chapter 19 enumerated — `/actuator/health`,
`/v3/api-docs/**`, the Swagger UI — and nothing else. The contrast with Chapter 19's chain is
the whole story: there the catch-all was `anyExchange().permitAll()` and the real check lived
elsewhere; here the catch-all *denies*, and you carve out the few public paths by name.

!!! note "Key term — `firefly.security.resource-server.permit-matchers`"
    The explicit, named allow-list of Ant-style path patterns that are reachable *without* a
    validated token. It is the only opt-out from default-deny the resource server offers —
    there is no global "permit all." Anything not matched requires a signature-validated
    bearer token (and then must clear the policy decision). Keep it to operational and
    documentation routes; never widen it to `/**`.

### What the auto-configuration actually wires

It is worth pausing on *how* "drop the jar and it's secure" works, because the mechanism is
plain Spring Boot auto-configuration — no magic. The starter ships one
`@AutoConfiguration` class registered in
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, gated so
it only activates for a reactive web app and only when not explicitly disabled:

::: listing fireflyframework-security-resource-server/src/main/java/org/fireflyframework/security/rs/ResourceServerAutoConfiguration.java | Listing 19a.4 — the auto-configuration: conditional, secure-by-default, fully overridable
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnProperty(prefix = "firefly.security.resource-server", name = "enabled", matchIfMissing = true)
@EnableWebFluxSecurity
@EnableConfigurationProperties(ResourceServerProperties.class)
public class ResourceServerAutoConfiguration {
    // KeyManagementPort, AuthorityMappingPort, PrincipalFactory, PolicyDecisionPort,
    // AuditEventPort, SecurityContextPort, the JWT→principal Converter, the decoder,
    // and the SecurityWebFilterChain — every one @ConditionalOnMissingBean.
}
:::

Three conditions do all the work. `@ConditionalOnWebApplication(REACTIVE)` means the chain
only assembles for a WebFlux app — a pure library or a batch job stays untouched.
`@ConditionalOnProperty(... matchIfMissing = true)` means it is *on by default* and the single
documented escape hatch is `firefly.security.resource-server.enabled=false` (used, for
example, when a service deliberately fronts its own chain). And every bean inside is
`@ConditionalOnMissingBean`: declare your own `PolicyDecisionPort`, your own decoder, or your
own `SecurityWebFilterChain`, and yours wins. Secure-by-default, but never a cage.

## Validating the JWT: signature first, then iss/aud/exp

The heart of the platform is that a token is *cryptographically verified* before it becomes a
principal. The auto-configuration builds a `NimbusReactiveJwtDecoder` from the active signing
key's public half and wraps it in a chain of validators. Here is the real decoder bean.

::: listing fireflyframework-security-resource-server/src/main/java/org/fireflyframework/security/rs/ResourceServerAutoConfiguration.java | Listing 19a.5 — the verifying decoder: RS256 signature plus timestamp/issuer/audience validators
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

Three things to internalize. The decoder is built `withPublicKey(...)` and pinned to `RS256`
— so a token's signature must verify against *that* public key or decoding fails outright;
this is the line that makes a forged token impossible. The `JwtTimestampValidator` rejects
expired (and not-yet-valid) tokens. And `iss`/`aud` validators are added *conditionally*: set
`firefly.security.resource-server.issuer` and an issuer check is enforced; list
`audiences` and an audience check is enforced. The integration test runs without either, so
it exercises the pure signature-plus-expiry path — but a production Lumen deployment would
pin both to its identity provider.

Where does the public key come from? A `KeyManagementPort`. In dev/test the starter supplies
an `InMemoryKeyManagementAdapter` that generates an RSA pair on boot; in production you swap
it for Vault Transit or a cloud KMS, gaining `kid`-based key rotation and a published JWKS —
without touching this decoder code. (The design is emphatic that a missing key source under a
prod profile is a *startup failure*, not a silent HS256 fallback.) The port itself is small,
and its contract carries the rotation discipline:

::: listing fireflyframework-security-spi/src/main/java/org/fireflyframework/security/spi/KeyManagementPort.java | Listing 19a.6 — the key-management SPI: active key, verification keys, JWKS, rotation
public interface KeyManagementPort {

    /** @return the current key used to sign newly issued tokens (has a private key). */
    Mono<SigningKey> activeSigningKey();

    /** @return all currently-valid keys for verification (active + overlapping previous). */
    Flux<SigningKey> verificationKeys();

    /** @return the published JWKS document as JSON (public keys only). */
    Mono<String> jwkSetJson();

    /** Rotate to a fresh signing key, retaining the previous for the overlap window. */
    Mono<Void> rotate();
}
:::

`verificationKeys()` is the seam that makes rotation safe: when a key rotates, the previous
public key lingers in the verification set for an overlap window, so tokens minted under the
old `kid` keep verifying until they expire. `SigningKey` itself uses only JDK crypto types
(`PrivateKey`/`PublicKey`), so the *port* stays free of any JOSE dependency — Nimbus lives in
the resource-server module, not in the SPI.

!!! note "Key term — `KeyManagementPort`"
    The SPI that owns first-party signing keys and the JWKS the resource server verifies
    against. `activeSigningKey()` returns the current key (with a private half for signing);
    `verificationKeys()` keeps the previous public key during a rotation overlap so in-flight
    tokens stay valid. Backends: an in-memory dev generator (default), HashiCorp Vault
    Transit, and AWS/Azure KMS — selected by configuration, with the decoder above unchanged.

### Proving it: valid in, forged and expired out

The integration test signs real tokens with the framework's own active key and drives them
through `WebTestClient`. First, the happy path and the public route.

::: listing fireflyframework-security-resource-server/src/test/java/org/fireflyframework/security/rs/ResourceServerIntegrationTest.java | Listing 19a.7 — a public route needs no token; a protected route needs a valid one
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

The protected route returns `401` with no token and, with a properly signed one, returns the
principal's subject — `alice` — proving the decoder ran, the converter built a
`SecurityPrincipal`, and `@AuthenticationPrincipal` handed it to the method. The signing
helpers are ordinary Nimbus, using the key the framework itself published:

::: listing fireflyframework-security-resource-server/src/test/java/org/fireflyframework/security/rs/ResourceServerIntegrationTest.java | Listing 19a.8 — signing a real RS256 token and the claims that ride in it
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

Note the claims `alice` carries: a `scope` of `read write` and a `roles` array holding
`teller`. Those are the raw inputs the next section maps into the principal's `authorities`
and `scopes`. Now the two rejection paths — expired, and forged by an unknown key:

::: listing fireflyframework-security-resource-server/src/test/java/org/fireflyframework/security/rs/ResourceServerIntegrationTest.java | Listing 19a.9 — expired and forged tokens are both 401
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

The forged-token test is the one that would have *passed* under the old local-parse scheme
and silently authorized an attacker. Here it constructs a *second*, throwaway
`InMemoryKeyManagementAdapter` — a different RSA pair the server has never seen — signs a
token whose claims are otherwise perfect, and gets `401`. The signature does not verify
against the server's public key, so the token never becomes a principal. That single test is
the whole "signatures are actually validated now" promise, made executable.

## Mapping claims to authorities

A verified token is just claims; turning `roles: [teller]` and `scope: "read write"` into the
principal's `authorities` and `scopes` is the job of the `AuthorityMappingPort`. The default
implementation, `ConfigurableAuthorityMapper`, reads configured *dot-paths* out of the claim
set, which is how one mapper handles Keycloak, Cognito, and Entra without per-vendor code.

::: listing fireflyframework-security-core/src/main/java/org/fireflyframework/security/core/authority/AuthorityMappingProperties.java | Listing 19a.10 — the default claim paths span the major IdPs
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

Out of the box, roles are read from `roles`, `realm_access.roles` (Keycloak's nested shape),
`cognito:groups` (AWS), or `groups` (Entra) — whichever is present — and scopes from `scope`
or `scp`. The dot-path `realm_access.roles` navigates nested JSON (the `ClaimPaths` reader
walks each segment through nested maps); the same reader also splits a space- or
comma-delimited string, so `"read write"` becomes two scopes. `alice`'s `roles: [teller]`
therefore lands as the authority `teller`, and her `scope: "read write"` as scopes `read` and
`write`. You can override the paths and add an `authorityPrefix` (say `ROLE_`) per service via
`firefly.security.resource-server.*` without writing a mapper.

The mapper feeds a `PrincipalFactory`, which assembles the final `SecurityPrincipal` — and a
small `JwtToFireflyPrincipalConverter` replaces Spring's default token so your handler
receives the rich Firefly principal rather than a bare `JwtAuthenticationToken`. The chain
from raw `Jwt` to injectable principal is three short, swappable beans, all auto-configured:

::: listing fireflyframework-security-resource-server/src/main/java/org/fireflyframework/security/rs/JwtToFireflyPrincipalConverter.java | Listing 19a.11 — the seam that swaps Spring's token for the Firefly principal
@Override
public Mono<AbstractAuthenticationToken> convert(Jwt jwt) {
    String issuer = jwt.getIssuer() == null ? null : jwt.getIssuer().toString();
    SecurityPrincipal principal = principalFactory.fromClaims(jwt.getSubject(), issuer, jwt.getClaims());
    return Mono.just(new FireflyAuthenticationToken(principal));
}
:::

The `FireflyAuthenticationToken` it returns is a real Spring `Authentication`: its
authorities are derived from the principal, so `hasAuthority(...)` and
`authorizeExchange().hasRole(...)` work natively, while `@AuthenticationPrincipal
SecurityPrincipal` recovers the rich object. That dual nature is why the rest of the chain —
URL enforcement, method security, your handler — can all read identity the same way.

!!! note "Key term — `AuthorityMappingPort`"
    The SPI that turns validated claims into the principal's normalized `authorities` and
    `scopes`. The default `ConfigurableAuthorityMapper` reads dot-path claim locations
    (`roles`, `realm_access.roles`, `cognito:groups`, …) and an optional authority prefix —
    so swapping identity providers is a configuration change, not a code change. It absorbs
    the old, duplicated `JwtClaimsRoleExtractor`.

!!! spring "Spring parity"
    Stock Spring gives you a `JwtAuthenticationConverter` and a `JwtGrantedAuthoritiesConverter`
    you configure per service to find your roles claim. Firefly's `AuthorityMappingPort` is
    the same idea with two upgrades: a fleet-wide default that already knows the major IdP
    claim shapes, and a product-agnostic `SecurityPrincipal` as the output instead of Spring's
    `Jwt`-bound token — so application code never imports an OAuth2 type to read identity.

## The embedded policy decision point

Authentication answers *who*; authorization answers *may they*. After a token validates, the
resource server's URL enforcement point routes the request through a `PolicyDecisionPort` — an
externalized ABAC decision point that says PERMIT, DENY, or INDETERMINATE for a given
subject, action, and resource. The port itself is one method, and its contract is *fail-closed*:

::: listing fireflyframework-security-spi/src/main/java/org/fireflyframework/security/spi/PolicyDecisionPort.java | Listing 19a.12 — the ABAC decision SPI: one method, fail-closed by contract
public interface PolicyDecisionPort {

    Mono<Decision> authorize(SecurityPrincipal principal, String action, String resource, Map<String, Object> context);
}
:::

The framework ships a zero-dependency **embedded** adapter that combines in-process
`PolicyRule` beans with deny-overrides semantics, strictly fail-closed.

::: listing fireflyframework-security-core/src/main/java/org/fireflyframework/security/core/policy/EmbeddedPolicyDecisionAdapter.java | Listing 19a.13 — the embedded PDP: deny-overrides, fail-closed
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

Read the rules of combination. If *any* registered rule denies, the request is denied (deny
overrides). Otherwise, if at least one rule permits, it is permitted. If rules are registered
but none permit — all abstain — the request is *denied*. And a rule that throws is caught and
converted to INDETERMINATE, never to a permit. There is one deliberate convenience: if *no*
rules are registered at all, the embedded PDP permits, deferring authorization to RBAC at the
policy enforcement point (the validated-token requirement still applies). Lumen's one rule
fences off the sensitive route:

::: listing fireflyframework-security-resource-server/src/test/java/org/fireflyframework/security/rs/ResourceServerIntegrationTest.java | Listing 19a.14 — one embedded policy rule denies a sensitive resource
@Bean
PolicyRule denyDeniedPath() {
    return (principal, action, resource, context) ->
            "/api/denied".equals(resource)
                    ? Mono.just(Decision.deny("blocked by policy"))
                    : Mono.just(Decision.permit());
}
:::

A `PolicyRule` is a functional interface over `(principal, action, resource, context)` →
`Mono<Decision>`, where the URL enforcement point passes the HTTP method as the *action* and
the request path as the *resource*. This rule denies `/api/denied` and permits everything
else. Crucially, it runs *after* authentication — so the caller is a fully validated
`alice` — and still denies. That is authentication and authorization doing different jobs:

::: listing fireflyframework-security-resource-server/src/test/java/org/fireflyframework/security/rs/ResourceServerIntegrationTest.java | Listing 19a.15 — a valid token still gets 403 when policy denies
@Test
void policyDeniedRouteIsForbiddenEvenWithValidToken() throws Exception {
    String token = signWith(keyManagementPort.activeSigningKey().block(), validClaims().build());
    client.get().uri("/api/denied").header("Authorization", "Bearer " + token)
            .exchange().expectStatus().isForbidden();
}
:::

`alice` presents a perfectly valid token and gets `403` — not `401`. The distinction is the
point: `401` means "I don't know who you are," `403` means "I know exactly who you are and
you may not do this." For Lumen, a rule like this is where you encode "a `teller` may read an
application but may not move funds," reading the principal's `authorities` and the resource
path to decide.

### How the URL enforcement point calls the port

The bridge between Spring's filter chain and the `PolicyDecisionPort` is one
`ReactiveAuthorizationManager` in the webflux binding. It is worth seeing, because it makes
"fail-closed" concrete — note the `onErrorReturn(false)` and the `defaultIfEmpty(false)`:

::: listing fireflyframework-security-webflux/src/main/java/org/fireflyframework/security/webflux/authz/PolicyAuthorizationManager.java | Listing 19a.16 — the URL PEP: action = HTTP method, resource = path, fail-closed
@Override
public Mono<AuthorizationDecision> check(Mono<Authentication> authentication, AuthorizationContext context) {
    ServerHttpRequest request = context.getExchange().getRequest();
    String action = request.getMethod().name();
    String resource = request.getPath().value();
    Map<String, Object> ctx = Map.of("method", action, "path", resource);

    return authentication
            .filter(Authentication::isAuthenticated)
            .flatMap(auth -> policyDecisionPort
                    .authorize(PrincipalSupport.extract(auth), action, resource, ctx)
                    .map(decision -> new AuthorizationDecision(decision.granted())))
            .onErrorReturn(new AuthorizationDecision(false))
            .defaultIfEmpty(new AuthorizationDecision(false));
}
:::

Every escape route from this method denies. An unauthenticated request is filtered out and
falls to `defaultIfEmpty(false)`. A policy engine that errors falls to `onErrorReturn(false)`.
Only an explicit `Decision.granted()` yields `true`. This is the same fail-closed posture as
the embedded adapter, enforced one layer up — defense in depth between the engine and the PEP.

!!! note "Key term — `PolicyDecisionPort` / `PolicyRule`"
    The `PolicyDecisionPort` is the SPI the resource server consults for every authorization
    decision; the embedded default combines `PolicyRule` beans with **deny-overrides** and is
    **fail-closed** (errors and all-abstain both deny). A `PolicyRule` is a functional
    `(principal, action, resource, context) → Mono<Decision>`. Swap the port for OPA/Cerbos/OpenFGA
    to externalize policy with no change to handlers or rules' call sites.

!!! warning "Fail-closed is not negotiable"
    Every error path in the authorization chain denies: a policy engine that times out, a
    rule that throws, a decision that comes back INDETERMINATE — all map to `403`, never to a
    permit. This is the opposite of the old fail-open posture, and it is deliberate. When you
    write a `PolicyRule`, do not catch-and-permit on error to "keep the site up"; a security
    decision you cannot make is a denial, full stop.

## Embedded vs external: when to move policy out of the JVM

The embedded PDP is the right default — zero dependencies, in-process, fast, and good enough
for "a `teller` may read but not write." But policy that real institutions audit, version, and
change without a redeploy belongs *outside* the service. The hexagon makes that move a
one-bean change, because the consumer of `PolicyDecisionPort` (the URL PEP above) does not
know or care which adapter answers. Here is the decision in one table.

| | Embedded (default) | OPA | Cerbos | OpenFGA |
|---|---|---|---|---|
| Port | `PolicyDecisionPort` | `PolicyDecisionPort` | `PolicyDecisionPort` | `RelationshipPort` |
| Model | in-process `PolicyRule` beans | ABAC, Rego policies | ABAC, YAML resource policies | ReBAC, Zanzibar tuples |
| Lives | in the JVM | sidecar / service | sidecar / service | service |
| Change policy without redeploy | no | yes | yes | yes |
| Container-tested in this platform | n/a | ✓ | ✓ | ✓ |
| Use when | simple, code-owned rules | central, audited ABAC | relationship-light ABAC | "who can act on this object" |

The two ABAC adapters share the exact same port. OPA posts the request as `input` to a Rego
data document and reads back a boolean — fail-closed on any error:

::: listing fireflyframework-security-adapter-opa/src/main/java/org/fireflyframework/security/adapter/opa/OpaPolicyDecisionAdapter.java | Listing 19a.17 — the OPA adapter: post input, read allow, fail closed
return webClient.post()
        .uri("/v1/data/" + decisionPath)
        .bodyValue(Map.of("input", input))
        .retrieve()
        .bodyToMono(OpaResult.class)
        .map(result -> Boolean.TRUE.equals(result.result())
                ? Decision.permit()
                : Decision.deny("denied by OPA policy"))
        .onErrorResume(error -> {
            log.warn("OPA evaluation failed; failing closed: {}", error.getMessage());
            return Mono.just(Decision.indeterminate("OPA error: " + error.getMessage()));
        });
:::

Cerbos satisfies the same `PolicyDecisionPort` differently — it parses the `resource` argument
as `kind:id`, maps the principal's authorities to Cerbos roles, and calls `CheckResources`,
treating anything other than `EFFECT_ALLOW` as a denial (and a transport error as
indeterminate, i.e. denied). The point is *uniformity*: to move Lumen's URL authorization from
in-process rules to OPA, you add `fireflyframework-security-adapter-opa`, declare an
`OpaPolicyDecisionAdapter` bean (which, being `@ConditionalOnMissingBean` at the default,
replaces the embedded one), and write Rego. The controller, the `@Secure` annotations, and the
`@AuthenticationPrincipal` parameters do not move.

For relationship questions — "may *this* underwriter act on *that* loan file" — the model is
not attributes but **relationships**, and the port is `RelationshipPort`, backed by OpenFGA:

::: listing fireflyframework-security-spi/src/main/java/org/fireflyframework/security/spi/RelationshipPort.java | Listing 19a.18 — the ReBAC SPI: a Zanzibar-style relationship check
public interface RelationshipPort {

    Mono<Boolean> check(String subject, String relation, String object);
}
:::

The OpenFGA adapter issues a tuple `check` against a store and maps `allowed` to a boolean,
failing closed on any transport error. ABAC (OPA/Cerbos) and ReBAC (OpenFGA) compose: a Lumen
rule can demand both an attribute ("operates in this region") and a relationship ("is assigned
to this contract") before permitting a funds movement.

!!! spring "Spring parity"
    Spring gives you `ReactiveAuthorizationManager` and `PermissionEvaluator` hooks but no
    opinion on *where* policy lives. Firefly's `PolicyDecisionPort` / `RelationshipPort` are
    that opinion made portable: the same call site serves an in-process rule, an OPA sidecar,
    a Cerbos service, or an OpenFGA store, and every adapter is fail-closed by contract.

## Hardened headers on every response

Secure-by-default extends past the request: the resource server writes a hardened header set
on *all* responses, public ones included. The same `SecurityWebFilterChain` that wires the
decoder also configures the header writers.

::: listing fireflyframework-security-resource-server/src/main/java/org/fireflyframework/security/rs/ResourceServerAutoConfiguration.java | Listing 19a.19 — hardened security headers, configured once for the whole app
.headers(headers -> headers
        .frameOptions(frame -> frame.mode(XFrameOptionsServerHttpHeadersWriter.Mode.DENY))
        .referrerPolicy(referrer -> referrer.policy(
                ReferrerPolicyServerHttpHeadersWriter.ReferrerPolicy.NO_REFERRER))
        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
        .hsts(hsts -> hsts.includeSubdomains(true).maxAge(Duration.ofDays(365))));
:::

`X-Frame-Options: DENY` blocks clickjacking, a strict `Content-Security-Policy` and
`Referrer-Policy: no-referrer` lock down content and referrers, and a year-long HSTS policy
(with subdomains) forces HTTPS. The same chain also disables CSRF, HTTP Basic, form login, and
logout — a pure bearer API needs none of them, and leaving Boot's defaults on is how the old
platform silently leaked an HTTP-Basic prompt. The test asserts the headers land even on the
*public* ping route, because hardening should not depend on authentication:

::: listing fireflyframework-security-resource-server/src/test/java/org/fireflyframework/security/rs/ResourceServerIntegrationTest.java | Listing 19a.20 — the hardened headers are present even on public routes
@Test
void hardenedSecurityHeadersArePresent() {
    client.get().uri("/public/ping").exchange()
            .expectStatus().isOk()
            .expectHeader().valueEquals("X-Frame-Options", "DENY")
            .expectHeader().valueEquals("Referrer-Policy", "no-referrer")
            .expectHeader().exists("Content-Security-Policy");
}
:::

## `@Secure` on the method, now fail-closed

The URL-level PDP is coarse; for method-level rules the platform keeps the `@Secure`
annotation from Chapter 19 — but reworked. It is the same ergonomic declaration of required
roles, scopes, and permissions, and it now sits *on top of* validated authentication with
**fixed AND-semantics** and **default-deny**: no `security.enabled` escape hatch, no
fail-open when a context is missing.

::: listing fireflyframework-security-api/src/main/java/org/fireflyframework/security/api/annotation/Secure.java | Listing 19a.21 — the reworked @Secure: typed requirements, AND across dimensions
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

The evaluator behind it is uncompromising: a `null` principal is always denied; across
dimensions (roles, scopes, permissions, expression) *all* declared dimensions must pass;
within a dimension, `requireAll*` flips between ANY (default) and ALL; and a SpEL expression
that fails or throws denies. Reading the evaluator confirms the semantics are exactly that:

::: listing fireflyframework-security-core/src/main/java/org/fireflyframework/security/core/authz/SecureAuthorizationEvaluator.java | Listing 19a.22 — the @Secure evaluator: null denies, dimensions AND, requireAll flips ANY/ALL
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
    // scopes and permissions follow the same ANY/ALL pattern …
    if (!requirement.expression().isBlank() && !evaluateExpression(principal, requirement.expression())) {
        return Decision.deny("authorization expression not satisfied");
    }
    return Decision.permit();
}
:::

The Chapter 19 model silently treated `requireAll*` as ANY and skipped the check when no
context was present — both are fixed here. On a Lumen handler that reads an application, you
would write `@Secure(scopes = "read")`; on one that submits a credit decision,
`@Secure(roles = "underwriter", scopes = "write")`, and the caller must hold *both*
dimensions. One annotation Chapter 19 carried is gone: `@RequireContext` was removed, because
it existed only to gate the old party/contract context — with default-deny and a validated
principal, "is there an identity at all" is no longer a separate question to ask.

### The mechanism: a reactive method-security interceptor

`@Secure` is not bespoke AOP anymore; it rides Spring's reactive method-security machinery.
The `security-method-policy` starter enables `@EnableReactiveMethodSecurity` and registers one
interceptor whose pointcut matches `@Secure` on a method or its class, delegating to a
`SecureMethodAuthorizationManager` that calls the same evaluator you just read:

::: listing fireflyframework-security-method-policy/src/main/java/org/fireflyframework/security/method/SecureMethodAuthorizationManager.java | Listing 19a.23 — @Secure enforced on the reactive method-security interceptor, fail-closed
@Override
public Mono<AuthorizationDecision> check(Mono<Authentication> authentication, MethodInvocation invocation) {
    Secure secure = resolve(invocation);
    if (secure == null) {
        return Mono.just(new AuthorizationDecision(true));
    }
    SecureRequirement requirement = SecureRequirement.from(secure);
    return authentication
            .filter(Authentication::isAuthenticated)
            .map(auth -> new AuthorizationDecision(
                    evaluator.evaluate(PrincipalSupport.extract(auth), requirement).granted()))
            .defaultIfEmpty(new AuthorizationDecision(false));
}
:::

The `defaultIfEmpty(false)` is the fail-closed default: an unauthenticated invocation never
reaches the evaluator and is denied. Because the interceptor is a real reactive
`AuthorizationManager`, `@Secure` and Spring's own `@PreAuthorize` enforce through the *same*
mechanism — you can mix them on the same service, and both read the same `SecurityPrincipal`.

!!! spring "Spring parity"
    `@Secure` is sugar over `@EnableReactiveMethodSecurity` + `@PreAuthorize`. You can use
    Spring's `@PreAuthorize("hasAuthority('underwriter') and hasAuthority('SCOPE_write')")`
    directly against the same `SecurityPrincipal`; `@Secure` is the typed, enumerable form
    that avoids hand-writing SpEL and keeps the requirement machine-readable. Both enforce on
    the reactive method-security interceptor — the mechanism is identical; the ergonomics differ.

## The one seam to an IdP: `TokenValidationPort`

So far the resource server validated tokens against its own in-memory key — fine for tests,
but Lumen in production trusts an external identity provider. The platform keeps the IdP tier
and the security tier decoupled, joined by exactly *one* SPI: the `TokenValidationPort`.

::: listing fireflyframework-security-spi/src/main/java/org/fireflyframework/security/spi/TokenValidationPort.java | Listing 19a.24 — the single join between the IdP tier and the security tier
public interface TokenValidationPort {

    Mono<SecurityPrincipal> validate(BearerToken token);
}
:::

One method: take a bearer token, return a validated `SecurityPrincipal` — or fail. Its
contract is the whole security posture in one sentence: it validates
signature/issuer/audience/expiry for JWTs (or RFC 7662 introspection for opaque tokens) and
*never returns an unvalidated principal*. The `BearerToken` it accepts even classifies itself
— a three-segment dotted token is treated as a JWT, anything else as opaque — so the
validation layer can pick the right strategy. The JWT path you have already seen — decoder plus
JWKS plus validators. The opaque path delegates to the IdP's introspection endpoint (the
segregated `TokenIntrospectionPort`) behind a TTL-bounded cache. Either way, this port is the
*only* place identity crosses from "a vendor's token" to "the framework's principal."

That is what makes the provider swappable. The IdP tier is itself a clean hexagon — the old
fat `IdpAdapter` was segregated into six focused capability ports (`AuthenticationPort`,
`TokenIntrospectionPort`, `UserAdminPort`, `RoleScopePort`, `SessionPort`, `MfaPort`), each
with `NotSupported` defaults, so a provider implements only what it offers. To move Lumen from
the in-memory dev key to Keycloak, you add the Keycloak adapter (which fronts Keycloak's JWKS
through `TokenValidationPort` and registers the trusted issuer) and set the issuer/audience
properties — and your controllers, your `@Secure` annotations, your `PolicyRule` beans, and
your `@AuthenticationPrincipal` parameters do not change, because they depend on the
`SecurityPrincipal` the port produces, never on a Keycloak type. Cognito and Entra plug in the
same way. This is the hexagonal pattern the whole book has shown — port stable, adapter
replaceable — applied to the most sensitive seam in the platform, and it mirrors how the Python
and Rust ports already structure their security tier.

!!! note "Key term — `TokenValidationPort`"
    The single SPI joining the IdP tier and the security tier: `validate(BearerToken)` →
    `Mono<SecurityPrincipal>`, failing on any validation error. JWTs go through the verifying
    decoder + JWKS; opaque tokens go through IdP introspection + a TTL-bounded cache. Because
    it is the *only* join, choosing or swapping an identity provider is an adapter + property
    change with no impact on handlers, policies, or `@Secure` rules. See Appendix B for the
    adapter catalog.

## Beyond the request: revocation and secrets ports

Two more SPI ports round out the platform, and each ships a container-tested adapter, so it is
worth knowing they exist before you need them.

**Revocation.** A validly-signed, unexpired token is still valid by signature alone — but a
fired employee's session should die immediately. The `RevocationPort` tracks revoked token ids
so even a cryptographically perfect token can be rejected, and the R2DBC adapter persists the
list in a relational table with self-expiring rows:

::: listing fireflyframework-security-adapter-r2dbc/src/main/java/org/fireflyframework/security/adapter/r2dbc/R2dbcRevocationAdapter.java | Listing 19a.25 — the R2DBC revocation adapter: a self-expiring revocation list
private static final String EXISTS_ACTIVE = """
        SELECT 1 FROM security_revoked_token
        WHERE token_id = :id AND (expires_at IS NULL OR expires_at > now())
        """;
:::

An entry counts as "revoked" only while its `expires_at` is in the future; once the token
would have expired anyway, the row stops mattering and a scheduled cleanup can prune it without
races. The platform's integration test runs this against a real PostgreSQL container.

**Secrets.** Credentials must not live in YAML. The `SecretsPort` resolves named secrets from
an external store at runtime, and the Vault adapter reads HashiCorp Vault's KV v2 engine,
failing *closed* if a required secret is missing so a service fails fast at startup rather than
running half-configured. That adapter, too, is verified against a real Vault container.

Both follow the same hexagonal discipline as the policy adapters: a one-method port in `spi`, a
real adapter behind it, and a consumer (the validation chain, the startup wiring) that never
imports the vendor SDK. Every driven port in the platform has at least one real adapter, and
the five most security-sensitive — Postgres-backed revocation, OPA, Cerbos, OpenFGA, and Vault
— are each proven against a genuine Docker container in CI.

## Securing a service: with the starter, and without it

The same secure-by-default resource server reaches a Lumen service two ways. Knowing both is
the difference between "it just works" and "I understand why it works."

### With the application starter (the default)

`fireflyframework-starter-application` declares the resource server and method security as
ordinary dependencies:

```xml
<dependency>
    <groupId>org.fireflyframework</groupId>
    <artifactId>fireflyframework-security-resource-server</artifactId>
</dependency>
<dependency>
    <groupId>org.fireflyframework</groupId>
    <artifactId>fireflyframework-security-method-policy</artifactId>
</dependency>
```

So any application-tier Lumen service inherits the *entire* posture transitively: default-deny,
JWT validation, the claim-to-authority mapper, hardened headers, the embedded PDP, and
`@Secure` method security — with **zero security code**. The service's only obligation is to
name its public routes in `firefly.security.resource-server.permit-matchers` and to annotate
sensitive methods with `@Secure`. This is the path most Lumen services take, and it is exactly
what the integration test exercises (its `@SpringBootApplication` pulls the same
auto-configuration the starter does).

### Without the starter (a standalone resource server)

A service that is *not* on the application starter — a thin gateway, a legacy module, a
specialized worker exposing one reactive endpoint — secures itself by adding the resource
server (and method-policy for `@Secure`) directly:

```xml
<dependency>
    <groupId>org.fireflyframework</groupId>
    <artifactId>fireflyframework-security-resource-server</artifactId>
</dependency>
```

It receives the *identical* `ResourceServerAutoConfiguration`. Because that class is
`@ConditionalOnWebApplication(REACTIVE)`, it activates only for a reactive web app and stays
out of a library's way; because it is `@ConditionalOnProperty(... matchIfMissing = true)`, it
is on by default and the one documented opt-out is
`firefly.security.resource-server.enabled=false`; and because every bean inside is
`@ConditionalOnMissingBean`, the standalone service can override any single piece — its own
`PolicyDecisionPort`, its own decoder, its own `SecurityWebFilterChain` — without forking the
starter. Standalone and starter-borne services therefore behave the same, which is the whole
promise of secure-by-default: there is one chain, and it is locked.

!!! tip "Which path am I on?"
    If your service's `pom.xml` depends on `fireflyframework-starter-application`, you are on
    the *with-starter* path — you already have the resource server and `@Secure`; just declare
    your public routes. If it does not, add `fireflyframework-security-resource-server`
    (and `-method-policy` for `@Secure`) yourself. Either way the runtime behaviour, the
    properties, and the 401/403 matrix are identical.

## Run it

The whole chapter is one integration test, and it boots a real resource server on a random
port — auto-configured chain, verifying decoder, in-memory key, embedded PDP, hardened
headers — then drives signed tokens through `WebTestClient`. From the security module:

```text
mvn -q -pl fireflyframework-security-resource-server test
```

You should see all seven scenarios pass:

```text
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- in org.fireflyframework.security.rs.ResourceServerIntegrationTest
[INFO] BUILD SUCCESS
```

Those seven are the chapter in executable form: a public route through without a token; a
protected route `401` without one and `200` with a valid one (echoing `alice`); an expired
token `401`; a forged token signed by an unknown key `401`; a policy-denied route `403`
*despite* a valid token; and the hardened headers present even on the public route. Read them
as a checklist of the posture — every one is a property the old model could not guarantee. And
they are the tip of a larger pyramid: across the platform, the OPA, Cerbos, OpenFGA, Vault, and
Postgres adapters each run their own integration test against a live container, so "fail-closed"
and "every port has a real adapter" are not claims but green builds.

!!! tip "Checkpoint"
    Run the command and confirm `Tests run: 7, Failures: 0`. Then prove the default-deny
    posture yourself: comment out the `permit-matchers` property and rerun — the
    `publicRouteIsAccessibleWithoutToken` test now fails with `401`, because *nothing* is
    public unless you name it. Restore the property and it passes again. You just watched
    secure-by-default refuse to let a route through until you opted it out, loudly.

## What you built {.recap}

- A **secure-by-default resource server** that needs no security code: dropping the
  resource-server starter on the classpath auto-configures the full chain — verifying JWT
  decoder, claim-to-authority mapping, a default-deny filter chain, and hardened headers —
  gated by `@ConditionalOnWebApplication(REACTIVE)` and fully overridable per bean.
- **Real signature validation**: a `NimbusReactiveJwtDecoder` pinned to the
  `KeyManagementPort`'s RS256 public key, with timestamp and optional issuer/audience
  validators — so an expired token and a token forged by an unknown key both come back
  `401`, the exact case the old local-parse scheme let through.
- **Claims mapped to authorities** through the `AuthorityMappingPort`, whose default dot-paths
  (`roles`, `realm_access.roles`, `cognito:groups`, `scope`/`scp`) span Keycloak, Cognito, and
  Entra — turning `alice`'s `roles: [teller]` and `scope: "read write"` into a
  `SecurityPrincipal`'s authorities and scopes with no per-vendor code.
- A **product-agnostic principal** with no `X-Party-Id`, party, contract, or product — identity
  comes only from the validated token, and products re-add their domain through `attributes`.
- **Default-deny** as the posture: one explicit `permit-matchers` line is the only public
  surface; every other route requires a validated token, and the `security.enabled` toggle is
  gone for good.
- The **embedded policy decision point** — `PolicyRule` beans combined with deny-overrides,
  strictly fail-closed at both the engine and the URL PEP — fencing off `/api/denied` so even a
  valid token gets `403`, swappable for **OPA / Cerbos** (`PolicyDecisionPort`) or **OpenFGA**
  (`RelationshipPort`) through the same ports.
- The reworked **`@Secure`** — fixed AND-semantics, default-deny, no fail-open — enforced on
  Spring's reactive method-security interceptor on top of validated authentication.
- The single **`TokenValidationPort`** seam where a real IdP plugs in (its tier itself
  segregated into six capability ports), leaving controllers, policies, and `@Secure`
  annotations untouched when Lumen moves from the dev key to Keycloak.
- The supporting **`RevocationPort`** (R2DBC/Postgres) and **`SecretsPort`** (Vault) ports,
  each with a container-tested adapter — every driven SPI port has at least one real adapter.
- The platform reaching a service **with the application starter** (transitive, zero code) and
  **without it** (a standalone resource server you add by hand) — identical runtime behaviour
  either way.
- A green **`Tests run: 7, Failures: 0`** integration run proving valid-in / forged-out /
  expired-out / policy-denied / headers-present, end to end.

## Try it yourself {.exercises}

1. **Break the public route.** Remove the `firefly.security.resource-server.permit-matchers`
   property from the test and rerun. Watch `publicRouteIsAccessibleWithoutToken` fail with
   `401`, then explain in one sentence why "nothing is public" is the safe default and why the
   permit list is the *only* way to opt a route out.
2. **Pin the issuer.** Set `firefly.security.resource-server.issuer` to a value the test
   tokens do not carry, then add an `issuer` claim to `validClaims()`. Confirm a token with
   the *wrong* issuer now returns `401` even though its signature is valid — and name the
   validator in `fireflyReactiveJwtDecoder` that did it.
3. **Add an authority-based policy.** Replace the `denyDeniedPath` rule with one that permits
   `/api/me` only when `principal.hasAuthority("teller")` and denies otherwise. Sign a token
   whose `roles` claim is `[viewer]` instead of `[teller]` and confirm it now gets `403`,
   proving the claim-to-authority mapping and the PDP are wired together.
4. **Watch fail-closed twice.** Write a `PolicyRule` whose `evaluate` throws a `RuntimeException`
   for one path. Confirm a request to that path is denied (`403`), not permitted — then read
   *both* `EmbeddedPolicyDecisionAdapter.combine` and `PolicyAuthorizationManager.check` and
   explain the two independent reasons a throwing rule can never produce a permit.
5. **Secure a method.** Add a fourth route to the test controller annotated
   `@Secure(roles = "underwriter", scopes = "write")`. Sign a token holding only
   `roles: [underwriter]` (no `write` scope) and confirm it is denied — then add the scope and
   confirm it passes, demonstrating the AND-across-dimensions rule and the reactive interceptor.
6. **Externalize the policy.** Without changing the controller or the `@Secure` annotations,
   sketch the move from the embedded PDP to OPA: which dependency you add, which bean you
   declare to replace `EmbeddedPolicyDecisionAdapter`, and what the OPA `input` document
   carries (look at `OpaPolicyDecisionAdapter`). Note that the URL PEP and your handler are
   untouched — that invariance is the hexagon paying off.
7. **Trace the provider swap.** Without changing the controller, the `PolicyRule`, or any
   `@Secure` annotation, list exactly what you would change to validate tokens from Keycloak
   instead of the in-memory key: which adapter satisfies `TokenValidationPort`, which two
   properties pin the issuer and audience, and which `KeyManagementPort` backend you would use
   in production. Confirm against Appendix B that the handler stays untouched.
8. **Secure a standalone service.** Take a reactive service that is *not* on the application
   starter, add `fireflyframework-security-resource-server` directly, and confirm it boots
   locked-down with the same default-deny behaviour. Then set
   `firefly.security.resource-server.enabled=false` and explain, in one sentence, why this is
   the *only* off switch — and why it is loud rather than silent.

## Where to go next

You now have a resource server that validates every signature, denies by default, maps claims
to authorities, and decides authorization through a swappable policy point — with the one
`TokenValidationPort` seam standing ready for a real IdP, and the OPA, Cerbos, OpenFGA, Vault,
and R2DBC adapters standing ready behind their ports. But a locked-down read endpoint still
hits the tier below it on every call. Chapter 20 turns to **caching**: how Firefly's reactive
`CacheAdapter` port serves repeated reads from a local Caffeine layer (and an optional
distributed L2) without a vendor SDK in your code — the same hexagonal pattern you just applied
to identity, applied now to speed.
