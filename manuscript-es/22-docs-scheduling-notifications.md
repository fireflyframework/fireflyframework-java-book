Un servicio de originación de préstamos realiza la mayor parte de su trabajo dentro
de su propia frontera: validar, puntuar, decidir, persistir. Pero los momentos que
un cliente *siente* de verdad ocurren en los bordes, donde el servicio se asoma al
mundo: un acuerdo en PDF que firmar, un correo de "tu préstamo ha sido aprobado", un
trabajo nocturno que caduca ofertas obsoletas, un webhook desde la pasarela de pagos,
una llamada de retorno al sistema de un socio. Cada uno de ellos es una pequeña
integración con sus propios modos de fallo, y una flota que se artesana cada uno
acaba con una docena de bucles de reintento, esquemas de firma y motores de
plantillas sutilmente distintos: exactamente el impuesto empresarial que nombró el
Capítulo 1.

Este capítulo es un recorrido por cuatro capacidades de Firefly que gestionan esos
bordes de forma consistente: **documentos** (renderizar un PDF que puedas firmar),
**planificación** (trabajo en segundo plano duradero y recuperable), **notificaciones**
(correo, SMS y push tras puertos de proveedor) y **webhooks y callbacks** (ingesta
entrante y entrega saliente firmada). El objetivo no es convertirte en experto en
ninguna de ellas (cada una tiene su propia referencia), sino darte la forma de cada
una, la frase que la diferencia de la alternativa obvia y dónde se enchufa en el
Lumen Lending que has ido construyendo.

!!! warning "Sinceridad sobre Lumen Lending"
    Ninguna de estas cuatro capacidades aparece en el reactor de acompañamiento. La
    porción recortada de originación se detiene en la aceptación de la oferta, así que
    **no hay código de documentos, planificación, notificaciones ni webhooks en el
    ejemplo, ni una prueba de acompañamiento para este capítulo.** Cada bloque de
    código de abajo es *ilustrativo*: un boceto fiel de cómo se usa la capacidad, no
    una porción textual del reactor. Donde una capacidad conecta con algo que el
    ejemplo *sí* construye (los eventos del Capítulo 11, el flujo de firma electrónica
    del Apéndice C), el texto lo indica explícitamente.

## Documentos — renderizar un PDF que puedas firmar

Cuando se acepta una oferta, el acuerdo de préstamo debe convertirse en un documento
real: generado a partir de la solicitud y la oferta aceptada, presentado para la firma
y almacenado como evidencia duradera. Firefly divide eso en dos responsabilidades.
*Generar* el documento es tarea de `TemplateRenderUtil` en `fireflyframework-utils`;
*almacenarlo y firmarlo* es tarea de los puertos ECM del Apéndice C. Esta sección
cubre la primera mitad: convertir datos en un PDF.

`TemplateRenderUtil` es una tubería de FreeMarker a XHTML a PDF: redactas el acuerdo
como una plantilla FreeMarker (`.ftl`) que produce XHTML bien formado, le proporcionas
un modelo de datos y renderiza un PDF listo para imprimir con soporte de marcas de
agua, cifrado, metadatos y caché de plantillas. La idea diferenciadora es el renderizado
en *dos etapas*: FreeMarker produce primero XHTML, que un motor de PDF pagina después,
de modo que el mismo lenguaje de plantillas y el mismo modelo de datos que impulsan tus
correos de notificación impulsan también tus documentos legales.

```java
// Illustrative — not in the companion reactor.
byte[] agreement = templateRenderUtil.renderPdf(
        "loan-agreement.ftl",
        Map.of(
            "application", application,     // the approved LoanApplication
            "offer",       acceptedOffer,   // the offer the customer accepted
            "generatedAt", LocalDate.now()));
```

La plantilla en sí es FreeMarker corriente sobre XHTML: los términos del préstamo
interpolados en el cuerpo de un documento con estilo:

```html
<!-- loan-agreement.ftl (illustrative) -->
<h1>Personal Loan Agreement</h1>
<p>Borrower: ${application.applicantName}</p>
<p>Principal: ${offer.amount} ${offer.currency}</p>
<p>Term: ${offer.termMonths} months at ${offer.apr}% APR</p>
```

Esos bytes de `agreement` son exactamente lo que consume el flujo de firma electrónica
del Apéndice C: el puerto de documentos almacena el archivo renderizado, los puertos de
firma electrónica lo envuelven en un sobre de firma y la prueba sellada vuelve para
almacenarse junto a él. La generación y la firma son deliberadamente preocupaciones
separadas: puedes renderizar un acuerdo sin firmarlo, y a los puertos de firma no les
importa cómo se produjeron los bytes.

!!! note "Término clave — `TemplateRenderUtil`"
    Una utilidad de renderizado de documentos en `fireflyframework-utils` que compila
    una plantilla FreeMarker a XHTML y luego a PDF. *No* forma parte de los puertos de
    documentos ECM; produce los bytes que esos puertos almacenan y firman. Como comparte
    FreeMarker con los canales de notificación de más abajo, un servicio tiene **una**
    historia de plantillas tanto para los documentos legales como para los mensajes al
    cliente.

!!! spring "Equivalente en Spring"
    Spring puro no tiene opinión aquí: elegirías una biblioteca de plantillas
    (FreeMarker, Thymeleaf) y un motor de PDF (OpenPDF, Flying Saucer) y los conectarías
    tú mismo, de forma distinta en cada servicio. La contribución de Firefly es la
    tubería preensamblada y el motor de plantillas compartido, de modo que la generación
    de documentos tiene el mismo aspecto en toda la flota.

## Planificación — trabajo en segundo plano duradero y recuperable

La originación tiene trabajo que no se dispara por una petición: las ofertas caducan
tras una ventana, un lote nocturno vuelve a puntuar las solicitudes cuyos datos de
buró cambiaron, un recordatorio sale tres días antes de una fecha límite de firma. El
reflejo es el `@Scheduled` de Spring: un método que se dispara según un cron. Eso vale
para un latido, pero tiene un filo afilado para el trabajo de *negocio*: `@Scheduled`
es dispara-y-olvida y local al nodo. Si el proceso muere a mitad de ejecución, el
trabajo simplemente se pierde; si ejecutas tres réplicas, el trabajo se dispara tres
veces.

La respuesta de Firefly es hacer el trabajo planificado *duradero* enrutándolo a través
de los mismos motores de orquestación que ya usas para los flujos en primer plano.
`@ScheduledSaga` dispara una saga (Capítulo 18) según una planificación, y
`@ScheduledWorkflow` dispara un workflow, de modo que una ejecución planificada obtiene
persistencia, recuperación a nivel de paso y compensación, no solo un temporizador. La
frase diferenciadora: un método `@Scheduled` corriente *se ejecuta* según una
planificación, mientras que un `@ScheduledSaga` *inicia una transacción recuperable y
de vuelo único* según una planificación, sobreviviendo a los reinicios y coordinándose
entre réplicas.

```java
// Illustrative — not in the companion reactor.
@ScheduledSaga(
        cron = "0 0 2 * * *",            // every day at 02:00
        name = "expire-stale-offers")
public class ExpireStaleOffersSaga {

    @SagaStep(id = "find")
    public Flux<OfferId> findExpired() {
        return offers.findExpiredBefore(Instant.now());
    }

    @SagaStep(id = "expire", dependsOn = "find", compensate = "reopen")
    public Mono<Void> expire(OfferId id) {
        return offers.markExpired(id);   // each step is journaled and recoverable
    }

    public Mono<Void> reopen(OfferId id) {
        return offers.reopen(id);        // compensation if a later step fails
    }
}
```

El `@Scheduled` más ligero sigue teniendo su lugar: pings de salud, calentamientos de
caché, agregaciones de métricas, cualquier cosa idempotente y desechable. Recurre a
`@ScheduledSaga` o `@ScheduledWorkflow` en cuanto el trabajo mute estado de negocio y te
pesará ejecutarlo dos veces o perderlo a medio camino.

!!! spring "Equivalente en Spring"
    `@ScheduledSaga` y `@ScheduledWorkflow` se construyen *sobre* la planificación de
    Spring (el disparador es el mismo mecanismo de cron) y añaden durabilidad a través
    de los runtimes de saga y workflow de Firefly. Usa el `@Scheduled` corriente de
    Spring para tics efímeros; usa las variantes de Firefly cuando "esto se disparó pero
    nunca terminó" o "esto se disparó dos veces" sería un incidente de verdad.

## Notificaciones — correo, SMS y push tras puertos

La misma aprobación que genera un acuerdo debería también llegar al cliente: "Has sido
aprobado", "Firma antes del viernes, por favor", "Tu préstamo está activo". Firefly
modela eso como un conjunto de **servicios de canal** (correo, SMS y push), cada uno
situado tras un puerto de proveedor, exactamente como el patrón hexagonal que viste
para los eventos y el ECM. Llamas a una API neutral respecto al canal; un adaptador
seleccionado por una propiedad `firefly.notifications.*` enruta el mensaje a SendGrid,
Twilio, Firebase u otro proveedor sin que tu código nombre a ninguno de ellos.

Dos detalles hacen que el módulo de notificaciones sea más que un fino envoltorio de un
SDK. Primero, comparte el motor FreeMarker con la generación de documentos, de modo que
un correo con plantilla se renderiza igual que el acuerdo de préstamo: una historia de
plantillas para documentos y mensajes por igual. Segundo, respeta las **preferencias de
canal por usuario**: un cliente que se dio de baja del SMS pero quiere correo recibe el
mensaje en el canal que eligió, decidido por el framework en lugar de por bifurcaciones
en tu manejador.

```java
// Illustrative — not in the companion reactor.
Mono<Void> sent = notificationService.send(
        Notification.builder()
            .recipient(applicant.id())          // resolves the user's channel prefs
            .template("loan-approved")          // FreeMarker template, shared engine
            .model(Map.of("name", applicant.fullName(),
                          "amount", offer.amount()))
            .build());
// The framework renders the template and dispatches over each channel
// the recipient has opted into — email, SMS, and/or push.
```

Un disparador natural para ese envío es un evento que ya publicas. El
`LoanApplicationRegisteredEvent` del Capítulo 11 (o un posterior `OfferAcceptedEvent`)
es exactamente el anuncio al que reacciona un consumidor de notificaciones: un
`@EventListener` recibe el hecho y llama a `notificationService.send(...)`, de modo que
la mensajería queda desacoplada de la escritura que la causó, igual que cualquier otro
consumidor.

!!! note "Término clave — servicio de canal"
    Un **servicio de canal** es el puerto para un medio de entrega: correo, SMS o push.
    Programas contra el `NotificationService` neutral respecto al canal (o contra un
    puerto de canal concreto) y un adaptador de proveedor hace el envío real. Cambiar de
    un proveedor de SMS a otro es un cambio de propiedad y un adaptador en el classpath,
    no una reescritura de SDK: la misma promesa de cambio-de-una-propiedad que los demás
    puertos de Firefly.

!!! spring "Equivalente en Spring"
    Spring ofrece `JavaMailSender` para el correo y nada unificado para SMS o push, así
    que cada servicio cría sus propios clientes de proveedor y su propia lógica de
    preferencias. El módulo de notificaciones de Firefly unifica los tres canales tras
    una API, comparte el motor de plantillas con la generación de documentos y
    centraliza las preferencias de canal, de modo que "notifica al cliente" es una sola
    llamada, no tres integraciones.

## Webhooks frente a callbacks — ingesta entrante frente a entrega saliente

El último borde es el más delicado, porque funciona en ambas direcciones, y las dos
direcciones *no* son simétricas. Un **webhook** es algo que el mundo exterior te envía
*a ti*: un procesador de pagos diciéndote que un desembolso se liquidó. Un **callback**
es algo que tú envías *al* mundo exterior: diciéndole a un socio corredor que una
solicitud llegó a una decisión. Firefly los trata como dos capacidades con
preocupaciones genuinamente distintas, y confundirlos es el error que el módulo está
diseñado para evitar.

### Webhooks entrantes — almacena rápido, luego procesa

Un webhook entrante llega desde un sistema que no controlas, a menudo con una política
agresiva de entrega y reintento: confirma en unos pocos cientos de milisegundos o
reintenta, a veces reenviando el mismo evento muchas veces. Por eso el manejo entrante
de Firefly es **en dos fases**. La fase uno valida la firma del proveedor y *almacena de
forma duradera* el payload en bruto, luego devuelve inmediatamente `2xx`: rápido, antes
de que se ejecute cualquier lógica de negocio. La fase dos procesa el evento almacenado
de forma asíncrona, con idempotencia basada en el id de evento del proveedor de modo que
un reenvío se reconoce y se descarta en lugar de aplicarse dos veces. La idea
diferenciadora es *almacena-luego-procesa*: nunca haces esperar a un llamante inestable
por tu lógica de negocio, y nunca pierdes un evento porque el procesamiento lanzó una
excepción.

```java
// Illustrative — not in the companion reactor.
@WebhookEndpoint(
        path = "/webhooks/payments",
        provider = "acme-payments")          // selects the signature scheme
public class PaymentWebhookHandler {

    @WebhookHandler(event = "disbursement.settled")
    public Mono<Void> onSettled(DisbursementSettled event) {
        // Phase 2: the framework already validated the signature, stored the raw
        // payload, returned 2xx, and de-duplicated on the provider's event id.
        return loans.markDisbursed(event.loanId());
    }
}
```

!!! warning "Un webhook entrante debe confirmar antes de procesar"
    Si haces el trabajo de negocio *antes* de devolver `2xx`, una llamada lenta a la
    base de datos o un error transitorio se convierte en que el proveedor reintenta, y
    ahora estás procesando el mismo evento dos, tres, cinco veces bajo carga. La división
    almacena-luego-procesa existe precisamente para que la confirmación sea rápida y el
    procesamiento sea idempotente. Deja que el framework almacene y confirme; haz tu
    trabajo en la fase dos.

### Callbacks salientes — firma, luego entrega de forma resiliente

Un callback saliente es la imagen especular. *Tú* eres ahora el llamante, así que la
carga recae en ti de demostrar autenticidad y de sobrevivir a que el socio esté caído.
Firefly firma el payload con **HMAC** para que el destinatario pueda verificar que vino
de ti y no fue manipulado, y lo entrega a través de un cliente protegido por un
**circuit-breaker** con reintentos, de modo que un socio que está agotando su tiempo de
espera dispara el breaker en lugar de ahogar tus hilos, y se recupera automáticamente
cuando vuelve. La frase diferenciadora: un callback saliente es una *entrega* firmada con
HMAC y con circuit-breaker, no solo un POST HTTP.

```java
// Illustrative — not in the companion reactor.
Mono<Void> delivered = callbackService.send(
        Callback.builder()
            .destination(partner.callbackUrl())
            .event("application.decisioned")
            .payload(Map.of("applicationId", id, "decision", "APPROVED"))
            .build());
// The framework HMAC-signs the body, adds the signature header, and delivers
// through a circuit-breaker-guarded client with retry and backoff.
```

Pon las dos lado a lado y la asimetría es la lección entera. Entrante, no confías en
nada y optimizas para *no perder* los eventos que no pediste: valida, almacena, confirma,
luego procesa de forma idempotente. Saliente, nadie confía en ti y optimizas por una
entrega *demostrable y resiliente*: firma, luego entrega a través de un breaker. La misma
familia de palabras, posturas opuestas.

!!! note "Término clave — webhook frente a callback"
    En el vocabulario de Firefly, un **webhook** es *entrante* (el mundo te llama) y un
    **callback** es *saliente* (tú llamas al mundo). Son capacidades separadas porque sus
    problemas son distintos: lo entrante trata de la confirmación rápida, la validación de
    firma y el reenvío idempotente; lo saliente trata de la firma HMAC y la entrega con
    circuit-breaker y reintentos. Si te encuentras escribiendo el mismo código de reintento
    en ambos lados, probablemente has colapsado dos problemas distintos en uno.

!!! spring "Equivalente en Spring"
    Spring puro te da `@RestController` para el endpoint entrante y `WebClient` para la
    llamada saliente, y deja la validación de firma, el almacenamiento duradero, la
    idempotencia, la firma HMAC y el circuit breaking enteramente en tus manos. Los módulos
    de webhook y callback de Firefly precablean esas preocupaciones para que cada servicio
    ingiera y emita integraciones de la misma manera: el contrato entrante de
    `2xx`-luego-procesa y la entrega saliente firmada-y-protegida-con-breaker, idénticos en
    toda la flota.

## Lo que has aprendido {.recap}

- Los **documentos** se renderizan con `TemplateRenderUtil` (una tubería de FreeMarker →
  XHTML → PDF en `fireflyframework-utils`) cuyos bytes alimentan el flujo de firma
  electrónica del Apéndice C. La generación y la firma son pasos deliberadamente separados.
- La **planificación** viene en dos intensidades: el `@Scheduled` corriente de Spring para
  tics efímeros, y `@ScheduledSaga` / `@ScheduledWorkflow` de Firefly para trabajo en
  segundo plano duradero, de vuelo único y recuperable que muta estado de negocio.
- Las **notificaciones** son servicios de canal (correo, SMS, push) tras puertos de
  proveedor, que comparten el motor FreeMarker con la generación de documentos y respetan
  las preferencias de canal por usuario, y se disparan de forma natural por los eventos de
  dominio del Capítulo 11.
- Los **webhooks y callbacks** son asimétricos: los webhooks entrantes *almacenan rápido,
  luego procesan* (validación de firma, almacenamiento duradero, reenvío idempotente); los
  callbacks salientes *firman, luego entregan* (firmas HMAC sobre un cliente protegido por
  circuit-breaker). La misma familia de palabras, posturas opuestas.
- Nada de esto está en el reactor de acompañamiento; el capítulo es un mapa de dónde se
  enchufa cada capacidad en el Lumen Lending que has construido, no una porción verificada.

## Pruébalo tú mismo {.exercises}

1. **Esboza la plantilla del acuerdo.** Escribe un pequeño `loan-agreement.ftl` que
   interpole un nombre de solicitante, principal, plazo y APR en XHTML. No necesitas
   renderizarlo: el objetivo es ver que la plantilla del documento es el mismo FreeMarker
   que usarías para el cuerpo de un correo.
2. **Elige el planificador correcto.** Para cada uno de estos, decide entre `@Scheduled`,
   `@ScheduledSaga` y `@ScheduledWorkflow`, y justifícalo en una frase: un ping de salud de
   cinco minutos; un trabajo nocturno que caduca ofertas y revierte trabajo parcial ante un
   fallo; un calentamiento de caché horario.
3. **Conecta una notificación a un evento.** Esboza un `@EventListener` (el estilo del
   Capítulo 11) sobre `LoanApplicationRegisteredEvent` que llame a `notificationService.send`
   con una plantilla `"loan-approved"`. Observa que el productor no cambia: un nuevo
   consumidor simplemente reacciona, exactamente como en el capítulo de EDA.
4. **Defiende el webhook en dos fases.** En dos o tres frases, explica qué se rompe si un
   manejador de webhook entrante hace su trabajo de base de datos *antes* de devolver `2xx`.
   Nombra el modo de fallo (reintentos del proveedor) y el arreglo (almacena-luego-procesa
   con idempotencia sobre el id de evento del proveedor).
5. **Contrasta las posturas.** Escribe la única frase que distingue un webhook entrante de
   un callback saliente en el vocabulario de Firefly, luego enumera la preocupación que es
   única de cada uno (reenvío idempotente para uno; firma HMAC para el otro).

## Adónde ir ahora

Ya has visto tanto los bordes de Lumen Lending como su núcleo. Lo que queda es confianza:
demostrar que todo funciona y mantenerlo funcionando en producción. El Capítulo 23 reúne
los patrones de prueba que el libro ha venido usando todo el tiempo (pruebas de porción
contra R2DBC en memoria, pruebas de EDA sin broker, `StepVerifier` sobre cada publicador)
en una estrategia deliberada, y el Capítulo 24 lleva el servicio la última milla hasta
producción.
