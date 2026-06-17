Todo lo demás en este libro se apoya en la idea de este capítulo. Un manejador de
Firefly devuelve un `Mono`. Un repositorio devuelve un `Flux`. El bus de comandos, el
publicador de eventos, el cliente HTTP resiliente: todos ellos hablan Project Reactor.
El flujo en vivo de tres capas que ejecutaste en el arranque rápido —un `POST` de canal
que viaja exp → domain → core y vuelve estampado como `SUBMITTED`— es, por debajo, una
larga cadena de `Mono`s compuesta a través de tres servicios. Si el modelo reactivo es
nebuloso, cada capítulo posterior parece un juego de manos: los valores aparecen de la
nada, los métodos devuelven cosas que no puedes imprimir y un `.block()` perdido tumba
todo el servicio. Así que antes de construir otro servicio, vas a aprender Reactor como
es debido —operador a operador, señal a señal— hasta que nada de esto sea magia.

La buena noticia: puedes aprenderlo como aprendes cualquier código, ejecutándolo y
viéndolo pasar. El reactor de acompañamiento incluye un único test autónomo,
`ReactiveModelTest`, cuyos seis métodos son un recorrido por el modelo. No hay base de
datos, ni servidor web, ni maquinaria de Firefly: solo `Mono`, `Flux` y `StepVerifier`.
En los pasos siguientes leerás cada método, entenderás exactamente qué afirma y
ejecutarás todo el archivo en verde. Abre un búfer de pruebas y teclea los ejemplos a
medida que avanzas; el código reactivo recompensa la memoria muscular.

El archivo vive en
`core-lending-loan-origination/src/test/java/com/firefly/lumen/core/ReactiveModelTest.java`.
Así es como empieza:

::: listing core-lending-loan-origination/src/test/java/com/firefly/lumen/core/ReactiveModelTest.java | Listado 5.1 — los imports que enmarcan todo el capítulo
package com.firefly.lumen.core;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
:::

Tres imports sostienen el capítulo. `Mono` y `Flux` son los publicadores que compones.
`StepVerifier`, del artefacto `reactor-test`, es cómo afirmas qué emite un publicador
*sin bloquear*: conduce una suscripción y comprueba cada señal por turno. `Duration`
aparece solo al final, para el ejemplo de tiempo virtual. Fíjate en lo que *no* se
importa: nada de `org.fireflyframework`, nada de Spring. Esto es deliberado. El modelo
reactivo que estás a punto de aprender es Project Reactor puro, la misma biblioteca que
usa una app de Spring WebFlux corriente; Firefly no la reemplaza ni la envuelve, sino que
*construye sobre* ella. Apréndela aquí, libre de contexto, y se transfiere sin cambios a
cada manejador, repositorio y cliente del resto del libro.

!!! note "Término clave — Project Reactor"
    **Project Reactor** es la biblioteca de reactive-streams sobre la que está
    construido Spring WebFlux —y, por tanto, Firefly—. Proporciona exactamente dos tipos
    de publicador, `Mono` y `Flux`, más los operadores que los transforman y combinan. Es
    una implementación de la especificación Reactive Streams (el contrato `Publisher` /
    `Subscriber` / `Subscription`), razón por la cual un `Flux` de Reactor y un
    `Observable` de RxJava pueden interoperar. Todo en este capítulo es Reactor; el valor
    específico de Firefly (Paso 9) es un hook *alrededor* de Reactor, no un cambio *en*
    él.

## Paso 1 — Mono y Flux son publicadores perezosos

Un `Mono<T>` es un publicador de **como mucho un** elemento: emitirá o bien un valor y
completará, o bien completará sin valor, o bien fallará. Piensa en una única respuesta
HTTP, un `findById`, un "guarda y devuelve la fila guardada". Un `Flux<T>` es un
publicador de **cero a muchos** elementos: un flujo de filas, una página de resultados,
un canal de eventos.

La palabra que más importa es **perezoso**. Un `Mono` o un `Flux` no es un valor; es una
*receta* para producir valores. Construir uno no ejecuta nada. La receta se ejecuta solo
cuando algo se **suscribe**, y ni un momento antes. Este es el mayor cambio de mentalidad
al venir del Java bloqueante, donde llamar a un método *es* hacer el trabajo.

!!! note "Término clave — publicador, suscriptor, señales"
    Un **publicador** (`Mono` o `Flux`) describe un flujo de datos. Un **suscriptor** lo
    consume. Cuando te suscribes, el publicador empuja una secuencia de **señales**: cero
    o más señales `onNext(value)`, y después exactamente una señal terminal —
    `onComplete()` (éxito) u `onError(throwable)` (fallo)—. "Testing reactivo" es en
    realidad "afirmar la secuencia exacta de señales", que es precisamente lo que hace
    `StepVerifier`.

Aquí está el `Mono` más simple posible, y la afirmación más simple posible sobre él:

::: listing core-lending-loan-origination/src/test/java/com/firefly/lumen/core/ReactiveModelTest.java | Listado 5.2 — un valor, luego completar
    @Test
    void monoEmitsOneValueThenCompletes() {
        Mono<String> greeting = Mono.just("hello");

        StepVerifier.create(greeting)
                .expectNext("hello")
                .verifyComplete();
    }
:::

Léelo como una frase. `Mono.just("hello")` construye una receta que, *cuando se
suscribe*, emite `"hello"` y completa. Nada se ha ejecutado todavía: `greeting` es una
descripción inerte. `StepVerifier.create(greeting)` se suscribe. `.expectNext("hello")`
afirma que la primera señal es `onNext("hello")`. `.verifyComplete()` afirma que la
siguiente señal es `onComplete()` y, crucialmente, *dispara la suscripción* y bloquea el
hilo del test hasta que termina la verificación. Sin esa llamada terminal, nada llegaría
a ejecutarse.

!!! spring "Equivalente en Spring"
    Si vienes de Spring MVC, el giro mental es este: el `return service.findById(id);` de
    un controlador bloqueante *hace el trabajo y devuelve un valor*; el
    `return service.findById(id);` de un manejador reactivo *devuelve una receta y no hace
    nada todavía*. La misma sintaxis, el momento opuesto. El framework —nunca tú— se
    suscribe más tarde, en el borde HTTP (Paso 8). Interiorizar "el método devuelve un
    plan, no un resultado" elimina la mayor parte de la confusión inicial.

Un `Mono` no necesita llevar un valor en absoluto. El vacío es un resultado de primera
clase y esperado:

::: listing core-lending-loan-origination/src/test/java/com/firefly/lumen/core/ReactiveModelTest.java | Listado 5.3 — completar sin ningún valor
    @Test
    void emptyMonoCompletesWithoutAValue() {
        StepVerifier.create(Mono.empty())
                .verifyComplete();
    }
:::

`Mono.empty()` no emite ningún `onNext`: simplemente completa. Aquí no hay ningún `null`,
ni ninguna excepción; "no se encontró nada" es una señal normal, no un error. Por eso el
`findById` de un repositorio de Firefly devuelve `Mono<LoanApplication>`: una fila
ausente es un `Mono` vacío, y la manejas con un operador como `switchIfEmpty` en lugar de
una comprobación de nulo. Viste este patrón exacto dar sus frutos en el arranque rápido:
un `GET` para un id desconocido devolvió un `Mono` vacío desde el repositorio, que el
manejador convirtió en el 404 RFC 7807 que leíste en
`localhost:8081/api/v1/loan-applications/00000000-0000-0000-0000-000000000000`.

!!! tip "Punto de control"
    Antes de seguir, asegúrate de que el archivo de test compila y de que estos primeros
    métodos pasan. Desde el directorio `samples/lumen-lending`, ejecuta:

    ```text
    mvn -q -pl core-lending-loan-origination -Dtest=ReactiveModelTest#monoEmitsOneValueThenCompletes test
    ```

    Deberías ver `Tests run: 1, Failures: 0`. Si ves un error de compilación, confirma que
    `reactor-test` está en el classpath de test: se incluye con el starter del core de
    Firefly.

## Paso 2 — Crear Mono y Flux

Rara vez *escribes* un `Mono` desde cero en código de aplicación: los operadores y el
framework te entregan uno. Pero conocer los métodos de fábrica hace legible cada operador
posterior, porque son la forma en que comienza un flujo.

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

`Flux.just(1, 2, 3)` emite `1`, luego `2`, luego `3`, y después completa, y **el orden
está garantizado**. `.expectNext(1, 2, 3)` es una abreviatura de tres expectativas
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

Merece la pena grabarse a fuego la distinción entre `Mono.just(compute())` y
`Mono.fromCallable(compute)`: `just` evalúa su argumento **ahora**, ansiosamente, en el
momento en que construyes la receta; `fromCallable` y `defer` posponen el trabajo hasta
la suscripción. En la pila reactiva quieres la forma perezosa para cualquier cosa con un
efecto secundario, de modo que el trabajo ocurra en el momento de la suscripción, en el
hilo correcto, y se vuelva a ejecutar en el reintento.

!!! warning "`Mono.just` captura su argumento ansiosamente"
    `Mono.just(loadFromDb())` llama a `loadFromDb()` inmediatamente, *antes* de que nadie
    se suscriba, anulando la pereza y, si esa llamada bloquea, estancando el bucle de
    eventos. Cuando el valor proviene de trabajo real, envuelve el trabajo:
    `Mono.fromCallable` o `Mono.defer`. Reserva `Mono.just` para valores que ya tienes en
    la mano.

La diferencia entre `fromCallable` y `defer` es de tipo de retorno, y hace tropezar a la
gente, así que pongámosle nombre ahora. `Mono.fromCallable(() -> x)` toma un supplier que
devuelve un *valor llano* `x` y lo envuelve en un `Mono`. `Mono.defer(() -> someMono)`
toma un supplier que devuelve *un `Mono` ya construido*, y difiere su construcción hasta
cada suscripción. Recurre a `defer` cuando la receta misma deba construirse fresca por
suscriptor; por ejemplo, cuando captura una marca de tiempo o un UUID nuevo que debería
diferir en cada reintento. Te volverás a encontrar con `defer` en el Paso 9, donde leer el
`Context` de la suscripción usa la misma forma de supplier diferido.

!!! tip "Punto de control"
    En tu búfer de pruebas, sustituye `Flux.just(1, 2, 3)` por `Flux.range(1, 3)` y
    vuelve a ejecutar el método. Sigue pasando: `range(1, 3)` emite `1, 2, 3`. Ahora
    prueba `Flux.range(1, 3)` contra `.expectNext(1, 2, 3, 4)` y lee el mensaje de fallo:
    `StepVerifier` te dice que esperaba un cuarto `onNext` pero obtuvo `onComplete`. Ese
    informe —señal esperada frente a señal real— es cómo depuras código reactivo.

## Paso 3 — Transformar y combinar

La composición es todo el juego. Casi nunca te suscribes tú mismo; en su lugar encadenas
**operadores** que devuelven un nuevo publicador que describe el flujo transformado. Los
operadores reflejan la API `Stream` que ya conoces, pero cada uno devuelve un `Mono` o un
`Flux` en lugar de una colección materializada.

El test demuestra `filter` y `map` en una sola tubería:

::: listing core-lending-loan-origination/src/test/java/com/firefly/lumen/core/ReactiveModelTest.java | Listado 5.5 — filter, luego map, construir un flujo nuevo
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
esto se ejecuta cuando construyes `evensDoubled`; sigue siendo una receta. Solo la
suscripción de `StepVerifier` tira de los valores a través de la cadena, uno a uno.

Una sutileza que merece la pena interiorizar: cada operador devuelve un publicador
*nuevo* y deja el original intacto. `Flux.range(1, 6)` no es mutado por `.filter(...)`; el
filtro lo envuelve, y `.map(...)` envuelve eso. La cadena es una pila de recetas,
construida de fuera adentro, que se ejecuta de dentro afuera en el momento de la
suscripción. Por eso una sola fuente puede alimentar dos tuberías distintas sin
interferencia, y por eso volver a suscribirse siempre vuelve a ejecutar desde arriba: la
propiedad que hace funcionar a `retry` (Paso 5).

Los cuatro operadores que usarás constantemente:

- **`map`** — transforma cada elemento de forma síncrona, de `T` a `U`. `Mono<Applicant>`
  a `Mono<String>` con `.map(Applicant::fullName)`.
- **`filter`** — descarta los elementos que no superan un predicado. En un `Mono`, un
  valor filtrado se convierte en un `Mono` *vacío*.
- **`flatMap`** — transforma cada elemento en *otro publicador* y aplana el resultado. Así
  es como encadenas llamadas asíncronas.
- **`zip`** — combina los últimos valores de varios publicadores en uno solo.

La distinción crítica es `map` frente a `flatMap`. Usa `map` cuando la transformación es
un valor llano (`n * 10`, `Applicant::fullName`). Usa `flatMap` cuando la transformación
es ella misma asíncrona y devuelve un publicador; por ejemplo, tomar el ID de una
solicitud y llamar a un repositorio:

```java
Mono<Decision> decision =
    repository.findById(id)              // Mono<LoanApplication>
        .flatMap(app -> scoringClient    // returns Mono<Score> — async!
            .score(app))                 // flatMap flattens Mono<Mono<Score>>
        .map(Score::toDecision);         // plain transform — map
```

Si recurres a `map` donde necesitabas `flatMap`, acabas con un `Mono<Mono<Score>>` —un
publicador de un publicador— que nunca hace el trabajo interior. `flatMap` se suscribe al
publicador interior por ti y aplana un nivel. Cuando dos llamadas independientes alimentan
un resultado, `zip` las ejecuta y las combina:

```java
Mono<Quote> quote = Mono.zip(
        pricingClient.rateFor(product),  // Mono<Rate>
        limitsClient.limitFor(customer)) // Mono<Limit>
    .map(both -> new Quote(both.getT1(), both.getT2()));
```

Esta forma `flatMap`-luego-`map` no es un juguete: es *literalmente* lo que hace el flujo
en vivo del arranque rápido a través de los servicios. Cuando haces `POST` a la capa de
experiencia, el BFF hace `flatMap` sobre la respuesta `Mono` del cliente de domain; el
manejador de domain hace `flatMap` sobre el resultado de la saga, cuyo paso raíz hace
`flatMap` sobre la escritura `Mono<LoanApplicationDto>` del cliente de core. Tres
`flatMap`s a través de tres JVMs, y todo el conjunto sigue siendo una sola receta fría que
se ejecuta cuando el borde WebFlux del BFF se suscribe.

!!! spring "Equivalente en Spring"
    Estos operadores son Project Reactor puro —Firefly no añade nada aquí—, y una app de
    Spring WebFlux corriente compone exactamente igual. Si vienes de Spring MVC y la API
    `Stream` de Java, `map`/`filter` te resultarán familiares; la idea nueva es `flatMap`
    para pasos *asíncronos*, que no tiene equivalente en `Stream` porque los streams son
    síncronos. Piensa en `flatMap` como el `await`-y-continúa reactivo.

!!! note "Término clave — concurrencia y orden de flatMap"
    En un `Flux`, `flatMap` se suscribe a los publicadores interiores **ansiosamente y en
    paralelo** (hasta un límite de concurrencia), de modo que los resultados pueden llegar
    *fuera de orden*: está bien para llamadas independientes, está mal cuando el orden
    importa. Cuando necesites preservar el orden de la fuente, usa `concatMap` (ejecuta
    los publicadores interiores de uno en uno, en secuencia) o `flatMapSequential`
    (ejecuta en paralelo pero reordena los resultados). En un `Mono` solo hay un elemento,
    así que la distinción no surge.

!!! tip "Punto de control"
    Añade un `.map(n -> n + 1)` al final de la cadena en `operatorsTransformTheStream` y
    actualiza la expectativa a `.expectNext(21, 41, 61)`. Vuelve a ejecutar y míralo
    pasar. Luego borra la línea `.filter` y predice la salida antes de ejecutar: seis
    valores, cada uno multiplicado por diez: `10, 20, 30, 40, 50, 60`.

## Paso 4 — Terminar y afirmar con StepVerifier

Has estado usando `StepVerifier` todo el tiempo; ahora ponle nombre a lo que es. Es la
forma canónica de testear código reactivo, porque la alternativa —llamar a `.block()`
para extraer el valor— anula el propósito y, en un servicio real, bloquea el bucle de
eventos. `StepVerifier` se suscribe, luego te deja afirmar cada señal en el orden en que
llega, terminando con una expectativa terminal que *ejecuta* la verificación.

La forma es siempre las mismas tres partes:

```java
StepVerifier.create(publisher)   // subscribe
    .expectNext(...)             // assert onNext signals, in order
    .verifyComplete();           // assert terminal onComplete — and run it
```

La llamada terminal es obligatoria y de carga. `.verifyComplete()` afirma que el flujo
termina con `onComplete`; `.verify()` afirma cualquier terminal que hayas descrito justo
antes (la usarás para errores en el siguiente paso); `.expectComplete()` seguido de
`.verify()` es la forma larga. Olvida la llamada terminal y tu "test" construye un
verificador y nunca se suscribe, así que pasa sin hacer nada. Ese es el bug de testing
reactivo más común, y es silencioso.

Vale la pena conocer ya otros dos pasos de `StepVerifier`, porque los usarás en los
ejercicios. `.expectNextCount(n)` afirma *cuántas* señales `onNext` llegan sin nombrar sus
valores —útil para un `Flux` cuyo contenido es generado—. Y
`.expectError(SomeException.class)` es la forma escueta de la coincidencia de error que
estás a punto de conocer, afirmando solo el tipo del error terminal.

!!! note "Término clave — publicadores fríos vs. calientes"
    Cada publicador de este test es **frío**: no hace trabajo hasta que se suscribe, y
    produce la secuencia completa *de nuevo para cada suscriptor*. Suscríbete dos veces y
    `Flux.range(1, 6)` se ejecuta dos veces. Un publicador **caliente** emite tanto si
    alguien escucha como si no (un canal de eventos en vivo; un `Sinks.Many`), y los
    suscriptores tardíos se pierden los elementos anteriores. Casi todo lo que construyes
    en Firefly —llamadas a repositorio, llamadas a cliente, resultados de manejador— es
    frío, por lo que reintentar simplemente vuelve a ejecutar la receta.

!!! tip "Punto de control"
    Comenta la línea `.verifyComplete()` en cualquier test que pase, añade un `;` llano
    para que siga compilando, y vuelve a ejecutar. Sigue "pasando", porque nada se
    suscribió. Restaura el terminal. Este es el hábito más importante en el testing
    reactivo: un verificador sin terminal no afirma nada.

## Paso 5 — Errores y reintento

En la pila reactiva, un error no se lanza hacia arriba por una pila de llamadas: viaja
*hacia abajo por el flujo* como una señal `onError`, igual que los valores viajan como
`onNext`. Es una señal terminal: una vez que un publicador emite `onError`, no emite nada
más. La afirmas con `StepVerifier` igual que un valor.

::: listing core-lending-loan-origination/src/test/java/com/firefly/lumen/core/ReactiveModelTest.java | Listado 5.6 — un error es una señal terminal, afirmada como cualquier otra
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
que falla inmediatamente. Así que la secuencia completa de señales es `onNext(1)`,
`onNext(2)`, `onError(IllegalStateException("boom"))`. El verificador afirma los dos
valores, luego `.expectErrorMatches(...)` inspecciona el tipo y el mensaje del error
terminal, y `.verify()` lo ejecuta. Fíjate en que el terminal aquí es `.verify()`, no
`.verifyComplete()`: el flujo *no* completa, falla, y afirmar la finalización sería
incorrecto.

Esta es la misma maquinaria detrás de cada error RFC 7807 que viste en el arranque
rápido. Cuando el `Mono` del manejador de core emite `onError` —una
`ResourceNotFoundException` para un id desconocido, o un fallo de validación para un
`requestedAmount` negativo—, esa señal terminal viaja hacia abajo hasta el borde WebFlux,
donde el `GlobalExceptionHandler` de Firefly la captura y renderiza el cuerpo
`application/problem+json`. Una excepción en código reactivo no es más que una señal
`onError` buscando un operador (o el framework) que la maneje. El Capítulo 6 traza ese
camino exacto.

En código real no solo observas los errores; te *recuperas*. Los operadores de
recuperación son los equivalentes reactivos de `catch` y de un bucle de reintento:

```java
service.score(application)
    .onErrorResume(TimeoutException.class,
        ex -> Mono.just(Decision.deferred()))   // fallback value on a specific error
    .onErrorReturn(Decision.unavailable());      // last-resort constant fallback
```

`onErrorResume` sustituye por un *nuevo publicador* cuando ocurre el error coincidente:
una llamada de respaldo, un valor cacheado, un valor por defecto. `onErrorReturn`
sustituye por una constante. Para reintentar fallos transitorios, usa `retry`:

```java
pricingClient.rateFor(product)
    .retry(3);                                   // re-subscribe up to 3 times on error
```

Como el publicador es frío, `retry` simplemente vuelve a ejecutar toda la receta. Para
cualquier cosa más allá de un conteo fijo, `retryWhen` con una estrategia de backoff es la
forma de grado producción: espacia los intentos y añade jitter para que un servicio
dependiente que está sufriendo no sea machacado:

```java
import reactor.util.retry.Retry;

pricingClient.rateFor(product)
    .retryWhen(Retry.backoff(3, Duration.ofMillis(200))   // 3 retries, exponential
        .filter(ex -> ex instanceof TimeoutException));    // only retry timeouts
```

!!! warning "Recupérate a propósito, no por reflejo"
    `onErrorResume` y `retry` son lo bastante potentes como para esconder fallos reales.
    Un `.onErrorReturn(default)` general que se traga *todos* los errores convierte un
    servicio dependiente roto en datos silenciosamente erróneos: el equivalente reactivo
    de `catch (Exception e) {}`. Coincide con la excepción específica de la que sabes
    recuperarte (como hacen los fragmentos de arriba), deja que el resto se propague como
    `onError`, y deja que el manejador de errores de Firefly lo convierta en una respuesta
    RFC 7807 honesta. Un reintento que vuelve a ejecutar una escritura *no idempotente* es
    un peligro en sí mismo; el camino de compensación de la saga (Capítulo 11) existe
    exactamente por esa razón.

!!! spring "Equivalente en Spring"
    Nada de esto es específico de Firefly: `onErrorResume`, `retry` y `retryWhen` son
    Reactor del núcleo, idénticos en cualquier app de Spring WebFlux. Donde Firefly se
    gana su sueldo es una capa más arriba: sus clientes HTTP resilientes vienen con
    valores por defecto sensatos de reintento, timeout y circuit-breaker ya cableados
    (Capítulo 14), de modo que escribes la política `retryWhen` una vez, en la
    configuración del framework, en lugar de en cada punto de llamada.

!!! tip "Punto de control"
    Cambia el mensaje afirmado en `errorsArePropagatedAsTerminalSignals` de `"boom"` a
    `"bang"` y vuelve a ejecutar. El test falla, pero lee el mensaje: el verificador
    informa del error real que recibió frente a lo que hiciste coincidir. Restaura
    `"boom"`. Ahora puedes depurar una tubería de errores leyendo el informe de señales.

## Paso 6 — Schedulers e hilos

Hasta ahora cada ejemplo se ejecutó en el hilo del test, de forma síncrona. Los servicios
reales hacen E/S, y *dónde* se ejecuta ese trabajo importa enormemente en la pila
reactiva. Por defecto, una cadena reactiva se ejecuta en el hilo que se suscribió; para un
manejador HTTP de Firefly, ese es un hilo del bucle de eventos de Netty, de los cuales hay
solo un puñado, compartidos entre *todas* las peticiones. Bloquea uno y estancas cada
petición a la que estaba sirviendo.

Un **scheduler** es la abstracción de Reactor para "qué pool de hilos ejecuta este
trabajo". Desplazas la ejecución con dos operadores:

- **`subscribeOn(scheduler)`** — controla el hilo en el que se ejecutan la *suscripción y
  la fuente*. Afecta a toda la cadena aguas arriba de él, y efectivamente hay uno por
  cadena.
- **`publishOn(scheduler)`** — cambia de hilo para todo lo que esté *aguas abajo* de él, a
  partir de ese punto. Úsalo tantas veces como necesites para mover el trabajo entre
  pools.

```java
Mono.fromCallable(() -> legacyBlockingLookup(id))   // a blocking call
    .subscribeOn(Schedulers.boundedElastic())       // ...run it OFF the event loop
    .map(this::toDto)                                // safe: not the event loop
    .publishOn(Schedulers.parallel());              // continue on a CPU-bound pool
```

Los schedulers que realmente nombrarás:

- **`Schedulers.boundedElastic()`** — un pool que crece pero está limitado para proteger
  el host, pensado exactamente para envolver llamadas *bloqueantes inevitables* (un driver
  JDBC heredado, una lectura del sistema de archivos) para que nunca toquen el bucle de
  eventos.
- **`Schedulers.parallel()`** — un pool fijo dimensionado según las CPUs, para trabajo
  acotado por CPU.
- **`Schedulers.immediate()`** — ejecutar en el hilo actual; el comportamiento por
  defecto.

Una pregunta común de principiante: "si el comportamiento por defecto es no bloqueante,
¿por qué existe siquiera un bucle de eventos?". Porque la fortaleza del bucle de eventos
*es* que nunca espera. Un puñado de hilos puede servir a miles de peticiones en vuelo
precisamente porque cada hilo, en lugar de aparcarse mientras la base de datos responde,
registra un callback y pasa a la siguiente petición. Ese trato solo se mantiene si nadie
bloquea. Un `Thread.sleep` o una llamada JDBC síncrona en un hilo del bucle de eventos
saca a ese hilo de la rotación, y el rendimiento se desploma muy por debajo de lo que
gestionaría un servidor de hilo-por-petición. Los schedulers de arriba son cómo mantienes
el trato cuando no tienes más remedio que llamar a algo bloqueante.

!!! warning "No bloquees el bucle de eventos"
    El pecado cardinal del código reactivo es una llamada bloqueante en un hilo del bucle
    de eventos: una consulta JDBC, un `Thread.sleep`, un `.block()`, un SDK síncrono. La
    solución nunca es "hacerlo más rápido"; es `subscribeOn(Schedulers.boundedElastic())`
    para mover el trabajo bloqueante a un pool construido para absorberlo. Mejor aún, usa
    un cliente no bloqueante (R2DBC, `WebClient`) y evita la llamada bloqueante por
    completo. Por esto el preludio insistía: nunca bloquees. (El reactor de acompañamiento
    sigue su propio consejo: core persiste sobre **R2DBC** contra H2, no JDBC bloqueante,
    así que el bucle de eventos se mantiene limpio incluso sin Docker.)

!!! spring "Equivalente en Spring"
    Los schedulers son Reactor puro y se comportan de forma idéntica en Spring WebFlux
    corriente. Firefly no cambia el modelo de hilos —lo hereda—, pero sus starters sí
    configuran el dimensionamiento del bucle de eventos y de `boundedElastic` mediante
    propiedades `firefly.*`, de modo que la flota comparte una sola política de hilos en
    lugar de que cada servicio adivine.

!!! tip "Punto de control"
    No hay ninguna afirmación de scheduler en `ReactiveModelTest`: los hilos son una
    propiedad de *dónde* se ejecuta el trabajo, no de *qué* emite, así que `StepVerifier`
    (que solo comprueba señales) es la herramienta equivocada. Para *ver* un cambio de
    hilo, añade un
    `Flux.range(1, 3).publishOn(Schedulers.parallel()).doOnNext(n -> System.out.println(Thread.currentThread().getName())).blockLast();`
    temporal en un `main` desechable y observa el nombre del pool en la salida. Bórralo
    después.

## Paso 7 — Tiempo virtual para operadores basados en tiempo

Algunos operadores tienen que ver con el *tiempo*: `delayElement`, `timeout`, `retryWhen`
con backoff, `interval`. Testearlos ingenuamente significa que tu test *espera de verdad*:
un retardo de una hora tardaría una hora. Reactor resuelve esto con **tiempo virtual**:
`StepVerifier` intercambia el reloj por uno que tú controlas, de modo que adelantas una
hora al instante y afirmas qué ocurre.

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

Tres detalles hacen que esto funcione. Primero, pasas un **supplier** —
`() -> Mono.just("done").delayElement(...)`— no un `Mono` ya construido. `withVirtualTime`
debe instalar su reloj virtual *antes* de que se cree el publicador, así que solo se le
puede dar una receta para construir más tarde. (Esta es la misma forma de supplier
diferido que `Mono.defer` del Paso 2: el publicador debe nacer *después* de que el reloj
esté en su sitio.) Segundo, `.expectSubscription()` afirma la señal de suscripción, el
momento en que arranca el reloj virtual. Tercero, `.thenAwait(Duration.ofHours(1))`
adelanta ese reloj virtual una hora completa *inmediatamente* —sin espera real—, momento
en el que el elemento retardado se dispara, así que `.expectNext("done")` y
`.verifyComplete()` tienen éxito. El test se ejecuta en microsegundos y aun así demuestra
una hora de comportamiento.

!!! note "Término clave — tiempo virtual"
    El **tiempo virtual** reemplaza el reloj real del scheduler por uno que el test
    adelanta a mano mediante `thenAwait`. Te permite afirmar *cuándo* se disparan las
    señales —que un timeout se dispara exactamente a los 30 segundos, que un backoff
    espera 200 ms— de forma determinista e instantánea. Cualquier operador basado en
    tiempo debería testearse así; nunca con un `sleep` real. La política
    `retryWhen(Retry.backoff(...))` del Paso 5 es una candidata de primera: el tiempo
    virtual te deja demostrar el espaciado del backoff sin que tu suite de tests tarde la
    duración de reloj de pared del backoff.

!!! tip "Punto de control"
    Cambia `.thenAwait(Duration.ofHours(1))` por `.thenAwait(Duration.ofMinutes(59))` y
    vuelve a ejecutar. Ahora falla: a los 59 minutos virtuales el elemento no se ha
    disparado, así que el verificador no ve ningún `onNext`. El retardo es real, aunque no
    haya pasado tiempo real. Restaura la hora.

## Paso 8 — Cómo un Mono se convierte en una respuesta HTTP

Todo lo anterior ha sido un `StepVerifier` suscribiéndose en un test. En un servicio
Firefly en ejecución, *¿quién se suscribe?* El framework. Cuando llega una petición,
Spring WebFlux invoca tu manejador, que devuelve un `Mono` —una receta, no un valor—, y
WebFlux se suscribe a él en el bucle de eventos. Cuando el `Mono` emite `onNext`, el
framework serializa el valor a JSON y escribe la respuesta HTTP; en un `onComplete` sin
valor escribe un cuerpo vacío; en un `onError` mapea el error a un código de estado. Nunca
llamas a `.subscribe()` tú mismo.

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

Los mismos operadores que testeaste arriba —`map`, `flatMap`, `filter`, `onErrorResume`—
son todo el vocabulario de un manejador real. Un `Mono` vacío (ninguna fila encontrada) se
convierte en un 404 vía `switchIfEmpty` y el modelo de errores de Firefly; un `onError` se
convierte en una respuesta de problema RFC 7807 (Capítulo 6); un retorno `Flux` se
convierte en un array JSON o en una respuesta en streaming. El test que acabas de ejecutar
y el manejador de producción son *el mismo modelo*, que es exactamente por lo que
aprenderlo en un test de seis métodos se transfiere por completo.

Esto ya no es hipotético para ti: es precisamente lo que ocurrió cuando ejecutaste el
`mvn spring-boot:run` del arranque rápido en el servicio core (puerto `8081`, H2 +
Flyway, sin Docker) e hiciste `POST` de una solicitud de préstamo. WebFlux se suscribió al
`Mono` del manejador en un hilo de Netty, la receta se ejecutó (validar → persistir →
enviar), emitió un `onNext` que llevaba el DTO `SUBMITTED`, y el framework serializó eso al
cuerpo `201 Created` que leíste. El módulo entero es también un `java -jar` ejecutable —el
repackage de Spring Boot está cableado—, así que la *misma* suscripción ocurre tanto si
arrancas vía Maven como vía el fat jar.

!!! spring "Equivalente en Spring"
    Esto es Spring WebFlux corriente: devolver `Mono<T>` o `Flux<T>` desde un
    `@RestController` y dejar que el framework se suscriba es idéntico con o sin Firefly.
    Lo que Firefly añade es el comportamiento de borde consistente a su alrededor: el
    mapeo de errores RFC 7807, el sobre de paginación y la propagación de contexto, de
    modo que el `Mono` que devuelves aterriza como una respuesta uniforme en toda la
    flota.

## Paso 9 — El eje de producción: contexto a través de las fronteras de operadores

Aquí está el problema que muerde a cada equipo que escribe a mano código reactivo, y de lo
que Firefly más calladamente te salva. En el Java bloqueante, un trace ID o un tenant ID
vive en un `ThreadLocal` (el MDC del logging es uno), y como un hilo sirve a una petición
de principio a fin, cada línea de log de ese hilo lleva el ID correcto. En la pila
reactiva esa garantía se evapora: una sola petición salta entre muchos hilos a medida que
cruza fronteras de `flatMap`, `publishOn` y schedulers, y el `ThreadLocal` **no** la
sigue. Tus logs acaban en blanco —o peor, estampados con el ID de otra petición—.

Ya has *visto* el final feliz de esta historia sin darte cuenta. Echa la vista atrás a las
líneas de log del hilo de petición del arranque rápido: llevan un `traceId` y un `spanId`
en cada línea, enhebrados a través de filtros y hasta dentro del manejador:

```json
{"timestamp":"2026-06-17T08:21:44.132+0000","message":"Generated new transaction ID: ce0c2ede-0e81-430f-9c99-7464a1613884","logger":"o.f.core.config.TransactionFilter","level":"DEBUG","traceId":"bfa32cdc5313c5951ec124b491f07687","spanId":"78466db40897c823"}
{"timestamp":"2026-06-17T08:21:44.134+0000","message":"IdempotencyWebFilter.filter: Processing request POST /api/v1/loan-applications","logger":"o.f.w.i.filter.IdempotencyWebFilter","level":"DEBUG","traceId":"bfa32cdc5313c5951ec124b491f07687","spanId":"78466db40897c823"}
```

Que esas dos líneas compartan *un* `traceId` aunque la petición ya se haya movido de un
filtro a una cadena reactiva no es suerte: es exactamente el mecanismo del que trata este
paso. La respuesta propia de Reactor es el **Context**: un mapa inmutable, de ámbito de
suscripción, que *sí* viaja con la suscripción a través de cada operador. Puedes leerlo y
escribirlo explícitamente:

```java
Mono.deferContextual(ctx ->
        log.info("tenant={}", ctx.get("tenantId"))   // reads the subscription Context
    )
    .contextWrite(Context.of("tenantId", tenant));    // writes it, near the edge
```

Eso funciona, pero enhebrarlo a mano en cada llamada es exactamente el tipo de boilerplate
de copiar y pegar que el Capítulo 1 llamó el impuesto empresarial. El puente entre el
viejo mundo de `ThreadLocal` (que tus bibliotecas de logging, seguridad y trazado todavía
usan) y el `Context` de Reactor es una sola línea, instalada una vez en el arranque:

```java
Hooks.enableAutomaticContextPropagation();
```

Con ese hook habilitado, Reactor restaura automáticamente los valores `ThreadLocal`
registrados —el MDC, el contexto de trazado, el tenant— alrededor de cada operador, en
cualquier hilo que lo ejecute. Tus logs llevan el ID de correlación correcto a través de
cada `flatMap` y `publishOn`, sin ningún `contextWrite` por tu parte.

No encontrarás esa llamada en `ReactiveModelTest`: el test es un recorrido por los
operadores deliberadamente libre de contexto. En un servicio Firefly tampoco la
escribirás, y ese es el punto: la autoconfiguración de observabilidad habilita el hook por
ti. La viste anunciarse en el log de arranque del arranque rápido:

```json
{"timestamp":"2026-06-17T08:21:44.319+0000","message":"Reactor automatic context propagation enabled — ThreadLocal/MDC values will automatically bridge to Reactor Context across thread boundaries","logger":"o.f.o.t.ReactiveContextPropagationAutoConfiguration","level":"INFO"}
```

Esa única línea de arranque es lo que hace que las dos líneas `DEBUG` de arriba compartan
un `traceId`, y es lo que dejaría que ese mismo contexto de trazado siguiera el flujo en
vivo exp → domain → core a través de los saltos de hilo dentro de cada capa. Esta es la
capacidad que tanto el preludio como el Capítulo 1 señalaron como lo más valioso que hace
Firefly en la pila reactiva, y ahora sabes con precisión qué arregla y dónde buscar la
prueba.

!!! warning "Sin propagación de contexto, los logs reactivos mienten"
    Un ID de correlación que no sobrevive a las fronteras de operadores es peor que no
    tener ID: adjunta silenciosamente la identidad de la petición *equivocada* a una línea
    de log. Si alguna vez construyes código reactivo fuera de Firefly, habilitar
    `Hooks.enableAutomaticContextPropagation()` y registrar tus accessors de `ThreadLocal`
    no es opcional: es la diferencia entre una producción rastreable y otra irrastreable.

!!! spring "Equivalente en Spring"
    Este mecanismo es la biblioteca `context-propagation` de Micrometer más el hook de
    Reactor, disponible para cualquier app de Spring Boot 3 / WebFlux. La diferencia es el
    cableado: en Spring corriente habilitas el hook y registras cada `ThreadLocalAccessor`
    tú mismo; el starter de observabilidad de Firefly lo hace para el contexto de trazado
    y de tenant de fábrica, de forma idéntica en cada servicio. La línea de arranque de
    arriba (`ReactiveContextPropagationAutoConfiguration`) es esa autoconfiguración
    anunciando que ha hecho el trabajo por ti.

## Ejecútalo

Has recorrido los seis tests. Ahora ejecuta todo el archivo y míralo pasar de principio a
fin. Desde el directorio `samples/lumen-lending`:

```text
mvn -q -pl core-lending-loan-origination -Dtest=ReactiveModelTest test
```

El resultado esperado:

```text
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Seis tests en verde —uno por cada faceta del modelo: un valor `Mono`, un `Mono` vacío, una
secuencia `Flux`, una tubería de operadores, una señal de error y un retardo en tiempo
virtual—. Ese es todo el vocabulario reactivo que usa el resto del libro. Estos seis son
parte de los dieciocho tests del módulo core, que a su vez son parte de los treinta y tres
del reactor (core 18, domain 6, exp 9); ejecuta `mvn clean verify` desde
`samples/lumen-lending` para verlos todos ponerse en verde de una vez.

## Lo que has aprendido {.recap}

- Un `Mono<T>` publica **como mucho un** elemento; un `Flux<T>` publica **cero a muchos**.
  Ambos son **recetas perezosas**: nada se ejecuta hasta que algo se **suscribe**, y el
  framework se suscribe por ti en el borde HTTP.
- Una suscripción produce una secuencia de **señales**: cero o más `onNext`, y después un
  `onComplete` u `onError` terminal. El testing reactivo con `StepVerifier` consiste en
  afirmar esa secuencia exacta, y la llamada terminal (`verifyComplete`/`verify`) es lo
  que realmente la ejecuta.
- **Compones** con operadores: `map`/`filter` para transformaciones síncronas, `flatMap`
  para encadenar llamadas asíncronas (y `concatMap` cuando el orden importa), `zip` para
  combinar, y `onErrorResume`/`retry`/`retryWhen` para recuperarte. Los errores fluyen
  hacia abajo por el flujo como una señal terminal, no hacia arriba por una pila de
  llamadas, que es exactamente cómo nacen los errores RFC 7807 del arranque rápido.
- Los **schedulers** controlan qué hilo ejecuta el trabajo; mueves las llamadas
  bloqueantes inevitables fuera del bucle de eventos con
  `subscribeOn(Schedulers.boundedElastic())` y nunca bloqueas el bucle. El **tiempo
  virtual** te deja testear operadores basados en tiempo al instante.
- Un manejador devuelve un `Mono`/`Flux`; WebFlux se suscribe y escribe la respuesta: la
  misma suscripción que convirtió tu `POST` en un `201 SUBMITTED`. El eje de producción es
  la **propagación automática de contexto** —`Hooks.enableAutomaticContextPropagation()`—,
  que mantiene vivos el `traceId`/`spanId` que ves en los logs a través de las fronteras de
  operadores. Firefly la habilita por ti, y el log de arranque lo dice.

## Pruébalo tú mismo {.exercises}

Cada ejercicio extiende el test real en
`core-lending-loan-origination/src/test/java/com/firefly/lumen/core/ReactiveModelTest.java`.
Añade un método, ejecuta `mvn -q -pl core-lending-loan-origination -Dtest=ReactiveModelTest test`,
y mantenlo en verde.

1. **flatMap quita la profundidad.** Escribe un test donde `Flux.just(1, 2, 3)` se
   transforme con `.flatMap(n -> Flux.just(n, n))` y afirma los seis valores que emite.
   Luego cambia `flatMap` por `map` y lee el error de compilación: habrás construido un
   `Flux<Flux<Integer>>`. Ese error es la lección.
2. **Recupérate de un error.** Toma el flux `failing` de
   `errorsArePropagatedAsTerminalSignals`, añade `.onErrorReturn(99)`, y afirma que la
   secuencia es ahora `1, 2, 99` seguida de `verifyComplete()`: el error se convirtió en un
   valor y el flujo completó.
3. **Vacío no es un error.** Escribe un test en el que `Mono.<String>empty()` seguido de
   `.switchIfEmpty(Mono.just("fallback"))` emita `"fallback"`. Este es el patrón exacto
   que usa el manejador `GET` de core para convertir una fila ausente en el 404 RFC 7807
   que viste en `localhost:8081/api/v1/loan-applications/<unknown-id>`.
4. **Timeout rápido.** Usando `StepVerifier.withVirtualTime`, construye
   `Mono.just("late").delayElement(Duration.ofSeconds(10)).timeout(Duration.ofSeconds(2))`,
   adelanta el tiempo virtual, y afirma que emite un `onError` de `TimeoutException`: una
   llamada de diez segundos cortada a los dos segundos, demostrado al instante.
5. **Observa moverse el hilo.** En un `main` desechable (no un test), suscríbete a un
   `Flux.range(1, 3)` con un `.publishOn(Schedulers.parallel())` e imprime
   `Thread.currentThread().getName()` en un `doOnNext` antes y después del `publishOn`.
   Confirma que el nombre cambia en la frontera, y luego bórralo.
6. **Lee la traza en vivo.** Arranca el servicio core con `mvn spring-boot:run` (sirve en
   el `8081`), haz `POST` de una solicitud de préstamo, y encuentra el `traceId` en las
   líneas de log del hilo de petición. Confirma que el *mismo* `traceId` aparece en más de
   una línea de la misma petición: ese id compartido es la propagación automática de
   contexto (Paso 9) haciendo su trabajo a través de las fronteras de operadores.

## Adónde ir ahora

Ahora lees y escribes Reactor con fluidez, lo que significa que el resto de Lumen Lending
es solo este modelo aplicado. El Capítulo 6 da el paso inmediatamente siguiente en el
borde HTTP: cuando un `Mono` emite `onError`, ¿cómo convierte Firefly esa señal terminal
en una respuesta de problema RFC 7807 consistente, automáticamente, idénticamente, en cada
servicio?
