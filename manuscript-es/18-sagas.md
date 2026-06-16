El capítulo 6 te dio `@Transactional`, y dentro de un único servicio contra una única
base de datos es la herramienta adecuada: en cuanto un paso falla, la fila nunca llega
a aparecer, porque la base de datos revierte la unidad entera. Pero registrar una
solicitud de préstamo no es una escritura a una base de datos. Crea la solicitud en el
sistema central de registro, adjunta una parte solicitante y propone una oferta: tres
operaciones que, en una plataforma real, cruzan tres fronteras de servicio y tres
almacenes de datos. No hay una transacción compartida que revertir. Si el paso de la
oferta falla después de que la solicitud ya se haya escrito, `@Transactional` no puede
ayudarte: la solicitud está confirmada, en otro servicio, y ahora es huérfana.

Este es el problema de las transacciones distribuidas, y la respuesta es la **saga**.
Una saga descompone una operación de varios pasos en pasos discretos, cada uno con una
**compensación**: un "deshacer" explícito que el motor ejecuta si un paso posterior
falla. No hay un rollback global; en su lugar, los pasos completados se *compensan* en
orden inverso, de modo que el sistema se conduce de vuelta hacia un estado consistente
ejecutando operaciones de negocio reales (borrar la solicitud, liberar la retención)
en lugar de revertir filas de la base de datos. La compensación es el sustituto del
rollback entre servicios, y es el corazón de este capítulo.

En este capítulo diseccionas la `RegisterApplicationSaga` de Lumen — una `@Saga` con un
`@SagaStep` raíz que crea la solicitud y dos pasos dependientes que se abren en abanico
a partir de él — y el `LoanOriginationService` que la ejecuta a través del `SagaEngine`.
Luego lees el test estrella: fuerza el fallo del paso de la oferta y demuestra que el
motor compensa el paso raíz, de modo que no queda ninguna solicitud huérfana. Todo se
ejecuta en el módulo `domain-lending-loan-origination`, **sin servicio central y sin
Docker**. Al final conocerás dos patrones hermanos — Workflow y TCC — y sabrás cuándo
recurrir a cada uno.

El reparto de archivos, todos bajo `samples/lumen-lending/domain-lending-loan-origination`:

- `src/main/java/com/firefly/lumen/domain/saga/RegisterApplicationSaga.java` — la
  `@Saga` en sí: tres métodos `@SagaStep`, sus deshacer `compensate`, la topología
  `dependsOn` y un `@StepEvent` en cada paso.
- `src/main/java/com/firefly/lumen/domain/service/LoanOriginationService.java` — el
  servicio que ensambla `StepInputs` y llama a `SagaEngine.execute(...)`, leyendo de
  vuelta un `SagaResult`.
- `src/test/java/com/firefly/lumen/domain/saga/RegisterApplicationSagaCompensationTest.java`
  — el test estrella de compensación.
- `src/test/java/com/firefly/lumen/domain/saga/RegisterApplicationSagaHappyPathTest.java`
  — el compañero del camino de éxito, uno de los seis tests del módulo.

## Qué es una saga, y por qué un árbol de métodos

Una saga de Firefly es un bean de Spring corriente — anotado con `@Saga` *y* `@Service`
— cuyos métodos son los pasos. No escribes una máquina de estados ni un XML de workflow;
escribes métodos, anotas cada uno con `@SagaStep` y declaras dos cosas por paso: el
nombre de su método de compensación y de qué otro paso `dependsOn`. A partir de esas
declaraciones el motor construye un **DAG de dependencias** y lo ejecuta: los pasos sin
dependencias pendientes se ejecutan, sus salidas fluyen hacia adelante, los pasos
dependientes se ejecutan a continuación y, si algo falla, los pasos ya completados se
compensan en orden inverso.

!!! note "Término clave — saga"
    Una **saga** es una secuencia de pasos locales que en conjunto llevan a cabo una
    operación distribuida, donde cada paso tiene una **acción compensatoria** que lo
    deshace semánticamente. No hay commit en dos fases ni rollback global; la
    consistencia se restaura mediante *compensación*: ejecutando la operación de negocio
    inversa para cada paso que ya se completó. Una saga cambia la fuerte atomicidad de
    `@Transactional` por el único tipo de atomicidad disponible entre servicios
    independientes.

La saga de Lumen tiene la topología más pequeña que aún enseña el patrón completo: una
única raíz, y luego dos dependientes que se abren en abanico a partir de ella.

```text
registerLoanApplication            (root; compensate = removeLoanApplication)
  ├── registerApplicant            (dependsOn root)
  └── proposeOffer                 (dependsOn root)
```

La raíz crea la solicitud y debe ejecutarse primero, porque ambos dependientes necesitan
el id de la nueva solicitud. Una vez que la raíz se completa, `registerApplicant` y
`proposeOffer` tienen satisfecha su dependencia y el motor los abre en abanico. El id
viaja desde la raíz hasta los dependientes a través del `ExecutionContext` que conociste
en el capítulo 10: la bolsa con alcance de petición que fluye por toda la saga.

## Paso 1 — El paso raíz y su compensación

Abre la saga y lee primero el paso raíz. Está anotado con `@SagaStep` con un `id` y un
`compensate` — el nombre del método que el motor llama para deshacer este paso — y un
`@StepEvent` que nombra un evento de dominio a emitir cuando el paso se completa. El
método despacha un comando CQRS a través del `CommandBus` (el mismo bus del capítulo 10)
y, crucialmente, escribe el id de la nueva solicitud en el `ExecutionContext`.

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
puedan leerlo. La compensación es el inverso exacto: dado el id que el paso produjo,
borra la solicitud del core. El motor recuerda la salida de cada paso y, cuando debe
compensar, entrega esa salida al método de compensación. Así que `removeLoanApplication`
recibe el mismísimo `UUID` que el paso devolvió y borra precisamente la fila que el paso
creó. Sin huérfanos.

Fíjate en que la compensación llama directamente a `client.removeLoanApplication(...)` —
la costura del SDK `LoanOriginationClient` del capítulo 10 — en lugar de despachar otro
comando. Una compensación es un método reactivo corriente que devuelve `Mono<Void>`;
puede hacer cualquier deshacer que el paso requiera. El único contrato del framework es
que devuelva `Mono<Void>` y acepte el resultado del paso.

!!! note "Término clave — `@SagaStep` y compensate"
    `@SagaStep(id, compensate, dependsOn)` marca un método como un paso de una saga.
    `id` nombra el paso (y es la clave que usas para suministrarle su entrada).
    `compensate` nombra el método que deshace este paso si un paso posterior falla.
    `dependsOn` nombra el paso (o pasos) que deben completarse antes de que este se
    ejecute. El método hacia adelante devuelve el resultado del paso; la compensación
    recibe ese resultado y devuelve `Mono<Void>`. Un paso sin un deshacer significativo
    aún puede declarar un método `compensate` que devuelva `Mono.empty()`.

!!! spring "Equivalente en Spring"
    `@Saga` está meta-anotada con `@Service`, de modo que el escaneo de componentes
    descubre el bean de la saga exactamente como descubre cualquier `@Service`: no hay
    ningún registro especial que poblar. La aportación de Firefly es el postprocesamiento
    que lee las anotaciones `@SagaStep`/`@StepEvent`, construye el DAG de dependencias y
    registra la saga con el `SagaEngine` por su `@Saga(name = ...)`. Tú escribes beans de
    Spring corrientes; el motor lee las anotaciones.

## Paso 2 — Los pasos dependientes se abren en abanico

Los dos pasos dependientes añaden un atributo de anotación que la raíz no tenía:
`dependsOn = STEP_REGISTER_LOAN_APPLICATION`. Ese único atributo es lo que los coloca
*después* de la raíz en el DAG y, como dependen del mismo paso y de nada más, el motor
puede ejecutarlos como un abanico una vez que la raíz se completa. Cada uno lee de vuelta
el id de la solicitud desde el `ExecutionContext` y lo estampa en su comando antes del
despacho.

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
los datos fluyen a lo largo de una arista del DAG sin que los pasos mantengan referencias
unos a otros: se comunican solo a través del `ExecutionContext`, que es exactamente lo
que permite al motor planificarlos y, cuando hace falta, compensarlos de forma
independiente.

Ambas compensaciones dependientes devuelven aquí `Mono.empty()`, y el comentario es
honesto sobre el porqué: en esta porción, registrar un solicitante y proponer una oferta
no dejan nada aguas arriba que necesite deshacerse si ellos mismos fueron lo *último* en
ejecutarse. La compensación que hace trabajo real es la `removeLoanApplication` de la
raíz, porque la raíz es el paso que creó la escritura duradera que la saga no debe
dejar huérfana. Ese es el caso que el test ejercita.

!!! note "Término clave — `ExecutionContext` en una saga"
    El **`ExecutionContext`** es el almacén con alcance de petición que fluye a través de
    cada paso de una ejecución de saga. Un paso escribe estado intermedio con
    `ctx.putVariable(key, value)` y un paso posterior lo lee de vuelta, tipado, con
    `ctx.getVariableAs(key, Type.class)`. En una flota también transporta ids de tenant y
    de correlación en la pila reactiva, sin `ThreadLocal`. Aquí transporta una cosa que
    importa enormemente: el id de la nueva solicitud, desde el paso raíz hasta los dos
    dependientes.

## Paso 3 — Los eventos de paso anuncian cada paso completado

Cada paso de esta saga lleva un `@StepEvent(type = ...)`. Cuando un paso se completa con
éxito, el motor emite un evento de dominio a nivel de paso de ese tipo. Esta es la
conexión de la saga con el runtime de EDA del capítulo 11: `registerLoanApplication`
emite `loanApplication.registered`, `registerApplicant` emite `applicant.registered` y
`proposeOffer` emite `offer.proposed`. Una comprobación de fraude, un servicio de
notificaciones o un registro de auditoría pueden suscribirse a esos eventos de paso y
reaccionar a medida que la saga progresa, sin que la saga sepa que existen.

Las constantes de tipo de evento viven en la parte superior de la clase de la saga:

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/saga/RegisterApplicationSaga.java | Listado 18.3 — los tipos de evento de paso que emite cada paso
    /** Step-event types emitted by each step. */
    public static final String EVENT_LOAN_APPLICATION_REGISTERED = "loanApplication.registered";
    public static final String EVENT_APPLICANT_REGISTERED = "applicant.registered";
    public static final String EVENT_OFFER_PROPOSED = "offer.proposed";
:::

El sentido de `@StepEvent` es que el anuncio es *declarativo* y *por paso*: no inyectas
un `EventPublisher` en la saga ni llamas a `publish` en el cuerpo del paso. Anotas el
paso, y el motor emite el evento cuando el paso tiene éxito. El avance hacia adelante de
la saga y el flujo de hechos sobre ella quedan conectados por el motor, de modo que los
observadores ven exactamente los pasos que se completaron y, por su ausencia, los pasos
que no.

!!! spring "Equivalente en Spring"
    `@StepEvent` cabalga sobre el mismo runtime de EDA que el `@EventListener` del
    capítulo 11. Un evento de paso es un evento de dominio corriente publicado por el
    motor en el transporte configurado; en Lumen ese es el bus `APPLICATION_EVENT`
    dentro de la JVM, de modo que la saga emite eventos de paso sin broker. Cambiar a
    Kafka es el mismo cambio de `PublisherType` y `firefly.eda.*` que viste antes: el
    código de la saga no se mueve.

## Paso 4 — Ejecutar la saga: StepInputs a la entrada, SagaResult a la salida

Un bean de saga define los pasos pero no se inicia a sí mismo. El `SagaEngine` lo
ejecuta por nombre, y el servicio de dominio es donde vive esa llamada.
`LoanOriginationService` inyecta el `SagaEngine`, construye una entrada por paso con
`StepInputs` y llama a `execute(...)`, devolviendo el `SagaResult` para que el llamador
pueda inspeccionar exactamente qué ocurrió.

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/service/LoanOriginationService.java | Listado 18.4 — ensamblar StepInputs y ejecutar la saga a través del motor
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

Tres movimientos, y cada uno se corresponde con algo que ya has visto.
`StepInputs.builder()` adjunta un objeto de entrada a cada id de paso: el comando que
cada método `@SagaStep` recibirá como su primer parámetro. Los ids de paso aquí
(`STEP_REGISTER_LOAN_APPLICATION` y compañeros) son las mismas constantes que declararon
las anotaciones `@SagaStep(id = ...)`, así que el builder está literalmente direccionando
cada paso por nombre. Luego `sagaEngine.execute(SAGA_NAME, inputs)` busca la saga por su
`@Saga(name = ...)`, ejecuta el DAG y devuelve un `Mono<SagaResult>`.

El `SagaResult` es todo el sentido de recuperar un valor en lugar de un fire-and-forget.
Te dice si la saga tuvo éxito o falló y — cuando falló — qué pasos fallaron y cuáles se
compensaron. El servicio no interpreta el resultado; lo devuelve, para que el llamador
(y, en un momento, el test) pueda leerlo:

```java
// Illustrative — the questions a SagaResult answers.
result.isSuccess();        // every step completed
result.isFailed();         // at least one step failed
result.failedSteps();      // ids of the steps that threw
result.compensatedSteps(); // ids of completed steps the engine undid
```

!!! note "Término clave — SagaEngine, StepInputs, SagaResult"
    El **`SagaEngine`** ejecuta una saga registrada por nombre. **`StepInputs`** es el
    mapa de entradas por paso que le entregas — `forStepId(id, input)` para cada paso. El
    motor devuelve un **`SagaResult`**: `isSuccess()`/`isFailed()` para el resultado
    global, `failedSteps()` para los pasos que lanzaron excepción y `compensatedSteps()`
    para los pasos completados que tuvo que deshacer. El resultado es dato, no una
    excepción, de modo que el llamador decide qué significa un fallo parcial para la
    respuesta de la API.

!!! spring "Equivalente en Spring"
    No hay un equivalente en Spring puro de `SagaEngine`: esta es una capacidad de
    orquestación de Firefly, autoconfigurada cuando el módulo de orquestación está en el
    classpath. Lo que *sí* es familiar es la costura: el motor es un bean inyectado, las
    entradas son objetos corrientes y el resultado es un record corriente. Orquestas una
    transacción distribuida con la misma ergonomía de inyección de dependencias que usas
    para cualquier servicio.

## Paso 5 — El plato fuerte: compensación cuando un paso falla

Ahora el test que justifica todo el patrón. El test de compensación cablea la saga
exactamente como lo hace producción — `SagaEngine` real, `CommandBus` real, manejadores
reales — y cambia una sola cosa: sustituye un stub `LoanOriginationClient` configurado
para hacer fallar el paso `proposeOffer`. Luego ejecuta `submitApplication(...)` y afirma
lo que el motor hizo con el fallo.

::: listing domain-lending-loan-origination/src/test/java/com/firefly/lumen/domain/saga/RegisterApplicationSagaCompensationTest.java | Listado 18.5 — forzar el fallo de proposeOffer y afirmar que el paso raíz fue compensado
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

Lee las afirmaciones como la historia de lo que el motor hizo. Primero, la saga en su
conjunto falló: `isSuccess()` es falso, `isFailed()` es verdadero. Segundo, el paso
*concreto* que falló es `proposeOffer` — `failedSteps()` lo contiene — porque el stub
estaba configurado para lanzar una excepción ahí. Tercero, y esta es la recompensa,
`compensatedSteps()` contiene el paso *raíz*, `registerLoanApplication`. La raíz tuvo
éxito, luego un dependiente falló, así que el motor ejecutó la compensación
`removeLoanApplication` de la raíz para deshacerla.

Las dos últimas afirmaciones demuestran que la compensación no solo fue *registrada*
sino *efectiva*. El stub recuerda cada solicitud que creó y cada una que eliminó. Tras
la saga, se creó exactamente una solicitud (por el paso raíz) y el conjunto de
solicitudes eliminadas es igual al conjunto de las creadas — mismo id, todo cuadrado. La
escritura que hizo el paso raíz ha desaparecido. **Sin huérfanos.** Esa es la promesa
entera de una saga, verificada: cuando un paso posterior falla, el trabajo que los pasos
anteriores confirmaron no se filtra.

La configuración que hace fallar el paso de la oferta es una configuración de test de un
solo bean — `new StubLoanOriginationClient().failProposeOffer()` — suministrada para el
puerto `LoanOriginationClient`. Nada más del cableado cambia; la saga, el motor y la
compensación son todos código real. Esta es la costura del SDK del capítulo 10 ganándose
de nuevo el sueldo: como la saga depende de la *interfaz*, un test puede hacer fallar
cualquier paso configurando el stub, y observar el comportamiento de compensación real
del motor sin servicio central y sin Docker.

!!! tip "Punto de control"
    El compañero `RegisterApplicationSagaHappyPathTest` ejecuta la misma saga con un stub
    *sin configurar*, de modo que todos los pasos tienen éxito, y afirma que
    `result.isSuccess()` es verdadero sin compensaciones. Lee los dos tests en paralelo:
    la misma saga, el mismo motor, y la única diferencia es si el stub hace fallar el
    paso de la oferta. Ese contraste es el patrón de saga en dos archivos: el éxito corre
    hacia adelante, el fallo corre hacia adelante y luego compensa de vuelta.

## Ejecútalo

Toda la capa de dominio — los manejadores CQRS, el listener de EDA y ambos tests de saga
— arranca y se ejecuta sin un servicio central. Desde el directorio
`samples/lumen-lending`:

```text
mvn -q -pl domain-lending-loan-origination test
```

El resultado esperado:

```text
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Seis tests en verde, y dos de ellos son la saga: `RegisterApplicationSagaHappyPathTest`
demuestra que el camino hacia adelante se completa, y `RegisterApplicationSagaCompensationTest`
— Listado 18.5 — demuestra que el camino de fallo compensa el paso raíz y no deja
huérfanos. Cuando se ejecuta el test de compensación verás al motor registrarlo en
tiempo real: `step.failed ... stepId=proposeOffer`, luego `compensation.started`, luego
una entrada `dead-lettered` que registra el paso fallido. Esas líneas de log son el
motor narrando exactamente el comportamiento que comprueban las afirmaciones.

!!! warning "Una saga es eventual, no atómica — las compensaciones deben ser seguras de ejecutar"
    Una saga renuncia a la atomicidad de todo-o-nada de `@Transactional`. Entre que el
    paso raíz confirma y un paso posterior falla, la solicitud *existe brevemente* antes
    de que la compensación la elimine: el sistema es consistente solo *eventualmente*.
    Eso pone peso sobre tus compensaciones: deben ser idempotentes (el motor puede
    reintentar), deben tolerar ejecutarse contra un paso cuyo efecto solo se aplicó
    parcialmente y no deberían fallar ellas mismas en silencio. Diseña cada método
    `compensate` con tanto cuidado como el paso que deshace.

## Dos hermanos: Workflow y TCC

La saga es uno de los tres patrones de orquestación que soporta el motor de Firefly. Los
otros dos resuelven el mismo problema de transacciones distribuidas con compromisos
distintos, y saber dónde encaja cada uno te evita forzar una saga sobre un trabajo para
el que es inadecuada. Ambos se describen aquí de forma conceptual — el reactor verifica
la saga, no estos — así que trata los fragmentos de abajo como un cómo-funciona, no como
algo que esta compilación ejecuta.

**Workflow — dispara hacia adelante, sin compensación.** Un workflow es el primo
optimista de una saga: una secuencia de pasos que corren hacia adelante hasta
completarse, *sin* compensar ante un fallo. Lo usas cuando los pasos no tienen un
deshacer significativo — enviar una secuencia de notificaciones, ejecutar un pipeline de
enriquecimiento, abrir en abanico llamadas de solo lectura — o cuando un paso fallido
debería simplemente detener el flujo y reintentarse más tarde en lugar de revertirse. Un
workflow conserva el DAG, el `ExecutionContext` y los eventos de paso, y descarta la
mitad de compensación:

```java
// Illustrative — a workflow step: forward-only, no compensate attribute.
@WorkflowStep(id = "notifyApplicant", dependsOn = "registerApplicant")
public Mono<Void> notifyApplicant(NotifyCommand command, ExecutionContext ctx) {
    return notifier.send(command);            // nothing to undo if a later step fails
}
```

**TCC — Try, Confirm, Cancel.** TCC es el primo más estricto de la saga, para recursos
que soportan *reserva*. Cada participante expone tres operaciones: **Try** reserva el
recurso (poner una retención sobre fondos, reservar inventario) sin confirmarlo;
**Confirm** hace permanente cada reserva una vez que todos los Try tienen éxito;
**Cancel** libera las reservas si algún Try falla. Donde una saga confirma cada paso y
compensa después, TCC mantiene todo en un estado reservado hasta que se sabe que la
operación entera tendrá éxito — de modo que no hay ninguna ventana en la que un paso
confirmado deba deshacerse de forma visible. Cuesta más (cada recurso debe soportar el
protocolo de tres fases) y compra una consistencia más estrecha:

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
    y del mismo `ExecutionContext`. Una **Saga** confirma cada paso y *compensa* los
    pasos completados en orden inverso ante un fallo — mejor cuando los pasos tienen un
    deshacer semántico limpio. Un **Workflow** corre hacia adelante *sin* compensación —
    mejor cuando los pasos no pueden o no necesitan deshacerse. **TCC**
    (Try-Confirm-Cancel) *reserva* cada recurso, y luego los confirma todos o los cancela
    todos — mejor cuando los participantes soportan reservas y quieres evitar una ventana
    visible de confirmado-y-luego-deshecho. Elige según cómo se comporten tus recursos:
    reversibles, dispara-hacia-adelante o reservables.

## Lo que has construido {.recap}

- Un bean **`@Saga`**, `RegisterApplicationSaga`, cuyos métodos son `@SagaStep`s: una
  raíz `registerLoanApplication` y dos dependientes, `registerApplicant` y
  `proposeOffer`, que `dependsOn` de la raíz y se abren en abanico una vez que se
  completa.
- Una **compensación** para cada paso — la `removeLoanApplication` de la raíz borra la
  solicitud que el paso creó, dado el mismísimo id que el paso devolvió — que es el
  sustituto entre servicios del rollback de `@Transactional`.
- El **`ExecutionContext`** transportando el id de la nueva solicitud desde el paso raíz
  (`putVariable`) hasta los dependientes (`getVariableAs`), de modo que los pasos
  comparten datos a lo largo de las aristas del DAG sin referenciarse unos a otros.
- Un **`@StepEvent`** en cada paso, de modo que el motor emite un evento de dominio a
  nivel de paso en el runtime de EDA a medida que la saga progresa.
- El **`SagaEngine`** ejecutado desde `LoanOriginationService`: `StepInputs` a la
  entrada, un `SagaResult` a la salida, exponiendo
  `isSuccess`/`isFailed`/`failedSteps`/`compensatedSteps`.
- El **test estrella de compensación** (`Tests run: 6, Failures: 0`): forzar el fallo de
  `proposeOffer` demuestra que el paso raíz se compensa y la solicitud creada se elimina
  — **sin huérfanos** — sin servicio central y sin Docker.

## Pruébalo tú mismo {.exercises}

1. **Lleva el fallo a la API.** `submitApplication` devuelve el `SagaResult` en bruto.
   Esboza cómo un llamador de la capa de experiencia convertiría `result.isFailed()` más
   `result.failedSteps()` en una respuesta HTTP — qué código de estado, y qué pondrías en
   el `detail` de RFC 7807. No necesitas ejecutarlo; argumenta el mapeo.
2. **Haz fallar la raíz en su lugar.** En `StubLoanOriginationClient`, añade un
   interruptor `failCreate()` como el `failProposeOffer()` existente y escribe un test que
   haga fallar el paso *raíz*. ¿Qué debería contener `compensatedSteps()`, y por qué está
   vacío? (Pista: la raíz nunca se completó, así que no hay nada que deshacer.)
3. **Da a un dependiente un deshacer real.** `removeOffer` devuelve `Mono.empty()`. Cambia
   `proposeOffer` para que su compensación llame a un nuevo método del stub que registre
   una oferta eliminada, luego haz fallar un *tercer* paso y afirma que tanto la raíz como
   la oferta se compensan. Esto demuestra que el motor compensa *todos* los pasos
   completados, en orden inverso.
4. **Rastrea un evento de paso.** Añade un `@EventListener` (capítulo 11) para el tipo de
   evento de paso `offer.proposed` y afirma, en el camino feliz, que se disparó
   exactamente una vez. Luego ejecuta el test de compensación y confirma que *no* se
   disparó — el paso de la oferta nunca se completó, así que su `@StepEvent` nunca se
   emitió.
5. **Elige el patrón.** Para cada una de estas operaciones, decide Saga, Workflow o TCC y
   justifícalo en una sola frase: (a) reservar asientos, cobrar una tarjeta, emitir
   entradas; (b) enviar un correo de bienvenida, y luego uno de seguimiento; (c) debitar
   una cuenta y abonar otra a través de dos servicios centrales.

## Adónde ir ahora

Ahora tienes la capacidad más difícil de la capa de dominio: una transacción distribuida
que o bien se completa o bien se deshace limpiamente a sí misma, verificada sin un solo
servicio aguas abajo en ejecución. Pero los pasos de la saga siguen llamando al *stub*
`LoanOriginationClient`. Los próximos capítulos reemplazan esa costura con el SDK central
generado y conectan los comandos de la saga a llamadas HTTP reales contra el sistema
central de registro que construiste antes — momento en el que `removeLoanApplication`
borra una fila real en un servicio real, y la compensación que demostraste aquí protege
datos de producción, no la lista de un stub.
