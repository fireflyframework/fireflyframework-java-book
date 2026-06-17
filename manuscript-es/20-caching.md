Un servicio de originación de préstamos pasa la mayor parte de su tiempo respondiendo a las
mismas preguntas. «¿Cuál es el estado de esta solicitud?» «¿Cuál es el tarifario de este
producto?» «¿Está este solicitante en la lista de vigilancia?» Los datos que hay detrás de
esas respuestas cambian despacio, pero las preguntas llegan sin parar — y cada una que viaja
hasta la base de datos, o peor aún hasta un servicio core de aguas abajo, cuesta un viaje de
ida y vuelta que no necesitabas pagar. La caché es la forma de dejar de pagarlo. La
resiliencia es la forma de sobrevivir a los viajes de ida y vuelta que *no puedes* evitar
cuando lo que hay al otro lado va lento o está caído.

Firefly trata ambas como capacidades transversales, conectadas de la misma manera que todo
lo demás en este libro: un puerto, un adaptador por defecto y una propiedad para
intercambiarlo. La caché es un `CacheAdapter` reactivo con Caffeine integrado y una variante
distribuida L2 a una dependencia de distancia. La resiliencia es un circuit breaker, un
reintento y un bulkhead de Resilience4j que el `ServiceClient` unificado (capítulo 16) aplica
por ti — expresados mediante operadores de Reactor, nunca con un `try`/`catch` bloqueante. Te
adhieres a la caché de consultas con un único atributo de anotación, y ajustas la resiliencia
con propiedades `firefly.*`.

Una nota honesta antes de empezar. **Este capítulo es conceptual.** La porción de
originación de Lumen Lending que has venido construyendo *no* conecta una caché — su modelo
de lectura es deliberadamente trivial (el `GetApplicationStatusHandler` que leerás más abajo
simplemente devuelve una constante), y sus 33 tests se ejecutan contra valores por defecto
en proceso, sin Redis y sin dramas de circuit breaker. Así que aquí no hay un test
acompañante que poner en verde, y este capítulo no extrae una porción de código de
producción verificado como el capítulo 2 extrajo el punto de entrada del core. En su lugar,
cada fragmento de abajo es *ilustrativo*: muestra los tipos y propiedades reales de Firefly,
anclados en el código fuente del framework, y señala exactamente dónde encaja cada uno en el
servicio que ya tienes. La única porción literal es el manejador *sin caché* tal como está
hoy — el punto de partida sobre el que después se construyen los ejercicios. Esos ejercicios
te hacen añadir una caché a una consulta caliente real del reactor —
`GetApplicationStatusHandler` en el módulo de dominio — de modo que terminas el capítulo con
código funcionando aunque el capítulo en sí no entregue ninguno.

Este es el camino que recorreremos. Las secciones 1 a 4 construyen la caché de abajo arriba:
el puerto, la L1 integrada, la L2 distribuida y la elección de proveedor en tiempo de
ejecución. La sección 5 la conecta con el bus CQRS que ya conoces, de modo que una lectura
se vuelve cacheable con un atributo y se auto-sana mediante eventos. Las secciones 6 y 7 se
vuelcan hacia la resiliencia — los patrones que protegen las llamadas que una caché *no
puede* eliminar, y por qué son operadores de Reactor en lugar de guardas bloqueantes. La
sección 8 es la contabilidad honesta de lo que el reactor conecta y lo que no. Cada sección
termina donde la anterior lo dejó, así que el conjunto se lee como un único argumento:
elimina los viajes de ida y vuelta que puedas, sobrevive a los que no puedas, y observa cómo
los dos se encuentran cuando una caché se convierte en un fallback.

## El puerto CacheAdapter

Todo arranca en una única interfaz, `org.fireflyframework.cache.core.CacheAdapter`. Es
reactiva hasta el núcleo — cada operación devuelve un `Mono`, de modo que una búsqueda en
caché se compone en la cadena de un manejador exactamente igual que una llamada a un
repositorio, y un *fallo* de caché es simplemente un `Optional` vacío, nunca una comprobación
de null bloqueante.

```java
public interface CacheAdapter {
    <K, V> Mono<Optional<V>> get(K key);
    <K, V> Mono<Optional<V>> get(K key, Class<V> valueType);
    <K, V> Mono<Void>        put(K key, V value);
    <K, V> Mono<Void>        put(K key, V value, Duration ttl);
    <K, V> Mono<Boolean>     putIfAbsent(K key, V value);
    <K>    Mono<Boolean>     evict(K key);
    // ...plus clear, evictByPrefix, exists, keys, size, getStats, getHealth, isAvailable
}
```

La forma importa, y vale la pena leerla con detenimiento. Tres decisiones de diseño en esa
firma hacen trabajo real:

1. **`get` devuelve `Mono<Optional<V>>`, no `Mono<V>`.** Un valor presente y uno ausente son
   ambos señales *normales* — un `Optional` poblado o uno vacío — no un valor frente a un
   error. Ramificas sobre ellos con operadores corrientes, y una caché vacía nunca es un
   `onError` que tengas que capturar. (Contrasta con un `Mono<V>` que se completa vacío:
   tendrías que distinguir «fallo» de «el valor realmente está vacío», y `switchIfEmpty` los
   confundiría.)
2. **`put` devuelve `Mono<Void>`, así que una escritura es una acción diferida y
   componible**, no un efecto secundario de disparar y olvidar. La encadenas con
   `then`/`thenReturn` de modo que la caché se pueble *como parte de* la misma tubería
   reactiva que produjo el valor — y la escritura se completa antes de que el valor fluya
   aguas abajo.
3. **Cada operación es genérica tanto en clave como en valor (`<K, V>`).** El puerto no te
   obliga a convertir las claves en cadenas ni a borrar tipos en la frontera; el adaptador
   gestiona la serialización donde debe (Redis) y la omite donde no hace falta (Caffeine).

Un patrón de lectura pasante (read-through) — el que más utilizarás — surge de esas
decisiones de forma natural:

```java
// Illustrative: look in the cache; on a miss, load and backfill.
cache.<String, RateCard>get(productId)
    .flatMap(cached -> cached
        .map(Mono::just)                              // hit: hand back the cached value
        .orElseGet(() -> loadRateCard(productId)      // miss: do the real work
            .flatMap(card -> cache
                .put(productId, card, Duration.ofMinutes(10))
                .thenReturn(card))));                 // ...and populate the cache
```

Ningún hilo se bloquea mientras la caché responde, porque la caché *misma* responde con un
`Mono`. Esa es toda la razón por la que el puerto es reactivo en lugar de una API simple al
estilo `Map`: en la pila reactiva, una caché que se bloquea para obtener datos de Redis
estancaría el bucle de eventos con la misma seguridad que lo haría una consulta bloqueante.
Un hilo del bucle de eventos bloqueado no solo ralentiza *esta* petición — estanca todas las
demás peticiones programadas en el mismo hilo, de modo que una sola búsqueda lenta en caché
se convierte en un pico de latencia para toda la flota. El puerto reactivo hace que ese modo
de fallo sea estructuralmente imposible: no hay ninguna llamada bloqueante que hacer.

!!! note "Término clave — CacheAdapter (el puerto de caché)"
    `CacheAdapter` es la única interfaz reactiva que implementa todo proveedor de caché —
    Caffeine, Redis, Hazelcast, JCache, Postgres. Tu código depende del puerto, nunca del
    cliente de un proveedor, de modo que el proveedor es una decisión de despliegue (una
    dependencia más una propiedad), no una decisión de código. Este es el mismo patrón
    hexagonal que el `EventPublisher` de EDA del capítulo 11 y el `IdpAdapter` de IDP del
    capítulo 19.

!!! note "¿Por qué no inyectar simplemente una `Cache` de Caffeine?"
    Podrías — la propia API de Caffeine es excelente. Pero inyectar el cliente concreto
    suelda tu manejador a Caffeine: el día que necesites una caché *compartida* entre
    instancias, cada sitio con lectura pasante es una reescritura. Depender del puerto
    `CacheAdapter` en su lugar significa que el cambio de en-proceso a distribuido es un
    cambio de propiedad, y el código de lectura pasante de arriba no mueve ni una línea. El
    puerto te cuesta hoy una interfaz extra para comprarte mañana una migración gratuita. Ese
    es el compromiso recurrente de Firefly.

## Caffeine es la L1 integrada

Añade la capacidad de caché y obtienes una caché funcional de inmediato, sin
infraestructura: **Caffeine**, una caché en proceso de alto rendimiento, es el valor por
defecto integrado. Vive dentro de tu JVM, así que un acierto es una llamada a método —
nanosegundos, sin red — y no necesita nada ejecutándose junto al servicio. Para una sola
instancia, o para datos que cada instancia puede cachear de forma independiente, Caffeine
por sí sola suele ser la respuesta completa.

```yaml
firefly:
  cache:
    type: CAFFEINE          # the built-in, in-process L1 — zero infrastructure
    caffeine:
      maximum-size: 10000   # bounded so it can never exhaust the heap
      expire-after-write: 10m
```

Caffeine es **acotada** por diseño — un número máximo de entradas y un tiempo de vida — de
modo que un espacio de claves descontrolado nunca puede comerse el heap. La acotación no es
una nota al pie; es la propiedad operativa más importante de una caché en proceso. Una caché
*no acotada* es una fuga de memoria con buenas intenciones: cada clave distinta que consultes
permanece residente hasta que el proceso muere, y un servicio que cachea por `productId` o
por `id` acabará por agotar la memoria (OOM) bajo un espacio de claves suficientemente amplio.
`maximum-size` convierte ese fallo latente en un conjunto de trabajo acotado con desalojo
del menos usado recientemente — la caché olvida las entradas más frías para hacer sitio, y el
heap se mantiene plano.

Esa acotación es también la única limitación de Caffeine: es por instancia y volátil.
Reinicia el servicio y la caché está fría; ejecuta tres instancias y cada una tiene su propia
copia, que pueden discrepar durante el tiempo de un TTL. Cuando eso importa, añades una capa
distribuida detrás — que es la siguiente sección.

!!! note "Término clave — TTL (tiempo de vida) y el presupuesto de obsolescencia"
    Un **TTL** es cuánto tiempo se permite vivir a una entrada cacheada antes de que expire y
    la siguiente lectura la recompute. Elegirlo es elegir un *presupuesto de obsolescencia*:
    un TTL de 10 minutos dice «estoy dispuesto a servir una respuesta con hasta 10 minutos de
    antigüedad a cambio de no recomputarla». Los TTL cortos cuestan más recomputación pero
    acotan la obsolescencia con firmeza; los TTL largos son más rápidos pero más arriesgados
    para datos que cambian. Para datos que *deben* estar frescos en el instante en que
    cambian, un TTL es la herramienta equivocada — quieres invalidación dirigida por eventos
    en su lugar (véase «invalidación», más adelante).

## Una L2 distribuida, write-through mediante SmartCacheAdapter

Pon una caché distribuida — Redis o Hazelcast — *detrás* de Caffeine y obtienes lo mejor de
ambas: la velocidad de una L1 en proceso para los aciertos, y una L2 compartida que sobrevive
a los reinicios y es consistente entre instancias. Firefly compone las dos por ti con el
`SmartCacheAdapter`, que es él mismo un `CacheAdapter` (de modo que tu código nunca sabe que
hay dos capas) envolviendo una L1 y una L2.

Su política es **write-through con backfill de lectura**, y vale la pena entenderla con
precisión porque determina lo que tu código observa:

- En un `put`, escribe en **ambas** capas a la vez — `Mono.when(l1.put(...),
  l2.put(...))` — de modo que la L2 compartida se actualiza en el momento en que lo hace la
  L1 local. Una escritura nunca es solo local.
- En un `get`, lee primero la L1; en un fallo de L1 cae hacia la L2, y si la L2 tiene el
  valor **rellena la L1** (backfill) para que la siguiente lectura local sea un acierto
  rápido.

Esto no es una paráfrasis — es la implementación real del adaptador. El método `get` lee
primero `l1` y solo consulta `l2` ante un `Optional` ausente, y `put` es literalmente
`Mono.when(l1.put(key, value), l2.put(key, value))`. Conceptualmente, esa ruta de lectura son
dos llamadas a `CacheAdapter` cosidas con `flatMap`:

```java
// Illustrative: the SmartCacheAdapter read path — L1, then L2, then backfill L1.
l1.get(key)
    .flatMap(hit -> hit.isPresent()
        ? Mono.just(hit)                          // L1 hit — done, no network
        : l2.get(key)                             // L1 miss — try the distributed L2
            .flatMap(l2hit -> backfillL1(key, l2hit)));  // populate L1 for next time
```

Sigue las tres rutas a través de esa única expresión, porque son las tres cosas que suceden
en producción:

- **Acierto de L1** (el caso común): una búsqueda en proceso, sin red, nanosegundos.
- **Fallo de L1, acierto de L2** (caché local fría, caché compartida caliente — p. ej. justo
  después de un reinicio): un viaje de ida y vuelta por red a Redis, luego un backfill para
  que la *siguiente* lectura local sea un acierto de L1.
- **Fallo de L1, fallo de L2** (genuinamente fría): un `Optional` vacío fluye hacia fuera, y
  tu lectura pasante carga desde el origen y hace `put` — que el write-through puebla en
  *ambas* capas, de modo que la siguiente lectura de L2 de cualquier otra instancia también
  acierta.

Habilitas todo esto sin tocar ese código. Elegir un `type` distribuido (o dejar que `AUTO`
elija uno) y tener el jar del adaptador en el classpath es suficiente; la autoconfiguración
ensambla el `SmartCacheAdapter` con Caffeine como L1 y tu proveedor como L2.

```yaml
firefly:
  cache:
    type: REDIS             # distributed L2; Caffeine becomes the L1 in front of it
    redis:
      host: redis.internal
      port: 6379
    default-ttl: 10m
```

!!! note "Término clave — caché write-through L1/L2"
    Una caché de **dos niveles** (L1/L2) empareja una caché local rápida (L1, Caffeine) con
    una caché distribuida compartida (L2, Redis o Hazelcast). **Write-through** significa que
    cada escritura va a *ambas* de inmediato, de modo que la capa compartida siempre está al
    día; **backfill de lectura** significa que un valor encontrado solo en L2 se copia hacia
    arriba a la L1 para que las lecturas locales posteriores sean rápidas. El
    `SmartCacheAdapter` implementa ambas, detrás del mismo puerto `CacheAdapter`, de modo que
    las dos capas son invisibles para tu manejador.

!!! warning "Las entradas de L1 pueden quedar brevemente obsoletas entre instancias"
    El write-through mantiene consistente la L2 *compartida*, pero la L1 local de cada
    instancia sigue expirando con su propio reloj. Tras una escritura en la instancia A, la
    L1 de la instancia B puede servir el valor antiguo hasta que el TTL de su entrada
    transcurra. Ese es el compromiso deliberado por la velocidad de L1 — así que mantén
    cortos los TTL de L1 para datos que varias instancias cachean y mutan, o desaloja según
    el evento que los cambió (véase «invalidación», más abajo). Nunca supongas que una
    escritura por debajo del segundo es visible al instante en todos los nodos.

## CacheType.AUTO elige el proveedor por ti

Rara vez quieres codificar `REDIS` a fuego en cada servicio y cada entorno. Pon `type: AUTO`
y Firefly selecciona el mejor proveedor realmente disponible en tiempo de ejecución, en un
orden de prioridad fijo:

```yaml
firefly:
  cache:
    type: AUTO   # Redis > Hazelcast > JCache > Caffeine > No-Op, by what's on the classpath
```

`AUTO` resuelve **Redis, luego Hazelcast, luego JCache, luego Caffeine, luego No-Op** — el
primero cuyo adaptador esté presente y configurado gana, y Caffeine es el suelo, así que
siempre obtienes *una* caché incluso sin infraestructura distribuida. (Este es el orden
documentado en el propio `CacheType.AUTO`.) Esto es lo que permite que un servicio se ejecute
sobre Caffeine puro en los tests de un desarrollador y sobre Redis en producción *con el
mismo valor de configuración* — el entorno suministra el jar, y `AUTO` hace el resto. La
matriz completa de proveedores y la dependencia de cada uno vive en el apéndice B.

Vale la pena explicitar dos consecuencias prácticas. Primera, **`No-Op` es un proveedor real
y seleccionable**, no un fallback degenerado a «nada de caché en absoluto que rompa tu
código». Un adaptador `NOOP` implementa el puerto `CacheAdapter` completo pero siempre
devuelve un fallo — de modo que una lectura pasante sigue funcionando, simplemente nunca
acierta. Eso significa que puedes desactivar la caché *por completo* en un test o una sesión
de depuración sin eliminar una sola línea de código de caché: el comportamiento es correcto,
solo desaparece la optimización. Segunda, `AUTO` es un mecanismo de *promoción*, no una
negociación en tiempo de ejecución — el proveedor se elige una vez al arranque según el
classpath y la configuración, y luego queda fijo. No hay una comprobación por petición de
«¿está Redis levantado?»; si se seleccionó Redis y luego se viene abajo, para eso está la
mitad de *resiliencia* de este capítulo.

!!! spring "Equivalente en Spring"
    La propia abstracción `@Cacheable`/`CacheManager` de Spring es **bloqueante** — se
    construyó para la pila de servlets y envuelve una `Cache` síncrona. En WebFlux eso es una
    trampa: un método `@Cacheable` que sale hacia Redis bloquea el bucle de eventos. El
    `CacheAdapter` de Firefly es el reemplazo reactivo — devuelve `Mono` de extremo a extremo
    — además de la selección de proveedor (`AUTO`) y la composición L1/L2 que Spring puro te
    deja ensamblar a mano. Sigues siendo libre de usar la caché de Spring para rutas de
    código bloqueantes; en la ruta reactiva, usa el puerto.

## Caché de consultas CQRS: adhesión con un atributo

Ya conociste el lado de lectura del bus en el capítulo 10 — un `@QueryHandlerComponent` que
el `QueryBus` descubre y al que despacha. Cachear el resultado de una consulta **no**
significa escribir nada de la fontanería de lectura pasante de la primera sección. El bus de
consultas lo hace por ti; tú solo declaras que los resultados de un manejador son cacheables
y cuánto viven.

El manejador en el reactor hoy se desadhiere — bueno, cachea con un TTL *por defecto* pero la
porción nunca conecta un `CacheAdapter`, así que el bus no tiene nada *en lo que* cachear.
Aquí está el manejador real, actual, literal — tu punto de partida para los ejercicios:

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/handler/GetApplicationStatusHandler.java | Listado 20.1 — el manejador de consultas sin caché tal como está hoy
package com.firefly.lumen.domain.handler;

import com.firefly.lumen.domain.query.GetApplicationStatusQuery;
import org.fireflyframework.cqrs.annotations.QueryHandlerComponent;
import org.fireflyframework.cqrs.query.QueryHandler;
import reactor.core.publisher.Mono;

/**
 * Query handler for {@link GetApplicationStatusQuery}.
 *
 * <p>The read-side counterpart to the command handlers: discovered via
 * {@code @QueryHandlerComponent} and dispatched through the
 * {@link org.fireflyframework.cqrs.query.QueryBus}. The slice keeps the read model trivial —
 * once an application id exists it is reported as {@code REGISTERED} — to demonstrate the
 * command/query split without pulling in a projection store.
 */
@QueryHandlerComponent
public class GetApplicationStatusHandler extends QueryHandler<GetApplicationStatusQuery, String> {

    @Override
    protected Mono<String> doHandle(GetApplicationStatusQuery query) {
        return Mono.just("REGISTERED");
    }
}
:::

Lee los valores por defecto de la anotación con precisión, porque son sutiles.
`@QueryHandlerComponent` declara `cacheable()` con valor por defecto `true` y `cacheTtl()` con
valor por defecto `-1`. Una anotación *desnuda*, entonces, dice «esta consulta es cacheable
en principio, pero sin TTL establecido». La clase base `QueryHandler` lee eso de vuelta a
través de `supportsCaching()` y calcula el TTL efectivo: solo cachea cuando `cacheTtl() > 0`.
Así que el manejador de arriba, con un TTL por defecto de `-1` y sin `CacheAdapter`
configurado en la porción, no cachea nada — `doHandle` se ejecuta en cada despacho. Para
activar de verdad la caché estableces un TTL positivo:

```java
// Illustrative: cache this query's results for five minutes.
@QueryHandlerComponent(cacheable = true, cacheTtl = 300)
public class GetApplicationStatusHandler
        extends QueryHandler<GetApplicationStatusQuery, String> {

    @Override
    protected Mono<String> doHandle(GetApplicationStatusQuery query) {
        return Mono.just("REGISTERED");   // doHandle runs only on a cache miss
    }
}
```

Con eso — *y* un `CacheAdapter` en el classpath — el bus consulta la caché configurada
*antes* de invocar `doHandle`. La compuerta dentro de `DefaultQueryBus` son exactamente tres
condiciones, todas las cuales deben cumplirse:

```java
// Illustrative: the bus's caching gate (from DefaultQueryBus).
if (cacheAdapter != null && query.isCacheable() && handler.supportsCaching()) {
    // ... consult the cache, then fall through to doHandle on a miss
}
```

- `cacheAdapter != null` — hay de verdad un proveedor de caché configurado. (En la porción no
  lo hay, que es por lo que la anotación desnuda de arriba es inofensiva.)
- `query.isCacheable()` — el *objeto de consulta* se declara cacheable a sí mismo.
- `handler.supportsCaching()` — la anotación del *manejador* lo habilita con un TTL positivo.

En un acierto devuelve el valor cacheado y tu manejador nunca se ejecuta; en un fallo ejecuta
`doHandle`, almacena el resultado bajo la clave de caché de la consulta durante `cacheTtl`
segundos, y lo devuelve. La clave de caché proviene del propio objeto de consulta —
`query.getCacheKey()`, derivada de los parámetros de la consulta — de modo que dos consultas
con los mismos parámetros comparten una entrada y dos con parámetros distintos no.

Esta es la recompensa de la separación CQRS del capítulo 10: como las lecturas fluyen a
través de un bus, el bus es el único sitio donde añadir caché, y un único atributo de
anotación la enciende para cualquier lectura de la flota. Ningún manejador acumula código de
caché; la capacidad vive en el bus. También viste esta misma línea confirmada en el arranque
en el capítulo 2 — el log de `CqrsAutoConfiguration` te dijo que el bus de consultas se
conectó con soporte de caché:

```text
{"timestamp":"2026-06-17T08:21:44.077+0000","message":"CQRS Query Bus configured with cache support via fireflyframework-cache","logger":"o.f.c.config.CqrsAutoConfiguration","level":"INFO"}
```

Esa línea es el bus anunciando que `cacheAdapter != null` — la primera de las tres
condiciones de la compuerta se satisface en el arranque. (Cuando `fireflyframework-cache`
está ausente, la misma configuración registra `configured without cache support` en su
lugar, y la primera condición de la compuerta nunca puede cumplirse.)

!!! note "Término clave — caché de resultados de consultas"
    Un `@QueryHandlerComponent(cacheable = true, cacheTtl = N)` (con `N > 0`) le dice al
    `QueryBus` que cachee el resultado del manejador durante `N` segundos, indexado por los
    parámetros de la consulta. El bus cortocircuita al valor cacheado en un acierto, de modo
    que `doHandle` se ejecuta solo en un fallo. Es la contraparte declarativa, del lado de
    lectura, del patrón manual de lectura pasante mostrado antes — misma caché, sin
    fontanería.

!!! warning "Cacheable es un valor por defecto; un TTL positivo es el interruptor real"
    `cacheable` tiene valor por defecto `true` y `cacheTtl` tiene valor por defecto `-1`, así
    que un `@QueryHandlerComponent` *desnudo* es «cacheable, sin TTL» — lo que el bus trata
    como no cachear, porque `supportsCaching()` requiere `cacheTtl > 0`. No interpretes una
    anotación desnuda como «los resultados se están cacheando». El interruptor visible es el
    `cacheTtl` positivo; la tercera precondición (un `CacheAdapter` configurado) es invisible
    en el código y la suministra el classpath.

### Mantener honesta una lectura cacheada: invalidación

Una caché vale tan solo lo que vale su desalojo. Un estado cacheado durante cinco minutos es
incorrecto en el instante en que el lado de escritura lo cambia — a menos que algo le diga a
la caché que lo olvide. El capítulo 11 mostró el puente que hace exactamente esto:
`@InvalidateCacheOn` ata una consulta cacheada a los eventos de dominio que la dejan obsoleta,
de modo que una escritura que publica `LoanApplicationRegisteredEvent` desaloja
automáticamente las entradas cacheadas coincidentes.

```java
// Illustrative: evict this query's cache when the matching event fires.
@QueryHandlerComponent(cacheable = true, cacheTtl = 300)
@InvalidateCacheOn(eventTypes = "LoanApplicationRegisteredEvent")
public class GetApplicationStatusHandler
        extends QueryHandler<GetApplicationStatusQuery, String> {
    // cached results are evicted whenever a LoanApplicationRegisteredEvent arrives
}
```

El atributo `eventTypes` es un `String[]`, de modo que una consulta que varios eventos pueden
dejar obsoleta los lista todos — `@InvalidateCacheOn(eventTypes = {"LoanApplicationRegisteredEvent",
"LoanApplicationWithdrawnEvent"})`. La llegada de cualquiera de los dos eventos desaloja la
entrada.

Este es el matrimonio del TTL y la invalidación por eventos, y vale la pena ser explícito
sobre por qué quieres *ambos*. El TTL es la *red de seguridad*: aunque un evento de
invalidación llegue a perderse o nunca se modele, la entrada no puede estar equivocada
durante más de `cacheTtl` segundos. El evento es la *precisión*: en el caso común el modelo
de lectura se auto-sana en el instante en que el lado de escritura cambia algo, mucho antes
de que el TTL hubiera transcurrido. Juntos te dan «fresco en milisegundos en la práctica, y
demostrablemente nunca más obsoleto que `N` segundos en el peor caso» — una garantía mucho
más fuerte que cualquiera de los dos por separado.

Recuerda la regla del capítulo 11 — `eventTypes` coincide con el **nombre de clase simple**
del payload, no con la cadena lógica con puntos del productor — porque `@InvalidateCacheOn`
se indexa sobre el mismísimo runtime de EDA y la mismísima regla de coincidencia. Así que es
`"LoanApplicationRegisteredEvent"`, nunca `"loanApplication.registered"`.

!!! note "Término clave — invalidación de caché"
    La **invalidación** es eliminar (desalojar) una entrada cacheada para que la siguiente
    lectura la recompute. Firefly ofrece dos disparadores complementarios: *tiempo* (el
    `cacheTtl`, que expira cada entrada con un reloj) y *evento* (`@InvalidateCacheOn`, que
    desaloja en el momento en que llega un evento de dominio coincidente). El tiempo acota el
    peor caso; los eventos entregan frescura en el caso común. Los problemas difíciles de la
    caché son casi todos problemas de invalidación — que es por lo que el framework hace
    declarativa la ruta de eventos.

## Resiliencia: sobrevivir a las llamadas que no puedes cachear

La caché elimina viajes de ida y vuelta; la resiliencia gobierna los que quedan. Cuando el
dominio de originación llama al core mediante un SDK generado (capítulo 7), o cualquier capa
llama a otra a través del `ServiceClient` unificado (capítulo 16) — exactamente la ruta viva
`exp → domain → core` que el README captura, donde el paso raíz de la saga escribe en el core
sobre HTTP — esa llamada puede ir lenta, ser inestable o estar directamente caída. Firefly
envuelve cada una de esas llamadas en tres patrones de Resilience4j, aplicados por ti y
configurados con propiedades `firefly.*`:

- **Circuit breaker** — después de que la tasa de fallo de un aguas abajo cruza un umbral, el
  breaker se *abre* y falla rápido durante una ventana de enfriamiento en lugar de apilar
  miles de peticiones sobre un servicio que ya está sufriendo. Luego se semiabre para probar
  la recuperación con unas pocas llamadas de prueba antes de cerrarse de nuevo.
- **Retry (reintento)** — los fallos transitorios (una conexión caída, un `503`) se
  reintentan un número acotado de veces con backoff, de modo que un parpadeo momentáneo no
  aflora como un error.
- **Bulkhead** — el número de llamadas concurrentes en vuelo hacia un aguas abajo se limita,
  de modo que una dependencia lenta no puede consumir todos los hilos y arrastrar consigo a
  las llamadas a servicios *sanos*.

¿Por qué los tres, y no solo el reintento? Porque defienden contra modos de fallo
*diferentes*, y cada uno hace seguros a los otros. El reintento por sí solo, ante un aguas
abajo que está genuinamente caído, convierte una petición fallida en tres — *amplifica* la
carga sobre un servicio enfermo en el peor momento posible. El circuit breaker es lo que
detiene esa amplificación: una vez que la tasa de fallo es claramente mala, se abre y los
reintentos nunca se disparan. El bulkhead defiende un tercer eje por completo — no la *tasa*
de fallo sino la *concurrencia*: un aguas abajo que es meramente *lento* (no que falla)
acumulará, sin un bulkhead, llamadas en vuelo hasta que cada hueco disponible esté esperándolo,
y una dependencia lenta se convierte en una caída total. El bulkhead limita ese recuento en
vuelo de modo que la lentitud queda contenida. Juntos cubren el fallo rápido, la recuperación
de transitorios y el aislamiento de dependencias lentas — las tres maneras en que un aguas
abajo te hace daño.

Ajustas los tres bajo las propiedades del cliente. El framework trae valores por defecto
sensatos — un umbral de tasa de fallo del 50% sobre una ventana deslizante de 10 llamadas, un
mínimo de 5 llamadas antes de que el breaker pueda saltar, un estado abierto de 60 segundos,
3 llamadas permitidas en semiabierto, y 3 intentos de reintento con 500 ms de backoff — de
modo que el comportamiento es correcto antes de que configures nada. (Estos son los valores
por defecto literales en `CircuitBreakerConfig`: `failureRateThreshold = 50.0`,
`slidingWindowSize = 10`, `minimumNumberOfCalls = 5`, `waitDurationInOpenState = 60s`,
`permittedNumberOfCallsInHalfOpenState = 3`.)

```yaml
firefly:
  service-client:
    circuit-breaker:
      enabled: true
      failure-rate-threshold: 50          # open at a 50% failure rate
      sliding-window-size: 10             # measured over the last 10 calls
      minimum-number-of-calls: 5          # ...but only after at least 5
      wait-duration-in-open-state: 60s    # stay open this long, then half-open
      permitted-number-of-calls-in-half-open-state: 3
    retry:
      enabled: true
      max-attempts: 3                     # the call, plus up to 2 retries
      wait-duration: 500ms
      exponential-backoff-multiplier: 2.0
```

El `minimum-number-of-calls` merece una segunda mirada, porque es la propiedad que impide que
el breaker sea nervioso. Sin él, un único fallo temprano en un servicio recién arrancado se
leería como una tasa de fallo del 100% y haría saltar el breaker con una sola llamada mala. El
mínimo dice «no *evalúes* siquiera la tasa de fallo hasta que tengas una muestra
estadísticamente significativa» — cinco llamadas, por defecto — de modo que un parpadeo
transitorio solitario durante el calentamiento no abre el circuito. El umbral y la ventana
deciden *cuándo* saltar; el mínimo decide cuándo hay evidencia suficiente para decidir
siquiera.

### La resiliencia como operadores de Reactor, no guardas bloqueantes

La parte crucial — la razón por la que esto encaja en absoluto en la pila reactiva — es
*cómo* se aplican esos patrones. **No** son un `try`/`catch` bloqueante alrededor de una
llamada síncrona. Cada uno es un operador en la cadena reactiva: el breaker, el reintento y
el bulkhead envuelven todos una operación que devuelve `Mono` y devuelven un `Mono`, de modo
que la protección se compone en la misma tubería no bloqueante que todo lo demás. El
`CircuitBreakerManager` de Firefly, por ejemplo, expone
`executeWithCircuitBreaker(name, Supplier<Mono<T>>)` — *difiere* el trabajo protegido detrás
de un `Supplier` y sustituye una señal de error cuando el breaker está abierto, el análogo
reactivo de fallar rápido:

```java
// Illustrative: the breaker is a Mono operator — it defers the call and
// emits onError(CircuitBreakerOpenException) instead of blocking when open.
Mono<RateCard> guarded = circuitBreaker.executeWithCircuitBreaker(
        "pricing-core",
        () -> pricingClient.rateFor(productId));   // the guarded operation, a Supplier<Mono>
```

El `Supplier<Mono<T>>` es el detalle que soporta todo el peso. Un argumento `Mono` simple ya
habría *empezado* a ensamblar la llamada para cuando el breaker inspeccionara su estado;
pasar un `Supplier` significa que el breaker decide *primero* — «¿estoy abierto?» — e invoca
el supplier (y por tanto solo toca el aguas abajo) únicamente cuando la respuesta es
«cerrado». Cuando el breaker está abierto, el supplier nunca se llama y el aguas abajo nunca
se toca; el breaker devuelve `Mono.error(new CircuitBreakerOpenException(...))` directamente.
Eso es lo que «fallar rápido» significa concretamente en la pila reactiva: no un hilo
bloqueado esperando un timeout, sino una señal de error inmediata que nunca abandona la JVM.

Como el resultado es un `Mono`, la *recuperación* son simplemente los operadores de error de
Reactor que ya aprendiste en el capítulo 5. Cuando el breaker está abierto o se agota cada
reintento, retrocedes con un fallback usando `onErrorResume` — un tarifario cacheado, un valor
por defecto conservador — en lugar de propagar el fallo al llamante:

```java
// Illustrative: combine resilience with a graceful fallback, reactively.
pricingClient.rateFor(productId)                       // ServiceClient applies CB + retry + bulkhead
    .timeout(Duration.ofSeconds(2))                    // bound the wait
    .onErrorResume(CircuitBreakerOpenException.class,
        ex -> cache.<String, RateCard>get(productId)   // breaker open → serve last good value
            .flatMap(Mono::justOrEmpty)
            .switchIfEmpty(Mono.just(RateCard.conservativeDefault())));
```

Recorre esa cadena de arriba abajo, porque es todo el capítulo en cinco líneas. La llamada al
`ServiceClient` ya lleva el breaker, el reintento y el bulkhead. `timeout` acota cuánto vas a
esperar en absoluto — un aguas abajo que se cuelga es simplemente un fallo lento, y `timeout`
lo convierte en un `onError` del que puedes reanudar. `onErrorResume` captura la señal de
breaker-abierto y *reanuda el flujo* con un fallback en lugar de salir con error. El fallback
lee la caché; `Mono::justOrEmpty` colapsa el `Optional` de modo que un fallo de caché se
convierte en una señal vacía; y `switchIfEmpty` suministra un valor por defecto conservador
cuando incluso la caché está fría. Tres operadores, tres degradaciones elegantes, sin
bloqueo, sin `try`/`catch`.

Aquí es donde la caché y la resiliencia se encuentran: una caché es a menudo el *fallback* al
que reanuda una llamada resiliente. El breaker te impide martillear un aguas abajo enfermo; la
caché te permite seguir respondiendo — con un valor ligeramente obsoleto pero seguro —
mientras se recupera. Las dos mitades de este capítulo no son temas separados que casualmente
comparten una página; son dos extremos de la misma tubería.

!!! spring "Equivalente en Spring"
    Resilience4j es una biblioteca estándar de Spring Boot, y podrías anotar métodos con
    `@CircuitBreaker`, `@Retry` y `@Bulkhead` tú mismo en cualquier aplicación Spring. La
    contribución de Firefly es doble: conecta esos patrones en el `ServiceClient` unificado
    (capítulo 16) de modo que *cada* llamada a un aguas abajo está protegida sin anotaciones
    por llamada, y los ajusta mediante propiedades `firefly.service-client.*` de alcance de
    flota con valores por defecto conscientes del entorno — de modo que el décimo servicio
    abre circuitos y reintenta exactamente igual que el primero, en lugar de que cada equipo
    rederive los umbrales.

!!! warning "Reintenta solo lo que es seguro reintentar"
    Los reintentos multiplican la carga y pueden duplicar efectos secundarios. Reemitir un
    `GET` es inocuo; reemitir un `POST` no idempotente puede crear dos solicitudes de
    préstamo. Por eso el filtro de idempotencia del capítulo 6 (`X-Idempotency-Key`) y el
    reintento son socios: reintenta la lectura con libertad, pero haz idempotente cada
    escritura reintentada de modo que un reintento deduplique en lugar de hacer una doble
    reserva. En el flujo vivo del reactor esto es concreto — el paso raíz
    `registerLoanApplication` de la saga escribe en el core sobre HTTP, y el
    `DELETE /api/v1/loan-applications/{id}` del core (la ruta de compensación de la saga) es
    deliberadamente idempotente de modo que una compensación reintentada no pueda fallar sobre
    una fila ya eliminada. Configura el reintento para que se dispare ante fallos
    transitorios y *seguros* — timeouts y `5xx` — no ante cada error.

## Aquí no hay test acompañante — y por qué eso está bien

Los capítulos anteriores terminaban con `mvn ... test` y un recuento en verde, porque
extraían código real y verificado del reactor. Este no lo hace, y eso es honestidad
deliberada: la porción de originación de Lumen Lending nunca conecta una caché, y sus 33
tests (core 18, dominio 6, exp 9) se ejecutan contra valores por defecto en proceso, sin
Redis y sin circuit breaker que ejercitar. La única pieza que este capítulo *sí* extrae
literalmente — el Listado 20.1 — es el manejador *sin caché*, precisamente para mostrarte el
punto de partida honesto. La caché es una optimización de producción que la muestra no
necesita, y atornillarla solo para tener algo que aseverar te enseñaría una caché que nunca
mantendrías.

Vale la pena ser preciso sobre cuáles de las afirmaciones de este capítulo son *código
ejecutable verificado* y cuáles son *dónde-encaja*. El puerto `CacheAdapter`, el
comportamiento de write-through/backfill del `SmartCacheAdapter`, el orden de prioridad de
`CacheType`, los valores por defecto de las anotaciones `cacheable`/`cacheTtl`, la compuerta
de tres condiciones del bus, el `String[] eventTypes` de `@InvalidateCacheOn`, la firma del
`CircuitBreakerManager` y su `CircuitBreakerOpenException`, y los valores por defecto de la
configuración de Resilience4j son todos tipos y comportamientos reales del framework,
nombrados exactamente como el framework los nombra — puedes abrirlos en el classpath hoy
mismo. Lo que el *reactor* no hace es conectar ninguno de ellos en la porción de préstamos:
ni Redis, ni un breaker disparado en un test, ni desalojo dirigido por eventos de una
proyección real. El flujo vivo `exp → domain → core` demuestra que la costura del
`ServiceClient` existe y porta llamadas HTTP reales; no ejercita la apertura del breaker ni
una caché sirviendo un fallback.

Así que trata este capítulo como el mapa, no como el territorio — los reales `CacheAdapter`,
`CacheType`, `SmartCacheAdapter`, los atributos `cacheable`/`cacheTtl`, y las propiedades de
Resilience4j son todos exactamente como se nombran aquí, listos en el framework en el momento
en que tu servicio los necesite. Los ejercicios de abajo cierran la brecha: añadirás una
caché a una consulta caliente genuina del reactor y observarás cómo la lectura cortocircuita,
convirtiendo el mapa en código que se ejecuta.

## Lo que has aprendido {.recap}

- El puerto reactivo **`CacheAdapter`** devuelve `Mono<Optional<V>>`, de modo que un acierto
  y un fallo de caché son señales corrientes y una lectura pasante se compone con `flatMap` —
  sin bloqueo, sin comprobaciones de null. `put` devuelve `Mono<Void>` de modo que una
  escritura es parte de la misma tubería.
- **Caffeine** es la **L1** integrada, **acotada** (`maximum-size` + TTL), en proceso, que no
  necesita infraestructura. Una **L2** distribuida (Redis o Hazelcast) se sitúa detrás de ella
  mediante el **`SmartCacheAdapter`**, que escribe en ambas capas (write-through) con
  `Mono.when(l1.put, l2.put)` y rellena la L1 (backfill) en un acierto de L2 — todo detrás del
  mismo puerto.
- **`CacheType.AUTO`** elige el proveedor en tiempo de ejecución — Redis, luego Hazelcast,
  luego JCache, luego Caffeine, luego No-Op — de modo que un valor de configuración se
  ejecuta sobre Caffeine en los tests y sobre Redis en producción (matriz de proveedores en el
  apéndice B). `No-Op` es un adaptador real y seleccionable que siempre falla, no un fallback
  roto.
- La **caché de consultas** CQRS es el par de atributos
  `@QueryHandlerComponent(cacheable = true, cacheTtl = N)`, pero el interruptor real es un
  **`cacheTtl` positivo** (el valor por defecto `-1` no cachea nada); el bus cachea solo
  cuando se cumplen **las tres** condiciones `cacheAdapter != null`, `query.isCacheable()` y
  `handler.supportsCaching()`. `@InvalidateCacheOn` (un `String[] eventTypes`) desaloja ante
  el evento de dominio coincidente por **nombre de clase simple** — el TTL como red de
  seguridad, el evento como precisión.
- La **resiliencia** — circuit breaker, reintento y bulkhead de Resilience4j — la aplica el
  `ServiceClient` unificado (capítulo 16) como **operadores de Reactor**, no guardas
  bloqueantes. `executeWithCircuitBreaker(name, Supplier<Mono<T>>)` difiere la llamada detrás
  de un `Supplier` y emite `CircuitBreakerOpenException` cuando está abierto, de modo que la
  recuperación son simplemente `onErrorResume`/`switchIfEmpty`/`timeout` — y una caché hace un
  fallback natural.

## Pruébalo tú mismo {.exercises}

Estos ejercicios editan el reactor real bajo `samples/lumen-lending`, partiendo del
`GetApplicationStatusHandler` sin caché del Listado 20.1 (el módulo de dominio).

1. **Cachea una consulta caliente.** En `GetApplicationStatusHandler`, cambia el
   `@QueryHandlerComponent` desnudo por
   `@QueryHandlerComponent(cacheable = true, cacheTtl = 60)`. Ejecuta los tests del módulo de
   dominio (`mvn -q -pl domain-lending-loan-origination test`) y confirma que los **6** siguen
   pasando — cachear una consulta determinista no cambia el resultado para nadie, que es
   exactamente el punto: es una optimización transparente. (Observa que sin un `CacheAdapter`
   en el classpath la primera condición de la compuerta del bus sigue fallando, así que esto
   demuestra que la anotación es *inofensiva*, no aún *efectiva* — el ejercicio 4 suministra
   la condición que falta.)
2. **Demuestra el cortocircuito.** Añade un contador (un `AtomicInteger`) que el manejador
   incremente dentro de `doHandle`, luego escribe un test que despache la *misma* consulta dos
   veces a través del `QueryBus` y aserte que el contador se incrementó solo **una vez** —
   prueba de que la segunda lectura se sirvió desde la caché y `doHandle` nunca se ejecutó.
   (Necesitarás un bean `CacheAdapter` en el contexto del test para que se cumpla la primera
   condición de la compuerta; el valor por defecto Caffeine en proceso es suficiente.)
3. **Conecta la invalidación.** Añade `@InvalidateCacheOn(eventTypes =
   "LoanApplicationRegisteredEvent")` al manejador ahora cacheable. En una frase, explica
   (usando la regla del capítulo 11) por qué `eventTypes` debe ser el nombre de clase simple y
   no `"loanApplication.registered"` — y por qué mantienes el `cacheTtl` incluso con el evento
   conectado (pista: el TTL es la red de seguridad, el evento es la precisión).
4. **Elige un proveedor con AUTO.** En `application.yml`, pon `firefly.cache.type: AUTO` y
   razona a qué proveedor resuelve en el perfil de test (sin jar de Redis en el classpath)
   frente a un perfil de producción que añade `fireflyframework-cache-redis`. Confirma tu
   respuesta contra el orden de prioridad en `CacheType.AUTO` y en el apéndice B. ¿A qué
   resuelve `AUTO` si *nada* está en el classpath, y por qué eso sigue siendo seguro para tu
   código de lectura pasante?
5. **Diseña un fallback resiliente.** Esboza (no hace falta ejecutarlo) una llamada de
   `ServiceClient` al core de tarificación que, ante una `CircuitBreakerOpenException`,
   reanude a un `RateCard` cacheado y solo entonces a un valor por defecto conservador.
   Identifica qué operador maneja cada paso — `timeout`, `onErrorResume`, `switchIfEmpty` — y
   dónde encaja la lectura de caché. Luego explica por qué la operación protegida debe pasarse
   como un `Supplier<Mono<T>>` y no como un `Mono` ya construido.

## Adónde ir ahora

La caché y la resiliencia mantienen un servicio rápido y en pie; lo siguiente que necesitas
es *ver* cómo lo hace. El capítulo 21 se vuelca hacia la observabilidad — las métricas, las
trazas, y la propagación funcional del contexto de Reactor (el `traceId`/`spanId` que viste
decorar cada línea de log en el capítulo 2) que hacen visibles una tasa de aciertos de caché,
un breaker disparado o una tormenta de reintentos a lo largo de la flota, de modo que el
comportamiento que configuraste aquí sea algo que puedas observar de verdad en producción.
