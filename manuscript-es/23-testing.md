Cada capítulo hasta ahora ha terminado de la misma forma: un comando `mvn` y una línea
de salida en verde. No era un adorno. Es la afirmación central de este libro hecha
operativa — que un servicio Firefly, con toda su fontanería reactiva, su orquestación
de sagas y su enrutamiento de eventos, se puede *probar como código corriente*, rápido
y sin un solo contenedor. Este capítulo se aparta de construir funcionalidades y mira
de frente a las pruebas en sí: qué tipos incluye Lumen Lending, cómo forman una
pirámide y por qué la suite entera se ejecuta en segundos en un portátil sin un demonio
de Docker a la vista.

Ya tienes el vocabulario. Conociste `StepVerifier` en el Capítulo 5, la porción con
`WebTestClient` en el Capítulo 6 y la prueba de EDA sin broker en el Capítulo 11. Aquí
los ensamblas en una estrategia deliberada. Los tres módulos de préstamo del reactor
suman **treinta y tres** pruebas a lo largo de cuatro niveles — unitaria simple,
reactiva con `StepVerifier`, porción web de contexto completo y una prueba de
integración de compensación de saga — y leeremos una prueba representativa de cada
nivel, en orden, de la más barata a la más exhaustiva. Para el final sabrás
exactamente a qué tipo de prueba recurrir, y por qué la más exigente de la suite sigue
sin necesitar red.

Las pruebas viven en tres árboles de módulos, uno por capa. Las pruebas del módulo
**core** están bajo `core-lending-loan-origination/src/test/java/com/firefly/lumen/core/`
(con subpaquetes `domain/` y `web/`); las del módulo **domain** están bajo
`domain-lending-loan-origination/src/test/java/com/firefly/lumen/domain/` (con
subpaquetes `handler/` y `saga/`); y las del módulo **experience** (BFF) están bajo
`exp-lending/src/test/java/com/firefly/lumen/exp/` (con subpaquetes `service/` y
`web/`). Tomaremos porciones de cada nivel y terminaremos ejecutando los tres módulos
en verde a la vez con un solo `mvn verify`.

## La pirámide de pruebas, edición Firefly

La pirámide de pruebas es una idea antigua: tener muchas pruebas rápidas y acotadas en
la base, menos pruebas de alcance medio en el centro, y un número pequeño de pruebas
amplias y lentas en la cima. La forma importa porque las pruebas baratas de la base
atrapan la mayoría de las regresiones en milisegundos, mientras que las caras de la
cima — las que arrancan un contexto o hablan con un broker — se reservan para el
cableado que las pruebas unitarias no pueden alcanzar.

La suite de Lumen Lending se asigna limpiamente a cuatro capas:

- **Pruebas unitarias simples** — un objeto puro bajo prueba, sin Spring, sin tipos
  reactivos, sin E/S. `MoneyTest` y `LoanApplicationTest` viven aquí. Microsegundos
  cada una.
- **Pruebas unitarias reactivas** — siguen sin Spring, pero la unidad bajo prueba
  devuelve un `Mono` o un `Flux`, así que afirmas las señales con `StepVerifier`.
  `ReactiveModelTest` es el recorrido puro; el `ApplicationServiceTest` de la capa de
  experiencia y la prueba del manejador usan la misma herramienta contra una costura
  simulada con un stub.
- **Pruebas de porción web** — `@SpringBootTest` arranca el contexto reactivo completo
  contra H2 en memoria (core) o una costura de SDK simulada (BFF) y conduce la
  superficie HTTP con `WebTestClient`. Una por borde de servicio.
  `LoanApplicationControllerTest` y el `ApplicationControllerTest` de experiencia son
  los ejemplos.
- **Pruebas de orquestación / integración** — `@SpringBootTest` de nuevo, pero
  ejercitando una saga entera o un flujo de EDA de extremo a extremo a través del
  runtime del framework. El `RegisterApplicationSagaCompensationTest` es el titular
  aquí.

!!! note "Término clave — pirámide de pruebas"
    Una **pirámide de pruebas** describe la *proporción* saludable de pruebas por
    alcance: una base ancha de pruebas unitarias rápidas y aisladas, una banda más
    estrecha de pruebas de integración y una fina cúspide de pruebas de extremo a
    extremo. Invertirla — apoyarse en pruebas lentas y amplias para atrapar errores que
    una prueba unitaria debería haber atrapado — te da una suite lenta de ejecutar y
    lenta de diagnosticar. El diseño de Firefly mantiene la base barata a propósito: la
    lógica de dominio es Java simple, así que la mayoría de tus pruebas nunca arrancan un
    contexto.

Lo que hace inusual a esta pirámide es la **cima**. En la mayoría de las pilas
empresariales las capas superiores exigen infraestructura — un contenedor de Postgres,
un broker de Kafka, un fichero docker-compose que hay que vigilar. Las capas superiores
de Firefly no lo hacen. La porción web se ejecuta contra H2 hablando R2DBC; las pruebas
de saga y de EDA ejecutan los runtimes de orquestación y de eventos en proceso; la
porción del BFF sustituye una costura de SDK en memoria por el servicio aguas abajo. La
suite entera es `mvn verify` y nada más. Veremos exactamente cómo en cada nivel, y luego
nombraremos dónde *sí* tienen su sitio los contenedores reales.

Una nota orientadora antes de descender por los niveles. La pirámide se repite *por
capa*: cada uno de los tres servicios de Lumen tiene su propia base de pruebas
unitarias y su propia cúspide de pruebas de porción, escalada a lo que esa capa posee.
La capa **core**, al ser el sistema de registro, es la más pesada — dieciocho pruebas,
incluida una ida y vuelta real a la base de datos. La capa **domain**, al ser
orquestación, tiene las seis pruebas que demuestran el cableado de la saga y de la EDA.
La capa **experience**, un BFF delgado, tiene nueve — un puñado de pruebas unitarias
para su lógica de mapeo y una porción para su borde. Al leer los niveles de abajo,
imagina la misma forma de cuatro capas estampada tres veces, más ancha en el sistema de
registro.

## Nivel 1 — Pruebas unitarias simples

La base de la pirámide es el modelo de dominio probado como Java simple. Sin
anotaciones que arranquen un contexto, sin publicadores, sin mocks de tipos del
framework — solo construye un objeto, llama a un método, afirma el resultado. Estas
pruebas no cuestan nada de ejecutar y fijan las reglas que más importan: las que están
dentro de tus agregados y objetos de valor.

`MoneyTest` es el ejemplo más pequeño del reactor. `Money` es el objeto de valor de
unidades menores del core, y la prueba enuncia tres invariantes como tres hechos de una
línea:

::: listing core-lending-loan-origination/src/test/java/com/firefly/lumen/core/MoneyTest.java | Listado 23.1 — una prueba unitaria simple: sin Spring, sin tipos reactivos
class MoneyTest {

    @Test
    void ofRejectsNegativeAmounts() {
        assertThrows(IllegalArgumentException.class, () -> Money.of(-1));
    }

    @Test
    void minusReturnsTheDifference() {
        assertEquals(Money.of(50), Money.of(150).minus(Money.of(100)));
    }

    @Test
    void minusThrowsWhenResultWouldBeNegative() {
        assertThrows(IllegalArgumentException.class,
                () -> Money.of(100).minus(Money.of(150)));
    }
}
:::

Esto es JUnit 5 y nada más. `assertThrows` fija las cláusulas de guarda — `Money` se
niega a existir como cantidad negativa, y una resta que iría por debajo de cero falla
ruidosamente en vez de producir en silencio un saldo erróneo. `assertEquals` comprueba
la aritmética del camino feliz. No hay `@SpringBootTest`, no hay `@Autowired`, no hay
`Mono`. Una prueba como esta se ejecuta en microsegundos y nunca falla de forma
intermitente, porque no hay nada asíncrono ni externo que pueda fallar así.

¿Por qué probar un objeto de valor con tanta intensidad? Porque `Money` es el tipo por
el que fluye toda cantidad monetaria del servicio, y sus invariantes son la clase de
error que es invisible hasta que es catastrófico — un saldo que se va a negativo en
silencio, un error de redondeo que se acumula. Las cláusulas de guarda son baratas de
escribir y aún más baratas de probar, y un único `assertThrows` por regla documenta el
contrato mejor que un párrafo de prosa. Este es el dividendo de empujar las reglas
*dentro* del tipo en vez de esparcir comprobaciones `if (amount < 0)` por los servicios.

El mismo nivel se eleva hasta el agregado. `LoanApplicationTest` construye una
`LoanApplication` con su builder y la recorre por sus transiciones de estado legales —
enteramente en memoria, sin persistencia:

::: listing core-lending-loan-origination/src/test/java/com/firefly/lumen/core/domain/LoanApplicationTest.java | Listado 23.2 — prueba unitaria de la máquina de estados de un agregado en memoria
    @Test
    void happyPathReachesApproved() {
        LoanApplication app = draft();
        app.submit();
        assertEquals(ApplicationStatus.SUBMITTED, app.getStatus());
        app.startReview();
        assertEquals(ApplicationStatus.UNDER_REVIEW, app.getStatus());
        app.approve();
        assertEquals(ApplicationStatus.APPROVED, app.getStatus());
        assertTrue(app.getStatus().isTerminal());
    }
:::

El agregado aplica sus propias reglas, y la misma clase de prueba fija cada una como un
hecho separado: `cannotApproveADraft` afirma que llamar a `approve()` sobre un `DRAFT`
lanza `IllegalStateException`; `rejectRequiresAReason` afirma que se rechaza una razón en
blanco; `cannotCancelATerminalApplication` afirma que no puedes cancelar una solicitud
que ya ha alcanzado un estado terminal. Verificas cada una de ellas sin una ida y vuelta
a la base de datos, porque las reglas viven en el objeto, no en la tabla. Este es el
dividendo de mantener la lógica de dominio en Java simple: el comportamiento más
importante del sistema es también el más barato de probar.

Fíjate en el ayudante `draft()` al principio de la clase — una llamada al builder de una
línea que cada prueba reutiliza. Esa es la pequeña disciplina que mantiene legible una
suite unitaria: un fixture con nombre que dice «una solicitud en borrador recién creada»
para que cada `@Test` se lea como la *transición* que ejercita, no como ruido de
construcción. La clase cierra con un hecho más, `requestedMoneyConvertsToMinorUnits`,
que ata el agregado de vuelta al objeto de valor `Money` del Listado 23.1 — prueba de
que las dos unidades de Nivel 1 componen entre sí.

!!! tip "Punto de control"
    Ejecuta solo las dos clases unitarias desde `samples/lumen-lending`:

    ```text
    mvn -q -pl core-lending-loan-origination -Dtest=MoneyTest,LoanApplicationTest test
    ```

    Deberías ver `Tests run: 9, Failures: 0` — tres de `MoneyTest`, seis de
    `LoanApplicationTest`. Fíjate en el tiempo transcurrido del informe: milisegundos de
    un solo dígito. Esa velocidad es la razón por la que la base de la pirámide debe ser
    ancha.

## Nivel 2 — Pruebas unitarias reactivas con StepVerifier

Un nivel más arriba, la unidad bajo prueba devuelve un publicador. No puedes hacer
`assertEquals` sobre un `Mono` — es una receta, no un valor — así que te suscribes y
afirmas la secuencia de señales con `StepVerifier`, exactamente como enseñó el Capítulo
5. Y, crucialmente, esto sigue siendo una prueba *unitaria*: no arranca ningún contexto,
no se abre ningún puerto. `ReactiveModelTest` es la forma más pura, afirmando contra
publicadores construidos a mano:

::: listing core-lending-loan-origination/src/test/java/com/firefly/lumen/core/ReactiveModelTest.java | Listado 23.3 — afirmar las señales de un publicador sin Spring ni red
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

`StepVerifier.create(...)` se suscribe; `.expectNext(...)` afirma cada `onNext` en
orden; `.verifyComplete()` afirma el `onComplete` terminal *y ejecuta la verificación*.
Todo el conjunto se ejecuta de forma síncrona en el hilo de la prueba en microsegundos.
Esta es la manera canónica de probar cualquier método reactivo que escribas — un método
de servicio, un mapper, un operador a medida — porque tira de los valores a través del
pipeline sin `.block()` y afirma con precisión lo que el stream emitió.

`ReactiveModelTest` merece leerse más allá de este único caso, porque cataloga toda la
gramática que usarás en todas partes. `errorsArePropagatedAsTerminalSignals` afirma un
`onError` con `.expectErrorMatches(...)` y un `.verify()` final — la forma reactiva de
probar un camino de fallo, dado que un error es solo otra señal terminal, no una
excepción lanzada que atrapas con `try/catch`. Y `virtualTimeProvesDelayWithoutWaiting`
usa `StepVerifier.withVirtualTime(...)` con `.thenAwait(Duration.ofHours(1))` para
demostrar un retardo de una hora *sin que la prueba tarde una hora* — el reloj del
planificador es virtual, así que el código reactivo basado en el tiempo se mantiene
rápido y determinista en lugar de apoyarse en esperas reales.

!!! note "Término clave — tiempo virtual"
    El **tiempo virtual** es la forma que tiene `StepVerifier` de probar código reactivo
    dependiente del tiempo sin espera real. `withVirtualTime(...)` intercambia un
    `VirtualTimeScheduler` cuyo reloj avanzas a mano con `.thenAwait(...)`; un
    `delayElement` o un `interval` que bloquearía durante una hora se resuelve al
    instante. Hace que la clase más lenta de lógica reactiva — timeouts, reintentos con
    backoff, emisiones programadas — sea tan rápida y libre de intermitencias de probar
    como la base síncrona.

La misma herramienta escala hasta afirmar código reactivo que involucra colaboradores
reales sin arrancar nunca un contexto. El `ApplicationServiceTest` de la capa de
experiencia es el ejemplo más claro: construye `ApplicationService` directamente sobre
un cliente stub en memoria y afirma el contrato reactivo del servicio — la validación,
la clave de idempotencia determinista que deriva, y cómo mapea un fallo aguas abajo a
una señal de error:

::: listing exp-lending/src/test/java/com/firefly/lumen/exp/service/ApplicationServiceTest.java | Listado 23.4 — afirmar la señal de error de un servicio con StepVerifier, sin arrancar contexto
    @Test
    void getApplication_mapsMissingToNotFound() {
        StepVerifier.create(service.getApplication(UUID.randomUUID()))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(BusinessException.class);
                    var be = (BusinessException) error;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(be.getCode()).isEqualTo("APPLICATION_NOT_FOUND");
                })
                .verify();
    }
:::

Lee la disciplina que hay aquí. `service` y su `StubLoanOriginationDomainClient` son
objetos `new` simples — sin `@SpringBootTest`, sin autocableado — así que la prueba se
ejecuta a velocidad de unidad. El `getApplication` del stub devuelve `Mono.empty()` para
un id desconocido, y se espera que el servicio *traduzca* esa señal vacía en una
`BusinessException` tipada que lleva `NOT_FOUND` y el código estable
`APPLICATION_NOT_FOUND`. `.expectErrorSatisfies(...)` te deja meterte en el error
terminal y afirmar sus campos, y `.verify()` ejecuta la comprobación. La prueba hermana
`createApplication_rejectsNonPositiveAmount` demuestra el mismo mapeo de
`BusinessException` para un `BAD_REQUEST`, y
`createApplication_derivesDeterministicIdempotencyKey` afirma que el servicio entrega a
la costura del SDK la *misma* clave para la misma petición lógica — el contrato que hace
seguro un reintento. Las tres son de Nivel 2: lógica de servicio real, mapeo de error
real, afirmados a través de `StepVerifier`, sin nada arrancado.

!!! spring "Equivalente en Spring"
    `StepVerifier` viene en `reactor-test`, un artefacto puro de Project Reactor sin
    nada de Firefly dentro — una app de Spring WebFlux simple prueba código reactivo de
    forma idéntica. Firefly no añade nada a la herramienta; simplemente te da más código
    reactivo digno de probar con ella, y mantiene `reactor-test` en el classpath de
    pruebas a través del starter del core para que nunca tengas que cablear la
    dependencia tú mismo.

## Nivel 3 — La porción web con @SpringBootTest y WebTestClient

Ahora la pirámide se estrecha. Para probar el borde HTTP necesitas el contexto real: el
controlador, el servicio, los validadores, el repositorio R2DBC, el manejador global de
excepciones y los filtros web, todos cableados juntos como están en tiempo de
ejecución. Eso es lo que te da `@SpringBootTest` — y lo que convierte a la porción web
de Lumen en la primera prueba de este capítulo que arranca Spring.

El coste es un arranque de contexto de un par de segundos. La recompensa es que
verificas la *integración* del borde entero: que `@Valid` realmente se dispara, que
`ResourceNotFoundException` realmente se convierte en un problem detail `404`, que el
JSON realmente se serializa a través de los codecs configurados. Nada de eso puede
alcanzarse con una prueba unitaria, porque nada de eso vive en un solo objeto.

Así es como la porción del core arranca y adquiere su cliente:

::: listing core-lending-loan-origination/src/test/java/com/firefly/lumen/core/web/LoanApplicationControllerTest.java | Listado 23.5 — arrancar el contexto reactivo completo y enlazar un WebTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoanApplicationControllerTest {

    @Autowired
    private WebTestClient client;
:::

`@SpringBootTest` arranca el contexto de la aplicación. `webEnvironment = RANDOM_PORT`
arranca un servidor reactivo real en un puerto efímero e inyecta un `WebTestClient`
enlazado a él. El `WebTestClient` es el análogo reactivo de `MockMvc`: un cliente HTTP
no bloqueante que conduce tus endpoints y te deja afirmar sobre el estado, las cabeceras
y el cuerpo JSON con una cadena fluida. El puerto *aleatorio* importa más de lo que
parece: un puerto fijo choca en cuanto dos clases de prueba se ejecutan en paralelo o un
proceso extraviado retiene el `8081`, así que enlazar a un puerto efímero e inyectar un
cliente ya apuntado a él mantiene la suite segura en paralelo y amigable para CI.

Con el contexto levantado, cada prueba se lee como una petición y un conjunto de
expectativas:

::: listing core-lending-loan-origination/src/test/java/com/firefly/lumen/core/web/LoanApplicationControllerTest.java | Listado 23.6 — conducir la API y afirmar sobre el cuerpo RFC 7807
    @Test
    void returnsRfc7807ProblemDetailWhenMissing() {
        client.get()
                .uri("/api/v1/loan-applications/{id}", UUID.randomUUID())
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.status").isEqualTo(HttpStatus.NOT_FOUND.value());
    }
:::

`client.get().uri(...).exchange()` realiza la petición;
`.expectStatus().isNotFound()` afirma el `404`;
`.expectBody().jsonPath("$.status").isEqualTo(404)` se mete en el JSON y demuestra que
el problem detail es un cuerpo real, no una mera línea de estado. La prueba
`createsAnApplicationAndReadsItBack` de la misma clase hace una ida y vuelta completa de
POST-y-luego-GET — afirma que el estado creado es `SUBMITTED` (el servicio envía como
parte de la creación, exactamente el comportamiento que el Capítulo 2 observó por
`curl`) y luego vuelve a leer el recurso por su id, afirmando que `$.currency` y
`$.purpose` sobrevivieron a la ida y vuelta de persistencia. La prueba
`rejectsAnInvalidPayload` envía una cantidad negativa para confirmar que `@ValidAmount`
produce un `400`. Tres pruebas, un contexto arrancado, el borde entero verificado.

Fíjate en el detalle que hace esto práctico. El comentario de la clase lo dice mejor:

> boots the full reactive context against in-memory H2 (R2DBC runtime + Flyway
> migration, no Docker).

El repositorio bajo prueba es un repositorio R2DBC *real* que se ejecuta contra una base
de datos H2 en memoria en modo R2DBC, con el esquema aplicado por una migración de
Flyway en el arranque. No hay repositorio simulado y no hay contenedor. El camino de
persistencia se ejercita de verdad — guardados, lecturas, la ida y vuelta — contra una
base de datos que vive enteramente dentro de la JVM y desaparece cuando la prueba
termina. Así es como una prueba de porción tan exhaustiva sigue ejecutándose en un par
de segundos sin nada instalado. El `src/test/resources/application.yml` lleva el perfil
de pruebas que apunta R2DBC y Flyway a H2; el `src/main/resources/application.yml` lleva
el perfil ejecutable que sirve el core en vivo en el puerto `8081`. El mismo código bajo
prueba, dos perfiles — uno para la suite sin interfaz, otro para `mvn spring-boot:run`.

!!! note "Término clave — prueba de porción"
    Una **prueba de porción** (slice test) arranca lo suficiente de la aplicación para
    ejercitar una capa de extremo a extremo — aquí, el borde HTTP entero hasta la base de
    datos — usando un contexto real en lugar de mocks. Se sitúa por encima de las pruebas
    unitarias (arranca Spring) y por debajo de una prueba de integración externa completa
    (usa sustitutos dentro de la JVM como H2 en vez del Postgres de producción). Es la
    prueba de mayor valor por segundo en la mayoría de los servicios: lo bastante amplia
    para atrapar errores de cableado, lo bastante rápida para ejecutarse en cada
    guardado.

La capa de experiencia secciona su borde de la misma forma, pero con una diferencia
reveladora: el BFF no posee base de datos, así que no hay nada que levantar con un H2.
Lo que posee es la *costura* hacia el servicio de dominio aguas abajo, y eso es
exactamente lo que la porción sustituye. La clase arranca el contexto real del BFF y
registra un stub en memoria como el bean de la costura del SDK:

::: listing exp-lending/src/test/java/com/firefly/lumen/exp/web/ApplicationControllerTest.java | Listado 23.7 — seccionar un borde de BFF: contexto real, costura aguas abajo simulada, @Secure conservada
@SpringBootTest
@AutoConfigureWebTestClient
class ApplicationControllerTest {

    private static final String BASE_PATH = "/api/v1/experience/lending/applications";

    @TestConfiguration
    static class StubConfig {
        /** Registers the in-memory stub as the SDK-seam bean; wins via the config's @ConditionalOnMissingBean. */
        @Bean
        LoanOriginationDomainClient loanOriginationDomainClient() {
            return new StubLoanOriginationDomainClient();
        }
    }

    @Autowired
    private WebTestClient webTestClient;
:::

Tres cosas que leer de esto. Primero, `@SpringBootTest` con
`@AutoConfigureWebTestClient` es la variante sin socket — arranca el contexto completo y
enlaza un `WebTestClient` a él *sin* abrir un puerto de servidor real, que es todo lo
que necesita un borde de BFF sin persistencia. Segundo, la `@TestConfiguration` registra
`StubLoanOriginationDomainClient` como el bean `LoanOriginationDomainClient`; como
indica el comentario de la clase, gana porque el cliente en vivo respaldado por
`WebClient` es `@ConditionalOnMissingBean`, así que el stub tiene precedencia y ninguna
prueba alcanza el servicio de dominio ausente. Tercero — y esta es la recompensa del
Capítulo 19 — los controladores conservan sus anotaciones `@Secure` reales; la
aplicación de la seguridad simplemente se desactiva para la prueba mediante
`firefly.application.security.enabled=false` en el `src/test/resources/application.yml`
del módulo. El cableado de seguridad está presente y es real; solo se mantiene abierta la
puerta para que la prueba pueda conducir el borde directamente.

Con la costura simulada, las aserciones se leen exactamente como las de la porción del
core — estado, cuerpo, ida y vuelta — pero contra el DTO mapeado del BFF.
`createApplication_returns201WithMappedDetail` envía una petición de canal y afirma que
el cuerpo del `201` mapea `requestedAmount`, `term`, `purpose`, `status` (`"DRAFT"`) y
`simulationId`. `createThenGetApplication_roundTrips` demuestra un crear-y-luego-leer
contra el almacén del stub. `getUnknownApplication_returnsProblemDetailNotFound` afirma
que el cuerpo del `404` contiene `APPLICATION_NOT_FOUND` — la mismísima
`BusinessException` que `ApplicationServiceTest` lanzó en el Nivel 2, ahora renderizada
como un problem detail por el `GlobalExceptionHandler` de `fireflyframework-web`. Y
`createApplication_rejectsInvalidAmountWithBadRequest` envía una cantidad cero para el
`400`. Cuatro pruebas, una costura simulada, todo el borde del BFF verificado.

!!! spring "Equivalente en Spring"
    `@SpringBootTest` con `RANDOM_PORT` y un `WebTestClient` inyectado es Spring Boot de
    serie — Firefly no reemplaza el arnés de pruebas, cabalga sobre él. La variante sin
    socket `@AutoConfigureWebTestClient` es igualmente de serie. Lo que el contexto
    arrancado contiene *sí* es Firefly: el `GlobalExceptionHandler` autoconfigurado, los
    validadores, los filtros de idempotencia y de transacción del Capítulo 6, la
    aplicación de `@Secure` del Capítulo 19. Así que el mismo mecanismo de prueba de Boot
    verifica el comportamiento transversal del framework gratis, sin que registres nada
    de ello en la prueba.

## Nivel 4 — La prueba de compensación de saga

En el vértice se asienta la prueba más exigente de la suite: demostrar que cuando un
paso de saga falla, el framework *compensa* los pasos que ya tuvieron éxito, sin dejar
atrás ninguna escritura huérfana. Esta es la prueba que cumple la promesa central del
capítulo de orquestación, y merece leerse entera porque muestra cuánto puedes verificar
con `@SpringBootTest` y `StepVerifier` y aun así sin broker, sin contenedor, sin un
servicio aguas abajo real.

Antes del caso de compensación, ayuda ver el camino feliz contra el que se mide.
`RegisterApplicationSagaHappyPathTest` arranca el mismo contexto de dominio — el
`SagaEngine` real, el `CommandBus`, cada `@CommandHandlerComponent`, el bean `@Saga` —
sustituye solo la costura del SDK con un simple `StubLoanOriginationClient`, y afirma
que la saga tiene éxito: `result.isSuccess()` es verdadero, `compensatedSteps()` está
vacío, y los tres pasos se ejecutaron (el paso raíz `registerLoanApplication` más los
dos dependientes `registerApplicant` y `proposeOffer`), observado a través del registro
de llamadas del stub. Esa es la línea base. La prueba de compensación es la misma
configuración con un interruptor cambiado.

Recuerda la forma de la saga del capítulo de orquestación: `registerLoanApplication` es
el paso raíz, y tanto `registerApplicant` como `proposeOffer` lo declaran como
`dependsOn`. El paso raíz declara `compensate = removeLoanApplication`. Si un paso
dependiente falla después de que la raíz tuviera éxito, el motor debe ejecutar la
compensación de la raíz. La prueba fuerza exactamente ese fallo intercambiando un stub
configurado para hacer que `proposeOffer` lance:

::: listing domain-lending-loan-origination/src/test/java/com/firefly/lumen/domain/saga/RegisterApplicationSagaCompensationTest.java | Listado 23.8 — forzar el fallo de un paso dependiente con un bean stub de ámbito de prueba
@SpringBootTest
@Import(RegisterApplicationSagaCompensationTest.FailingOfferConfig.class)
class RegisterApplicationSagaCompensationTest {

    @TestConfiguration
    static class FailingOfferConfig {
        @Bean
        LoanOriginationClient loanOriginationClient() {
            return new StubLoanOriginationClient().failProposeOffer();
        }
    }
:::

`@SpringBootTest` arranca el contexto de dominio — el motor de saga, el bus de CQRS, los
manejadores de comandos — todo real. La `@TestConfiguration` con `@Import` sobrescribe un
bean: el `LoanOriginationClient` pasa a ser un `StubLoanOriginationClient` configurado en
`failProposeOffer()`. Esta es la costura. El runtime del framework es genuino; solo la
frontera del SDK hacia el servicio aguas abajo (ausente) está simulada, así que la prueba
puede conducir un fallo de forma determinista sin red. Sustituir un único bean en el
borde, en lugar de simular el motor, es lo que mantiene *real* la orquestación bajo
prueba. El `failProposeOffer()` del stub voltea una bandera volatile que hace que su
`proposeOffer` devuelva `Mono.error(...)` — un fallo limpio y determinista inyectado en el
punto exacto en que la saga llama hacia fuera, sin excepción lanzada que atrapar y sin
temporización que domesticar.

La aserción es donde se demuestra la compensación:

::: listing domain-lending-loan-origination/src/test/java/com/firefly/lumen/domain/saga/RegisterApplicationSagaCompensationTest.java | Listado 23.9 — afirmar el fallo, el paso fallido y la raíz compensada
    @Test
    void submitApplication_failsAndCompensatesRootStep_whenDependentStepThrows() {
        StepVerifier.create(service.submitApplication("Ada Lovelace", 250_000L, 575))
                .assertNext(result -> {
                    // The saga as a whole failed.
                    assertThat(result.isSuccess()).isFalse();
                    assertThat(result.isFailed()).isTrue();
                    // The failing step is the proposeOffer dependent step.
                    assertThat(result.failedSteps()).contains(RegisterApplicationSaga.STEP_PROPOSE_OFFER);
                    // The root step was compensated (its removeLoanApplication ran).
                    assertThat(result.compensatedSteps())
                            .contains(RegisterApplicationSaga.STEP_REGISTER_LOAN_APPLICATION);
                })
                .verifyComplete();
:::

Lee la cadena de aserciones como la historia que cuenta la saga.
`service.submitApplication(...)` devuelve un `Mono` del resultado de la saga;
`StepVerifier.create(...)` se suscribe y `.assertNext(...)` inspecciona el único
resultado emitido. El resultado informa de `isFailed()` — la saga en su conjunto no tuvo
éxito — y `failedSteps()` contiene `STEP_PROPOSE_OFFER`, el paso dependiente que lanzó. La
línea decisiva es la última: `compensatedSteps()` contiene
`STEP_REGISTER_LOAN_APPLICATION`, demostrando que el motor ejecutó la compensación
`removeLoanApplication` del paso raíz tras el fallo del dependiente. El mismo
`StepVerifier` que usaste sobre `Flux.range(1, 6)` dos niveles más abajo está aquí
afirmando el resultado de una orquestación completa — y fíjate en que `.verifyComplete()`
sigue aplicando, porque el *Mono* de la saga se completó con normalidad con un resultado
que da la casualidad de que informa de un fallo; el fallo de la orquestación es dato en
el resultado, no una señal de error en el stream.

La prueba no se detiene en el objeto resultado. Se mete en el stub para demostrar el
*efecto* — que la solicitud creada por el paso raíz fue realmente eliminada, sin huérfano
alguno dejado en el (simulado) aguas abajo:

::: listing domain-lending-loan-origination/src/test/java/com/firefly/lumen/domain/saga/RegisterApplicationSagaCompensationTest.java | Listado 23.10 — demostrar que el efecto secundario se deshizo: mismo id creado y luego eliminado
        var stub = (StubLoanOriginationClient) client;
        // The application was created by the root step...
        assertThat(stub.createdApplications()).hasSize(1);
        // ...and then removed by the root step's compensation — same id, no orphan.
        assertThat(stub.removedApplications()).containsExactlyElementsOf(stub.createdApplications());
    }
:::

`createdApplications()` tiene exactamente una entrada — el paso raíz sí se ejecutó y creó
la solicitud. `removedApplications()` contiene exactamente los mismos ids — la
compensación se ejecutó y la deshizo. `containsExactlyElementsOf` ata las dos cosas:
cada id que se creó fue eliminado a continuación, así que la saga dejó el sistema limpio.
Afirmar el *efecto registrado* a través del stub, no solo el objeto resultado, es lo que
hace de esto una prueba real de corrección y no una comprobación de que el motor
*informó* de un rollback — la diferencia entre «la saga dice que compensó» y «la
escritura realmente desapareció». Esta es la afirmación de corrección más fuerte del
libro — rollback automático de una transacción distribuida parcialmente completada — y se
verifica con un contexto de Boot, una costura simulada y un `StepVerifier`. No se
persistió ningún estado de saga en disco; ningún broker entregó un mensaje por un socket.

!!! note "Término clave — compensación"
    En el patrón saga, la **compensación** es el acto de deshacer un paso completado
    cuando un paso posterior falla — el sustituto en sistemas distribuidos de un rollback
    de base de datos, que no puede abarcar servicios independientes. Cada paso que muta
    estado declara un método `compensate`; el motor invoca esos métodos, en orden
    inverso, para los pasos que ya tuvieron éxito. Probar la compensación significa
    forzar un fallo aguas abajo y afirmar que el deshacer se ejecutó — que es precisamente
    lo que hacen los Listados 23.9 y 23.10.

La capa de dominio lleva dos pruebas más que merece la pena nombrar, porque redondean
este nivel. `RegisterLoanApplicationHandlerTest` ejercita el manejador de comandos *sin*
un contexto — hace `new` del manejador sobre el stub y un `EventPublisher` simulado con
Mockito, afirma que `handle(...)` llama a la costura del core y devuelve el nuevo id con
`StepVerifier`, y usa un `ArgumentCaptor` para demostrar que el manejador publica un
`LoanApplicationRegisteredEvent` tipado bajo su tipo de evento canónico. Y
`LoanApplicationEventListenerTest` arranca el contexto para demostrar que un método
anotado con `@EventListener` está cableado en el runtime de EDA de Firefly: conduce
directamente el `processEvent(payload, headers)` del `EventListenerProcessor` real — el
mismo componente al que cada transporte (Kafka, RabbitMQ, en JVM) canaliza los mensajes
entregados — y afirma que el bean grabador recibió el evento, sin broker y sin Docker. La
prueba del manejador es Nivel 2 hecho contra colaboradores del framework; la prueba del
listener es una integración al estilo de Nivel 4 que solo elide el salto del transporte
externo.

## Sin Docker: cómo las capas superiores se mantienen libres de contenedores

Merece la pena detenerse en la frase recurrente de este capítulo, porque es una genuina
decisión de diseño y no un atajo solo para el sample. Cada prueba de estos tres módulos
— incluso la porción web de contexto completo y la prueba de compensación de saga — se
ejecuta sin un demonio de Docker, sin docker-compose y sin nada preinstalado. Dos
sustituciones lo hacen posible.

Para la persistencia, la porción web del core se ejecuta contra **H2 en modo R2DBC**:
una base de datos SQL reactiva real que vive dentro de la JVM. La capa de datos de
Firefly habla R2DBC, y el driver R2DBC para H2 permite que el mismo código de repositorio
que se ejecuta contra Postgres en producción se ejecute contra una base de datos en
memoria en la prueba, con una migración de Flyway aplicando el esquema en el arranque.
(Como indica el README, el reactor usa el driver R2DBC de H2 para el acceso en tiempo de
ejecución y su driver JDBC para el paso de migración de Flyway.) El camino de
persistencia se ejercita de verdad; solo el *motor que hay detrás* es el ligero, dentro
de la JVM.

Para la mensajería y la orquestación, los runtimes de EDA y de saga se ejecutan **en
proceso**. Como mostró el Capítulo 11, enlazar un listener a
`PublisherType.APPLICATION_EVENT` enruta los eventos por el bus de eventos dentro de la
JVM de Spring en lugar de por Kafka, así que la prueba de EDA despacha a través del
`EventListenerProcessor` real sin broker. El motor de saga, igualmente, se ejecuta
enteramente dentro del contexto de Boot; lo único simulado es la llamada del SDK al
servicio aguas abajo ausente. Y la porción del BFF simula su única costura aguas abajo. En
cada caso el *runtime del framework* es real — el descubrimiento de anotaciones, el
enrutamiento, la compensación, todo se ejecuta — y solo se elide el salto de red externo.

El resultado es una suite totalmente autocontenida: clona, `mvn verify`, observa cómo
pasa. Eso no es poca cosa. Una suite de pruebas que necesita infraestructura es una suite
que se ejecuta en CI y en ningún otro sitio; una suite que se ejecuta en cualquier parte
se ejecuta *constantemente*, que es donde su valor se acumula.

!!! warning "Los sustitutos dentro de la JVM verifican el cableado, no el backend real"
    H2 no es Postgres, y el bus de eventos dentro de la JVM no es Kafka. Estos sustitutos
    ejercitan fielmente *tu* código — repositorios, listeners, pasos de saga — pero no
    atrapan SQL específico del dialecto, el comportamiento de las restricciones de
    Postgres, el particionado de Kafka, el rebalanceo de grupos de consumidores ni la
    serialización a través de un cable real. Para eso necesitas una prueba contra el
    backend genuino, que es la siguiente sección. Trata las capas libres de contenedores
    como cobertura exhaustiva de tu lógica y cobertura *parcial* de tu infraestructura.

## Dónde encaja Testcontainers

La pirámide tal como está construida culmina en sustitutos dentro de la JVM, y para la
inmensa mayoría de tus pruebas ese es el techo correcto — rápido, determinista,
ejecutable en cualquier parte. Pero la advertencia de arriba es real: algunos errores
solo aparecen contra el backend genuino. Ahí es donde pertenece **Testcontainers** — una
cúspide fina y deliberada por encima de la capa dentro de la JVM, ejecutada con
moderación, para el puñado de pruebas que deben demostrar el comportamiento contra
Postgres real o Kafka real.

Testcontainers es una biblioteca que arranca un contenedor Docker desechable durante la
vida de una prueba y lo derriba después. Apuntas tu URL de R2DBC o tus servidores de
arranque de Kafka al contenedor y ejecutas la misma prueba que de otro modo ejecutarías
contra H2 o el bus dentro de la JVM — pero ahora contra el motor real. La forma es
ilustrativa aquí porque el reactor no incluye tal prueba; Lumen se mantiene libre de
contenedores por diseño. Si fueras a añadir una, se leería así:

```java
// Illustrative: a Testcontainers integration test against real Postgres.
// Not in the reactor — Lumen's suite is container-free by design.
@SpringBootTest
@Testcontainers
class LoanApplicationPostgresIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void r2dbcProps(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () ->
                "r2dbc:postgresql://" + postgres.getHost()
                        + ":" + postgres.getFirstMappedPort()
                        + "/" + postgres.getDatabaseName());
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);
    }

    // ... the same WebTestClient assertions as the H2 slice, now against real Postgres
}
```

`@Container` arranca un contenedor de Postgres 16; `@DynamicPropertySource` reescribe las
propiedades de conexión de R2DBC para apuntarlas a él antes de que el contexto arranque;
el cuerpo de la prueba es por lo demás idéntico al de la porción de H2. Un contenedor de
Kafka sigue el mismo patrón con `KafkaContainer` y un listener
`PublisherType.KAFKA`, demostrando el comportamiento real de particiones y de grupos de
consumidores que el bus dentro de la JVM no puede.

La decisión de criterio es *cuántas* de estas escribir. Una prueba de Testcontainers
cuesta segundos de arranque de contenedor y requiere un demonio de Docker, así que
invierte la pirámide en cuanto abusas de ella. Resérvala para lo que genuinamente
necesita el backend real — una migración específica de Postgres, un escenario de
rebalanceo de Kafka, un contrato de serialización — y mantén la cobertura amplia en las
capas de H2 y dentro de la JVM. La pirámide sigue siendo una pirámide: una base y un
centro anchos y libres de contenedores, coronados por una banda fina y deliberada de
pruebas contra el backend real.

!!! spring "Equivalente en Spring"
    Testcontainers, `@DynamicPropertySource` y la extensión de JUnit `@Testcontainers`
    son todo Spring Boot y Testcontainers a secas — sin Firefly involucrado. Como las
    capas de datos y de EDA de Firefly se configuran mediante propiedades corrientes
    `spring.r2dbc.*` y `firefly.eda.*`, apuntar una prueba a un backend en contenedor es
    la misma sobrescritura de propiedades que escribirías en cualquier servicio de Spring
    Boot. El framework no se interpone en el camino de las pruebas contra el backend
    real; simplemente hace que las necesites menos.

## Ejecútalo

Ejecuta las pruebas del reactor entero con un solo `verify` desde el directorio
`samples/lumen-lending`:

```text
mvn clean verify
```

Los tres módulos pasan todas las pruebas. El módulo **core** ejecuta **dieciocho**
pruebas — `MoneyTest` (3), `LoanApplicationTest` (6), `ReactiveModelTest` (6) y la
porción `LoanApplicationControllerTest` (3). El módulo **domain** ejecuta **seis** —
`DomainLendingApplicationTest` (1), `RegisterLoanApplicationHandlerTest` (2),
`LoanApplicationEventListenerTest` (1), `RegisterApplicationSagaHappyPathTest` (1) y
`RegisterApplicationSagaCompensationTest` (1). El módulo **experience** ejecuta
**nueve** — `ExpLendingApplicationTest` (1), `ApplicationServiceTest` (4) y la porción
`ApplicationControllerTest` (4). Treinta y tres pruebas en verde a lo largo de los cuatro
niveles de la pirámide, en las tres capas:

```text
core-lending-loan-origination ..... Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
domain-lending-loan-origination ... Tests run: 6,  Failures: 0, Errors: 0, Skipped: 0
exp-lending ....................... Tests run: 9,  Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Si quieres una sola capa en lugar del reactor entero, acota con `-pl`, por ejemplo
`mvn -q -pl core-lending-loan-origination test` para las dieciocho pruebas del core, o
añade `-Dtest=RegisterApplicationSagaCompensationTest` para ejecutar solo la prueba del
vértice.

!!! tip "Punto de control"
    Ejecuta `mvn clean verify` desde `samples/lumen-lending` y confirma que los tres
    módulos informan de `Failures: 0, Errors: 0` y que la compilación termina en
    `BUILD SUCCESS`. Fíjate en el tiempo de reloj — la suite entera de treinta y tres
    pruebas, incluidos cuatro contextos de Spring arrancados y una compensación de saga
    completa, termina en segundos sin Docker en ejecución. Si ves
    `Unable to load ...MacOSDnsServerAddressStreamProvider` en el log, ignóralo — es una
    advertencia inofensiva de DNS de Netty en macOS, no un fallo de prueba.

!!! note "Término clave — la fase verify"
    La fase **`verify`** de Maven ejecuta todo a través de las pruebas de integración:
    compila, ejecuta la fase `test` (pruebas unitarias y de porción vía Surefire),
    empaqueta cada módulo y ejecuta cualquier prueba de integración (Failsafe).
    `mvn clean verify` es el comando «¿está en verde?» para toda la flota — más fuerte
    que `mvn test` porque también empaqueta y ejecutaría un `*IT` de Failsafe como el
    `LoanApplicationPostgresIT` ilustrativo de arriba. Para Lumen, sin clases `*IT`, es
    el único comando que demuestra las treinta y tres pruebas y una compilación limpia.

### Un humo de extremo a extremo opcional

Las treinta y tres pruebas demuestran cada capa de forma aislada, con cada aguas abajo
simulado. Si quieres observar en su lugar el camino *en vivo* de tres capas — el cableado
real exp → domain → core en lugar de un stub — el README lo documenta. Arranca las tres
apps (`core` en `8081`, `domain` en `8082`, `exp` en `8080`) y haz POST de una solicitud
al BFF:

```text
$ curl -s -X POST localhost:8080/api/v1/experience/lending/applications \
    -H 'Content-Type: application/json' \
    -d '{"productId":"11111111-1111-1111-1111-111111111111","requestedAmount":25000.00,"term":36,"purpose":"HOME_IMPROVEMENT","simulationId":"22222222-2222-2222-2222-222222222222"}'
```

```json
{
  "applicationId": "786544c7-2f10-4110-95fe-682d63edbace",
  "simulationId": "22222222-2222-2222-2222-222222222222",
  "status": "SUBMITTED",
  "requestedAmount": 25000.00,
  "term": 36,
  "purpose": "HOME_IMPROVEMENT",
  "createdAt": "2026-06-17T11:46:21.186231",
  "updatedAt": "2026-06-17T11:46:21.186231"
}
```

Ese `201 SUBMITTED` fluyó exp → domain (se ejecutó la `RegisterApplicationSaga`) → core
(el paso raíz de la saga escribió en el sistema de registro por HTTP), y el id asignado
por el core volvió a través de ambas costuras. En la consola de la capa de dominio puedes
observar cómo la saga conduce la escritura — la misma orquestación que la prueba de
compensación afirma sin interfaz, ahora narrada en vivo:

```text
[orchestration] started   name=RegisterApplicationSaga ... pattern=SAGA
[orchestration] step.success ... stepId=registerLoanApplication latencyMs=94
[orchestration] step.success ... stepId=proposeOffer
[orchestration] step.success ... stepId=registerApplicant
[orchestration] completed name=RegisterApplicationSaga ... success=true
```

Esta prueba de humo es la contraparte en vivo de la suite: donde el Nivel 4 simula la
costura del core y afirma el objeto resultado, la pila en vivo ejecuta el salto HTTP real
y te deja hacer `GET` para recuperar la solicitud desde el core por el id que el BFF
devolvió. Usa la suite para una retroalimentación constante y determinista; usa el humo
para una comprobación de sanidad de extremo a extremo ocasional de que las costuras están
cableadas.

## Lo que has aprendido {.recap}

- Un servicio Firefly se prueba como una **pirámide**: una base ancha de pruebas
  unitarias simples, una banda de pruebas reactivas con `StepVerifier`, **pruebas de
  porción** web con `@SpringBootTest` y `WebTestClient`, y una fina cúspide de pruebas de
  orquestación/integración — **treinta y tres** a lo largo de las tres capas de Lumen
  (core 18, domain 6, exp 9), con la misma forma de cuatro capas repetida por servicio,
  más ancha en el sistema de registro.
- El **Nivel 1** es Java simple — `MoneyTest` y `LoanApplicationTest` afirman invariantes
  y transiciones de estado sin Spring y sin publicadores, en microsegundos, porque la
  lógica de dominio vive en los objetos.
- El **Nivel 2** usa `StepVerifier` de `reactor-test` para afirmar la secuencia de
  señales de un publicador sin `.block()`; la misma herramienta escala desde
  `Flux.range` pasando por las señales de error y el **tiempo virtual** hasta el
  `ApplicationServiceTest` de la capa de experiencia, que afirma la validación real y el
  mapeo a `BusinessException` de un aguas abajo ausente — todo sin arrancar un contexto.
- El **Nivel 3**, la porción web, arranca el contexto reactivo completo. La porción del
  core se ejecuta contra **H2 en memoria en modo R2DBC** (esquema vía Flyway, sin Docker)
  y conduce el borde con `WebTestClient`, verificando de verdad la validación, el `404` de
  RFC 7807 y la ida y vuelta de persistencia; la porción del BFF usa
  `@AutoConfigureWebTestClient` (sin socket), conserva sus anotaciones `@Secure` reales y
  sustituye un stub por su única costura aguas abajo.
- El **Nivel 4** demuestra la **compensación** de saga: una `@TestConfiguration`
  intercambia un stub que hace fallar `proposeOffer`, y la prueba afirma que
  `compensatedSteps()` contiene `registerLoanApplication` y que cada id de solicitud
  creada fue eliminado — rollback distribuido completo, sin broker y sin contenedor —
  medido contra una prueba de camino feliz que afirma que los tres pasos se ejecutaron sin
  nada compensado.
- Las capas superiores se mantienen **libres de contenedores** sustituyendo H2 por
  Postgres, el bus de eventos dentro de la JVM por Kafka y stubs en memoria por las
  costuras de SDK aguas abajo, manteniendo el runtime del framework real y eludiendo solo
  la red. `mvn clean verify` ejecuta las treinta y tres en verde; **Testcontainers** es la
  cúspide deliberada y fina para las pocas pruebas que deben ejecutarse contra el Postgres
  o el Kafka genuinos.

## Pruébalo tú mismo {.exercises}

1. **Añade un invariante al Nivel 1.** En `MoneyTest`, añade una prueba de que
   `Money.of(0)` está permitido (cero es una cantidad válida) y de que sumar dos
   cantidades devuelve su suma, reflejando el estilo existente de
   `minusReturnsTheDifference`. Ejecuta `-Dtest=MoneyTest` y mantenla en verde — un hecho
   nuevo fijado en microsegundos.
2. **Afirma un método de servicio reactivo con StepVerifier.** Mira
   `ApplicationServiceTest.createApplication_derivesDeterministicIdempotencyKey` y escribe
   una prueba hermana que envíe *dos* peticiones lógicamente idénticas y afirme que el
   stub grabó la *misma* clave de idempotencia ambas veces. Confirma que se ejecuta sin
   arrancar un contexto — esa es la disciplina del Nivel 2, y demuestra que un reintento
   es seguro.
3. **Extiende una porción web.** En el `LoanApplicationControllerTest` del core, añade una
   prueba que envíe una petición con `currency = "XYZ"` y afirme
   `.expectStatus().isBadRequest()` más `.jsonPath("$.status").isEqualTo(400)`,
   demostrando que `@ValidCurrencyCode` se aplica en el borde arrancado. Vuelve a ejecutar
   `-Dtest=LoanApplicationControllerTest`.
4. **Rompe la compensación y observa cómo falla.** En `RegisterApplicationSaga`, elimina
   temporalmente el atributo `compensate` del `@SagaStep` del paso raíz, luego ejecuta
   `-Dtest=RegisterApplicationSagaCompensationTest`. Lee el fallo de aserción sobre
   `compensatedSteps()` — la raíz ya no hace rollback — y después restaura el atributo.
   Acabas de demostrar qué es lo que la prueba está realmente protegiendo. Por contraste,
   ejecuta `RegisterApplicationSagaHappyPathTest` y confirma que sigue pasando — solo el
   camino que falla necesita la compensación.
5. **Esboza una cúspide de Testcontainers.** Toma el `LoanApplicationPostgresIT`
   ilustrativo de arriba y lista, para tu propio servicio, exactamente qué una o dos
   pruebas necesitan genuinamente Postgres real (¿una migración específica del dialecto?
   ¿una restricción de unicidad?) y cuáles pertenecen a la capa de H2. Escribe la lista
   como un comentario — el objetivo es una cúspide *fina*, no una segunda suite completa.

## Adónde ir ahora

Ya puedes probar un servicio Firefly en cada nivel y saber a qué nivel pertenece cada
comportamiento. Los próximos capítulos pasan de demostrar que un servicio es correcto a
ejecutarlo bien: la observabilidad, para que un servicio en producción te diga qué está
haciendo, y las preocupaciones operativas — configuración, salud, despliegue — que llevan
a Lumen Lending de una suite de pruebas en verde a un sistema en el que puedes confiar
estando de guardia.
