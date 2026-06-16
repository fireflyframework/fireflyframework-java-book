Empecemos con una confesión clara: Lumen Lending no usa event sourcing, y este
capítulo es opcional. La porción de originación que has construido persiste el estado
de la forma habitual — una fila `loan_application` actualizada en su sitio (Capítulo
8), un agregado rico que es dueño de su ciclo de vida (Capítulo 9), comandos y
consultas sobre un bus (Capítulo 10) y una saga que compensa cuando un paso falla
(Capítulo 18). Eso es CRUD clásico con orquestación, y para la originación es
exactamente lo correcto. Nada en este capítulo cambia ese código, y no hay ningún test
compañero que ejecutar.

Entonces, ¿por qué dedicarle un capítulo? Porque Firefly incorpora una capacidad de
event sourcing, y una plataforma de préstamos real tiene al menos un lugar que la
desea: el **libro mayor**. Cuando la pregunta no es "¿cuál es el saldo ahora?" sino
"demuestra cómo llegó el saldo hasta aquí, asiento a asiento, y déjame reconstruirlo en
cualquier instante pasado", la costumbre de CRUD de sobrescribir el valor anterior es el
valor por defecto equivocado. El event sourcing conserva los *eventos* — los hechos que
ocurrieron — como fuente de verdad, y deriva el estado reproduciéndolos. Un auditor
nunca puede preguntarle a CRUD "¿qué decía esta fila el martes pasado?"; el event
sourcing responde a eso por construcción.

Este capítulo enseña el modelo de event sourcing del framework apoyándose en un
ejemplo paralelo claramente etiquetado como *ilustrativo*: un agregado `Ledger` de
cuentas y asientos. Nada de su código vive en el reactor — cada fragmento aquí es un
bloque cercado estándar, no un listado verificado — así que léelo como un mapa de dónde
encaja cada pieza, no como una porción que puedas ejecutar con `mvn test`. Cuando
recurras a él, lo construirás tal como muestran estos bocetos.

!!! warning "Capítulo ilustrativo — sin listados verificados"
    A diferencia del resto del libro, el código de aquí **no** es una porción literal
    del reactor compañero y no lo comprueba la integración continua. La porción de
    originación es CRUD más una saga; el event sourcing es una capacidad ausente que se
    enseña como funciona-así. Los nombres de clases y anotaciones coinciden con el
    módulo de event sourcing de Firefly, pero el ejemplo `Ledger` es tuyo para
    construirlo, no de Lumen para enviarlo.

## Cuándo recurrir al event sourcing (y cuándo no)

La decisión no es estética; se deriva de para qué sirven los datos. Usa la lista de
abajo como un triaje, y luego confía en el valor por defecto que implica.

Recurre al **event sourcing** cuando:

- La **historia es el producto.** Un libro mayor, un saldo, una posición, un registro
  de auditoría — la secuencia de cambios tiene peso regulatorio o de negocio, y "el
  valor actual" es una comodidad derivada, no la verdad.
- Debes **reconstruir estado pasado.** Disputas, reformulaciones y auditorías preguntan
  todas "¿qué aspecto tenía esto en el momento *T*?" Reproducir los eventos hasta un
  punto lo responde con exactitud; una fila sobrescrita no puede.
- Necesitas **analítica temporal o hipótesis what-if.** Como cada cambio se conserva,
  puedes construir nuevos modelos de lectura de forma retroactiva — proyectar una
  métrica que no pensaste capturar, sobre eventos que ya ocurrieron.
- **Escritores concurrentes compiten** por una misma entidad y quieres detección de
  conflictos optimista, basada en versión, en lugar de bloqueos pesimistas.

Quédate con **CRUD** (el valor por defecto de la originación) cuando:

- La entidad tiene un **ciclo de vida pero no una historia significativa** — una
  solicitud de préstamo pasa de `DRAFT` a `APPROVED`, y el estado *actual* es lo que
  quiere todo el que llama. La columna `updated_at` y un evento publicado en la
  transición son más que suficientes.
- El equipo es **pequeño y el dominio es joven.** El event sourcing añade maquinaria
  real — un almacén de eventos, proyecciones, snapshots, upcasters — y pagas ese coste
  por adelantado.
- No necesitas **responder preguntas históricas** que una tabla de auditoría normal no
  pueda ya responder.

!!! note "Término clave — event sourcing"
    El **event sourcing** almacena el estado de una entidad como una secuencia de solo
    anexar (append-only) de **eventos de dominio** inmutables en lugar de como una fila
    mutable. El estado actual *no* se almacena; se calcula reproduciendo los eventos a
    través del agregado. Los eventos son el sistema de registro; cualquier tabla que
    puedas consultar es una **proyección** derivada que puedes reconstruir a voluntad a
    partir de los eventos.

!!! spring "Equivalente en Spring"
    No existe un "starter de event sourcing" en Spring puro. En una aplicación Spring
    Boot estándar tendrías que ensamblar esto tú mismo — una tabla de eventos, un
    serializador JSON, una comprobación de bloqueo optimista, un trabajo de proyección,
    una estrategia de snapshots — y cada equipo lo haría un poco diferente. Ese es
    precisamente el impuesto empresarial del Capítulo 1. La contribución de Firefly aquí
    es el *mismo* ensamblaje, cableado una sola vez, tras un `EventStore` reactivo y una
    clase base `AggregateRoot`, de modo que los servicios con event sourcing de la flota
    coincidan en las partes difíciles.

## El agregado como un fold sobre eventos

En el Capítulo 9, `LoanApplication` cambiaba su propio estado directamente: `approve()`
ponía `status` en `APPROVED`. Un agregado con event sourcing funciona de otra manera.
Un método de comando no muta el estado — *decide qué evento ocurrió* y lo emite. El
estado solo cambia entonces como efecto secundario de **aplicar** ese evento, en un
manejador `on(...)`. El estado actual del agregado es, literalmente, el fold por la
izquierda de todos sus eventos a través de esos manejadores.

Aquí está la cuenta del agregado `Ledger` como boceto. Fíjate en las dos mitades: el
método de comando (`deposit`) valida y lanza un evento; el manejador `on(...)` es el
*único* código que toca un campo.

```java
// ILLUSTRATIVE — not in the reactor.
public class Account extends AggregateRoot<String> {

    private String accountId;
    private long balanceMinorUnits;   // current state, derived from events

    // --- command: decide, then raise an event (no field is set here) ---
    public void deposit(long amount, String reference) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Deposit must be positive");
        }
        // raise() appends the event to the aggregate's uncommitted changes
        // and immediately routes it through the matching on(...) handler.
        raise(new FundsDeposited(accountId, amount, reference));
    }

    public void withdraw(long amount, String reference) {
        if (amount > balanceMinorUnits) {
            throw new InsufficientFundsException(accountId, amount, balanceMinorUnits);
        }
        raise(new FundsWithdrawn(accountId, amount, reference));
    }

    // --- on(...) handlers: the ONLY place state mutates ---
    @EventSourcingHandler
    public void on(AccountOpened event) {
        this.accountId = event.accountId();
        this.balanceMinorUnits = 0L;
    }

    @EventSourcingHandler
    public void on(FundsDeposited event) {
        this.balanceMinorUnits += event.amount();
    }

    @EventSourcingHandler
    public void on(FundsWithdrawn event) {
        this.balanceMinorUnits -= event.amount();
    }
}
```

La disciplina es estricta y conviene interiorizarla: **las invariantes viven en el
comando, el estado vive en el manejador `on(...)`.** `withdraw` rechaza un descubierto
*antes* de lanzar un evento, porque un evento es un hecho que ya ocurrió y un hecho no
puede des-ocurrir. El manejador `on(FundsWithdrawn)` nunca revalida — confía en que
cualquier evento del stream era legal cuando se lanzó. Esa separación es lo que hace
seguro el replay: para reconstruir una cuenta a partir de su historia, el framework
construye una `Account` en blanco y alimenta cada evento almacenado a través de
`on(...)` en orden, sin que se ejecute nada de lógica de comando.

!!! note "Término clave — despacho del manejador `on(...)`"
    Un **manejador `on(...)`** (aquí anotado `@EventSourcingHandler`) aplica un tipo de
    evento al estado en memoria del agregado. `AggregateRoot` despacha cada evento al
    manejador cuyo parámetro coincide con el tipo del evento. Durante la operación
    normal un manejador se ejecuta una vez, justo después de `raise(...)`; durante la
    reconstrucción se ejecuta una vez por evento histórico. Como los manejadores solo
    asignan campos y nunca validan, reproducir un millón de eventos es determinista y
    sin efectos secundarios.

!!! warning "Nunca pongas una guarda en un manejador `on(...)`"
    Es tentador volver a comprobar la regla de descubierto dentro de
    `on(FundsWithdrawn)`. No lo hagas. La validación pertenece al comando, que se
    ejecuta una vez contra el estado vivo. Una guarda en el manejador se ejecuta de
    nuevo en *cada replay* — y el día que endurezcas la regla, cada cuenta histórica que
    en su momento fue legal fallará al cargar. El único trabajo del manejador es plegar
    un evento conocido-como-válido al estado.

## Los eventos de dominio como fuente de verdad

Los eventos son la clave de todo, así que reciben un trato de primera clase: cada uno es
un record inmutable, lleva todo lo necesario para reconstruirlo y está marcado para que
el framework pueda serializarlo, versionarlo y enrutarlo.

```java
// ILLUSTRATIVE — not in the reactor.
@DomainEvent(type = "ledger.funds-deposited", revision = 1)
public record FundsDeposited(
        String accountId,
        long amount,
        String reference) {
}
```

Dos propiedades hacen duradero a un record así. Primero, es **inmutable** — un `record`
sin setters — porque un hecho almacenado nunca debe cambiar. Segundo, la anotación
`@DomainEvent` le da un *tipo lógico* estable (`ledger.funds-deposited`) y una
*revisión*. El tipo lógico desacopla la identidad del evento del nombre de su clase
Java, de modo que puedes refactorizar el paquete o renombrar la clase sin dejar
huérfanos años de eventos almacenados; el almacén lee y escribe la cadena, no el nombre
de clase totalmente cualificado. El número de `revision` es lo que hace tratable la
evolución del esquema más adelante en este capítulo.

!!! note "Término clave — `@DomainEvent`"
    `@DomainEvent` marca un record como un hecho serializable y versionado en la
    historia de un agregado. Su `type` es el identificador estable que se persiste junto
    a cada fila de evento; su `revision` registra qué versión de esquema lo produjo.
    Juntos permiten que el almacén de eventos deserialice un evento escrito hace años en
    lo que el código actual espera — siempre que aportes un upcaster para cualquier
    salto de revisión.

## El EventStore reactivo y la concurrencia optimista

El agregado produce eventos; algo debe anexarlos de forma duradera y volverlos a leer.
Ese algo es el `EventStore` — un puerto reactivo, con un adaptador R2DBC,
`R2dbcEventStore`, que persiste eventos en una base de datos relacional sin bloquear
nunca el bucle de eventos. Su superficie es pequeña y enteramente `Mono`/`Flux`,
exactamente el modelo reactivo del Capítulo 5.

```java
// ILLUSTRATIVE — the shape of the port, not in the reactor.
public interface EventStore {

    /** Append new events for an aggregate, asserting its current version. */
    Mono<Void> append(String aggregateId,
                      long expectedVersion,
                      List<DomainEventEnvelope> events);

    /** Stream an aggregate's events in order, for reconstruction. */
    Flux<DomainEventEnvelope> readStream(String aggregateId);
}
```

El parámetro que soporta el peso es `expectedVersion`. Cada agregado lleva una
**versión** — el número de eventos en su stream — y cada anexado afirma la versión sobre
la que esperaba escribir. El almacén comprueba que la versión almacenada siga
coincidiendo y la incrementa *de forma atómica* al anexar. Si dos escritores cargaron la
misma `Account` en la versión 7, ambos calcularon una retirada y ambos intentaron
anexar "el 8.º evento", solo el primero lo consigue; el `append` del segundo falla
porque el stream ya está en la versión 8. Eso es **concurrencia optimista**, impuesta en
el almacén de eventos en lugar de con un bloqueo de base de datos.

Un repositorio lo ata todo. Cargar pliega el stream en un agregado nuevo; guardar anexa
los eventos no confirmados en la versión a la que se cargó el agregado.

```java
// ILLUSTRATIVE — not in the reactor.
public Mono<Account> load(String accountId) {
    return eventStore.readStream(accountId)
            .reduce(new Account(), (account, envelope) -> {
                account.replay(envelope.event());   // routes through on(...)
                return account;
            });
}

public Mono<Void> save(Account account) {
    return eventStore.append(
            account.getId(),
            account.getBaseVersion(),               // version when loaded
            account.getUncommittedEvents())         // events raised since
        .doOnSuccess(v -> account.markCommitted());
}
```

Un `append` fallido aflora como un `onError` que manejas a la manera reactiva —
típicamente `retryWhen` con una recarga, de modo que el perdedor de una carrera recarga
en la versión 8, vuelve a decidir su retirada contra el saldo ahora actual y anexa como
versión 9. La invariante del agregado (sin descubierto) se vuelve a comprobar en esa
recarga, que es exactamente por lo que quieres la guarda en el comando y no en el
manejador.

!!! note "Término clave — concurrencia optimista mediante versionado de agregados"
    El stream de cada agregado tiene una **versión** monótonamente creciente igual a su
    número de eventos. Un anexado lleva la versión que leyó; el almacén confirma solo si
    esa sigue siendo la más reciente, y rechaza la escritura en caso contrario. Ninguna
    fila se bloquea entre la lectura y la escritura — los conflictos se *detectan* en la
    confirmación, no se *previenen* bloqueando — lo que mantiene no bloqueante la tubería
    reactiva y deja que la contienda honesta reintente con limpieza.

!!! warning "Un almacén de eventos necesita un contrato de serialización, no objetos Java en crudo"
    Los eventos sobreviven al código que los escribió. El almacén persiste cada evento
    como su `type` de `@DomainEvent` más un payload serializado (JSON), nunca un blob
    serializado por Java y referenciado por nombre de clase. Si te saltas el tipo lógico
    y te apoyas en el nombre de clase totalmente cualificado, el primer renombrado de
    paquete vuelve ilegibles años de historia. Trata el esquema del evento como un
    contrato publicado desde el primer día.

## Proyecciones y checkpoints

Reproducir cada evento para responder "¿cuál es el saldo?" es correcto pero no es como
sirves una consulta a escala. Construyes una **proyección**: una tabla optimizada para
lectura — por ejemplo `account_balance(account_id, balance, updated_at)` — mantenida al
día consumiendo el stream de eventos y aplicando cada evento a la tabla. El agregado es
el modelo de escritura; la proyección es el modelo de lectura, y están deliberadamente
separados (esta es la división CQRS del Capítulo 10, ahora con los eventos como
costura).

Un `ProjectionService` consume eventos y mantiene un **checkpoint** — la posición en el
stream global de eventos hasta la que ha procesado — de modo que al reiniciar reanuda
exactamente donde lo dejó en lugar de reprocesar la historia o saltarse eventos.

```java
// ILLUSTRATIVE — not in the reactor.
@Component
public class AccountBalanceProjection {

    @ProjectionHandler                       // invoked per event, in order
    public Mono<Void> on(FundsDeposited event, ProjectionContext ctx) {
        return balanceTable.add(event.accountId(), event.amount())
                .then(ctx.checkpoint());     // advance the checkpoint atomically
    }

    @ProjectionHandler
    public Mono<Void> on(FundsWithdrawn event, ProjectionContext ctx) {
        return balanceTable.subtract(event.accountId(), event.amount())
                .then(ctx.checkpoint());
    }
}
```

Dos propiedades de los checkpoints se ganan su sitio. Primero, **reanudabilidad**: una
proyección que se cae tras el evento 4.000.000 reinicia y le pide al almacén los eventos
posteriores a su checkpoint, no desde cero. Segundo, **reconstruibilidad**: pon el
checkpoint de vuelta al inicio y el mismo código reconstruye la tabla desde cero — que
es como añades un modelo de lectura completamente nuevo meses después, sobre eventos que
ya ocurrieron, o reparas una proyección corrompida por un bug. Como los eventos son la
fuente de verdad, una proyección es siempre desechable y siempre reconstruible.

!!! note "Término clave — proyección y checkpoint"
    Una **proyección** es un modelo de lectura derivado, construido aplicando eventos a
    un almacén con forma de consulta; no contiene verdad propia y puede descartarse y
    reconstruirse. Un **checkpoint** es la posición del stream persistida hasta la que
    una proyección ha consumido, avanzada de forma atómica con cada evento aplicado para
    que el procesamiento sea reanudable y, en efecto, exactamente-una-vez. Muchas
    proyecciones pueden consumir el mismo stream con checkpoints independientes.

!!! spring "Equivalente en Spring"
    En Spring puro montarías esto a mano como un sondeador programado o un consumidor de
    Kafka que rastrea un offset en una tabla auxiliar, y te equivocarías sutilmente al
    menos una vez con el "aplicar-y-avanzar" atómico. El `ProjectionService` de Firefly
    convierte el checkpoint en un concepto transaccional de primera clase, de modo que
    los modelos de lectura de la flota comparten un único mecanismo reanudable y
    reconstruible en lugar de que cada servicio improvise una tabla de offsets.

## El outbox transaccional

Aquí está el fallo que atormenta a los sistemas dirigidos por eventos ingenuos: anexas
el evento al almacén *y* quieres publicarlo en Kafka para que otros servicios
reaccionen. Haz esas dos cosas como operaciones separadas y una caída entre ellas o
bien pierde la publicación (evento almacenado, nunca anunciado) o la duplica
(publicado, y luego la escritura en el almacén revierte). El **outbox transaccional**
cierra esa brecha.

La idea es simple y el framework la cablea: en la *misma transacción de base de datos*
que anexa el evento al almacén, insertas una fila en una tabla `outbox`. La confirmación
es atómica — o aterrizan tanto el evento como su fila de outbox, o ninguno de los dos.
Un **relay** separado lee entonces el outbox y publica al broker, marcando cada fila
como enviada. Si el relay se cae a medio camino, vuelve a publicar al reiniciar; los
consumidores aguas abajo desduplican por ID de evento. Obtienes entrega
*al-menos-una-vez* sin eventos perdidos, sin una transacción distribuida entre la base
de datos y el broker.

```text
   append() transaction
   ┌──────────────────────────────┐
   │  INSERT event   (event_store)│   ── both, or neither ──►  COMMIT
   │  INSERT row     (outbox)     │
   └──────────────────────────────┘
                  │
                  ▼  (separate, asynchronous)
        Outbox relay  ──►  Kafka topic  ──►  other services
                  │
                  └─ mark outbox row as published
```

La recompensa es que "guarda mis eventos" y "avisa al mundo" se convierten en un único
hecho atómico desde el punto de vista del escritor. El comando del agregado termina
cuando la transacción local confirma; la propagación es problema del relay, y el relay
puede ser tan lento, reintentado o reiniciado como necesite sin perder ni fabricar
nunca un evento.

!!! note "Término clave — outbox transaccional"
    El **outbox transaccional** registra "este evento debe publicarse" en la misma
    transacción local que persiste el evento, y luego lo retransmite al broker fuera de
    banda. Cambia lo imposible (una confirmación atómica que abarque una base de datos y
    un broker de mensajes) por lo alcanzable (una confirmación local atómica más un relay
    al-menos-una-vez), eliminando las carreras de actualización-perdida y
    publicación-fantasma de las dobles escrituras.

!!! spring "Equivalente en Spring"
    Este es el mismo patrón al que recurren los equipos de Spring con librerías como
    Debezium o un relay hecho a mano — pero ensamblarlo correctamente (la inserción
    atómica, la idempotencia del relay, el contrato de desduplicación) es delicado y
    fácil de equivocar. Firefly pliega el outbox dentro del anexado del almacén de
    eventos, de modo que publicar es una propiedad de guardar, no una segunda cosa que
    debes acordarte de hacer.

## Snapshots

El replay es elegante hasta que un stream es largo. Una cuenta abierta durante diez años
con asientos diarios tiene miles de eventos, y plegarlos todos en cada carga es un
derroche. Un **snapshot** es la cura: periódicamente, el framework serializa el estado
*actual* del agregado y lo almacena etiquetado con la versión que representa. En la
siguiente carga, el almacén lee el snapshot más reciente y luego reproduce solo los
eventos *posteriores* a esa versión.

```text
   Without snapshots:   replay events 1 ............................ 5000   (slow)

   With a snapshot @ 4900:
        load snapshot (state @ 4900)  +  replay events 4901 .. 5000  (fast)
```

Un snapshot es puramente una **optimización, nunca una fuente de verdad.** Los eventos
siguen siendo autoritativos; un snapshot es un fold en caché que podrías borrar y
regenerar en cualquier momento. Esa distinción importa: si alguna vez un snapshot es
sospechoso — pongamos un bug de serialización — lo descartas y reproduces desde un
snapshot anterior o desde cero, y obtienes el mismo estado, porque los eventos nunca
mintieron. Típicamente configuras la cadencia (cada N eventos, o según un calendario)
como una propiedad `firefly.*` y nunca escribes la lógica de snapshots a mano.

!!! note "Término clave — snapshot"
    Un **snapshot** es una copia almacenada y versionada del estado derivado de un
    agregado, usada para acortar la reconstrucción: carga el snapshot y luego reproduce
    solo los eventos posteriores a su versión. Es una caché de rendimiento, no un
    registro de verdad — borrable y regenerable a partir del stream de eventos
    autoritativo en cualquier momento.

!!! warning "Un snapshot es una caché, no la verdad — conserva los eventos para siempre"
    Dos tentaciones que resistir. Primero, no trates el snapshot como la verdad y
    empieces a podar eventos antiguos "porque tenemos un snapshot" — en el momento en que
    borras eventos pierdes el replay, la auditoría y las proyecciones retroactivas, las
    razones mismas por las que elegiste el event sourcing. Segundo, versiona cada
    snapshot: si la forma del estado del agregado cambia, un snapshot antiguo debe
    ignorarse (y regenerarse por replay), no deserializarse en la nueva forma.

## Upcasting para la evolución del esquema

Los eventos son inmutables y viven para siempre, lo que plantea la pregunta obvia: ¿qué
pasa cuando la *forma* de un evento debe cambiar? Supón que la revisión 1 de
`FundsDeposited` no tenía campo `reference` y añades uno como obligatorio en la revisión
2. Millones de eventos de revisión 1 están en el almacén sin `reference`. No puedes
reescribir la historia, y no quieres que el cargador se atragante con la forma antigua.

La respuesta es un **upcaster**: una función pequeña y pura que transforma un evento de
una revisión a la siguiente *según se lee*, antes de que llegue a tu manejador
`on(...)`. El almacén deserializa el payload de revisión 1, lo pasa por la cadena de
upcasters hasta la revisión actual y entrega a tu agregado solo la forma actual. Los
eventos antiguos en disco nunca cambian; el upcaster es la lente que los lee hacia
adelante.

```java
// ILLUSTRATIVE — not in the reactor.
@EventUpcaster(type = "ledger.funds-deposited", fromRevision = 1, toRevision = 2)
public class FundsDepositedV1ToV2 implements Upcaster {

    @Override
    public ObjectNode upcast(ObjectNode payload) {
        // revision 1 had no "reference"; supply a safe default for old events.
        if (!payload.has("reference")) {
            payload.put("reference", "LEGACY-UNREFERENCED");
        }
        return payload;
    }
}
```

Los upcasters se **encadenan**: si llegas a la revisión 4, un evento escrito en la
revisión 1 pasa por los upcasters de 1 a 2, de 2 a 3 y de 3 a 4 por turno, cada uno un
paso minúsculo y testeable de forma independiente. Como se ejecutan en la lectura y
nunca tocan los datos almacenados, son seguros de desplegar y triviales de testear
unitariamente — alimentas un payload antiguo, afirmas la forma nueva. Así es como un
sistema con event sourcing evoluciona su esquema durante años sin una sola migración
destructiva.

!!! note "Término clave — upcasting"
    El **upcasting** transforma un evento almacenado de una revisión más antigua a la
    actual en el momento de la lectura, de modo que los agregados y las proyecciones ven
    siempre la forma más reciente mientras los eventos históricos permanecen inalterados
    byte a byte. Los upcasters son funciones puras indexadas por `type` y revisión del
    evento, aplicadas en cadena, y son el mecanismo por el cual una historia inmutable
    coexiste con un esquema en evolución.

!!! warning "Haz upcast solo hacia adelante — nunca edites los eventos en su sitio"
    La regla cardinal del event sourcing es que los eventos almacenados son inmutables.
    El arreglo equivocado para un cambio de esquema es un script de migración que
    reescribe filas de eventos antiguas; el arreglo correcto es un upcaster que las lee
    hacia adelante. Reescribir la historia pierde la garantía de auditoría que justificó
    el event sourcing en primer lugar — y un bug en la reescritura es irrecuperable,
    porque los hechos originales ya no están.

## Lo que has aprendido {.recap}

- La porción de originación de Lumen Lending es **CRUD más una saga**, no event
  sourcing, y este capítulo opcional no cambió nada de ella. El event sourcing es la
  herramienta correcta cuando la **historia es el producto** — un libro mayor, un saldo,
  una posición crítica para auditoría — y CRUD sigue siendo el valor por defecto
  correcto para una entidad cuyo estado *actual* es todo lo que alguien necesita.
- Un **`AggregateRoot`** con event sourcing decide eventos en los métodos de comando
  (`deposit`, `withdraw`) y muta el estado *solo* en los manejadores `on(...)`. El
  estado actual es el fold de todos los eventos; las invariantes viven en el comando,
  nunca en el manejador, de modo que el replay es determinista.
- Los eventos son records **`@DomainEvent`** inmutables con un `type` lógico estable y
  una `revision`. El **`EventStore`** reactivo (`R2dbcEventStore`) los anexa y los
  transmite, imponiendo **concurrencia optimista** al afirmar la versión del agregado en
  cada anexado.
- Un **`ProjectionService`** construye modelos de lectura a partir del stream y rastrea
  un **checkpoint** para que el procesamiento sea reanudable y los modelos de lectura
  reconstruibles. El **outbox transaccional** publica eventos de forma atómica con el
  anexado local, cambiando una transacción distribuida imposible por un relay
  al-menos-una-vez.
- Los **snapshots** acortan el replay de streams largos y son una caché, nunca la
  verdad; los **upcasters** transforman eventos antiguos hacia adelante en el momento de
  la lectura, de modo que una historia inmutable y auditada coexiste con un esquema que
  evoluciona durante años.

## Pruébalo tú mismo {.exercises}

Estos ejercicios son trabajo de diseño con papel y lápiz o en un buffer de borrador — no
hay test del reactor que ejecutar, porque la porción de originación no usa event
sourcing. El objetivo es pensar en eventos.

1. **Esboza el agregado del libro mayor.** En papel, enumera los eventos que emite una
   cuenta `Ledger`: `AccountOpened`, `FundsDeposited`, `FundsWithdrawn`, quizá
   `AccountFrozen`. Para cada uno, escribe el manejador `on(...)` en una línea —
   exactamente qué campo muta — y confirma que ninguno de ellos valida nada.
2. **Pon la guarda en el lugar correcto.** Escribe el comando `withdraw` y el manejador
   `on(FundsWithdrawn)`. Marca cuál de los dos rechaza un descubierto y explica, en una
   frase, por qué poner esa comprobación en el manejador rompería el replay el día que
   cambies la regla de descubierto.
3. **Reproduce un stream a mano.** Dada la secuencia de eventos `AccountOpened`,
   `FundsDeposited(500)`, `FundsWithdrawn(200)`, `FundsDeposited(50)`, pliégalos a
   través de tus manejadores y enuncia el saldo final. Ahora anota la *versión* del
   agregado después de cada evento — esto es lo que un `append` afirmaría.
4. **Diseña una proyección.** Especifica un modelo de lectura `account_statement` (una
   fila por asiento) y nombra el checkpoint que avanza. Luego describe, en dos frases, el
   procedimiento para reconstruirlo desde cero seis meses después del lanzamiento, y por
   qué eso es siquiera posible.
5. **Escribe un upcaster en papel.** Supón que la revisión 1 de `FundsWithdrawn`
   almacenaba `amount` como un `double` y la revisión 2 lo almacena como un `long` de
   unidades menores. Esboza el `@EventUpcaster` de la revisión 1 a la 2, y enuncia por
   qué editar las filas almacenadas de revisión 1 en su lugar sería el arreglo
   equivocado.

## Adónde ir ahora

Si el event sourcing pareció mucha maquinaria para la porción de originación, esa es la
conclusión honesta: recurres a él cuando una historia crítica para auditoría justifica
el coste, y te quedas con CRUD en caso contrario. El Capítulo 13 vuelve al camino que
Lumen recorre de verdad, externalizando la lógica de decisiones con el **motor de
reglas** — la siguiente capacidad que usa el flujo de originación para mantener la
política fuera de los condicionales escritos a mano.
