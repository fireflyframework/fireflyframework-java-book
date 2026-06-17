El capítulo 6 te dio `@Transactional`, y dentro de un único servicio contra una única
base de datos es la herramienta correcta: en cuanto un paso falla, la fila nunca llega
a aparecer, porque la base de datos revierte la unidad entera. Pero registrar una
solicitud de préstamo no es una escritura a una base de datos. Crea la solicitud en el
sistema de registro del core, adjunta una parte solicitante y propone una oferta —
tres operaciones que, en una plataforma real, cruzan tres fronteras de servicio y tres
almacenes de datos. No hay ninguna transacción compartida que revertir. Si el paso de
la oferta falla después de que la solicitud ya esté escrita, `@Transactional` no puede
ayudarte: la solicitud está confirmada, en otro servicio, y ahora es huérfana.

Este es el problema de la transacción distribuida, y la respuesta es la **saga**. Una
saga descompone una operación de varios pasos en pasos discretos, cada uno con una
**compensación** — un "deshacer" explícito que el motor ejecuta si un paso posterior
falla. No hay rollback global; en su lugar, los pasos completados se *compensan* en
orden inverso, de modo que el sistema es conducido de vuelta hacia un estado coherente
ejecutando operaciones de negocio reales (eliminar la solicitud, liberar la retención)
en vez de revirtiendo filas de base de datos. La compensación es el sustituto del
rollback entre servicios, y es el corazón de este capítulo.

En este capítulo diseccionas la `RegisterApplicationSaga` de Lumen — una `@Saga` con un
`@SagaStep` raíz que crea la solicitud y dos pasos dependientes que se abren en abanico
a partir de él — y el `LoanOriginationService` que la ejecuta a través del `SagaEngine`.
Después lees el test protagonista: fuerza el fallo del paso de la oferta y demuestra que
el motor compensa el paso raíz, de modo que no queda ninguna solicitud huérfana atrás. Y
— nuevo en esta revisión — ves la *misma saga ejecutándose en vivo*: con la pila de tres
capas levantada, un único `POST` de canal al BFF de experiencia fluye `exp → domain →
core`, el paso raíz de la saga escribe una fila real en el servicio core en ejecución
sobre HTTP, y el motor registra `[orchestration] completed name=RegisterApplicationSaga
... success=true`. Todo sigue ejecutándose **sin Docker** — el core usa H2 en memoria — y
el test de compensación sigue ejecutándose **sin servicio core alguno**. Al final
conocerás dos patrones hermanos — Workflow y TCC — y sabrás cuándo recurrir a cada uno.

El reparto de archivos, todos bajo `samples/lumen-lending/domain-lending-loan-origination`:

- `src/main/java/com/firefly/lumen/domain/saga/RegisterApplicationSaga.java` — la
  `@Saga` en sí: tres métodos `@SagaStep`, sus deshaceres `compensate`, la topología
  `dependsOn` y un `@StepEvent` en cada paso.
- `src/main/java/com/firefly/lumen/domain/service/LoanOriginationService.java` — el
  servicio que ensambla los `StepInputs` y llama a `SagaEngine.execute(...)`, leyendo
  de vuelta un `SagaResult`.
- `src/main/java/com/firefly/lumen/domain/web/LoanOriginationController.java` — la cara
  REST de la capa de orquestación que llama el BFF de experiencia; conduce la saga y
  mapea la petición y la respuesta del canal.
- `src/main/java/com/firefly/lumen/domain/client/WebClientLoanOriginationClient.java`
  — la costura del SDK del core en vivo: un `LoanOriginationClient` respaldado por
  `WebClient` cuya escritura raíz (y su borrado compensatorio) alcanzan el servicio
  core en ejecución sobre HTTP.
- `src/test/java/com/firefly/lumen/domain/saga/RegisterApplicationSagaCompensationTest.java`
  — el test de compensación protagonista.
- `src/test/java/com/firefly/lumen/domain/saga/RegisterApplicationSagaHappyPathTest.java`
  — el compañero de la ruta de éxito. (Estos seis archivos abarcan la saga; los seis
  tests del módulo incluyen también los tests del manejador CQRS y del listener EDA de
  los capítulos 10 y 11.)

## Qué es una saga, y por qué un árbol de métodos

Una saga de Firefly es un bean de Spring corriente — anotado con `@Saga` *y* `@Service`
— cuyos métodos son los pasos. No escribes una máquina de estados ni un XML de workflow;
escribes métodos, anotas cada uno con `@SagaStep` y declaras dos cosas por paso: el
nombre de su método de compensación, y de qué otro paso `dependsOn`. A partir de esas
declaraciones el motor construye un **DAG de dependencias** y lo ejecuta: los pasos sin
dependencias pendientes se ejecutan, sus salidas fluyen hacia adelante, los pasos
dependientes se ejecutan a continuación, y si algo falla, los pasos ya completados se
compensan en orden inverso.

!!! note "Término clave — saga"
    Una **saga** es una secuencia de pasos locales que juntos llevan a cabo una
    operación distribuida, donde cada paso tiene una **acción compensatoria** que lo
    deshace semánticamente. No hay confirmación en dos fases ni rollback global; la
    coherencia se restaura mediante *compensación* — ejecutando la operación de negocio
    inversa por cada paso que ya se completó. Una saga cambia la fuerte atomicidad de
    `@Transactional` por el único tipo de atomicidad disponible entre servicios
    independientes.

La saga de Lumen tiene la topología más pequeña que aun así enseña el patrón completo:
una única raíz, y luego dos dependientes que se abren en abanico a partir de ella.

```text
registerLoanApplication            (root; compensate = removeLoanApplication)
  ├── registerApplicant            (dependsOn root)
  └── proposeOffer                 (dependsOn root)
```

La raíz crea la solicitud y debe ejecutarse primero, porque ambos dependientes
necesitan el id de la nueva solicitud. Una vez que la raíz se completa,
`registerApplicant` y `proposeOffer` tienen su dependencia satisfecha y el motor los
abre en abanico. El id viaja de la raíz a los dependientes a través del
`ExecutionContext` que conociste en el capítulo 10 — la bolsa de ámbito de petición que
fluye por toda la saga.

## Paso 1 — El paso raíz y su compensación

Abre la saga y lee primero el paso raíz. Está anotado con `@SagaStep` con un `id` y un
`compensate` — el nombre del método que el motor llama para deshacer este paso — y un
`@StepEvent` que nombra un evento de dominio a emitir cuando el paso se completa. El
método despacha un comando CQRS a través del `CommandBus` (el mismo bus del capítulo 10)
y, fundamentalmente, escribe el id de la nueva solicitud en el `ExecutionContext`.

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/saga/RegisterApplicationSaga.java | Listado 18.1 — el paso raíz escribe la solicitud y publica su id en el contexto
    @SagaStep(id = STEP_REGISTER_LOAN_APPLICATION, compensate = COMPENSATE_REMOVE_LOAN_APPLICATION)
    @StepEvent(type = EVENT_LOAN_APPLICATION_REGISTERED)
    public Mono<UUID> registerLoanApplication(RegisterLoanApplicationCommand command, ExecutionContext ctx) {
        return commandBus.send(command)
                .doOnNext(loanApplicationId -> ctx.putVariable(CTX_LOAN_APPLICATION_ID, loanApplicationId));
    }

    /** Compensation for the root step: delete the application created above. */
    public Mono<Void> removeLoanApplication(UUID loanApplicationId) {
        return client.removeLoanApplication(loanApplicationId);
    }
:::

Lee los dos métodos como una pareja, porque ese emparejamiento *es* la disciplina de la
saga. El paso hace el trabajo — `commandBus.send(command)` devuelve un `Mono<UUID>`, y
`doOnNext` guarda ese id bajo `CTX_LOAN_APPLICATION_ID` para que los pasos posteriores
puedan leerlo. La compensación es la inversa exacta: dado el id que produjo el paso,
elimina la solicitud del core. El motor recuerda la salida de cada paso, y cuando debe
compensar, entrega esa salida al método de compensación. Así, `removeLoanApplication`
recibe el mismísimo `UUID` que el paso devolvió y elimina precisamente la fila que el
paso creó. Sin huérfanos.

Fíjate en que la compensación llama a `client.removeLoanApplication(...)` directamente —
la costura del SDK `LoanOriginationClient` del capítulo 10 — en vez de despachar otro
comando. Una compensación es un método reactivo corriente que devuelve `Mono<Void>`;
puede hacer cualquier deshacer que el paso requiera. El único contrato del framework es
que devuelva `Mono<Void>` y acepte el resultado del paso. Quédate con este método: en el
Paso 6 verás que, con la pila en vivo en ejecución, esta misma llamada emite un `DELETE`
HTTP contra el servicio core — `removeLoanApplication` elimina una fila *real*, no la
entrada de una lista de un stub.

!!! note "Término clave — `@SagaStep` y compensate"
    `@SagaStep(id, compensate, dependsOn)` marca un método como un paso de una saga.
    `id` nombra el paso (y es la clave que usas para suministrar su entrada).
    `compensate` nombra el método que deshace este paso si un paso posterior falla.
    `dependsOn` nombra el paso (o pasos) que deben completarse antes de que este se
    ejecute. El método hacia adelante devuelve el resultado del paso; la compensación
    recibe ese resultado y devuelve `Mono<Void>`. Un paso sin un deshacer significativo
    puede aun así declarar un método `compensate` que devuelva `Mono.empty()`.

!!! spring "Equivalente en Spring"
    `@Saga` está meta-anotada con `@Service`, así que el escaneo de componentes
    descubre el bean de la saga exactamente igual que descubre cualquier `@Service` —
    no hay ningún registro especial que poblar. El añadido de Firefly es el
    post-procesado que lee las anotaciones `@SagaStep`/`@StepEvent`, construye el DAG
    de dependencias y registra la saga con el `SagaEngine` por su `@Saga(name = ...)`.
    Tú escribes beans de Spring corrientes; el motor lee las anotaciones.

## Paso 2 — Los pasos dependientes se abren en abanico

Los dos pasos dependientes añaden un atributo de anotación que la raíz no tenía:
`dependsOn = STEP_REGISTER_LOAN_APPLICATION`. Ese único atributo es lo que los coloca
*después* de la raíz en el DAG, y como dependen del mismo paso y de nada más, el motor
puede ejecutarlos como un abanico una vez que la raíz se completa. Cada uno lee de
vuelta el id de la solicitud del `ExecutionContext` y lo estampa en su comando antes de
despachar.

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/saga/RegisterApplicationSaga.java | Listado 18.2 — dos pasos dependientes leen el id del contexto y despachan
    @SagaStep(id = STEP_REGISTER_APPLICANT,
            compensate = COMPENSATE_REMOVE_APPLICANT,
            dependsOn = STEP_REGISTER_LOAN_APPLICATION)
    @StepEvent(type = EVENT_APPLICANT_REGISTERED)
    public Mono<UUID> registerApplicant(RegisterApplicantCommand command, ExecutionContext ctx) {
        UUID loanApplicationId = ctx.getVariableAs(CTX_LOAN_APPLICATION_ID, UUID.class);
        return commandBus.send(command.withLoanApplicationId(loanApplicationId));
    }

    /** Compensation for {@link #registerApplicant}. No-op in this slice; nothing to undo upstream. */
    public Mono<Void> removeApplicant(UUID applicantId, ExecutionContext ctx) {
        return Mono.empty();
    }

    // ---------------------------------------------------------------------
    // Dependent step: propose an offer (after the root step).
    // ---------------------------------------------------------------------

    @SagaStep(id = STEP_PROPOSE_OFFER,
            compensate = COMPENSATE_REMOVE_OFFER,
            dependsOn = STEP_REGISTER_LOAN_APPLICATION)
    @StepEvent(type = EVENT_OFFER_PROPOSED)
    public Mono<UUID> proposeOffer(ProposeOfferCommand command, ExecutionContext ctx) {
        UUID loanApplicationId = ctx.getVariableAs(CTX_LOAN_APPLICATION_ID, UUID.class);
        return commandBus.send(command.withLoanApplicationId(loanApplicationId));
    }

    /** Compensation for {@link #proposeOffer}. No-op in this slice; nothing to undo upstream. */
    public Mono<Void> removeOffer(UUID offerId, ExecutionContext ctx) {
        return Mono.empty();
    }
:::

La llamada `ctx.getVariableAs(CTX_LOAN_APPLICATION_ID, UUID.class)` es la otra mitad del
`putVariable` que viste en el paso raíz. La raíz *escribió* el id; cada dependiente lo
*lee*, tipado, y lo enhebra en el comando con `withLoanApplicationId(...)`. Así es como
los datos fluyen a lo largo de una arista del DAG sin que los pasos mantengan
referencias entre sí — se comunican solo a través del `ExecutionContext`, que es
exactamente lo que permite al motor planificarlos y, cuando hace falta, compensarlos de
forma independiente.

Ambas compensaciones dependientes devuelven `Mono.empty()` aquí, y el comentario es
honesto sobre por qué: en este corte, registrar un solicitante y proponer una oferta no
dejan nada aguas arriba que necesite deshacerse si ellas mismas fueron lo *último* en
ejecutarse. La compensación que hace trabajo real es la `removeLoanApplication` de la
raíz, porque la raíz es el paso que creó la escritura duradera que la saga no debe dejar
huérfana. Ese es el caso que ejercita el test.

!!! note "Por qué los pasos dependientes no tienen nada duradero que deshacer"
    Contra el core en vivo (Paso 6) los pasos dependientes son *deliberadamente* en
    proceso: el servicio core recortado expone únicamente el recurso de la solicitud de
    préstamo, así que los métodos de costura `addApplicant` y `proposeOffer` sintetizan
    un id y retornan sin una escritura de red. No hay ninguna fila remota que eliminar,
    que es exactamente por lo que sus compensaciones son `Mono.empty()`. La escritura
    duradera — la que un fallo no debe dejar huérfana — es la de la raíz, y esa es la
    única compensación con trabajo real que hacer.

!!! note "Término clave — `ExecutionContext` en una saga"
    El **`ExecutionContext`** es el almacén de ámbito de petición que fluye por cada
    paso de una ejecución de saga. Un paso escribe estado intermedio con
    `ctx.putVariable(key, value)` y un paso posterior lo lee de vuelta, tipado, con
    `ctx.getVariableAs(key, Type.class)`. En una flota también transporta ids de tenant
    y de correlación sobre la pila reactiva, sin `ThreadLocal`. Aquí transporta una cosa
    que importa enormemente: el id de la nueva solicitud, desde el paso raíz hasta los
    dos dependientes.

## Paso 3 — Los eventos de paso anuncian cada paso completado

Cada paso de esta saga lleva un `@StepEvent(type = ...)`. Cuando un paso se completa con
éxito, el motor emite un evento de dominio a nivel de paso de ese tipo. Esta es la
conexión de la saga con el runtime EDA del capítulo 11: `registerLoanApplication` emite
`loanApplication.registered`, `registerApplicant` emite `applicant.registered` y
`proposeOffer` emite `offer.proposed`. Una verificación de fraude, un servicio de
notificaciones o un registro de auditoría pueden suscribirse a esos eventos de paso y
reaccionar a medida que la saga progresa, sin que la saga sepa que existen.

Las constantes de tipo de evento viven en lo alto de la clase de la saga:

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/saga/RegisterApplicationSaga.java | Listado 18.3 — los tipos de evento de paso que emite cada paso
    /** Step-event types emitted by each step. */
    public static final String EVENT_LOAN_APPLICATION_REGISTERED = "loanApplication.registered";
    public static final String EVENT_APPLICANT_REGISTERED = "applicant.registered";
    public static final String EVENT_OFFER_PROPOSED = "offer.proposed";
:::

La gracia de `@StepEvent` es que el anuncio es *declarativo* y *por paso*: no inyectas
un `EventPublisher` en la saga ni llamas a `publish` en el cuerpo del paso. Anotas el
paso, y el motor emite el evento cuando el paso tiene éxito. El avance hacia adelante de
la saga y el flujo de hechos sobre ella quedan cableados juntos por el motor, de modo
que los observadores ven exactamente los pasos que se completaron — y, por su ausencia,
los pasos que no lo hicieron.

!!! spring "Equivalente en Spring"
    `@StepEvent` cabalga sobre el mismo runtime EDA que el `@EventListener` del capítulo
    11. Un evento de paso es un evento de dominio corriente publicado por el motor sobre
    el transporte configurado; en Lumen ese es el bus `APPLICATION_EVENT` dentro de la
    JVM, así que la saga emite eventos de paso sin broker. Cambiar a Kafka es el mismo
    cambio de `PublisherType` y `firefly.eda.*` que viste antes — el código de la saga
    no se mueve.

## Paso 4 — Ejecutar la saga: StepInputs entran, SagaResult sale

Un bean de saga define los pasos pero no se arranca a sí mismo. El `SagaEngine` la
ejecuta por nombre, y el servicio de dominio es donde vive esa llamada.
`LoanOriginationService` inyecta el `SagaEngine`, construye una entrada por paso con
`StepInputs` y llama a `execute(...)` — devolviendo el `SagaResult` para que quien llama
pueda inspeccionar exactamente qué ocurrió.

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/service/LoanOriginationService.java | Listado 18.4 — ensamblar los StepInputs y ejecutar la saga a través del motor
    public Mono<SagaResult> submitApplication(String applicantName, long amount, int annualRateBps) {
        StepInputs inputs = StepInputs.builder()
                .forStepId(RegisterApplicationSaga.STEP_REGISTER_LOAN_APPLICATION,
                        new RegisterLoanApplicationCommand(applicantName, amount))
                .forStepId(RegisterApplicationSaga.STEP_REGISTER_APPLICANT,
                        new RegisterApplicantCommand(applicantName))
                .forStepId(RegisterApplicationSaga.STEP_PROPOSE_OFFER,
                        new ProposeOfferCommand(amount, annualRateBps))
                .build();

        return sagaEngine.execute(RegisterApplicationSaga.SAGA_NAME, inputs);
    }
:::

Tres movimientos, y cada uno mapea a algo que ya has visto. `StepInputs.builder()`
adjunta un objeto de entrada a cada id de paso — el comando que cada método `@SagaStep`
recibirá como su primer parámetro. Los ids de paso de aquí
(`STEP_REGISTER_LOAN_APPLICATION` y compañía) son las mismas constantes que declararon
las anotaciones `@SagaStep(id = ...)`, así que el builder está literalmente
direccionando cada paso por nombre. Luego `sagaEngine.execute(SAGA_NAME, inputs)` busca
la saga por su `@Saga(name = ...)`, ejecuta el DAG y devuelve un `Mono<SagaResult>`.

El `SagaResult` es todo el sentido de recibir un valor de vuelta en lugar de un
dispara-y-olvida. Te dice si la saga tuvo éxito o falló y — cuando falló — qué pasos
fallaron y cuáles fueron compensados. El servicio no interpreta el resultado; lo
devuelve, para que quien llama (y, en un momento, el test) pueda leerlo:

```java
// Illustrative — the questions a SagaResult answers.
result.isSuccess();        // every step completed
result.isFailed();         // at least one step failed
result.failedSteps();      // ids of the steps that threw
result.compensatedSteps(); // ids of completed steps the engine undid
```

!!! note "Término clave — SagaEngine, StepInputs, SagaResult"
    El **`SagaEngine`** ejecuta una saga registrada por nombre. **`StepInputs`** es el
    mapa de entradas por paso que le entregas — `forStepId(id, input)` por cada paso. El
    motor devuelve un **`SagaResult`**: `isSuccess()`/`isFailed()` para el resultado
    global, `failedSteps()` para los pasos que lanzaron excepción, y
    `compensatedSteps()` para los pasos completados que tuvo que deshacer. El resultado
    es dato, no excepción, así que quien llama decide qué significa un fallo parcial
    para la respuesta de la API.

!!! spring "Equivalente en Spring"
    No hay equivalente en Spring plano de `SagaEngine` — esta es una capacidad de
    orquestación de Firefly, autoconfigurada cuando el módulo de orquestación está en el
    classpath. Lo que *sí* es familiar es la costura: el motor es un bean inyectado, las
    entradas son objetos corrientes y el resultado es un record corriente. Orquestas una
    transacción distribuida con la misma ergonomía de inyección de dependencias que usas
    para cualquier servicio.

## Paso 5 — El protagonista: compensación cuando un paso falla

Ahora el test que justifica el patrón entero. El test de compensación cablea la saga
exactamente como lo hace producción — `SagaEngine` real, `CommandBus` real, manejadores
reales — y cambia una sola cosa: sustituye un stub `LoanOriginationClient` configurado
para hacer que el paso `proposeOffer` falle. Luego ejecuta `submitApplication(...)` y
comprueba lo que el motor hizo con el fallo.

::: listing domain-lending-loan-origination/src/test/java/com/firefly/lumen/domain/saga/RegisterApplicationSagaCompensationTest.java | Listado 18.5 — forzar el fallo de proposeOffer y comprobar que el paso raíz fue compensado
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

        var stub = (StubLoanOriginationClient) client;
        // The application was created by the root step...
        assertThat(stub.createdApplications()).hasSize(1);
        // ...and then removed by the root step's compensation — same id, no orphan.
        assertThat(stub.removedApplications()).containsExactlyElementsOf(stub.createdApplications());
    }
:::

Lee las aserciones como una historia de lo que el motor hizo. Primero, la saga en su
conjunto falló: `isSuccess()` es falso, `isFailed()` es verdadero. Segundo, el paso
*concreto* que falló es `proposeOffer` — `failedSteps()` lo contiene — porque el stub
estaba configurado para lanzar excepción ahí. Tercero, y esta es la recompensa,
`compensatedSteps()` contiene el paso *raíz*, `registerLoanApplication`. La raíz tuvo
éxito, luego un dependiente falló, así que el motor ejecutó la compensación
`removeLoanApplication` de la raíz para deshacerla.

Las dos aserciones finales demuestran que la compensación no fue meramente *registrada*
sino *efectiva*. El stub recuerda cada solicitud que creó y cada una que eliminó.
Después de la saga, exactamente una solicitud fue creada (por el paso raíz) y el conjunto
de solicitudes eliminadas es igual al conjunto de las creadas — mismo id, todo
cuadrado. La escritura que hizo el paso raíz ha desaparecido. **Sin huérfanos.** Esa es
la promesa entera de una saga, verificada: cuando un paso posterior falla, el trabajo
que confirmaron los pasos anteriores no se filtra.

La configuración que hace fallar el paso de la oferta es una configuración de test de un
solo bean — `new StubLoanOriginationClient().failProposeOffer()` — suministrada para el
puerto `LoanOriginationClient`. Nada más del cableado cambia; la saga, el motor y la
compensación son todos código real. Esta es la costura del SDK del capítulo 10 ganándose
el sueldo de nuevo: como la saga depende de la *interfaz*, un test puede hacer fallar
cualquier paso configurando el stub, y observar el comportamiento de compensación real
del motor sin servicio core y sin Docker.

!!! tip "Punto de control"
    El compañero `RegisterApplicationSagaHappyPathTest` ejecuta la misma saga con un
    stub *sin configurar*, así que cada paso tiene éxito, y comprueba que
    `result.isSuccess()` es verdadero sin compensaciones. Lee los dos tests en paralelo:
    misma saga, mismo motor, y la única diferencia es si el stub hace fallar el paso de
    la oferta. Ese contraste es el patrón saga en dos archivos — el éxito corre hacia
    adelante, el fallo corre hacia adelante y luego compensa hacia atrás.

## Paso 6 — La misma saga, ejecutándose en vivo contra el core

Todo lo anterior ejecutó la saga contra un *stub en memoria*. El reactor ahora también
la ejecuta de verdad: con la pila de tres capas levantada, el paso raíz escribe al
servicio core en ejecución sobre HTTP, y el mismo código de saga que acabas de leer
conduce una fila real persistida. Nada en `RegisterApplicationSaga`,
`LoanOriginationService` o la compensación cambia entre el test y la ejecución en vivo —
solo cambia *qué implementación de `LoanOriginationClient` está en el classpath*. Esa es
la costura del SDK rindiendo frutos una vez más.

El punto de entrada en vivo es el BFF de experiencia, que llama al dominio sobre HTTP. La
cara REST del dominio es `LoanOriginationController` — el segundo salto en el flujo
`exp → domain → core`. Su método `submit` es delgado a propósito: mapea la petición con
forma de canal, llama a `LoanOriginationService.submitApplication(...)` (que ejecuta la
saga) y convierte el `SagaResult` en una vista de detalle `201 Created`.

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/web/LoanOriginationController.java | Listado 18.6 — la cara REST del dominio ejecuta la saga y devuelve el id asignado por el core
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "submitApplication", summary = "Submit Application",
            description = "Runs the RegisterApplicationSaga, writing the application to the core system of record.")
    public Mono<ResponseEntity<ApplicationDetailView>> submit(@RequestBody ApplicationChannelRequest request) {
        long amountMinor = toMinorUnits(request.requestedAmount());
        String applicantName = applicantNameFor(request);
        log.debug("Submitting application productId={} simulationId={} requestedAmount={} term={}",
                request.productId(), request.simulationId(), request.requestedAmount(), request.term());

        return service.submitApplication(applicantName, amountMinor, DEFAULT_ANNUAL_RATE_BPS)
                .flatMap(result -> toDetail(result, request))
                .map(detail -> ResponseEntity.status(HttpStatus.CREATED).body(detail));
    }
:::

Lee lo que el controlador hace con el resultado. Llama al *mismo* `submitApplication` que
llama el test, recibe de vuelta un `SagaResult`, y en `toDetail` extrae el id de la
solicitud de la salida del paso raíz con
`result.resultOf(STEP_REGISTER_LOAN_APPLICATION, UUID.class)` — el id que asignó el
core. Si la saga falló, no finge éxito: lanza una `BusinessException(BAD_GATEWAY,
"ORIGINATION_FAILED", ...)`, que el manejador global del framework renderiza como el
mismo problem detail RFC 7807 que viste en el capítulo 2. El `SagaResult` de la saga es
el contrato; el controlador simplemente lo mapea a HTTP.

Lo que convierte el paso raíz en una escritura de red real es el `LoanOriginationClient`
*en vivo* del classpath del dominio. Cuando el dominio apunta a un servicio core, el
manejador de comando del paso raíz llama a
`WebClientLoanOriginationClient.createLoanApplication(...)`, que hace `POST` al endpoint
`/api/v1/loan-applications` del core y mapea el id de la respuesta de vuelta. La
compensación llama a su hermana, `removeLoanApplication(...)`, que emite un `DELETE`
HTTP.

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/client/WebClientLoanOriginationClient.java | Listado 18.7 — la costura en vivo: la escritura raíz hace POST al core, la compensación hace DELETE
    @Override
    public Mono<UUID> createLoanApplication(String applicantName, long amount) {
        CoreCreateRequest body = new CoreCreateRequest(
                UUID.randomUUID(),
                BigDecimal.valueOf(amount, 2),
                DEFAULT_CURRENCY,
                DEFAULT_TERM_MONTHS,
                DEFAULT_PURPOSE);
        log.debug("Core create loan-application applicant={} amountMinor={}", applicantName, amount);
        return webClient.post()
                .uri(LOAN_APPLICATIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(CoreLoanApplicationResponse.class)
                .map(CoreLoanApplicationResponse::loanApplicationId);
    }

    @Override
    public Mono<Void> removeLoanApplication(UUID loanApplicationId) {
        log.debug("Core compensation delete loan-application id={}", loanApplicationId);
        return webClient.delete()
                .uri(LOAN_APPLICATIONS_PATH + "/{id}", loanApplicationId)
                .retrieve()
                .bodyToMono(Void.class);
    }
:::

Esta es la misma `removeLoanApplication` que ejercitó el test de compensación — pero
ahora elimina una fila real en un servicio real. El `DELETE /api/v1/loan-applications/{id}`
del core es *idempotente*: eliminar un id inexistente es una operación sin efecto que aun
así responde `204 No Content`, que es exactamente la propiedad que necesita una
compensación, porque el motor puede reintentarla. La compensación que demostraste en el
Paso 5 ahora protege datos de producción, no la lista de un stub.

El cliente en vivo solo se cablea cuando un operador apunta el dominio a un servicio
core. `LiveLoanOriginationClientConfig` registra el `WebClient` y el cliente de forma
*condicional*: `@ConditionalOnProperty(firefly.lumen.core.loan-origination.base-path)`
significa que se materializa solo cuando se establece una ruta base del core, y
`@ConditionalOnMissingBean` significa que nunca desplaza a un `LoanOriginationClient` que
los tests ya suministran. El `application.yml` que se entrega establece la ruta base en
`http://localhost:8081`, así que la app de dominio autónoma llama al core en ejecución —
mientras que los tests de corte, que registran su propio stub, nunca tocan este código en
absoluto.

!!! note "Término clave — la costura del SDK bajo dos implementaciones"
    La saga depende de la *interfaz* `LoanOriginationClient`. Dos beans pueden
    satisfacerla. En los tests, `StubLoanOriginationClient` registra las llamadas en
    memoria y puede recibir la orden de hacer fallar un paso. En la pila en ejecución,
    `WebClientLoanOriginationClient` llama al core sobre HTTP. Como ambas son
    implementaciones del mismo puerto que devuelven `Mono`, la saga, el motor y cada
    compensación son idénticos byte a byte en ambos mundos. Por eso el comportamiento
    que demostraste sin servidor es el mismo comportamiento que se ejecuta en vivo.

!!! spring "Equivalente en Spring"
    El cableado condicional es Spring Boot corriente: `@ConditionalOnProperty` y
    `@ConditionalOnMissingBean` son las mismas guardas que usan las propias
    autoconfiguraciones de Boot. Firefly no añade nada aquí — el cliente en vivo es un
    bean `@Configuration` corriente que se retira cuando un test u otro módulo ya ha
    definido el puerto. "Configura una ruta base para ir en vivo, déjala sin establecer
    para quedarte dentro de la JVM" es la misma ergonomía de beans condicionales que
    usas en todas partes.

Para verla en ejecución, levanta las tres capas (cada una en su propia terminal), luego
haz `POST` de una petición de canal al BFF en `:8080`:

```text
$ curl -s -X POST localhost:8080/api/v1/experience/lending/applications \
    -H 'Content-Type: application/json' \
    -d '{"productId":"11111111-1111-1111-1111-111111111111","requestedAmount":25000.00,"term":36,"purpose":"HOME_IMPROVEMENT","simulationId":"22222222-2222-2222-2222-222222222222"}'
```

El BFF responde `201 Created` con el id asignado por el core — la solicitud fluyó
`exp → domain (saga) → core`:

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

En el log de la capa de dominio puedes ver al motor narrar la ejecución. Las líneas
`[orchestration]` son el `SagaEngine` reportando cada paso a medida que el DAG se
ejecuta — la raíz tiene éxito primero (fíjate en `stepId=registerLoanApplication`, el
único paso que hace una llamada de red), luego los dos dependientes se completan en
proceso, y después la saga reporta éxito global:

```text
[orchestration] started   name=RegisterApplicationSaga ... pattern=SAGA
[orchestration] step.success ... stepId=registerLoanApplication latencyMs=94
[orchestration] step.success ... stepId=proposeOffer
[orchestration] step.success ... stepId=registerApplicant
[orchestration] completed name=RegisterApplicationSaga ... pattern=SAGA success=true
```

Esa línea final — `[orchestration] completed name=RegisterApplicationSaga ...
pattern=SAGA success=true` (con un `durationMs=...` para la ejecución entera) — es la
contraparte en vivo de que `result.isSuccess()` sea `true`. El mismo `SagaResult` sobre
el que el test hace aserciones es el valor que el controlador mapea a un `201`.

Puedes demostrar que la escritura realmente aterrizó leyendo la solicitud de vuelta
directamente desde el *core* — el `applicationId` que devolvió el BFF es el id que asignó
el sistema de registro del core:

```text
$ curl -s localhost:8081/api/v1/loan-applications/786544c7-2f10-4110-95fe-682d63edbace
```

```json
{
  "loanApplicationId": "786544c7-2f10-4110-95fe-682d63edbace",
  "applicationNumber": "9d2e8b8c-fc64-4aae-8578-c77558a3ec4b",
  "applicantId": "8db1c7ab-5d74-44fd-a4c7-d9433f0ddaba",
  "requestedAmount": 25000.00,
  "currency": "EUR",
  "termMonths": 12,
  "purpose": "GENERAL",
  "status": "SUBMITTED",
  "decisionReason": null,
  "createdAt": "2026-06-17T11:46:21.145765",
  "updatedAt": "2026-06-17T11:46:21.145781"
}
```

!!! warning "El mapeo en vivo es intencionadamente mínimo — dilo, no exageres"
    Mira de cerca la fila del core: `currency` es `EUR`, `termMonths` es `12` y
    `purpose` es `GENERAL` — *no* el `36` y `HOME_IMPROVEMENT` que enviaste al BFF. Eso
    es honesto, no un bug. La costura de escritura recortada transporta a través de la
    saga al core solo el nombre del solicitante y el importe; los demás campos aterrizan
    como *valores por defecto* del core (`DEFAULT_CURRENCY`, `DEFAULT_TERM_MONTHS`,
    `DEFAULT_PURPOSE` en `WebClientLoanOriginationClient`). Los pasos dependientes —
    parte solicitante y oferta — se completan en proceso porque el core recortado expone
    únicamente el recurso de la solicitud de préstamo. El mapeo de campos más rico y los
    endpoints de solicitante/oferta pertenecen al SDK generado en el servicio real; el
    sample mantiene la costura mínima para que el flujo de tres capas en vivo se ejecute
    sin Docker y sin código generado.

## Paso 7 — Ejecútalo

La capa de dominio entera — manejadores CQRS, el listener EDA y ambos tests de saga —
arranca y se ejecuta sin un servicio core. Desde el directorio `samples/lumen-lending`:

```text
$ mvn -q -pl domain-lending-loan-origination test
```

El resultado esperado:

```text
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Seis tests en verde, y dos de ellos son la saga: `RegisterApplicationSagaHappyPathTest`
demuestra que la ruta hacia adelante se completa, y `RegisterApplicationSagaCompensationTest`
— Listado 18.5 — demuestra que la ruta de fallo compensa el paso raíz y no deja
huérfanos. Cuando se ejecuta el test de compensación verás al motor registrarlo en
tiempo real: `step.failed ... stepId=proposeOffer`, luego `compensation.started`, y
después una entrada `dead-lettered` que registra el paso fallido. Esas líneas de log son
el motor narrando exactamente el comportamiento que comprueban las aserciones — el
espejo de ruta de fallo de la línea `success=true` que viste en la ejecución en vivo.

Estos seis tests de dominio forman parte de los **33** tests en verde del reactor en
total — core **18**, dominio **6**, experiencia **9** — que `mvn clean verify` desde
`samples/lumen-lending` ejecuta de extremo a extremo. Los tests nunca necesitan la pila
en vivo: usan el stub en memoria, así que la compensación de la saga se verifica sin
servidor, y el flujo en vivo `exp → domain → core` del Paso 6 es una prueba separada y
ejecutable sobre lo anterior.

!!! warning "Una saga es eventual, no atómica — las compensaciones deben ser seguras de ejecutar"
    Una saga renuncia a la atomicidad de todo-o-nada de `@Transactional`. Entre la
    confirmación del paso raíz y el fallo de un paso posterior, la solicitud *existe
    brevemente* antes de que la compensación la elimine — el sistema es coherente solo
    *eventualmente*. Eso pone peso sobre tus compensaciones: deben ser idempotentes (el
    motor puede reintentar, y viste que el `DELETE` en vivo es una operación sin efecto
    sobre un id inexistente precisamente por esto), deben tolerar ejecutarse contra un
    paso cuyo efecto está solo parcialmente aplicado, y no deberían fallar ellas mismas
    en silencio. Diseña cada método `compensate` con tanto cuidado como el paso que
    deshace.

## Dos hermanos: Workflow y TCC

La saga es uno de los tres patrones de orquestación que soporta el motor de Firefly. Los
otros dos resuelven el mismo problema de la transacción distribuida con compensaciones
distintas, y saber dónde encaja cada uno te evita forzar una saga sobre un trabajo para
el que es incorrecta. Ambos se describen aquí de forma conceptual — el reactor verifica
la saga, no estos — así que trata los fragmentos de abajo como un cómo-funciona, no como
algo que esta build ejecute.

**Workflow — dispara hacia adelante, sin compensación.** Un workflow es el primo
optimista de la saga: una secuencia de pasos que corren hacia adelante hasta completarse,
*sin* compensar ante un fallo. Lo usas cuando los pasos no tienen un deshacer
significativo — enviar una secuencia de notificaciones, ejecutar un pipeline de
enriquecimiento, abrir en abanico llamadas de solo lectura — o cuando un paso fallido
debería simplemente detener el flujo y reintentarse más tarde en vez de revertirse. Un
workflow conserva el DAG, el `ExecutionContext` y los eventos de paso, y descarta la
mitad de la compensación:

```java
// Illustrative — a workflow step: forward-only, no compensate attribute.
@WorkflowStep(id = "notifyApplicant", dependsOn = "registerApplicant")
public Mono<Void> notifyApplicant(NotifyCommand command, ExecutionContext ctx) {
    return notifier.send(command);            // nothing to undo if a later step fails
}
```

**TCC — Try, Confirm, Cancel.** TCC es el primo más estricto de la saga, para recursos
que soportan *reserva*. Cada participante expone tres operaciones: **Try** reserva el
recurso (coloca una retención sobre fondos, reserva inventario) sin confirmarlo;
**Confirm** hace permanente cada reserva una vez que todos los Try tienen éxito;
**Cancel** libera las reservas si algún Try falla. Donde una saga confirma cada paso y
compensa a posteriori, TCC mantiene todo en estado reservado hasta que se sabe que la
operación entera tendrá éxito — de modo que no hay ventana en la que un paso confirmado
deba deshacerse visiblemente. Cuesta más (cada recurso debe soportar el protocolo de tres
fases) y compra una coherencia más ajustada:

```java
// Illustrative — a TCC participant exposes try / confirm / cancel.
@TccParticipant(id = "reserveFunds")
class ReserveFunds {
    @TccTry     Mono<Void> tryReserve(ReserveCommand c, ExecutionContext ctx) { /* place hold */ }
    @TccConfirm Mono<Void> confirm(ExecutionContext ctx)                      { /* settle hold */ }
    @TccCancel  Mono<Void> cancel(ExecutionContext ctx)                       { /* release hold */ }
}
```

!!! note "Término clave — Saga vs. Workflow vs. TCC"
    Los tres orquestan operaciones distribuidas de varios pasos a través del mismo motor
    y el mismo `ExecutionContext`. Una **Saga** confirma cada paso y *compensa* los pasos
    completados en orden inverso ante un fallo — mejor cuando los pasos tienen un deshacer
    semántico limpio. Un **Workflow** corre hacia adelante *sin* compensación — mejor
    cuando los pasos no pueden o no necesitan deshacerse. **TCC** (Try-Confirm-Cancel)
    *reserva* cada recurso, luego confirma todos o cancela todos — mejor cuando los
    participantes soportan reservas y quieres evitar una ventana visible de
    confirmado-y-luego-deshecho. Elige según cómo se comporten tus recursos: deshacibles,
    dispara-hacia-adelante o reservables.

## Lo que has construido {.recap}

- Un bean **`@Saga`**, `RegisterApplicationSaga`, cuyos métodos son `@SagaStep`s: una
  raíz `registerLoanApplication` y dos dependientes, `registerApplicant` y
  `proposeOffer`, que `dependsOn` la raíz y se abren en abanico una vez que se completa.
- Una **compensación** por cada paso — la `removeLoanApplication` de la raíz elimina la
  solicitud que el paso creó, dado el mismísimo id que el paso devolvió — que es el
  sustituto entre servicios del rollback de `@Transactional`.
- El **`ExecutionContext`** transportando el id de la nueva solicitud desde el paso raíz
  (`putVariable`) hasta los dependientes (`getVariableAs`), de modo que los pasos
  comparten datos a lo largo de las aristas del DAG sin referenciarse entre sí.
- Un **`@StepEvent`** en cada paso, de modo que el motor emite un evento de dominio a
  nivel de paso sobre el runtime EDA a medida que la saga progresa.
- El **`SagaEngine`** ejecutado desde `LoanOriginationService`: `StepInputs` entran, un
  `SagaResult` sale, exponiendo `isSuccess`/`isFailed`/`failedSteps`/`compensatedSteps`.
- El **test de compensación protagonista** (`Tests run: 6, Failures: 0`): forzar el
  fallo de `proposeOffer` demuestra que el paso raíz se compensa y la solicitud creada
  se elimina — **sin huérfanos** — sin servicio core y sin Docker.
- La **ejecución en vivo**: con la pila de tres capas levantada, la misma saga conduce
  una escritura real — `LoanOriginationController` la ejecuta,
  `WebClientLoanOriginationClient` hace `POST` del paso raíz al core sobre HTTP, y el
  motor registra `[orchestration] completed name=RegisterApplicationSaga ... pattern=SAGA
  success=true`. La compensación ahora emite un `DELETE` HTTP contra el core, protegiendo
  una fila real.

## Pruébalo tú mismo {.exercises}

1. **Lleva el fallo a la API.** `submitApplication` devuelve el `SagaResult` crudo;
   `LoanOriginationController.toDetail` ya convierte `!result.isSuccess()` en una
   `BusinessException(BAD_GATEWAY, "ORIGINATION_FAILED")`. Sigue eso a través del
   manejador RFC 7807 del framework: ¿qué código de estado llega al BFF, y qué pondrías
   en el `detail` del problema? Argumenta el mapeo; no necesitas ejecutarlo.
2. **Haz fallar la raíz en su lugar.** En `StubLoanOriginationClient`, añade un
   conmutador `failCreate()` como el `failProposeOffer()` existente y escribe un test que
   haga fallar el paso *raíz*. ¿Qué debería contener `compensatedSteps()`, y por qué está
   vacío? (Pista: la raíz nunca se completó, así que no hay nada que deshacer.)
3. **Dale a un dependiente un deshacer real.** `removeOffer` devuelve `Mono.empty()`.
   Cambia `proposeOffer` para que su compensación llame a un nuevo método del stub que
   registre una oferta eliminada, luego haz fallar un *tercer* paso y comprueba que tanto
   la raíz como la oferta se compensan. Esto demuestra que el motor compensa *todos* los
   pasos completados, en orden inverso.
4. **Sigue un evento de paso.** Añade un `@EventListener` (capítulo 11) para el tipo de
   evento de paso `offer.proposed` y comprueba, en la ruta feliz, que se disparó
   exactamente una vez. Luego ejecuta el test de compensación y confirma que *no* se
   disparó — el paso de la oferta nunca se completó, así que su `@StepEvent` nunca emitió.
5. **Observa la compensación en vivo.** Levanta las tres capas, luego envía a través del
   BFF y lee la solicitud de vuelta desde el core (Paso 6). Ahora imagina el paso de la
   oferta fallando en vivo: ¿qué líneas de log `[orchestration]` sustituirían a
   `success=true`, y qué verbo HTTP emitiría `WebClientLoanOriginationClient` contra el
   core para deshacer la escritura raíz? (Pista: `removeLoanApplication` — y el `DELETE`
   es idempotente.)
6. **Elige el patrón.** Para cada una de estas operaciones, decide Saga, Workflow o TCC y
   justifícalo en una frase: (a) reservar asientos, cargar una tarjeta, emitir entradas;
   (b) enviar un correo de bienvenida, luego un seguimiento; (c) cargar una cuenta y
   abonar otra a través de dos servicios core.

## Adónde ir ahora

Ahora tienes la capacidad más difícil de la capa de dominio demostrada *y en ejecución*:
una transacción distribuida que o bien se completa contra un core en vivo o se deshace
limpiamente a sí misma, con el mismo código verificado sin servidor y ejecutado de
extremo a extremo. Los capítulos restantes profundizan en las piezas en las que este se
apoyó — el motor de reglas (capítulo 13) para la toma de decisiones que el paso de la
oferta solo simula, la capa de datos (capítulo 15) tras la persistencia del core, y el
event sourcing (capítulo 12) como alternativa a la compensación-por-borrado — y aprietan
la costura de escritura recortada hacia el SDK generado, momento en el cual cada campo
que la ejecución en vivo hoy tomó por defecto fluirá sin cambios.
