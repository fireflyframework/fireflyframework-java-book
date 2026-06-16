`flywork` is Firefly's command-line companion — the tool that bootstraps the
framework on a new machine and scaffolds new services so they start life on the
right conventions. (It happens to be written in Go and shipped as a single binary;
that is an implementation detail — you never write Go to use it, and this remains a
book about the Java framework.)

## Bootstrapping the framework

The framework is many repositories, and they build in dependency order. `flywork
setup` clones them all and installs them to your local Maven repository
(`~/.m2`) in the right sequence, so that `fireflyframework-kernel` is built before
the modules that depend on it, and so on up the graph.

```text
$ flywork setup
Resolving framework dependency graph … 41 repositories, 7 layers
Layer 1/7  kernel, utils, validators …            installed
Layer 2/7  observability, cache, eda …            installed
…
Layer 7/7  starter-core, starter-domain …         installed
Done. Framework <version> available in ~/.m2.
```

Once this completes, a project that inherits `fireflyframework-parent` or imports
`fireflyframework-bom` resolves entirely offline — which is exactly why the
companion reactor in this book builds with `mvn -o verify`, no network required.

## Scaffolding a service

`flywork create` generates a new project from one of four archetypes, each aligned
to a tier (Chapter 14):

```text
$ flywork create --archetype domain --name lending-loan-origination
```

| Archetype | Produces | Starter |
|---|---|---|
| `core` | a system-of-record service (R2DBC, Flyway, web) | `starter-core` |
| `domain` | an orchestration service (CQRS, saga, EDA) | `starter-domain` |
| `application` | an experience/BFF service (`@Secure`, SDK clients) | `starter-application` |
| `library` | a shared library module | — |

The generated project already has the right parent, the tier starter, a sensible
package layout, an `application.yml`, and a passing smoke test — the same shape as
the modules you grew across this book.

## Troubleshooting

A short field guide to the issues you are most likely to meet.

!!! tip "Checkpoint — is the framework installed?"
    If a build fails to resolve `org.fireflyframework:*`, run `flywork setup` (or
    confirm `~/.m2/repository/org/fireflyframework/` is populated). Every other
    problem below assumes the framework is installed.

- **`BUILD FAILURE` resolving a framework artifact.** The version you declared is
  not in `~/.m2`. Either align to the version `flywork setup` installed, or import
  the matching `fireflyframework-bom`. Mixing two framework versions is the most
  common cause of a confusing `NoSuchMethodError` at runtime.
- **A reactive endpoint blocks under load / occasional stalls.** Something on the
  request path is blocking the event loop — a JDBC call, a `.block()`, a synchronous
  third-party SDK. Move it off the event loop (`subscribeOn(Schedulers.boundedElastic())`)
  or replace it with a reactive client. Re-read Chapter 5's "never block" warning.
- **A correlation or trace ID is missing in a downstream log.** Confirm
  `fireflyframework-observability` is present — it enables automatic Reactor context
  propagation. Without it, `ThreadLocal`/MDC values do not follow operators.
- **An `@EventListener` never fires.** The runtime matches `eventTypes` by the
  payload's simple class name; check the name matches and that EDA is enabled
  (`firefly.eda.enabled=true`) with a transport configured. Chapter 11 covers this.
- **A list endpoint ignores a filter parameter.** ID fields are excluded from the
  generic filter engine unless annotated `@FilterableId`. See Chapter 8.
- **`@Secure` returns 401/403 in a test.** The endpoint is genuinely secured; supply
  a permissive test security configuration or an authenticated test context, as the
  experience-tier slice does (Chapter 17).
- **Tests need Docker you do not have.** Prefer the in-process defaults — H2 for
  R2DBC, the in-JVM EDA transport, Caffeine for cache — exactly as this book's
  reactor does. Reach for Testcontainers (Chapter 23) only when a chapter calls for
  a real backend.

## Where to go next

With `flywork setup` done once and `flywork create` for each new service, standing
up a correct, consistent microservice is a single command — which is the whole
promise of Chapter 1, now at your fingertips.
