Uno de los superpoderes más silenciosos de Firefly es que las decisiones de
infraestructura son *propiedades*, no código. Cada capacidad se define mediante un
**puerto** — una interfaz que posee el módulo central del framework — y se satisface
con un **adaptador**, una implementación seleccionada en tiempo de ejecución según qué
jar esté en el classpath y una línea de YAML `firefly.*`. Por tanto, pasar de un
broker, caché o proveedor de identidad a otro es un cambio de dependencia más una
propiedad, sin tocar tus manejadores, servicios ni controladores. Este apéndice reúne
los cambios que es más probable que hagas.

El patrón es siempre el mismo, y es el mecanismo de autoconfiguración que el Capítulo 2
mostró en acción: el módulo *central* define el puerto y entrega un valor por defecto
sin infraestructura; un módulo *adaptador* en el classpath registra una implementación
alternativa tras guardas `@Conditional...`; y una propiedad `firefly.*` (o simplemente
qué jar adaptador está presente) selecciona cuál gana. Como la selección ocurre en el
momento del cableado, tu código de negocio solo ve el tipo del puerto — nunca nombra el
adaptador.

Así es exactamente como Lumen Lending sigue siendo ejecutable **sin Docker, sin base de
datos externa y sin broker de mensajería**. El ejemplo arranca sobre los valores por
defecto en proceso: la capa central persiste en **H2** en memoria (R2DBC en tiempo de
ejecución, JDBC para la migración de Flyway), y la capa de dominio emite eventos sobre
el transporte **`APPLICATION_EVENT` dentro de la JVM**. Promover cualquiera de estos a
infraestructura de producción es el tema de este apéndice, y en todos los casos la
receta son dos ediciones — añadir la dependencia del adaptador, establecer una propiedad
`firefly.*` — nunca una reescritura.

!!! note "Término clave — puerto y adaptador"
    Un **puerto** es la interfaz que posee un módulo central de Firefly (`EventPublisher`,
    `CacheAdapter`, `IdpAdapter`, …). Un **adaptador** es una implementación concreta,
    empaquetada en su propio módulo y registrada por la autoconfiguración. Tu código
    depende del puerto; el framework enlaza el adaptador. Intercambiar adaptadores nunca
    toca el código que depende solo del puerto — el sentido mismo del patrón.

## Transporte de eventos (EDA)

El núcleo de EDA (`fireflyframework-eda`) define los puertos `EventPublisher` y
`EventConsumer` y las anotaciones `@EventPublisher` / `@EventListener` que usa tu código
de dominio. En Lumen Lending la capa de dominio publica sobre el transporte dentro de la
JVM — `PublisherType.APPLICATION_EVENT`, que enruta los eventos a través del propio
`ApplicationEventPublisher` de Spring en la misma JVM, de modo que no hay ningún broker
que ejecutar. Pasar a un broker real es un jar más una propiedad; tus productores
`@EventPublisher` y tus consumidores `@EventListener` quedan intactos.

```yaml
firefly:
  eda:
    enabled: true
    default-publisher-type: KAFKA   # APPLICATION_EVENT | KAFKA | RABBITMQ | POSTGRES | AUTO
```

| Transporte | Dependencia del adaptador | Carácter |
|---|---|---|
| Dentro de la JVM | (integrado) | mismo proceso, sin infraestructura — cómo se ejecuta y se prueba Lumen Lending |
| Kafka | `fireflyframework-eda-kafka` | persistente, particionado, ordenado, alto rendimiento |
| RabbitMQ | `fireflyframework-eda-rabbitmq` | persistente, topologías flexibles de exchange/enrutamiento |
| Postgres | `fireflyframework-eda-postgres` | outbox transaccional + `LISTEN`/`NOTIFY`, sin broker adicional |

Con `default-publisher-type: AUTO`, Firefly resuelve el mejor transporte disponible en
el orden Kafka → RabbitMQ → Postgres → dentro de la JVM, recurriendo a lo que haya en el
classpath. Por eso el ejemplo puede dejar el valor por defecto tal cual: sin ningún jar
de broker presente, `AUTO` recae en el transporte dentro de la JVM y la aplicación
arranca limpiamente.

Una sutileza que merece nombrarse: el transporte dentro de la JVM es *síncrono y en
proceso*, por lo que un `@EventListener` se ejecuta en el hilo del publicador y comparte
su destino. Un broker real los desacopla — el publicador retorna en cuanto el evento se
acepta de forma duradera, y el consumidor se ejecuta de forma independiente, posiblemente
en otra instancia, posiblemente tras un reinicio. Esa diferencia es invisible para tu
código pero muy visible para tus operaciones, y por eso el cambio es una decisión
deliberada de producción y no un accidente del classpath.

!!! spring "Equivalente en Spring"
    Spring te permite publicar en proceso mediante `ApplicationEventPublisher` y, con
    Spring Kafka o Spring AMQP, hacia un broker — pero cableas tú mismo la infraestructura
    de productor y consumidor, y los dos estilos no se parecen en nada a nivel de código.
    El puerto `EventPublisher` de Firefly los unifica: el mismo código
    `@EventPublisher`/`@EventListener` se ejecuta sobre el transporte dentro de la JVM en
    las pruebas y sobre Kafka en producción, elegido por `default-publisher-type`.

## Caché

El núcleo de caché (`fireflyframework-cache`) define el puerto reactivo `CacheAdapter`,
con **Caffeine** integrado como capa L1 (en proceso). Registrar un proveedor distribuido
te da una L2: un `SmartCacheAdapter` write-through mantiene una capa Caffeine local
delante del almacén distribuido, de modo que las lecturas siguen siendo rápidas y las
escrituras se propagan. El bus de consultas CQRS que viste autoconfigurarse en el log de
arranque del Capítulo 2 (`CQRS Query Bus configured with cache support`) consume este
puerto — dale una L2 y el cacheado de consultas se vuelve distribuido sin tocar ningún
manejador.

```yaml
firefly:
  cache:
    type: AUTO          # AUTO picks by provider priority: Redis > Hazelcast > JCache > Caffeine
```

| Proveedor | Dependencia del adaptador | Uso |
|---|---|---|
| Caffeine | (integrado) | L1 en proceso, sin infraestructura — el valor por defecto |
| Redis | `fireflyframework-cache-redis` | L2 distribuida, la opción común de producción |
| Hazelcast | `fireflyframework-cache-hazelcast` | malla de datos en memoria distribuida |
| JCache | `fireflyframework-cache-jcache` | cualquier proveedor JSR-107 |
| Postgres | `fireflyframework-cache-postgres` | caché respaldada por base de datos, sin almacén adicional |

Lumen Lending no cablea una caché distribuida — no la necesita a su tamaño, y el valor
por defecto Caffeine en proceso mantiene el ejemplo libre de infraestructura. El sentido
de la tabla es lo que costaría añadir una: una dependencia `fireflyframework-cache-redis`
y `firefly.cache.type: REDIS` (o simplemente `AUTO` con el jar presente), más las
propiedades de conexión que Redis necesita. Sin cambios en el código de servicio.

## Identidad (IDP)

El núcleo de IDP define un único puerto `IdpAdapter` que cubre toda la superficie de
identidad — inicio de sesión, refresco de token, introspección, CRUD de usuarios, MFA y
gestión de sesiones — de modo que tu código llama a una API consistente
independientemente del proveedor que haya detrás. El proveedor es una propiedad; cada
módulo adaptador se activa mediante `@ConditionalOnProperty`.

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
| Base de datos local | `fireflyframework-idp-internal-db` |

Esta es la única capacidad en la que Lumen Lending toca la historia de seguridad sin
cablear un proveedor. La capa de experiencia lleva anotaciones `@Secure` a nivel de
método en sus endpoints BFF, pero se ejecuta localmente con
`firefly.application.security.enabled: false`, así que las anotaciones están presentes en
el código pero inertes en tiempo de ejecución — exactamente el punto medio honesto que
busca este libro. Levantar autenticación real es el cambio de arriba: añade un jar
adaptador de IDP, establece `firefly.idp.provider`, activa la seguridad, y las
anotaciones `@Secure` que los controladores ya declaran empiezan a aplicarse contra el
proveedor configurado. El puerto `IdpAdapter` es la forma en que el framework mantiene
esa aplicación agnóstica del proveedor.

## Notificaciones

Los servicios de canal — `EmailService`, `SMSService`, `PushService` — se sitúan sobre
puertos específicos de cada proveedor, elegidos de forma independiente por canal. Tu
código pide al servicio de canal que envíe; el proveedor configurado realiza la entrega.

```yaml
firefly:
  notifications:
    email: { provider: sendgrid }   # sendgrid | resend
    sms:   { provider: twilio }
    push:  { provider: firebase }
```

| Canal | Proveedores (módulos adaptadores) |
|---|---|
| Email | SendGrid, Resend |
| SMS | Twilio |
| Push | Firebase Cloud Messaging |

Las notificaciones **no están cableadas en Lumen Lending** — el flujo de solicitud de
préstamo del ejemplo no envía ni un email ni una notificación push. Se muestran aquí como
*dónde se conectan*: un servicio de préstamos de producción llamaría a `EmailService`,
por ejemplo, al enviar una solicitud, y seleccionaría SendGrid o Resend por propiedad. La
forma del cambio es idéntica a las demás — un adaptador de proveedor en el classpath, una
propiedad `firefly.notifications.*` para elegirlo — que es la razón por la que esta
capacidad se gana un lugar en el apéndice de adaptadores aunque el slice no la ejercite.

## Contenido y firma electrónica (ECM)

El núcleo de ECM selecciona un adaptador de almacenamiento y un proveedor de firma
electrónica por propiedad — el contrato y el flujo de firma esbozados en el Apéndice C
dependen solo de los puertos, nunca de S3 ni de DocuSign por su nombre.

```yaml
firefly:
  ecm:
    adapter-type: aws-s3        # storage: aws-s3 | azure-blob
    esignature:
      provider: docusign        # docusign | adobe-sign | logalty
```

Como las notificaciones, ECM **no está cableado en el ejemplo**: Lumen Lending no almacena
documentos ni solicita firmas. El emparejamiento se muestra por completitud porque una
originación de préstamos real termina en un contrato firmado — el almacenamiento elige un
almacén de objetos (`aws-s3` o `azure-blob`), la firma electrónica elige un proveedor
(`docusign`, `adobe-sign` o `logalty`), y el código del flujo permanece igual en todos
ellos.

## La conclusión

Como cada uno de estos es un puerto con un adaptador seleccionado por propiedad, puedes
desarrollar contra los valores por defecto en proceso — sin Docker, sin broker, pruebas
rápidas, exactamente cómo arranca Lumen Lending y cómo se ejecutan sus 33 pruebas — y
cambiar a infraestructura de producción sin editar una sola línea de lógica de negocio.
Las dos capacidades que el ejemplo realmente ejercita (EDA dentro de la JVM, persistencia
en H2) y las cuatro que deja como puntos de conexión (caché, IDP, notificaciones, ECM)
comparten la misma receta de dos ediciones: añadir la dependencia del adaptador,
establecer una propiedad `firefly.*`. Desarrolla sobre los valores por defecto, despliega
sobre la infraestructura, no cambies nada entremedias. Prueba lo que despliegas; despliega
lo que probaste.
