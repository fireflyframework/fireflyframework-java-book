Todos los capítulos hasta ahora han terminado de la misma forma: un comando `mvn` y
una línea de salida en verde. No era un adorno. Es la afirmación central de este
libro hecha operativa: que un servicio Firefly, con toda su fontanería reactiva, su
orquestación de sagas y su enrutamiento de eventos, puede *probarse como código
ordinario*, rápido y sin un solo contenedor. Este capítulo da un paso atrás respecto
a la construcción de funcionalidades y mira de frente a las pruebas en sí: qué tipos
incluye Lumen Lending, cómo forman una pirámide y por qué la suite completa se
ejecuta en segundos en un portátil sin un demonio de Docker a la vista.

Ya tienes el vocabulario. Conociste `StepVerifier` en el Capítulo 5, el corte
(*slice*) con `WebTestClient` en el Capítulo 6 y la prueba de EDA sin broker en el
Capítulo 11. Aquí los ensamblas en una estrategia deliberada. Los dos módulos de
originación de préstamos del reactor llevan veinticuatro pruebas repartidas en cuatro
niveles —unitaria pura, reactiva con `StepVerifier`, corte web con contexto completo
y una prueba de integración de compensación de saga— y leeremos una prueba
representativa de cada nivel, en orden, de la más barata a la más exhaustiva. Al
final sabrás exactamente a qué tipo de prueba recurrir, y por qué la más exigente de
la suite sigue sin necesitar red.

Las pruebas viven en dos directorios. Las del módulo core están bajo
`core-lending-loan-origination/src/test/java/com/firefly/lumen/core/` (con los
subpaquetes `domain/` y `web/`); las del módulo de dominio están bajo
`domain-lending-loan-origination/src/test/java/com/firefly/lumen/domain/` (con un
subpaquete `saga/`). Tomaremos un corte de cada nivel y terminaremos ejecutando ambos
módulos en verde juntos.

## La pirámide de pruebas, edición Firefly

La pirámide de pruebas es una idea antigua: tener muchas pruebas rápidas y estrechas
en la base, menos pruebas de alcance medio en el centro y un número reducido de
pruebas amplias y lentas en la cima. La forma importa porque las pruebas baratas de la
base detectan la mayoría de las regresiones en milisegundos, mientras que las caras de
la cima —las que arrancan un contexto o hablan con un broker— se reservan para el
cableado que las pruebas unitarias no pueden alcanzar.

La suite de Lumen Lending encaja limpiamente en cuatro capas:

- **Pruebas unitarias puras** — un objeto puro bajo prueba, sin Spring, sin tipos
  reactivos, sin E/S. `MoneyTest` y `LoanApplicationTest` viven aquí. Microsegundos
  cada una.
- **Pruebas unitarias reactivas** — todavía sin Spring, pero la unidad bajo prueba
  devuelve un `Mono` o `Flux`, así que afirmas las señales con `StepVerifier`.
  `ReactiveModelTest` es el recorrido puro; las pruebas de saga usan la misma
  herramienta con beans reales.
- **Pruebas de corte web** — `@SpringBootTest` arranca el contexto reactivo completo
  contra una H2 en memoria y dirige la superficie HTTP con `WebTestClient`. Una por
  borde de servicio. `LoanApplicationControllerTest` es el ejemplo.
- **Pruebas de orquestación / integración** — `@SpringBootTest` de nuevo, pero
  ejercitando una saga o un flujo EDA completos de extremo a extremo a través del
  runtime del framework. La `RegisterApplicationSagaCompensationTest` es el titular
  aquí.

!!! note "Termino clave — pirámide de pruebas"
    Una **pirámide de pruebas** describe la *proporción* saludable de pruebas por
    alcance: una base ancha de pruebas unitarias rápidas y aisladas, una banda más
    estrecha de pruebas de integración y una fina cúspide de pruebas de extremo a
    extremo. Invertirla —apoyarse en pruebas lentas y amplias para detectar errores que
    una prueba unitaria debería haber detectado— te deja una suite lenta de ejecutar y
    lenta de diagnosticar. El diseño de Firefly mantiene la base barata a propósito: la
    lógica de dominio es Java puro, así que la mayoría de tus pruebas nunca arrancan un
    contexto.

Lo que hace inusual esta pirámide es la **cima**. En la mayoría de las pilas
empresariales, las capas superiores exigen infraestructura: un contenedor de Postgres,
un broker de Kafka, un fichero docker-compose que vigilas. Las capas superiores de
Firefly no lo hacen. El corte web se ejecuta contra H2 hablando R2DBC; las pruebas de
saga y de EDA ejecutan los runtimes de orquestación y de eventos en el proceso. La
suite entera es `mvn test` y nada más. Veremos exactamente cómo en cada nivel, y
luego nombraremos dónde sí *pertenecen* los contenedores reales.

## Nivel 1 — Pruebas unitarias puras

La base de la pirámide es el modelo de dominio probado como Java puro. Sin
anotaciones que arranquen un contexto, sin publicadores, sin mocks de tipos del
framework: solo construyes un objeto, llamas a un método y afirmas el resultado. Estas
pruebas no cuestan nada de ejecutar y fijan las reglas que más importan: las que viven
dentro de tus agregados y objetos de valor.

`MoneyTest` es el ejemplo más pequeño del reactor. `Money` es el objeto de valor de
unidades menores del core, y la prueba enuncia tres invariantes como tres hechos de
una línea:

::: listing core-lending-loan-origination/src/test/java/com/firefly/lumen/core/MoneyTest.java | Listado 23.1 — una prueba unitaria pura: sin Spring, sin tipos reactivos
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

Esto es JUnit 5 y nada más. `assertThrows` fija las cláusulas de guarda: `Money` se
niega a existir como una cantidad negativa, y una resta que iría por debajo de cero
falla ruidosamente en lugar de producir silenciosamente un saldo erróneo.
`assertEquals` comprueba la aritmética del caso feliz. No hay `@SpringBootTest`, ni
`@Autowired`, ni `Mono`. Una prueba como esta se ejecuta en microsegundos y nunca es
inestable, porque no hay nada asíncrono ni externo que pueda fallar de forma
intermitente.

El mismo nivel asciende hasta el agregado. `LoanApplicationTest` construye una
`LoanApplication` con su builder y la recorre por sus transiciones de estado legales
—enteramente en memoria, sin persistencia:

::: listing core-lending-loan-origination/src/test/java/com/firefly/lumen/core/domain/LoanApplicationTest.java | Listado 23.2 — probando unitariamente la máquina de estados de un agregado en memoria
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

El agregado impone sus propias reglas —`cannotApproveADraft` afirma que llamar a
`approve()` sobre un `DRAFT` lanza `IllegalStateException`— y verificas cada una de
ellas sin un viaje de ida y vuelta a la base de datos, porque las reglas viven en el
objeto, no en la tabla. Este es el dividendo de mantener la lógica de dominio en Java
puro: el comportamiento más importante del sistema es también el más barato de probar.

!!! tip "Punto de control"
    Ejecuta solo las dos clases unitarias desde `samples/lumen-lending`:

    ```text
    mvn -q -pl core-lending-loan-origination -Dtest=MoneyTest,LoanApplicationTest test
    ```

    Deberías ver `Tests run: 9, Failures: 0` —tres de `MoneyTest`, seis de
    `LoanApplicationTest`. Fíjate en el tiempo transcurrido en el informe:
    milisegundos de un solo dígito. Esa velocidad es la razón por la que la base de la
    pirámide debe ser ancha.

## Nivel 2 — Pruebas unitarias reactivas con StepVerifier

Un nivel por encima, la unidad bajo prueba devuelve un publicador. No puedes hacer
`assertEquals` sobre un `Mono` —es una receta, no un valor—, así que te suscribes y
afirmas la secuencia de señales con `StepVerifier`, exactamente como enseñó el
Capítulo 5. Es crucial que esto siga siendo una prueba *unitaria*: no arranca ningún
contexto, no se abre ningún puerto. `ReactiveModelTest` es la forma más pura,
afirmando contra publicadores construidos a mano:

::: listing core-lending-loan-origination/src/test/java/com/firefly/lumen/core/ReactiveModelTest.java | Listado 23.3 — afirmando las señales de un publicador sin Spring ni red
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
orden; `.verifyComplete()` afirma el `onComplete` terminal *y ejecuta la
verificación*. Todo el conjunto se ejecuta de forma síncrona en el hilo de prueba en
microsegundos. Esta es la forma canónica de probar cualquier método reactivo que
escribas —un método de servicio, un mapeador, un operador personalizado— porque tira
de los valores a través de la tubería sin `.block()` y afirma con precisión lo que el
flujo emitió.

La misma herramienta escala para probar código reactivo que *sí* involucra beans
reales del framework, sin dejar de afirmar a través de `StepVerifier`. Lo viste en el
Capítulo 11: la prueba del listener de EDA dirige el `EventListenerProcessor` y
envuelve el despacho en un `StepVerifier`. La lección es que `StepVerifier` no está
atado al nivel unitario: es como afirmas *cualquier* publicador, ya provenga de
`Flux.range` o de un motor de sagas. Lo veremos de nuevo en la cima misma de la
pirámide.

!!! spring "Equivalente en Spring"
    `StepVerifier` se incluye en `reactor-test`, un artefacto puro de Project Reactor
    sin nada de Firefly dentro: una app Spring WebFlux corriente prueba el código
    reactivo de la forma idéntica. Firefly no añade nada a la herramienta; simplemente
    te da más código reactivo digno de probar con ella, y mantiene `reactor-test` en el
    classpath de pruebas a través del starter del core para que nunca cablees la
    dependencia tú mismo.

## Nivel 3 — El corte web con @SpringBootTest y WebTestClient

Ahora la pirámide se estrecha. Para probar el borde HTTP necesitas el contexto real:
el controlador, el servicio, los validadores, el repositorio R2DBC, el manejador
global de excepciones y los filtros web, todos cableados juntos tal como están en
tiempo de ejecución. Eso es lo que te da `@SpringBootTest`, y lo que convierte el
corte web de Lumen en la primera prueba de este capítulo que arranca Spring.

El coste es un arranque de contexto de un par de segundos. La recompensa es que
verificas la *integración* de todo el borde: que `@Valid` realmente se dispara, que
`ResourceNotFoundException` realmente se convierte en un *problem detail* `404`, que el
JSON realmente se serializa a través de los códecs configurados. Nada de eso puede
alcanzarse con una prueba unitaria, porque nada de ello vive en un único objeto.

Así arranca el corte y adquiere su cliente:

::: listing core-lending-loan-origination/src/test/java/com/firefly/lumen/core/web/LoanApplicationControllerTest.java | Listado 23.4 — arrancando el contexto reactivo completo y enlazando un WebTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoanApplicationControllerTest {

    @Autowired
    private WebTestClient client;
:::

`@SpringBootTest` arranca el contexto de la aplicación. `webEnvironment = RANDOM_PORT`
arranca un servidor reactivo real en un puerto efímero e inyecta un `WebTestClient`
enlazado a él. El `WebTestClient` es el análogo reactivo de `MockMvc`: un cliente HTTP
no bloqueante que dirige tus endpoints y te deja afirmar sobre el estado, las
cabeceras y el cuerpo JSON con una cadena fluida.

Con el contexto en marcha, cada prueba se lee como una petición y un conjunto de
expectativas:

::: listing core-lending-loan-origination/src/test/java/com/firefly/lumen/core/web/LoanApplicationControllerTest.java | Listado 23.5 — dirigiendo la API y afirmando sobre el cuerpo RFC 7807
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
`.expectBody().jsonPath("$.status").isEqualTo(404)` se adentra en el JSON y demuestra
que el *problem detail* es un cuerpo real, no una mera línea de estado. La prueba
`createsAnApplicationAndReadsItBack` de la misma clase hace un viaje completo de ida y
vuelta POST-y-luego-GET, y `rejectsAnInvalidPayload` envía una cantidad negativa para
confirmar que `@ValidAmount` produce un `400`. Tres pruebas, un contexto arrancado,
todo el borde verificado.

Fíjate en el detalle que hace esto práctico. El comentario de la clase lo expresa
mejor:

> arranca el contexto reactivo completo contra una H2 en memoria (runtime de R2DBC +
> migración Flyway, sin Docker).

El repositorio bajo prueba es un repositorio R2DBC *real* corriendo contra una base de
datos H2 en memoria en modo R2DBC, con el esquema aplicado por una migración Flyway al
arranque. No hay un repositorio mockeado ni hay un contenedor. La ruta de persistencia
se ejercita de verdad —guardados, lecturas, el viaje de ida y vuelta— contra una base
de datos que vive enteramente dentro de la JVM y desaparece cuando la prueba termina.
Así es como una prueba de corte tan exhaustiva como esta sigue ejecutándose en un par
de segundos sin nada instalado.

!!! note "Termino clave — prueba de corte"
    Una **prueba de corte** (*slice test*) arranca lo suficiente de la aplicación para
    ejercitar una capa de extremo a extremo —aquí, todo el borde HTTP hasta la base de
    datos— usando un contexto real en lugar de mocks. Se sitúa por encima de las pruebas
    unitarias (arranca Spring) y por debajo de una prueba de integración externa
    completa (usa sustitutos en la JVM como H2 en lugar del Postgres de producción). Es
    la prueba de mayor valor por segundo en la mayoría de los servicios: lo bastante
    amplia para detectar errores de cableado, lo bastante rápida para ejecutarse en cada
    guardado.

!!! spring "Equivalente en Spring"
    `@SpringBootTest` con `RANDOM_PORT` y un `WebTestClient` inyectado es Spring Boot de
    serie: Firefly no reemplaza el arnés de pruebas, lo aprovecha. Lo que el contexto
    arrancado contiene *sí es* Firefly: el `GlobalExceptionHandler` autoconfigurado, los
    validadores, los filtros de idempotencia y de transacción del Capítulo 6. Así, el
    mismo mecanismo de prueba de Boot verifica gratis el comportamiento transversal del
    framework, sin que tú registres nada de ello en la prueba.

## Nivel 4 — La prueba de compensación de saga

En el vértice se sienta la prueba más exigente de la suite: demostrar que cuando un
paso de la saga falla, el framework *compensa* los pasos que ya habían tenido éxito,
sin dejar ninguna escritura huérfana detrás. Esta es la prueba que cumple la promesa
central del capítulo de orquestación, y vale la pena leerla entera porque muestra
cuánto puedes verificar con `@SpringBootTest` y `StepVerifier` y aun así sin broker,
sin contenedor, sin un downstream real.

Recuerda la forma de la saga del capítulo de orquestación: `registerLoanApplication`
es el paso raíz, y tanto `registerApplicant` como `proposeOffer` lo `dependsOn`. El
paso raíz declara `compensate = removeLoanApplication`. Si un paso dependiente falla
después de que la raíz tuviera éxito, el motor debe ejecutar la compensación de la
raíz. La prueba fuerza exactamente ese fallo intercambiando un stub configurado para
hacer que `proposeOffer` lance una excepción:

::: listing domain-lending-loan-origination/src/test/java/com/firefly/lumen/domain/saga/RegisterApplicationSagaCompensationTest.java | Listado 23.6 — forzando el fallo de un paso dependiente con un bean stub de ámbito de prueba
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

`@SpringBootTest` arranca el contexto de dominio —el motor de sagas, el bus de CQRS,
los manejadores de comandos— todo real. La `@TestConfiguration` con `@Import`
sobrescribe un bean: el `LoanOriginationClient` se convierte en un
`StubLoanOriginationClient` configurado para `failProposeOffer()`. Esta es la costura.
El runtime del framework es genuino; solo se sustituye con un stub la frontera del SDK
hacia el servicio downstream (ausente), de modo que la prueba puede provocar un fallo
de forma determinista sin red. Sustituir un único bean en el borde, en lugar de
mockear el motor, es lo que mantiene la orquestación bajo prueba *real*.

La afirmación es donde se demuestra la compensación:

::: listing domain-lending-loan-origination/src/test/java/com/firefly/lumen/domain/saga/RegisterApplicationSagaCompensationTest.java | Listado 23.7 — afirmando el fallo, el paso fallido y la raíz compensada
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

Lee la cadena de afirmaciones como la historia que cuenta la saga.
`service.submitApplication(...)` devuelve un `Mono` del resultado de la saga;
`StepVerifier.create(...)` se suscribe y `.assertNext(...)` inspecciona el único
resultado emitido. El resultado informa `isFailed()` —la saga en su conjunto no tuvo
éxito— y `failedSteps()` contiene `STEP_PROPOSE_OFFER`, el paso dependiente que lanzó
la excepción. La línea decisiva es la última: `compensatedSteps()` contiene
`STEP_REGISTER_LOAN_APPLICATION`, demostrando que el motor ejecutó la compensación
`removeLoanApplication` del paso raíz tras el fallo del dependiente. El mismo
`StepVerifier` que usaste sobre `Flux.range(1, 6)` dos niveles más abajo está aquí
afirmando el resultado de una orquestación completa.

La prueba no se detiene en el objeto resultado. Se adentra en el stub para demostrar
el *efecto*: que la solicitud creada por el paso raíz fue efectivamente eliminada, sin
ningún huérfano en el downstream (stub):

::: listing domain-lending-loan-origination/src/test/java/com/firefly/lumen/domain/saga/RegisterApplicationSagaCompensationTest.java | Listado 23.8 — demostrando que el efecto secundario se deshizo: mismo id creado y luego eliminado
        var stub = (StubLoanOriginationClient) client;
        // The application was created by the root step...
        assertThat(stub.createdApplications()).hasSize(1);
        // ...and then removed by the root step's compensation — same id, no orphan.
        assertThat(stub.removedApplications()).containsExactlyElementsOf(stub.createdApplications());
    }
:::

`createdApplications()` tiene exactamente una entrada: el paso raíz sí se ejecutó y
creó la solicitud. `removedApplications()` contiene exactamente los mismos ids: la
compensación se ejecutó y la deshizo. `containsExactlyElementsOf` ata ambos: cada id
que se creó fue posteriormente eliminado, así que la saga dejó el sistema limpio. Esta
es la afirmación de corrección más fuerte del libro —reversión automática de una
transacción distribuida parcialmente completada— y se verifica con un contexto de
Boot, una costura con stub y un `StepVerifier`. No se persistió ningún estado de saga
en disco; ningún broker entregó un mensaje por un socket.

!!! note "Termino clave — compensacion"
    En el patrón saga, la **compensacion** es el acto de deshacer un paso completado
    cuando un paso posterior falla: el sustituto en sistemas distribuidos de un rollback
    de base de datos, que no puede abarcar servicios independientes. Cada paso que muta
    estado declara un método `compensate`; el motor invoca esos métodos, en orden
    inverso, para los pasos que ya tuvieron éxito. Probar la compensación significa
    forzar un fallo downstream y afirmar que el deshacer se ejecutó, que es precisamente
    lo que hacen los Listados 23.7 y 23.8.

## Sin Docker: cómo las capas superiores se mantienen libres de contenedores

Vale la pena detenerse en la frase recurrente de este capítulo, porque es una decisión
de diseño genuina y no un atajo solo para el ejemplo. Cada prueba de estos dos módulos
—incluso el corte web con contexto completo y la prueba de compensación de saga— se
ejecuta sin demonio de Docker, sin docker-compose y sin nada preinstalado. Dos
sustituciones lo hacen posible.

Para la persistencia, el corte web se ejecuta contra **H2 en modo R2DBC**: una base de
datos SQL reactiva real que vive dentro de la JVM. La capa de datos de Firefly habla
R2DBC, y el driver R2DBC para H2 permite que el mismo código de repositorio que corre
contra Postgres en producción corra contra una base de datos en memoria en la prueba,
con una migración Flyway aplicando el esquema al arranque. La ruta de persistencia se
ejercita de verdad; solo el *motor que hay detrás* es el ligero en la JVM.

Para la mensajería y la orquestación, los runtimes de EDA y de saga corren **en el
proceso**. Como mostró el Capítulo 11, enlazar un listener a
`PublisherType.APPLICATION_EVENT` enruta los eventos por el bus de eventos en la JVM de
Spring en lugar de Kafka, así que la prueba de EDA despacha a través del
`EventListenerProcessor` real sin broker. El motor de sagas igualmente corre
enteramente dentro del contexto de Boot; lo único que se sustituye con un stub es la
llamada del SDK al servicio downstream ausente. En ambos casos el *runtime del
framework* es real —el descubrimiento por anotaciones, el enrutamiento, la
compensación, todo se ejecuta— y solo se elide el salto a la red externa.

El resultado es una suite completamente autocontenida: clonas, `mvn test`, y la ves
pasar. Eso no es poca cosa. Una suite de pruebas que necesita infraestructura es una
suite que corre en CI y en ningún otro sitio; una suite que corre en cualquier parte
corre *constantemente*, que es donde su valor se compone.

!!! warning "Los sustitutos en la JVM verifican el cableado, no el backend real"
    H2 no es Postgres, y el bus de eventos en la JVM no es Kafka. Estos sustitutos
    ejercitan fielmente *tu* código —repositorios, listeners, pasos de saga— pero no
    detectan SQL específico del dialecto, el comportamiento de las restricciones de
    Postgres, el particionado de Kafka, el rebalanceo de grupos de consumidores ni la
    serialización a través de un cable real. Para eso necesitas una prueba contra el
    backend genuino, que es la siguiente sección. Trata las capas libres de contenedores
    como cobertura exhaustiva de tu lógica y cobertura *parcial* de tu infraestructura.

## Dónde encaja Testcontainers

La pirámide tal como está construida culmina en sustitutos en la JVM, y para la gran
mayoría de tus pruebas ese es el techo correcto: rápido, determinista, ejecutable en
cualquier parte. Pero la advertencia de arriba es real: algunos errores solo aparecen
contra el backend genuino. Ahí es donde pertenece **Testcontainers**: una cúspide fina
y deliberada por encima de la capa en la JVM, usada con moderación, para el puñado de
pruebas que deben demostrar el comportamiento contra Postgres real o Kafka real.

Testcontainers es una librería que arranca un contenedor Docker desechable durante la
vida de una prueba y lo desmonta después. Apuntas tu URL de R2DBC o tus servidores
bootstrap de Kafka al contenedor y ejecutas la misma prueba que de otro modo
ejecutarías contra H2 o el bus en la JVM, pero ahora contra el motor real. La forma es
ilustrativa aquí porque el reactor no incluye ninguna prueba así; Lumen se mantiene
libre de contenedores por diseño. Si añadieras una, se leería así:

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

`@Container` arranca un contenedor de Postgres 16; `@DynamicPropertySource` reescribe
las propiedades de conexión de R2DBC para apuntarlas a él antes de que el contexto
arranque; el cuerpo de la prueba es por lo demás idéntico al corte de H2. Un contenedor
de Kafka sigue el mismo patrón con `KafkaContainer` y un listener
`PublisherType.KAFKA`, demostrando el comportamiento real de particiones y de grupos
de consumidores que el bus en la JVM no puede.

La decisión de criterio es *cuántas* de estas escribir. Una prueba de Testcontainers
cuesta segundos de arranque de contenedor y requiere un demonio de Docker, así que
invierte la pirámide en el momento en que abusas de ella. Resérvala para lo que
genuinamente necesita el backend real —una migración específica de Postgres, un
escenario de rebalanceo de Kafka, un contrato de serialización— y mantén la cobertura
amplia en las capas de H2 y en la JVM. La pirámide sigue siendo una pirámide: una base
y un centro anchos y libres de contenedores, coronados por una banda fina y deliberada
de pruebas contra el backend real.

!!! spring "Equivalente en Spring"
    Testcontainers, `@DynamicPropertySource` y la extensión `@Testcontainers` de JUnit
    son todo Spring Boot y Testcontainers corrientes: nada de Firefly involucrado. Como
    las capas de datos y de EDA de Firefly se configuran mediante propiedades
    `spring.r2dbc.*` y `firefly.eda.*` ordinarias, apuntar una prueba a un backend en
    contenedor es la misma sobrescritura de propiedades que escribirías en cualquier
    servicio Spring Boot. El framework no se interpone en las pruebas contra el backend
    real; simplemente hace que las necesites menos.

## Ejecútalo

Ejecuta juntas las pruebas de ambos módulos de originación de préstamos, desde el
directorio `samples/lumen-lending`:

```text
mvn -q -pl core-lending-loan-origination,domain-lending-loan-origination test
```

Los dos módulos pasan todas las pruebas. El módulo core ejecuta **dieciocho** pruebas
—`MoneyTest` (3), `LoanApplicationTest` (6), `ReactiveModelTest` (6) y el corte
`LoanApplicationControllerTest` (3). El módulo de dominio ejecuta **seis**—
`DomainLendingApplicationTest` (1), `RegisterLoanApplicationHandlerTest` (2),
`LoanApplicationEventListenerTest` (1), `RegisterApplicationSagaHappyPathTest` (1) y
`RegisterApplicationSagaCompensationTest` (1). Veinticuatro pruebas en verde a través
de los cuatro niveles de la pirámide:

```text
core-lending-loan-origination ..... Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
domain-lending-loan-origination ... Tests run: 6,  Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

!!! tip "Punto de control"
    Ejecuta el comando de arriba y confirma que ambos módulos informan `Failures: 0,
    Errors: 0`. Fíjate en el tiempo de reloj de pared: la suite completa de
    veinticuatro pruebas, incluyendo dos contextos de Spring arrancados y una
    compensación de saga completa, termina en segundos sin Docker corriendo. Para
    observar una sola capa, acota con `-Dtest`, por ejemplo
    `-Dtest=RegisterApplicationSagaCompensationTest` para solo la prueba del vértice. Si
    ves `Unable to load ...MacOSDnsServerAddressStreamProvider` en el log, ignóralo: es
    un aviso inofensivo de DNS de Netty en macOS, no un fallo de prueba.

## Lo que has aprendido {.recap}

- Un servicio Firefly se prueba como una **pirámide**: una base ancha de pruebas
  unitarias puras, una banda de pruebas reactivas con `StepVerifier`, **pruebas de
  corte** web con `@SpringBootTest` y `WebTestClient`, y una cúspide fina de pruebas de
  orquestación/integración —veinticuatro en los dos módulos de originación de préstamos
  de Lumen.
- El **Nivel 1** es Java puro —`MoneyTest` y `LoanApplicationTest` afirman invariantes
  y transiciones de estado sin Spring y sin publicadores, en microsegundos, porque la
  lógica de dominio vive en los objetos.
- El **Nivel 2** usa `StepVerifier` de `reactor-test` para afirmar la secuencia de
  señales de un publicador sin `.block()`; la misma herramienta escala desde
  `Flux.range` hasta un resultado de saga completo.
- El **Nivel 3**, el corte web, arranca el contexto reactivo completo contra **H2 en
  memoria en modo R2DBC** (esquema vía Flyway, sin Docker) y dirige el borde con
  `WebTestClient`, verificando de verdad la validación, el `404` RFC 7807 y el viaje de
  ida y vuelta de persistencia.
- El **Nivel 4** demuestra la **compensacion** de la saga: una `@TestConfiguration`
  intercambia un stub que hace fallar `proposeOffer`, y la prueba afirma que
  `compensatedSteps()` contiene `registerLoanApplication` y que cada id de solicitud
  creada fue eliminado —reversión distribuida completa, sin broker y sin contenedor.
- Las capas superiores se mantienen **libres de contenedores** sustituyendo H2 por
  Postgres y el bus de eventos en la JVM por Kafka, manteniendo real el runtime del
  framework y elidiendo únicamente la red. **Testcontainers** es la cúspide deliberada
  y fina para las pocas pruebas que deben correr contra el Postgres o el Kafka genuinos.

## Pruebalo tu mismo {.exercises}

1. **Añade un invariante al Nivel 1.** En `MoneyTest`, añade una prueba de que
   `Money.of(0)` está permitido (cero es una cantidad válida) y de que sumar dos
   cantidades devuelve su suma, reflejando el estilo existente de
   `minusReturnsTheDifference`. Ejecuta `-Dtest=MoneyTest` y mantenla en verde: un
   nuevo hecho fijado en microsegundos.
2. **Afirma un método de servicio reactivo.** Elige un método de servicio que devuelva
   un `Mono` y escribe una prueba con `StepVerifier` que afirme el valor emitido con
   `.expectNextMatches(...)` y `.verifyComplete()`. Confirma que se ejecuta sin arrancar
   un contexto: esa es la disciplina del Nivel 2.
3. **Extiende el corte web.** En `LoanApplicationControllerTest`, añade una prueba que
   envíe una petición con `currency = "XYZ"` y afirme `.expectStatus().isBadRequest()`
   más `.jsonPath("$.status").isEqualTo(400)`, demostrando que `@ValidCurrencyCode` se
   impone en el borde arrancado. Vuelve a ejecutar `-Dtest=LoanApplicationControllerTest`.
4. **Rompe la compensación y obsérvala fallar.** En `RegisterApplicationSaga`, elimina
   temporalmente el atributo `compensate` del `@SagaStep` del paso raíz, luego ejecuta
   `-Dtest=RegisterApplicationSagaCompensationTest`. Lee el fallo de afirmación en
   `compensatedSteps()` —la raíz ya no se revierte— y luego restaura el atributo.
   Acabas de demostrar qué está protegiendo realmente la prueba.
5. **Esboza una cúspide de Testcontainers.** Toma el `LoanApplicationPostgresIT`
   ilustrativo de arriba y enumera, para tu propio servicio, exactamente qué una o dos
   pruebas necesitan genuinamente Postgres real (¿una migración específica del
   dialecto? ¿una restricción de unicidad?) y cuáles pertenecen a la capa de H2.
   Escribe la lista como un comentario: el objetivo es una cúspide *fina*, no una
   segunda suite completa.

## Adonde ir ahora

Ahora puedes probar un servicio Firefly en cada nivel y saber a qué nivel pertenece
cada comportamiento. Los próximos capítulos pasan de demostrar que un servicio es
correcto a ejecutarlo bien: observabilidad, para que un servicio en producción te diga
qué está haciendo, y las preocupaciones operativas —configuración, salud,
despliegue— que llevan a Lumen Lending de una suite de pruebas en verde a un sistema
en el que puedes confiar estando de guardia.
