A service that runs on your laptop, in a test harness, in a staging cluster, and
in production is *one* build artifact — the same JAR, byte for byte. What changes
between those four places is never the code; it is the configuration. The database
URL, the broker address, the log level, the port it listens on, the secret that
unlocks an upstream API: all of it lives outside the JAR, layered so that each
environment supplies only what differs from the defaults.

This is **externalized configuration**, and it is one of the load-bearing ideas
you inherit straight from Spring Boot. Firefly does not change the rules; it adds
a hierarchy on top of them — a config server that serves common, core, and
domain settings to a whole fleet, with secrets encrypted at rest. This chapter
starts with the two files the core service actually runs against — the one that
boots a live server and the near-identical one its tests use — then works
outward: the resolution hierarchy, typed binding with `@ConfigurationProperties`,
and finally where secrets and the config server plug in.

## The file the service boots against

In Chapter 2 you booted `core-lending-loan-origination` with `mvn spring-boot:run`,
watched the banner, and POSTed a loan application. The configuration that made that
run possible is one short file. Open the *runnable* config — the one on the main
classpath, the one a `mvn spring-boot:run` or a `java -jar` reads:

::: listing core-lending-loan-origination/src/main/resources/application.yml | Listing 4.1 — the runnable configuration: a server port, H2, Flyway, and R2DBC pointed at one in-memory database
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

Read it top to bottom and you have read the whole contract for a tier that serves
real CRUD with nothing installed but a JVM.

`server.port: 8081` is the first line, and it earns its place: the core tier
listens on **8081**, the domain tier on **8082**, and the experience BFF on
**8080**, so all three can run on one laptop at once without colliding. Leave this
line out and the starter defaults the reactive server to `8080` — fine for a single
service, a port clash the moment a second tier comes up. (You met `8080` as the
banner's default in Chapter 2, before this property was set; here the property
moves the listener to `8081`, which is why the run commands and `curl`s in this
book hit `localhost:8081` for core.)

`spring.application.name` names the service. It is the identity every other
subsystem keys off: it is the name on the startup banner, the `title` of the
generated OpenAPI document, the label on metrics, and — as you will see at the end
of this chapter — the folder the config server looks in when it serves this service
its settings. One property, surfacing in four places; we trace two of them in the
next section.

`spring.r2dbc.*` configures the **reactive** data access path. The URL
`r2dbc:h2:mem:///lumen;DB_CLOSE_DELAY=-1` says: an in-memory H2 database named
`lumen`, kept alive for the life of the JVM. This is what your repositories read
and write through, on the event loop, without blocking.

`spring.flyway.*` configures schema migration, which runs over **JDBC** —
`jdbc:h2:mem:lumen;DB_CLOSE_DELAY=-1`. The two URLs use different drivers
(`r2dbc:` versus `jdbc:`) but name the *same* in-memory database, `lumen`. That
shared name is the whole trick: Flyway creates the table over JDBC at startup, and
R2DBC reads the very same table at runtime. `baseline-on-migrate: true` lets
Flyway adopt an empty database cleanly; `locations: classpath:db/migration`
points it at the migration that lives beside the code.

!!! note "Key term — externalized configuration"
    **Externalized configuration** means settings live outside the compiled
    artifact — in `application.yml`, profile files, and environment variables —
    so the same JAR runs unchanged in every environment. The code reads *named*
    properties; the environment supplies the *values*.

!!! warning "Two URLs, one database — keep the name in sync"
    The R2DBC URL and the Flyway URL must name the same in-memory database
    (`lumen` here). If they drift apart, Flyway migrates one database and your
    repositories read an empty other one — and the failure looks like a missing
    table, not a configuration typo. When you copy this pattern to a new module,
    change both names together.

!!! spring "Spring parity"
    Nothing in Listing 4.1 is Firefly-specific. `server.port`,
    `spring.application.name`, `spring.r2dbc.*`, and `spring.flyway.*` are vanilla
    Spring Boot property keys, bound by Spring Boot's own auto-configuration.
    Firefly inherits the entire externalized-configuration mechanism unchanged and
    layers its hierarchy on top — it never replaces it.

## One property, two surfaces: the banner and the OpenAPI title

It is worth slowing down on `spring.application.name`, because it is the clearest
small proof that configuration *flows into* the framework rather than sitting in a
file the framework ignores. You set it once; it shows up twice in the boot you ran
in Chapter 2.

First, the banner. The starter ships a `banner.txt` with `${spring.application.name}`
as a placeholder, and Spring Boot substitutes the live value before any business
line runs. With `server.port: 8081` and the name from Listing 4.1, the boot prints
the framework's real banner:

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

The `Application:` line is your property, echoed back. (The `SwaggerUI:` line shows
the starter's default `8080` placeholder; the live server is on `8081`, so the
working docs URL is `http://localhost:8081/swagger-ui.html` — a reminder that the
banner template is rendered from defaults, while `server.port` redirects the actual
listener.)

Second, the OpenAPI document. The framework builds the document's `title` from the
same property, suffixed with `API`. Ask the running service for its spec and the
property surfaces a second time:

```text
$ curl -s http://localhost:8081/v3/api-docs
{"openapi":"3.1.0","info":{"title":"core-lending-loan-origination API","description":"core-lending-loan-origination API Documentation","license":{"name":"Apache 2.0","url":"https://www.apache.org/licenses/LICENSE-2.0"},"version":"1.0.0"}, ... }
```

So a single line of YAML — `name: core-lending-loan-origination` — names the
process in its banner, titles its self-generated API contract, tags its metrics,
and (at fleet scale) selects its config-server folder. That is the payoff of
*named* properties read by the framework: you change one value and every subsystem
that keys off it follows. Rename the service and the banner, the OpenAPI title, and
the config lookup all move together, with no second edit.

!!! note "Key term — relaxed binding"
    Spring Boot binds properties with **relaxed binding**: a key can be written in
    kebab-case, camelCase, or with environment-variable underscores and still bind
    to the same target. `server.port`, `SERVER_PORT`, and `server.port` in YAML all
    set the same value. This is why YAML stays idiomatic kebab-case while the Java
    that reads it stays camelCase — you will lean on it again with
    `@ConfigurationProperties` below.

## Run it

Two ways to run the file in Listing 4.1, both first-class. From the module
directory, the Maven plugin boots it in place:

```text
$ mvn spring-boot:run
```

Or, because the Spring Boot repackage goal is wired in the parent, build the
executable JAR and run it like any artifact — the same byte-for-byte JAR you would
ship:

```text
$ mvn -q -pl core-lending-loan-origination package
$ java -jar core-lending-loan-origination/target/core-lending-loan-origination-0.1.0-SNAPSHOT.jar
```

Either way, H2 spins up in memory, Flyway applies the migration over JDBC, Netty
binds **8081**, and the service is ready to serve the loan-origination API you
exercised in Chapter 2. Once it is up, a quick health check confirms the wiring —
note that the R2DBC indicator reports the live `H2` database the same config just
configured (trimmed for space):

```text
$ curl -s http://localhost:8081/actuator/health
{"status":"UP","groups":["liveness","readiness"],"components":{
  "cqrs":{"status":"UP","details":{"command_bus":"UP","query_bus":"UP","command_handlers":0,"query_handlers":0}},
  "eda":{"status":"UP","details":{"enabled":true,"message":"All EDA components are healthy"}},
  "r2dbc":{"status":"UP","details":{"database":"H2"}},
  "ping":{"status":"UP"}}}
```

### The same config, on the test classpath

There is a second copy of this file, and the difference between the two is the
whole point of externalized configuration. Beside the runnable config lives the
*test* config, which the module's tests pick up automatically because it is on the
test classpath. It is the same H2/R2DBC/Flyway contract, minus the one line a test
does not need:

::: listing core-lending-loan-origination/src/test/resources/application.yml | Listing 4.2 — the test configuration: the same H2 stack, with no server port
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

Look at what is *missing*: there is no `server:` block. A slice test drives the API
through `WebTestClient`, which binds the application context directly with no socket,
so there is no port to listen on — and pinning one would only risk a clash when
tests run in parallel. The data contract is identical to the runnable file, so the
tests exercise the very same reactive stack the live server uses. This is the lesson
in miniature: *the data wiring is shared; only the deployment-shaped line (the port)
differs between running and testing.* Run the tests and Maven reports the core
module's count:

```text
$ mvn -q -pl core-lending-loan-origination test
```

```text
Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Those eighteen are the core tier's share of the reactor's **33** (core 18, domain
6, experience 9). They boot the full reactive context against in-memory H2 and
drive the real API headless — the same code paths Listing 4.1 serves over a socket.

!!! tip "Checkpoint"
    If you see `Tests run: 18, Failures: 0`, the test config wired a real reactive
    data stack with nothing installed but a JVM. If instead a test reports a missing
    table, the two URLs in Listing 4.2 have drifted apart — recheck that the R2DBC
    and Flyway URLs name the same database.

!!! note "Key term — main vs. test resources"
    Spring Boot reads `src/main/resources/application.yml` when the application
    *runs*, and lets `src/test/resources/application.yml` override it when the
    application is loaded by a *test* (the test classpath comes first). Keeping the
    two in sync on data wiring — and letting them differ only on deployment shape,
    like the port — is how the reactor proves under test exactly what it serves in
    production.

## The resolution hierarchy

Two files for one service is already the hierarchy at work, but it is only the
start. Spring Boot resolves a property by consulting a series of sources in a fixed
order, and **later sources win**. Three of those sources carry almost all the
weight in practice:

1. **`application.yml`** — the baseline. Sensible defaults that are true
   everywhere, like the service name and the port.
2. **Profile-specific files** — `application-{profile}.yml`. Activated by name,
   these override the baseline for one environment or shape of run.
3. **Environment variables and JVM system properties** — supplied by the
   platform at launch. These override everything, which is exactly what you want
   for values that differ per deployment or must never sit in a file.

A **profile** is just a named slice of configuration. You activate one with the
`spring.profiles.active` property — itself usually set by an environment variable
— and Spring loads the matching `application-{profile}.yml` on top of the
baseline. A production file might look like this (illustrative — the reactor ships
the runnable H2 file in Listing 4.1, not a prod file):

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

Notice what is *absent*: there is no `spring.application.name` and no `server.port`
here, because the baseline already set them and production does not change them. A
profile file states only the delta. Run with `--spring.profiles.active=prod` and the
Postgres URLs win over the H2 baseline; run with no profile and you get the
in-memory defaults from Listing 4.1. The same JAR, two databases, decided entirely
outside the code.

The final layer — environment variables — follows a mechanical naming rule:
uppercase the property, replace dots and dashes with underscores. So
`spring.r2dbc.password` is overridden by the environment variable
`SPRING_R2DBC_PASSWORD`, and `server.port` by `SERVER_PORT`. This is how a
deployment platform injects a value the file never contains, or moves the listener
without touching a line of YAML:

```text
export SPRING_PROFILES_ACTIVE=prod
export SERVER_PORT=9443                  # platform decides the port at deploy time
export SPRING_R2DBC_PASSWORD=...         # injected by the platform, never in git
java -jar core-lending-loan-origination.jar
```

The baseline names the property, the profile reshapes it for an environment, and
the environment variable supplies the one value you would never commit. Same JAR,
three places, three behaviors.

!!! note "Key term — profile"
    A **profile** is a named configuration slice activated by
    `spring.profiles.active`. Spring loads `application-{profile}.yml` over the
    baseline `application.yml`, so a profile file declares only what differs.
    Profiles are layered, not exclusive: you can activate several at once.

!!! warning "Secrets do not belong in profile files"
    A profile file is committed to source control like any other resource. Put a
    database password in `application-prod.yml` and it is now in your git history
    forever. (This is exactly why the H2 password in Listing 4.1 is an empty string
    — there is no secret to leak in an in-memory dev database.) Real secrets arrive
    through environment variables or — better, at fleet scale — the encrypted config
    server covered at the end of this chapter.

## How the other tiers configure themselves

The core file in Listing 4.1 is the simplest of the three because the core tier
owns persistence. The other two tiers in the reactor configure *different*
capabilities through the very same mechanism, and a glance at them shows that
"externalized configuration" scales from data wiring to framework switches to
service-to-service URLs without changing the rules.

The **domain** tier (`domain-lending-loan-origination`, port **8082**) owns no
database; it orchestrates. Its `application.yml` sets `server.port: 8082`, names
the service, and then flips Firefly *capability* switches — `firefly.cqrs.enabled`,
`firefly.orchestration.enabled`, `firefly.eda.enabled` with
`default-publisher-type: APPLICATION_EVENT` (the in-JVM event transport, no Kafka,
no Docker) — and points its core SDK seam at the system of record with
`firefly.lumen.core.loan-origination.base-path: http://localhost:8081`. That last
property is how the saga you will meet in later chapters knows where the core
service lives; change the URL and the saga writes somewhere else, with no code
change.

The **experience** BFF (`exp-lending`, port **8080**) configures yet another
concern: it sets `firefly.application.security.enabled: false` so the BFF is
reachable locally without a token (the controllers keep their real `@Secure`
annotations; the switch just short-circuits enforcement — production leaves it
`true`), and points *its* seam at the domain tier with
`lumen.exp.loan-origination.base-path: http://localhost:8082`.

Three tiers, three completely different configuration needs — a database, a set of
framework switches, a downstream URL — all expressed as externalized properties in
the same flat YAML shape. None of it is bespoke; it is the same Spring Boot binding
applied to keys the framework happens to read. (These `firefly.*` and `lumen.*`
keys are the subject of the next section and of later chapters; the point here is
only that they ride the identical resolution hierarchy as `server.port`.)

!!! spring "Spring parity"
    The `firefly.*` switches the domain and experience tiers set — `firefly.cqrs.enabled`,
    `firefly.eda.enabled`, `firefly.application.security.enabled` — are ordinary
    Spring Boot properties bound to framework `@ConfigurationProperties` types. There
    is no special configuration channel: a Firefly capability is toggled by the same
    `application.yml` key, profile override, or environment variable as any Spring
    Boot setting. Same mechanism, more knobs.

## Binding configuration to typed objects

Reading properties one key at a time with `@Value("${...}")` works for a value or
two, but it scatters string keys through your code and gives you no type safety. The
better tool is `@ConfigurationProperties`, which binds a whole group of related
properties to one immutable, validated object that the rest of your code injects
like any other bean. This is not a hypothetical: the domain tier's
`firefly.lumen.core.loan-origination.base-path` and the experience tier's
`lumen.exp.loan-origination.base-path` are both bound to properties types exactly
this way, which is how a typo in one of those URLs is caught at startup rather than
at the first saga.

The core loan-origination slice does not ship a custom properties type of its own —
its configuration is the framework-owned `server.*` and `spring.*` keys you saw in
Listing 4.1 — so what follows is illustrative: the shape you would add when a
service grows its own settings. Suppose Lumen needs lending policy knobs — the
maximum term it will underwrite and the default currency. You model them as a
record:

```java
@ConfigurationProperties(prefix = "lumen.lending")
public record LendingProperties(
        int maxTermMonths,
        String defaultCurrency) {
}
```

You enable binding once, on a configuration class, with `@EnableConfigurationProperties`:

```java
@Configuration
@EnableConfigurationProperties(LendingProperties.class)
public class LendingConfig {
}
```

Then you supply the values in YAML, under the prefix the record declared. Spring
maps the kebab-case `max-term-months` to the record component `maxTermMonths`
automatically — the relaxed binding you met above, so the YAML stays idiomatic and
the Java stays camelCase:

```yaml
lumen:
  lending:
    max-term-months: 84
    default-currency: EUR
```

Now any bean can ask for the typed object by constructor injection and read
`properties.maxTermMonths()` — a real `int`, checked at startup, with no string
key in sight:

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

A record bound this way is immutable, so the configuration cannot be mutated at
runtime by accident. And because the binding happens at startup, a missing or
malformed value fails fast — the application refuses to boot rather than throwing
deep in a request months later. Firefly's own capabilities are configured exactly
this way: every `firefly.*` property you will meet in later chapters is bound to a
framework `@ConfigurationProperties` type, which is why a typo in a `firefly.*` key
(or in that `base-path` URL the domain tier sets) is caught at startup, not in
production.

!!! note "Key term — @ConfigurationProperties"
    `@ConfigurationProperties(prefix = "...")` binds a tree of properties under a
    prefix to a typed object — commonly an immutable `record`. You get type
    safety, relaxed (kebab-case to camelCase) binding, and startup-time
    validation, instead of scattered `@Value` string keys.

!!! spring "Spring parity"
    `@ConfigurationProperties` is pure Spring Boot. Firefly adds no new binding
    mechanism — it simply uses the same one for its `firefly.*` settings that you
    use for your `lumen.*` ones. When you read `firefly.cqrs.enabled` from the
    domain tier's config, it is binding to a framework record through this exact
    machinery.

## Secrets and the config server

Two questions remain. Where do secrets live when an environment variable per
secret per service does not scale? And how does a fleet of dozens of services
share the settings they have in common without copy-pasting YAML into every repo?

Firefly's answer to both is the **config server** — a Spring Cloud Config server
that serves configuration over HTTP, and that the tier starters wire a client for.
A service does not read all of its settings from local files; on startup it asks
the config server for its configuration and merges the response into the same
hierarchy you already know. The local `application.yml` still wins for what it
declares; the server fills in the rest.

The reactor in this book runs against local files — Listing 4.1 is the whole
configuration for the core run, with no server in the loop — so treat this section
as *how it plugs in* at fleet scale, not as something the loan-origination service
exercises today.

The server's value is **hierarchy**. Rather than every service repeating the same
broker address, log format, and tracing settings, the server organizes
configuration in layers and composes them per service:

- **common** — settings true for the entire fleet (log format, tracing endpoint,
  default timeouts).
- **core / domain / experience** — settings shared by every service of one tier
  (this is the natural home for things like the `firefly.eda.*` block the domain
  tier sets locally today).
- **per-service** — the deltas unique to one service, keyed by its
  `spring.application.name` — the same property that named the banner and titled the
  OpenAPI document now selects the folder.

A service receives the union, with the more specific layer winning — the same
"later source wins" rule, applied across a fleet instead of within one file. Change
the tracing endpoint once in **common**, and every service picks it up on its next
refresh. This is the fleet-scale version of the delta principle you saw with
profiles: each layer states only what it adds.

Secrets ride the same channel, **encrypted at rest**. The config server holds an
encryption key; sensitive values are stored as ciphertext marked with a `{cipher}`
prefix and decrypted in flight as the server hands them out:

```yaml
# stored in the config server — the value is ciphertext, not the password
spring:
  r2dbc:
    password: '{cipher}AQB4f2c1e9...d0a7'
```

The committed value is opaque ciphertext; only the server, holding the key, can
turn it back into the password, and the service receives the plaintext only over
the wire at startup. No secret ever sits in a service's git repository, and
rotating one is a single change in one place. (Contrast that with the empty H2
password in Listing 4.1: a dev database has nothing to protect, so it stays in the
clear; a real Postgres password would be a `{cipher}` value the server decrypts on
delivery.)

Put the three layers together and the picture is complete: local
`application.yml` and profiles for what a service owns, environment variables for
the last-mile platform values, and the config server for everything a fleet shares
— with `{cipher}` keeping secrets out of every repository.

!!! note "Key term — config server"
    A **config server** is a central service that serves configuration over HTTP.
    Firefly uses it to compose hierarchical common, tier, and per-service settings
    for a fleet, and to hold secrets as `{cipher}`-encrypted values decrypted on
    delivery — so shared config lives in one place and no secret lives in a repo.

!!! spring "Spring parity"
    The config server is **Spring Cloud Config**, unchanged. Plain Spring Cloud
    gives you the server, the client, and `{cipher}` encryption. What Firefly adds
    is the *convention*: the common/core/domain/experience hierarchy and the tier
    starters that wire the client for you, so a new service joins the hierarchy by
    declaring its name rather than by hand-configuring a client.

## What you learned {.recap}

- A Firefly service is **one artifact configured many ways**. The same JAR runs in
  test and production via `mvn spring-boot:run` or `java -jar`; only externalized
  configuration differs.
- The core service's runnable config (Listing 4.1) wires a real reactive stack with
  no Docker: a `server.port` of 8081, H2 in memory, Flyway migrating over JDBC and
  R2DBC reading at runtime, both pointed at the *same* database name so the migrated
  table is the one the repositories read. The test config (Listing 4.2) is the same
  data contract minus the port, because `WebTestClient` needs no socket.
- One property does a lot of work: `spring.application.name` surfaces in the
  startup banner's `Application:` line and as the OpenAPI document's `title`
  (`core-lending-loan-origination API`), and at fleet scale it selects the config
  server folder.
- Properties resolve in a fixed hierarchy — **baseline `application.yml`, then
  profile files, then environment variables**, with later sources winning. A
  profile and an env var each state only the delta.
- The three tiers configure different concerns through the same mechanism: core sets
  a database, domain flips `firefly.*` capability switches and a core `base-path`,
  experience disables security enforcement and points at the domain — all ordinary
  externalized properties.
- `@ConfigurationProperties` binds a group of properties to an immutable, typed,
  startup-validated object — the same mechanism Firefly uses for every `firefly.*`
  setting and for the `base-path` seams between tiers.
- The **config server** serves a fleet hierarchical common/tier/per-service
  configuration and holds secrets as `{cipher}`-encrypted values. The reactor itself
  runs on local files; the server is how this scales beyond one module.

## Try it yourself {.exercises}

1. **Move the port.** In `core-lending-loan-origination/src/main/resources/application.yml`,
   change `server.port` to `9081`, run `mvn spring-boot:run`, and confirm the service
   now answers on `http://localhost:9081/actuator/health`. Then override it without
   editing the file: stop the app and start it with `SERVER_PORT=9091 mvn spring-boot:run`.
   Which value wins, and why?
2. **Trace the shared name.** In `core-lending-loan-origination/src/test/resources/application.yml`,
   the R2DBC and Flyway URLs both name the database `lumen`. Change *only* the Flyway
   URL to name `lumen2`, run `mvn -q -pl core-lending-loan-origination test`, and read
   the failure. Then restore it and confirm the 18 tests pass again. You have just
   felt why the two names must agree.
3. **Follow one property to two surfaces.** Boot the core service and confirm
   `spring.application.name` appears on the banner's `Application:` line *and* as the
   `title` in `curl -s http://localhost:8081/v3/api-docs`. Then change the name to
   `lumen-core-demo`, reboot, and watch both surfaces follow. Where else would this
   rename ripple in a fleet?
4. **Add a profile delta.** Create `application-fast.yml` beside the runnable config
   that sets `spring.flyway.baseline-on-migrate: false`, and reason about which value
   wins when you run with `spring.profiles.active=fast`. Which file supplies
   `server.port` and `spring.application.name` in that run, and why?
5. **Override from the environment.** Work out the environment-variable name that
   would override `spring.r2dbc.username` from Listing 4.1 (apply the
   uppercase-and-underscore rule), and the one for `spring.flyway.locations`.
6. **Design a typed properties record.** Sketch a `@ConfigurationProperties` record
   for two lending knobs of your own — say a minimum loan amount and a list of
   supported currencies — and the matching YAML under a `lumen.lending` prefix.
   Which component type maps to a YAML list?
7. **Place a secret correctly.** Given the production Postgres password from the
   hierarchy section, list the three places it could live — profile file, environment
   variable, config-server `{cipher}` value — and rank them worst to best for a fleet
   of fifty services. Justify the ranking in one sentence each.

## Where to go next

You can now configure a service the way the build does — boot it with a real port on
8081, run the same data contract under test, layer environments with profiles, bind
settings to typed objects, and place secrets where they belong. The next chapters
lean on this constantly — every `firefly.*` property that toggles a capability binds
through the machinery you just learned. Chapter 5 turns to the reactive model in
full, the keystone the rest of the book stands on.
</content>
</invoke>
