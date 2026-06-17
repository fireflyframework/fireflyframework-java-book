El capítulo 10 despachó un comando a través del bus de CQRS y observó cómo un
manejador escribía en el sistema central de registro. Aquel manejador hizo su trabajo
y regresó. Pero en una plataforma real, *registrar una solicitud de préstamo* nunca es
el final de la historia: una comprobación de fraude quiere enterarse, un servicio de
notificaciones quiere saludar al solicitante, una canalización de analítica quiere
contabilizarla y un registro de auditoría quiere dejar constancia. El arreglo ingenuo
consiste en que el manejador llame a los cuatro. Hazlo y el manejador pasará a conocer
el fraude, las notificaciones, la analítica y la auditoría; añade un quinto consumidor
y volverás a editar el manejador. La escritura y todos los que se interesan por ella
quedan soldados entre sí.

La arquitectura dirigida por eventos rompe esa soldadura. El manejador hace una sola
cosa: escribe y luego *anuncia* que escribió, publicando un **evento de dominio**. No
sabe ni le importa quién está escuchando. Los consumidores se suscriben al anuncio y
reaccionan a su propio ritmo, de forma independiente, y los añades o los quitas sin
tocar el productor. Este capítulo construye ese desacoplamiento en la capa de dominio
de Lumen: un `LoanApplicationRegisteredEvent` publicado por el manejador de registro y
un `@EventListener` que deja constancia de cada uno que recibe, todo cableado a través
del runtime de EDA de Firefly, demostrado por un test, **sin Kafka y sin Docker**.

La capacidad es `firefly-eda`. Como toda capacidad de Firefly, es agnóstica respecto al
transporte: el mismo código `@EventPublisher`/`@EventListener` se ejecuta sobre un bus
en la propia JVM, Kafka, RabbitMQ o Postgres, elegido por configuración en lugar de
reescribiendo tu servicio. Lumen lo ejecuta en la propia JVM para que el ejemplo siga
sin dependencias, y por el camino conocerás el único detalle que hace tropezar a todo
el mundo la primera vez: cómo decide el runtime a qué listener pertenece un evento dado.

## El evento de dominio es un simple record

Un evento de dominio es un *hecho que ya ha ocurrido*, nombrado en pasado, que lleva
consigo los datos justos para que un consumidor actúe sin tener que devolver la llamada.
Es un objeto de valor inmutable; en Java, un `record`. El evento de Lumen dice "se
registró una solicitud de préstamo" y lleva el id asignado por el servidor, el nombre
del solicitante y el importe:

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

No hay nada específico de Firefly en este fichero, y es deliberado. Un evento de dominio
forma parte de tu *dominio*, no de ningún broker. No contiene cabeceras de Kafka, ni
callback de confirmación, ni tipos de transporte: solo los hechos. El framework lo
envuelve en metadatos de transporte en el borde, lo que mantiene tu evento lo bastante
limpio como para probarlo unitariamente con un simple `assertEquals`.

Fíjate en la constante `EVENT_TYPE`. El productor la pasará como *nombre lógico de
topic* cuando publique: una cadena estable sobre la que puedes enrutar. Quédate con esa
idea; la regla de coincidencia entre productor y consumidor es la parte sutil de este
capítulo, y la constante por sí sola no cuenta toda la historia.

!!! note "Término clave — evento de dominio"
    Un **evento de dominio** es un registro inmutable de algo que ya ha ocurrido en el
    dominio de negocio, nombrado en pasado (`LoanApplicationRegistered`,
    `OfferAccepted`). Lo publica el componente que provocó el cambio y lo consume
    cualquiera que necesite reaccionar. Como es un hecho, no una petición, el productor
    nunca espera respuesta y nunca se entera de quién lo consumió, que es exactamente lo
    que permite a los consumidores aparecer y desaparecer con total libertad.

## El productor publica a través de EventPublisher

Ahora el productor. El manejador de registro del capítulo 10 ya llama al core para
crear la solicitud; la incorporación de EDA es un paso al final de la cadena. Después de
que la llamada al core tiene éxito y produce un id, el manejador publica el evento, y
solo entonces devuelve el id. Aquí está el manejador completo, con la publicación en la
ruta de éxito:

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

Lee con atención la forma reactiva, porque el orden es una garantía, no un accidente.
`createLoanApplication(...)` devuelve un `Mono<UUID>`; `flatMap` encadena la publicación
*después* de él y solo si tiene éxito: si la llamada al core falla con `onError`, la
cadena se cortocircuita y nunca se publica nada. `publishRegistered` construye el evento
y se lo entrega a `EventPublisher.publish(event, eventType)`, que devuelve `Mono<Void>`.
El `.thenReturn(id)` produce entonces el id de vuelta como resultado del manejador, de
modo que la publicación queda secuenciada *dentro* de la respuesta: el manejador no se
completa hasta que el transporte ha aceptado el evento.

`EventPublisher` es la costura agnóstica al transporte del framework: una única interfaz
cuyo `publish` invocas de la misma manera sin importar qué haya detrás. La inyectas como
cualquier bean; la autoconfiguración proporciona la implementación que corresponde a tu
transporte configurado. En Lumen esa implementación entrega en la propia JVM, pero el
código del manejador no cambiaría ni un solo carácter para ejecutarse sobre Kafka.

!!! note "Término clave — EventEnvelope"
    Tu record es el *payload*. Antes de cruzar un transporte, el runtime lo envuelve en
    un `EventEnvelope`: un record del framework que añade los metadatos de enrutamiento
    que necesita un broker: el `destination` (topic), el `eventType`, un `transactionId`
    para la correlación, un mapa de `headers`, un `timestamp` y el tipo de transporte del
    publicador/consumidor. En el lado del consumo, el envelope también puede llevar un
    callback de confirmación. Rara vez lo tocas directamente; `publish(payload,
    eventType)` lo construye por ti, y tu método `@EventListener` recibe el payload
    desempaquetado. El envelope es la razón por la que el mismo record limpio puede
    viajar sin cambios sobre cuatro brokers distintos.

### Publicar de forma declarativa, con una anotación

Llamar a `EventPublisher.publish` a mano, como hace el manejador, es la forma explícita,
y la más clara para aprender, porque la publicación está justo ahí en la cadena
reactiva. Firefly ofrece también una forma *declarativa* para el caso común "publica el
resultado de este método". Anotas el método y el framework publica su valor de retorno
por ti, después de que se complete con éxito:

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

También existe `@EventPublisher` para publicar un *argumento* elegido en lugar del valor
de retorno. Ambos son azúcar dirigido por aspectos sobre la misma costura
`EventPublisher`: la llamada explícita y la anotación producen el envelope idéntico
sobre el transporte idéntico. Lumen usa la llamada explícita para que la publicación sea
visible en la porción que acabas de leer; recurre a las anotaciones cuando la
publicación sea puramente "emite lo que acabo de producir" y prefieras no hilvanar
`EventPublisher` a través del cuerpo del método.

## El consumidor es un bean @EventListener

Un consumidor es cualquier bean de Spring con un método anotado con `@EventListener`. El
framework lo descubre al arrancar, lo registra en el runtime de EDA y lo invoca cada vez
que se entrega un evento coincidente. El consumidor de Lumen deja constancia de cada
evento que ve en una lista segura para hilos: lo bastante pequeña como para hacerle
aserciones en un test, lo bastante real como para demostrar el cableado:

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

La firma del método porta el contrato: `on(LoanApplicationRegisteredEvent event)` toma
el payload desempaquetado, de modo que trabajas con tu propio tipo, no con un envelope.
La anotación `@EventListener` indica *qué* eventos quiere este método. `consumerType =
PublisherType.APPLICATION_EVENT` lo vincula al transporte en la propia JVM, el
`ApplicationEventPublisher` de Spring, que es por lo que el ejemplo no necesita ningún
broker. `destinations` y `eventTypes` son los filtros de enrutamiento.

Y aquí está el detalle que sorprende a todo el mundo. Fíjate en `eventTypes`: es
`"LoanApplicationRegisteredEvent"`, el **nombre simple de clase** del payload, no la
cadena lógica `"loanApplication.registered"` que el productor pasa a `publish`. No es
una errata en el ejemplo; es así como hace la coincidencia el runtime de EDA. Lee la
advertencia antes de escribir tu primer listener.

!!! warning "eventTypes es el nombre simple de clase del payload, no una cadena lógica con puntos"
    El runtime de EDA hace coincidir el `eventTypes` de un listener con el **nombre
    simple de clase del payload publicado**, aquí `LoanApplicationRegisteredEvent`. *No*
    lo hace coincidir con la cadena `eventType` que entregas a `publish(...)` (la
    `"loanApplication.registered"` del productor). Así que un listener que ponga
    `eventTypes = "loanApplication.registered"` para "coincidir con el productor" no
    recibirá nada en silencio: el runtime lo está comparando con
    `LoanApplicationRegisteredEvent` y nunca encuentra coincidencia. El arreglo consiste
    en poner `eventTypes` al nombre de la clase. Este es el error de cableado de EDA más
    común con diferencia, y falla *silenciosamente* (el productor tiene éxito, el
    consumidor simplemente nunca se dispara), así que graba la regla en la memoria ahora:
    **`eventTypes` es el nombre de la clase.**

!!! note "Término clave — PublisherType"
    `PublisherType` selecciona el transporte para un productor (`publisherType`) o un
    consumidor (`consumerType`). Los valores son `APPLICATION_EVENT` (en la propia JVM,
    sobre el bus de eventos de Spring, el predeterminado que usa Lumen), `KAFKA`,
    `RABBITMQ`, `POSTGRES`, `NOOP` (descarta los eventos, útil en tests) y `AUTO` (deja
    que el framework elija según lo que haya en el classpath). Cambiar Lumen de la propia
    JVM a Kafka es un cambio de `PublisherType` más una dependencia y unas cuantas
    propiedades `firefly.eda.*`; el código `@EventPublisher`/`@EventListener` queda
    intacto. Esa portabilidad es la razón entera para publicar a través del framework en
    lugar de un cliente de broker directamente.

!!! spring "Equivalente en Spring"
    El `@EventListener` de Firefly *no* es, deliberadamente, el
    `org.springframework.context.event.EventListener` de Spring: es
    `org.fireflyframework.eda.annotation.EventListener`, un superconjunto consciente del
    transporte. La versión de Spring solo entrega siempre dentro del proceso; la de
    Firefly ejecuta el mismo listener sobre Kafka, RabbitMQ o Postgres cambiando
    `consumerType`. Cuando lo vinculas a `APPLICATION_EVENT`, *usa* por debajo el
    `ApplicationEventPublisher` de Spring, de modo que obtienes la mecánica en la propia
    JVM de Spring gratis, más una única anotación que escala hasta un broker real sin
    reescritura. Comprueba el import cuando copies un listener; las dos anotaciones
    parecen idénticas y se comportan de forma muy distinta.

## Ejecútalo

El test del listener de EDA demuestra el cableado de extremo a extremo sin un broker.
Conduce directamente el `EventListenerProcessor` del framework, el mismo componente al
que todo transporte canaliza los mensajes entregados, de modo que el descubrimiento de
anotaciones y la invocación del método son enteramente reales; solo se elide el salto de
red externo:

::: listing domain-lending-loan-origination/src/test/java/com/firefly/lumen/domain/saga/LoanApplicationEventListenerTest.java | Listado 11.4 — demostrar que el listener se dispara, sin broker
    @Test
    void annotatedListener_receivesDispatchedEvent() {
        var event = new LoanApplicationRegisteredEvent(UUID.randomUUID(), "Ada Lovelace", 250_000L);

        // The processor routes the payload to every @EventListener whose type matches.
        StepVerifier.create(processor.processEvent(event, Map.of()))
                .verifyComplete();

        assertThat(recorder.received()).containsExactly(event);
    }
:::

El test construye un evento real, le pide al procesador que lo despache y hace dos
aserciones: que el despacho se completa limpiamente (`verifyComplete()`) y que el
recorder recibió de hecho exactamente ese evento (`containsExactly(event)`). La segunda
aserción es la que importa: demuestra que el runtime hizo coincidir el nombre de clase
del payload `LoanApplicationRegisteredEvent` con el `eventTypes` del listener e invocó
`on(...)`. Productor y consumidor no comparten ninguna referencia entre sí; el único
vínculo es el tipo de evento, resuelto por el framework.

Ejecuta los tests del módulo de dominio desde la raíz del ejemplo:

```text
mvn -q -pl domain-lending-loan-origination test
```

Deberías ver pasar los seis:

```text
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

El test del listener de EDA es uno de esos seis, junto con el manejador de registro y
los tests de la saga de capítulos anteriores. Seis tests en verde significa que el
productor publica, el runtime enruta por nombre de clase y el `@EventListener` se
dispara: el bucle desacoplado completo, verificado dentro del proceso en menos de un
segundo.

!!! tip "Punto de control"
    Abre `LoanApplicationEventRecorder` y cambia `eventTypes` de
    `"LoanApplicationRegisteredEvent"` a `"loanApplication.registered"`, la cadena lógica
    del productor. Vuelve a ejecutar `-Dtest=LoanApplicationEventListenerTest`. Ahora
    falla: el recorder no recibe nada, porque el runtime hizo coincidir el *nombre de
    clase* del payload y no encontró ningún listener registrado bajo él. Lee el fallo de
    la aserción (`containsExactly` informa de una lista vacía), luego restaura el nombre
    de clase y observa cómo pasa. Acabas de reproducir el error de EDA más común a
    propósito, que es la forma más segura de no enviarlo nunca a producción.

## El puente CQRS/EDA

Has visto la ruta explícita: un manejador de CQRS inyecta `EventPublisher` y publica en
su cadena reactiva. Firefly cierra el bucle entre ambas capacidades con dos puentes
declarativos, de modo que un manejador de comandos puede emitir un evento de dominio (y
un lado de consulta puede reaccionar a uno) sin cableado a mano.

En el lado de la *escritura*, `@PublishDomainEvent` permite que un manejador de CQRS
anuncie su resultado como un evento de dominio automáticamente, con la misma forma que
el Listado 11.2 escribió a mano:

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

En el lado de la *lectura*, `@InvalidateCacheOn` ata una consulta cacheada a los eventos
que la dejan obsoleta, de modo que un evento de registro desaloja automáticamente la
lista de solicitudes cacheada: el modelo de lectura se autorrepara en lugar de servir
una instantánea que el lado de la escritura ya dejó obsoleta:

```java
// Illustrative: evict a query's cache when matching events arrive.
@QueryHandlerComponent
@InvalidateCacheOn(eventTypes = "LoanApplicationRegisteredEvent")
public class GetApplicationStatusHandler
        extends QueryHandler<GetApplicationStatusQuery, ApplicationStatusView> {
    // cached results are invalidated whenever a LoanApplicationRegisteredEvent fires
}
```

Ambas anotaciones viven en el módulo de CQRS y se enrutan a través del mismo runtime de
EDA que acabas de ejercitar, y ambas se basan en `eventTypes` según la **misma regla del
nombre simple de clase**, de modo que la advertencia anterior se aplica aquí
literalmente. Lumen usa la llamada explícita a `EventPublisher` en lugar de
`@PublishDomainEvent` para que la publicación sea visible en la porción; en un servicio
más grande, el puente declarativo elimina ese código repetitivo manteniendo exactamente
el mismo desacoplamiento productor/consumidor. Las dos capacidades (CQRS para la
separación comando/consulta, EDA para el anuncio) están diseñadas para encontrarse aquí:
un comando cambia el estado, publica un hecho y el lado de lectura se ajusta, todo ello
sin que ninguna de las dos mitades nombre a la otra.

## Lo que has construido {.recap}

- Un **`LoanApplicationRegisteredEvent`**: un evento de dominio `record` inmutable, en
  pasado y libre de broker, que lleva justo los hechos que un consumidor necesita y un
  nombre lógico `EVENT_TYPE` estable.
- Un **productor** en `RegisterLoanApplicationHandler` que escribe en el core y luego
  publica el evento a través del `EventPublisher` agnóstico al transporte, secuenciado
  dentro de la cadena reactiva con `flatMap` para que se dispare solo si hay éxito.
- Un **consumidor**, `LoanApplicationEventRecorder`, un bean `@EventListener` vinculado
  al transporte en la propia JVM `PublisherType.APPLICATION_EVENT`, de modo que el bucle
  entero se ejecuta sin Kafka y sin Docker.
- La regla decisiva del runtime: el runtime de EDA hace coincidir el `eventTypes` de un
  listener por el **nombre simple de clase** del payload
  (`LoanApplicationRegisteredEvent`), no por la cadena lógica con puntos con la que el
  productor publica; un desajuste falla *silenciosamente*.
- Un **test de integración sin broker** que despacha un evento real a través del
  `EventListenerProcessor` del framework y afirma que el listener lo recibió: uno de los
  seis tests que pasan en el módulo.

## Pruébalo tú mismo {.exercises}

1. **Añade un segundo listener.** Crea un nuevo `@Component` con un método
   `@EventListener` sobre `LoanApplicationRegisteredEvent` (con el mismo `consumerType` y
   `eventTypes` que el recorder) que incremente un contador. Añade un test que afirme que
   un único evento despachado llega a *ambos* listeners. Esta es la recompensa de EDA: el
   productor no cambió y, sin embargo, un nuevo consumidor reacciona.
2. **Reproduce el error de fallo silencioso y luego demuéstralo.** Cambia el `eventTypes`
   del recorder a `LoanApplicationRegisteredEvent.EVENT_TYPE` (la cadena
   `"loanApplication.registered"`), ejecuta `-Dtest=LoanApplicationEventListenerTest` y
   confirma que falla con una lista `received()` vacía. Escribe un comentario de una
   frase explicando por qué y luego restaura el nombre de clase.
3. **Publica a través del manejador.** En `RegisterLoanApplicationHandlerTest`, registra
   un bean `LoanApplicationEventRecorder` real y afirma que manejar un
   `RegisterLoanApplicationCommand` hace que el recorder reciba exactamente un evento con
   el id que el manejador devolvió, demostrando que la publicación del Listado 11.2 está
   secuenciada correctamente.
4. **Haz que la publicación sea condicional.** Vuelve a leer el método explícito
   `publishRegistered` y luego esboza (sin necesidad de ejecutarlo) cómo publicarías solo
   cuando el importe supere un umbral. ¿Dónde va la guarda, en el manejador, o podría
   expresarla declarativamente en su lugar el atributo `condition` de `@PublishResult`?
5. **Traza un cambio de transporte.** Sin cambiar ningún código `@EventPublisher` ni
   `@EventListener`, enumera exactamente qué cambiarías para mover los eventos de Lumen a
   Kafka: qué valores de `PublisherType`, qué dependencia, qué propiedades
   `firefly.eda.*`. El hecho de que el *código* del listener y del productor quede
   intacto es el sentido de la costura.

## Adónde ir ahora

El evento que has publicado aquí es una *notificación*: un hecho al que otros
componentes reaccionan, tras lo cual desaparece. El capítulo 12 da el siguiente paso: en
lugar de almacenar el estado actual y anunciar los cambios, ¿y si la secuencia de
eventos *es* el estado? El **event sourcing** trata el log de eventos de dominio de solo
anexión como la fuente de la verdad, y el estado actual como un pliegue sobre ese log. El
`LoanApplicationRegisteredEvent` que acabas de construir es exactamente la clase de
evento que, persistido en lugar de descartado, se convierte en un historial completo y
reproducible de cada solicitud.
