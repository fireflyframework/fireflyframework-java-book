Todo lo demás en este libro se apoya en la idea de este capítulo. Un manejador de
Firefly devuelve un `Mono`. Un repositorio devuelve un `Flux`. El bus de comandos, el
publicador de eventos, el cliente HTTP resiliente: todos hablan Project Reactor. Si el
modelo reactivo es confuso, cada capítulo posterior parece un juego de manos: los
valores aparecen de la nada, los métodos devuelven cosas que no puedes imprimir, y un
`.block()` despistado tumba todo el servicio. Así que, antes de construir otro servicio,
vas a aprender Reactor como es debido (operador a operador, señal a señal) hasta que
nada de ello sea magia.

La buena noticia: puedes aprenderlo igual que aprendes cualquier código, ejecutándolo y
viéndolo pasar. El reactor que acompaña al libro incluye un único test autocontenido,
`ReactiveModelTest`, cuyos seis métodos son un recorrido por el modelo. No hay base de
datos, ni servidor web, ni maquinaria de Firefly: solo `Mono`, `Flux` y `StepVerifier`.
En los pasos siguientes leerás cada método, entenderás exactamente qué afirma, y harás
que todo el fichero quede en verde. Abre un buffer de pruebas y escribe los ejemplos
sobre la marcha; el código reactivo recompensa la memoria muscular.

El fichero está en
`core-lending-loan-origination/src/test/java/com/firefly/lumen/core/ReactiveModelTest.java`.
Así es como empieza:

::: listing core-lending-loan-origination/src/test/java/com/firefly/lumen/core/ReactiveModelTest.java | Listado 5.1 — los imports que enmarcan todo el capitulo
package com.firefly.lumen.core;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
:::

Tres imports cargan con el capítulo. `Mono` y `Flux` son los publicadores que compones.
`StepVerifier`, del artefacto `reactor-test`, es como afirmas lo que emite un publicador
*sin bloquear*: gobierna una suscripción y comprueba cada señal por turnos. `Duration`
aparece solo al final, en el ejemplo de tiempo virtual.

## Paso 1 — Mono y Flux son publicadores perezosos

Un `Mono<T>` es un publicador de **como mucho un** elemento: emitirá o bien un valor y
completará, o bien completará sin valor, o bien fallará. Piensa en una única respuesta
HTTP, un `findById`, un "guarda y devuelve la fila guardada". Un `Flux<T>` es un
publicador de **cero a muchos** elementos: un flujo de filas, una página de resultados,
un feed de eventos.

La palabra que más importa es **perezoso**. Un `Mono` o un `Flux` no es un valor; es una
*receta* para producir valores. Construir uno no ejecuta nada. La receta se ejecuta solo
cuando algo se **suscribe**, y ni un instante antes. Este es el mayor cambio respecto al
Java bloqueante, donde llamar a un método *es* hacer el trabajo.

!!! note "Término clave — publicador, suscriptor, señales"
    Un **publicador** (`Mono` o `Flux`) describe un flujo de datos. Un **suscriptor**
    lo consume. Cuando te suscribes, el publicador empuja una secuencia de **señales**:
    cero o más señales `onNext(value)`, y luego exactamente una señal terminal:
    `onComplete()` (éxito) o `onError(throwable)` (fallo). El "test reactivo" es en
    realidad "afirmar la secuencia exacta de señales", que es precisamente lo que hace
    `StepVerifier`.

Aquí tienes el `Mono` más simple posible, y la afirmación más simple posible sobre él:

::: listing core-lending-loan-origination/src/test/java/com/firefly/lumen/core/ReactiveModelTest.java | Listado 5.2 — un valor, luego completar
    @Test
    void monoEmitsOneValueThenCompletes() {
        Mono<String> greeting = Mono.just("hello");

        StepVerifier.create(greeting)
                .expectNext("hello")
                .verifyComplete();
    }
:::

Léelo como una frase. `Mono.just("hello")` construye una receta que, *al suscribirse*,
emite `"hello"` y completa. Todavía no se ha ejecutado nada: `greeting` es una
descripción inerte. `StepVerifier.create(greeting)` se suscribe. `.expectNext("hello")`
afirma que la primera señal es `onNext("hello")`. `.verifyComplete()` afirma que la
siguiente señal es `onComplete()` y, fundamentalmente, *dispara la suscripción* y
bloquea el hilo del test hasta que la verificación termina. Sin esa llamada terminal,
nada llegaría a ejecutarse.

Un `Mono` no tiene por qué llevar valor alguno. El vacío es un resultado esperado y de
primera clase:

::: listing core-lending-loan-origination/src/test/java/com/firefly/lumen/core/ReactiveModelTest.java | Listado 5.3 — completar sin ningun valor
    @Test
    void emptyMonoCompletesWithoutAValue() {
        StepVerifier.create(Mono.empty())
                .verifyComplete();
    }
:::

`Mono.empty()` no emite ningún `onNext`: simplemente completa. No hay ningún `null` aquí,
ni ninguna excepción; "no se encontró nada" es una señal normal, no un error. Por eso el
`findById` de un repositorio de Firefly devuelve `Mono<LoanApplication>`: una fila
ausente es un `Mono` vacío, y lo gestionas con un operador como `switchIfEmpty` en lugar
de una comprobación de nulo.

!!! tip "Punto de control"
    Antes de seguir, asegúrate de que el fichero de test compila y de que estos primeros
    métodos pasan. Desde el directorio `samples/lumen-lending`, ejecuta:

    ```text
    mvn -q -pl core-lending-loan-origination -Dtest=ReactiveModelTest#monoEmitsOneValueThenCompletes test
    ```

    Deberías ver `Tests run: 1, Failures: 0`. Si ves un error de compilación, confirma
    que `reactor-test` está en el classpath de test: viene con el starter del core de
    Firefly.

## Paso 2 — Crear Mono y Flux

Rara vez *escribes* un `Mono` desde cero en código de aplicación: los operadores y el
framework te entregan uno. Pero conocer los métodos de fábrica hace legible cada operador
posterior, porque son la forma en que empieza un flujo.

Un `Flux` de valores conocidos es `Flux.just(...)`:

::: listing core-lending-loan-origination/src/test/java/com/firefly/lumen/core/ReactiveModelTest.java | Listado 5.4 — un Flux emite cada elemento, en orden
    @Test
    void fluxEmitsEachElementInOrder() {
        Flux<Integer> numbers = Flux.just(1, 2, 3);

        StepVerifier.create(numbers)
                .expectNext(1, 2, 3)
                .verifyComplete();
    }
:::

`Flux.just(1, 2, 3)` emite `1`, luego `2`, luego `3`, y luego completa, y el **orden está
garantizado**. `.expectNext(1, 2, 3)` es una forma abreviada de tres expectativas
`onNext` en secuencia; el test falla si el orden difiere o falta un valor.

Más allá de `just`, las fábricas a las que más recurrirás son:

```java
Mono.just(value);              // one known value
Mono.empty();                  // zero values, completes
Mono.error(new RuntimeException());   // fails immediately
Mono.fromCallable(() -> compute());   // run a (non-blocking) supplier lazily
Mono.defer(() -> buildMono());        // build the Mono fresh per subscription

Flux.just(a, b, c);            // known values
Flux.range(1, 6);              // 1, 2, 3, 4, 5, 6
Flux.fromIterable(list);       // from a collection
Flux.empty();                  // zero values, completes
```

La distinción entre `Mono.just(compute())` y `Mono.fromCallable(compute)` merece quedar
grabada a fuego: `just` evalúa su argumento **ahora**, de forma ansiosa, en el momento en
que construyes la receta; `fromCallable` y `defer` posponen el trabajo hasta la
suscripción. En la pila reactiva quieres la forma perezosa para cualquier cosa con efecto
secundario, de modo que el trabajo ocurra en el momento de la suscripción, en el hilo
correcto, y se reejecute al reintentar.

!!! warning "`Mono.just` captura su argumento de forma ansiosa"
    `Mono.just(loadFromDb())` llama a `loadFromDb()` de inmediato, *antes* de que nadie
    se suscriba, derrotando la pereza y, si esa llamada bloquea, atascando el bucle de
    eventos. Cuando el valor proviene de trabajo real, envuelve el trabajo:
    `Mono.fromCallable` o `Mono.defer`. Reserva `Mono.just` para valores que ya tienes en
    la mano.

!!! tip "Punto de control"
    En tu buffer de pruebas, reemplaza `Flux.just(1, 2, 3)` por `Flux.range(1, 3)` y
    vuelve a ejecutar el método. Sigue pasando: `range(1, 3)` emite `1, 2, 3`. Ahora
    prueba `Flux.range(1, 3)` contra `.expectNext(1, 2, 3, 4)` y lee el mensaje de fallo:
    `StepVerifier` te dice que esperaba un cuarto `onNext` pero obtuvo `onComplete`. Ese
    informe (señal esperada frente a señal real) es como depuras el código reactivo.

## Paso 3 — Transformar y combinar

La composición es todo el juego. Casi nunca te suscribes tú mismo; en su lugar encadenas
**operadores** que devuelven un nuevo publicador que describe el flujo transformado. Los
operadores reflejan la API `Stream` que ya conoces, pero cada uno devuelve un `Mono` o un
`Flux` en lugar de una colección materializada.

El test demuestra `filter` y `map` en una sola tubería:

::: listing core-lending-loan-origination/src/test/java/com/firefly/lumen/core/ReactiveModelTest.java | Listado 5.5 — filtra, luego mapea, construye un nuevo flujo
    @Test
    void operatorsTransformTheStream() {
        Flux<Integer> evensDoubled = Flux.range(1, 6)
                .filter(n -> n % 2 == 0)
                .map(n -> n * 10);

        StepVerifier.create(evensDoubled)
                .expectNext(20, 40, 60)
                .verifyComplete();
    }
:::

`Flux.range(1, 6)` emite del `1` al `6`. `.filter(n -> n % 2 == 0)` deja pasar solo los
valores pares: `2, 4, 6`. `.map(n -> n * 10)` transforma cada uno: `20, 40, 60`. Nada de
esto se ejecuta cuando construyes `evensDoubled`; sigue siendo una receta. Solo cuando
`StepVerifier` se suscribe se tiran los valores a través de la cadena, uno a uno.

Los cuatro operadores que usarás constantemente:

- **`map`** — transforma cada elemento de forma síncrona, de `T` a `U`. De
  `Mono<Applicant>` a `Mono<String>` con `.map(Applicant::fullName)`.
- **`filter`** — descarta los elementos que no cumplen un predicado. En un `Mono`, un
  valor descartado se convierte en un `Mono` *vacío*.
- **`flatMap`** — transforma cada elemento en *otro publicador* y aplana el resultado.
  Así es como encadenas llamadas asíncronas.
- **`zip`** — combina los últimos valores de varios publicadores en uno solo.

La distinción crítica es `map` frente a `flatMap`. Usa `map` cuando la transformación es
un valor plano (`n * 10`, `Applicant::fullName`). Usa `flatMap` cuando la transformación
es en sí misma asíncrona y devuelve un publicador; por ejemplo, tomar el ID de una
solicitud y llamar a un repositorio:

```java
Mono<Decision> decision =
    repository.findById(id)              // Mono<LoanApplication>
        .flatMap(app -> scoringClient    // returns Mono<Score> — async!
            .score(app))                 // flatMap flattens Mono<Mono<Score>>
        .map(Score::toDecision);         // plain transform — map
```

Si recurres a `map` donde necesitabas `flatMap`, acabas con un `Mono<Mono<Score>>` (un
publicador de un publicador) que nunca hace el trabajo interno. `flatMap` se suscribe al
publicador interno por ti y aplana un nivel. Cuando dos llamadas independientes alimentan
un resultado, `zip` las ejecuta y las combina:

```java
Mono<Quote> quote = Mono.zip(
        pricingClient.rateFor(product),  // Mono<Rate>
        limitsClient.limitFor(customer)) // Mono<Limit>
    .map(both -> new Quote(both.getT1(), both.getT2()));
```

!!! spring "Equivalente en Spring"
    Estos operadores son Project Reactor puro: Firefly no añade nada aquí, y una
    aplicación Spring WebFlux normal compone exactamente igual. Si vienes de Spring MVC y
    de la API `Stream` de Java, `map`/`filter` te resultarán familiares; la idea nueva es
    `flatMap` para pasos *asíncronos*, que no tiene equivalente en `Stream` porque los
    streams son síncronos. Piensa en `flatMap` como el `await`-y-continúa reactivo.

!!! tip "Punto de control"
    Añade un `.map(n -> n + 1)` al final de la cadena en `operatorsTransformTheStream` y
    actualiza la expectativa a `.expectNext(21, 41, 61)`. Vuelve a ejecutar y observa
    cómo pasa. Luego elimina la línea `.filter` y predice la salida antes de ejecutar:
    seis valores, cada uno multiplicado por diez: `10, 20, 30, 40, 50, 60`.

## Paso 4 — Terminar y afirmar con StepVerifier

Has estado usando `StepVerifier` todo el tiempo; ahora pongámosle nombre. Es la forma
canónica de testear código reactivo, porque la alternativa (llamar a `.block()` para
sacar el valor) derrota el propósito y, en un servicio real, bloquea el bucle de eventos.
`StepVerifier` se suscribe, y luego te deja afirmar cada señal en el orden en que llega,
terminando con una expectativa terminal que *ejecuta* la verificación.

La forma es siempre la misma, con tres partes:

```java
StepVerifier.create(publisher)   // subscribe
    .expectNext(...)             // assert onNext signals, in order
    .verifyComplete();           // assert terminal onComplete — and run it
```

La llamada terminal es obligatoria y portante. `.verifyComplete()` afirma que el flujo
termina con `onComplete`; `.verify()` afirma cualquier terminal que hayas descrito justo
antes (lo usarás para errores en el siguiente paso); `.expectComplete()` seguido de
`.verify()` es la forma larga. Olvida la llamada terminal y tu "test" construye un
verificador y nunca se suscribe, así que pasa sin hacer nada. Ese es el bug más común del
testeo reactivo, y es silencioso.

!!! note "Término clave — publicadores fríos frente a calientes"
    Todos los publicadores de este test son **fríos**: no hacen ningún trabajo hasta que
    se suscriben, y producen la secuencia completa *de nuevo para cada suscriptor*.
    Suscríbete dos veces y `Flux.range(1, 6)` se ejecuta dos veces. Un publicador
    **caliente** emite haya o no alguien escuchando (un feed de eventos en vivo; un
    `Sinks.Many`), y los suscriptores tardíos se pierden los elementos anteriores. Casi
    todo lo que construyes en Firefly (llamadas a repositorios, llamadas a clientes,
    resultados de manejadores) es frío, razón por la cual reintentar simplemente
    reejecuta la receta.

!!! tip "Punto de control"
    Comenta la línea `.verifyComplete()` en cualquier test que pase, añade un simple `;`
    para mantenerlo compilando, y vuelve a ejecutar. Sigue "pasando", porque nada se
    suscribió. Restaura el terminal. Este es el hábito más importante en el testeo
    reactivo: un verificador sin terminal no afirma nada.

## Paso 5 — Errores y reintento

En la pila reactiva, un error no se lanza hacia arriba por una pila de llamadas: viaja
*hacia abajo por el flujo* como una señal `onError`, igual que los valores viajan como
`onNext`. Es una señal terminal: una vez que un publicador emite `onError`, no emite nada
más. Lo afirmas con `StepVerifier` igual que un valor.

::: listing core-lending-loan-origination/src/test/java/com/firefly/lumen/core/ReactiveModelTest.java | Listado 5.6 — un error es una senal terminal, afirmada como cualquier otra
    @Test
    void errorsArePropagatedAsTerminalSignals() {
        Flux<Integer> failing = Flux.just(1, 2)
                .concatWith(Flux.error(new IllegalStateException("boom")));

        StepVerifier.create(failing)
                .expectNext(1, 2)
                .expectErrorMatches(e -> e instanceof IllegalStateException
                        && "boom".equals(e.getMessage()))
                .verify();
    }
:::

`Flux.just(1, 2)` emite dos valores, y luego `.concatWith(Flux.error(...))` añade un flujo
que falla de inmediato. Así que la secuencia completa de señales es `onNext(1)`,
`onNext(2)`, `onError(IllegalStateException("boom"))`. El verificador afirma los dos
valores, luego `.expectErrorMatches(...)` inspecciona el tipo y el mensaje del error
terminal, y `.verify()` lo ejecuta. Fíjate en que el terminal aquí es `.verify()`, no
`.verifyComplete()`: el flujo *no* completa, falla, y afirmar la completitud sería
incorrecto.

En código real no solo observas los errores; te *recuperas*. Los operadores de
recuperación son los equivalentes reactivos de `catch` y de un bucle de reintento:

```java
service.score(application)
    .onErrorResume(TimeoutException.class,
        ex -> Mono.just(Decision.deferred()))   // fallback value on a specific error
    .onErrorReturn(Decision.unavailable());      // last-resort constant fallback
```

`onErrorResume` cambia a un *nuevo publicador* cuando ocurre el error que coincide: una
llamada alternativa, un valor cacheado, un valor por defecto. `onErrorReturn` cambia a una
constante. Para reintentar fallos transitorios, usa `retry`:

```java
pricingClient.rateFor(product)
    .retry(3);                                   // re-subscribe up to 3 times on error
```

Como el publicador es frío, `retry` simplemente reejecuta toda la receta. Para cualquier
cosa más allá de un conteo fijo, `retryWhen` con una estrategia de backoff es la forma de
grado de producción: espacia los intentos y añade jitter para que un dependiente con
problemas no sea machacado:

```java
import reactor.util.retry.Retry;

pricingClient.rateFor(product)
    .retryWhen(Retry.backoff(3, Duration.ofMillis(200))   // 3 retries, exponential
        .filter(ex -> ex instanceof TimeoutException));    // only retry timeouts
```

!!! spring "Equivalente en Spring"
    Nada de esto es específico de Firefly: `onErrorResume`, `retry` y `retryWhen` son
    Reactor del núcleo, idénticos en cualquier aplicación Spring WebFlux. Donde Firefly
    se gana el sueldo es una capa más arriba: sus clientes HTTP resilientes vienen con
    valores por defecto sensatos de reintento, timeout y cortacircuitos ya cableados
    (Capítulo 14), de modo que escribes la política de `retryWhen` una sola vez, en la
    configuración del framework, en lugar de en cada punto de llamada.

!!! tip "Punto de control"
    Cambia el mensaje afirmado en `errorsArePropagatedAsTerminalSignals` de `"boom"` a
    `"bang"` y vuelve a ejecutar. El test falla, pero lee el mensaje: el verificador
    informa del error real que recibió frente al que tú hiciste coincidir. Restaura
    `"boom"`. Ahora puedes depurar una tubería de errores leyendo el informe de señales.

## Paso 6 — Schedulers e hilos

Hasta ahora cada ejemplo se ejecutó en el hilo del test, de forma síncrona. Los servicios
reales hacen E/S, y *dónde* se ejecuta ese trabajo importa enormemente en la pila
reactiva. Por defecto, una cadena reactiva se ejecuta en el hilo que se haya suscrito;
para un manejador HTTP de Firefly, ese es un hilo del bucle de eventos de Netty, de los
que solo hay un puñado, compartidos entre *todas* las peticiones. Bloquea uno y atascas
todas las peticiones que estaba sirviendo.

Un **scheduler** es la abstracción de Reactor para "qué pool de hilos ejecuta este
trabajo". Desplazas la ejecución con dos operadores:

- **`subscribeOn(scheduler)`** — controla el hilo en el que se ejecutan la *suscripción y
  la fuente*. Afecta a toda la cadena aguas arriba de él, y hay efectivamente uno por
  cadena.
- **`publishOn(scheduler)`** — cambia de hilo para todo lo que está *aguas abajo* de él,
  a partir de ese punto. Úsalo tantas veces como necesites para mover trabajo entre
  pools.

```java
Mono.fromCallable(() -> legacyBlockingLookup(id))   // a blocking call
    .subscribeOn(Schedulers.boundedElastic())       // ...run it OFF the event loop
    .map(this::toDto)                                // safe: not the event loop
    .publishOn(Schedulers.parallel());              // continue on a CPU-bound pool
```

Los schedulers que de verdad nombrarás:

- **`Schedulers.boundedElastic()`** — un pool que crece pero está acotado para proteger el
  host, pensado exactamente para envolver llamadas bloqueantes *inevitables* (un driver
  JDBC heredado, una lectura de sistema de ficheros) para que nunca toquen el bucle de
  eventos.
- **`Schedulers.parallel()`** — un pool fijo dimensionado al número de CPUs, para trabajo
  ligado a CPU.
- **`Schedulers.immediate()`** — ejecuta en el hilo actual; el comportamiento por defecto.

!!! warning "No bloquees el bucle de eventos"
    El pecado capital del código reactivo es una llamada bloqueante en un hilo del bucle
    de eventos: una consulta JDBC, un `Thread.sleep`, un `.block()`, un SDK síncrono. La
    solución nunca es "hazlo más rápido"; es `subscribeOn(Schedulers.boundedElastic())`
    para mover el trabajo bloqueante a un pool construido para absorberlo. Mejor aún, usa
    un cliente no bloqueante (R2DBC, `WebClient`) y evita por completo la llamada
    bloqueante. Por eso el preludio insistió: nunca bloquees.

!!! spring "Equivalente en Spring"
    Los schedulers son Reactor puro y se comportan de forma idéntica en Spring WebFlux
    normal. Firefly no cambia el modelo de hilos (lo hereda), pero sus starters sí
    configuran el bucle de eventos y el dimensionamiento de `boundedElastic` mediante
    propiedades `firefly.*`, de modo que la flota comparte una única política de hilos en
    lugar de que cada servicio vaya adivinando.

!!! tip "Punto de control"
    No hay ninguna afirmación de scheduler en `ReactiveModelTest`: los hilos son una
    propiedad de *dónde* se ejecuta el trabajo, no de *qué* emite, así que `StepVerifier`
    (que solo comprueba señales) es la herramienta equivocada. Para *ver* un cambio de
    hilo, añade un
    `Flux.range(1, 3).publishOn(Schedulers.parallel()).doOnNext(n -> System.out.println(Thread.currentThread().getName())).blockLast();`
    temporal en un `main` desechable y observa el nombre del pool en la salida. Bórralo
    después.

## Paso 7 — Tiempo virtual para operadores basados en tiempo

Algunos operadores van sobre *tiempo*: `delayElement`, `timeout`, `retryWhen` con
backoff, `interval`. Testearlos de forma ingenua significa que tu test *espera de
verdad*: un retardo de una hora tardaría una hora. Reactor lo resuelve con **tiempo
virtual**: `StepVerifier` intercambia un reloj que tú controlas, de modo que avanzas una
hora al instante y afirmas que ocurre.

::: listing core-lending-loan-origination/src/test/java/com/firefly/lumen/core/ReactiveModelTest.java | Listado 5.7 — demostrar un retardo de una hora en microsegundos
    @Test
    void virtualTimeProvesDelayWithoutWaiting() {
        StepVerifier.withVirtualTime(() -> Mono.just("done").delayElement(Duration.ofHours(1)))
                .expectSubscription()
                .thenAwait(Duration.ofHours(1))
                .expectNext("done")
                .verifyComplete();
    }
:::

Tres detalles hacen que esto funcione. Primero, pasas un **supplier**:
`() -> Mono.just("done").delayElement(...)`, no un `Mono` ya construido.
`withVirtualTime` debe instalar su reloj virtual *antes* de que se cree el publicador,
así que solo se le puede dar una receta para construir más tarde. Segundo,
`.expectSubscription()` afirma la señal de suscripción, el momento en que arranca el reloj
virtual. Tercero, `.thenAwait(Duration.ofHours(1))` adelanta ese reloj virtual una hora
completa *de inmediato* (sin espera real), momento en el que el elemento retardado se
dispara, así que `.expectNext("done")` y `.verifyComplete()` tienen éxito. El test se
ejecuta en microsegundos y sin embargo demuestra una hora de comportamiento.

!!! note "Término clave — tiempo virtual"
    El **tiempo virtual** reemplaza el reloj real del scheduler por uno que el test
    adelanta a mano mediante `thenAwait`. Te permite afirmar *cuándo* se disparan las
    señales (que un timeout salta a exactamente 30 segundos, que un backoff espera 200 ms)
    de forma determinista e instantánea. Cualquier operador basado en tiempo debería
    testearse así; nunca con un `sleep` real.

!!! tip "Punto de control"
    Cambia `.thenAwait(Duration.ofHours(1))` por `.thenAwait(Duration.ofMinutes(59))` y
    vuelve a ejecutar. Ahora falla: a los 59 minutos virtuales el elemento no se ha
    disparado, así que el verificador no ve ningún `onNext`. El retardo es real, aunque no
    haya pasado tiempo real. Restaura la hora.

## Paso 8 — Cómo un Mono se convierte en una respuesta HTTP

Todo lo anterior ha sido un `StepVerifier` suscribiéndose en un test. En un servicio
Firefly en ejecución, ¿quién se suscribe? El framework. Cuando llega una petición, Spring
WebFlux invoca tu manejador, que devuelve un `Mono` (una receta, no un valor), y WebFlux se
suscribe a él en el bucle de eventos. Cuando el `Mono` emite `onNext`, el framework
serializa el valor a JSON y escribe la respuesta HTTP; con `onComplete` sin valor escribe
un cuerpo vacío; con `onError` mapea el error a un código de estado. Tú nunca llamas a
`.subscribe()`.

Esa es toda la razón por la que los manejadores devuelven publicadores. Un controlador
bloqueante *retiene* un hilo mientras la base de datos responde; un manejador reactivo
*describe* la respuesta y devuelve la receta, liberando el hilo para servir otras
peticiones hasta que el valor esté listo. Conceptualmente:

```java
@GetMapping("/{id}")
public Mono<LoanApplicationDto> byId(@PathVariable UUID id) {
    return repository.findById(id)          // Mono<LoanApplication>
        .map(this::toDto)                   // Mono<LoanApplicationDto>
        .switchIfEmpty(Mono.error(
            new ResourceNotFoundException("LoanApplication", id)));
}                                           // the framework subscribes; you never do
```

Los mismos operadores que testeaste arriba (`map`, `flatMap`, `filter`, `onErrorResume`)
son todo el vocabulario de un manejador real. Un `Mono` vacío (no se encontró fila) se
convierte en un 404 vía `switchIfEmpty` y el modelo de errores de Firefly; un `onError` se
convierte en una respuesta de problema RFC 7807 (Capítulo 6); un retorno de `Flux` se
convierte en un array JSON o en una respuesta en streaming. El test que acabas de ejecutar
y el manejador de producción son *el mismo modelo*, que es exactamente por lo que
aprenderlo en un test de seis métodos se transfiere por completo.

!!! spring "Equivalente en Spring"
    Esto es Spring WebFlux puro: devolver `Mono<T>` o `Flux<T>` desde un `@RestController`
    y dejar que el framework se suscriba es idéntico con o sin Firefly. Lo que Firefly
    añade es el comportamiento consistente del borde a su alrededor: el mapeo de errores
    RFC 7807, el envoltorio de paginación y la propagación de contexto, de modo que el
    `Mono` que devuelves aterriza como una respuesta uniforme en toda la flota.

## Paso 9 — El eje de producción: contexto a través de las fronteras de operadores

Aquí está el problema que muerde a todo equipo que se fabrica código reactivo a mano, y la
única cosa de la que Firefly te salva con más discreción. En Java bloqueante, un ID de
traza o un ID de inquilino vive en un `ThreadLocal` (el MDC de logging es uno de ellos), y
como un hilo sirve una petición de principio a fin, cada línea de log de ese hilo lleva el
ID correcto. En la pila reactiva esa garantía se evapora: una sola petición salta entre
muchos hilos a medida que cruza `flatMap`, `publishOn` y las fronteras de los schedulers, y
el `ThreadLocal` **no** la sigue. Tus logs acaban en blanco o, peor, sellados con el ID de
otra petición.

La propia respuesta de Reactor es el **Context**: un mapa inmutable, con alcance de
suscripción, que *sí* viaja con la suscripción a través de cada operador. Puedes leerlo y
escribirlo de forma explícita:

```java
Mono.deferContextual(ctx ->
        log.info("tenant={}", ctx.get("tenantId"))   // reads the subscription Context
    )
    .contextWrite(Context.of("tenantId", tenant));    // writes it, near the edge
```

Eso funciona, pero pasarlo a mano en cada llamada es exactamente el tipo de boilerplate de
copiar y pegar que el Capítulo 1 llamó el impuesto empresarial. El puente entre el viejo
mundo de `ThreadLocal` (que tus librerías de logging, seguridad y trazas siguen usando) y
el `Context` de Reactor es una línea, instalada una sola vez al arranque:

```java
Hooks.enableAutomaticContextPropagation();
```

Con ese hook habilitado, Reactor restaura automáticamente los valores de `ThreadLocal`
registrados (el MDC, el contexto de trazas, el inquilino) alrededor de cada operador, en
cualquier hilo que lo ejecute. Tus logs llevan el ID de correlación correcto a través de
cada `flatMap` y `publishOn`, sin ningún `contextWrite` por tu parte.

No encontrarás esa llamada en `ReactiveModelTest`: el test es un recorrido por los
operadores deliberadamente libre de contexto. En un servicio Firefly tampoco la
escribirás, y ese es el punto: la autoconfiguración de observabilidad habilita el hook y
registra los accesores `ThreadLocal` de trazas e inquilino por ti, de modo que la
propagación de contexto simplemente *funciona* en toda la flota. Esta es la capacidad que
tanto el preludio como el Capítulo 1 señalaron como lo más valioso que hace Firefly en la
pila reactiva, y ahora sabes con precisión qué arregla.

!!! warning "Sin propagación de contexto, los logs reactivos mienten"
    Un ID de correlación que no sobrevive a las fronteras de los operadores es peor que no
    tener ID: adjunta silenciosamente la identidad de la petición *equivocada* a una línea
    de log. Si alguna vez construyes código reactivo fuera de Firefly, habilitar
    `Hooks.enableAutomaticContextPropagation()` y registrar tus accesores `ThreadLocal` no
    es opcional: es la diferencia entre una producción trazable y una intrazable.

!!! spring "Equivalente en Spring"
    Este mecanismo es la librería `context-propagation` de Micrometer más el hook de
    Reactor, disponible para cualquier aplicación Spring Boot 3 / WebFlux. La diferencia
    es el cableado: en Spring normal habilitas el hook y registras cada `ThreadLocalAccessor`
    tú mismo; el starter de observabilidad de Firefly lo hace para el contexto de trazas e
    inquilino de fábrica, idéntico en cada servicio.

## Ejecútalo

Has recorrido los seis tests. Ahora ejecuta todo el fichero y observa cómo pasa de
principio a fin. Desde el directorio `samples/lumen-lending`:

```text
mvn -q -pl core-lending-loan-origination -Dtest=ReactiveModelTest test
```

El resultado esperado:

```text
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Seis tests en verde, uno por cada faceta del modelo: un valor `Mono`, un `Mono` vacío, una
secuencia `Flux`, una tubería de operadores, una señal de error y un retardo de tiempo
virtual. Ese es todo el vocabulario reactivo que usa el resto del libro.

## Lo que has aprendido {.recap}

- Un `Mono<T>` publica **como mucho un** elemento; un `Flux<T>` publica **cero a muchos**.
  Ambos son **recetas perezosas**: nada se ejecuta hasta que algo se **suscribe**, y el
  framework se suscribe por ti en el borde HTTP.
- Una suscripción produce una secuencia de **señales**: cero o más `onNext`, y luego una
  terminal `onComplete` o `onError`. Testear de forma reactiva con `StepVerifier` es
  afirmar esa secuencia exacta, y la llamada terminal (`verifyComplete`/`verify`) es lo
  que realmente la ejecuta.
- **Compones** con operadores: `map`/`filter` para transformaciones síncronas, `flatMap`
  para encadenar llamadas asíncronas, `zip` para combinar, y
  `onErrorResume`/`retry`/`retryWhen` para recuperarte. Los errores fluyen hacia abajo por
  el flujo como una señal terminal, no hacia arriba por una pila de llamadas.
- Los **schedulers** controlan qué hilo ejecuta el trabajo; mueves las llamadas
  bloqueantes inevitables fuera del bucle de eventos con
  `subscribeOn(Schedulers.boundedElastic())` y nunca bloqueas el bucle. El **tiempo
  virtual** te permite testear operadores basados en tiempo al instante.
- Un manejador devuelve un `Mono`/`Flux`; WebFlux se suscribe y escribe la respuesta. El
  eje de producción es la **propagación automática de contexto**
  (`Hooks.enableAutomaticContextPropagation()`), que mantiene vivo el contexto de trazas e
  inquilino a través de las fronteras de los operadores. Firefly lo habilita por ti.

## Pruébalo tú mismo {.exercises}

Cada ejercicio extiende el test real en
`core-lending-loan-origination/src/test/java/com/firefly/lumen/core/ReactiveModelTest.java`.
Añade un método, ejecuta `mvn -q -pl core-lending-loan-origination -Dtest=ReactiveModelTest test`,
y mantenlo en verde.

1. **flatMap para deshacer la profundidad.** Escribe un test donde `Flux.just(1, 2, 3)` se
   transforme con `.flatMap(n -> Flux.just(n, n))` y afirma los seis valores que emite.
   Luego cambia `flatMap` por `map` y lee el error de compilación: habrás construido un
   `Flux<Flux<Integer>>`. Ese error es la lección.
2. **Recupérate de un error.** Toma el flux `failing` de
   `errorsArePropagatedAsTerminalSignals`, añade `.onErrorReturn(99)`, y afirma que la
   secuencia es ahora `1, 2, 99` seguida de `verifyComplete()`: el error se convirtió en un
   valor y el flujo completó.
3. **Vacío no es error.** Escribe un test donde `Mono.<String>empty()` seguido de
   `.switchIfEmpty(Mono.just("fallback"))` emita `"fallback"`. Este es el patrón exacto que
   usa un manejador para convertir una fila ausente en un valor por defecto o un 404.
4. **Tiempo de espera rápido.** Usando `StepVerifier.withVirtualTime`, construye
   `Mono.just("late").delayElement(Duration.ofSeconds(10)).timeout(Duration.ofSeconds(2))`,
   adelanta el tiempo virtual, y afirma que emite un `onError` de `TimeoutException`: una
   llamada de diez segundos cortada a los dos segundos, demostrado al instante.
5. **Observa el hilo moverse.** En un `main` desechable (no un test), suscríbete a un
   `Flux.range(1, 3)` con un `.publishOn(Schedulers.parallel())` e imprime
   `Thread.currentThread().getName()` en un `doOnNext` antes y después del `publishOn`.
   Confirma que el nombre cambia en la frontera, y luego bórralo.

## Adónde ir ahora

Ahora lees y escribes Reactor con fluidez, lo que significa que el resto de Lumen Lending
es solo este modelo aplicado. El Capítulo 6 da el siguiente paso justo en el borde HTTP:
cuando un `Mono` emite `onError`, ¿cómo convierte Firefly esa señal terminal en una
respuesta de problema RFC 7807 consistente, de forma automática, idéntica, en cada
servicio?
