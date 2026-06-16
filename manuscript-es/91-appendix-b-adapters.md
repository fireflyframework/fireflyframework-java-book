One of Firefly's quietest superpowers is that infrastructure choices are
*properties*, not code. Each capability is defined by a port (an interface) and
satisfied by an adapter chosen at runtime — so moving from one broker, cache, or
identity provider to another is a dependency swap plus a line of YAML, with no
change to your handlers, services, or controllers. This appendix collects the
swaps you are most likely to make.

The pattern is always the same: the *core* module gives you the port and a default;
an *adapter* module on the classpath registers an implementation; and a
`firefly.*` property (or simply which adapter jar is present) selects it.

## Event transport (EDA)

The EDA core (`fireflyframework-eda`) defines `EventPublisher` / `EventConsumer`.
In Lumen Lending the domain emits events over the in-JVM transport
(`PublisherType.APPLICATION_EVENT`). Moving to a real broker is a jar plus a
property — your `@EventPublisher` and `@EventListener` code is untouched.

```yaml
firefly:
  eda:
    enabled: true
    default-publisher-type: KAFKA   # APPLICATION_EVENT | KAFKA | RABBITMQ | POSTGRES | AUTO
```

| Transport | Adapter dependency | Character |
|---|---|---|
| In-JVM | (built in) | same-process, no infra — ideal for tests and a monolith |
| Kafka | `fireflyframework-eda-kafka` | persistent, ordered, high-throughput |
| RabbitMQ | `fireflyframework-eda-rabbitmq` | persistent, flexible routing |
| Postgres | `fireflyframework-eda-postgres` | transactional outbox + LISTEN/NOTIFY, no extra broker |

With `default-publisher-type: AUTO`, Firefly resolves the best available transport
in the order Kafka → RabbitMQ → Postgres → in-JVM.

## Cache

The cache core (`fireflyframework-cache`) defines the reactive `CacheAdapter` port
with Caffeine built in as the L1. Registering a distributed provider gives you an
L2 — a write-through `SmartCacheAdapter` keeps a local Caffeine layer in front of it.

```yaml
firefly:
  cache:
    type: AUTO          # AUTO picks by provider priority: Redis > Hazelcast > JCache > Caffeine
```

| Provider | Adapter dependency | Use |
|---|---|---|
| Caffeine | (built in) | in-process L1, zero infra |
| Redis | `fireflyframework-cache-redis` | distributed L2 |
| Hazelcast | `fireflyframework-cache-hazelcast` | distributed in-memory grid |
| JCache | `fireflyframework-cache-jcache` | any JSR-107 provider |
| Postgres | `fireflyframework-cache-postgres` | database-backed cache |

## Identity (IDP)

The IDP core defines one `IdpAdapter` port (login, refresh, introspection, user
CRUD, MFA, sessions). The provider is one property; each adapter activates by
`@ConditionalOnProperty`.

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

## Notifications

Channel services (`EmailService`, `SMSService`, `PushService`) sit over provider
ports, chosen per channel.

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

## Content & e-signature (ECM)

The ECM core selects a storage adapter and an e-signature provider by property —
the contract flow in Appendix C depends only on the ports.

```yaml
firefly:
  ecm:
    adapter-type: aws-s3        # storage: aws-s3 | azure-blob
    esignature:
      provider: docusign        # docusign | adobe-sign | logalty
```

## The takeaway

Because every one of these is a port with a property-selected adapter, you can
develop against the in-process defaults (no Docker, fast tests — exactly how Lumen
Lending's tests run) and switch to production infrastructure without editing a
single line of business logic. Test what you ship; ship what you tested.
