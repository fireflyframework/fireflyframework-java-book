One of Firefly's quietest superpowers is that infrastructure choices are
*properties*, not code. Each capability is defined by a **port** — an interface the
framework's core module owns — and satisfied by an **adapter**, an implementation
selected at runtime by which jar is on the classpath and a line of `firefly.*` YAML.
Moving from one broker, cache, or identity provider to another is therefore a
dependency swap plus a property, with no change to your handlers, services, or
controllers. This appendix collects the swaps you are most likely to make.

The pattern is always the same, and it is the auto-configuration mechanism Chapter 2
showed in action: the *core* module defines the port and ships a zero-infrastructure
default; an *adapter* module on the classpath registers an alternative
implementation behind `@Conditional...` guards; and a `firefly.*` property (or
simply which adapter jar is present) selects which one wins. Because the selection
happens at wiring time, your business code only ever sees the port type — it never
names the adapter.

This is exactly how Lumen Lending stays runnable with **no Docker, no external
database, and no message broker**. The sample boots on the in-process defaults: the
core tier persists to in-memory **H2** (R2DBC at runtime, JDBC for the Flyway
migration), and the domain tier emits events over the **in-JVM
`APPLICATION_EVENT`** transport. Promoting any one of these to production
infrastructure is the subject of this appendix, and in every case the recipe is two
edits — add the adapter dependency, set one `firefly.*` property — never a rewrite.

!!! note "Key term — port and adapter"
    A **port** is the interface a Firefly core module owns (`EventPublisher`,
    `CacheAdapter`, `IdpAdapter`, …). An **adapter** is a concrete implementation,
    packaged in its own module and registered by auto-configuration. Your code
    depends on the port; the framework binds the adapter. Swapping adapters never
    touches code that depends only on the port — the whole point of the pattern.

## Event transport (EDA)

The EDA core (`fireflyframework-eda`) defines the `EventPublisher` and
`EventConsumer` ports and the `@EventPublisher` / `@EventListener` annotations your
domain code uses. In Lumen Lending the domain tier publishes over the in-JVM
transport — `PublisherType.APPLICATION_EVENT`, which routes events through Spring's
own `ApplicationEventPublisher` in the same JVM, so there is no broker to run.
Moving to a real broker is a jar plus a property; your `@EventPublisher` producers
and `@EventListener` consumers are untouched.

```yaml
firefly:
  eda:
    enabled: true
    default-publisher-type: KAFKA   # APPLICATION_EVENT | KAFKA | RABBITMQ | POSTGRES | AUTO
```

| Transport | Adapter dependency | Character |
|---|---|---|
| In-JVM | (built in) | same-process, no infra — how Lumen Lending runs and tests |
| Kafka | `fireflyframework-eda-kafka` | persistent, partitioned, ordered, high-throughput |
| RabbitMQ | `fireflyframework-eda-rabbitmq` | persistent, flexible exchange/routing topologies |
| Postgres | `fireflyframework-eda-postgres` | transactional outbox + `LISTEN`/`NOTIFY`, no extra broker |

With `default-publisher-type: AUTO`, Firefly resolves the best available transport
in the order Kafka → RabbitMQ → Postgres → in-JVM, falling back to whatever is on
the classpath. That is why the sample can leave the default in place: with no broker
jar present, `AUTO` lands on the in-JVM transport and the app boots clean.

A subtlety worth naming: the in-JVM transport is *synchronous and in-process*, so an
`@EventListener` runs on the publisher's thread and shares its fate. A real broker
decouples them — the publisher returns once the event is durably accepted, and the
consumer runs independently, possibly on another instance, possibly after a restart.
That difference is invisible to your code but very visible to your operations, which
is why the swap is a deliberate production decision rather than an accident of the
classpath.

!!! spring "Spring parity"
    Spring lets you publish in-process via `ApplicationEventPublisher` and, with
    Spring Kafka or Spring AMQP, to a broker — but you wire the producer and consumer
    infrastructure yourself, and the two styles look nothing alike in code. Firefly's
    `EventPublisher` port unifies them: the same `@EventPublisher`/`@EventListener`
    code runs over the in-JVM transport in tests and over Kafka in production, chosen
    by `default-publisher-type`.

## Cache

The cache core (`fireflyframework-cache`) defines the reactive `CacheAdapter` port,
with **Caffeine** built in as the L1 (in-process) layer. Registering a distributed
provider gives you an L2: a write-through `SmartCacheAdapter` keeps a local Caffeine
layer in front of the distributed store, so reads stay fast and writes propagate.
The CQRS query bus you saw configure itself in the Chapter 2 boot log
(`CQRS Query Bus configured with cache support`) consumes this port — give it an L2
and query caching becomes distributed without touching a handler.

```yaml
firefly:
  cache:
    type: AUTO          # AUTO picks by provider priority: Redis > Hazelcast > JCache > Caffeine
```

| Provider | Adapter dependency | Use |
|---|---|---|
| Caffeine | (built in) | in-process L1, zero infra — the default |
| Redis | `fireflyframework-cache-redis` | distributed L2, the common production choice |
| Hazelcast | `fireflyframework-cache-hazelcast` | distributed in-memory data grid |
| JCache | `fireflyframework-cache-jcache` | any JSR-107 provider |
| Postgres | `fireflyframework-cache-postgres` | database-backed cache, no extra store |

Lumen Lending does not wire a distributed cache — it has no need for one at its
size, and the in-process Caffeine default keeps the sample infra-free. The point of
the table is what it would cost to add one: a `fireflyframework-cache-redis`
dependency and `firefly.cache.type: REDIS` (or simply `AUTO` with the jar present),
plus the connection properties Redis needs. No service code changes.

## Identity (IDP)

The IDP core defines one `IdpAdapter` port covering the whole identity surface —
login, token refresh, introspection, user CRUD, MFA, and session management — so
your code calls one consistent API regardless of the provider behind it. The
provider is one property; each adapter module activates by `@ConditionalOnProperty`.

```yaml
firefly:
  idp:
    provider: keycloak   # keycloak | cognito | azure-ad | internal-db
```

| Provider | Adapter dependency |
|---|---|
| Keycloak | `fireflyframework-idp-keycloak` |
| AWS Cognito | `fireflyframework-idp-aws-cognito` |
| Microsoft Entra ID | `fireflyframework-idp-azure-ad` |
| Local DB | `fireflyframework-idp-internal-db` |

This is the one capability where Lumen Lending touches the security story without
wiring a provider. The experience tier carries method-level `@Secure` annotations on
its BFF endpoints, but it runs locally with `firefly.application.security.enabled:
false`, so the annotations are present in the code and inert at runtime — exactly the
honest middle ground this book aims for. Standing up real authentication is the
swap above: add an IDP adapter jar, set `firefly.idp.provider`, flip security on, and
the `@Secure` annotations the controllers already declare begin enforcing against the
configured provider. The `IdpAdapter` port is how the framework keeps that enforcement
provider-agnostic.

## Notifications

Channel services — `EmailService`, `SMSService`, `PushService` — sit over
provider-specific ports, chosen independently per channel. Your code asks the channel
service to send; the configured provider does the delivering.

```yaml
firefly:
  notifications:
    email: { provider: sendgrid }   # sendgrid | resend
    sms:   { provider: twilio }
    push:  { provider: firebase }
```

| Channel | Providers (adapter modules) |
|---|---|
| Email | SendGrid, Resend |
| SMS | Twilio |
| Push | Firebase Cloud Messaging |

Notifications are **not wired in Lumen Lending** — the sample's loan-application flow
neither sends an email nor a push. They are shown here as *where they plug in*: a
production lending service would call `EmailService` on, say, application submission,
and select SendGrid or Resend by property. The shape of the swap is identical to the
others — a provider adapter on the classpath, a `firefly.notifications.*` property to
pick it — which is the whole reason this capability earns a place in the adapter
appendix even though the slice does not exercise it.

## Content & e-signature (ECM)

The ECM core selects a storage adapter and an e-signature provider by property — the
contract and signing flow sketched in Appendix C depends only on the ports, never on
S3 or DocuSign by name.

```yaml
firefly:
  ecm:
    adapter-type: aws-s3        # storage: aws-s3 | azure-blob
    esignature:
      provider: docusign        # docusign | adobe-sign | logalty
```

Like notifications, ECM is **not wired in the sample**: Lumen Lending stores no
documents and requests no signatures. The pairing is shown for completeness because a
real loan origination ends in a signed contract — storage picks an object store
(`aws-s3` or `azure-blob`), e-signature picks a provider (`docusign`, `adobe-sign`,
or `logalty`), and the flow code stays the same across all of them.

## The takeaway

Because every one of these is a port with a property-selected adapter, you can
develop against the in-process defaults — no Docker, no broker, fast tests, exactly
how Lumen Lending boots and how its 33 tests run — and switch to production
infrastructure without editing a single line of business logic. The two capabilities
the sample actually exercises (in-JVM EDA, H2 persistence) and the four it leaves as
plug-in points (cache, IDP, notifications, ECM) all share the same two-edit recipe:
add the adapter dependency, set one `firefly.*` property. Develop on the defaults,
deploy on the infrastructure, change nothing in between. Test what you ship; ship
what you tested.
