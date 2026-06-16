Una solicitud de préstamo atraviesa tres capas y una docena de operadores
reactivos antes de que un cliente vea una decisión. Cuando algo va mal a las dos de
la madrugada, lo único que se interpone entre tú y un incidente de producción a
ciegas es lo que el servicio *emitió mientras se ejecutaba*: sus logs, sus métricas,
sus trazas. La observabilidad es la disciplina de hacer que un sistema en ejecución
se explique a sí mismo, y en la pila reactiva es más difícil de lo que parece: el
hilo que inició una petición rara vez es el hilo que la termina, así que los
identificadores de correlación que deberían enlazar toda la historia tienden a
quedarse por el camino.

Este es el capítulo donde se ata el hilo que venimos tirando desde el Capítulo 1.
Cada servicio de Lumen que has construido ya emite **logs JSON estructurados**
sellados con un `traceId`, un `spanId` y un `X-Transaction-Id`; ya expone health de
Actuator y métricas de Prometheus; y ya mantiene vivo ese contexto de correlación a
través de cada `flatMap` y cada `publishOn`, sin una sola línea de código de
observabilidad en ningún controlador, manejador o servicio que escribieras. Nada de
esto está en el código fuente del ejemplo, porque todo viene en el módulo
`fireflyframework-observability` que cada starter de capa arrastra consigo. Este
capítulo te muestra exactamente qué cablea ese módulo, por qué la correlación
reactiva funciona de verdad y dónde te enchufas cuando necesitas más.

No hay test acompañante para este capítulo: la observabilidad trata de lo que un
servicio *en ejecución* emite a los logs, a un scrape de métricas y a un colector de
trazas, no de un valor que un `StepVerifier` pueda afirmar. Así que todo lo que
sigue es ilustrativo: configuración real y código de framework real, leído para
entender cómo se comporta el cableado, y no una porción que verifique una
compilación. Donde una afirmación se apoya en el código fuente del framework, la
prosa nombra la clase para que puedas abrirla tú mismo.

## Lo que el starter ya te da

Los tres servicios de Lumen declaran `fireflyframework-starter-core`,
`-starter-domain` y `-starter-application`. Cada uno de esos starters depende,
transitivamente, de `fireflyframework-observability`, así que en el momento en que un
servicio de Lumen está en el classpath, una pila de observabilidad completa se
autoconfigura sola. No te suscribiste a nada de ello por servicio; llegó con el
starter, exactamente de la forma que el Capítulo 1 prometió que llegarían las
cuestiones transversales.

En concreto, sin código y sin configuración en el ejemplo, cada servicio de Lumen
obtiene:

- **Logs JSON estructurados** a la consola, codificados por el `LogstashEncoder` de
  Logstash, con el contexto de traza y de transacción promovido a campos de primer
  nivel.
- **Métricas de Micrometer** bajo un único espacio de nombres
  `firefly.{module}.{metric}`, exportadas a Prometheus por defecto.
- **Trazado distribuido** puenteado a **OpenTelemetry** por defecto, usando
  propagación W3C TraceContext, con `X-Transaction-Id` transportado como baggage de
  traza.
- **Endpoints de Actuator** para health, info, metrics y un scrape de Prometheus,
  expuestos y configurados de forma consistente.
- **Propagación de contexto reactivo** —`Hooks.enableAutomaticContextPropagation()`
  llamado una vez al arrancar—, que es *por lo que* todo lo anterior sobrevive a los
  saltos de hilo de una petición reactiva.

El resto del capítulo recorre cada uno de estos puntos, anclado en la clase del
framework que lo implementa.

!!! spring "Equivalente en Spring"
    Cada pieza de aquí es un mecanismo estándar de Spring Boot 3 / Micrometer:
    Logback con un codificador JSON, el `MeterRegistry` de Micrometer, Micrometer
    Tracing sobre un puente a OpenTelemetry, Spring Boot Actuator y la librería
    `context-propagation` de Micrometer. Podrías ensamblarlo todo a mano en una
    aplicación WebFlux sin más. La contribución de Firefly es que se ensambla *una
    vez*, se afina con propiedades `firefly.observability.*` y es idéntico en toda la
    flota, de modo que el décimo servicio correla sus logs exactamente igual que el
    primero.

## Una línea de log JSON real

Empieza por el artefacto que más vas a mirar fijamente durante un incidente: una
línea de log. En desarrollo puede que veas una línea de consola bonita y coloreada,
pero en producción cada servicio de Lumen emite **JSON estructurado**, un objeto por
evento. Eso no es una decisión de la aplicación: es el `logback-firefly.xml` del
framework, incluido por la autoconfiguración de logging del módulo de
observabilidad, que cablea un `LogstashEncoder` sobre el appender de consola.

El codificador está configurado para promover un conjunto fijo de claves de MDC a
campos JSON de primer nivel. Cuando el core de originación de préstamos hace log
mientras maneja una petición, la línea que viaja por el cable tiene este aspecto:

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
`traceId` enlaza este log con cualquier otro log —en este servicio y en los de aguas
abajo— que pertenezca a la misma traza distribuida. El `spanId` identifica esta
unidad de trabajo concreta dentro de esa traza. El `X-Transaction-Id` es el propio
identificador de correlación de Firefly, el mismo que el `TransactionFilter` del
módulo web sella en cada respuesta HTTP (Capítulo 6), de modo que un operador puede
tomar un `X-Transaction-Id` de una cabecera de respuesta, pegarlo en una consulta de
logs y recuperar cada línea que la petición produjo a través de todas las capas.

Esos nombres de campo exactos no son casuales. Son constantes que comparte todo el
framework, definidas en `org.fireflyframework.observability.logging.MdcConstants`:
`TRACE_ID = "traceId"`, `SPAN_ID = "spanId"` y
`TRANSACTION_ID = "X-Transaction-Id"`, junto a `userId`, `correlationId` y
`requestId`. El codificador JSON está configurado para incluir exactamente esas
claves, de modo que cualquier módulo que haga log a través de ellas ve su contexto
elevado a un campo de primer nivel. Como cada módulo hace log a través de las mismas
claves, la agregación de logs a través de una flota es uniforme: una sola forma de
consulta funciona en todas partes.

!!! note "Termino clave — MDC (Mapped Diagnostic Context)"
    El **MDC** es el mapa clave/valor por contexto de SLF4J que un codificador de
    logging puede leer y adjuntar a cada línea. En el Java clásico y bloqueante,
    pones un `traceId` en el MDC al inicio de una petición y cada línea de log de ese
    hilo lo lleva gratis. En la pila reactiva eso se rompe, y arreglarlo es la pieza
    clave de este capítulo (ver *Por qué sobrevive la correlación*, más abajo). El
    codificador JSON de Firefly eleva las claves de MDC de `MdcConstants` a campos
    JSON de primera clase.

!!! warning "La PII nunca llega a estos logs por accidente"
    Una línea de log que lleva un `traceId` nunca debe llevar un documento nacional
    de identidad o un número de tarjeta. El enmascarado de PII del Capítulo 6 es la
    otra mitad de esta historia: el módulo web redacta datos de identificación
    personal —correos, documentos de identidad, números de tarjeta— *antes* de que se
    registren. El logging estructurado hace las líneas consultables; el enmascarado
    de PII las mantiene seguras para consultar. Trata ambas cosas como un par: quieres
    cada petición trazable y ninguna petición filtrando datos.

## Métricas: un espacio de nombres para toda la flota

Los logs te cuentan sobre una petición; las métricas te cuentan sobre todas a la vez:
tasas, duraciones, ratios de error, profundidades de cola. Firefly emite las métricas
de su framework a través de Micrometer e impone una única convención de nombres para
que un dashboard construido para un servicio se lea igual en el siguiente:
**`firefly.{module}.{metric}`**.

Esa convención se impone en código, no a mano. La clase
`org.fireflyframework.observability.metrics.MetricNaming` construye cada nombre de
métrica del framework a partir de un módulo y una métrica, y rechaza un módulo que no
sea alfanumérico en minúsculas:

```java
// From org.fireflyframework.observability.metrics.MetricNaming (illustrative).
public static String prefix(String module) {            // e.g. "cqrs" -> "firefly.cqrs"
    if (module == null || !module.matches("[a-z][a-z0-9]*")) {
        throw new IllegalArgumentException(
                "Module must be lowercase alphanumeric starting with a letter: " + module);
    }
    return FIREFLY_PREFIX + "." + module;               // FIREFLY_PREFIX = "firefly"
}

public static String name(String prefix, String metric) {   // "firefly.cqrs" + "command.processed"
    return prefix + "." + metric;                            //  -> "firefly.cqrs.command.processed"
}
```

Las métricas de los módulos extienden una base compartida, `FireflyMetricsSupport`,
que prefija cada contador y temporizador automáticamente y los cachea. La clase de
métricas de un módulo nombra solo su propia métrica corta; el prefijo
`firefly.{module}.` se le proporciona:

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

Así que cuando la capa de dominio de Lumen despacha un comando a través del bus de
CQRS (Capítulo 10) o publica un evento a través del runtime de EDA (Capítulo 11), los
medidores resultantes aterrizan bajo `firefly.cqrs.*` y `firefly.eda.*`: nombres
predecibles que puedes graficar sin leer el código fuente de ningún servicio. Un
scrape de Prometheus del servicio core muestra los medidores del framework junto a
los propios de Spring Boot (`http.server.requests`, gauges de la JVM y del pool de
R2DBC), todos desde el único registro.

Un detalle que vale la pena interiorizar: `FireflyMetricsSupport` es **null-safe**.
Cuando no hay ningún `MeterRegistry` en el classpath —digamos un test de porción con
Actuator ausente—, cada contador y temporizador se convierte en un no-op en lugar de
un `NullPointerException`. La instrumentación de métricas nunca cambia si tu lógica
de negocio se ejecuta; solo la observa.

!!! note "Termino clave — Micrometer Observation"
    La API **Observation** de Micrometer es la abstracción unificadora bajo todo
    esto: "observas" una unidad de trabajo *una vez*, y Micrometer despliega esa
    única observación en una métrica (un temporizador/contador) *y* un span de traza,
    a través de cualquier backend registrado. El soporte de métricas de Firefly
    registra temporizadores y contadores contra el registro de Micrometer, y su
    puente de trazado convierte las mismas fronteras de petición en spans; ambos se
    alimentan de ese único registro, que es por lo que el cronometraje de una métrica
    y el span de una traza concuerdan sobre la misma operación.

!!! tip "Punto de control"
    No hay aquí ninguna afirmación de métrica que ejecutar: una métrica es una
    propiedad de un registro *en ejecución*, no una señal que `StepVerifier`
    compruebe. Para ver la convención en vivo, arranca el servicio core (Capítulo 2)
    y haz `curl` a su endpoint de Prometheus en `/actuator/prometheus`. Busca en la
    salida `firefly_` (Prometheus renderiza los puntos como guiones bajos) y
    encontrarás los medidores del framework con su espacio de nombres exactamente como
    los construye `MetricNaming`.

## Trazado, puenteado a OpenTelemetry

Un `traceId` en una línea de log es solo la mitad del valor; la otra mitad es una
**traza**: el árbol de spans, uno por salto de servicio, que reconstruye el viaje
completo de una petición a través de la flota. Firefly configura Micrometer Tracing
con un puente a **OpenTelemetry** por defecto, el estándar neutral respecto al
proveedor que prácticamente todo colector de trazas (Jaeger, Tempo, Honeycomb, un APM
en la nube) habla.

Puedes leer los valores por defecto directamente de las propiedades de
observabilidad —el prefijo es `firefly.observability`, así que son sobreescribibles
por servicio o para toda la flota:

```yaml
# Firefly observability defaults (firefly.observability.*), overridable per service.
firefly:
  observability:
    tracing:
      enabled: true
      bridge: OTEL              # OpenTelemetry (default); BRAVE for Zipkin/B3 estates
      sampling-probability: 1.0 # sample every request; lower it under heavy load
      propagation-type: W3C     # W3C TraceContext headers (traceparent/tracestate)
      baggage-fields:
        - X-Transaction-Id      # carry the transaction id as trace baggage
```

Tres elecciones aquí son determinantes. El **puente `OTEL`** significa que los spans
se exportan por OTLP a cualquier colector compatible con OpenTelemetry; cambia una
sola propiedad a `BRAVE` y obtienes propagación B3 para un parque Zipkin heredado, sin
cambio de código. La **propagación `W3C`** significa que las cabeceras estándar
`traceparent`/`tracestate` transportan la traza a través de un salto HTTP, de modo
que cuando la capa de experiencia de Lumen llama a la capa de dominio, el mismo
`traceId` continúa, y los spans de ambos servicios anidan en una sola traza. Y listar
**`X-Transaction-Id` como campo de baggage** significa que el propio identificador de
correlación de Firefly viaja dentro del contexto de la traza, manteniendo el
identificador de log y el identificador de traza unidos de extremo a extremo.

El puente desde una llamada de un `WebClient` de Firefly a una traza propagada está
él mismo autoconfigurado: el `TracingWebClientCustomizer` del módulo de
observabilidad instala la propagación en los clientes salientes, de modo que las
llamadas resilientes del SDK del Capítulo 14 llevan la traza hacia adelante sin que
tú cablees ninguna cabecera.

!!! spring "Equivalente en Spring"
    Esto es Micrometer Tracing —la misma librería que usa una aplicación Spring Boot
    3 normal— sobre `micrometer-tracing-bridge-otel`. En un servicio hecho a mano
    eliges el artefacto del puente, fijas el formato de propagación, registras el
    baggage y cableas el customizer del `WebClient` tú mismo, en cada servicio.
    Firefly elige valores por defecto sensatos (`OTEL`, `W3C`, muestrear todo, baggage
    de transaction-id) y los aplica de manera uniforme, de modo que la flota traza de
    forma consistente en lugar de que cada equipo decida de forma distinta.

## Health y la superficie de Actuator

Operaciones necesita una respuesta plana a "¿está este servicio vivo y listo?", y un
objetivo de métricas que scrapear. Spring Boot Actuator proporciona ambas cosas; el
`FireflyActuatorAutoConfiguration` de Firefly garantiza que los mismos endpoints se
expongan de forma consistente en cada servicio, impulsado por las propiedades por
defecto del módulo:

```yaml
# Firefly observability defaults: Actuator exposure (overridable per service).
management:
  endpoints:
    web:
      exposure:
        include: health, info, metrics, prometheus
  endpoint:
    health:
      probes:
        enabled: true          # Kubernetes liveness/readiness probe groups
  prometheus:
    metrics:
      export:
        enabled: true
```

Así que, de fábrica, cada servicio de Lumen responde `/actuator/health` (con grupos
de probe de liveness y readiness de Kubernetes), `/actuator/info`,
`/actuator/metrics` y `/actuator/prometheus`. Los módulos del framework pueden
aportar su propio detalle de health extendiendo `FireflyHealthIndicator`, por ejemplo
un indicador de EDA que reporta los publicadores activos y una tasa de error, de modo
que el health de un servicio agrega los propios subsistemas del framework, no solo la
fuente de datos.

!!! note "Termino clave — liveness frente a readiness"
    Un probe de **liveness** responde a "¿está este proceso sano, o debería el
    orquestador reiniciarlo?". Un probe de **readiness** responde a "¿puede esta
    instancia recibir tráfico ahora mismo?": un servicio puede estar vivo pero no
    listo mientras calienta un pool de conexiones. Spring Boot expone ambos como
    grupos de probe de health; Firefly los activa por defecto para que un servicio de
    Lumen salga limpiamente de un balanceador de carga durante el arranque y el
    apagado en lugar de dejar caer peticiones.

## Por qué sobrevive la correlación: la pieza clave reactiva

Ahora la clave de bóveda: la capacidad que el preludio y los Capítulos 1 y 5 marcaron
como lo más valioso que hace Firefly en la pila reactiva, y la razón por la que cada
línea de log JSON anterior lleva de verdad el `traceId` *correcto*.

Recuerda el problema del Capítulo 5. Un `traceId` vive en un `ThreadLocal` (el MDC es
uno). En Java bloqueante eso está bien: un hilo sirve una petición de principio a
fin, así que cada línea de log de ese hilo lleva el identificador correcto. En la
pila reactiva la garantía se evapora: una sola petición salta a través de muchos
hilos a medida que cruza `flatMap`, `publishOn` y fronteras de scheduler, y el
`ThreadLocal` **no** la sigue. Si se deja en paz, tus logs reactivos vuelven en
blanco en el campo `traceId`, o peor, sellados con el identificador de una petición
*distinta*.

El arreglo es una línea, y ya la has visto antes:

```java
Hooks.enableAutomaticContextPropagation();
```

Lo que *no* has visto es dónde vive. No está en ningún código fuente de Lumen. Está
en el `ReactiveContextPropagationAutoConfiguration` del módulo de observabilidad, que
lo llama una vez en un `@PostConstruct` al arrancar:

```java
// From org.fireflyframework.observability.tracing
//   .ReactiveContextPropagationAutoConfiguration (illustrative).
@AutoConfiguration
@ConditionalOnClass({Hooks.class, ContextSnapshot.class})
@ConditionalOnProperty(prefix = "firefly.observability.context-propagation",
        name = "reactor-hooks-enabled", havingValue = "true", matchIfMissing = true)
public class ReactiveContextPropagationAutoConfiguration {

    @PostConstruct
    void enableAutomaticContextPropagation() {
        Hooks.enableAutomaticContextPropagation();
    }
}
```

Con ese hook activado, Reactor captura automáticamente los valores de `ThreadLocal`
registrados —el MDC, el contexto de OpenTelemetry, el tenant— dentro del `Context` de
Reactor de la suscripción, y **los restaura alrededor de cada operador, en cualquier
hilo que lo ejecute**, vía el puente `ContextSnapshot` de Micrometer. El contexto de
traza y de transacción viaja con la suscripción, no con el hilo. Así que cuando la
cadena de `flatMap` del core de originación de préstamos cruza de un hilo
`reactor-http-nio` a otro, el `traceId` lo sigue, y la línea de log JSON escrita en
lo profundo de la tubería lleva la correlación correcta, sin ningún `doOnEach`, sin
`contextWrite` manual y sin contabilidad de MDC en tu código.

Esta es la recompensa concreta de un hilo que ha recorrido todo el libro. El
`ExecutionContext` multi-tenant, el identificador de traza, el `X-Transaction-Id`:
todos ellos son valores respaldados por `ThreadLocal`, y todos sobreviven a los
operadores reactivos de Lumen por exactamente una razón: el módulo de observabilidad
activó el hook y registró sus accessors por ti. Es también por lo que esta
autoconfiguración es condicional y sobreescribible:
`firefly.observability.context-propagation.reactor-hooks-enabled` es `true` por
defecto (`matchIfMissing = true`), pero es una propiedad real que puedes leer,
auditar y (en el raro caso de que debas) desactivar.

!!! warning "Sin propagación de contexto, los logs reactivos mienten"
    Un identificador de correlación que no sobrevive a las fronteras de los operadores
    es peor que no tener identificador: adjunta silenciosamente la identidad de la
    petición *equivocada* a una línea de log, y una investigación de incidente
    persigue a un fantasma. Si alguna vez construyes código reactivo fuera de Firefly,
    activar `Hooks.enableAutomaticContextPropagation()` y registrar tus accessors de
    `ThreadLocal` no es opcional: es la línea entre producción trazable e intrazable.
    Dentro de Firefly, el starter de observabilidad ya la ha trazado por ti.

!!! spring "Equivalente en Spring"
    El mecanismo es la librería `context-propagation` de Micrometer más el hook de
    Reactor, disponible para cualquier aplicación Spring Boot 3 / WebFlux. En Spring a
    secas llamas tú mismo al hook y registras cada `ThreadLocalAccessor` (para el MDC,
    para el contexto de traza, para tu tenant) a mano, en cada servicio, y un servicio
    que se olvide hace log en blanco. El `ReactiveContextPropagationAutoConfiguration`
    de Firefly lo hace una vez, condicionado a que las clases correctas estén
    presentes, idénticamente en toda la flota.

## Dónde te enchufas

Casi todo en este capítulo es gratis y automático, pero hay dos costuras que son
tuyas para usar cuando un incidente o un dashboard lo exija.

**Tus propias métricas de negocio.** Para contar o cronometrar algo específico del
dominio —digamos, solicitudes de préstamo por encima de un umbral— extiende
`FireflyMetricsSupport` con el nombre de módulo de tu servicio y deja que la base lo
prefije. Heredas la convención del espacio de nombres `firefly.`, la null-safety y el
cacheado, y tu métrica se sitúa junto a la del framework en el mismo scrape de
Prometheus.

**Tu propio contexto de log.** Para añadir un campo a cada línea de log dentro de una
unidad de trabajo —un `applicantId`, por ejemplo— ponlo en el MDC cerca del borde y
deja que el codificador lo eleve, o escríbelo en el `Context` de Reactor y confía en
el hook de propagación para restaurarlo. Como el hook ya está activado, un valor que
coloques en el contexto sobrevive a los saltos de operador igual que lo hace el
`traceId`.

Lo que *no* deberías hacer es recurrir a la gestión manual del MDC dentro de una
cadena reactiva: el baile de MDC con `doOnEach`/`doFinally` que precede al hook de
propagación. Con la propagación automática de contexto activada, ese patrón es
obsoleto y propenso a errores; deja que el framework mueva el contexto por ti.

## Lo que has aprendido {.recap}

- Cada servicio de Lumen hereda una pila de observabilidad completa de
  `fireflyframework-observability`, arrastrada por cada starter de capa: **ningún
  código de observabilidad aparece en el código fuente del ejemplo**, y aun así los
  logs, las métricas, las trazas, el health y la correlación reactiva funcionan
  todos.
- Los logs son **JSON estructurado** vía un codificador de Logstash, con `traceId`,
  `spanId` y `X-Transaction-Id` promovidos a campos de primer nivel a partir de las
  claves compartidas de `MdcConstants`, y la PII se enmascara (Capítulo 6) antes de
  que se escriba.
- Las métricas del framework siguen una convención, **`firefly.{module}.{metric}`**,
  impuesta por `MetricNaming` y aplicada a través de la base null-safe
  `FireflyMetricsSupport`; los medidores de CQRS y EDA aterrizan bajo
  `firefly.cqrs.*` y `firefly.eda.*`, exportados a Prometheus.
- El trazado puentea a **OpenTelemetry** por defecto con propagación **W3C**,
  llevando `X-Transaction-Id` como baggage para que el identificador de log y el de
  traza permanezcan unidos; Actuator expone health (con probes de Kubernetes), info,
  metrics y un scrape de Prometheus.
- La pieza clave es **`Hooks.enableAutomaticContextPropagation()`**, llamada una vez
  por `ReactiveContextPropagationAutoConfiguration`, que es *por lo que* el contexto
  de traza y de tenant sobrevive a los saltos de hilo de Reactor, concluyendo el hilo
  de correlación que el libro ha llevado desde el Capítulo 1.

## Pruebalo tu mismo {.exercises}

1. **Lee la forma de tu propio log.** Arranca el servicio core (Capítulo 2), haz una
   petición y copia una línea de log JSON. Identifica los campos `traceId`, `spanId` y
   `X-Transaction-Id`, luego confirma que el `X-Transaction-Id` coincide con la
   cabecera de la respuesta HTTP. Acabas de hacer, a mano, lo que un operador hace
   durante un incidente.
2. **Encuentra los medidores del framework.** Haz `curl` al `/actuator/prometheus` del
   servicio core y grep por `firefly_`. Lista cada `firefly.{module}.{metric}` que
   encuentres y nombra qué capacidad de qué capítulo lo produjo.
3. **Traza un salto.** Con las capas de experiencia y de dominio en marcha, haz una
   llamada de BFF que se despliegue hacia un servicio aguas abajo, luego confirma que
   ambos servicios hacen log del *mismo* `traceId`. Ese identificador compartido es la
   propagación W3C haciendo su trabajo a través del salto HTTP.
4. **Demuestra que el hook importa.** Como experimento mental, fija
   `firefly.observability.context-propagation.reactor-hooks-enabled=false` en un
   perfil de pruebas y predice qué mostraría el campo `traceId` en una línea de log
   escrita después de un `publishOn`. (Respuesta: en blanco o incorrecto, que es
   exactamente el bug que el valor por defecto `true` previene.) Restaura el valor por
   defecto.
5. **Añade una métrica de negocio.** Esboza una clase `LoanMetrics` extendiendo
   `FireflyMetricsSupport` con nombre de módulo `"loan"` y un `counter("created")`.
   Nombra la métrica totalmente cualificada que emitiría, y confirma que se situaría
   bajo el mismo espacio de nombres `firefly.` que las propias del framework.

## Adonde ir ahora

Ahora puedes ver un servicio de Lumen en ejecución desde fuera: sus logs, sus
métricas, sus trazas, todo correlacionado. Esa visibilidad es la precondición para
todo lo que un operador hace a continuación: alertar sobre una métrica, seguir una
traza hasta un servicio lento aguas abajo, consultar un identificador de transacción a
través de las capas. Con la flota observable, los capítulos restantes se vuelven hacia
cómo ejecutarla: empaquetado, configuración y la forma operativa de un servicio
Firefly en producción.
