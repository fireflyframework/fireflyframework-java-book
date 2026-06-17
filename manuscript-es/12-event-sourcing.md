Empieza aquí con una admisión sencilla: Lumen Lending no usa event sourcing, y
este capítulo es opcional. La porción de originación que has construido persiste su
estado de la forma habitual — una fila `loan_application` actualizada en su sitio (capítulo 8), un rico
agregado que es dueño de su ciclo de vida (capítulo 9), comandos y consultas sobre un bus
(capítulo 10), eventos de dominio dentro de la JVM en el transporte `APPLICATION_EVENT` (capítulo
11) y una saga que compensa cuando un paso falla (capítulo 18). Eso es CRUD
clásico con orquestación, y para la originación es exactamente lo correcto. Cuando haces POST a
la capa de experiencia, la petición fluye `exp → domain → core`, la
`RegisterApplicationSaga` dirige la escritura, y el servicio core almacena una única fila
que vuelve marcada como `SUBMITTED` — sin registro de eventos, sin reproducción, sin proyección a la
vista. Nada en este capítulo cambia ese código, y no hay ninguna prueba acompañante que
ejecutar.

Entonces, ¿por qué un capítulo siquiera? Porque Firefly incluye una capacidad de event sourcing, y una
plataforma de préstamos real tiene al menos un lugar que la desea: el **ledger** (libro mayor). Cuando la
pregunta no es «¿cuál es el saldo ahora?» sino «demuéstrame cómo llegó el saldo hasta aquí, asiento
por asiento, y déjame reconstruirlo en cualquier instante pasado», la costumbre de CRUD de sobrescribir
el valor anterior es el valor por defecto equivocado. El event sourcing conserva los *eventos* — los
hechos que ocurrieron — como fuente de la verdad, y deriva el estado reproduciéndolos. Un
auditor nunca puede preguntar a CRUD «¿qué decía esta fila el martes pasado?»; el event sourcing
responde a eso por construcción.

Este capítulo enseña el modelo de event sourcing del framework contra un ejemplo lateral
claramente etiquetado como *ilustrativo*: un agregado `Ledger` de cuentas y asientos. Nada de
su código vive en el reactor — cada fragmento aquí es un bloque cercado estándar, no un
listado verificado — así que léelo como un mapa de dónde encaja cada pieza, no como una porción
que puedas ejecutar con `mvn test`. Cuando lo necesites, lo construirás de la forma que estos esbozos
muestran.

!!! warning "Capítulo ilustrativo — sin listados verificados"
    A diferencia del resto del libro, el código aquí **no** es una porción literal del
    reactor acompañante y no lo comprueba la integración continua. La porción de
    originación es CRUD más una saga; el event sourcing es una capacidad ausente enseñada como
    cómo-funciona. Los nombres de clase y de anotación coinciden con el módulo de event sourcing
    de Firefly, pero el ejemplo `Ledger` es tuyo para construirlo, no de Lumen para enviarlo.

## Cuándo recurrir al event sourcing (y cuándo no)

La decisión no es estética; se deriva de para qué sirven los datos. Usa la lista
de abajo como un triaje, y luego confía en el valor por defecto que implica.

Recurre al **event sourcing** cuando:

- El **historial es el producto.** Un ledger, un saldo, una posición, un registro de auditoría — la
  secuencia de cambios tiene peso regulatorio o de negocio, y «el valor actual»
  es una comodidad derivada, no la verdad.
- Debes **reconstruir el estado pasado.** Disputas, reformulaciones y auditorías todas preguntan
  «¿qué aspecto tenía esto en el momento *T*?». Reproducir eventos hasta un punto lo responde
  exactamente; una fila sobrescrita no puede.
- Necesitas **analítica temporal o what-if.** Como cada cambio se retiene,
  puedes construir nuevos modelos de lectura retroactivamente — proyectar una métrica que no pensaste
  capturar, sobre eventos que ya ocurrieron.
- **Escritores concurrentes compiten** por una entidad y quieres detección de conflictos
  optimista, basada en versiones, en lugar de bloqueos pesimistas.

Quédate con **CRUD** (el valor por defecto de la originación) cuando:

- La entidad tiene un **ciclo de vida pero no un historial significativo** — una solicitud de préstamo
  pasa de `DRAFT` a `APPROVED`, y el estado *actual* es lo que todo consumidor quiere.
  La columna `updated_at` y un evento publicado en la transición son de sobra.
- El equipo es **pequeño y el dominio es joven.** El event sourcing añade maquinaria real —
  un almacén de eventos, proyecciones, snapshots, upcasters — y pagas ese coste por adelantado.
- No necesitas **responder preguntas históricas** que una tabla de auditoría normal no pueda
  ya responder.

!!! note "Término clave — event sourcing"
    El **event sourcing** almacena el estado de una entidad como una secuencia de solo-anexado de
    **eventos de dominio** inmutables en lugar de como una fila mutable. El estado actual *no* se
    almacena; se calcula reproduciendo los eventos a través del agregado. Los eventos
    son el sistema de registro; cualquier tabla que puedas consultar es una **proyección** derivada que
    puedes reconstruir a voluntad a partir de los eventos.

!!! spring "Equivalente en Spring"
    No existe un «starter de event sourcing» de Spring puro. En una aplicación Spring Boot básica
    montarías esto tú mismo — una tabla de eventos, un serializador JSON, una
    comprobación de bloqueo optimista, un trabajo de proyección, una estrategia de snapshot — y cada equipo
    lo haría un poco diferente. Ese es precisamente el impuesto empresarial del
    capítulo 1. La contribución de Firefly aquí es el *mismo* montaje, cableado una vez, tras
    un `EventStore` reactivo y una clase base `AggregateRoot`, de modo que los servicios
    con event sourcing de la flota coincidan en las partes difíciles.

## El agregado como un fold sobre eventos

En el capítulo 9, `LoanApplication` cambiaba su propio estado directamente: `approve()` ponía
`status` en `APPROVED`. Un agregado con event sourcing funciona de otra forma. Un método de comando
no muta el estado — *decide qué evento ocurrió* y lo emite. El estado entonces
cambia solo como un efecto secundario de **aplicar** ese evento, en un manejador `on(...)`. El
estado actual del agregado es, literalmente, el fold por la izquierda de todos sus eventos a través de
esos manejadores.

Aquí está la cuenta del agregado `Ledger` como un esbozo. Fíjate en las dos mitades: el
método de comando (`deposit`) valida y eleva un evento; el manejador `on(...)` es el
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

La disciplina es estricta y vale la pena interiorizarla: **los invariantes viven en el comando,
el estado vive en el manejador `on(...)`.** `withdraw` rechaza un descubierto *antes* de
elevar un evento, porque un evento es un hecho que ya ocurrió y un hecho no puede
des-ocurrir. El manejador `on(FundsWithdrawn)` nunca revalida — confía en que cualquier
evento del flujo era legal cuando se elevó. Esa división es lo que hace segura la reproducción: para
reconstruir una cuenta a partir del historial, el framework construye un `Account` en blanco y alimenta
cada evento almacenado a través de `on(...)` en orden, sin que se ejecute ninguna lógica de comando.

!!! note "Término clave — despacho de manejadores `on(...)`"
    Un **manejador `on(...)`** (aquí anotado con `@EventSourcingHandler`) aplica un tipo de evento
    al estado en memoria del agregado. `AggregateRoot` despacha cada evento al
    manejador cuyo parámetro coincide con el tipo del evento. Durante la operación normal un
    manejador se ejecuta una vez, justo después de `raise(...)`; durante la reconstrucción se ejecuta una vez por
    evento histórico. Como los manejadores solo asignan campos y nunca validan, reproducir
    un millón de eventos es determinista y libre de efectos secundarios.

!!! warning "Nunca pongas una guarda en un manejador `on(...)`"
    Es tentador volver a comprobar la regla del descubierto dentro de `on(FundsWithdrawn)`. No lo hagas.
    La validación pertenece al comando, que se ejecuta una vez contra el estado vivo. Una guarda en
    el manejador se ejecuta de nuevo en *cada reproducción* — y el día que endurezcas la regla, toda
    cuenta histórica que en su momento era legal fallará al cargar. El único trabajo del manejador es
    plegar un evento conocido-bueno en el estado.

!!! spring "Equivalente en Spring"
    No hay análogo en Spring para esta división comando/`on(...)`. Un `@Service`
    básico muta una entidad JPA o R2DBC en su sitio; «el estado» *es* la fila, y
    el historial es lo que recordaste escribir en una tabla de auditoría. El
    `AggregateRoot` de Firefly invierte eso: los eventos son primarios y el estado es derivado, y
    `raise(...)` es el único punto de estrangulamiento por el que debe pasar cada cambio. La
    recompensa — reconstrucción determinista a partir del historial — es exactamente la propiedad que una
    fila mutada nunca puede darte, por muy cuidadoso que sea el `@Service`.

Haz concreto el fold. Dado el flujo `AccountOpened`, `FundsDeposited(500)`,
`FundsWithdrawn(200)`, el framework construye un `Account` en blanco y enhebra cada
evento a través de `on(...)` en orden: la apertura pone el saldo en `0`, el depósito lo eleva
a `500`, la retirada lo baja a `300`. Ningún comando `deposit` ni `withdraw` se ejecuta
durante esta reproducción — solo se disparan los tres manejadores `on(...)` — y el resultado es
`balanceMinorUnits == 300` cada una de las veces, en cada máquina, para siempre. Ese
determinismo no es un detalle bonito; es el contrato que permite que un snapshot sea una caché y una
proyección sea desechable, ambas cosas que conoces más abajo.

## Los eventos de dominio como fuente de la verdad

Los eventos son el quid de la cuestión, así que reciben un trato de primera clase: cada uno es un
record inmutable, lleva todo lo necesario para reconstruirse, y está marcado para que el
framework pueda serializarlo, versionarlo y enrutarlo.

```java
// ILLUSTRATIVE — not in the reactor.
@DomainEvent(type = "ledger.funds-deposited", revision = 1)
public record FundsDeposited(
        String accountId,
        long amount,
        String reference) {
}
```

Dos propiedades hacen duradero a un record como este. Primero, es **inmutable** — un
`record` sin setters — porque un hecho almacenado nunca debe cambiar. Segundo, la
anotación `@DomainEvent` le da un *tipo lógico* estable (`ledger.funds-deposited`)
y una *revisión*. El tipo lógico desacopla la identidad del evento de su nombre de clase
Java, de modo que puedes refactorizar el paquete o renombrar la clase sin dejar huérfanos años de
eventos almacenados; el almacén lee y escribe la cadena, no el nombre de clase
completamente cualificado. El número de `revision` es lo que hace tratable la evolución del esquema
más adelante en este capítulo.

!!! note "Término clave — `@DomainEvent`"
    `@DomainEvent` marca un record como un hecho serializable y versionado en el historial de un
    agregado. Su `type` es el identificador estable persistido junto a cada fila de evento;
    su `revision` registra qué versión de esquema lo produjo. Juntos permiten que el
    almacén de eventos deserialice un evento escrito hace años en lo que el código actual
    espera — siempre que proporciones un upcaster para cualquier salto de revisión.

## El EventStore reactivo y la concurrencia optimista

El agregado produce eventos; algo debe anexarlos de forma duradera y volver a leerlos.
Ese algo es el `EventStore` — un puerto reactivo, con un adaptador R2DBC,
`R2dbcEventStore`, que persiste eventos en una base de datos relacional sin bloquear nunca
el event loop. Su superficie es pequeña y enteramente `Mono`/`Flux`, exactamente el modelo
reactivo del capítulo 5.

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

El parámetro portante es `expectedVersion`. Cada agregado lleva una **versión**
— el conteo de eventos en su flujo — y cada append afirma la versión sobre la que esperaba
escribir. El almacén comprueba que la versión almacenada todavía coincide y la
incrementa *atómicamente* al anexar. Si dos escritores cargaron el mismo `Account` en
la versión 7, ambos calcularon una retirada, y ambos intentaron anexar «el 8º evento», solo
el primero tiene éxito; el `append` del segundo falla porque el flujo ya está en la
versión 8. Eso es **concurrencia optimista**, aplicada en el almacén de eventos en lugar de
con un bloqueo de base de datos.

Un repositorio lo ata todo. Cargar pliega el flujo en un agregado fresco; guardar
anexa los eventos no confirmados en la versión en la que se cargó el agregado.

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

Un `append` fallido aflora como un `onError` que manejas a la manera reactiva — típicamente
`retryWhen` con una recarga, de modo que el perdedor de una carrera recarga en la versión 8,
vuelve a decidir su retirada contra el saldo ahora actual, y anexa como versión 9. El
invariante del agregado (sin descubierto) se vuelve a comprobar en esa recarga, que es exactamente por qué quieres
la guarda en el comando y no en el manejador. Este es el momento en que toda la disciplina
da sus frutos: como la regla vive en el comando y el comando se ejecuta de nuevo en cada
reintento, el perdedor no vuelve a aplicar ciegamente una decisión obsoleta — hace la pregunta
de nuevo contra el saldo que el ganador acaba de dejar atrás, y puede ahora legítimamente
rechazar donde habría tenido éxito un milisegundo antes.

Una pregunta natural: ¿en qué versión escribe un agregado totalmente nuevo? Una cuenta que
nunca se ha almacenado está en la versión `0`, así que su primer `append` (que lleva
`AccountOpened`) afirma `expectedVersion = 0` y crea el flujo; el almacén
rechaza esa creación si ya existe algún flujo para el id, que es como el event
sourcing impone «abre esta cuenta exactamente una vez».

!!! note "Término clave — concurrencia optimista mediante versionado de agregados"
    El flujo de cada agregado tiene una **versión** monótonamente creciente igual a su
    conteo de eventos. Un append lleva la versión que leyó; el almacén confirma solo si esa
    sigue siendo la última, y rechaza la escritura en caso contrario. Ninguna fila se bloquea entre la lectura
    y la escritura — los conflictos se *detectan* en la confirmación, no se *previenen* bloqueando — lo que
    mantiene la tubería reactiva no bloqueante y permite que la contención honesta reintente limpiamente.

!!! warning "Un almacén de eventos necesita un contrato de serialización, no objetos Java en crudo"
    Los eventos sobreviven al código que los escribió. El almacén persiste cada evento como su
    `type` de `@DomainEvent` más un payload serializado (JSON), nunca un
    blob serializado por Java con clave en el nombre de clase. Si omites el tipo lógico y te apoyas en el
    nombre de clase completamente cualificado, el primer renombrado de paquete hace ilegibles años de
    historial. Trata el esquema de eventos como un contrato publicado desde el primer día.

## Proyecciones y checkpoints

Reproducir cada evento para responder «¿cuál es el saldo?» es correcto pero no es cómo
sirves una consulta a escala. Construyes una **proyección**: una tabla optimizada para lectura — digamos
`account_balance(account_id, balance, updated_at)` — mantenida al día consumiendo el
flujo de eventos y aplicando cada evento a la tabla. El agregado es el modelo de escritura;
la proyección es el modelo de lectura, y están deliberadamente separados (esta es la división CQRS
del capítulo 10, ahora con los eventos como la costura).

Un `ProjectionService` consume eventos y mantiene un **checkpoint** — la posición en
el flujo global de eventos hasta la que ha procesado — de modo que al reiniciar reanuda exactamente
donde lo dejó en lugar de reprocesar el historial o saltarse eventos.

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

Dos propiedades de los checkpoints se ganan su sustento. Primero, la **reanudabilidad**: una proyección
que se cae después del evento 4.000.000 reinicia y pide al almacén los eventos posteriores a su
checkpoint, no desde cero. Segundo, la **reconstruibilidad**: pon el checkpoint de nuevo al
inicio y el mismo código reconstruye la tabla desde cero — que es como añades un modelo de
lectura totalmente nuevo meses después, sobre eventos que ya ocurrieron, o reparas una proyección
corrompida por un bug. Como los eventos son la fuente de la verdad, una proyección es siempre
desechable y siempre reconstruible.

!!! note "Término clave — proyección y checkpoint"
    Una **proyección** es un modelo de lectura derivado construido aplicando eventos a un almacén
    con forma de consulta; no contiene verdad propia y puede descartarse y reconstruirse. Un
    **checkpoint** es la posición persistida del flujo hasta la que una proyección ha consumido,
    avanzada atómicamente con cada evento aplicado de modo que el procesamiento es reanudable y
    de hecho exactamente-una-vez. Muchas proyecciones pueden consumir el mismo flujo en checkpoints
    independientes.

!!! spring "Equivalente en Spring"
    En Spring puro montarías esto a mano como un poller programado o un consumidor de Kafka
    que rastrea un offset en una tabla lateral, y harías el atómico
    «aplicar-y-avanzar» sutilmente mal al menos una vez. El `ProjectionService` de Firefly hace
    del checkpoint un concepto de primera clase y transaccional, de modo que los modelos de lectura de la flota
    comparten un mecanismo reanudable y reconstruible en lugar de que cada servicio improvise una
    tabla de offsets.

## El outbox transaccional

Este es el fallo que atormenta a los sistemas dirigidos por eventos ingenuos: anexas el evento al
almacén *y* quieres publicarlo en Kafka para que otros servicios reaccionen. Haz eso como
dos operaciones separadas y una caída entre ellas o pierde la publicación (evento
almacenado, nunca anunciado) o la duplica (publicado, y luego la escritura en el almacén se revierte).
El **outbox transaccional** cierra esa brecha.

La idea es simple y el framework la cablea: en la *misma transacción de base de datos* que
anexa el evento al almacén, insertas una fila en una tabla `outbox`. La confirmación es
atómica — o aterrizan tanto el evento como su fila de outbox, o ninguno lo hace. Un **relay**
separado luego lee el outbox y publica al broker, marcando cada fila como enviada. Si
el relay se cae a mitad de vuelo, vuelve a publicar al reiniciar; los consumidores aguas abajo deduplican
por ID de evento. Obtienes entrega *al-menos-una-vez* sin eventos perdidos, sin una
transacción distribuida a través de la base de datos y el broker.

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

La recompensa es que «guarda mis eventos» y «cuéntaselo al mundo» se convierten en un único hecho
atómico desde el punto de vista del escritor. El comando del agregado termina cuando la transacción
local confirma; la propagación es problema del relay, y el relay puede ser tan lento,
reintentado o reiniciado como necesite sin perder ni fabricar nunca un evento.

Vale un recordatorio sobre la honestidad aquí: el outbox importa precisamente cuando «cuéntaselo al
mundo» cruza una frontera de proceso. Los eventos de originación de Lumen permanecen *dentro de la JVM* en el
transporte `APPLICATION_EVENT` (capítulo 11), así que el salto al broker del diagrama simplemente no
existe para el ejemplo — no hay un segundo sistema que pudiera discrepar con la
base de datos. El outbox se gana su sustento el día que cambies ese transporte a Kafka o
Postgres y la publicación se convierta en una escritura genuinamente separada; el patrón está aquí para que
reconozcas la carrera antes de que muerda, no porque la porción lo ejecute.

!!! note "Término clave — outbox transaccional"
    El **outbox transaccional** registra «este evento debe publicarse» en la misma
    transacción local que persiste el evento, y luego lo retransmite al broker
    fuera de banda. Cambia lo imposible (una confirmación atómica que abarque una base de datos y un
    broker de mensajes) por lo alcanzable (una confirmación local atómica más un relay al-menos-una-vez),
    eliminando las carreras de actualización perdida y de publicación fantasma de las escrituras duales.

!!! spring "Equivalente en Spring"
    Este es el mismo patrón al que recurren los equipos de Spring con librerías como Debezium o un
    relay construido a mano — pero montarlo correctamente (el insert atómico, la idempotencia del
    relay, el contrato de deduplicación) es delicado y fácil de equivocar. Firefly pliega el
    outbox dentro del append del almacén de eventos de modo que publicar es una propiedad de guardar, no una
    segunda cosa que debas recordar hacer.

## Snapshots

La reproducción es elegante hasta que un flujo es largo. Una cuenta abierta diez años con asientos
diarios tiene miles de eventos, y plegarlos todos en cada carga es un derroche.
Un **snapshot** es la cura: periódicamente, el framework serializa el estado *actual* del
agregado y lo almacena etiquetado con la versión que representa. En la siguiente carga,
el almacén lee el último snapshot, y luego reproduce solo los eventos *posteriores* a esa
versión.

```text
   Without snapshots:   replay events 1 ............................ 5000   (slow)

   With a snapshot @ 4900:
        load snapshot (state @ 4900)  +  replay events 4901 .. 5000  (fast)
```

Un snapshot es puramente una **optimización, nunca una fuente de la verdad.** Los eventos siguen siendo
autoritativos; un snapshot es un fold cacheado que podrías borrar y regenerar en cualquier momento.
Esa distinción importa: si un snapshot alguna vez es sospechoso — digamos un bug de serialización — lo
descartas y reproduces desde un snapshot anterior o desde cero, y obtienes el mismo
estado, porque los eventos nunca mintieron. Típicamente configuras la cadencia (cada N
eventos, o según un horario) como una propiedad `firefly.*` y nunca escribes la lógica de snapshots
a mano.

!!! note "Término clave — snapshot"
    Un **snapshot** es una copia almacenada y versionada del estado derivado de un agregado, usada para
    atajar la reconstrucción: carga el snapshot, y luego reproduce solo los eventos posteriores a su
    versión. Es una caché de rendimiento, no un registro de la verdad — borrable y
    regenerable a partir del flujo de eventos autoritativo en cualquier momento.

!!! warning "Un snapshot es una caché, no la verdad — conserva los eventos para siempre"
    Dos tentaciones que resistir. Primero, no trates el snapshot como la verdad y empieces a
    podar eventos antiguos «porque tenemos un snapshot» — en el momento en que borras eventos
    pierdes la reproducción, la auditoría y las proyecciones retroactivas, las mismísimas razones por las que elegiste
    el event sourcing. Segundo, versiona cada snapshot: si la forma del estado del agregado
    cambia, un snapshot antiguo debe ignorarse (y regenerarse por reproducción), no
    deserializarse en la nueva forma.

## Upcasting para la evolución del esquema

Los eventos son inmutables y viven para siempre, lo que plantea la pregunta obvia: ¿qué
ocurre cuando la *forma* de un evento debe cambiar? Supón que `FundsDeposited` revisión 1
no tenía campo `reference` y añades uno como obligatorio en la revisión 2.
Millones de eventos de revisión 1 yacen en el almacén sin `reference`. No puedes reescribir la historia, y
no quieres que el cargador se atragante con la forma antigua.

La respuesta es un **upcaster**: una función pequeña y pura que transforma un evento de una
revisión a la siguiente *a medida que se lee*, antes de que llegue a tu manejador `on(...)`. El
almacén deserializa el payload de la revisión 1, lo pasa por la cadena de upcasters hasta la
revisión actual, y entrega a tu agregado solo la forma actual. Los eventos antiguos en
disco nunca cambian; el upcaster es la lente que los lee hacia adelante.

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

Los upcasters **encadenan**: si llegas a la revisión 4, un evento escrito en la revisión 1 pasa
por los upcasters de 1-a-2, 2-a-3 y 3-a-4 por turno, cada uno un paso diminuto e
independientemente comprobable. Como se ejecutan en la lectura y nunca tocan los datos almacenados, son seguros de
desplegar y triviales de probar unitariamente — alimenta un payload antiguo, afirma la nueva forma. Así
es como un sistema con event sourcing evoluciona su esquema durante años sin una sola
migración destructiva.

!!! note "Término clave — upcasting"
    El **upcasting** transforma un evento almacenado de una revisión más antigua a la actual
    en el momento de la lectura, de modo que los agregados y las proyecciones siempre ven la forma más reciente mientras
    los eventos históricos permanecen byte-a-byte sin cambios. Los upcasters son funciones puras
    indexadas por `type` y revisión del evento, aplicadas en una cadena, y son el mecanismo
    por el que el historial inmutable coexiste con un esquema en evolución.

!!! warning "Haz upcast solo hacia adelante — nunca edites eventos en su sitio"
    La regla cardinal del event sourcing es que los eventos almacenados son inmutables. El arreglo
    equivocado para un cambio de esquema es un script de migración que reescribe filas de eventos antiguas; el
    arreglo correcto es un upcaster que las lee hacia adelante. Reescribir la historia pierde la
    garantía de auditoría que justificó el event sourcing en primer lugar — y un bug en la
    reescritura es irrecuperable, porque los hechos originales han desaparecido.

## Lo que has aprendido {.recap}

- La porción de originación de Lumen Lending es **CRUD más una saga**, no event sourcing, y
  este capítulo opcional no cambió nada de ella. El event sourcing es la herramienta correcta cuando
  **el historial es el producto** — un ledger, un saldo, una posición crítica para la auditoría — y
  CRUD sigue siendo el valor por defecto correcto para una entidad cuyo estado *actual* es todo lo que cualquiera
  necesita.
- Un **`AggregateRoot`** con event sourcing decide eventos en métodos de comando (`deposit`,
  `withdraw`) y muta el estado *solo* en manejadores `on(...)`. El estado actual es el fold
  de todos los eventos; los invariantes viven en el comando, nunca en el manejador, de modo que la reproducción
  es determinista.
- Los eventos son records **`@DomainEvent`** inmutables con un `type` lógico estable y una
  `revision`. El **`EventStore`** reactivo (`R2dbcEventStore`) los anexa y transmite,
  aplicando **concurrencia optimista** afirmando la versión del agregado en
  cada append.
- Un **`ProjectionService`** construye modelos de lectura a partir del flujo y rastrea un
  **checkpoint** de modo que el procesamiento es reanudable y los modelos de lectura son reconstruibles. El
  **outbox transaccional** publica eventos atómicamente con el append local, cambiando
  una transacción distribuida imposible por un relay al-menos-una-vez — una carrera que solo
  existe una vez que cruzas una frontera de proceso, lo que el transporte `APPLICATION_EVENT` dentro de la JVM de Lumen no hace.
- Los **snapshots** atajan la reproducción de flujos largos y son una caché, nunca la verdad;
  los **upcasters** transforman eventos antiguos hacia adelante en el momento de la lectura, de modo que un historial
  inmutable y auditado coexiste con un esquema que evoluciona durante años.

## Pruébalo tú mismo {.exercises}

Estos ejercicios son trabajo de diseño de lápiz y papel o de buffer de borrador — no hay ninguna prueba
del reactor que ejecutar, porque la porción de originación no usa event sourcing. El objetivo es pensar
en eventos.

1. **Esboza el agregado ledger.** En papel, lista los eventos que una cuenta `Ledger`
   emite: `AccountOpened`, `FundsDeposited`, `FundsWithdrawn`, quizá `AccountFrozen`.
   Para cada uno, escribe el manejador `on(...)` en una línea — exactamente qué campo muta —
   y confirma que ninguno de ellos valida nada.
2. **Pon la guarda en el lugar correcto.** Escribe el comando `withdraw` y el
   manejador `on(FundsWithdrawn)`. Marca cuál rechaza un descubierto y explica, en una
   frase, por qué poner esa comprobación en el manejador rompería la reproducción el día que
   cambies la regla del descubierto.
3. **Reproduce un flujo a mano.** Dada la secuencia de eventos `AccountOpened`,
   `FundsDeposited(500)`, `FundsWithdrawn(200)`, `FundsDeposited(50)`, pliégalos
   a través de tus manejadores y enuncia el saldo final. Ahora anota la *versión* del agregado
   después de cada evento — esto es lo que un `append` afirmaría.
4. **Diseña una proyección.** Especifica un modelo de lectura `account_statement` (una fila por
   asiento) y nombra el checkpoint que avanza. Luego describe, en dos frases, el
   procedimiento para reconstruirlo desde cero seis meses después del lanzamiento, y por qué eso es siquiera
   posible.
5. **Escribe un upcaster en papel.** Supón que `FundsWithdrawn` revisión 1 almacenaba `amount`
   como un `double` y la revisión 2 lo almacena como un `long` de unidades menores. Esboza el
   `@EventUpcaster` de la revisión 1 a la 2, y enuncia por qué editar las filas almacenadas de revisión 1
   en su lugar sería el arreglo equivocado.
6. **Encuentra la costura en Lumen.** La porción de originación es CRUD: el servicio core almacena
   una única fila `loan_application` que el flujo en vivo `exp → domain → core` deja en
   `status: SUBMITTED`. ¿Dónde, en ese flujo, *podría* encajar un ledger con event sourcing
   sin perturbar la originación? Escribe dos frases nombrando la frontera — por
   ejemplo, un ledger de desembolsos al que el servicio core haría asientos tras la aprobación —
   y explica por qué el event sourcing encaja *ahí* pero no en el estado de la solicitud
   en sí.

## Adónde ir ahora

Si el event sourcing pareció mucha maquinaria para la porción de originación, esa es la
conclusión honesta: recurres a él cuando un historial crítico para la auditoría justifica el coste,
y te quedas con CRUD en caso contrario. El capítulo 13 vuelve al camino que Lumen realmente recorre,
externalizando la lógica de decisión con el **motor de reglas** — la siguiente capacidad que el
flujo de originación usa para mantener la política fuera de condicionales escritos a mano.
