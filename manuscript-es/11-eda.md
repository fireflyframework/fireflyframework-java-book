El capítulo 10 despachó un comando a través del bus CQRS y observó cómo un manejador
escribía en el sistema central de registro. Ese manejador hizo su trabajo y retornó. Pero en una
plataforma real, *registrar una solicitud de préstamo* nunca es el final de la historia: una
verificación de fraude quiere saberlo, un servicio de notificaciones quiere saludar al solicitante, una
canalización de analítica quiere contabilizarlo y un registro de auditoría quiere anotarlo. El arreglo ingenuo
consiste en que el manejador llame a los cuatro. Hazlo y el manejador pasa a conocer
el fraude, las notificaciones, la analítica y la auditoría; añade un quinto consumidor y editas el
manejador de nuevo. La escritura y todos los que se interesan por ella quedan soldados entre sí.

La arquitectura dirigida por eventos rompe esa soldadura. El manejador hace una sola cosa: escribir y luego
*anunciar* que escribió, publicando un **evento de dominio**. No sabe ni le importa
quién está escuchando. Los consumidores se suscriben al anuncio y reaccionan a su propio
ritmo, de forma independiente, y los añades o eliminas sin tocar al productor.
Este capítulo construye ese desacoplamiento en la capa de dominio de Lumen: un
`LoanApplicationRegisteredEvent` publicado por el manejador de registro y un
`@EventListener` que anota cada uno que recibe, conectado a través del entorno de ejecución EDA
de Firefly, demostrado mediante un test, **sin Kafka y sin Docker**.

La capacidad es `firefly-eda`. Como toda capacidad de Firefly es agnóstica del
transporte: el mismo código `@EventPublisher`/`@EventListener` se ejecuta sobre un bus dentro de la JVM,
Kafka, RabbitMQ o Postgres, elegido por configuración en lugar de reescribiendo tu
servicio. Lumen lo ejecuta dentro de la JVM para que el ejemplo se mantenga libre de dependencias, y por el camino
conocerás el detalle que hace tropezar a todo el mundo la primera vez: cómo decide el entorno de ejecución
a qué oyente pertenece un evento dado.

## El evento de dominio es un simple record

Un evento de dominio es un *hecho que ya ha ocurrido*, nombrado en pasado, que lleva
justo los datos suficientes para que un consumidor actúe sin tener que volver a llamar. Es un objeto de valor
inmutable: en Java, un `record`. El evento de Lumen dice «se ha registrado una solicitud de préstamo»
y lleva el id asignado por el servidor, el nombre del solicitante y el importe:

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/event/LoanApplicationRegisteredEvent.java | Listado 11.1 — el evento de dominio es un record inmutable
package com.firefly.lumen.domain.event;

import java.util.UUID;

/**
 * Domain event published when a loan application has been registered in the core
 * system of record.
 *
 * <p>This is the EDA (event-driven architecture) payload. The
 * {@link com.firefly.lumen.domain.handler.RegisterLoanApplicationHandler} publishes it
 * through the Firefly {@code EventPublisher} after the core call succeeds; any number of
 * {@code @EventListener} beans (see
 * {@link com.firefly.lumen.domain.event.LoanApplicationEventRecorder}) react to it
 * asynchronously and independently.
 *
 * @param loanApplicationId the server-assigned application id
 * @param applicantName     the primary applicant's display name
 * @param amount            the requested principal, in minor units
 */
public record LoanApplicationRegisteredEvent(UUID loanApplicationId, String applicantName, long amount) {

    /** Canonical EDA event type used as both the topic and the routing/event-type key. */
    public static final String EVENT_TYPE = "loanApplication.registered";
}
:::

No hay nada específico de Firefly en este fichero, y eso es deliberado. Un evento de
dominio forma parte de tu *dominio*, no de ningún broker. No contiene cabeceras de Kafka, ni
callback de confirmación, ni tipos de transporte: solo los hechos. El framework lo envuelve
en metadatos de transporte en el borde, lo que mantiene tu evento lo bastante limpio como para hacerle pruebas unitarias
con un simple `assertEquals`.

Fíjate en la constante `EVENT_TYPE`. El productor la pasará como *nombre de topic lógico*
cuando publique: una cadena estable sobre la que puedes enrutar. Guarda esa idea; la
regla de coincidencia entre productor y consumidor es la parte sutil de este capítulo, y
la constante por sí sola no cuenta toda la historia.

!!! note "Termino clave — evento de dominio"
    Un **evento de dominio** es un registro inmutable de algo que ya ha ocurrido
    en el dominio de negocio, nombrado en pasado (`LoanApplicationRegistered`,
    `OfferAccepted`). Lo publica el componente que provocó el cambio y
    lo consume cualquiera que necesite reaccionar. Como es un hecho, no una petición, el
    productor nunca espera una respuesta y nunca se entera de quién lo consumió, que es
    exactamente lo que permite que los consumidores entren y salgan libremente.

## El productor publica a través de EventPublisher

Ahora el productor. El manejador de registro del capítulo 10 ya llama al core para
crear la solicitud; la incorporación de EDA es un paso al final de la cadena. Después de
que la llamada al core tiene éxito y produce un id, el manejador publica el evento, y solo
entonces retorna el id. Aquí está el manejador completo, con la publicación en la ruta de éxito:

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/handler/RegisterLoanApplicationHandler.java | Listado 11.2 — el manejador escribe y luego anuncia
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

Lee con atención la forma reactiva, porque el orden es una garantía, no una
casualidad. `createLoanApplication(...)` retorna un `Mono<UUID>`; `flatMap` encadena la
publicación *después* de él y solo en caso de éxito: si la llamada al core falla con `onError`, la
cadena se corta y nunca se publica nada. `publishRegistered` construye el
evento y se lo entrega a `EventPublisher.publish(event, eventType)`, que retorna
`Mono<Void>`. Después, `.thenReturn(id)` devuelve el id como resultado del manejador,
de modo que la publicación queda secuenciada *dentro* de la respuesta: el manejador no completa hasta
que el evento ha sido aceptado por el transporte.

`EventPublisher` es la costura agnóstica del transporte del framework: una única interfaz
cuyo `publish` invocas de la misma manera sea lo que sea lo que esté detrás. Lo inyectas
como cualquier bean; la autoconfiguración proporciona la implementación que coincide con tu
transporte configurado. En Lumen esa implementación entrega dentro de la JVM, pero el código del manejador
no cambiaría ni un carácter para ejecutarse sobre Kafka.

!!! note "Termino clave — EventEnvelope"
    Tu record es el *payload*. Antes de cruzar un transporte, el entorno de ejecución lo envuelve
    en un `EventEnvelope`: un record del framework que añade los metadatos de enrutamiento que un broker
    necesita: el `destination` (topic), el `eventType`, un `transactionId` para
    correlación, un mapa de `headers`, un `timestamp` y el tipo de transporte del
    publicador/consumidor. En el lado de consumo, el envelope también puede llevar un callback de confirmación.
    Rara vez lo tocas directamente; `publish(payload, eventType)` lo construye por ti, y
    tu método `@EventListener` recibe el payload ya desenvuelto. El envelope es la razón por la que
    el mismo record limpio puede viajar sin cambios sobre cuatro brokers diferentes.

### Publicar de forma declarativa, con una anotación

Llamar a `EventPublisher.publish` a mano, como hace el manejador, es la forma explícita,
y la más clara para aprender, porque la publicación está ahí mismo en la cadena reactiva.
Firefly ofrece además una forma *declarativa* para el caso común «publica el
resultado de este método». Anotas el método y el framework publica su
valor de retorno por ti, después de que completa con éxito:

```java
// Illustrative: publish a method's result automatically, no EventPublisher injection.
@PublishResult(
        destination = "loanApplication.registered",
        eventType = "LoanApplicationRegisteredEvent",
        publisherType = PublisherType.APPLICATION_EVENT)
public Mono<LoanApplicationRegisteredEvent> register(RegisterLoanApplicationCommand cmd) {
    return client.createLoanApplication(cmd.getApplicantName(), cmd.getAmount())
        .map(id -> new LoanApplicationRegisteredEvent(id, cmd.getApplicantName(), cmd.getAmount()));
}
```

También existe `@EventPublisher` para publicar un *argumento* elegido en lugar del
valor de retorno. Ambos son azúcar guiado por aspectos sobre la misma costura `EventPublisher`: la
llamada explícita y la anotación producen el mismo envelope sobre el mismo
transporte. Lumen usa la llamada explícita para que la publicación sea visible en la porción que
acabas de leer; recurre a las anotaciones cuando la publicación sea puramente «emite lo que acabo de
producir» y prefieras no hilvanar `EventPublisher` por todo el cuerpo del método.

## El consumidor es un bean @EventListener

Un consumidor es cualquier bean de Spring con un método anotado con `@EventListener`. El framework
lo descubre al arrancar, lo registra en el entorno de ejecución EDA e lo invoca siempre que se
entrega un evento coincidente. El consumidor de Lumen anota cada evento que ve en una
lista segura para hilos: lo bastante pequeña como para comprobarla en un test, lo bastante real como para demostrar el
cableado:

::: listing domain-lending-loan-origination/src/main/java/com/firefly/lumen/domain/event/LoanApplicationEventRecorder.java | Listado 11.3 — el consumidor: un bean @EventListener
@Component
public class LoanApplicationEventRecorder {

    private final List<LoanApplicationRegisteredEvent> received = new CopyOnWriteArrayList<>();

    @EventListener(
            destinations = "LoanApplicationRegisteredEvent",
            eventTypes = "LoanApplicationRegisteredEvent",
            consumerType = PublisherType.APPLICATION_EVENT)
    public void on(LoanApplicationRegisteredEvent event) {
        received.add(event);
    }

    /** Returns an immutable snapshot of the events received so far. */
    public List<LoanApplicationRegisteredEvent> received() {
        return List.copyOf(received);
    }
}
:::

La firma del método lleva el contrato: `on(LoanApplicationRegisteredEvent event)`
toma el payload ya desenvuelto, así que trabajas con tu propio tipo, no con un envelope. La
anotación `@EventListener` indica *qué* eventos quiere este método. `consumerType =
PublisherType.APPLICATION_EVENT` lo enlaza al transporte dentro de la JVM —el
`ApplicationEventPublisher` de Spring—, que es la razón por la que el ejemplo no necesita broker. `destinations`
y `eventTypes` son los filtros de enrutamiento.

Y aquí está el detalle que sorprende a todo el mundo. Mira `eventTypes`: es
`"LoanApplicationRegisteredEvent"` —el **nombre simple de la clase** del payload— no la
cadena lógica `"loanApplication.registered"` que el productor pasa a `publish`. Eso
no es una errata en el ejemplo; es como hace coincidir el entorno de ejecución EDA. Lee la advertencia
antes de escribir tu primer oyente.

!!! warning "eventTypes es el nombre simple de la clase del payload, no una cadena lógica con puntos"
    El entorno de ejecución EDA compara los `eventTypes` de un oyente con el **nombre simple de la clase
    del payload publicado** —aquí `LoanApplicationRegisteredEvent`—. *No* lo compara
    con la cadena `eventType` que entregas a `publish(...)` (el `"loanApplication.registered"`
    del productor). Así que un oyente que ponga `eventTypes =
    "loanApplication.registered"` para «coincidir con el productor» no recibirá nada en silencio:
    el entorno de ejecución lo está comparando con `LoanApplicationRegisteredEvent` y nunca
    encuentra coincidencia. El arreglo es poner `eventTypes` al nombre de la clase. Este es el
    error de cableado de EDA más común con diferencia, y falla *silenciosamente* —el productor tiene éxito,
    el consumidor sencillamente nunca se dispara—, así que graba la regla en tu memoria ahora: **`eventTypes` es
    el nombre de la clase.**

!!! note "Termino clave — PublisherType"
    `PublisherType` selecciona el transporte para un productor (`publisherType`) o consumidor
    (`consumerType`). Los valores son `APPLICATION_EVENT` (dentro de la JVM, sobre el bus de eventos
    de Spring —el predeterminado que usa Lumen—), `KAFKA`, `RABBITMQ`, `POSTGRES`, `NOOP` (descarta
    eventos, útil en tests) y `AUTO` (deja que el framework elija según lo que haya en el
    classpath). Cambiar Lumen de dentro de la JVM a Kafka es un cambio de `PublisherType` más una
    dependencia y unas pocas propiedades `firefly.eda.*`: el código `@EventPublisher`/
    `@EventListener` no se toca. Esa portabilidad es toda la razón para publicar
    a través del framework en lugar de directamente con un cliente de broker.

!!! spring "Equivalente en Spring"
    El `@EventListener` de Firefly *no* es deliberadamente el
    `org.springframework.context.event.EventListener` de Spring: es
    `org.fireflyframework.eda.annotation.EventListener`, un superconjunto consciente del transporte.
    La versión de Spring solo entrega siempre en proceso; la de Firefly ejecuta el mismo oyente
    sobre Kafka, RabbitMQ o Postgres cambiando `consumerType`. Cuando lo enlazas a
    `APPLICATION_EVENT`, *usa* por debajo el `ApplicationEventPublisher` de Spring, de modo que
    obtienes gratis la mecánica dentro de la JVM de Spring, más una única anotación que escala
    a un broker real sin reescritura. Comprueba el import cuando copies un oyente; las
    dos anotaciones parecen idénticas y se comportan de forma muy distinta.

## Ejecútalo

El test del oyente EDA demuestra el cableado de extremo a extremo sin un broker. Acciona
el `EventListenerProcessor` del framework directamente —el mismo componente al que todo transporte
canaliza los mensajes entregados—, de modo que el descubrimiento de la anotación y la invocación del método
son enteramente reales; solo se omite el salto de red externo:

::: listing domain-lending-loan-origination/src/test/java/com/firefly/lumen/domain/saga/LoanApplicationEventListenerTest.java | Listado 11.4 — demostrar que el oyente se dispara, sin broker
    @Test
    void annotatedListener_receivesDispatchedEvent() {
        var event = new LoanApplicationRegisteredEvent(UUID.randomUUID(), "Ada Lovelace", 250_000L);

        // The processor routes the payload to every @EventListener whose type matches.
        StepVerifier.create(processor.processEvent(event, Map.of()))
                .verifyComplete();

        assertThat(recorder.received()).containsExactly(event);
    }
:::

El test construye un evento real, pide al procesador que lo despache y comprueba dos
cosas: que el despacho completa limpiamente (`verifyComplete()`) y que el grabador
recibió de verdad exactamente ese evento (`containsExactly(event)`). La segunda afirmación
es la que importa: demuestra que el entorno de ejecución hizo coincidir el nombre de clase del payload
`LoanApplicationRegisteredEvent` con los `eventTypes` del oyente e invocó `on(...)`.
Productor y consumidor no comparten ninguna referencia el uno al otro; el único enlace es el tipo de
evento, resuelto por el framework.

Ejecuta los tests del módulo de dominio desde la raíz del ejemplo:

```text
mvn -q -pl domain-lending-loan-origination test
```

Deberías ver pasar los seis:

```text
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

El test del oyente EDA es uno de esos seis, junto con el manejador de registro y los
tests de saga de capítulos anteriores. Seis tests en verde significan que el productor publica, el
entorno de ejecución enruta por nombre de clase y el `@EventListener` se dispara: el bucle desacoplado
completo, verificado en proceso en menos de un segundo.

!!! tip "Punto de control"
    Abre `LoanApplicationEventRecorder` y cambia `eventTypes` de
    `"LoanApplicationRegisteredEvent"` a `"loanApplication.registered"` —la
    cadena lógica del productor—. Vuelve a ejecutar `-Dtest=LoanApplicationEventListenerTest`. Ahora
    falla: el grabador no recibe nada, porque el entorno de ejecución hizo coincidir el *nombre de clase*
    del payload y no encontró ningún oyente registrado bajo él. Lee el fallo de la afirmación
    —`containsExactly` reporta una lista vacía— y después restaura el nombre de clase y míralo
    pasar. Acabas de reproducir a propósito el error de EDA más común, que es la forma más segura
    de no llevarlo nunca a producción.

## El puente CQRS/EDA

Has visto la ruta explícita: un manejador CQRS inyecta `EventPublisher` y publica
en su cadena reactiva. Firefly cierra el círculo entre ambas capacidades con dos
puentes declarativos, de modo que un manejador de comandos puede emitir un evento de dominio —y un lado de
consulta puede reaccionar a uno— sin cableado manual.

En el lado de *escritura*, `@PublishDomainEvent` permite a un manejador CQRS anunciar su resultado como
un evento de dominio automáticamente, con la misma forma que el Listado 11.2 escribió a mano:

```java
// Illustrative: a CQRS handler that publishes its result as a domain event.
@CommandHandlerComponent
@PublishDomainEvent(
        destination = "loanApplication.registered",
        eventType = "LoanApplicationRegisteredEvent")
public class RegisterLoanApplicationHandler
        extends CommandHandler<RegisterLoanApplicationCommand, UUID> {
    // doHandle(...) returns the id; the framework publishes the event after success.
}
```

En el lado de *lectura*, `@InvalidateCacheOn` ata una consulta cacheada a los eventos que la
dejan obsoleta, de modo que un evento de registro invalida automáticamente la lista de solicitudes cacheada:
el modelo de lectura se autorrepara en lugar de servir una instantánea que el lado de escritura ya
dejó obsoleta:

```java
// Illustrative: evict a query's cache when matching events arrive.
@QueryHandlerComponent
@InvalidateCacheOn(eventTypes = "LoanApplicationRegisteredEvent")
public class GetApplicationStatusHandler
        extends QueryHandler<GetApplicationStatusQuery, ApplicationStatusView> {
    // cached results are invalidated whenever a LoanApplicationRegisteredEvent fires
}
```

Ambas anotaciones viven en el módulo CQRS y enrutan a través del mismo entorno de ejecución EDA que
acabas de ejercitar, y ambas se basan en `eventTypes` según la **misma regla del nombre simple de clase**,
de modo que la advertencia de arriba se aplica aquí literalmente. Lumen usa la llamada explícita a `EventPublisher`
en lugar de `@PublishDomainEvent` para que la publicación sea visible en la porción; en un
servicio más grande, el puente declarativo elimina ese código repetitivo manteniendo exactamente el
mismo desacoplamiento productor/consumidor. Las dos capacidades —CQRS para la separación comando/consulta,
EDA para el anuncio— están diseñadas para encontrarse aquí: un comando cambia el estado,
publica un hecho y el lado de lectura se ajusta, todo sin que ninguna mitad nombre a la otra.

## Lo que has construido {.recap}

- Un **`LoanApplicationRegisteredEvent`**: un evento de dominio `record` inmutable,
  en pasado y libre de broker, que lleva justo los hechos que un consumidor necesita y un nombre lógico
  `EVENT_TYPE` estable.
- Un **productor** en `RegisterLoanApplicationHandler` que escribe en el core y luego
  publica el evento a través del `EventPublisher` agnóstico del transporte, secuenciado dentro de la
  cadena reactiva con `flatMap` para que se dispare solo en caso de éxito.
- Un **consumidor**, `LoanApplicationEventRecorder`, un bean `@EventListener` enlazado al
  transporte `PublisherType.APPLICATION_EVENT` dentro de la JVM, de modo que todo el bucle se ejecuta sin
  Kafka y sin Docker.
- La regla decisiva del entorno de ejecución: el entorno de ejecución EDA hace coincidir los `eventTypes` de un oyente por el
  **nombre simple de la clase** del payload (`LoanApplicationRegisteredEvent`), no por la cadena
  lógica con puntos con la que publica el productor —un desajuste falla *silenciosamente*—.
- Un **test de integración libre de broker** que despacha un evento real a través del
  `EventListenerProcessor` del framework y comprueba que el oyente lo recibió: uno de
  los seis tests que pasan en el módulo.

## Pruebalo tu mismo {.exercises}

1. **Añade un segundo oyente.** Crea un nuevo `@Component` con un método `@EventListener`
   sobre `LoanApplicationRegisteredEvent` (con el mismo `consumerType` y los mismos `eventTypes` que
   el grabador) que incremente un contador. Añade un test que compruebe que un evento despachado
   llega a *ambos* oyentes. Esta es la recompensa de EDA: el productor no
   cambió, y aun así un nuevo consumidor reacciona.
2. **Reproduce el error de fallo silencioso y luego demuéstralo.** Cambia los `eventTypes` del grabador
   a `LoanApplicationRegisteredEvent.EVENT_TYPE` (la cadena
   `"loanApplication.registered"`), ejecuta `-Dtest=LoanApplicationEventListenerTest`
   y confirma que falla con una lista `received()` vacía. Escribe un comentario de una frase
   explicando por qué y luego restaura el nombre de clase.
3. **Publica a través del manejador.** En `RegisterLoanApplicationHandlerTest`, registra un
   bean `LoanApplicationEventRecorder` real y comprueba que manejar un
   `RegisterLoanApplicationCommand` hace que el grabador reciba exactamente un evento
   con el id que el manejador retornó —demostrando que la publicación del Listado 11.2 está secuenciada
   correctamente—.
4. **Haz la publicación condicional.** Vuelve a leer el método explícito `publishRegistered` y
   luego esboza (sin necesidad de ejecutarlo) cómo publicarías solo cuando el importe supere un
   umbral. ¿Dónde va la guarda —en el manejador, o podría el atributo `condition` de `@PublishResult`
   expresarla declarativamente en su lugar?
5. **Traza un cambio de transporte.** Sin cambiar ningún código `@EventPublisher` ni
   `@EventListener`, enumera exactamente qué cambiarías para mover los eventos de Lumen
   a Kafka: qué valores de `PublisherType`, qué dependencia, qué propiedades `firefly.eda.*`.
   El hecho de que el *código* del oyente y del productor permanezca intacto es la
   razón de ser de la costura.

## Adonde ir ahora

El evento que has publicado aquí es una *notificación*: un hecho al que reaccionan otros componentes,
tras lo cual desaparece. El capítulo 12 da el siguiente paso: en lugar de almacenar el estado
actual y anunciar los cambios, ¿y si la secuencia de eventos *es* el estado? El **event
sourcing** trata el log de solo anexar de eventos de dominio como la fuente de verdad, y el
estado actual como un pliegue sobre ese log. El `LoanApplicationRegisteredEvent` que acabas de
construir es exactamente el tipo de evento que, persistido en lugar de descartado, se convierte en un
historial completo y reproducible de cada solicitud.
