Uno de los superpoderes más silenciosos de Firefly es que las decisiones de
infraestructura son *propiedades*, no código. Cada capacidad se define mediante un
puerto (una interfaz) y la satisface un adaptador elegido en tiempo de ejecución,
de modo que pasar de un broker, una caché o un proveedor de identidad a otro es un
intercambio de dependencias más una línea de YAML, sin cambio alguno en tus
manejadores, servicios o controladores. Este apéndice recopila los intercambios
que es más probable que necesites hacer.

El patrón es siempre el mismo: el módulo *core* te da el puerto y un valor por
defecto; un módulo *adaptador* en el classpath registra una implementación; y una
propiedad `firefly.*` (o simplemente qué adaptador jar está presente) lo selecciona.

## Transporte de eventos (EDA)

El core de EDA (`fireflyframework-eda`) define `EventPublisher` / `EventConsumer`.
En Lumen Lending el dominio emite eventos sobre el transporte dentro de la JVM
(`PublisherType.APPLICATION_EVENT`). Pasar a un broker real es un jar más una
propiedad: tu código `@EventPublisher` y `@EventListener` permanece intacto.

```yaml
firefly:
  eda:
    enabled: true
    default-publisher-type: KAFKA   # APPLICATION_EVENT | KAFKA | RABBITMQ | POSTGRES | AUTO
```

| Transporte | Dependencia del adaptador | Carácter |
|---|---|---|
| Dentro de la JVM | (incorporado) | mismo proceso, sin infra: ideal para tests y un monolito |
| Kafka | `fireflyframework-eda-kafka` | persistente, ordenado, alto rendimiento |
| RabbitMQ | `fireflyframework-eda-rabbitmq` | persistente, enrutado flexible |
| Postgres | `fireflyframework-eda-postgres` | outbox transaccional + LISTEN/NOTIFY, sin broker extra |

Con `default-publisher-type: AUTO`, Firefly resuelve el mejor transporte disponible
en el orden Kafka → RabbitMQ → Postgres → dentro de la JVM.

## Caché

El core de caché (`fireflyframework-cache`) define el puerto reactivo `CacheAdapter`
con Caffeine incorporado como L1. Registrar un proveedor distribuido te da una L2:
un `SmartCacheAdapter` de escritura directa mantiene una capa local de Caffeine por
delante de él.

```yaml
firefly:
  cache:
    type: AUTO          # AUTO picks by provider priority: Redis > Hazelcast > JCache > Caffeine
```

| Proveedor | Dependencia del adaptador | Uso |
|---|---|---|
| Caffeine | (incorporado) | L1 en proceso, infra cero |
| Redis | `fireflyframework-cache-redis` | L2 distribuida |
| Hazelcast | `fireflyframework-cache-hazelcast` | grid distribuido en memoria |
| JCache | `fireflyframework-cache-jcache` | cualquier proveedor JSR-107 |
| Postgres | `fireflyframework-cache-postgres` | caché respaldada por base de datos |

## Identidad (IDP)

El core de IDP define un único puerto `IdpAdapter` (login, refresco, introspección,
CRUD de usuarios, MFA, sesiones). El proveedor es una sola propiedad; cada adaptador
se activa mediante `@ConditionalOnProperty`.

```yaml
firefly:
  idp:
    provider: keycloak   # keycloak | cognito | azure-ad | internal-db
```

| Proveedor | Dependencia del adaptador |
|---|---|
| Keycloak | `fireflyframework-idp-keycloak` |
| AWS Cognito | `fireflyframework-idp-aws-cognito` |
| Microsoft Entra ID | `fireflyframework-idp-azure-ad` |
| BD local | `fireflyframework-idp-internal-db` |

## Notificaciones

Los servicios de canal (`EmailService`, `SMSService`, `PushService`) se apoyan sobre
puertos de proveedor, elegidos por canal.

```yaml
firefly:
  notifications:
    email: { provider: sendgrid }   # sendgrid | resend
    sms:   { provider: twilio }
    push:  { provider: firebase }
```

| Canal | Proveedores (modulos adaptadores) |
|---|---|
| Email | SendGrid, Resend |
| SMS | Twilio |
| Push | Firebase Cloud Messaging |

## Contenido y firma electrónica (ECM)

El core de ECM selecciona un adaptador de almacenamiento y un proveedor de firma
electrónica por propiedad: el flujo de contratos del Apéndice C depende únicamente
de los puertos.

```yaml
firefly:
  ecm:
    adapter-type: aws-s3        # storage: aws-s3 | azure-blob
    esignature:
      provider: docusign        # docusign | adobe-sign | logalty
```

## La conclusión

Como cada una de estas capacidades es un puerto con un adaptador seleccionado por
propiedad, puedes desarrollar contra los valores por defecto en proceso (sin Docker,
tests rápidos: exactamente como se ejecutan los tests de Lumen Lending) y cambiar a
infraestructura de producción sin editar una sola línea de lógica de negocio. Prueba
lo que despliegas; despliega lo que probaste.
