El capítulo 1 nombró cuatro capas — experiencia, dominio, core y **datos** — y prometió
que conoceríamos la última aquí. Es la única capa que el corte de originación de Lumen
Lending no construye realmente y, en lugar de disimularlo, este capítulo va a ser
honesto al respecto. El corte que has ido haciendo crecer lee y escribe solicitudes de
préstamo contra una base de datos que es suya. Es un *sistema de registro*: la capa core
en el puerto `8081`, respaldada por persistencia reactiva sencilla — Spring Data R2DBC a
través de `fireflyframework-r2dbc`, sobre H2 en memoria con migraciones de Flyway,
exactamente el stack que construiste en el capítulo 8. La capa de datos es otro animal —
no posee un esquema, sino que *obtiene* y *mejora* datos que se originan en otro lugar. En
una plataforma de préstamos real eso significa consultas a burós de crédito, controles de
KYC y AML, búsquedas de señales de fraude, normalización de direcciones y el rastro de
auditoría que registra de dónde provino cada hecho tomado prestado.

El corte de originación no tiene nada de eso. Donde una plataforma de producción llamaría
a un buró de crédito, el reactor registra un resultado de stub fabricado por él mismo para
que el ejemplo pueda arrancar sin sistema externo y sin Docker — exactamente la misma
honestidad que viste con la costura del SDK en el capítulo 10. Así que este capítulo enseña
la capa de datos de la única forma en que puede enseñarse con veracidad: recorriendo los
bloques de construcción de `starter-data` — el framework `DataEnricher`, el motor de
calidad de datos y el seguimiento de linaje — con fragmentos claramente **ilustrativos**, y
señalando con precisión dónde encajaría cada uno en Lumen si la plataforma creciera hasta
ese punto.

En este capítulo no hay listados de reactor literales ni un momento `mvn` de "Ejecútalo",
porque el corte de originación no ejercita la capa de datos. Cada bloque de código de abajo
es ilustrativo — un bloque cercado estándar, nunca una directiva `::: listing` — y
etiquetado como tal. Léelo como un mapa del territorio, no como un corte de la construcción.

!!! note "Término clave — la capa de datos (`starter-data`)"
    La **capa de datos** es el hogar del *enriquecimiento, la calidad de datos y el
    linaje* — el trabajo de traer datos externos o derivados a la plataforma y responder
    por ellos. Un servicio de la capa de datos no posee un esquema de negocio propio como
    sí hace el core; *enriquece* un sujeto (un solicitante, una dirección, una empresa)
    llamando a proveedores, puntúa el resultado contra reglas de calidad y registra de
    dónde provino cada hecho. Su starter es `fireflyframework-starter-data`. En Lumen se
    situaría detrás de la capa de dominio — la misma capa cuya `RegisterApplicationSaga`
    ves ejecutarse de principio a fin en el capítulo 18 — invocada desde un paso de saga
    cuando la originación necesita una decisión de crédito o una verificación de KYC,
    exactamente donde el flujo en vivo de hoy simplemente escribe la solicitud directamente
    en el core.

## Por qué la originación necesita una capa de datos (pero el corte no tiene una)

Rastrea una solicitud de préstamo real y darás con la capa de datos casi de inmediato.
Antes de que un motor de decisión pueda puntuar a un solicitante, *alguien* tiene que
obtener un informe de crédito, verificar documentos de identidad, cribar al solicitante
contra listas de sanciones y de PEP, y normalizar la dirección del domicilio a algo en lo
que el resto de la plataforma esté de acuerdo. Ninguno de esos hechos se origina en Lumen.
Provienen de proveedores externos — Experian, Equifax, un proveedor de KYC, un servicio de
listas de sanciones — cada uno con su propia API, latencia, coste y caída ocasional.

Podrías llamar a esos proveedores directamente desde un manejador de dominio. Hay equipos
que lo hacen, y se arrepienten: el manejador acumula lógica de reintentos para un buró, un
plan alternativo a un segundo buró cuando el primero está caído, una caché para que una
solicitud reenviada no pague por una segunda consulta, una comprobación de calidad para
rechazar una respuesta basura, y un registro de auditoría de qué buró respondió. Esa es la
descripción del trabajo de la capa de datos, y el propósito de `starter-data` es hacer de
ello una *capacidad* que configuras en lugar de fontanería que tejes a mano en cada
manejador.

El corte de originación se salta todo eso. En el capítulo 10 el manejador de comando llamó
a `client.createLoanApplication(...)` y devolvió un id; en ningún sitio enriqueció al
solicitante. Si lo hiciera, el sustituto honesto se parecería a la costura del SDK — un
puerto con un resultado escrito a mano — para que las pruebas sigan libres de broker:

```java
// Illustrative — the kind of stub the slice would use instead of a real bureau call.
// A self-recorded result, so the sample boots with no Experian, no network, no Docker.
public final class StubCreditBureauClient implements CreditBureauClient {
    @Override
    public Mono<CreditReport> pull(String applicantTaxId) {
        return Mono.just(new CreditReport(applicantTaxId, /* score */ 720, "STUB"));
    }
}
```

Ese stub sería *todo* lo que el corte llevaría. Todo lo demás en este capítulo — las
cadenas de fallback, los cortacircuitos por proveedor, las puertas de calidad, los
registros de linaje — es lo que `starter-data` proporciona para que la versión de
producción de esa única línea se convierta en una capacidad configurada en lugar de mil
líneas de código de resiliencia a medida.

!!! warning "Este capítulo describe una capacidad que el corte no ejecuta"
    Tenlo claro: la construcción en `samples/lumen-lending` **no** depende de
    `fireflyframework-starter-data`, y nada de lo de abajo está verificado por una prueba de
    Lumen. Trata cada fragmento como cómo-funciona y dónde-encaja. Cuando el capítulo dice
    "el enriquecedor hace fallback", lee "la capa de datos está *diseñada* para hacer
    fallback" — la prueba vive en las pruebas de módulo del propio framework, no en este
    ejemplo.

## El framework DataEnricher

El centro de la capa de datos es el enriquecimiento: tomar un sujeto escueto y
*completarlo* a partir de fuentes externas. Firefly lo modela como un contrato pequeño. Una
`EnricherOperation` es una unidad de enriquecimiento con nombre — "obtener un informe de
crédito", "cribar contra sanciones", "normalizar esta dirección" — y el `DataEnricher` es
el orquestador que ejecuta una operación contra una cadena de proveedores, con resiliencia
y caché envueltas alrededor de cada llamada.

Conceptualmente, una operación es una interfaz muy parecida a un manejador CQRS: una
entrada tipada, una salida tipada y un único método reactivo.

```java
// Illustrative — the shape of an enrichment operation.
public interface EnricherOperation<I, O> {

    /** Stable name used for routing, metrics, caching, and lineage. */
    String name();

    /** Runs the enrichment against one provider; returns the enriched value. */
    Mono<O> enrich(I input, EnrichmentContext context);
}
```

Una operación de buró de crédito implementa esa interfaz una vez por *proveedor*. Escribes
un `ExperianCreditOperation` y un `EquifaxCreditOperation`, cada uno hablando la API de su
propio proveedor pero ambos produciendo el mismo tipo de salida `CreditReport`. El tipo de
salida es el contrato del que depende el resto de la plataforma; los proveedores detrás de
él son intercambiables.

```java
// Illustrative — one provider's implementation of the credit-pull operation.
@EnricherComponent(name = "creditReport", provider = "experian", order = 1)
public class ExperianCreditOperation
        implements EnricherOperation<ApplicantRef, CreditReport> {

    private final ExperianClient experian;

    @Override
    public String name() { return "creditReport"; }

    @Override
    public Mono<CreditReport> enrich(ApplicantRef input, EnrichmentContext ctx) {
        return experian.report(input.taxId())
                .map(CreditReportMapper::fromExperian);
    }
}
```

Como los manejadores CQRS del capítulo 10, la operación se descubre mediante una anotación
— aquí un estereotipo como `@EnricherComponent` — así que nunca la registras a mano. El
`name` agrupa los proveedores que producen la misma salida (`"creditReport"`), y el `order`
los clasifica dentro de ese grupo. Esa clasificación es lo que convierte un conjunto de
operaciones en una *cadena de fallback*.

!!! note "Término clave — EnricherOperation frente a DataEnricher"
    Una **`EnricherOperation<I, O>`** es la manera en que *un proveedor* enriquece un sujeto
    — una hoja. El **`DataEnricher`** es el orquestador: dado un nombre lógico de
    enriquecimiento como `"creditReport"`, reúne todas las operaciones registradas bajo ese
    nombre, las ordena y las ejecuta como una cadena de fallback con resiliencia y caché
    alrededor de cada una. Tú escribes operaciones; tú *llamas* al enriquecedor. La relación
    tiene deliberadamente la misma forma que la de `CommandHandler` con `CommandBus`.

### Cadenas de fallback entre proveedores

Un único buró es un único punto de fallo. Los burós tienen caídas, límites de tasa y
huecos de cobertura — y una solicitud de préstamo que no puede puntuarse porque Experian
está caído es ingreso perdido por una razón evitable. La respuesta de la capa de datos es
una **cadena de fallback**: registra dos o más proveedores bajo el mismo nombre de
enriquecimiento, ordenados por preferencia, y deja que el `DataEnricher` los pruebe por
turnos hasta que uno tenga éxito.

```java
// Illustrative — calling the enricher; the chain is configured, not coded here.
public Mono<CreditReport> creditReportFor(ApplicantRef applicant) {
    return dataEnricher.enrich("creditReport", applicant);
    // Tries experian (order 1); on failure, falls through to equifax (order 2);
    // on failure of both, the chain's terminal policy decides: error or a default.
}
```

Quien llama nombra el enriquecimiento — `"creditReport"` — no el proveedor, exactamente
igual que quien llama a CQRS nombra el mensaje y no el manejador. El `DataEnricher`
resuelve la cadena: primero el proveedor con `order = 1`, y si falla (o agota el tiempo, o
su circuito está abierto), la *siguiente* operación en la cadena. Añadir Equifax como
respaldo es una nueva clase `@EnricherComponent(order = 2)` y cero cambios en quien llama.
Promover Equifax a primario es un intercambio de `order`. El comportamiento terminal de la
cadena — fallar de forma rotunda frente a devolver un valor por defecto configurado — es
una política que defines, no un `if` que escribes.

```java
// Illustrative — the second provider in the same chain. Same name, higher order.
@EnricherComponent(name = "creditReport", provider = "equifax", order = 2)
public class EquifaxCreditOperation
        implements EnricherOperation<ApplicantRef, CreditReport> {
    // ... same CreditReport output type; different vendor API behind it.
}
```

Este es el mismo instinto hexagonal que el capítulo 1 describió para los proveedores de
identidad y de contenido: la plataforma depende de la *capacidad* (`"creditReport"`), y los
proveedores se sitúan detrás de un puerto, intercambiables por configuración. La diferencia
es que el enriquecimiento espera usar *más de un* proveedor a la vez — no un único
adaptador elegido, sino una cadena clasificada que recorre ante un fallo.

!!! spring "Equivalente en Spring"
    Una cadena de fallback es algo que podrías ensamblar en Spring puro con una
    `List<EnricherOperation>` ordenada e inyectada por tipo y una cascada reactiva de
    `onErrorResume` — Spring inyectará los beans en secuencia de `@Order`, y el
    `onErrorResume` de Reactor expresa "prueba el siguiente". `starter-data` es ese patrón,
    ya construido: descubrimiento por anotación, ordenación por atributo y la cascada
    generada por ti, de modo que cada enriquecimiento de la flota hace fallback de la misma
    manera en lugar de que cada equipo vuelva a derivar la escalera de `onErrorResume`.

### Resiliencia por proveedor con Resilience4j

Hacer fallback solo es seguro si un proveedor que falla *falla rápido*. Si Experian agota
el tiempo a treinta segundos por llamada, recorrer la cadena hasta Equifax tras cada
timeout hace que cada consulta de crédito sea catastróficamente lenta. Así que la capa de
datos envuelve cada llamada de proveedor en sus propios decoradores de Resilience4j — un
cortacircuitos, un timeout, un reintento y un mamparo (bulkhead) — con clave por proveedor,
no compartidos a lo largo de la cadena.

El uso de la clave importa. Un cortacircuitos compartido se dispararía con la caída de
Experian y luego *también* bloquearía la llamada sana a Equifax. Los cortacircuitos por
proveedor los aíslan: cuando el cortacircuitos de Experian se abre, el enriquecedor salta
directamente a Equifax sin esperar una llamada condenada, y el cortacircuitos de Experian
se entreabre más tarde para sondear la recuperación — todo mientras Equifax sirvió tráfico
sin interrupción.

```yaml
# Illustrative — per-provider resilience, tuned by configuration, not by code.
firefly:
  data:
    enrichers:
      creditReport:
        providers:
          experian:
            order: 1
            resilience:
              timeout: 3s
              retry: { max-attempts: 2, backoff: 200ms }
              circuit-breaker: { failure-rate-threshold: 50, wait-duration-in-open-state: 30s }
          equifax:
            order: 2
            resilience:
              timeout: 5s
              circuit-breaker: { failure-rate-threshold: 50, wait-duration-in-open-state: 30s }
```

Como los decoradores se configuran en lugar de codificarse, la postura de resiliencia de
toda la capa de datos es visible en un solo lugar y se ajusta sin redesplegar lógica. Este
es el mismo Resilience4j que el `ServiceClient` resiliente del capítulo 16 envuelve
alrededor de sus llamadas; la capa de datos simplemente lo aplica *por proveedor dentro de
una cadena*, que es la granularidad que el enriquecimiento necesita — un cortacircuitos por
buró, no uno compartido para toda la capacidad de consulta de crédito.

!!! warning "No compartas un cortacircuitos entre proveedores de una cadena"
    El valor entero de una cadena de fallback es que la caída de un proveedor encamina el
    tráfico a otro. Un cortacircuitos compartido a lo largo de la cadena lo anula — se abre
    con los fallos del primer proveedor y bloquea también el fallback. Pon clave a los
    cortacircuitos (y a los mamparos) por **proveedor**, de modo que un cortacircuitos
    abierto en el primario sea exactamente la señal que hace que el enriquecedor eche mano
    del respaldo.

## Caché de enriquecimiento y seguimiento de costes

Los datos externos cuestan dinero y tiempo. Una consulta a un buró de crédito se factura
por consulta y conlleva un viaje de ida y vuelta por la red; un control de KYC puede
tarificarse por comprobación. Si un cliente reenvía una solicitud, o dos partes de la
plataforma piden el informe del mismo solicitante en cuestión de minutos, pagar dos veces
es desperdicio — y, para algunos productos de buró, volver a consultar puede incluso
*afectar a la puntuación del solicitante*. Por tanto, la capa de datos cachea los
resultados de enriquecimiento, con clave por el nombre del enriquecimiento más el sujeto,
con un TTL que defines por enriquecimiento.

```java
// Illustrative — an operation that opts its results into caching with a TTL.
@EnricherComponent(
        name = "creditReport",
        provider = "experian",
        order = 1,
        cacheable = true,
        cacheTtl = "15m")
public class ExperianCreditOperation
        implements EnricherOperation<ApplicantRef, CreditReport> {
    // a repeated pull for the same applicant inside 15 minutes is served from cache
}
```

La caché monta sobre el mismo `CacheAdapter` agnóstico de proveedor que Firefly usa en
otros lugares (capítulo 20), de modo que el almacén de respaldo — Caffeine en proceso como
L1, un Redis o Hazelcast distribuido como L2 detrás de él — es una elección de
configuración, no un cambio de código. Un enriquecimiento cacheado nunca toca al proveedor,
así que nunca dispara un cortacircuitos, nunca incurre en una tarifa y devuelve en
microsegundos.

Emparejado con la caché está el **seguimiento de costes**. Como cada llamada de proveedor
fluye a través del `DataEnricher`, el framework está posicionado para contarla y ponerle
precio. La capa de datos registra, por enriquecimiento y por proveedor, cuántas llamadas en
vivo se hicieron, cuántas se sirvieron desde caché y — donde aportes un coste unitario —
cuál fue el gasto. Eso convierte "¿por qué es tan alta nuestra factura de buró este mes?"
de una investigación forense en un endpoint de Actuator y una métrica.

```java
// Illustrative — enrichment metadata the tier can surface alongside the result.
// callsMade=1, cacheHits=0, provider="experian", estimatedCost=0.85 USD
EnrichmentResult<CreditReport> result = dataEnricher.enrichDetailed("creditReport", applicant).block();
result.value();          // the CreditReport
result.provider();       // which provider in the chain actually answered
result.fromCache();      // whether this was a billable live call
result.estimatedCost();  // priced from the configured per-call cost
```

!!! note "Término clave — seguimiento de costes de enriquecimiento"
    El **seguimiento de costes** es la contabilidad que la capa de datos hace del gasto en
    datos externos. Cada enriquecimiento lleva metadatos — qué proveedor respondió, si fue
    un acierto de caché y un coste estimado a partir de un precio por llamada configurado —
    de modo que la plataforma puede atribuir el gasto a un proveedor, un producto o un
    inquilino, y un acierto de caché aparece visiblemente como una llamada que *no* pagaste.
    Existe porque, en los préstamos, los datos que compras suelen ser el mayor coste
    variable por solicitud.

!!! spring "Equivalente en Spring"
    Donde el Spring de toda la vida echa mano de `@Cacheable` y un `CacheManager`
    bloqueante, la caché de Firefly (capítulo 20) es un `CacheAdapter` reactivo — Caffeine
    como L1 integrado, un L2 distribuido a una dependencia de distancia — que nunca bloquea
    un hilo de Reactor para obtener. Lo que `starter-data` añade encima es la *estrategia de
    clave* específica del enriquecimiento (nombre del enriquecimiento más sujeto), el TTL
    por enriquecimiento y los metadatos de coste hilvanados a través del resultado, nada de
    lo cual te da un `@Cacheable` desnudo.

## El motor de calidad de datos

Una llamada de proveedor exitosa no es lo mismo que una respuesta *utilizable*. Un buró
puede devolver un informe con una puntuación que falta, una fecha de consulta caduca, o un
nombre que no coincide con el solicitante registrado; un proveedor de KYC puede devolver
una coincidencia de baja confianza. Dejar pasar eso contamina cada decisión posterior. La
capa de datos pone un **motor de calidad de datos basado en reglas** entre el
enriquecimiento en bruto y el resto de la plataforma: un conjunto de reglas con nombre que
puntúan y validan un resultado antes de confiar en él.

Una regla de calidad es, de nuevo, una pequeña unidad tipada — un predicado sobre el valor
enriquecido que arroja un aprobado/fallo (o una puntuación ponderada) y una razón.

```java
// Illustrative — a data-quality rule over a credit report.
@DataQualityRule(name = "creditScorePresent", dimension = COMPLETENESS, weight = 1.0)
public class CreditScorePresentRule implements QualityRule<CreditReport> {

    @Override
    public RuleResult evaluate(CreditReport report) {
        return report.score() != null
                ? RuleResult.pass()
                : RuleResult.fail("credit score is missing");
    }
}
```

Las reglas se agrupan por *dimensión* — completitud, validez, frescura, consistencia,
exactitud — el vocabulario estándar de la calidad de datos, de modo que la puntuación de un
resultado no es un número opaco sino un desglose sobre el que puedes razonar: "completo y
válido, pero caduco". El motor ejecuta cada regla registrada para un tipo, agrega los
resultados ponderados y produce un `QualityReport`.

### Puertas de calidad

Una puntuación solo es útil si algo *actúa* sobre ella. Una **puerta de calidad** es un
umbral que el enriquecimiento debe superar para ser aceptado: por debajo de él, el resultado
se rechaza o se encamina a revisión manual en lugar de alimentar al motor de decisión. La
puerta es donde la calidad de datos deja de ser un cuadro de mando y se convierte en un
control.

```java
// Illustrative — gating an enrichment on its quality score before it is used.
public Mono<CreditReport> trustedCreditReport(ApplicantRef applicant) {
    return dataEnricher.enrich("creditReport", applicant)
            .flatMap(report -> qualityEngine.assess(report)
                    .flatMap(quality -> quality.score() >= 0.80
                            ? Mono.just(report)
                            : Mono.error(new QualityGateException("creditReport", quality))));
}
```

En un Lumen que poseyera esta capa, esa puerta es precisamente lo que protege la saga del
capítulo 18 — la `RegisterApplicationSaga` que el stack en ejecución realmente ejecuta hoy.
Su paso raíz `registerLoanApplication` escribe la solicitud en el sistema de registro del
core (la línea de log real `[orchestration] step.success ...
stepId=registerLoanApplication`), y un paso de consulta de crédito condicionado a la
calidad se situaría *antes* de que se puntúe una decisión: un informe que no superase la
puerta no avanzaría la saga sino que dispararía su compensación — el compensador
`removeLoanApplication` del paso raíz, que hoy ya llama al `DELETE
/api/v1/loan-applications/{id}` del core — o lo derivaría a un humano, en lugar de puntuar
una decisión sobre datos en los que la plataforma no confía. El umbral de la puerta y qué
dimensiones son obligatorias son configuración, de modo que riesgo y cumplimiento pueden
apretar el listón sin un cambio de código.

!!! note "Término clave — puerta de calidad"
    Una **puerta de calidad** es una puntuación de calidad mínima (opcionalmente por
    dimensión) que un resultado de enriquecimiento debe cumplir para ser aceptado aguas
    abajo. Los resultados por debajo de la puerta se rechazan, se sustituyen por un valor
    por defecto o se escalan a revisión manual. La puerta es la diferencia entre *medir* la
    calidad de datos y *imponerla*: es el punto donde una respuesta de buró de baja
    confianza deja de ser un número en un gráfico y empieza a bloquear una decisión de
    préstamo que de otro modo se tomaría sobre datos malos.

!!! spring "Equivalente en Spring"
    El motor de calidad son beans de Spring puros — cada regla es un `@Component`
    descubierto por tipo, agregado por un bean del motor — sin magia de framework que no
    pudieras escribir tú mismo. El valor que `starter-data` añade es el *vocabulario*
    (dimensiones, pesos, puertas) y el cableado, de modo que cada servicio expresa la
    calidad de datos de la misma manera en lugar de inventar un método de validación a
    medida por enriquecimiento.

## Linaje de datos enchufable

Cuando un regulador, un auditor o un cliente en disputa pregunta "¿de dónde salió esta
puntuación de crédito, y cuándo?", la plataforma debe responder con precisión: qué
proveedor, qué versión de qué cadena de reglas, a qué hora, contra qué entrada, con qué
puntuación de calidad. Eso es el **linaje de datos** — la procedencia registrada de cada
hecho enriquecido — y en una plataforma de préstamos no es opcional, es una obligación de
cumplimiento.

Como cada enriquecimiento fluye a través del `DataEnricher`, la capa es el lugar natural
para capturar el linaje automáticamente. Cada enriquecimiento emite un registro de linaje:
el sujeto, el nombre del enriquecimiento, el proveedor que respondió, si fue un acierto de
caché, el resultado de calidad, una marca de tiempo y el id de correlación del
`ExecutionContext` del capítulo 10 — de modo que el historial completo de obtención de
datos de una sola solicitud pueda reconstruirse a partir del rastro de auditoría.

```java
// Illustrative — the lineage record the tier emits per enrichment.
// subject=applicant:7b1f..., enrichment="creditReport", provider="equifax",
// fromCache=false, qualityScore=0.91, at=2026-06-17T10:14:32Z, correlationId=...
public record LineageRecord(
        String subjectRef,
        String enrichment,
        String provider,
        boolean fromCache,
        double qualityScore,
        Instant at,
        String correlationId) { }
```

Es crucial que la captura de linaje sea **enchufable**. Adónde *van* los registros es un
puerto: un grabador en memoria para las pruebas, un appender de logs para el desarrollo, un
topic de Kafka o una base de datos de auditoría en producción, un sumidero compatible con
OpenLineage si alimentas un catálogo de datos. Dependes de una interfaz `LineageRecorder` y
eliges el adaptador por configuración, el mismo movimiento hexagonal que cualquier otra
costura de proveedor en Firefly.

```java
// Illustrative — the lineage sink is a port; the adapter is chosen by config.
public interface LineageRecorder {
    Mono<Void> record(LineageRecord record);
}
```

Esa capacidad de enchufado es lo que permite que el *mismo* código de enriquecimiento se
ejecute con un grabador no-op en una prueba unitaria y con un sumidero de auditoría durable
en producción — exactamente la sustitución prueba-frente-producción que viste con la
costura del SDK, aplicada a la procedencia.

!!! note "Término clave — linaje de datos"
    El **linaje de datos** es la procedencia registrada de un hecho derivado u obtenido: de
    dónde provino, cuándo, mediante qué proveedor y reglas, y con qué calidad. En la capa de
    datos cada enriquecimiento emite automáticamente un `LineageRecord` a un sumidero
    `LineageRecorder` enchufable. El linaje responde a la pregunta de auditoría "justifica
    este número" y, como pone clave al mismo id de correlación que fluye por el resto de la
    plataforma, la procedencia de un hecho se une con la petición que lo necesitó.

!!! spring "Equivalente en Spring"
    No hay un starter de Spring Boot para "linaje de datos" — esto es genuinamente una
    capacidad de Firefly y no una de Spring recableada. Lo que *sí* es Spring puro es el
    mecanismo: el grabador es un puerto (una interfaz) con adaptadores seleccionados por
    `@ConditionalOnProperty` — el mismo movimiento de bean condicional que la
    `LiveLoanOriginationClientConfig` del reactor ya usa para intercambiar el cliente core
    en vivo solo cuando `firefly.lumen.core.loan-origination.base-path` está definida, y los
    puertos de identidad y contenido del capítulo 1. Firefly aporta el *modelo* de linaje y
    la emisión automática; Spring aporta el cableado de beans que intercambia el sumidero.

## Cómo se componen las piezas

Da un paso atrás y la capa de datos es una única tubería, y cada sección de arriba es una
etapa de ella. Un manejador de dominio pide un enriquecimiento por nombre. El `DataEnricher`
recorre la **cadena de fallback**, cada llamada de proveedor envuelta en **Resilience4j por
proveedor** y cortocircuitada por la **caché**, registrando el **coste** sobre la marcha. El
resultado ganador ejecuta el **motor de calidad de datos**, y una **puerta de calidad**
decide si se confía en él. Pase lo que pase, se emite un **registro de linaje** a un
sumidero enchufable. La capa de dominio recibe de vuelta o bien un hecho de confianza, con
precio y procedencia rastreada — o bien un error limpio contra el que puede compensar.

Esa tubería entera es lo que el corte de originación reemplaza con una sola línea de stub.
El stub es honesto y suficiente para enseñar la originación; la tubería es lo que hace que
la versión de producción de esa línea sea segura, barata, observable y auditable. Conocer
la forma de la capa es lo que te permite reconocer, el día en que Lumen necesite una
consulta de crédito real, que la respuesta es un servicio `starter-data` detrás de la capa
de dominio — no más código de resiliencia en un manejador.

## Lo que has aprendido {.recap}

- La **capa de datos** (`starter-data`) es el hogar en la plataforma del *enriquecimiento,
  la calidad de datos y el linaje* — obteniendo datos externos como informes de crédito y
  resultados de KYC y respondiendo por ellos — y el corte de originación de Lumen
  deliberadamente **no** la construye, sustituyéndola por un stub fabricado por él mismo, de
  la misma forma que el capítulo 10 sustituyó la costura del SDK.
- El **`DataEnricher`** ejecuta proveedores `EnricherOperation` con nombre como una
  **cadena de fallback** clasificada, de modo que una consulta de crédito prueba un buró y
  cae al siguiente, con quien llama nombrando el *enriquecimiento* y nunca el proveedor.
- Cada llamada de proveedor se envuelve en **Resilience4j por proveedor** (cortacircuitos,
  timeout, reintento, mamparo) — con clave por proveedor, de modo que un cortacircuitos
  abierto en el primario es la señal que encamina al respaldo — y se cortocircuita por
  **caché con seguimiento de costes**, de modo que las consultas repetidas son gratis y el
  gasto de buró es atribuible.
- Un **motor de calidad de datos basado en reglas** puntúa los resultados a lo largo de
  dimensiones, y una **puerta de calidad** impone un umbral de confianza antes de que un
  resultado alcance el camino de decisión — el punto donde medir la calidad se convierte en
  controlarla.
- El **linaje de datos enchufable** emite un registro de procedencia por enriquecimiento a
  un sumidero `LineageRecorder` basado en puerto, respondiendo a la pregunta de auditoría
  "¿de dónde salió este hecho?" con un adaptador no-op en las pruebas y un sumidero durable
  en producción.
- El hogar natural de la capa de datos es **detrás de la saga de dominio**: el stack en vivo
  hoy ejecuta `RegisterApplicationSaga` (exp `8080` → dominio `8082` → core `8081`,
  `[orchestration] completed name=RegisterApplicationSaga ... success=true`) y escribe
  directamente en el core; una consulta de crédito real se convertiría en un paso de saga
  más, condicionado por una puerta, ahí — no en código nuevo en un manejador.
- Todo en este capítulo es **ilustrativo**: ni corte de reactor literal, ni ejecución
  `mvn`, porque el ejemplo no depende de `starter-data`. La prueba vive en las pruebas de
  módulo del propio framework; aquí es un mapa de dónde encaja la capa.

## Pruébalo tú mismo {.exercises}

1. **Esboza un enriquecedor de buró con una cadena de fallback.** Sobre papel, diseña un
   enriquecimiento `"creditReport"` para Lumen con dos proveedores — uno primario y uno de
   respaldo. Escribe las dos firmas de clase `@EnricherComponent` (name, provider, order) y
   el único sitio de llamada `dataEnricher.enrich("creditReport", applicant)` en un manejador
   de dominio. Marca exactamente qué líneas cambian cuando (a) añades un tercer buró y (b)
   promueves el respaldo a primario — y confirma que el sitio de llamada no está entre ellas.
2. **Coloca la resiliencia.** Para los dos proveedores de arriba, escribe el YAML de
   `firefly.data.enrichers.creditReport` con un timeout y un cortacircuitos por proveedor.
   Luego explica, en una sola frase, qué se rompería si subieras el cortacircuitos al nivel
   de la cadena en lugar de por proveedor.
3. **Añade una puerta de calidad a la saga de originación.** Vuelve a leer la
   `RegisterApplicationSaga` del capítulo 18 y su paso raíz `registerLoanApplication`
   (compensado por `removeLoanApplication`, que borra del core sobre HTTP). Describe dónde se
   situaría una puerta `qualityEngine.assess(...)` sobre el informe de crédito en esa cadena
   reactiva, y qué debería hacer la saga — proceder, disparar la compensación
   `removeLoanApplication` o escalar a revisión manual — cuando la puerta falla. ¿Qué
   propiedad `firefly.*` poseería riesgo?
4. **Pon precio a un reenvío.** Un cliente envía la misma solicitud dos veces en diez
   minutos, y la consulta de crédito es `cacheable = true, cacheTtl = "15m"` a 0,85 $ por
   llamada en vivo. Usando los metadatos de `EnrichmentResult` (`fromCache`,
   `estimatedCost`), indica qué reportan los números de seguimiento de costes para el segundo
   envío y por qué.
5. **Elige un sumidero de linaje.** El puerto `LineageRecorder` tiene adaptadores para
   memoria, log, Kafka y una base de datos de auditoría. Elige el adaptador que cablearías en
   una prueba unitaria y el que usarías para producción, y explica cómo intercambiarlos deja
   el código de enriquecimiento — y el `LineageRecord` emitido — completamente inalterado.

## Adónde ir ahora

Esta es la última de las cuatro capas y, con ella, tienes el mapa entero: experiencia
compone, dominio orquesta, core registra, datos obtiene y responde. Los capítulos restantes
vuelven al corte en ejecución para atar las capas entre sí — cableando el SDK generado que
reemplaza al puerto del capítulo 10, y levantando el flujo de originación completo de
principio a fin. Cuando llegues a un punto en que Lumen necesite una decisión de crédito
real en lugar de un id de stub, ya conoces la forma del servicio que la responde: un
enriquecedor `starter-data`, detrás de una cadena de fallback, condicionado por una puerta
de calidad, registrando su linaje — añadido como una capa, no atornillado a un manejador.
