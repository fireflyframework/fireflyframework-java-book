El capítulo 9 dejó la solicitud de préstamo aplicando sus propias reglas — pero cambiaba
de estado mediante una llamada a método directa dentro de un único servicio. Eso funciona
hasta que la orquestación se vuelve seria. Un servicio de dominio que registra una
solicitud, adjunta un solicitante, propone una oferta *y* sabe cómo deshacer cada paso si
uno posterior falla es demasiado comportamiento como para verterlo en un único método. La
cura consiste en dividir el trabajo en unidades discretas, con nombre y comprobables de
forma individual, y despacharlas a través de un bus en lugar de llamarlas directamente.
Eso es **CQRS**, y es el corazón de la capa de dominio.

En este capítulo conoces la capa de **dominio** de Lumen Lending — `starter-domain`, la
capa de orquestación que no posee base de datos y habla con el sistema de registro central
a través de un SDK. Definirás un `Command<R>` y un `Query<R>`, escribirás los manejadores
de método único que los satisfacen y verás cómo el framework descubre y despacha esos
manejadores por su tipo genérico, sin registro manual. Verás el `CommandBus` y el
`QueryBus` que enrutan el trabajo, el `ExecutionContext` multi-tenant que fluye a través de
él y la costura del SDK — un puerto reactivo — que los manejadores de comandos llaman para
alcanzar el servicio central.

Y este es el capítulo en el que el cableado deja de ser teórico. Los manejadores que
construyes aquí ahora se ejecutan **en vivo**: cuando haces POST al BFF de experiencia en
el puerto `8080`, la petición fluye `exp → domain → core`, el dominio ejecuta el
`RegisterApplicationSaga` y la saga envía un `RegisterLoanApplicationCommand` por el
`CommandBus` directo al `RegisterLoanApplicationHandler` que estás a punto de leer — que
escribe en el servicio central por HTTP y recibe de vuelta una solicitud `SUBMITTED`. La
pila completa se ejecuta sin Docker (el core en `:8081` sobre H2, el dominio en `:8082`, el
exp en `:8080`). Conociste ese flujo de principio a fin en la guía rápida; aquí construyes
la pieza del medio que lo hace funcionar.

Todo lo que troceas vive en el módulo `domain-lending-loan-origination`, y una batería de
seis pruebas demuestra que la capa arranca y se ejecuta sin servicio central y sin Docker.
Empecemos con las dos mitades de CQRS: comandos que cambian el estado y consultas que lo
leen.

## Por qué separar los comandos de las consultas

El acrónimo es **C**ommand **Q**uery **R**esponsibility **S**egregation, y la idea es más
antigua que cualquier framework: la operación que *cambia* el mundo y la operación que lo
*lee* tienen formas distintas, necesidades de escalado distintas y modos de fallo
distintos, así que modélalas por separado. Un comando — "registra esta solicitud de
préstamo" — es un imperativo con un resultado que te importa (el nuevo id). Una consulta —
"¿cuál es el estado de esta solicitud?" — es una pregunta con una respuesta y sin efectos
secundarios.

Firefly hace concreta la separación con dos interfaces genéricas y dos buses. Un comando
implementa `Command<R>`, donde `R` es el tipo de resultado, y viaja por el `CommandBus`.
Una consulta implementa `Query<R>` y viaja por el `QueryBus`. Los buses están separados a
propósito: la ruta de escritura puede validar, emitir eventos y participar en una saga,
mientras que la ruta de lectura puede cachear de forma agresiva, porque una lectura no
cambia nada.

!!! note "Término clave — CQRS"
    **CQRS** separa el modelo que *escribe* el estado (comandos) del modelo que lo *lee*
    (consultas). En Firefly cada uno es un pequeño mensaje tipado — `Command<R>` o
    `Query<R>` — despachado a través de su propio bus a un manejador que el framework
    descubre por el tipo genérico. El beneficio no es la ceremonia; es que cada operación
    se convierte en una unidad discreta que puedes probar, trazar, cachear y orquestar de
    forma independiente.

!!! note "Término clave — la capa de dominio (orquestación)"
    La **capa de dominio** — construida sobre `fireflyframework-starter-domain` — es la capa
    de orquestación de una flota Firefly. No posee *ninguna* base de datos. Su trabajo es
    componer los sistemas de registro centrales en flujos de negocio: despacha comandos y
    consultas en los buses de CQRS, ejecuta sagas (capítulo 11), publica eventos de dominio
    y alcanza cada servicio central a través de una costura del SDK — una interfaz de
    cliente reactiva. En Lumen sirve en el puerto `8082` y se sitúa entre el BFF de
    experiencia (`8080`) y el servicio central (`8081`).

## Paso 1 — Definir un comando

Un comando es un mensaje sencillo: los datos que la operación necesita, más el tipo de
resultado integrado en la interfaz. Aquí tienes el comando para registrar una solicitud de
préstamo. Implementa `Command<UUID>` — el resultado es el id asignado por el servidor — y
lleva solo los dos campos que esta porción necesita.

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/command/RegisterLoanApplicationCommand.java | Listado 10.1 — un comando es un mensaje tipado que lleva su tipo de resultado
public final class RegisterLoanApplicationCommand implements Command<UUID> {

    private final String applicantName;
    private final long amount;

    public RegisterLoanApplicationCommand(String applicantName, long amount) {
        this.applicantName = applicantName;
        this.amount = amount;
    }

    public String getApplicantName() {
        return applicantName;
    }

    public long getAmount() {
        return amount;
    }
}
:::

Ese `Command<UUID>` es la parte que soporta la carga. El parámetro de tipo es un
*contrato*: dice "despacharme produce un `Mono<UUID>`", y también es la clave que el
framework usa para encontrar el manejador. El comando en sí no tiene comportamiento — ni
lógica, ni cliente, ni bus. Es un sobre. El comportamiento vive en un manejador, y el
parámetro de tipo es el cable entre ambos.

El comando es además intencionadamente *inmutable en sus campos centrales*: `applicantName`
y `amount` son `final`, fijados una sola vez en el constructor. Un comando es un valor que
creas, entregas al bus y nunca mutas — lo que hace que sea seguro registrarlo en logs,
reproducirlo y razonar sobre él. (Verás en el capítulo 11 que los comandos *dependientes*
añaden una ranura mutable `loanApplicationId` que la saga estampa justo antes del despacho;
esa es la única excepción deliberada, y es local al paso de orquestación.)

!!! spring "Equivalente en Spring"
    `Command<R>` y `Query<R>` son interfaces de Firefly, pero la separación entre mensaje y
    manejador es la misma idea que hay detrás del `ApplicationEventPublisher` de Spring o de
    una librería mediadora como un port en Java de MediatR. Lo que Firefly añade sobre un
    publicador de eventos crudo es el *resultado tipado*: un comando devuelve un `Mono<R>`,
    así que quien llama recupera el nuevo id, no solo una notificación de tipo
    fire-and-forget.

## Paso 2 — Escribir el manejador

Un manejador es donde el comando hace su trabajo. El patrón de manejadores de Firefly es
deliberadamente estrecho: extiendes `CommandHandler<C, R>` e implementas *un solo* método,
`doHandle`, que recibe el comando y devuelve un `Mono<R>`. El framework envuelve tu
`doHandle` con validación, métricas, trazado y mapeo de errores, de modo que tu método
contiene solo el paso de negocio.

Aquí tienes el manejador del comando de registro. Está anotado con
`@CommandHandlerComponent`, inyecta el cliente de la costura del SDK y un publicador de
eventos, y en `doHandle` llama al core para crear la solicitud y luego publica un evento de
dominio.

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/handler/RegisterLoanApplicationHandler.java | Listado 10.2 — un manejador de comandos: un doHandle, anotado para su descubrimiento
@CommandHandlerComponent
public class RegisterLoanApplicationHandler extends CommandHandler<RegisterLoanApplicationCommand, UUID> {

    private final LoanOriginationClient client;
    private final EventPublisher eventPublisher;

    public RegisterLoanApplicationHandler(LoanOriginationClient client, EventPublisher eventPublisher) {
        this.client = client;
        this.eventPublisher = eventPublisher;
    }

    @Override
    protected Mono<UUID> doHandle(RegisterLoanApplicationCommand command) {
        return client.createLoanApplication(command.getApplicantName(), command.getAmount())
                .flatMap(id -> publishRegistered(id, command).thenReturn(id));
    }

    private Mono<Void> publishRegistered(UUID id, RegisterLoanApplicationCommand command) {
        var event = new LoanApplicationRegisteredEvent(id, command.getApplicantName(), command.getAmount());
        return eventPublisher.publish(event, LoanApplicationRegisteredEvent.EVENT_TYPE);
    }
}
:::

Lee los genéricos de la cláusula `extends`: `CommandHandler<RegisterLoanApplicationCommand, UUID>`.
El primer parámetro de tipo es el comando que responde este manejador; el segundo es el
resultado. Ese emparejamiento es exactamente la información que el framework necesita para
enrutar — indexa cada `@CommandHandlerComponent` por su tipo de comando, así que cuando algo
envía un `RegisterLoanApplicationCommand`, el bus ya sabe que este es el manejador. Nunca
escribes una línea `register(...)` ni una sentencia switch; el tipo genérico *es* el
registro.

Dentro de `doHandle`, el vocabulario reactivo del capítulo 5 es todo lo que necesitas.
`client.createLoanApplication(...)` devuelve un `Mono<UUID>`; `flatMap` encadena la
publicación asíncrona del evento y luego vuelve a emitir el id con `thenReturn`. Esta es la
forma canónica de "el comando escribe, luego emite un evento de dominio" — la escritura
alcanza primero el sistema de registro, y solo si tiene éxito sale el evento. El orden es lo
importante: `flatMap` no se suscribe a `publishRegistered(...)` hasta que
`createLoanApplication(...)` haya emitido un id, así que una escritura fallida en el core
cortocircuita y no se publica ningún evento para una escritura que no ocurrió.

!!! note "Término clave — el patrón de manejador de método único"
    Un manejador de Firefly extiende `CommandHandler<C, R>` (o `QueryHandler<Q, R>`) e
    implementa exactamente un método abstracto, `doHandle`. Todo lo transversal — la
    validación de la entrada, el temporizador de métricas, el span de trazado, la traducción
    de errores — vive en el método `handle` envolvente del framework, que llama a tu
    `doHandle`. Escribes el paso de negocio y nada más, y cada manejador de la flota se
    envuelve de la misma manera.

!!! note "Término clave — el handle público frente a tu doHandle"
    `doHandle` es `protected` — es *tu* paso de negocio y nada lo llama directamente. El bus
    (y la prueba del manejador que ejecutarás) invoca el `handle(command)` *público*
    heredado, el envoltorio del framework. `handle` valida el comando, abre un span de
    trazado, arranca un temporizador de métricas, llama a `doHandle` y mapea cualquier error
    lanzado o señalado sobre el modelo de errores común a toda la flota antes de devolver el
    `Mono<R>`. La separación es la razón por la que tu manejador sigue siendo una línea
    limpia mientras cada despacho sigue temporizándose, trazándose y validándose de forma
    idéntica.

!!! spring "Equivalente en Spring"
    `@CommandHandlerComponent` es un estereotipo de Spring meta-anotado — bajo el capó es un
    `@Component`, así que el escaneo de componentes encuentra el manejador exactamente igual
    que encuentra un `@Service`. La aportación de Firefly es el post-procesamiento que lee
    los parámetros de tipo genérico del manejador y lo registra en el `CommandBus`. Sin XML,
    sin `bus.register(...)` manual: la presencia en el classpath más una firma genérica son
    todo el cableado.

## Paso 3 — Definir una consulta y su manejador

El lado de lectura es simétrico y más sencillo. Una consulta implementa `Query<R>`; aquí
`GetApplicationStatusQuery` implementa `Query<String>` porque la respuesta es una etiqueta
de estado.

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/query/GetApplicationStatusQuery.java | Listado 10.3 — una consulta es una pregunta tipada
public final class GetApplicationStatusQuery implements Query<String> {

    private final UUID loanApplicationId;

    public GetApplicationStatusQuery(UUID loanApplicationId) {
        this.loanApplicationId = loanApplicationId;
    }

    public UUID getLoanApplicationId() {
        return loanApplicationId;
    }
}
:::

Su manejador sigue el mismo patrón de método único que el manejador de comandos, pero
extiende `QueryHandler<Q, R>` y está anotado con `@QueryHandlerComponent`. La porción
mantiene el modelo de lectura trivial a propósito — una vez que existe un id de solicitud,
su estado se reporta como `REGISTERED` — para que la separación comando/consulta sea visible
sin arrastrar un almacén de proyección aparte.

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/handler/GetApplicationStatusHandler.java | Listado 10.4 — el manejador del lado de lectura, descubierto en el QueryBus
@QueryHandlerComponent
public class GetApplicationStatusHandler extends QueryHandler<GetApplicationStatusQuery, String> {

    @Override
    protected Mono<String> doHandle(GetApplicationStatusQuery query) {
        return Mono.just("REGISTERED");
    }
}
:::

Fíjate en lo poco que tiene: sin cliente, sin evento, solo un `Mono` con la respuesta. Un
manejador de lectura real consultaría una proyección o llamaría a la API de lectura del
core, pero la *forma* es idéntica — extiende la clase base, parametriza con la consulta y su
resultado, implementa `doHandle`. El framework lo descubre en el `QueryBus` precisamente de
la misma manera en que descubrió el manejador de comandos en el `CommandBus`, por el tipo
genérico.

Una pregunta justa en este punto: si la ruta de lectura en vivo devuelve una solicitud
`SUBMITTED` real desde el core, ¿por qué este manejador devuelve la constante `"REGISTERED"`?
Porque el *GET en vivo* en Lumen no pasa en absoluto por este manejador de consultas — pasa
por el controlador de dominio directo a un lector del core (verás esa costura en el paso 5).
El `GetApplicationStatusQuery` existe para enseñar la mitad de lectura de CQRS como un
mensaje tipado y enrutado por bus por derecho propio; mantener su manejador como una
constante es la forma honesta de mostrar el *mecanismo* sin levantar un almacén de proyección
que la porción no necesita. Ambas lecturas son reales; responden a preguntas distintas.

!!! tip "Punto de control"
    Detente y fíjate en el patrón. Cuatro archivos — dos mensajes, dos manejadores — y no has
    escrito ni una sola línea que *registre* un manejador, *enrute* un mensaje o se
    *suscriba* a un `Mono`. Las interfaces de comando/consulta declaran el tipo de resultado;
    las clases base de los manejadores declaran a qué mensaje responden; el framework lee
    esos genéricos y cablea los buses. Si venías esperando una clase de configuración, no la
    hay.

## Paso 4 — Despachar a través de los buses

Los manejadores se descubren, pero algo tiene que *enviar*. Eso es el `CommandBus` y el
`QueryBus`. Los inyectas como cualquier bean y llamas a `send` para un comando o a `query`
para una consulta; cada uno devuelve un `Mono<R>` cuyo tipo coincide con el parámetro de
resultado del mensaje.

El lugar más limpio para ver la ruta de lectura es el servicio de dominio.
`LoanOriginationService` inyecta el `QueryBus` y despacha la consulta de estado en una línea:

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/service/LoanOriginationService.java | Listado 10.5 — el servicio despacha una consulta a través del QueryBus
    /** Reads the current status of a loan application via the {@link QueryBus}. */
    public Mono<String> getApplicationStatus(UUID loanApplicationId) {
        return queryBus.query(new GetApplicationStatusQuery(loanApplicationId));
    }
:::

`queryBus.query(new GetApplicationStatusQuery(id))` devuelve `Mono<String>` — el compilador
infiere el tipo de resultado a partir del parámetro `Query<String>` de la consulta, y el bus
enruta a `GetApplicationStatusHandler` porque ese manejador declaró el mismo tipo de
consulta. Quien llama nunca nombra al manejador. Nombra el *mensaje*, y el bus encuentra el
resto.

Ese mismo `LoanOriginationService` es el que el flujo en vivo realmente conduce. Su otro
método, `submitApplication(...)`, no llama al `CommandBus` directamente — entrega el trabajo
al `SagaEngine`, que ejecuta el `RegisterApplicationSaga`, y *los pasos de la saga* son los
que llaman a `commandBus.send(...)`. No verás un `send` desnudo en el servicio, porque en
Lumen la ruta de escritura está envuelta en una saga (el tema del próximo capítulo), pero
dentro de un paso de saga la llamada es exactamente esa:

```java
// Inside a saga step — a command dispatched on the CommandBus.
return commandBus.send(command)
        .doOnNext(id -> ctx.putVariable(CTX_LOAN_APPLICATION_ID, id));
```

`commandBus.send(command)` devuelve el `Mono<UUID>` prometido por el parámetro `Command<UUID>`
del comando; el paso guarda el nuevo id en el `ExecutionContext` para que los pasos
posteriores puedan leerlo. Tanto si se despacha directamente como desde un paso de saga, el
contrato es el mismo: entrega al bus un mensaje tipado, recibe de vuelta un `Mono` de su
resultado declarado. Ese fragmento no es un boceto — es el cuerpo exacto del paso raíz de la
saga, y es la línea que se ejecuta cada vez que el BFF envía una solicitud. El comando que
definiste en el paso 1, el manejador que escribiste en el paso 2 y esta llamada
`commandBus.send` son los tres eslabones que enlaza el flujo en vivo `exp → domain → core`.

!!! note "Término clave — ExecutionContext"
    El **`ExecutionContext`** es la bolsa de variables y metadatos con ámbito de petición que
    fluye a través de un despacho — a través de los manejadores de comandos y consultas, y a
    través de cada paso de una saga. Es cómo una flota multi-tenant mantiene un id de tenant,
    un id de correlación y resultados intermedios (como el nuevo id de solicitud) viajando con
    el trabajo, sobre la pila reactiva, sin un `ThreadLocal`. Escribes en él con `putVariable`
    y lo lees de vuelta, tipado, con `getVariableAs`.

## Paso 5 — La costura del SDK, y cómo la usa el flujo en vivo

El manejador de comandos llamó a `client.createLoanApplication(...)`. ¿Qué es ese cliente?
Es un **puerto reactivo** — una interfaz de la que depende la capa de dominio para alcanzar
el sistema de registro central. Aquí tienes la costura.

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/client/LoanOriginationClient.java | Listado 10.6 — la costura del SDK: un puerto reactivo al servicio central
public interface LoanOriginationClient {

    /**
     * Creates the loan application in the core system of record and returns its server-assigned id.
     *
     * @param applicantName the primary applicant's display name
     * @param amount        the requested principal, in minor units
     * @return the new loan application id
     */
    Mono<UUID> createLoanApplication(String applicantName, long amount);
:::

Seamos claros sobre qué es esto. En un despliegue real de Firefly, la capa de dominio no
escribe a mano esta interfaz — inyecta el *SDK del core generado*, un cliente basado en
WebClient producido a partir del contrato OpenAPI del servicio central. El reactor escribe a
mano un puerto recortado aquí por una razón honesta: para que el ejemplo compile y sus
pruebas se ejecuten **sin un servicio central en ejecución y sin Docker**. Pero el puerto es
más que un stub didáctico — es la costura que da a la capa *dos* implementaciones
intercambiables, y la pila en vivo usa ambas:

- En las **pruebas de la porción**, un `@Bean` suministra el `StubLoanOriginationClient` en
  memoria (bajo `src/test/java`), que registra las llamadas y devuelve ids sintéticos. Sin
  HTTP, sin core, sin Docker.
- En la **pila en ejecución**, un `WebClientLoanOriginationClient` implementa el mismo puerto
  haciendo POST al servicio central por HTTP. Se activa solo cuando fijas la ruta base del
  core, así que nunca desplaza al stub de pruebas.

El manejador no sabe ni le importa cuál recibió — depende de la *interfaz*, nunca de un
cliente concreto. Esa es toda la recompensa del puerto: el mismo
`RegisterLoanApplicationHandler` se ejercita en una prueba unitaria de un milisegundo y en el
flujo en vivo de tres capas, sin cambios.

!!! note "Término clave — la costura del SDK"
    Una **costura** es un lugar donde puedes cambiar el comportamiento sin editar el código a
    ninguno de sus dos lados. `LoanOriginationClient` es la costura del SDK de la capa de
    dominio hacia el servicio central: el cableado de producción enchufa el SDK generado, el
    ejemplo en vivo enchufa un adaptador `WebClient`, y las pruebas enchufan un stub en
    memoria. Las capas se integran sobre un *contrato*, nunca sobre una base de datos
    compartida — la regla que el capítulo 1 fijó para toda la flota.

Puedes ver al adaptador en vivo haciendo la llamada real. `WebClientLoanOriginationClient`
implementa el mismo `createLoanApplication(...)` que llama el manejador, y lo convierte en un
POST HTTP al servicio central:

```java
// From WebClientLoanOriginationClient — the live adapter behind the same port.
return webClient.post()
        .uri(LOAN_APPLICATIONS_PATH)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .retrieve()
        .bodyToMono(CoreLoanApplicationResponse.class)
        .map(CoreLoanApplicationResponse::loanApplicationId);
```

Cuando haces POST al BFF, este es el fondo de la pila de llamadas: el
`RegisterLoanApplicationHandler` llama al puerto, el `WebClientLoanOriginationClient` hace
POST al `/api/v1/loan-applications` del core, y el `loanApplicationId` asignado por el core
sube de vuelta a través de la saga, el controlador y el BFF.

!!! warning "El mapeo en vivo es intencionadamente mínimo — y los pasos dependientes se ejecutan en proceso"
    Seamos honestos sobre dos recortes. Primero, la costura de escritura en vivo lleva solo el
    *nombre* y el *importe* del solicitante, así que el core recibe valores por defecto
    sensatos para los campos que el puerto recortado no pasa — `currency` aterriza como `EUR`,
    `termMonths` como `12`, `purpose` como `GENERAL`. (Por eso el valor que lees de vuelta del
    core puede diferir de lo que enviaste al BFF; un mapeo más rico es trabajo del SDK
    generado.) Segundo, solo el paso *raíz* — `createLoanApplication`, y su compensación
    `removeLoanApplication` — alcanza realmente el core, porque el controlador recortado del
    core expone solo el recurso de solicitud de préstamo. Los pasos dependientes
    (`addApplicant`, `proposeOffer`) no tienen endpoint en el core en esta porción, así que se
    completan en proceso con un id sintetizado — suficiente para que la saga termine mientras
    la escritura real y su compensación fluyen por HTTP. El SDK generado rellena ambas
    lagunas en un servicio de producción.

## Cómo cachean las consultas, y dónde reporta el bus

Vale la pena conocer dos capacidades de los buses de CQRS aunque la porción no las ejercite,
porque explican *por qué* la separación comando/consulta es más que nomenclatura.

Como una consulta no cambia nada, el `QueryBus` puede cachear su resultado. La
autoconfiguración de CQRS de Firefly admite cachear la respuesta de una consulta indexada por
la instancia de la consulta, de modo que un `GetApplicationStatusQuery` repetido para el
mismo id puede servirse desde la caché en lugar de volver a golpear el modelo de lectura. Te
suscribes por tipo de consulta y lo ajustas con propiedades `firefly.cqrs.*`; el bus de
comandos nunca cachea, porque todo el propósito de un comando es el efecto secundario.
Conceptualmente un manejador de consultas cacheable tiene este aspecto:

```java
// Illustrative — a query handler that opts into result caching.
@QueryHandlerComponent(cacheable = true, cacheTtl = 30)
public class GetApplicationStatusHandler extends QueryHandler<GetApplicationStatusQuery, String> {
    @Override
    protected Mono<String> doHandle(GetApplicationStatusQuery query) {
        return Mono.just("REGISTERED");
    }
}
```

Y como cada despacho fluye a través de un bus, el framework puede observarlo. Cuando la
capacidad de CQRS está en el classpath, aporta un endpoint de Actuator, `/actuator/cqrs`, que
reporta los manejadores registrados y las métricas por tipo — cuántos comandos y consultas se
han despachado, sus latencias, sus recuentos de fallos. No cableaste nada de eso; viene con el
bus, de forma idéntica en toda la flota. La porción de este capítulo no afirma nada contra el
endpoint, así que trata tanto el cacheo como `/actuator/cqrs` como cómo-funciona, no como algo
que esta build verifique.

!!! spring "Equivalente en Spring"
    El cacheo de consultas se monta sobre la abstracción de caché de Spring, y `/actuator/cqrs`
    es un endpoint corriente de Spring Boot Actuator — ambos son mecanismos que podrías
    ensamblar a mano en una app de toda la vida. El valor de Firefly es que están precableados
    a los buses: cada comando y consulta se temporiza y se cuenta, y cualquier consulta puede
    suscribirse al cacheo, sin que toques un `CacheManager` ni escribas un `@Endpoint`.

## Ejecútalo

Toda la capa arranca y se ejecuta sin un servicio central. El reactor lo demuestra con seis
pruebas: dos que ejercitan el manejador de registro de forma aislada contra un stub en
memoria, y cuatro que arrancan un contexto Spring real para cablear los buses, los manejadores
y la saga — sustituyendo solo la costura del SDK. Desde el directorio `samples/lumen-lending`:

```text
mvn -q -pl domain-lending-loan-origination test
```

El resultado esperado:

```text
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

La prueba del manejador es la que conviene leer con atención, porque muestra la maquinaria de
despacho sin ningún contexto Spring en absoluto — construye el manejador directamente y llama
al método `handle` público del framework, que envuelve `doHandle`:

```java
// From RegisterLoanApplicationHandlerTest — handle(...) wraps your doHandle(...).
StepVerifier.create(handler.handle(command))
        .assertNext(id -> assertThat(id).isEqualTo(client.createdApplications().get(0)))
        .verifyComplete();
```

Las cuatro pruebas que arrancan el contexto van un nivel más arriba: dejan que el framework
descubra cada `@CommandHandlerComponent`, registre el `@Saga` y cablee el `CommandBus` y el
`QueryBus` — y la *única* sustitución es un `@Bean` que suministra el `StubLoanOriginationClient`
en memoria para el puerto `LoanOriginationClient`. Sin Docker, sin servicio central. Eso es la
costura del SDK ganándose el sueldo.

Para ver los *mismos* manejadores ejecutarse de verdad, arranca la pila completa y haz POST al
BFF — el flujo de principio a fin que ejecutaste en la guía rápida. Con las tres capas
levantadas (el core en `:8081`, el dominio en `:8082`, el exp en `:8080`):

```text
curl -s -X POST localhost:8080/api/v1/experience/lending/applications \
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

En la capa de dominio puedes observar a la saga conducir la escritura — el paso raíz se ejecuta
primero y escribe al core por HTTP, luego los dos pasos dependientes se completan en proceso, y
la saga reporta éxito:

```text
[orchestration] started   name=RegisterApplicationSaga ... pattern=SAGA
[orchestration] step.success ... stepId=registerLoanApplication latencyMs=94
[orchestration] step.success ... stepId=proposeOffer
[orchestration] step.success ... stepId=registerApplicant
[orchestration] completed name=RegisterApplicationSaga ... pattern=SAGA success=true
```

Esa línea `stepId=registerLoanApplication` es tu manejador ejecutándose en vivo: la saga envió
un `RegisterLoanApplicationCommand` por el `CommandBus`, el bus lo enrutó a
`RegisterLoanApplicationHandler`, el manejador llamó al puerto `LoanOriginationClient`, y el
`WebClientLoanOriginationClient` hizo POST al core — que es por lo que el `applicationId` de la
respuesta es el id que asignó el sistema de registro central. El mecanismo comando/consulta que
construiste en las pruebas y el flujo de producción son el mismo código.

!!! tip "Punto de control"
    Seis pruebas en verde, y ni una de ellas necesita la capa core en ejecución — y sin embargo
    los *mismos* manejadores, despachados a través de los *mismos* buses, también se ejecutan en
    vivo cuando el BFF envía una solicitud y la saga escribe al core por HTTP. La separación
    comando/consulta, los buses tipados y el puerto juntos te permiten probar la capa de
    orquestación en completo aislamiento *y* ejecutarla de verdad sin cambiar una línea. Eso es
    la realimentación más rápida posible para el código más crítico para el negocio de la flota.

## Lo que has construido {.recap}

- Un **`Command<UUID>`** y un **`Query<String>`** — mensajes tipados que llevan su tipo de
  resultado, el lado de escritura en el `CommandBus`, el lado de lectura en el `QueryBus`,
  separados porque escribir y leer tienen formas y necesidades distintas.
- Dos **manejadores de método único** — los estereotipos `@CommandHandlerComponent` y
  `@QueryHandlerComponent` que extienden `CommandHandler<C, R>` / `QueryHandler<Q, R>` e
  implementan solo `doHandle`, mientras que el `handle` público del framework suministra
  validación, métricas, trazado y mapeo de errores a su alrededor.
- **Autodescubrimiento por tipo genérico** — el framework indexa cada manejador por su parámetro
  de tipo de comando/consulta y enruta `commandBus.send` / `queryBus.query` hacia él, sin
  registro manual y sin sentencia switch.
- El **`ExecutionContext`** que transporta el tenant, la correlación y el estado intermedio a
  través de un despacho sobre la pila reactiva, y un primer vistazo al cacheo de consultas y a
  `/actuator/cqrs` como capacidades que el bus aporta de forma gratuita.
- La **costura del SDK** — `LoanOriginationClient`, un puerto reactivo con dos implementaciones:
  el `StubLoanOriginationClient` en memoria que mantiene en verde las pruebas de la capa sin
  servicio central y sin Docker, y el `WebClientLoanOriginationClient` en vivo que hace POST al
  core por HTTP para que los manejadores se ejecuten de verdad dentro de la saga que el BFF
  dispara.

## Pruébalo tú mismo {.exercises}

1. **Añade una consulta.** Define `GetApplicationAmountQuery implements Query<Long>` junto a la
   consulta de estado, y un manejador `@QueryHandlerComponent` que devuelva un `Mono.just(0L)`
   fijo. Inyecta el `QueryBus` en una prueba pequeña y despáchalo. No escribiste código de
   registro — explica qué línea le indicó al framework la existencia de tu nuevo manejador.
2. **Lee el cableado genérico.** En `RegisterLoanApplicationHandler`, cambia el segundo parámetro
   de tipo de `CommandHandler<RegisterLoanApplicationCommand, UUID>` a `String` sin cambiar
   `doHandle`. Lee el error de compilación y explica, en una frase, por qué el tipo de resultado
   es parte del contrato y no una elección libre.
3. **Demuestra que el puerto es la costura.** Abre `StubLoanOriginationClient` bajo
   `src/test/java`, cambia el formato de la llamada que registra en `createLoanApplication`, y
   vuelve a ejecutar `RegisterLoanApplicationHandlerTest`. Ajusta solo la aserción de la prueba y
   confirma que el *manejador* nunca cambió — depende de la interfaz, no del stub.
4. **Traza un comando hasta su manejador.** Partiendo de `commandBus.send(command)` en
   `RegisterApplicationSaga`, sigue los parámetros de tipo: qué manejador responde a un
   `RegisterLoanApplicationCommand`, y qué hay en la declaración de su clase que hace que el bus
   lo elija. Anota el único hecho que el bus usa para enrutar.
5. **Ejecuta el flujo en vivo y encuentra tu manejador en el log.** Arranca las tres capas, haz
   POST al BFF como en *Ejecútalo*, y encuentra la línea `stepId=registerLoanApplication` en el
   log de dominio. Explica el camino desde ese POST hasta que `WebClientLoanOriginationClient`
   hace POST al core, nombrando el comando, el bus, el manejador y el puerto en orden.
6. **Haz cacheable una consulta mentalmente.** El `GetApplicationStatusHandler` de la porción
   devuelve una constante. Argumenta por qué cachearlo sería seguro, luego describe un cambio en
   el modelo de lectura que haría el cacheo *inseguro* — y qué perilla `firefly.cqrs.*` usarías
   para acotar la obsolescencia.

## Adónde ir ahora

Ya tienes comandos y consultas discretos, despachados y comprobables — y los has visto
ejecutarse en vivo dentro de una saga que el BFF dispara. Pero un registro real son varios
comandos que deben tener éxito o fallar *juntos*, deshaciendo cada paso completado si uno
posterior se rompe. El capítulo 11 introduce la **saga**: la orquestación con `@Saga` y
`@SagaStep` que ejecuta `registerLoanApplication`, `registerApplicant` y `proposeOffer` como un
solo flujo, enhebra el nuevo id a través del `ExecutionContext` que conociste aquí, y compensa
en orden inverso cuando un paso falla. Los comandos que acabas de construir son exactamente los
pasos que esa saga orquesta — y ya la has visto escribir al core por HTTP.
