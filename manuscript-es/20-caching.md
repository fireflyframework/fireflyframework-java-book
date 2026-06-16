Un servicio de originación de préstamos pasa la mayor parte de su tiempo respondiendo a las mismas preguntas.
"¿Cuál es el estado de esta solicitud?" "¿Cuál es el tarifario de este producto?"
"¿Está este solicitante en la lista de vigilancia?" Los datos que hay detrás de esas respuestas cambian despacio,
pero las preguntas llegan sin parar — y cada una que viaja hasta el final hasta la
base de datos, o peor aún hasta un servicio central descendente, cuesta una ida y vuelta que no necesitabas
pagar. El caching es la forma de dejar de pagarla. La resiliencia es la forma de sobrevivir a las idas y vueltas
que *no* puedes evitar cuando lo que hay al otro lado es lento o está caído.

Firefly trata ambas como capacidades transversales, cableadas igual que todo lo
demás en este libro: un puerto, un adaptador por defecto y una propiedad para intercambiarlo. La caché es
un `CacheAdapter` reactivo con Caffeine integrado y una caché distribuida L2 a una dependencia
de distancia. La resiliencia es un circuit breaker, un retry y un bulkhead de Resilience4j que el
`ServiceClient` unificado (Capítulo 16) aplica por ti — expresados mediante operadores
de Reactor, nunca un `try`/`catch` bloqueante. Te suscribes al caching de consultas con un único
atributo de anotación, y ajustas la resiliencia con propiedades `firefly.*`.

Una nota sincera antes de empezar. **Este capítulo es conceptual.** El slice de originación
de Lumen Lending que has estado construyendo *no* cablea una caché — su modelo de lectura es
deliberadamente trivial, y sus pruebas se ejecutan contra valores por defecto en proceso, sin Redis ni
dramas de circuit breaker. Así que aquí no hay una prueba acompañante que ejecutar en verde. En cambio,
cada fragmento de abajo es *ilustrativo*: muestra los tipos y propiedades reales de Firefly,
fundamentados en el código fuente del framework, y señala exactamente dónde se conecta cada uno en el
servicio que ya tienes. Los ejercicios luego te hacen añadir una caché a una consulta caliente real
en el reactor — `GetApplicationStatusHandler` — para que termines el capítulo con
código funcionando aunque el propio capítulo no entregue ninguno.

## El puerto CacheAdapter

Todo empieza en una sola interfaz, `org.fireflyframework.cache.core.CacheAdapter`.
Es reactiva hasta el núcleo — cada operación devuelve un `Mono`, de modo que una consulta a la caché
se compone en la cadena de un manejador exactamente igual que una llamada a un repositorio, y un *fallo* de caché
es simplemente un `Optional` vacío, nunca una comprobación de nulo bloqueante.

```java
public interface CacheAdapter {
    <K, V> Mono<Optional<V>> get(K key);
    <K, V> Mono<Optional<V>> get(K key, Class<V> valueType);
    <K, V> Mono<Void>        put(K key, V value);
    <K, V> Mono<Void>        put(K key, V value, Duration ttl);
    <K, V> Mono<Boolean>     evict(K key);
    // ...plus clear, exists, keys, stats, health
}
```

La forma importa. Como `get` devuelve `Mono<Optional<V>>`, un valor presente y uno
ausente son ambos señales *normales* — un `Optional` poblado o uno vacío — y
ramificas sobre ellos con operadores corrientes. Un patrón read-through, el que más
sueles usar, sale de forma natural:

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
`Mono`. Esa es la razón completa por la que el puerto es reactivo en lugar de una API estilo `Map`
sin más: en la pila reactiva una caché que se bloquea para recuperar de Redis pararía el
bucle de eventos con tanta seguridad como lo haría una consulta bloqueante.

!!! note "Termino clave — CacheAdapter (el puerto de caché)"
    `CacheAdapter` es la única interfaz reactiva que implementa todo proveedor de caché —
    Caffeine, Redis, Hazelcast, JCache, Postgres. Tu código depende del puerto, nunca
    del cliente de un proveedor, así que el proveedor es una decisión de despliegue (una dependencia más una
    propiedad), no una decisión de código. Este es el mismo patrón hexagonal que el
    `EventPublisher` de EDA del Capítulo 11 y el `IdpAdapter` de IDP del Capítulo 19.

## Caffeine es la L1 integrada

Añade la capacidad de caché y obtienes una caché funcionando de inmediato, sin
infraestructura: **Caffeine**, una caché en proceso de alto rendimiento, es el valor por defecto
integrado. Vive dentro de tu JVM, así que un acierto es una llamada a un método — nanosegundos, sin red
— y no necesita nada ejecutándose junto al servicio. Para una sola instancia, o para
datos que cada instancia puede cachear de forma independiente, Caffeine por sí sola suele ser toda la
respuesta.

```yaml
firefly:
  cache:
    type: CAFFEINE          # the built-in, in-process L1 — zero infrastructure
    caffeine:
      maximum-size: 10000   # bounded so it can never exhaust the heap
      expire-after-write: 10m
```

Caffeine está **acotada** por diseño — un número máximo de entradas y un tiempo de vida — de modo que un
espacio de claves desbocado nunca puede comerse el heap. Esa cota es también la única limitación de Caffeine:
es por instancia y volátil. Reinicia el servicio y la caché queda fría; ejecuta tres
instancias y cada una tiene su propia copia, que pueden discrepar durante todo un TTL. Cuando eso
importa, añades una capa distribuida detrás.

## Una L2 distribuida, write-through mediante SmartCacheAdapter

Pon una caché distribuida — Redis o Hazelcast — *detrás* de Caffeine y obtienes lo mejor
de ambos mundos: la velocidad de una L1 en proceso para los aciertos, y una L2 compartida que sobrevive a los reinicios
y es consistente entre instancias. Firefly compone las dos por ti con el
`SmartCacheAdapter`, que es a su vez un `CacheAdapter` (así que tu código nunca sabe que hay
dos capas) que envuelve una L1 y una L2.

Su política es **write-through con backfill en lectura**, y vale la pena entenderla
con precisión porque determina lo que tu código observa:

- En `put`, escribe en **ambas** capas a la vez — `Mono.when(l1.put(...),
  l2.put(...))` — de modo que la L2 compartida se actualiza en el momento en que lo hace la L1 local. Una escritura
  nunca es solo local.
- En `get`, lee primero la L1; ante un fallo de L1 cae a la L2, y si la L2 tiene el
  valor **rellena la L1** para que la siguiente lectura local sea un acierto rápido.

Conceptualmente, esa ruta de lectura son solo dos llamadas a `CacheAdapter` cosidas con `flatMap`:

```java
// Illustrative: the SmartCacheAdapter read path — L1, then L2, then backfill L1.
l1.get(key)
    .flatMap(hit -> hit.isPresent()
        ? Mono.just(hit)                          // L1 hit — done, no network
        : l2.get(key)                             // L1 miss — try the distributed L2
            .flatMap(l2hit -> backfillL1(key, l2hit)));  // populate L1 for next time
```

Habilitas todo esto sin tocar ese código. Elegir un `type` distribuido (o
dejar que `AUTO` elija uno) y tener el jar del adaptador en el classpath es suficiente; la
autoconfiguración ensambla el `SmartCacheAdapter` con Caffeine como L1 y tu
proveedor como L2.

```yaml
firefly:
  cache:
    type: REDIS             # distributed L2; Caffeine becomes the L1 in front of it
    redis:
      host: redis.internal
      port: 6379
    default-ttl: 10m
```

!!! note "Termino clave — caché write-through L1/L2"
    Una caché **de dos niveles** (L1/L2) empareja una caché local rápida (L1, Caffeine) con una caché
    distribuida compartida (L2, Redis o Hazelcast). **Write-through** significa que cada escritura va
    a *ambas* de inmediato, así que la capa compartida está siempre al día; **backfill en lectura** significa
    que un valor hallado solo en L2 se copia hacia arriba a L1 para que las lecturas locales posteriores sean rápidas.
    El `SmartCacheAdapter` implementa ambos, tras el mismo puerto `CacheAdapter`, de modo que las
    dos capas son invisibles para tu manejador.

!!! warning "Las entradas de L1 pueden quedar brevemente obsoletas entre instancias"
    El write-through mantiene consistente la L2 *compartida*, pero la L1 local de cada instancia sigue
    expirando con su propio reloj. Tras una escritura en la instancia A, la L1 de la instancia B puede servir el
    valor antiguo hasta que el TTL de su entrada caduque. Ese es el compromiso deliberado por la velocidad de la L1 —
    así que mantén TTL de L1 cortos para datos que varias instancias cachean y mutan, o invalida con
    el evento que los cambió (véase "Invalidación", más abajo). Nunca supongas que una escritura por debajo del
    segundo es instantáneamente visible en todos los nodos.

## CacheType.AUTO elige el proveedor por ti

Rara vez quieres codificar a mano `REDIS` en cada servicio y cada entorno. Establece
`type: AUTO` y Firefly selecciona el mejor proveedor realmente disponible en tiempo de ejecución,
en un orden de prioridad fijo:

```yaml
firefly:
  cache:
    type: AUTO   # Redis > Hazelcast > JCache > Caffeine > No-Op, by what's on the classpath
```

`AUTO` resuelve **Redis, luego Hazelcast, luego JCache, luego Caffeine, luego No-Op** — gana el
primero cuyo adaptador esté presente y configurado, y Caffeine es el suelo, así que
siempre obtienes *una* caché incluso sin infraestructura distribuida. Esto es lo que permite que un servicio
se ejecute con Caffeine a secas en las pruebas de un desarrollador y con Redis en producción *con el mismo
valor de configuración* — el entorno aporta el jar, y `AUTO` hace el resto. La
matriz completa de proveedores y la dependencia de cada uno está en el Apéndice B.

!!! spring "Equivalente en Spring"
    La propia abstracción `@Cacheable`/`CacheManager` de Spring es **bloqueante** — se construyó
    para la pila de servlets y envuelve una `Cache` síncrona. En WebFlux eso es una trampa: un
    método `@Cacheable` que llega hasta Redis bloquea el bucle de eventos. El
    `CacheAdapter` de Firefly es el reemplazo reactivo — devolviendo `Mono` de extremo a extremo — más la
    selección de proveedor (`AUTO`) y la composición L1/L2 que Spring a secas te deja
    ensamblar a mano. Sigues siendo libre de usar la caché de Spring para rutas de código bloqueantes;
    en la ruta reactiva, usa el puerto.

## Caching de consultas CQRS: suscríbete con un atributo

Ya conociste el lado de lectura del bus en el Capítulo 10 — un
`@QueryHandlerComponent` que el `QueryBus` descubre y al que despacha. Cachear el resultado de una
consulta **no** significa escribir nada del cableado read-through de la primera
sección. El bus de consultas lo hace por ti; tú solo declaras que los resultados de un manejador son
cacheables y cuánto viven.

El manejador en el reactor de hoy se desuscribe — es una anotación pelada:

```java
// In the reactor today: caching is not enabled on this handler.
@QueryHandlerComponent
public class GetApplicationStatusHandler
        extends QueryHandler<GetApplicationStatusQuery, String> {

    @Override
    protected Mono<String> doHandle(GetApplicationStatusQuery query) {
        return Mono.just("REGISTERED");
    }
}
```

Activar el caching son dos atributos — `cacheable` y `cacheTtl` (en segundos):

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

Con eso, el bus consulta el `CacheAdapter` configurado *antes* de invocar
`doHandle`. Ante un acierto devuelve el valor cacheado y tu manejador nunca se ejecuta; ante un fallo
ejecuta `doHandle`, almacena el resultado bajo la clave de caché de la consulta durante `cacheTtl`
segundos, y lo devuelve. La clave de caché proviene del propio objeto de consulta — el bus
comprueba `query.isCacheable()` y el `supportsCaching()` del manejador, ambos derivados de
esa anotación, antes de cachear nada — de modo que dos consultas con los mismos parámetros
comparten una entrada y dos con parámetros distintos no.

Esta es la recompensa de la división CQRS del Capítulo 10: como las lecturas fluyen a través de un bus,
el bus es el único lugar donde añadir caching, y un único atributo de anotación lo activa
para cualquier lectura de la flota. Ningún manejador acumula código de caché; la capacidad vive en el
bus.

!!! note "Termino clave — caching de resultados de consulta"
    Un `@QueryHandlerComponent(cacheable = true, cacheTtl = N)` le dice al `QueryBus` que
    cachee el resultado del manejador durante `N` segundos, indexado por los parámetros de la consulta. El bus
    cortocircuita al valor cacheado ante un acierto, así que `doHandle` se ejecuta solo ante un fallo. Es
    la contraparte declarativa, del lado de lectura, del patrón read-through manual mostrado
    antes — misma caché, sin cableado.

### Mantener honesta una lectura cacheada: invalidación

Una caché solo vale lo que vale su desalojo. Un estado cacheado durante cinco minutos es erróneo en el
instante en que el lado de escritura lo cambia — a menos que algo le diga a la caché que olvide.
El Capítulo 11 mostró el puente que hace exactamente esto: `@InvalidateCacheOn` ata una consulta
cacheada a los eventos de dominio que la dejan obsoleta, de modo que una escritura que publica
`LoanApplicationRegisteredEvent` invalida automáticamente las entradas cacheadas coincidentes.

```java
// Illustrative: evict this query's cache when the matching event fires.
@QueryHandlerComponent(cacheable = true, cacheTtl = 300)
@InvalidateCacheOn(eventTypes = "LoanApplicationRegisteredEvent")
public class GetApplicationStatusHandler
        extends QueryHandler<GetApplicationStatusQuery, String> {
    // cached results are evicted whenever a LoanApplicationRegisteredEvent arrives
}
```

El modelo de lectura se autorrepara: sirve respuestas cacheadas rápidas hasta que el lado de escritura cambia
algo, momento en el que el evento invalida la entrada y la siguiente lectura recalcula.
Recuerda la regla del Capítulo 11 — `eventTypes` coincide con el **nombre simple de clase** del payload,
no con la cadena lógica con puntos del productor — porque `@InvalidateCacheOn` se indexa sobre
el mismísimo runtime de EDA y la mismísima regla de coincidencia.

## Resiliencia: sobrevivir a las llamadas que no puedes cachear

El caching elimina idas y vueltas; la resiliencia gobierna las que quedan. Cuando el
dominio de originación llama al núcleo a través de un SDK generado (Capítulo 7), o cualquier capa llama
a otra mediante el `ServiceClient` unificado (Capítulo 16), esa llamada puede ser lenta, inestable
o estar caída por completo. Firefly envuelve cada una de esas llamadas en tres patrones de Resilience4j, aplicados
por ti y configurados con propiedades `firefly.*`:

- **Circuit breaker** — después de que la tasa de fallos de un servicio descendiente cruce un umbral, el
  breaker *se abre* y falla rápido durante una ventana de enfriamiento en lugar de apilar miles de
  peticiones sobre un servicio que ya está sufriendo. Luego se semiabre para probar la
  recuperación con unas pocas llamadas de prueba antes de cerrarse de nuevo.
- **Retry** — los fallos transitorios (una conexión caída, un `503`) se reintentan un
  número acotado de veces con backoff, de modo que un fallo momentáneo no aflora como un
  error.
- **Bulkhead** — el número de llamadas concurrentes en vuelo a un servicio descendiente está limitado, de modo que
  una dependencia lenta no pueda consumir todos los hilos y arrastrar consigo las llamadas a servicios
  *sanos*.

Ajustas los tres bajo las propiedades del cliente. El framework trae valores por defecto sensatos — un
umbral de tasa de fallos del 50% sobre una ventana deslizante de 10 llamadas, un estado abierto de 60 segundos,
y tres intentos de retry con 500 ms de backoff — de modo que el comportamiento es correcto antes de que
configures nada:

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

### La resiliencia como operadores de Reactor, no como guardas bloqueantes

La parte crucial — la razón por la que esto encaja en la pila reactiva siquiera — es *cómo* se aplican
esos patrones. **No** son un `try`/`catch` bloqueante alrededor de una llamada
síncrona. Cada uno es un operador en la cadena reactiva: el breaker, el retry y el bulkhead todos
envuelven una operación que devuelve `Mono` y devuelven un `Mono`, así que la protección se compone en
la misma tubería no bloqueante que todo lo demás. El circuit breaker de Firefly,
por ejemplo, aplaza el trabajo protegido y sustituye una señal de error cuando el breaker está
abierto — el análogo reactivo de fallar rápido:

```java
// Illustrative: the breaker is a Mono operator — it defers the call and
// emits onError(CircuitBreakerOpenException) instead of blocking when open.
Mono<RateCard> guarded = circuitBreaker.executeWithCircuitBreaker(
        "pricing-core",
        () -> pricingClient.rateFor(productId));   // the guarded operation, a Supplier<Mono>
```

Como el resultado es un `Mono`, la *recuperación* son simplemente los operadores de error de Reactor que
ya aprendiste en el Capítulo 5. Cuando el breaker está abierto o cada retry se ha agotado,
recurres a un plan B con `onErrorResume` — un tarifario cacheado, un valor por defecto conservador — en lugar de
propagar el fallo al llamante:

```java
// Illustrative: combine resilience with a graceful fallback, reactively.
pricingClient.rateFor(productId)                       // ServiceClient applies CB + retry + bulkhead
    .timeout(Duration.ofSeconds(2))                    // bound the wait
    .onErrorResume(CircuitBreakerOpenException.class,
        ex -> cache.<String, RateCard>get(productId)   // breaker open → serve last good value
            .flatMap(Mono::justOrEmpty)
            .switchIfEmpty(Mono.just(RateCard.conservativeDefault())));
```

Aquí es donde se encuentran el caching y la resiliencia: una caché es a menudo el *plan B* al que una
llamada resiliente recurre. El breaker te impide machacar un servicio descendiente enfermo; la caché te deja
seguir respondiendo — con un valor ligeramente obsoleto pero seguro — mientras se recupera.

!!! spring "Equivalente en Spring"
    Resilience4j es una librería estándar de Spring Boot, y podrías anotar métodos con
    `@CircuitBreaker`, `@Retry` y `@Bulkhead` tú mismo en cualquier app de Spring. La
    contribución de Firefly es doble: cablea esos patrones en el `ServiceClient` unificado
    (Capítulo 16) de modo que *toda* llamada descendiente está protegida sin anotaciones por llamada,
    y los ajusta mediante propiedades `firefly.service-client.*` para toda la flota con
    valores por defecto conscientes del entorno — de modo que el décimo servicio abre circuitos y reintenta exactamente
    como el primero, en lugar de que cada equipo vuelva a deducir los umbrales.

!!! warning "Reintenta solo lo que sea seguro reintentar"
    Los reintentos multiplican la carga y pueden duplicar efectos secundarios. Reemitir un `GET` es
    inofensivo; reemitir un `POST` no idempotente puede crear dos solicitudes de préstamo. Por eso
    el filtro de idempotencia del Capítulo 6 (`X-Idempotency-Key`) y el retry son
    socios: reintenta la lectura con libertad, pero haz idempotente toda escritura reintentada para que un
    reintento deduplique en lugar de reservar por duplicado. Configura el retry para que dispare ante
    fallos transitorios y *seguros* — timeouts y `5xx` — no ante todo error.

## Aquí no hay prueba acompañante — y por qué eso está bien

Los capítulos anteriores terminaban con `mvn ... test` y un recuento en verde, porque cortaban código real
y verificado del reactor. Este no lo hace, y eso es honestidad deliberada:
el slice de originación de Lumen Lending nunca cablea una caché, y sus pruebas se ejecutan contra
valores por defecto en proceso, sin Redis ni circuit breaker que ejercitar. El caching es una
optimización de producción que el ejemplo no necesita, y atornillarlo solo para tener
algo que aseverar te enseñaría una caché que nunca conservarías.

Así que trata este capítulo como el mapa, no el territorio — el `CacheAdapter` real,
`CacheType`, `SmartCacheAdapter`, los atributos `cacheable`/`cacheTtl` y las
propiedades de Resilience4j son todos exactamente como se nombran aquí, listos en el framework en el momento
en que tu servicio los necesite. Los ejercicios de abajo cierran la brecha: añadirás una caché a una
consulta caliente genuina en el reactor y verás la lectura cortocircuitar, convirtiendo el mapa en
código que se ejecuta.

## Lo que has aprendido {.recap}

- El puerto reactivo **`CacheAdapter`** devuelve `Mono<Optional<V>>`, así que un acierto de caché y un
  fallo son señales corrientes y un read-through se compone con `flatMap` — sin bloqueo,
  sin comprobaciones de nulo.
- **Caffeine** es la **L1** integrada, acotada y en proceso, sin necesidad de infraestructura.
  Una **L2** distribuida (Redis o Hazelcast) se sitúa detrás mediante el **`SmartCacheAdapter`**,
  que escribe a través de ambas capas (write-through) y rellena la L1 ante un acierto de L2 — todo tras el mismo
  puerto.
- **`CacheType.AUTO`** elige el proveedor en tiempo de ejecución — Redis, luego Hazelcast, luego
  JCache, luego Caffeine, luego No-Op — de modo que un solo valor de configuración se ejecuta con Caffeine en las pruebas y
  con Redis en producción (matriz de proveedores en el Apéndice B).
- El **caching de consultas** CQRS es un atributo: `@QueryHandlerComponent(cacheable = true,
  cacheTtl = N)` hace que el `QueryBus` cachee resultados por los parámetros de la consulta y ejecute
  `doHandle` solo ante un fallo; `@InvalidateCacheOn` invalida ante el evento de dominio coincidente.
- La **resiliencia** — circuit breaker, retry y bulkhead de Resilience4j — la aplica el
  `ServiceClient` unificado (Capítulo 16) como **operadores de Reactor**, no como guardas bloqueantes, así que
  la recuperación es simplemente `onErrorResume`/`timeout`, y una caché es un plan B natural.

## Pruebalo tu mismo {.exercises}

Estos ejercicios editan el reactor real bajo `samples/lumen-lending`, partiendo del
`GetApplicationStatusHandler` sin cachear del módulo de dominio.

1. **Cachea una consulta caliente.** En `GetApplicationStatusHandler`, cambia la anotación
   `@QueryHandlerComponent` pelada por `@QueryHandlerComponent(cacheable = true, cacheTtl = 60)`.
   Ejecuta las pruebas del módulo de dominio (`mvn -q -pl domain-lending-loan-origination test`) y
   confirma que siguen pasando — cachear una consulta determinista no cambia el resultado para nadie,
   que es exactamente el objetivo: es una optimización transparente.
2. **Demuestra el cortocircuito.** Añade un contador (un `AtomicInteger`) que el manejador incremente
   dentro de `doHandle`, y luego escribe una prueba que despache la *misma* consulta dos veces a través del
   `QueryBus` y asevere que el contador se incrementó solo **una vez** — prueba de que la segunda lectura
   se sirvió desde la caché y `doHandle` nunca se ejecutó.
3. **Cablea la invalidación.** Añade `@InvalidateCacheOn(eventTypes =
   "LoanApplicationRegisteredEvent")` al manejador ahora cacheable. En una frase, explica
   (usando la regla del Capítulo 11) por qué `eventTypes` debe ser el nombre simple de clase y no
   `"loanApplication.registered"`.
4. **Elige un proveedor con AUTO.** En `application.yml`, establece `firefly.cache.type: AUTO`
   y razona a qué proveedor se resuelve en el perfil de pruebas (sin jar de Redis en
   el classpath) frente a un perfil de producción que añade `fireflyframework-cache-redis`.
   Confirma tu respuesta contra el orden de prioridad del Apéndice B.
5. **Diseña un plan B resiliente.** Esboza (no hace falta ejecutarlo) una llamada de `ServiceClient` al
   núcleo de pricing que, ante `CircuitBreakerOpenException`, recurra a un `RateCard`
   cacheado y solo entonces a un valor por defecto conservador. Identifica qué operador maneja
   cada paso — `timeout`, `onErrorResume`, `switchIfEmpty` — y dónde encaja la lectura de
   caché.

## Adonde ir ahora

El caching y la resiliencia mantienen un servicio rápido y en pie; lo siguiente que necesitas es
*ver* que lo hace. El Capítulo 21 se vuelve hacia la observabilidad — las métricas, las trazas y la
propagación funcional del contexto de Reactor que hacen visibles una tasa de aciertos de caché, un breaker disparado o una
tormenta de reintentos en toda la flota, de modo que el comportamiento que configuraste aquí sea algo
que realmente puedas observar en producción.
