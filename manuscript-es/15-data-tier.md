El capítulo 1 nombró cuatro capas — experiencia, dominio, núcleo y **datos** — y prometió
que conoceríamos la última aquí. Es la única capa que el segmento de originación de
Lumen Lending no construye realmente, y en lugar de disimularlo, este capítulo
va a ser honesto al respecto. El segmento que has ido haciendo crecer lee y escribe
solicitudes de préstamo contra una base de datos que es suya. Es un *sistema de registro*: la capa de núcleo,
respaldada por persistencia reactiva sencilla. La capa de datos es otro animal — no
posee un esquema, sino que *obtiene* y *mejora* datos que se originan en otra parte. En una
plataforma de préstamos real eso significa consultas a burós de crédito, comprobaciones de KYC y AML,
búsquedas de señales de fraude, normalización de direcciones y el rastro de auditoría que registra de dónde
provino cada dato tomado prestado.

El segmento de originación no tiene nada de eso. Donde una plataforma de producción llamaría a un
buró de crédito, el reactor registra un resultado de relleno hecho a mano para que la muestra pueda arrancar
sin ningún sistema externo y sin Docker — exactamente la misma honestidad que viste con la
costura del SDK en el capítulo 10. Así que este capítulo enseña la capa de datos del único modo en que puede
enseñarse con veracidad: recorriendo los bloques de construcción de `starter-data` — el
framework `DataEnricher`, el motor de calidad de datos y el seguimiento de linaje — con
fragmentos claramente **ilustrativos**, y señalando con precisión dónde encajaría cada uno
en Lumen si la plataforma creciera hasta ese punto.

En este capítulo no hay listados de reactor literales ni un momento `mvn` de "Ejecútalo",
porque el segmento de originación no ejercita la capa de datos. Cada bloque de código
de abajo es ilustrativo — un bloque cercado estándar, nunca una directiva `::: listing` —
y etiquetado como tal. Léelo como un mapa del territorio, no como un fragmento
de la construcción.

!!! note "Término clave — la capa de datos (`starter-data`)"
    La **capa de datos** es el hogar del *enriquecimiento, la calidad de datos y el linaje* — el
    trabajo de traer datos externos o derivados a la plataforma y dar fe de ellos.
    Un servicio de la capa de datos no posee un esquema de negocio propio como lo hace el núcleo;
    *enriquece* un sujeto (un solicitante, una dirección, una empresa) llamando a
    proveedores, puntúa el resultado contra reglas de calidad y registra de dónde vino cada
    dato. Su starter es `fireflyframework-starter-data`. En Lumen se situaría
    detrás de la capa de dominio, invocada cuando la originación necesita una decisión de crédito o una
    aprobación de KYC.

## Por qué la originación necesita una capa de datos (pero el segmento no tiene una)

Rastrea una solicitud de préstamo real y das con la capa de datos casi de inmediato. Antes de que un
motor de decisión pueda puntuar a un solicitante, *alguien* tiene que obtener un informe de crédito,
verificar documentos de identidad, comprobar al solicitante contra listas de sanciones y PEP, y
normalizar la dirección del domicilio a algo en lo que el resto de la plataforma esté de acuerdo. Ninguno de
esos datos se origina en Lumen. Vienen de proveedores externos — Experian,
Equifax, un proveedor de KYC, un servicio de listas de sanciones — cada uno con su propia API, latencia,
coste y caída ocasional.

Podrías llamar a esos proveedores directamente desde un manejador de dominio. Los equipos lo hacen, y se
arrepienten: el manejador acumula lógica de reintentos para un buró, un repliegue a un segundo
buró cuando el primero está caído, una caché para que una solicitud reenviada no pague por
una segunda consulta, una comprobación de calidad para rechazar una respuesta basura, y un registro de auditoría de
qué buró respondió. Esa es la descripción del puesto de la capa de datos, y el sentido de
`starter-data` es convertirla en una *capacidad* que configuras en lugar de fontanería que
montas a mano en cada manejador.

El segmento de originación se salta todo eso. En el capítulo 10 el manejador de comandos llamó a
`client.createLoanApplication(...)` y devolvió un id; en ningún sitio enriqueció al
solicitante. Si lo hiciera, el sustituto honesto se parecería a la costura del SDK — un puerto con
un resultado escrito a mano — para que las pruebas sigan libres de broker:

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

Ese stub sería *todo* lo que el segmento llevaría. Todo lo demás en este capítulo — las
cadenas de repliegue, los cortocircuitos por proveedor, las puertas de calidad, los registros de linaje —
es lo que proporciona `starter-data` para que la versión de producción de esa única
línea se convierta en una capacidad configurada en lugar de mil líneas de código de
resiliencia hecho a medida.

!!! warning "Este capítulo describe una capacidad que el segmento no ejecuta"
    Tengámoslo claro: la construcción en `samples/lumen-lending` **no** depende de
    `fireflyframework-starter-data`, y nada de lo de abajo está verificado por una prueba de Lumen.
    Trata cada fragmento como un cómo-funciona y un dónde-encaja. Cuando el capítulo dice
    "el enriquecedor se repliega", lee "la capa de datos está *diseñada* para replegarse" — la
    prueba vive en las pruebas de módulo del propio framework, no en esta muestra.

## El framework DataEnricher

El centro de la capa de datos es el enriquecimiento: tomar un sujeto escueto y *completarlo*
a partir de fuentes externas. Firefly lo modela como un pequeño contrato. Una `EnricherOperation`
es una unidad de enriquecimiento con nombre — "obtener un informe de crédito", "comprobar contra sanciones",
"normalizar esta dirección" — y el `DataEnricher` es el orquestador que ejecuta una
operación contra una cadena de proveedores, con resiliencia y caché envolviendo
cada llamada.

Conceptualmente una operación es una interfaz muy parecida a un manejador de CQRS: una entrada tipada, una
salida tipada y un único método reactivo.

```java
// Illustrative — the shape of an enrichment operation.
public interface EnricherOperation<I, O> {

    /** Stable name used for routing, metrics, caching, and lineage. */
    String name();

    /** Runs the enrichment against one provider; returns the enriched value. */
    Mono<O> enrich(I input, EnrichmentContext context);
}
```

Una operación de buró de crédito implementa esa interfaz una vez por *proveedor*. Escribes una
`ExperianCreditOperation` y una `EquifaxCreditOperation`, cada una hablando la API de su propio
proveedor pero ambas produciendo el mismo tipo de salida `CreditReport`. El tipo de salida
es el contrato del que depende el resto de la plataforma; los proveedores que hay detrás son
intercambiables.

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

Como los manejadores de CQRS del capítulo 10, la operación se descubre mediante una anotación —
aquí un estereotipo como `@EnricherComponent` — para que nunca la registres a mano. El
`name` agrupa proveedores que producen la misma salida (`"creditReport"`), y el
`order` los clasifica dentro de ese grupo. Esa clasificación es lo que convierte un conjunto de operaciones
en una *cadena de repliegue*.

!!! note "Término clave — EnricherOperation frente a DataEnricher"
    Una **`EnricherOperation<I, O>`** es la forma en que *un proveedor* enriquece un sujeto — una
    hoja. El **`DataEnricher`** es el orquestador: dado un nombre lógico de enriquecimiento
    como `"creditReport"`, reúne cada operación registrada bajo ese
    nombre, las ordena y las ejecuta como una cadena de repliegue con resiliencia y caché
    alrededor de cada una. Tú escribes operaciones; tú *llamas* al enriquecedor. La relación tiene
    deliberadamente la misma forma que `CommandHandler` respecto a `CommandBus`.

### Cadenas de repliegue de proveedores

Un único buró es un único punto de fallo. Los burós tienen caídas, límites de tasa y
huecos de cobertura — y una solicitud de préstamo que no puede puntuarse porque Experian está
caído son ingresos perdidos por una razón evitable. La respuesta de la capa de datos es una **cadena de
repliegue**: registra dos o más proveedores bajo el mismo nombre de enriquecimiento, ordenados por
preferencia, y deja que el `DataEnricher` los pruebe por turnos hasta que uno tenga éxito.

```java
// Illustrative — calling the enricher; the chain is configured, not coded here.
public Mono<CreditReport> creditReportFor(ApplicantRef applicant) {
    return dataEnricher.enrich("creditReport", applicant);
    // Tries experian (order 1); on failure, falls through to equifax (order 2);
    // on failure of both, the chain's terminal policy decides: error or a default.
}
```

El que llama nombra el enriquecimiento — `"creditReport"` — no el proveedor, exactamente igual que un
que llama de CQRS nombra el mensaje y no el manejador. El `DataEnricher` resuelve la
cadena: primero el proveedor `order = 1`, y si da error (o agota el tiempo, o su
circuito está abierto), la *siguiente* operación de la cadena. Añadir Equifax como respaldo es una
nueva clase `@EnricherComponent(order = 2)` y cero cambios en el que llama. Promover
Equifax a primario es un intercambio de `order`. El comportamiento terminal de la cadena — fallar duro
frente a devolver un valor por defecto configurado — es una política que estableces, no un `if` que escribes.

```java
// Illustrative — the second provider in the same chain. Same name, higher order.
@EnricherComponent(name = "creditReport", provider = "equifax", order = 2)
public class EquifaxCreditOperation
        implements EnricherOperation<ApplicantRef, CreditReport> {
    // ... same CreditReport output type; different vendor API behind it.
}
```

Este es el mismo instinto hexagonal que el capítulo 1 describió para los proveedores de identidad y
contenido: la plataforma depende de la *capacidad* (`"creditReport"`), y los
proveedores se sitúan detrás de un puerto, intercambiables por configuración. La diferencia es que
el enriquecimiento espera usar *más de un* proveedor a la vez — no un adaptador elegido,
sino una cadena clasificada que recorre cuando hay fallos.

!!! spring "Equivalente en Spring"
    Una cadena de repliegue es algo que podrías ensamblar en Spring puro con una
    `List<EnricherOperation>` ordenada inyectada por tipo y una cascada reactiva de
    `onErrorResume` — Spring inyectará los beans en secuencia `@Order`, y el
    `onErrorResume` de Reactor expresa "prueba el siguiente". `starter-data` es ese patrón,
    preconstruido: descubrimiento por anotación, ordenación por atributo y la cascada
    generada por ti, para que cada enriquecimiento de la flota se repliegue de la misma forma
    en lugar de que cada equipo vuelva a derivar la escalera de `onErrorResume`.

### Resiliencia por proveedor con Resilience4j

Replegarse solo es seguro si un proveedor que falla *falla rápido*. Si Experian está
agotando el tiempo a treinta segundos por llamada, recorrer la cadena hasta Equifax tras cada
agotamiento hace que cada consulta de crédito sea catastróficamente lenta. Así que la capa de datos envuelve cada
llamada a proveedor en sus propios decoradores de Resilience4j — un cortocircuito, un tiempo de espera, un
reintento y un mamparo — indexados por proveedor, no compartidos a lo largo de la cadena.

La indexación importa. Un cortocircuito compartido se dispararía con la caída de Experian y
luego *también* bloquearía la llamada sana a Equifax. Los cortocircuitos por proveedor los aíslan: cuando
el cortocircuito de Experian se abre, el enriquecedor salta directamente a Equifax sin esperar
a una llamada condenada, y el cortocircuito de Experian se entreabre más tarde para sondear la recuperación — todo
mientras Equifax servía tráfico sin interrupción.

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

Como los decoradores están configurados en lugar de codificados, la postura de resiliencia de
toda la capa de datos es visible en un solo lugar y se ajusta sin redesplegar lógica.
Este es el mismo Resilience4j que usan los clientes resilientes del SDK del capítulo 14; la capa de
datos simplemente lo aplica *por proveedor dentro de una cadena*, que es la granularidad
que el enriquecimiento necesita.

!!! warning "No compartas un cortocircuito entre proveedores de una cadena"
    Todo el valor de una cadena de repliegue es que la caída de un proveedor encamina el tráfico
    a otro. Un cortocircuito compartido a lo largo de la cadena anula eso — se abre con
    los fallos del primer proveedor y bloquea también el repliegue. Indexa los cortocircuitos (y
    los mamparos) por **proveedor**, para que un cortocircuito abierto en el primario sea exactamente la
    señal que hace que el enriquecedor recurra al respaldo.

## Caché de enriquecimiento y seguimiento de costes

Los datos externos cuestan dinero y tiempo. Una consulta a un buró de crédito se factura por consulta y
requiere un ida y vuelta por la red; una comprobación de KYC puede medirse por comprobación. Si un cliente
reenvía una solicitud, o dos partes de la plataforma piden el informe del mismo solicitante
en cuestión de minutos, pagar dos veces es desperdicio — y, para algunos productos de buró,
volver a consultar puede incluso *afectar a la puntuación del solicitante*. Por tanto, la capa de datos cachea los
resultados de enriquecimiento, indexados por el nombre del enriquecimiento más el sujeto, con un TTL que estableces
por enriquecimiento.

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

La caché va sobre la misma abstracción de caché agnóstica del proveedor que Firefly usa
en otros sitios (capítulo 7), de modo que el almacén subyacente — Caffeine en proceso, Redis entre
instancias — es una elección de configuración, no un cambio de código. Un enriquecimiento cacheado nunca
toca al proveedor, así que nunca dispara un cortocircuito, nunca incurre en una tarifa y se devuelve en
microsegundos.

Emparejado con la caché va el **seguimiento de costes**. Como cada llamada a proveedor fluye a través del
`DataEnricher`, el framework está bien situado para contarla y ponerle precio. La capa de datos
registra, por enriquecimiento y por proveedor, cuántas llamadas en vivo se hicieron, cuántas
se sirvieron desde caché y — allí donde proporcionas un coste unitario — cuál fue el gasto. Eso
convierte "¿por qué es tan alta nuestra factura de buró este mes?" de una investigación forense en un
endpoint de Actuator y una métrica.

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
    El **seguimiento de costes** es la contabilidad que la capa de datos hace del gasto en datos externos. Cada
    enriquecimiento lleva metadatos — qué proveedor respondió, si fue un acierto de
    caché y un coste estimado a partir de un precio por llamada configurado — para que la plataforma pueda
    atribuir el gasto a un proveedor, un producto o un inquilino, y un acierto de caché visiblemente
    aparezca como una llamada que *no* pagaste. Existe porque, en préstamos, los datos
    que compras son a menudo el mayor coste variable por solicitud.

!!! spring "Equivalente en Spring"
    La caché aquí es por debajo la abstracción de caché de Spring, la misma que usa `@Cacheable`
    — de modo que un `CacheManager` de Redis o Caffeine que ya ejecutas es el almacén
    subyacente. Lo que `starter-data` añade es la *estrategia de clave* (nombre de enriquecimiento más
    sujeto), el TTL por enriquecimiento y los metadatos de coste hilvanados a través del
    resultado, nada de lo cual te da un `@Cacheable` pelado.

## El motor de calidad de datos

Una llamada exitosa a un proveedor no es lo mismo que una respuesta *utilizable*. Un buró puede devolver
un informe con una puntuación faltante, una fecha de consulta caducada o un nombre que no coincide con el
solicitante en ficha; un proveedor de KYC puede devolver una coincidencia de baja confianza. Dejar pasar eso
contamina cada decisión aguas abajo. La capa de datos pone un **motor de calidad de datos
basado en reglas** entre el enriquecimiento crudo y el resto de la plataforma: un conjunto
de reglas con nombre que puntúan y validan un resultado antes de que se confíe en él.

Una regla de calidad es, de nuevo, una pequeña unidad tipada — un predicado sobre el valor enriquecido
que produce un aprobado/suspenso (o una puntuación ponderada) y un motivo.

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
exactitud — el vocabulario estándar de la calidad de datos, de modo que la puntuación de un resultado no es un
único número opaco sino un desglose sobre el que puedes razonar: "completo y válido, pero caducado".
El motor ejecuta cada regla registrada para un tipo, agrega los resultados ponderados
y produce un `QualityReport`.

### Puertas de calidad

Una puntuación solo es útil si algo *actúa* sobre ella. Una **puerta de calidad** es un umbral
que el enriquecimiento debe superar para ser aceptado: por debajo de él, el resultado se rechaza o se encamina
a revisión manual en lugar de alimentar al motor de decisión. La puerta es donde la calidad de
datos deja de ser un panel y se convierte en un control.

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
capítulo 18: el paso `registerLoanApplication` no procedería con un informe de crédito que
suspendiera la puerta — compensaría, o lo delegaría a un humano, en lugar de
puntuar una decisión sobre datos en los que la plataforma no confía. El umbral de la puerta y qué
dimensiones son obligatorias son configuración, de modo que riesgo y cumplimiento pueden apretar el
listón sin un cambio de código.

!!! note "Término clave — puerta de calidad"
    Una **puerta de calidad** es una puntuación mínima de calidad (opcionalmente por dimensión) que un
    resultado de enriquecimiento debe cumplir para ser aceptado aguas abajo. Los resultados por debajo de la puerta se
    rechazan, se sustituyen por un valor por defecto o se escalan a revisión manual. La puerta es la diferencia
    entre *medir* la calidad de datos y *imponerla*: es el punto donde una
    respuesta de buró de baja confianza deja de ser un número en un gráfico y empieza a
    bloquear una decisión de préstamo que de otro modo se tomaría sobre datos malos.

!!! spring "Equivalente en Spring"
    El motor de calidad son beans de Spring puros — cada regla es un `@Component`
    descubierto por tipo, agregado por un bean de motor — sin magia de framework que no
    pudieras escribir tú mismo. El valor que `starter-data` añade es el *vocabulario*
    (dimensiones, pesos, puertas) y el cableado, de modo que cada servicio exprese la calidad de
    datos de la misma forma en lugar de inventar un método de validación a medida por
    enriquecimiento.

## Linaje de datos enchufable

Cuando un regulador, un auditor o un cliente en disputa pregunta "¿de dónde vino esta puntuación
de crédito, y cuándo?", la plataforma debe responder con precisión: qué proveedor,
qué versión de qué cadena de reglas, en qué momento, contra qué entrada, con qué
puntuación de calidad. Eso es el **linaje de datos** — la procedencia registrada de cada
dato enriquecido — y en una plataforma de préstamos no es opcional, es una obligación de cumplimiento.

Como cada enriquecimiento fluye a través del `DataEnricher`, la capa es el lugar natural
para capturar el linaje automáticamente. Cada enriquecimiento emite un registro de linaje: el
sujeto, el nombre del enriquecimiento, el proveedor que respondió, si fue un acierto de caché,
el resultado de calidad, una marca de tiempo y el id de correlación del `ExecutionContext`
del capítulo 10 — de modo que todo el historial de obtención de datos de una única solicitud
pueda reconstruirse a partir del rastro de auditoría.

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

Crucialmente, la captura de linaje es **enchufable**. A dónde *van* los registros es un puerto: un
grabador en memoria para pruebas, un appender de log para desarrollo, un topic de Kafka o una
base de datos de auditoría en producción, un sumidero compatible con OpenLineage si alimentas un catálogo
de datos. Dependes de una interfaz `LineageRecorder` y eliges el adaptador por
configuración, el mismo movimiento hexagonal que cualquier otra costura de proveedor en Firefly.

```java
// Illustrative — the lineage sink is a port; the adapter is chosen by config.
public interface LineageRecorder {
    Mono<Void> record(LineageRecord record);
}
```

Esa capacidad de enchufe es lo que permite que el *mismo* código de enriquecimiento se ejecute con un grabador no-op
en una prueba unitaria y un sumidero de auditoría duradero en producción — exactamente la
sustitución prueba-frente-a-producción que viste con la costura del SDK, aplicada a la procedencia.

!!! note "Término clave — linaje de datos"
    El **linaje de datos** es la procedencia registrada de un dato derivado u obtenido: de dónde
    vino, cuándo, por qué proveedor y reglas, y con qué calidad. En la capa de
    datos cada enriquecimiento emite automáticamente un `LineageRecord` a un sumidero
    `LineageRecorder` enchufable. El linaje responde a la pregunta de auditoría "justifica este número",
    y como se indexa por el mismo id de correlación que fluye por el resto de la
    plataforma, la procedencia de un dato se enlaza con la petición que lo necesitó.

!!! spring "Equivalente en Spring"
    No hay un starter de Spring Boot para "linaje de datos" — esto es genuinamente una capacidad
    de Firefly en lugar de una de Spring recableada. Lo que *sí* es Spring puro es el
    mecanismo: el grabador es un puerto (una interfaz) con adaptadores seleccionados por
    `@ConditionalOnProperty`, exactamente como los puertos de identidad y contenido del
    capítulo 1. Firefly aporta el *modelo* de linaje y la emisión automática;
    Spring aporta el cableado de beans que intercambia el sumidero.

## Cómo se componen las piezas

Da un paso atrás y la capa de datos es un único pipeline, y cada sección de arriba es una etapa de él.
Un manejador de dominio pide un enriquecimiento por nombre. El `DataEnricher` recorre la
**cadena de repliegue**, cada llamada a proveedor envuelta en **Resilience4j por proveedor** y
cortocircuitada por la **caché**, registrando el **coste** a medida que avanza. El resultado ganador
ejecuta el **motor de calidad de datos**, y una **puerta de calidad** decide si es de
confianza. Pase lo que pase, se emite un **registro de linaje** a un sumidero enchufable. La
capa de dominio recibe de vuelta o bien un dato de confianza, con precio y con procedencia rastreada — o un
error limpio contra el que puede compensar.

Todo ese pipeline es lo que el segmento de originación reemplaza con una única línea de relleno.
El stub es honesto y suficiente para enseñar la originación; el pipeline es lo que hace que
la versión de producción de esa línea sea segura, barata, observable y auditable. Conocer
la forma de la capa es lo que te permite reconocer, el día que Lumen necesite una consulta de crédito
real, que la respuesta es un servicio `starter-data` detrás de la capa de dominio — no más
código de resiliencia en un manejador.

## Lo que has aprendido {.recap}

- La **capa de datos** (`starter-data`) es el hogar de la plataforma para el *enriquecimiento, la calidad
  de datos y el linaje* — obtener y dar fe de datos externos como informes de crédito
  y resultados de KYC — y el segmento de originación de Lumen deliberadamente **no** lo
  construye, sustituyéndolo por un stub hecho a mano del modo en que el capítulo 10 sustituyó la costura del SDK.
- El **`DataEnricher`** ejecuta proveedores **`EnricherOperation`** con nombre como una **cadena de
  repliegue** clasificada, de modo que una consulta de crédito prueba un buró y se repliega al siguiente,
  con el que llama nombrando el *enriquecimiento* y nunca el proveedor.
- Cada llamada a proveedor se envuelve en **Resilience4j por proveedor** (cortocircuito,
  tiempo de espera, reintento, mamparo) — indexado por proveedor de modo que un cortocircuito abierto en el primario es
  la señal que encamina al respaldo — y se cortocircuita por **caché con seguimiento de
  costes**, de modo que las consultas repetidas son gratis y el gasto en burós es atribuible.
- Un **motor de calidad de datos basado en reglas** puntúa resultados a través de dimensiones, y una
  **puerta de calidad** impone un umbral de confianza antes de que un resultado alcance la ruta de
  decisión — el punto donde medir la calidad se convierte en controlarla.
- El **linaje de datos enchufable** emite un registro de procedencia por enriquecimiento a un sumidero
  `LineageRecorder` basado en puertos, respondiendo a la pregunta de auditoría "¿de dónde vino este
  dato?" con un adaptador no-op en pruebas y un sumidero duradero en producción.
- Todo en este capítulo es **ilustrativo**: ningún segmento de reactor literal, ningún
  `mvn` ejecutado, porque la muestra no depende de `starter-data`. La prueba vive en
  las pruebas de módulo del propio framework; aquí es un mapa de dónde encaja la capa.

## Pruébalo tú mismo {.exercises}

1. **Esboza un enriquecedor de buró con una cadena de repliegue.** Sobre el papel, diseña un
   enriquecimiento `"creditReport"` para Lumen con dos proveedores — uno primario y uno de
   respaldo. Escribe las dos firmas de clase `@EnricherComponent` (name, provider,
   order) y el único punto de llamada `dataEnricher.enrich("creditReport", applicant)` en un
   manejador de dominio. Marca exactamente qué líneas cambian cuando (a) añades un tercer buró y
   (b) promueves el respaldo a primario — y confirma que el punto de llamada no está entre ellas.
2. **Coloca la resiliencia.** Para los dos proveedores de arriba, escribe el YAML de
   `firefly.data.enrichers.creditReport` con un tiempo de espera y un cortocircuito por proveedor. Después explica, en una frase,
   qué se rompería si subieras el cortocircuito al nivel de la cadena en lugar de por proveedor.
3. **Añade una puerta de calidad a la saga de originación.** Vuelve a leer el paso
   `registerLoanApplication` del capítulo 18. Describe dónde se situaría una puerta `qualityEngine.assess(...)`
   sobre el informe de crédito en esa cadena reactiva, y qué debería hacer la saga
   — proceder, compensar o escalar — cuando la puerta falla. ¿Qué propiedad `firefly.*`
   poseería riesgo?
4. **Pon precio a un reenvío.** Un cliente envía la misma solicitud dos veces en diez
   minutos, y la consulta de crédito es `cacheable = true, cacheTtl = "15m"` a 0,85 $ por
   llamada en vivo. Usando los metadatos de `EnrichmentResult` (`fromCache`, `estimatedCost`),
   indica qué reportan los números de seguimiento de costes para el segundo envío y por qué.
5. **Elige un sumidero de linaje.** El puerto `LineageRecorder` tiene adaptadores para en memoria,
   log, Kafka y una base de datos de auditoría. Escoge el adaptador que cablearías en una prueba unitaria
   y el de producción, y explica cómo intercambiarlos deja el código de enriquecimiento — y el
   `LineageRecord` emitido — completamente sin cambios.

## Adónde ir ahora

Esta es la última de las cuatro capas, y con ella tienes todo el mapa: experiencia
compone, dominio orquesta, núcleo registra, datos obtiene y da fe. Los capítulos restantes
vuelven al segmento en marcha para unir las capas — cableando el SDK generado
que reemplaza el puerto del capítulo 10, y levantando el flujo completo de originación de extremo
a extremo. Cuando llegues a un punto donde Lumen necesite una decisión de crédito real en lugar de un
id de relleno, ya conoces la forma del servicio que la responde: un enriquecedor `starter-data`,
detrás de una cadena de repliegue, con puerta de calidad, registrando su linaje — añadido como
una capa, no atornillado a un manejador.
