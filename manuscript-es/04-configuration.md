A service that runs on your laptop, in a test harness, in a staging cluster, and
in production is *one* build artifact — the same JAR, byte for byte. What changes
between those four places is never the code; it is the configuration. The database
URL, the broker address, the log level, the secret that unlocks an upstream API:
all of it lives outside the JAR, layered so that each environment supplies only
what differs from the defaults.

This is **externalized configuration**, and it is one of the load-bearing ideas
you inherit straight from Spring Boot. Firefly does not change the rules; it adds
a hierarchy on top of them — a config server that serves common, core, and
domain settings to a whole fleet, with secrets encrypted at rest. This chapter
starts with the file the build actually runs against, then works outward: the
resolution hierarchy, typed binding with `@ConfigurationProperties`, and finally
where secrets and the config server plug in.

## The file the tests run against

Open the test configuration for the loan-origination core service. It is small on
purpose: it has exactly one job, which is to make the module's tests run with no
Docker, no external database, and no network — yet against a real reactive data
stack.

::: listing core-lending-loan-origination/src/test/resources/application.yml | Listing 4.1 — the test configuration: H2, Flyway, and R2DBC pointed at one in-memory database
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

Read it top to bottom and you have read the whole contract.

`spring.application.name` names the service. It is the identity every other
subsystem keys off: it is the name on the startup banner, the label on metrics,
and — as you will see at the end of this chapter — the folder the config server
looks in when it serves this service its settings.

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
    Nothing in Listing 4.1 is Firefly-specific. `spring.application.name`,
    `spring.r2dbc.*`, and `spring.flyway.*` are vanilla Spring Boot property keys,
    bound by Spring Boot's own auto-configuration. Firefly inherits the entire
    externalized-configuration mechanism unchanged and layers its hierarchy on top
    — it never replaces it.

## Run it

Because this configuration is on the test classpath, the module's tests pick it up
automatically. Run them:

```text
mvn -q -pl core-lending-loan-origination test
```

The H2 database spins up in memory, Flyway applies the migration, the reactive
tests exercise the data and web layers, and Maven reports:

```text
Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

!!! tip "Checkpoint"
    If you see `Tests run: 18, Failures: 0`, your config wired a real reactive
    data stack with nothing installed but a JVM. If instead a test reports a
    missing table, the two URLs in Listing 4.1 have drifted apart — recheck that
    the R2DBC and Flyway URLs name the same database.

## The resolution hierarchy

A single file is the starting point, never the whole story. Spring Boot resolves a
property by consulting a series of sources in a fixed order, and **later sources
win**. Three of those sources carry almost all the weight in practice:

1. **`application.yml`** — the baseline. Sensible defaults that are true
   everywhere, like the service name.
2. **Profile-specific files** — `application-{profile}.yml`. Activated by name,
   these override the baseline for one environment or shape of run.
3. **Environment variables and JVM system properties** — supplied by the
   platform at launch. These override everything, which is exactly what you want
   for values that differ per deployment or must never sit in a file.

A **profile** is just a named slice of configuration. You activate one with the
`spring.profiles.active` property — itself usually set by an environment variable
— and Spring loads the matching `application-{profile}.yml` on top of the
baseline. A production file might look like this (illustrative — the reactor ships
the test profile shown in Listing 4.1, not a prod file):

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

Notice what is *absent*: there is no `spring.application.name` here, because the
baseline already set it and production does not change it. A profile file states
only the delta. Run with `--spring.profiles.active=prod` and the Postgres URLs win
over the H2 baseline; run with no profile and you get the in-memory defaults.

The final layer — environment variables — follows a mechanical naming rule:
uppercase the property, replace dots and dashes with underscores. So
`spring.r2dbc.password` is overridden by the environment variable
`SPRING_R2DBC_PASSWORD`. This is how a deployment platform injects a value the
file never contains:

```text
export SPRING_PROFILES_ACTIVE=prod
export SPRING_R2DBC_PASSWORD=...        # injected by the platform, never in git
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
    forever. Real secrets arrive through environment variables or — better, at
    fleet scale — the encrypted config server covered at the end of this chapter.

## Binding configuration to typed objects

Reading properties one key at a time with `@Value("${...}")` works for a value or
two, but it scatters string keys through your code and gives you no type safety. The
better tool is `@ConfigurationProperties`, which binds a whole group of related
properties to one immutable, validated object that the rest of your code injects
like any other bean.

The loan-origination slice does not ship a custom properties type — its
configuration is the framework-owned `spring.*` keys you saw in Listing 4.1 — so
what follows is illustrative: the shape you would add when a service grows its own
settings. Suppose Lumen needs lending policy knobs — the maximum term it will
underwrite and the default currency. You model them as a record:

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
automatically — relaxed binding, so the YAML stays idiomatic and the Java stays
camelCase:

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
framework `@ConfigurationProperties` type, which is why a typo in a `firefly.*`
key is caught at startup, not in production.

!!! note "Key term — @ConfigurationProperties"
    `@ConfigurationProperties(prefix = "...")` binds a tree of properties under a
    prefix to a typed object — commonly an immutable `record`. You get type
    safety, relaxed (kebab-case to camelCase) binding, and startup-time
    validation, instead of scattered `@Value` string keys.

!!! spring "Spring parity"
    `@ConfigurationProperties` is pure Spring Boot. Firefly adds no new binding
    mechanism — it simply uses the same one for its `firefly.*` settings that you
    use for your `lumen.*` ones. When you read `firefly.cqrs.enabled` in a later
    chapter, it is binding to a framework record through this exact machinery.

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
configuration for the test run, with no server in the loop — so treat this section
as *how it plugs in* at fleet scale, not as something the loan-origination tests
exercise.

The server's value is **hierarchy**. Rather than every service repeating the same
broker address, log format, and tracing settings, the server organizes
configuration in layers and composes them per service:

- **common** — settings true for the entire fleet (log format, tracing endpoint,
  default timeouts).
- **core / domain / experience** — settings shared by every service of one tier.
- **per-service** — the deltas unique to one service, keyed by its
  `spring.application.name`.

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
rotating one is a single change in one place.

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
  test and production; only externalized configuration differs.
- The loan-origination test config (Listing 4.1) wires a real reactive stack with
  no Docker: H2 in memory, Flyway migrating over JDBC and R2DBC reading at
  runtime, both pointed at the *same* database name so the migrated table is the
  one the repositories read.
- Properties resolve in a fixed hierarchy — **baseline `application.yml`, then
  profile files, then environment variables**, with later sources winning. A
  profile and an env var each state only the delta.
- `@ConfigurationProperties` binds a group of properties to an immutable, typed,
  startup-validated object — the same mechanism Firefly uses for every `firefly.*`
  setting.
- The **config server** serves a fleet hierarchical common/tier/per-service
  configuration and holds secrets as `{cipher}`-encrypted values — keeping shared
  config in one place and secrets out of every repository. The reactor itself runs
  on local files; the server is how this scales beyond one module.

## Try it yourself {.exercises}

1. **Trace the shared name.** In `core-lending-loan-origination/src/test/resources/application.yml`,
   the R2DBC and Flyway URLs both name the database `lumen`. Change *only* the
   Flyway URL to name `lumen2`, run `mvn -q -pl core-lending-loan-origination test`,
   and read the failure. Then restore it and confirm the 18 tests pass again. You
   have just felt why the two names must agree.
2. **Add a profile delta.** Create `application-fast.yml` beside the test config
   that sets `spring.flyway.baseline-on-migrate: false`, and reason about which
   value wins when you run with `spring.profiles.active=fast`. Which file supplies
   `spring.application.name` in that run, and why?
3. **Override from the environment.** Work out the environment-variable name that
   would override `spring.r2dbc.username` from Listing 4.1 (apply the
   uppercase-and-underscore rule), and the one for `spring.flyway.locations`.
4. **Design a typed properties record.** Sketch a `@ConfigurationProperties`
   record for two lending knobs of your own — say a minimum loan amount and a list
   of supported currencies — and the matching YAML under a `lumen.lending` prefix.
   Which component type maps to a YAML list?
5. **Place a secret correctly.** Given the production password from the hierarchy
   section, list the three places it could live — profile file, environment
   variable, config-server `{cipher}` value — and rank them worst to best for a
   fleet of fifty services. Justify the ranking in one sentence each.

## Where to go next

You can now configure a service the way the build does, layer environments with
profiles, bind settings to typed objects, and place secrets where they belong. The
next chapters lean on this constantly — every `firefly.*` property that toggles a
capability binds through the machinery you just learned. Chapter 5 turns to the
reactive model in full, the keystone the rest of the book stands on.
