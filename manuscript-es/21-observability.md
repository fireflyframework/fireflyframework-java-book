Una solicitud de préstamo atraviesa tres capas y una docena de operadores reactivos
antes de que un cliente vea una decisión. Cuando algo sale mal a las dos de la
madrugada, lo único que se interpone entre tú y un incidente de producción a ciegas
es lo que el servicio *emitió mientras se ejecutaba*: sus logs, sus métricas, sus
trazas. La observabilidad es la disciplina de hacer que un sistema en ejecución se
explique a sí mismo, y en la pila reactiva resulta más difícil de lo que parece: el
hilo que inició una petición rara vez es el hilo que la termina, así que los
identificadores de correlación que deberían hilvanar una historia tienden a caerse
por el camino.

Este es el capítulo donde se anuda el hilo que llevamos tirando desde el Capítulo 1.
Cada servicio de Lumen que has construido ya emite **logs JSON estructurados**
sellados con un `traceId`, un `spanId` y un `X-Transaction-Id`; ya expone la salud de
Actuator y un scrape de Prometheus; y ya mantiene vivo ese contexto de correlación a
través de cada `flatMap` y `publishOn` — sin una sola línea de código de
observabilidad en ningún controlador, manejador o servicio que hayas escrito. Nada de
ello está en el código fuente del ejemplo, porque todo viene en el módulo
`fireflyframework-observability` que cada starter de capa incorpora. Ya viste la
prueba en el Capítulo 2: el log de arranque del servicio core imprimió
`Reactor automatic context propagation enabled`, y una petición de préstamo volvió
sellada con un `traceId` y un `spanId` que nunca pidió. Este capítulo te muestra
exactamente qué cablea ese módulo, por qué la correlación reactiva funciona de verdad,
y dónde te enchufas cuando necesitas más.

No hay test acompañante para este capítulo — la observabilidad trata sobre lo que un
servicio *en ejecución* emite a los logs, a un scrape de métricas y a un colector de
trazas, no sobre un valor que un `StepVerifier` pueda aseverar. Así que todo lo que
sigue es ilustrativo: configuración real y código de framework real, leídos para
entender cómo se comporta el cableado, en lugar de un trozo que verifica un build.
Donde una afirmación se apoya en código del framework, la prosa nombra la clase para
que puedas abrirla tú mismo; donde se apoya en configuración, nombra la propiedad en
el `application-firefly-observability.yml` del módulo.

## Lo que el starter ya te da

Los tres servicios de Lumen declaran `fireflyframework-starter-core` (la capa core en
`:8081`), `-starter-domain` (la capa de dominio en `:8082`) y `-starter-application`
(el BFF de experiencia en `:8080`). Cada uno de esos starters depende,
transitivamente, de `fireflyframework-observability` — así que en cuanto un servicio
de Lumen está en el classpath, una pila de observabilidad completa se autoconfigura.
No optaste por nada de ello servicio a servicio; llegó con el starter, exactamente de
la forma en que el Capítulo 1 prometió que llegarían las cuestiones transversales.

En concreto, sin código ni configuración en el ejemplo, cada servicio de Lumen
obtiene:

- **Logs JSON estructurados** a la consola — el logging estructurado nativo de Spring
  Boot 3 puesto en formato `logstash` — con el contexto de traza y de transacción
  promovido a campos de primer nivel.
- **Métricas de Micrometer** bajo un único espacio de nombres
  `firefly.{module}.{metric}`, exportadas a Prometheus por defecto.
- **Trazado distribuido** puenteado a **OpenTelemetry** por defecto, usando
  propagación W3C TraceContext, con `X-Transaction-Id` transportado como baggage de
  traza.
- **Endpoints de Actuator** para salud, info, métricas y un scrape de Prometheus,
  expuestos y configurados de forma consistente — incluyendo grupos de probes de
  liveness/readiness de Kubernetes y apagado elegante.
- **Propagación de contexto reactivo** — `Hooks.enableAutomaticContextPropagation()`
  llamado una vez al arranque — que es *por qué* todo lo anterior sobrevive a los
  saltos de hilo de una petición reactiva.

Todo eso lo dirige un único profile que aporta el módulo,
`application-firefly-observability.yml`, incluido automáticamente y sobreescribible
por tu propio `application.yml`. El resto del capítulo recorre cada capacidad,
fundamentada en la clase o propiedad del framework que la implementa.

!!! spring "Equivalente en Spring"
    Cada pieza aquí es un mecanismo estándar de Spring Boot 3 / Micrometer: el logging
    estructurado de Spring Boot (`logging.structured.format.console`), el
    `MeterRegistry` de Micrometer, Micrometer Tracing sobre un puente de
    OpenTelemetry, Spring Boot Actuator y la biblioteca `context-propagation` de
    Micrometer. Podrías ensamblar todo a mano en una aplicación WebFlux corriente. La
    contribución de Firefly es que se ensambla *una sola vez*, se ajusta mediante
    propiedades `firefly.observability.*` y es idéntico en toda la flota — de modo que
    el décimo servicio correlaciona sus logs exactamente igual que el primero.

## Una línea de log JSON real

Empieza por el artefacto que más mirarás durante un incidente: una línea de log. En
un profile de dev podrías ver una línea de consola bonita y coloreada (el módulo trae
también ese appender, condicionado a un profile `dev`), pero por defecto cada servicio
de Lumen emite **JSON estructurado**, un objeto por evento. Eso no es una decisión de
la aplicación — es el profile de observabilidad poniendo el formato de consola
estructurado nativo de Spring Boot en `logstash`:

```yaml
# From application-firefly-observability.yml (the module's default profile).
firefly:
  observability:
    logging:
      enabled: true
      structured-format: logstash
logging:
  structured:
    format:
      console: ${firefly.observability.logging.structured-format:logstash}
```

Viste la forma de *arranque* de estas líneas en el Capítulo 2 — `FIREFLY EDA …`,
`CQRS Query Bus configured …`, `Netty started on port 8081`. Las líneas que importan
en un incidente son las que se escriben *en un hilo de petición*, porque esas llevan
el contexto de correlación. Cuando el core de originación de préstamos escribe un log
mientras maneja una petición, la línea sobre el cable tiene este aspecto:

```json
{
  "timestamp": "2026-06-17T09:14:22.481Z",
  "level": "INFO",
  "logger": "com.firefly.lumen.core.service.LoanApplicationService",
  "thread": "reactor-http-nio-3",
  "message": "Created loan application",
  "traceId": "8a3f1c92b47e5d016f0a2c7d9e114b23",
  "spanId": "6f0a2c7d9e114b23",
  "X-Transaction-Id": "4f2c9e10-7b3a-4f6e-9c21-2a1d5b8e0c33"
}
```

Tres campos hacen que esta línea sea *útil* en lugar de meramente estar presente. El
`traceId` ata este log a todos los demás logs — en este servicio y en los de aguas
abajo — que pertenecen a la misma traza distribuida. El `spanId` identifica esta
unidad de trabajo concreta dentro de esa traza. El `X-Transaction-Id` es el propio
identificador de correlación de Firefly, el mismo que el `TransactionFilter` del
módulo web estampa en cada petición y respuesta (Capítulo 6) — el mismísimo valor que
lo viste acuñar en la secuencia de arranque del Capítulo 2,
`Generated new transaction ID: ce0c2ede-…`. Un operador puede tomar un
`X-Transaction-Id` de una cabecera de respuesta, pegarlo en una consulta de logs y
recuperar cada línea que la petición produjo a través de cada capa.

Esos nombres de campo exactos no son casuales. Son constantes que comparte todo el
framework, definidas en `org.fireflyframework.observability.logging.MdcConstants`:
`TRACE_ID = "traceId"`, `SPAN_ID = "spanId"` y
`TRANSACTION_ID = "X-Transaction-Id"`, junto a `userId`, `correlationId`, `requestId`
y unas pocas claves de event-sourcing. El mismo conjunto está reflejado en el
`logback-firefly.xml` del módulo, donde se le indica al encoder de Logstash que eleve
exactamente esas claves del MDC a campos de primer nivel:

```xml
<!-- From fireflyframework-observability logback-firefly.xml (illustrative). -->
<includeMdcKeyName>traceId</includeMdcKeyName>
<includeMdcKeyName>spanId</includeMdcKeyName>
<includeMdcKeyName>X-Transaction-Id</includeMdcKeyName>
<includeMdcKeyName>userId</includeMdcKeyName>
<includeMdcKeyName>correlationId</includeMdcKeyName>
<includeMdcKeyName>requestId</includeMdcKeyName>
```

Como cada módulo escribe sus logs a través de las mismas claves, la agregación de logs
en toda una flota es uniforme — una misma forma de consulta funciona en todas partes.
Un filtro por `traceId` en tu backend de logs hace match con el core, el dominio y la
capa de experiencia de forma idéntica, sin ningún mapeo de campos por servicio que
mantener.

!!! note "Término clave — MDC (Mapped Diagnostic Context)"
    El **MDC** es el mapa clave/valor por contexto de SLF4J que un encoder de logging
    puede leer y adjuntar a cada línea. En el Java bloqueante clásico, pones un
    `traceId` en el MDC al inicio de una petición y cada línea de log en ese hilo lo
    lleva gratis. En la pila reactiva eso se rompe — y arreglarlo es la clave de bóveda
    de este capítulo (ver *Por qué sobrevive la correlación*, más abajo). El encoder de
    Firefly eleva las claves del MDC en `MdcConstants` a campos JSON de primera clase,
    de modo que una vez el contexto está *presente* en el hilo en ejecución, queda
    *registrado* sin ningún trabajo por sentencia.

!!! warning "El PII nunca llega a estos logs por accidente"
    Una línea de log que lleva un `traceId` nunca debe llevar un documento nacional de
    identidad ni un número de tarjeta. El enmascarado de PII del Capítulo 6 es la otra
    mitad de esta historia: el módulo web redacta los datos personales identificables
    — correos, documentos de identidad, números de tarjeta — *antes* de que se
    registren. El logging estructurado hace las líneas consultables; el enmascarado de
    PII las mantiene seguras para consultarlas. Trata ambos como un par: quieres cada
    petición trazable y ninguna petición filtrándose. El id de correlación es tu asa
    sobre una petición; nunca es la identidad del solicitante.

## Métricas: un espacio de nombres para toda la flota

Los logs te cuentan sobre una petición; las métricas te cuentan sobre todas a la vez —
tasas, duraciones, ratios de error, profundidades de cola. Firefly emite sus métricas
de framework a través de Micrometer e impone una única convención de nombres para que
un cuadro de mando construido para un servicio se lea igual en el siguiente:
**`firefly.{module}.{metric}`**.

Esa convención se impone en código, no a mano. La clase
`org.fireflyframework.observability.metrics.MetricNaming` construye cada nombre de
métrica del framework a partir de un módulo y una métrica, y rechaza un módulo que no
sea alfanumérico en minúsculas:

```java
// From org.fireflyframework.observability.metrics.MetricNaming (illustrative).
public static final String FIREFLY_PREFIX = "firefly";

public static String prefix(String module) {            // e.g. "cqrs" -> "firefly.cqrs"
    if (module == null || !module.matches("[a-z][a-z0-9]*")) {
        throw new IllegalArgumentException(
                "Module must be lowercase alphanumeric starting with a letter: " + module);
    }
    return FIREFLY_PREFIX + "." + module;
}

public static String name(String prefix, String metric) {   // "firefly.cqrs" + "command.processed"
    if (metric == null || metric.isBlank()) {
        throw new IllegalArgumentException("Metric name must not be blank");
    }
    return prefix + "." + metric;                            //  -> "firefly.cqrs.command.processed"
}
```

Las métricas de módulo extienden una base compartida, `FireflyMetricsSupport`, que
prefija cada contador, temporizador, resumen y gauge automáticamente y los cachea en
un `ConcurrentHashMap`. La clase de métricas de un módulo nombra solo su propia métrica
corta — el prefijo `firefly.{module}.` se le suministra, y las claves de tag vienen de
las constantes compartidas de `MetricTags` (`command.type`, `event.type`, `status`,
`error.type`, …):

```java
// Illustrative: how a module declares its own metrics on the shared base.
public class CqrsMetrics extends FireflyMetricsSupport {
    public CqrsMetrics(@Nullable MeterRegistry registry) {
        super(registry, "cqrs");                        // module = "cqrs"
    }
    public void commandProcessed(String commandType) {
        counter("command.processed",                    // becomes firefly.cqrs.command.processed
                MetricTags.COMMAND_TYPE, commandType).increment();
    }
}
```

Así que cuando la capa de dominio de Lumen despacha un comando a través del bus CQRS
(Capítulo 10) o publica un evento a través del runtime de EDA (Capítulo 11), los
medidores resultantes aterrizan bajo `firefly.cqrs.*` y `firefly.eda.*` — nombres
predecibles que puedes graficar sin leer el código fuente de ningún servicio. Un
scrape de Prometheus del servicio core muestra los medidores del framework junto a los
propios de Spring Boot (`http.server.requests`, gauges de la JVM y del pool de R2DBC),
todos del único registry, y cada medidor lleva un tag `application` fijado a partir de
`spring.application.name` de modo que un cuadro de mando compartido puede dividir por
servicio.

Un detalle que merece la pena interiorizar: `FireflyMetricsSupport` es
**null-safe**. Lee su método `counter(...)` y la primera línea es `if (meterRegistry == null) return
NOOP_COUNTER;`. Cuando no hay ningún `MeterRegistry` en el classpath — digamos un slice
test con Actuator ausente — cada contador, temporizador, resumen y gauge se convierte
en un no-op preasignado en lugar de un `NullPointerException`. La instrumentación de
métricas nunca cambia si tu lógica de negocio se ejecuta; solo la observa. Sus
temporizadores también publican los percentiles p50, p95 y p99 por defecto, de modo que
un panel de latencia funciona en cuanto aparece un medidor.

!!! note "Término clave — Observation de Micrometer"
    La API **Observation** de Micrometer es la idea unificadora bajo todo esto:
    "observas" una unidad de trabajo *una vez*, y Micrometer puede repartir esa única
    observación en una métrica (un temporizador/contador) *y* un span de traza — a
    través de cualesquiera backends que estén registrados. El `FireflyMetricsSupport`
    de Firefly registra temporizadores y contadores contra el registry de Micrometer, y
    su puente de trazado convierte los límites de petición en spans; ambos se alimentan
    del mismo registry, que es por lo que la temporización de una métrica y el span de
    una traza coinciden sobre la misma operación.

!!! tip "Punto de control"
    Aquí no hay aserción de métrica que ejecutar — una métrica es una propiedad de un
    registry *en ejecución*, no una señal que `StepVerifier` compruebe. Para ver la
    convención en vivo, arranca el servicio core (Capítulo 2) y haz `curl` a su
    endpoint de Prometheus en `http://localhost:8081/actuator/prometheus`. Busca en la
    salida `firefly_` (Prometheus renderiza los puntos como guiones bajos, así que
    `firefly.cqrs.command.processed` aparece como `firefly_cqrs_command_processed`) y
    encontrarás los medidores del framework con el espacio de nombres exactamente como
    los construye `MetricNaming`.

## Trazado, puenteado a OpenTelemetry

Un `traceId` en una línea de log es solo la mitad del valor; la otra mitad es una
**traza** — el árbol de spans, uno por salto de servicio, que reconstruye el viaje
completo de una petición a través de la flota. Firefly configura Micrometer Tracing
con un puente de **OpenTelemetry** por defecto, el estándar neutral respecto al
proveedor que habla prácticamente todo colector de trazas (Jaeger, Tempo, Honeycomb,
un APM de nube).

Puedes leer los valores por defecto directamente del profile de observabilidad — el
prefijo es `firefly.observability`, así que son sobreescribibles por servicio o en
toda la flota:

```yaml
# From application-firefly-observability.yml (firefly.observability.*).
firefly:
  observability:
    metrics:
      enabled: true
      prefix: firefly
      exporter: PROMETHEUS        # PROMETHEUS (default), OTLP, or BOTH — no POM changes
    tracing:
      enabled: true
      bridge: OTEL                # OpenTelemetry (default); BRAVE for Zipkin/B3 estates
      sampling-probability: 1.0   # sample every request; lower it in production
      propagation-type: W3C       # W3C TraceContext (composite propagator also reads B3)
      baggage-fields:
        - X-Transaction-Id        # carry the transaction id as trace baggage
```

Cuatro elecciones aquí son determinantes. El **puente `OTEL`** significa que los spans
se exportan sobre OTLP a cualquier colector compatible con OpenTelemetry (el profile
apunta a `otel/opentelemetry-collector-contrib` en los puertos estándar — gRPC `4317`,
HTTP `4318`); cambia la propiedad `bridge` a `BRAVE` y obtienes propagación B3 para una
infraestructura Zipkin heredada, sin cambio de código ni de POM. El **exportador
`PROMETHEUS`** es el valor por defecto para métricas, conmutable a `OTLP` o `BOTH` por
el mismo tipo de cambio de propiedad. La **propagación `W3C`** significa que las
cabeceras estándar `traceparent`/`tracestate` llevan la traza a través de un salto HTTP
— y como el propagador subyacente es un compuesto (`W3C,B3`), un salto desde un vecino
que solo habla B3 sigue leyéndose correctamente. Así que cuando la capa de experiencia
de Lumen llama a la capa de dominio, el mismo `traceId` continúa, y los spans de los
dos servicios se anidan en una sola traza. Y listar `X-Transaction-Id` **como campo de
baggage** significa que el propio id de correlación de Firefly viaja dentro del
contexto de traza, manteniendo el identificador de log y el identificador de traza
unidos de extremo a extremo.

Hay una costura específica de Firefly en las llamadas salientes. Spring Boot ya
autoconfigura un `ObservationWebClientCustomizer` que propaga el contexto de traza
estándar (la cabecera `traceparent`) en cada petición de `WebClient` cuando
`micrometer-tracing` está presente. El `TracingWebClientCustomizer` de Firefly añade
*solo* la cabecera `X-Transaction-Id` por encima de eso — y lee el valor primero del
`Context` de Reactor, recurriendo al MDC para llamadores no reactivos:

```java
// From org.fireflyframework.observability.tracing.TracingWebClientCustomizer (illustrative).
builder.filter((request, next) ->
        Mono.deferContextual(ctx -> {
            String txId = ctx.getOrDefault(MdcConstants.TRANSACTION_ID, (String) null);
            if (txId == null) {
                txId = MDC.get(MdcConstants.TRANSACTION_ID);
            }
            if (txId != null) {
                return next.exchange(ClientRequest.from(request)
                        .header(MdcConstants.TRANSACTION_ID_HEADER, txId)
                        .build());
            }
            return next.exchange(request);
        }));
```

Esa es la razón por la que el submit en vivo `exp → domain → core` que ejecutaste en el
Capítulo 2 — el `WebClient` del BFF llamando al dominio, el paso raíz de la saga
llamando al core — lleva adelante tanto la traza estándar *como* el id de transacción
de Firefly a través de cada salto HTTP, sin que cablees una sola cabecera. (Leer el id
de transacción del `Context` de Reactor es el mismo truco de propagación que la
siguiente sección explica al completo.)

!!! spring "Equivalente en Spring"
    Esto es Micrometer Tracing — la misma biblioteca que usa una aplicación Spring Boot
    3 corriente — sobre `micrometer-tracing-bridge-otel`, con el propio
    `ObservationWebClientCustomizer` de Spring Boot haciendo la propagación W3C
    estándar. En un servicio hecho a mano eliges el artefacto del puente, fijas el
    formato de propagación, registras el baggage y decides en cada `WebClient` si
    llevar tu propia cabecera de correlación. Firefly elige valores por defecto
    sensatos (`OTEL`, `W3C`/B3-compuesto, muestrear-todo, baggage de id de transacción)
    y añade el customizer de `X-Transaction-Id` de forma uniforme, de modo que la flota
    traza de forma consistente en lugar de que cada equipo decida de forma distinta.

## Salud y la superficie de Actuator

Operaciones necesita una respuesta plana a "¿está este servicio vivo y listo?", y un
objetivo de métricas que hacer scrape. Spring Boot Actuator proporciona ambas cosas;
la `FireflyActuatorAutoConfiguration` de Firefly asegura que los mismos endpoints se
expongan de forma consistente en cada servicio, dirigidos por el profile por defecto
del módulo:

```yaml
# From application-firefly-observability.yml: Actuator exposure (overridable).
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: always
      show-components: always
      probes:
        enabled: true                 # Kubernetes liveness/readiness probe groups
      group:
        liveness:
          include: livenessState
        readiness:
          include: readinessState,db,diskSpace
server:
  shutdown: graceful                  # drain in-flight requests before exit
```

Así que de fábrica cada servicio de Lumen responde a `/actuator/health`,
`/actuator/info`, `/actuator/metrics` y `/actuator/prometheus`. Como están puestos
`show-components: always` y `show-details: always`, una sola llamada de salud agrega
los subsistemas del framework, no solo el datasource. Viste la forma exacta en el
Capítulo 2 contra el servicio core — `cqrs`, `eda`, `r2dbc` y `ping`, con detalles:

```text
$ curl -s http://localhost:8081/actuator/health
{"status":"UP","groups":["liveness","readiness"],"components":{
  "cqrs":{"status":"UP","details":{"command_bus":"UP","query_bus":"UP","command_handlers":0,"query_handlers":0}},
  "eda":{"status":"UP","details":{"enabled":true,"message":"All EDA components are healthy"}},
  "r2dbc":{"status":"UP","details":{"database":"H2"}},
  "ping":{"status":"UP"}}}
```

Esos componentes de subsistema no vienen incorporados en Actuator — los módulos del
framework los aportan extendiendo `FireflyHealthIndicator`, una base que da a cada
indicador un nombre de componente y helpers de detalle estándar. Su propio Javadoc
muestra el patrón que sigue un indicador de EDA: reportar los publicadores activos,
luego plegar una tasa de error contra un umbral de modo que el componente vuelque a
`DOWN` si se incumple.

```java
// From org.fireflyframework.observability.health.FireflyHealthIndicator (illustrative).
public class EdaHealthIndicator extends FireflyHealthIndicator {
    public EdaHealthIndicator() {
        super("eda");
    }
    @Override
    protected void doHealthCheck(Health.Builder builder) throws Exception {
        builder.up().withDetail("publishers.active", getActivePublishers());
        addErrorRate(builder, getErrorRate(), 0.05);   // DOWN if error rate > 5%
    }
}
```

Así que la salud de un servicio de Lumen agrega los propios subsistemas del framework
con formas de detalle consistentes — `error.rate`, `pool.active`, `latency.p99.ms` —
en cada módulo que trae un indicador.

!!! note "Término clave — liveness vs. readiness"
    Una probe de **liveness** responde "¿está este proceso sano, o debería el
    orquestador reiniciarlo?". Una probe de **readiness** responde "¿puede esta
    instancia aceptar tráfico ahora mismo?" — un servicio puede estar vivo pero no
    listo mientras calienta un pool de conexiones. Spring Boot expone ambas como grupos
    de probes de salud; Firefly las activa por defecto (el grupo `readiness` integra
    `db` y `diskSpace`) y las empareja con `server.shutdown: graceful`, de modo que un
    servicio de Lumen sale de un balanceador de carga limpiamente durante el arranque y
    el apagado en lugar de descartar peticiones.

## Por qué sobrevive la correlación: la clave de bóveda reactiva

Ahora la piedra angular — la capacidad que el preludio y los Capítulos 1 y 5 marcaron
todos como lo más valioso que hace Firefly en la pila reactiva, y la razón por la que
cada línea de log JSON de arriba lleva de verdad el `traceId` *correcto*.

Recuerda el problema del Capítulo 5. Un `traceId` vive en un `ThreadLocal` (el MDC es
uno). En el Java bloqueante eso va bien: un hilo sirve una petición de principio a fin,
así que cada línea de log en ese hilo lleva el id correcto. En la pila reactiva la
garantía se evapora — una sola petición salta a través de muchos hilos a medida que
cruza `flatMap`, `publishOn` y límites de scheduler, y `ThreadLocal` **no** la sigue.
Sin intervención, tus logs reactivos vuelven en blanco en el campo `traceId`, o peor,
sellados con el id de una petición *distinta*.

El arreglo es una línea, y ya la has visto antes:

```java
Hooks.enableAutomaticContextPropagation();
```

Lo que *no* has visto es dónde vive. No está en ningún fuente de Lumen. Está en la
`ReactiveContextPropagationAutoConfiguration` del módulo de observabilidad, que la
llama una vez en un `@PostConstruct` al arranque — y registra la mismísima línea que
viste pasar en la secuencia de arranque del Capítulo 2:

```java
// From org.fireflyframework.observability.tracing
//   .ReactiveContextPropagationAutoConfiguration (illustrative).
@AutoConfiguration
@ConditionalOnClass({Hooks.class, ContextSnapshot.class})
@ConditionalOnProperty(prefix = "firefly.observability.context-propagation",
        name = "reactor-hooks-enabled", havingValue = "true", matchIfMissing = true)
public class ReactiveContextPropagationAutoConfiguration {

    private static final Logger log =
            LoggerFactory.getLogger(ReactiveContextPropagationAutoConfiguration.class);

    @PostConstruct
    void enableAutomaticContextPropagation() {
        Hooks.enableAutomaticContextPropagation();
        log.info("Reactor automatic context propagation enabled — ThreadLocal/MDC values " +
                "will automatically bridge to Reactor Context across thread boundaries");
    }
}
```

Ese `@PostConstruct` es por lo que el log de arranque del servicio core llevó esta
línea, byte a byte, entre las líneas de Actuator y Netty en el Capítulo 2:

```text
{"timestamp":"2026-06-17T08:21:44.319+0000","message":"Reactor automatic context propagation enabled — ThreadLocal/MDC values will automatically bridge to Reactor Context across thread boundaries","logger":"o.f.o.t.ReactiveContextPropagationAutoConfiguration","level":"INFO"}
```

Con ese hook habilitado, Reactor captura automáticamente los valores `ThreadLocal`
registrados — el MDC, el contexto de OpenTelemetry, el tenant — en el `Context` de
Reactor de la suscripción, y **los restaura alrededor de cada operador, en cualquier
hilo que lo ejecute**, mediante el puente `ContextSnapshot` de Micrometer. (Ese puente
es la mismísima razón por la que la autoconfiguración es
`@ConditionalOnClass({Hooks.class, ContextSnapshot.class})` — se activa solo cuando
`io.micrometer:context-propagation` está presente para hacer la restauración.) El
contexto de traza y de transacción cabalgan sobre la suscripción, no sobre el hilo. Así
que cuando la cadena de `flatMap` del core de originación de préstamos cruza de un hilo
`reactor-http-nio` a otro, el `traceId` lo sigue, y la línea de log JSON escrita en lo
profundo del pipeline lleva la correlación correcta — sin ningún `doOnEach`, sin
`contextWrite` manual y sin contabilidad de MDC en tu código.

Esta es la recompensa concreta de un hilo que ha recorrido todo el libro. El
`ExecutionContext` multi-tenant, el id de traza, el `X-Transaction-Id` — todos ellos
son valores respaldados por `ThreadLocal`, y todos ellos sobreviven a los operadores
reactivos de Lumen por exactamente una razón: el módulo de observabilidad habilitó el
hook y registró sus accessors por ti. Es también por lo que esta autoconfiguración es
condicional y sobreescribible — `firefly.observability.context-propagation.reactor-hooks-enabled`
es `true` por defecto (`matchIfMissing = true`), pero es una propiedad real que puedes
leer, auditar y (en el raro caso de que debas) desactivar.

!!! warning "Sin propagación de contexto, los logs reactivos mienten"
    Un id de correlación que no sobrevive a los límites de operador es peor que no tener
    id: adjunta silenciosamente la identidad de la petición *equivocada* a una línea de
    log, y una investigación de incidente persigue un fantasma. Si alguna vez
    construyes código reactivo fuera de Firefly, habilitar
    `Hooks.enableAutomaticContextPropagation()` y registrar tus accessors de
    `ThreadLocal` no es opcional — es la línea entre producción trazable e intrazable.
    Dentro de Firefly, el starter de observabilidad ya la ha trazado por ti.

!!! spring "Equivalente en Spring"
    El mecanismo es la biblioteca `context-propagation` de Micrometer más el hook de
    Reactor — disponible para cualquier aplicación Spring Boot 3 / WebFlux. En Spring
    corriente llamas tú mismo al hook y registras cada `ThreadLocalAccessor` (para el
    MDC, para el contexto de traza, para tu tenant) a mano, en cada servicio, y un
    servicio que lo olvida registra blancos. La
    `ReactiveContextPropagationAutoConfiguration` de Firefly lo hace una vez,
    condicionada a que las clases correctas estén presentes, idénticamente en toda la
    flota.

## Dónde te enchufas

Casi todo en este capítulo es gratis y automático, pero dos costuras son tuyas para
usar cuando un incidente o un cuadro de mando lo exija.

**Tus propias métricas de negocio.** Para contar o cronometrar algo específico del
dominio — digamos, las solicitudes de préstamo por encima de un umbral — extiende
`FireflyMetricsSupport` con el nombre de módulo de tu servicio y deja que la base lo
prefije. Heredas la convención del espacio de nombres `firefly.`, la null-safety, los
temporizadores que publican percentiles y el cacheo, y tu métrica se sitúa junto a la
del framework en el mismo scrape de Prometheus.

```java
// Sketch: a domain-specific metric on the shared base (illustrative).
public class LoanMetrics extends FireflyMetricsSupport {
    public LoanMetrics(@Nullable MeterRegistry registry) {
        super(registry, "loan");                    // module = "loan"
    }
    public void created() {
        counter("created").increment();             // -> firefly.loan.created
    }
}
```

**Tu propio contexto de log.** Para añadir un campo a cada línea de log dentro de una
unidad de trabajo — un `applicantId`, digamos — ponlo en el MDC cerca del borde y deja
que el encoder lo eleve, o escríbelo en el `Context` de Reactor y confía en el hook de
propagación para restaurarlo. Como el hook ya está habilitado, un valor que coloques en
contexto sobrevive a los saltos de operador igual que lo hace el `traceId`. (Añade la
clave a la lista `includeMdcKeyName` del encoder si lo quieres como campo JSON de
primer nivel en lugar de uno anidado.)

Lo que *no* deberías hacer es recurrir a la gestión manual del MDC dentro de una cadena
reactiva — el baile `doOnEach`/`doFinally` con el MDC que precede al hook de
propagación. Con la propagación automática de contexto activada, ese patrón es obsoleto
y propenso a errores; el Javadoc de `ReactiveContextPropagationAutoConfiguration` lo
dice con todas las letras. Deja que el framework mueva el contexto por ti.

## Lo que has aprendido {.recap}

- Cada servicio de Lumen hereda una pila de observabilidad completa de
  `fireflyframework-observability`, incorporada por cada starter de capa y configurada
  por un único profile (`application-firefly-observability.yml`) — **no aparece código
  de observabilidad en el código fuente del ejemplo**, y sin embargo los logs, las
  métricas, las trazas, la salud y la correlación reactiva funcionan todos.
- Los logs son **JSON estructurado** — el formato de consola `logstash` nativo de
  Spring Boot — con `traceId`, `spanId` y `X-Transaction-Id` promovidos a campos de
  primer nivel a partir de las claves compartidas de `MdcConstants`; el PII se
  enmascara (Capítulo 6) antes de que se escriba jamás.
- Las métricas del framework siguen una convención, **`firefly.{module}.{metric}`**,
  impuesta por `MetricNaming` y aplicada a través de la base null-safe
  `FireflyMetricsSupport` (cacheada, que publica percentiles); los medidores de CQRS y
  EDA aterrizan bajo `firefly.cqrs.*` y `firefly.eda.*`, etiquetados con el nombre
  `application` y exportados a Prometheus.
- El trazado se puentea a **OpenTelemetry** por defecto con propagación **W3C**
  (B3-compuesta), llevando `X-Transaction-Id` como baggage — añadido en las llamadas
  salientes de `WebClient` por `TracingWebClientCustomizer` — de modo que el id de log
  y el id de traza permanecen unidos; Actuator expone salud (con probes de Kubernetes y
  apagado elegante), info, métricas y un scrape de Prometheus, y los módulos aportan
  componentes de salud mediante `FireflyHealthIndicator`.
- La clave de bóveda es **`Hooks.enableAutomaticContextPropagation()`**, llamado una
  vez por `ReactiveContextPropagationAutoConfiguration` (la línea de arranque que viste
  en el Capítulo 2), que es *por qué* el contexto de traza y de tenant sobrevive a los
  saltos de hilo de Reactor — concluyendo el hilo de correlación que el libro ha
  llevado desde el Capítulo 1.

## Pruébalo tú mismo {.exercises}

1. **Lee la forma de tu propio log.** Arranca el servicio core (Capítulo 2) en `:8081`,
   haz una petición y copia una línea de log JSON escrita en el hilo de petición.
   Identifica los campos `traceId`, `spanId` y `X-Transaction-Id`, luego confirma que el
   `X-Transaction-Id` coincide con el valor de `Generated new transaction ID` del
   `TransactionFilter`. Acabas de hacer, a mano, lo que hace un operador durante un
   incidente.
2. **Encuentra los medidores del framework.** Haz `curl` al
   `http://localhost:8081/actuator/prometheus` del servicio core y haz grep de
   `firefly_`. Lista cada `firefly.{module}.{metric}` que encuentres y nombra qué
   capacidad de qué capítulo lo produjo. Confirma que cada medidor lleva un tag
   `application`.
3. **Traza un salto.** Con las tres capas levantadas, haz el submit en vivo del BFF del
   Capítulo 2 (`POST /api/v1/experience/lending/applications` en `:8080`) que se
   despliega en `exp → domain → core`, luego confirma que los tres servicios registran
   el *mismo* `traceId`. Ese id compartido es la propagación W3C haciendo su trabajo a
   través de dos saltos HTTP, con el `X-Transaction-Id` cabalgando junto vía
   `TracingWebClientCustomizer`.
4. **Demuestra que el hook importa.** Como experimento mental, pon
   `firefly.observability.context-propagation.reactor-hooks-enabled=false` en un profile
   de prueba y predice qué mostraría el campo `traceId` en una línea de log escrita
   después de un `publishOn`. (Respuesta: en blanco o equivocado — que es exactamente el
   bug que previene el `true` por defecto.) Ten en cuenta que el log de arranque también
   perdería la línea `Reactor automatic context propagation enabled`, porque el
   `@PostConstruct` ya no se ejecutaría. Restaura el valor por defecto.
5. **Añade una métrica de negocio.** Toma el boceto de `LoanMetrics` de arriba, con
   nombre de módulo `"loan"` y un `counter("created")`. Nombra la métrica plenamente
   cualificada que emitiría (`firefly.loan.created`), su renderizado en Prometheus
   (`firefly_loan_created_total`), y confirma que se situaría bajo el mismo espacio de
   nombres `firefly.` que la propia del framework — porque `MetricNaming.prefix("loan")`
   la construye de la misma forma que construye `firefly.cqrs`.

## Adónde ir ahora

Ahora puedes ver un servicio de Lumen en ejecución desde fuera — sus logs, sus
métricas, sus trazas, todos correlacionados. Esa visibilidad es la precondición de todo
lo que un operador hace a continuación: alertar sobre una métrica, seguir una traza
hasta un aguas abajo lento, consultar un id de transacción a través de las capas. Con
la flota observable, los capítulos restantes se vuelven hacia ejecutarla — empaquetado,
configuración y la forma operativa de un servicio Firefly en producción.
