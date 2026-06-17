Every chapter so far let a request straight through to the handler. That was fine
while you were learning the reactive stack, but a lending platform cannot ship that
way: creating a loan application is a privileged act, and reading one back exposes a
customer's financial life. The experience tier — the channel-facing BFF you met in
Chapter 17, the one that fronts the live `exp → domain → core` submit flow — is where
Lumen decides *who may do what*. This chapter shows how it does, on WebFlux, with two
pieces working together.

The first piece is conventional Spring: a reactive `SecurityWebFilterChain` at the
edge of the HTTP pipeline. The second is Firefly's: a declarative `@Secure`
annotation on the controller method, enforced not by the filter chain but by a
framework AOP aspect that reads the authenticated principal and tenant out of an
application context. Splitting authorization this way — coarse transport rules in the
filter, fine permission checks at the method — is the model the application starter
encourages, and it is what lets the same controller run unchanged whether identity
comes from Keycloak, Cognito, or an internal directory.

We will slice the real `ApplicationController` and its `WebSecurityConfig` out of
`exp-lending`, trace how `@Secure` is enforced (and how one property turns that
enforcement off — the same property that lets you reach the BFF on `localhost:8080`
during a local run), sketch the `AppContext` that carries the principal, and end at
the provider-agnostic identity port that Appendix B catalogs. Then we run the slice
test that exercises the secured methods with enforcement disabled. By the end you will
be able to read every authorization decision Lumen makes, name the property that
governs it, and explain why the same annotations protect production while staying out
of the way of a `WebTestClient` test.

## Why authorization sits in two places at once

Before the code, the shape. A request to create a loan application crosses two
checkpoints in Lumen, and they answer different questions.

The **transport checkpoint** — the `SecurityWebFilterChain` — runs first, at the very
edge of the HTTP pipeline, before routing. It answers *transport-level* questions: is
this path public, does this exchange carry a valid token, should the browser's auth
dialog or CSRF token apply. It works in URLs and HTTP verbs, knows nothing about your
domain, and either rejects the exchange or lets it continue toward a controller.

The **method checkpoint** — the `@Secure` aspect — runs later, after routing has
chosen a handler, and answers a *domain* question: does this authenticated caller hold
`lending:application:create`? It works in scoped permission strings, knows exactly
which business operation is about to run, and either proceeds into your handler or
short-circuits with a `403`.

Keeping them separate is deliberate. The filter chain is the right place for blanket
rules that apply to whole swaths of URLs; the method is the right place for the
fine-grained "this operation needs this permission" rule that belongs *next to* the
operation. Lumen leans hard on the second: its filter chain is permissive on purpose,
and the real deciding happens at the method. The two sections that follow take each
checkpoint in turn.

## Spring Security on WebFlux is a filter chain, not a servlet filter

If you have secured a Spring MVC application you reached for `WebSecurityConfigurerAdapter`
or a `SecurityFilterChain` built on the servlet `Filter` stack. WebFlux is a
different runtime — there is no servlet, no `ThreadLocal`-bound `SecurityContext` —
so Spring Security exposes a parallel, reactive API. You annotate a configuration
class with `@EnableWebFluxSecurity` and publish a `SecurityWebFilterChain` bean built
from a `ServerHttpSecurity`. Here is Lumen's, whole.

::: listing exp-lending/src/main/java/com/firefly/lumen/exp/config/WebSecurityConfig.java | Listing 19.1 — the reactive security filter chain for the BFF
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

Read what this chain actually does, because the surprise is what it *does not* do. It
disables four things Spring Security switches on by default the moment
`spring-boot-starter-security` lands on the classpath — and it does land, because
`fireflyframework-starter-application` brings it transitively. (You can confirm that:
nothing in `exp-lending`'s `pom.xml` declares `spring-boot-starter-security` directly,
yet the four `disable` calls only compile because Spring Security's WebFlux types are
present.) HTTP Basic, form login, CSRF, and logout are the right defaults for a
server-rendered web app and exactly wrong for a stateless JSON BFF: HTTP Basic would
pop a browser auth dialog, CSRF would reject your `POST` without a token, form login
would redirect an API client to an HTML login page. Disabling them clears that noise.

Then `authorizeExchange` permits everything — the documented routes explicitly
(Swagger UI, the OpenAPI document, the webjars it pulls, and the operational Actuator
endpoints), and `anyExchange().permitAll()` for the rest. That looks alarming until
you see the second half of the design. This filter chain is deliberately *open* at the
transport layer because authorization in Lumen happens one layer in, at the method,
through `@Secure`. The filter chain's job here is to stop Spring Security's defaults
from interfering; the *deciding* is delegated.

Why permit the Swagger and Actuator paths *explicitly* if the catch-all already
permits everything? Two reasons, both about what happens when you later tighten the
chain. First, the explicit list documents intent: these paths are public *by design*,
not by accident, and they survive a future edit that changes `anyExchange()` to
`authenticated()`. Second, it is the safe shape to copy: when a real deployment swaps
the last line for a bearer-token rule, the health probe and the docs stay reachable
without anyone having to remember to re-add them. A different deployment could tighten
this chain to require a bearer token on `anyExchange()` and validate a JWT — the slot
is right there, and Exercise 4 walks you through sketching it — but Lumen keeps the
transport rules permissive and puts the real check on the handler.

!!! spring "Spring parity"
    Every type in this listing is stock Spring Security for WebFlux:
    `@EnableWebFluxSecurity`, `ServerHttpSecurity`, `SecurityWebFilterChain`, the
    `CsrfSpec`/`HttpBasicSpec`/`FormLoginSpec`/`LogoutSpec` lambdas, and
    `authorizeExchange`. There is no Firefly type on this page. If you have written a
    reactive security config, you have written this one. What Firefly adds is *not*
    here — it is the method-level `@Secure` model the next section introduces, which
    rides on top of this chain rather than replacing it. The mental model from servlet
    Spring carries over with one substitution: `SecurityFilterChain` becomes
    `SecurityWebFilterChain`, and `HttpSecurity` becomes `ServerHttpSecurity`.

!!! note "Key term — `SecurityWebFilterChain`"
    The WebFlux equivalent of a servlet `SecurityFilterChain`. It is a `WebFilter`
    pipeline that runs on Reactor's event loop, built fluently from a
    `ServerHttpSecurity`. Because it is reactive, the principal it resolves lives in
    the Reactor `Context`, not a `ThreadLocal` — which is why a blocking
    `SecurityContextHolder` lookup does not work on this stack, and why Firefly's
    own context (below) is propagated through the reactive chain instead.

!!! note "Key term — `permitAll` vs `authenticated`"
    The two terminal rules a `pathMatchers(...)` or `anyExchange()` clause ends in.
    `permitAll()` lets the exchange through regardless of authentication; it is *not*
    "no security" — it is "the transport layer does not gate this path" (the method
    layer still might). `authenticated()` requires a successful authentication for the
    exchange to proceed at all. Lumen uses `permitAll()` everywhere precisely because
    its gate is one layer deeper.

## The declarative check: `@Secure` on the method

Now the controller. It is an ordinary reactive `@RestController` — two methods, a
`POST` to create an application and a `GET` to read one back — but each method carries
a Firefly `@Secure` annotation declaring the permission the caller must hold.

::: listing exp-lending/src/main/java/com/firefly/lumen/exp/web/ApplicationController.java | Listing 19.2 — declarative method-level authorization with @Secure
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

The authorization rule reads like documentation: `createApplication` requires
`lending:application:create`, `getApplication` requires `lending:application:read`.
The permission strings are scoped — `resource:action`, namespaced by domain — so a
role definition in your identity provider can grant `lending:application:read`
without accidentally handing out create rights. That naming convention is not enforced
by the framework — `@Secure` takes any strings — but adopting `domain:resource:action`
fleet-wide is what lets one role catalog cover every service without collisions.

The `description` is not decoration: it feeds the endpoint security registry the
framework builds at startup — the `EndpointSecurityRegistry` named in the
controller's Javadoc — which is how a back-office screen or an audit export can list
every protected method and the permission it demands without reading the source. Each
`@Secure` method contributes one entry to that registry as the application boots, so
the registry is the machine-readable inventory of "what is protected and by what."

Notice the import in the source file: `@Secure` is
`org.fireflyframework.common.application.security.annotation.Secure`, a framework
annotation, not a Spring Security one. Spring's method security uses
`@PreAuthorize("hasAuthority('...')")` with a SpEL expression; Firefly's `@Secure`
takes a typed list of permission strings and is enforced by a framework aspect rather
than Spring's `MethodSecurityInterceptor`. The two solve the same problem; Firefly's
version is tuned for the multi-tenant, reactive, permission-string world the platform
lives in — there is no SpEL to parse at runtime, the permissions are plain strings the
registry can enumerate, and the same annotation serves both the runtime check and the
startup inventory.

Why annotate the *method* rather than the class? Because a single controller usually
mixes operations of different sensitivity — a public health summary next to a
privileged write — and method-level annotations let them coexist without splitting the
controller into "secured" and "open" classes. Here both methods happen to be secured,
but each declares its own permission, so the create and read rights are independent.

!!! note "Key term — `@Secure`"
    A Firefly method-level authorization annotation. You declare the `permissions`
    (and optionally roles) a caller must hold; the framework's `SecurityAspect`
    intercepts the call and checks them against the authenticated principal carried in
    the application context. It is declarative — no `if (user.hasPermission(...))`
    in your handler — and it lives on the *method*, so the same controller can mix
    public and protected endpoints without splitting them across classes. The optional
    `description` feeds the startup `EndpointSecurityRegistry`.

!!! note "Key term — scoped permission string"
    A permission expressed as `domain:resource:action` (here
    `lending:application:create`). The colon-separated scoping lets an identity
    provider's role grant a narrow slice — read without create, this resource without
    that one — and lets the framework's registry group permissions by domain. It is a
    convention, not a parser: `@Secure` compares the strings literally, so the
    structure is for *your* role design, not the framework's matching.

## How `@Secure` is enforced: the SecurityAspect

`@Secure` is an annotation, which means it does nothing on its own — something has to
*read* it and act. That something is the application starter's `SecurityAspect`, an
ordinary Spring AOP aspect that wraps every `@Secure` method. When a secured method is
invoked, the aspect runs first: it locates the application execution context, resolves
the caller's permissions from it, compares them against the annotation's required set,
and either proceeds to your handler or short-circuits with an authorization failure
that the web layer renders as a `403`. Your handler body never runs if the check
fails — the aspect is a gate in front of the method, not a hook inside it.

This is the same AOP machinery you have seen elsewhere in the framework: a pointcut
that matches the annotation, advice that runs around the matched join point. What is
worth internalizing is the *ordering*. The transport filter chain ran at the edge;
this aspect runs after Spring WebFlux has bound the request to a handler method but
before the method body executes. So by the time the aspect fires, routing has already
chosen `createApplication` over `getApplication`, and the aspect knows exactly which
permission set to demand.

The aspect honors a single property that governs whether it enforces at all, and that
property is the hinge of this whole chapter.

### The enforcement toggle: `firefly.application.security.enabled`

The `SecurityAspect` reads `firefly.application.security.enabled`. In production it is
`true` and the aspect enforces every `@Secure` rule. Flip it to `false` and the aspect
degrades to a deliberate no-op: it still intercepts the method — so the annotation is
genuinely exercised — but it logs that security is disabled and proceeds straight to
the handler without checking permissions. You can watch this happen. With the property
`false`, the running service prints, per intercepted call:

```text
{"timestamp":"2026-06-17T11:46:21.018+0000","message":"Security is disabled, allowing access","logger":"o.f.c.application.aop.SecurityAspect","level":"DEBUG"}
```

That single line is the entire behavior of the toggle made visible: the aspect ran
(so `@Secure` is live), saw enforcement off, and waved the call through. This is not a
test-only curiosity — it is how Lumen's *local run* works. Both the test profile and
the runnable profile set the property to `false` so you can drive the BFF without
standing up an identity provider. Here is the test profile:

::: listing exp-lending/src/test/resources/application.yml | Listing 19.3 — disabling enforcement for the slice test, without changing the controller
firefly:
  application:
    security:
      enabled: false
  cqrs:
    enabled: false
:::

And here is the same switch in the *runnable* profile, which is what lets a `curl` to
`localhost:8080` reach the BFF during the live `exp → domain → core` flow from
Chapter 17:

```text
# exp-lending/src/main/resources/application.yml — runnable profile
firefly:
  application:
    security:
      enabled: false
```

This is the honest, important detail of the whole chapter. Neither profile removes
`@Secure`, mocks a principal, or stubs the aspect. They keep the real annotation and
the real `SecurityAspect`, and disable only *enforcement* through configuration. The
aspect runs, sees the property is off, logs the line above, and proceeds. The slice
test keeps the secured methods reachable so it can assert mapping and status codes; the
local run keeps the BFF reachable so you can exercise the saga by hand. Same property,
same reason: take the gate off the door while you are still building the house.

What would change with the property `true`? The aspect would stop waving calls through
and start looking for an application execution context to read permissions from. With
no context supplied — as in the slice test, which drives `WebTestClient` without one —
the aspect has nothing to check the required permissions against and rejects the call.
That is exactly the production behavior you want, and exactly the behavior the test and
the local run step around so they can focus on business logic and the cross-tier flow.

!!! warning "`security.enabled=false` is a local/test knob"
    Disabling enforcement is the right move for a slice test that asserts mapping and
    status codes, and for a local run where you want to exercise the BFF by hand
    without an IDP. It is the wrong move anywhere a real caller can reach. Keep it
    scoped to `src/test/resources` and the local runnable profile as Lumen does.
    Shipping a *deployed* service with `firefly.application.security.enabled=false`
    makes every `@Secure` annotation decorative — the aspect intercepts, logs
    "Security is disabled, allowing access," and lets everyone through. The framework
    default is `true` for exactly this reason; never carry the `false` override into a
    production profile.

!!! spring "Spring parity"
    Spring's `@EnableMethodSecurity` plus `@PreAuthorize` is enforced by an AOP
    interceptor too — the mechanism is the same shape. Firefly's difference is the
    *toggle*: there is one fleet-wide property that turns method authorization on or
    off, so every service's tests (and local runs) disable enforcement the same way
    instead of each inventing a `@WithMockUser` dance or a custom test security config.
    You are still using AOP-driven method security; Firefly standardizes the seams
    around it so the on/off switch is identical across the fleet.

## `AppContext`: the principal and tenant, carried reactively

The aspect needs to know *who* is calling and *which tenant* they belong to. On the
servlet stack that lives in a `ThreadLocal` `SecurityContextHolder`; on the reactive
stack a `ThreadLocal` is a trap, because Reactor hops threads between operators and the
value would not follow. (This is the same thread-hop hazard Chapter 1 flagged and the
boot log's "Reactor automatic context propagation enabled" line addresses for
tracing.) Firefly's answer is an application context object — call it the `AppContext`
(its security half is the `AppSecurityContext`) — that travels *with* the request
through the Reactor `Context`, so the authenticated principal and tenant id are
available at any point in the reactive chain, on any thread.

The application starter populates this context at the edge from whatever authentication
the request carried — a validated bearer token, an upstream gateway header — and the
`SecurityAspect` reads the caller's permissions out of it to satisfy `@Secure`. The
shape you would pass into a secured method, or read inside one, looks like this:

```java
// Illustrative: the application context carrying principal and tenant through a reactive call.
public Mono<ApplicationDetailDTO> createApplication(AppContext ctx, CreateApplicationRequest request) {
    String userId   = ctx.security().getPrincipalId();   // who is acting
    String tenantId = ctx.security().getTenantId();       // which tenant they belong to
    // @Secure already checked ctx.security().getPermissions() against the required set
    return applicationService.createApplication(tenantId, request);
}
```

That is illustrative — Lumen's slice controller does not take the context as a
parameter, which is exactly why enforcement is disabled rather than satisfied: with no
context to read, an enforcing aspect would have nothing to check against. In a fully
wired deployment the context is threaded in (or resolved from the Reactor `Context`),
the aspect finds it, and the permission check has something to check against. The
tenant id matters as much as the principal: a lending platform is multi-tenant, and the
same `getApplication` call must scope its read to the caller's tenant so one bank's
operator cannot fetch another's loan. The context is how that tenant boundary follows
the request without a parameter on every method, and why `@Secure`'s job ends at "may
this caller act" while the tenant scoping happens in the service against
`ctx.security().getTenantId()`.

!!! note "Key term — `AppContext` / `AppSecurityContext`"
    The request-scoped object the application tier carries through a reactive call,
    holding the authenticated principal id, the tenant id, the caller's permissions and
    roles, and correlation metadata. `AppSecurityContext` is its security-focused view.
    It is the reactive-safe replacement for `SecurityContextHolder`: populated once at
    the edge, propagated through the Reactor `Context`, and read by the `SecurityAspect`
    (and your handlers) downstream. It is the same idea as the CQRS `ExecutionContext`
    from Chapter 10, scoped to the experience tier and centered on identity.

!!! spring "Spring parity"
    Spring Security WebFlux exposes the principal through
    `ReactiveSecurityContextHolder.getContext()`, which reads from the Reactor
    `Context` — the right reactive primitive. Firefly's `AppContext` builds on that
    same primitive but carries more than a principal: tenant, permissions as scoped
    strings, and correlation, in one object the whole application tier shares. You can
    still reach Spring's `ReactiveSecurityContextHolder`; `AppContext` is the
    fleet-standard envelope around it, so a handler reads identity the same way in
    every service rather than reassembling it from a `Principal` and a custom header.

## `@RequireContext`: demanding the context be present

`@Secure` answers "may this caller do this?" A companion annotation, `@RequireContext`,
answers a prior question: "is there an authenticated context at all?" You put it on a
method (or class) that must not run anonymously — it tells the aspect to reject the call
when no `AppContext` is present, before any permission check. It is the explicit way to
say "this endpoint is never public," and it pairs naturally with `@Secure`:

```java
// Illustrative: require an authenticated context, then a specific permission.
@RequireContext
@Secure(permissions = {"lending:application:read"})
public Mono<ResponseEntity<ApplicationDetailDTO>> getApplication(AppContext ctx, @PathVariable UUID id) {
    return applicationService.getApplication(ctx.security().getTenantId(), id).map(ResponseEntity::ok);
}
```

Why would you want both? `@Secure` already fails a caller who lacks the permission, so
in the common case a missing context fails the permission check anyway. The difference
is *intent and error clarity*: `@RequireContext` makes "no identity" a distinct,
first-class rejection ("you are anonymous") separate from "wrong permission" ("you are
known but not allowed"). On an endpoint that must never be reachable anonymously —
think a funds-movement operation — declaring `@RequireContext` documents that invariant
in the source and produces the clearer failure, rather than relying on the permission
list to incidentally catch the anonymous case.

Lumen's slice uses `@Secure` alone and relies on the local/test toggle to skip
enforcement, so `@RequireContext` does not appear in the reactor — treat it as the
how-it-works companion you reach for when an endpoint must hard-fail on a missing
identity. Chapter 17 covers the broader `AppContext` story for the experience tier;
here the point is just that the two annotations compose: require the context, then
constrain what it may do.

## Where identity comes from: the provider-agnostic IDP port

Everything so far assumed an authenticated principal arrived. *Producing* that
principal — logging a user in, validating a token, refreshing a session, looking up a
user's roles — is the job of an identity provider, and Firefly keeps it behind a port
exactly like every other vendor concern in the platform. The IDP core defines one
`IdpAdapter` interface (login, refresh, token introspection, user CRUD, MFA, sessions),
and a concrete adapter is chosen by a single property:

```yaml
# Illustrative: pick an identity provider with one property; the adapter jar registers the rest.
firefly:
  idp:
    provider: keycloak   # keycloak | cognito | azure-ad | internal-db
```

Switching from Keycloak to AWS Cognito is a dependency swap plus this one line — your
`SecurityWebFilterChain`, your `@Secure` annotations, and your `AppContext`-aware
handlers do not change, because they depend on the *port's* notion of a principal and
permissions, never on a vendor SDK. That is the same hexagonal pattern Chapter 1
promised and the EDA transport swap in Chapter 11 demonstrated, applied to identity:
the port is the stable contract, the adapter is the replaceable detail, and a
`@ConditionalOnProperty` on the provider name decides which adapter the context wires.

Be precise about what this build does and does not run: the IDP core is **not**
exercised by this chapter's slice. The test disables enforcement rather than mint a
real token, and the local run does the same, so no adapter is on the classpath and no
provider is contacted. Treat the provider table as *where it plugs in* — the seam the
real platform fills — not as something this reactor boots. When you do wire it, the
adapter is what populates the `AppContext` the `SecurityAspect` later reads; the chain
from "token arrives" to "permission checked" runs through the port, not around it.

!!! note "Key term — IDP port (`IdpAdapter`)"
    A single interface the platform depends on for all identity operations — login,
    refresh, introspection, user and group management, MFA, session handling. Each
    vendor (Keycloak, Cognito, Microsoft Entra ID, an internal database) ships an
    adapter that activates by `@ConditionalOnProperty` when `firefly.idp.provider`
    names it. Your security code targets the port; the property picks the
    implementation. Appendix B lists the adapters and the exact dependencies.

!!! spring "Spring parity"
    In plain Spring you would wire `spring-security-oauth2-resource-server` to one
    issuer, or a Keycloak adapter, directly into your security config — and changing
    providers means editing that config in every service. Firefly's IDP port turns the
    provider into configuration: the resource-server or adapter wiring lives in the
    chosen adapter jar, selected by `firefly.idp.provider`, so the swap is fleet-wide
    and code-free. See Appendix B for the full adapter catalog.

## Run it

The slice test boots the real `exp-lending` application — the `ApplicationController`
with its `@Secure` annotations, the `WebSecurityConfig` filter chain, the
`SecurityAspect`, and `fireflyframework-web`'s `GlobalExceptionHandler` — and drives it
through `WebTestClient`, satisfying the SDK seam with an in-memory
`StubLoanOriginationDomainClient` bean so there is no domain service and no Docker.
Enforcement is disabled through the test profile's
`firefly.application.security.enabled=false`, so the secured methods run while the
`SecurityAspect` short-circuits with "Security is disabled, allowing access." From the
`samples/lumen-lending` directory:

```text
mvn -q -pl exp-lending test
```

You should see the module's tests pass:

```text
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in com.firefly.lumen.exp.web.ApplicationControllerTest
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

The four tests in `ApplicationControllerTest` are the ones that matter here. They
`POST` a valid application and assert `201` with the mapped detail, round-trip a
create-then-`GET`, assert an unknown id returns a `404` problem detail (its body
carries `APPLICATION_NOT_FOUND`, rendered by the `GlobalExceptionHandler`), and reject
a zero amount with `400` — and every one of those requests passes through a `@Secure`
method whose aspect intercepts, logs, and (enforcement off) proceeds. The other five
tests in the module exercise the service mapping and the application context. The build
proves two things at once: the authorization annotations are wired and live on the real
handlers, and disabling enforcement is a configuration flip, not a code change.

One honest note on the create case: the slice's `StubLoanOriginationDomainClient`
returns a `DRAFT` application, so the slice test asserts `status == "DRAFT"`. That is
the *stub's* answer. In the full live stack from Chapter 17, the same `POST` flows
`exp → domain → core`, the saga writes to the system of record, and the BFF returns
`SUBMITTED` — the form the README captures. The slice tests the BFF's mapping and
security in isolation; the live run proves the cross-tier status. Both are real; they
differ because one stops at the seam and the other crosses it.

!!! tip "Checkpoint"
    Run the command above and confirm `Tests run: 9, Failures: 0` for the module (with
    `4` in `ApplicationControllerTest`). Then run it with enforcement on —
    `mvn -q -pl exp-lending -Dfirefly.application.security.enabled=true test` — and
    watch the secured calls fail authorization: with no `AppContext` supplied, the
    `SecurityAspect` has nothing to check the required permissions against and rejects
    the request. Restore the test default and it passes again. You just saw the toggle
    do its single job, from both sides — and saw why the `false` default in the test
    and local profiles is what keeps the BFF reachable.

## What you learned {.recap}

- Authorization in Lumen sits at **two checkpoints**: a transport-level
  `SecurityWebFilterChain` at the HTTP edge, and a method-level `@Secure` aspect after
  routing. The filter answers "is this path/exchange allowed through"; the aspect
  answers "may this caller run this operation." Keeping them separate puts blanket
  rules in the filter and fine-grained rules next to the operation.
- Spring Security on WebFlux is a reactive **`SecurityWebFilterChain`**, built from
  `ServerHttpSecurity` under `@EnableWebFluxSecurity` — not the servlet filter stack.
  Lumen's chain disables the stateless-hostile defaults (HTTP Basic, form login, CSRF,
  logout) and permits all exchanges, *delegating* authorization to the method layer.
- **`@Secure`** is Firefly's declarative method-level authorization: a typed list of
  scoped permission strings (`lending:application:create`) on the handler, enforced by
  the application starter's **`SecurityAspect`** (AOP), not by Spring's
  `@PreAuthorize`. Its `description` feeds the startup **`EndpointSecurityRegistry`**.
- One property, **`firefly.application.security.enabled`**, turns enforcement on (the
  framework default) or off. With it `false`, the aspect still intercepts the `@Secure`
  method but logs "Security is disabled, allowing access" and skips the check — which
  is how both the slice test *and* the local run keep the real annotations while
  reaching the BFF.
- **`AppContext`/`AppSecurityContext`** carries the authenticated principal, tenant id,
  and permissions through the reactive chain via the Reactor `Context` — the
  reactive-safe replacement for `SecurityContextHolder` — and **`@RequireContext`**
  demands it be present before a method runs, separating "anonymous" from "forbidden."
- Identity is produced behind a provider-agnostic **`IdpAdapter`** port, selected by
  `firefly.idp.provider` (Keycloak, Cognito, Entra ID, internal DB) — a one-line swap
  cross-referenced in Appendix B, not exercised by this slice.
- A passing module build (`Tests run: 9, Failures: 0`, with `4` in
  `ApplicationControllerTest`) in which every controller call traverses a real
  `@Secure` aspect with enforcement disabled by configuration.

## Try it yourself {.exercises}

1. **Prove the aspect is real.** Run `mvn -pl exp-lending test` at debug and read the
   output for the `SecurityAspect` log line (`Security is disabled, allowing access`,
   from logger `o.f.c.application.aop.SecurityAspect`). Find it for a call into
   `createApplication` and one into `getApplication`, and explain in one sentence why
   that line means the annotation is exercised but not enforced.
2. **Flip the toggle.** Run the module with
   `-Dfirefly.application.security.enabled=true` and confirm the secured tests now fail
   authorization. Read the failure, then restore the test default. Which line in
   `src/test/resources/application.yml` is the one knob you changed — and which line in
   `src/main/resources/application.yml` is its twin that keeps the *local run*
   reachable?
3. **Add a permission.** Give `getApplication` a second required permission (for
   example `lending:application:read` *and* `lending:tenant:member`) by extending the
   `permissions` array in `@Secure`. Re-run the slice test — it still passes, because
   enforcement is off — then write a sentence on what would have to be true of the
   caller's `AppContext` for it to pass with enforcement *on*.
4. **Tighten the filter chain.** In `WebSecurityConfig`, change the final
   `.anyExchange().permitAll()` to `.anyExchange().authenticated()` and add a bearer-token
   resource server (`http.oauth2ResourceServer(...)`). Sketch — no need to run a real
   IDP — how this transport-level check and the method-level `@Secure` would each
   reject an unauthorized request, and which one fires first. (Hint: re-read "Why
   authorization sits in two places at once.")
5. **Trace a provider swap.** Without changing any `@Secure` or `SecurityWebFilterChain`
   code, list what you would change to move Lumen's identity from Keycloak to AWS
   Cognito: which `firefly.idp.provider` value, which adapter dependency. Confirm
   against Appendix B that the controller and aspect stay untouched — that invariance is
   the point of the port — and name the object the chosen adapter populates that the
   `SecurityAspect` later reads.
6. **Reconcile the two statuses.** The slice test asserts the create call returns
   `DRAFT`, but the README's live `exp → domain → core` run returns `SUBMITTED`. Read
   `StubLoanOriginationDomainClient` and explain in two sentences why the slice and the
   live stack disagree — and why both are honest.

## Where to go next

You now have requests that are authenticated, authorized, and tenant-scoped — but a
read endpoint like `getApplication` still hits the domain tier on every call, even
when nothing changed. Chapter 20 turns to **caching**: how Firefly's reactive
`CacheAdapter` port lets the experience and query tiers serve repeated reads from a
local Caffeine layer (and an optional distributed L2) without a vendor SDK in your
code — the same hexagonal pattern you just saw for identity, applied to speed.
</content>
</invoke>
