El capítulo 9 dejó la solicitud de préstamo aplicando sus propias reglas, pero cambiaba
de estado mediante una llamada directa a un método dentro de un único servicio. Eso
funciona hasta que la orquestación se vuelve seria. Un servicio de dominio que registra
una solicitud, le adjunta un solicitante, propone una oferta *y* además sabe cómo
deshacer cada paso si uno posterior falla es mucho comportamiento para volcarlo en un
solo método. La cura consiste en descomponer el trabajo en unidades discretas, con
nombre y verificables de forma individual, y despacharlas a través de un bus en lugar de
llamarlas directamente. Eso es **CQRS**, y es el corazón de la capa de dominio.

En este capítulo conoces la capa de **dominio** de Lumen Lending: `starter-domain`, la
capa de orquestación que no posee ninguna base de datos y habla con el sistema central de
registro a través de un SDK. Definirás un `Command<R>` y una `Query<R>`, escribirás los
manejadores de un único método que los satisfacen y verás cómo el framework descubre y
despacha esos manejadores por su tipo genérico, sin registro manual. Verás el
`CommandBus` y el `QueryBus` que enrutan el trabajo, el `ExecutionContext` multi-tenant
que fluye a través de él y la costura del SDK —un puerto reactivo— que sustituye al
cliente central generado hasta que el capítulo 14 conecta el real.

Todo lo que separes vive en el módulo `domain-lending-loan-origination`, y una batería de
seis pruebas demuestra que arranca y se ejecuta sin servicio central y sin Docker.
Empecemos con las dos mitades de CQRS: comandos que cambian el estado y consultas que lo
leen.

## Por qué separar comandos de consultas

El acrónimo es **C**ommand **Q**uery **R**esponsibility **S**egregation, y la idea es más
antigua que cualquier framework: la operación que *cambia* el mundo y la operación que lo
*lee* tienen formas distintas, necesidades de escalado distintas y modos de fallo
distintos, así que modélalas por separado. Un comando —"registra esta solicitud de
préstamo"— es un imperativo con un resultado que te importa (el nuevo id). Una consulta
—"¿cuál es el estado de esta solicitud?"— es una pregunta con una respuesta y sin efectos
secundarios.

Firefly hace concreta esa separación con dos interfaces genéricas y dos buses. Un comando
implementa `Command<R>`, donde `R` es el tipo de resultado, y viaja por el `CommandBus`.
Una consulta implementa `Query<R>` y viaja por el `QueryBus`. Los buses están separados a
propósito: el camino de escritura puede validar, emitir eventos y participar en una saga,
mientras que el camino de lectura puede cachear de forma agresiva, porque una lectura no
cambia nada.

!!! note "Término clave — CQRS"
    **CQRS** separa el modelo que *escribe* el estado (comandos) del modelo que lo *lee*
    (consultas). En Firefly cada uno es un pequeño mensaje tipado —`Command<R>` o
    `Query<R>`— despachado a través de su propio bus hacia un manejador que el framework
    descubre por el tipo genérico. El beneficio no es la ceremonia; es que cada operación
    se convierte en una unidad discreta que puedes probar, trazar, cachear y orquestar de
    forma independiente.

## Paso 1 — Definir un comando

Un comando es un mensaje sencillo: los datos que la operación necesita, más el tipo de
resultado incorporado en la interfaz. Aquí está el comando para registrar una solicitud
de préstamo. Implementa `Command<UUID>` —el resultado es el id asignado por el servidor— y
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

Ese `Command<UUID>` es la parte que sostiene todo. El parámetro de tipo es un *contrato*:
dice "despacharme produce un `Mono<UUID>`", y es además la clave que el framework usa para
encontrar el manejador. El comando en sí no tiene comportamiento: ni lógica, ni cliente,
ni bus. Es un sobre. El comportamiento vive en un manejador, y el parámetro de tipo es el
cable entre ambos.

!!! spring "Equivalente en Spring"
    `Command<R>` y `Query<R>` son interfaces de Firefly, pero la separación
    mensaje-y-manejador es la misma idea que hay detrás del `ApplicationEventPublisher` de
    Spring o de una biblioteca de mediación como un port de MediatR a Java. Lo que Firefly
    añade sobre un publicador de eventos en crudo es el *resultado tipado*: un comando
    devuelve un `Mono<R>`, de modo que quien llama recupera el nuevo id, no solo una
    notificación del tipo dispara-y-olvida.

## Paso 2 — Escribir el manejador

Un manejador es donde el comando hace su trabajo. El patrón de manejador de Firefly es
deliberadamente estrecho: extiendes `CommandHandler<C, R>` e implementas *un* método,
`doHandle`, que recibe el comando y devuelve un `Mono<R>`. El framework envuelve tu
`doHandle` con validación, métricas, trazado y mapeo de errores, de modo que tu método
contiene únicamente el paso de negocio.

Aquí está el manejador del comando de registro. Está anotado con
`@CommandHandlerComponent`, inyecta el cliente de la costura del SDK y un publicador de
eventos y, en `doHandle`, llama al núcleo para crear la solicitud y luego publica un
evento de dominio.

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/handler/RegisterLoanApplicationHandler.java | Listado 10.2 — un manejador de comandos: un solo doHandle, anotado para su descubrimiento
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
El primer parámetro de tipo es el comando que este manejador responde; el segundo es el
resultado. Ese emparejamiento es exactamente la información que el framework necesita para
enrutar: indexa cada `@CommandHandlerComponent` por su tipo de comando, de modo que cuando
algo envía un `RegisterLoanApplicationCommand`, el bus ya sabe que este es el manejador.
Nunca escribes una línea `register(...)` ni una sentencia switch; el tipo genérico *es* el
registro.

Dentro de `doHandle`, el vocabulario reactivo del capítulo 5 es todo lo que necesitas.
`client.createLoanApplication(...)` devuelve un `Mono<UUID>`; `flatMap` encadena la
publicación asíncrona del evento y luego vuelve a emitir el id con `thenReturn`. Esta es la
forma canónica "el comando escribe y luego emite un evento de dominio": la escritura
alcanza primero el sistema de registro, y solo en caso de éxito sale el evento.

!!! note "Término clave — el patrón de manejador de un único método"
    Un manejador de Firefly extiende `CommandHandler<C, R>` (o `QueryHandler<Q, R>`) e
    implementa exactamente un método abstracto, `doHandle`. Todo lo transversal
    —validación de la entrada, el temporizador de métricas, el span de trazado, la
    traducción de errores— vive en el método `handle` que rodea al tuyo en el framework, el
    cual llama a tu `doHandle`. Tú escribes el paso de negocio y nada más, y cada manejador
    de la flota se envuelve de la misma manera.

!!! spring "Equivalente en Spring"
    `@CommandHandlerComponent` es un estereotipo de Spring meta-anotado: por debajo es un
    `@Component`, de modo que el escaneo de componentes encuentra el manejador exactamente
    igual que encuentra un `@Service`. La aportación de Firefly es el post-procesamiento
    que lee los parámetros de tipo genérico del manejador y lo registra en el `CommandBus`.
    Sin XML, sin `bus.register(...)` manual: la presencia en el classpath más una firma
    genérica es todo el cableado.

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

Su manejador sigue el mismo patrón de un único método que el manejador de comandos, pero
extiende `QueryHandler<Q, R>` y está anotado con `@QueryHandlerComponent`. La porción
mantiene el modelo de lectura trivial a propósito —una vez que existe un id de solicitud,
su estado se reporta como `REGISTERED`— de modo que la separación comando/consulta es
visible sin arrastrar un almacén de proyección aparte.

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/handler/GetApplicationStatusHandler.java | Listado 10.4 — el manejador del lado de lectura, descubierto en el QueryBus
@QueryHandlerComponent
public class GetApplicationStatusHandler extends QueryHandler<GetApplicationStatusQuery, String> {

    @Override
    protected Mono<String> doHandle(GetApplicationStatusQuery query) {
        return Mono.just("REGISTERED");
    }
}
:::

Fíjate en lo poco que tiene: ni cliente, ni evento, solo un `Mono` con la respuesta. Un
manejador de lectura real consultaría una proyección o llamaría a la API de lectura del
núcleo, pero la *forma* es idéntica: extiende la clase base, parametriza con la consulta y
su resultado, implementa `doHandle`. El framework lo descubre en el `QueryBus` exactamente
del mismo modo que descubrió el manejador de comandos en el `CommandBus`, por el tipo
genérico.

!!! tip "Punto de control"
    Detente y fíjate en el patrón. Cuatro archivos —dos mensajes, dos manejadores— y no
    has escrito una sola línea que *registre* un manejador, *enrute* un mensaje o se
    *suscriba* a un `Mono`. Las interfaces de comando/consulta declaran el tipo de
    resultado; las clases base de los manejadores declaran qué mensaje responden; el
    framework lee esos genéricos y cablea los buses. Si venías esperando una clase de
    configuración, no la hay.

## Paso 4 — Despachar a través de los buses

Los manejadores se descubren, pero algo tiene que *enviar*. Eso son el `CommandBus` y el
`QueryBus`. Los inyectas como cualquier bean y llamas a `send` para un comando o a `query`
para una consulta; cada uno devuelve un `Mono<R>` cuyo tipo coincide con el parámetro de
resultado del mensaje.

El sitio más limpio para ver el camino de lectura es el servicio de dominio.
`LoanOriginationService` inyecta el `QueryBus` y despacha la consulta de estado en una
línea:

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/service/LoanOriginationService.java | Listado 10.5 — el servicio despacha una consulta a través del QueryBus
    /** Reads the current status of a loan application via the {@link QueryBus}. */
    public Mono<String> getApplicationStatus(UUID loanApplicationId) {
        return queryBus.query(new GetApplicationStatusQuery(loanApplicationId));
    }
:::

`queryBus.query(new GetApplicationStatusQuery(id))` devuelve `Mono<String>`: el compilador
infiere el tipo de resultado a partir del parámetro `Query<String>` de la consulta, y el
bus enruta a `GetApplicationStatusHandler` porque ese manejador declaró el mismo tipo de
consulta. Quien llama nunca nombra el manejador. Nombra el *mensaje*, y el bus encuentra
el resto.

El lado del comando despacha de la misma manera, con `commandBus.send(command)`. No verás
un `send` desnudo en el servicio, porque en Lumen el camino de escritura está envuelto en
una saga (el tema del próximo capítulo), pero dentro de un paso de saga la llamada es
exactamente esa:

```java
// Inside a saga step — a command dispatched on the CommandBus.
return commandBus.send(command)
        .doOnNext(id -> ctx.putVariable(CTX_LOAN_APPLICATION_ID, id));
```

`commandBus.send(command)` devuelve el `Mono<UUID>` prometido por el parámetro
`Command<UUID>` del comando; el paso guarda el nuevo id en el `ExecutionContext` para que
los pasos posteriores puedan leerlo. Tanto si se despacha directamente como desde un paso
de saga, el contrato es el mismo: entrega al bus un mensaje tipado y obtén de vuelta un
`Mono` de su resultado declarado.

!!! note "Término clave — ExecutionContext"
    El **`ExecutionContext`** es la bolsa de variables y metadatos con alcance de petición
    que fluye a través de un despacho —entre manejadores de comandos y consultas, y a
    través de cada paso de una saga—. Es la forma en que una flota multi-tenant mantiene un
    id de tenant, un id de correlación y resultados intermedios (como el nuevo id de
    solicitud) viajando con el trabajo, sobre la pila reactiva, sin un `ThreadLocal`.
    Escribes en él con `putVariable` y lo lees de vuelta, tipado, con `getVariableAs`.

## Paso 5 — La costura del SDK, con honestidad

El manejador de comandos llamaba a `client.createLoanApplication(...)`. ¿Qué es ese
cliente? Es un **puerto reactivo**: una interfaz de la que depende la capa de dominio para
alcanzar el sistema central de registro. Aquí está la costura.

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/client/LoanOriginationClient.java | Listado 10.6 — la costura del SDK: un puerto reactivo hacia el servicio central
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

Ten claro qué es esto. En un despliegue real de Firefly, la capa de dominio no escribe a
mano esta interfaz: inyecta el *SDK central generado*, un cliente basado en WebClient
producido a partir del contrato OpenAPI del servicio central. El reactor escribe a mano un
puerto recortado aquí por una razón honesta: para que el ejemplo compile y sus pruebas se
ejecuten **sin un servicio central en marcha y sin Docker**. El puerto es un sustituto. El
capítulo 14 lo reemplaza por el SDK generado y conecta la llamada HTTP real a la capa
central que construiste en los capítulos 7 y 8.

Esa sustitución es precisamente por qué importan CQRS y el puerto. El manejador depende de
la *interfaz*, nunca de un cliente concreto, de modo que una prueba puede proporcionar una
implementación en memoria y el cableado de producción puede proporcionar el SDK
generado, y el manejador no cambia. Las capas se integran sobre un contrato, nunca sobre
una base de datos compartida, que es la regla que el capítulo 1 fijó para toda la flota.

!!! warning "El puerto es un sustituto, no el cliente de producción"
    No leas `LoanOriginationClient` como "así llama Firefly a un servicio aguas abajo". El
    camino de producción es un cliente SDK *generado* con valores por defecto resilientes:
    reintentos, timeouts, un circuit breaker (capítulo 14). El puerto escrito a mano existe
    para que este capítulo pueda enseñar CQRS sin levantar el servicio central. Cuando veas
    el puerto, lee "aquí es donde se enchufa el SDK generado".

## Cómo cachean las consultas, y dónde reporta el bus

Dos capacidades de los buses de CQRS merece la pena conocerlas aunque la porción no las
ejercite, porque explican *por qué* la separación comando/consulta es más que una cuestión
de nomenclatura.

Como una consulta no cambia nada, el `QueryBus` puede cachear su resultado. La
autoconfiguración de CQRS de Firefly admite cachear la respuesta de una consulta indexada
por la instancia de la consulta, de modo que un `GetApplicationStatusQuery` repetido para
el mismo id puede servirse desde la caché en lugar de volver a golpear el modelo de
lectura. Te suscribes por tipo de consulta y lo ajustas con las propiedades
`firefly.cqrs.*`; el bus de comandos nunca cachea, porque el propósito entero de un comando
es el efecto secundario. Conceptualmente, un manejador de consulta cacheable tiene este
aspecto:

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
capacidad de CQRS está en el classpath, contribuye un endpoint de Actuator,
`/actuator/cqrs`, que reporta los manejadores registrados y métricas por tipo: cuántos
comandos y consultas se han despachado, sus latencias, sus recuentos de fallos. No cableaste
nada de eso; viene con el bus, idéntico en toda la flota. La porción de este capítulo no
hace aserciones contra el endpoint, así que trata tanto el cacheo como `/actuator/cqrs`
como un "así funciona", no como algo que esta build verifique.

!!! spring "Equivalente en Spring"
    El cacheo de consultas se apoya en la abstracción de caché de Spring, y
    `/actuator/cqrs` es un endpoint corriente de Spring Boot Actuator: ambos son mecanismos
    que podrías ensamblar a mano en una aplicación normal. El valor de Firefly es que están
    precableados a los buses: cada comando y cada consulta se cronometra y se cuenta, y
    cualquier consulta puede suscribirse al cacheo, sin que toques un `CacheManager` ni
    escribas un `@Endpoint`.

## Ejecútalo

La capa entera arranca y se ejecuta sin un servicio central. El reactor lo demuestra con
seis pruebas: dos que ejercitan el manejador de registro de forma aislada contra un stub en
memoria, y cuatro que arrancan un contexto real de Spring para cablear los buses, los
manejadores y la saga, sustituyendo únicamente la costura del SDK. Desde el directorio
`samples/lumen-lending`:

```text
mvn -q -pl domain-lending-loan-origination test
```

El resultado esperado:

```text
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

La prueba del manejador es la que conviene leer con atención, porque muestra la maquinaria
de despacho sin contexto de Spring en absoluto: construye el manejador directamente y llama
al método público `handle` del framework, que envuelve a `doHandle`:

```java
// From RegisterLoanApplicationHandlerTest — handle(...) wraps your doHandle(...).
StepVerifier.create(handler.handle(command))
        .assertNext(id -> assertThat(id).isEqualTo(client.createdApplications().get(0)))
        .verifyComplete();
```

Las cuatro pruebas que arrancan el contexto suben un nivel: dejan que el framework descubra
cada `@CommandHandlerComponent`, registre la `@Saga` y cablee el `CommandBus` y el
`QueryBus`, y la *única* sustitución es un `@Bean` que suministra el
`StubLoanOriginationClient` en memoria para el puerto `LoanOriginationClient`. Sin Docker,
sin servicio central. Eso es la costura del SDK ganándose el sueldo.

!!! tip "Punto de control"
    Seis pruebas en verde, y ninguna de ellas necesita la capa central en marcha. La
    separación comando/consulta, los buses tipados y el puerto, juntos, te permiten probar
    la capa de orquestación en completo aislamiento: el feedback más rápido posible para el
    código más crítico para el negocio de toda la flota.

## Lo que has construido {.recap}

- Un **`Command<UUID>`** y una **`Query<String>`** —mensajes tipados que llevan su tipo de
  resultado, el lado de escritura en el `CommandBus`, el lado de lectura en el `QueryBus`,
  separados porque escribir y leer tienen formas y necesidades distintas.
- Dos **manejadores de un único método** —los estereotipos `@CommandHandlerComponent` y
  `@QueryHandlerComponent` que extienden `CommandHandler<C, R>` / `QueryHandler<Q, R>` e
  implementan solo `doHandle`, mientras el framework aporta validación, métricas, trazado y
  mapeo de errores a su alrededor.
- **Auto-descubrimiento por tipo genérico** —el framework indexa cada manejador por su
  parámetro de tipo de comando/consulta y enruta `commandBus.send` / `queryBus.query` hacia
  él, sin registro manual y sin sentencia switch.
- El **`ExecutionContext`** que lleva tenant, correlación y estado intermedio a través de un
  despacho sobre la pila reactiva, y un primer vistazo al cacheo de consultas y a
  `/actuator/cqrs` como capacidades que el bus aporta gratis.
- La **costura del SDK** —`LoanOriginationClient`, un puerto reactivo que sustituye al SDK
  central generado para que la capa entera compile y pase sus pruebas en verde sin servicio
  central y sin Docker, y que el capítulo 14 reemplazará por el cliente real.

## Pruébalo tú mismo {.exercises}

1. **Añade una consulta.** Define `GetApplicationAmountQuery implements Query<Long>` junto a
   la consulta de estado, y un manejador `@QueryHandlerComponent` que devuelva un
   `Mono.just(0L)` fijo. Inyecta el `QueryBus` en una pequeña prueba y despáchalo. No
   escribiste código de registro: explica qué línea le contó al framework sobre tu nuevo
   manejador.
2. **Lee el cableado genérico.** En `RegisterLoanApplicationHandler`, cambia el segundo
   parámetro de tipo de `CommandHandler<RegisterLoanApplicationCommand, UUID>` a `String`
   sin cambiar `doHandle`. Lee el error de compilación y explica, en una frase, por qué el
   tipo de resultado es parte del contrato y no una elección libre.
3. **Demuestra que el puerto es la costura.** Abre `StubLoanOriginationClient` bajo
   `src/test/java`, cambia el formato de la llamada que registra en `createLoanApplication`
   y vuelve a ejecutar `RegisterLoanApplicationHandlerTest`. Ajusta únicamente la aserción
   de la prueba y confirma que el *manejador* nunca cambió: depende de la interfaz, no del
   stub.
4. **Traza un comando hasta su manejador.** Partiendo de `commandBus.send(command)` en
   `RegisterApplicationSaga`, sigue los parámetros de tipo: qué manejador responde a un
   `RegisterLoanApplicationCommand`, y qué hay en la declaración de su clase que hace que el
   bus lo escoja. Anota el único hecho que el bus usa para enrutar.
5. **Haz una consulta cacheable en tu cabeza.** El `GetApplicationStatusHandler` de la
   porción devuelve una constante. Argumenta por qué cachearla sería seguro, luego describe
   un cambio en el modelo de lectura que haría el cacheo *inseguro*, y qué perilla
   `firefly.cqrs.*` usarías para acotar la obsolescencia.

## Adónde ir ahora

Ahora tienes comandos y consultas discretos, despachados y verificables, pero un registro
real son varios comandos que deben tener éxito o fallar *juntos*, con cada paso completado
deshecho si uno posterior se rompe. El capítulo 11 introduce la **saga**: la orquestación
`@Saga` y `@SagaStep` que ejecuta `registerLoanApplication`, `registerApplicant` y
`proposeOffer` como un único flujo atómico, hila el nuevo id a través del
`ExecutionContext` que conociste aquí y compensa en orden inverso cuando un paso falla. Los
comandos que acabas de construir son exactamente los pasos que esa saga orquestará.
