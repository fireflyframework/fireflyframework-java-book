Un servicio de originación de préstamos hace la mayor parte de su trabajo dentro de
su propio límite: validar, puntuar, decidir, persistir. Pero los momentos que el
cliente realmente *siente* ocurren en los bordes, donde el servicio se extiende hacia
el mundo: un PDF de contrato que firmar, un correo de "tu préstamo está aprobado", un
trabajo nocturno que caduca ofertas obsoletas, un webhook de la pasarela de pago, un
callback al sistema de un socio. Cada uno de estos es una pequeña integración con sus
propios modos de fallo, y una flota que cada cual implementa a mano termina con una
docena de bucles de reintento, esquemas de firma y motores de plantillas sutilmente
distintos: exactamente el impuesto empresarial que nombró el capítulo 1.

Este capítulo es un recorrido por cuatro capacidades de Firefly que manejan esos
bordes de forma consistente: **documentos** (renderizar un PDF que puedas firmar),
**planificación** (trabajo de fondo duradero y recuperable), **notificaciones**
(correo, SMS y push detrás de puertos de proveedor) y **webhooks y callbacks**
(ingesta entrante y entrega firmada saliente). El objetivo no es hacerte experto en
ninguna de ellas —cada una tiene su propia referencia— sino darte la forma de cada
una, la frase única que la distingue de la alternativa obvia, y dónde encaja en el
Lumen Lending que has estado construyendo.

Lee este capítulo como un *mapa*, no como un tutorial. Cada sección sigue el mismo
ritmo: el momento de negocio que necesita la capacidad, la alternativa obvia y por
qué se queda corta a escala de flota, la capacidad de Firefly que la reemplaza, un
pequeño boceto fiel de la forma real de su API, y la única costura en Lumen Lending
donde se acoplaría. Al final sabrás qué capacidad usar, cómo se llama, y a qué evento
o apéndice se conecta: lo suficiente para abrir la referencia adecuada y empezar, sin
haber confundido un boceto con una porción.

!!! warning "Honestidad sobre Lumen Lending"
    Ninguna de estas cuatro capacidades aparece en el reactor de acompañamiento. La
    porción recortada de originación se detiene en la aceptación de la oferta —el
    flujo en vivo probado es
    `POST /api/v1/experience/lending/applications` devolviendo `201 SUBMITTED`,
    a través de la saga, hacia el core— así que **no hay código de documentos,
    planificación, notificaciones ni webhooks en el sample, ni una prueba de
    acompañamiento para este capítulo.** Cada bloque de código de abajo es
    *ilustrativo*: un boceto fiel de cómo se usa la capacidad, modelado según la API
    real del módulo del framework, no una porción literal del reactor. Cuando una
    capacidad se conecta con algo que el sample *sí* construye (los eventos del
    capítulo 11, el flujo de firma electrónica del apéndice C), el texto lo dice
    explícitamente, y solo esa conexión se afirma.

## Documentos — renderizar un PDF que puedas firmar

Cuando se acepta una oferta, el contrato del préstamo debe convertirse en un documento
real: generado a partir de la solicitud y la oferta aceptada, presentado para la
firma, y almacenado como evidencia duradera. Firefly divide eso en dos
responsabilidades. *Generar* el documento es el trabajo de `TemplateRenderUtil` en
`fireflyframework-utils`; *almacenarlo y firmarlo* es el trabajo de los puertos ECM
del apéndice C. Esta sección cubre la primera mitad: convertir datos en un PDF.

### La forma del pipeline de renderizado

`TemplateRenderUtil` es un pipeline de FreeMarker a XHTML a PDF: redactas el contrato
como una plantilla FreeMarker (`.ftl`) que produce XHTML bien formado, le pasas un
modelo de datos, y renderiza un PDF listo para imprimir con soporte para marcas de
agua, cifrado, metadatos y caché de plantillas. La idea diferenciadora es el
renderizado en *dos etapas* —FreeMarker primero produce XHTML, que un motor de PDF
luego pagina— de modo que el mismo lenguaje de plantillas y el mismo modelo de datos
que impulsan tus correos de notificación también impulsan tus documentos legales.

El porqué detrás de las dos etapas merece una frase. Una API de una sola etapa de
"datos directos a PDF" te obliga a expresar el diseño en código; un pipeline de dos
etapas deja que un diseñador sea dueño del XHTML/CSS mientras tú eres dueño solo del
modelo de datos. El XHTML intermedio también es inspeccionable: puedes renderizar la
misma plantilla a HTML para depurar un diseño, y luego a PDF para el artefacto real,
desde una sola fuente de plantilla.

El punto de entrada es una utilidad estática, no un bean inyectado. El método
principal toma un nombre de plantilla y un modelo de datos y devuelve los bytes
renderizados:

```java
// Illustrative — not in the companion reactor.
byte[] agreement = TemplateRenderUtil.renderTemplateToPdfBytes(
        "loan-agreement.ftl",
        Map.of(
            "application", application,     // the approved LoanApplication
            "offer",       acceptedOffer,   // the offer the customer accepted
            "generatedAt", LocalDate.now()));
```

Por defecto, la utilidad resuelve plantillas desde `classpath:/templates` y luego desde
un directorio local `./templates`, cachea las plantillas compiladas, y relanza los
errores de plantilla en lugar de tragárselos —de modo que una errata en el `.ftl`
falla ruidosamente en tiempo de renderizado en lugar de producir una página medio en
blanco.

### La plantilla es FreeMarker ordinario sobre XHTML

La plantilla en sí es FreeMarker ordinario sobre XHTML: los términos del préstamo
interpolados en un cuerpo de documento con estilo:

```html
<!-- loan-agreement.ftl (illustrative) -->
<h1>Personal Loan Agreement</h1>
<p>Borrower: ${application.applicantName}</p>
<p>Principal: ${offer.amount} ${offer.currency}</p>
<p>Term: ${offer.termMonths} months at ${offer.apr}% APR</p>
```

Si has escrito un cuerpo de correo con plantilla, ya has escrito esto: la misma
interpolación `${...}`, el mismo modelo de datos. Esa igualdad es justamente el punto
de la historia de notificaciones de la siguiente sección.

### Dónde encaja

Esos bytes de `agreement` son exactamente lo que consume el flujo de firma electrónica
del apéndice C: el puerto de documentos almacena el archivo renderizado, los puertos
de firma electrónica lo envuelven en un sobre de firma, y la prueba sellada vuelve para
ser almacenada junto a él. La generación y la firma son responsabilidades
deliberadamente separadas —puedes renderizar un contrato sin firmarlo, y los puertos
de firma no se preocupan de cómo se produjeron los bytes. En Lumen Lending el
disparador natural es la aceptación de la oferta: en el momento en que la capa de
experiencia registra una oferta aceptada, un consumidor de documentos renderiza el
contrato y entrega los bytes a los puertos ECM del apéndice C.

!!! note "Término clave — `TemplateRenderUtil`"
    Una utilidad de renderizado de documentos en `fireflyframework-utils` que compila
    una plantilla FreeMarker a XHTML y luego a PDF (el punto de entrada real es el
    estático `TemplateRenderUtil.renderTemplateToPdfBytes(template, model)`). *No* es
    parte de los puertos de documentos ECM; produce los bytes que esos puertos
    almacenan y firman. Como comparte FreeMarker con los canales de notificación de
    abajo, un servicio tiene **una sola** historia de plantillas tanto para documentos
    legales como para mensajes al cliente.

!!! spring "Equivalente en Spring"
    Spring puro no tiene opinión aquí —elegirías una librería de plantillas
    (FreeMarker, Thymeleaf) y un motor de PDF (OpenPDF, Flying Saucer) y los
    cablearías tú mismo, de forma distinta en cada servicio. La contribución de
    Firefly es el pipeline preensamblado y el motor de plantillas compartido, de modo
    que la generación de documentos se ve igual en toda la flota.

## Planificación — trabajo de fondo duradero y recuperable

La originación tiene trabajo que no se dispara por una petición: las ofertas caducan
tras una ventana, un lote nocturno vuelve a puntuar solicitudes cuyos datos de buró
cambiaron, un recordatorio sale tres días antes de la fecha límite de firma. El reflejo
es el `@Scheduled` de Spring —un método que se dispara según un cron. Eso está bien
para un latido, pero tiene un filo afilado para el trabajo *de negocio*.

### Por qué `@Scheduled` simple no basta para el trabajo de negocio

`@Scheduled` es de disparar-y-olvidar y local al nodo, y eso produce dos modos de fallo
en cuanto el trabajo muta estado. Primero, **trabajo perdido**: si el proceso muere a
mitad de ejecución, el trabajo a medio terminar simplemente desaparece —no hay diario
desde el que reanudar. Segundo, **trabajo duplicado**: si ejecutas tres réplicas, el
temporizador se dispara en las tres, así que un trabajo de "caducar ofertas obsoletas"
se ejecuta tres veces y un cliente podría ver una oferta caducada, reabierta y
recaducada en una confusa ráfaga. Ninguno es aceptable cuando el trabajo cambia estado
con forma de dinero.

### `@ScheduledSaga` hace el disparador duradero

La respuesta de Firefly es hacer que el trabajo planificado sea *duradero* enrutándolo
a través de los mismos motores de orquestación que ya usas para los flujos en primer
plano. `@ScheduledSaga` dispara una saga (capítulo 18) según una planificación, y
`@ScheduledWorkflow` dispara un workflow —de modo que una ejecución planificada obtiene
persistencia, recuperación a nivel de paso, y compensación, no solo un temporizador. La
frase diferenciadora: un método `@Scheduled` simple *se ejecuta* según una
planificación, mientras que un `@ScheduledSaga` *inicia una transacción recuperable y
de vuelo único* según una planificación, sobreviviendo a reinicios y coordinándose
entre réplicas.

La mecánica merece nombrarse con precisión, porque las anotaciones reparten la labor.
`@Saga(name = ...)` (capítulo 18) define *qué* es la transacción —sus pasos, sus
dependencias, sus compensaciones. `@ScheduledSaga(cron = ...)` añade *cuándo* se
dispara. Una saga planificada, por tanto, lleva **ambas** anotaciones: una para
identidad y recuperación, otra para el disparador.

```java
// Illustrative — not in the companion reactor.
@Saga(name = "expire-stale-offers")
@ScheduledSaga(
        cron = "0 0 2 * * *",            // every day at 02:00
        description = "Expire offers past their acceptance window")
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

Los atributos de `@ScheduledSaga` replican el propio vocabulario de planificación de
Spring a propósito: junto a `cron` puedes usar `fixedRate`, `fixedDelay` e
`initialDelay`, estrechar el cron a una `zone`, y desactivar una saga con
`enabled = false` sin borrarla. La anotación es `@Repeatable`, así que una saga puede
llevar varias planificaciones (un latido rápido con `fixedRate` *y* un `cron` nocturno,
por ejemplo). Lo que añade sobre Spring puro es todo lo que ocurre *después* de que el
disparador se active: la ejecución se registra en diario, es de vuelo único entre
réplicas, y es recuperable.

El `@Scheduled` más ligero todavía tiene su lugar —pings de salud, calentamientos de
caché, agregaciones de métricas, cualquier cosa idempotente y desechable. Recurre a
`@ScheduledSaga` o `@ScheduledWorkflow` en el momento en que el trabajo muta estado de
negocio y lamentarías ejecutarlo dos veces o perderlo a medio camino.

!!! note "Término clave — `@ScheduledSaga`"
    Una anotación a nivel de tipo en `fireflyframework-orchestration` que dispara una
    saga según una planificación. Se empareja con `@Saga(name = ...)`: la anotación
    `@Saga` da a la transacción su identidad, pasos y compensaciones; `@ScheduledSaga`
    añade el disparador `cron`/`fixedRate`/`fixedDelay`. El emparejamiento es la idea
    entera —no estás planificando un *método*, estás planificando una *transacción
    recuperable*.

!!! spring "Equivalente en Spring"
    `@ScheduledSaga` y `@ScheduledWorkflow` se construyen *sobre* la planificación de
    Spring —el disparador es el mismo mecanismo de cron y `fixedRate`/`fixedDelay`, con
    la misma sintaxis de expresión— y añaden durabilidad a través de los runtimes de
    saga y workflow de Firefly. Usa el `@Scheduled` simple de Spring para tics
    efímeros; usa las variantes de Firefly cuando "esto se disparó pero nunca terminó" o
    "esto se disparó dos veces" sería un incidente real.

## Notificaciones — correo, SMS y push detrás de puertos

La misma aprobación que genera un contrato también debería llegar al cliente: "Estás
aprobado", "Por favor, firma antes del viernes", "Tu préstamo está activo". Firefly
modela eso como un conjunto de **servicios de canal** —correo, SMS y push— cada uno
situado detrás de un puerto de proveedor, exactamente como el patrón hexagonal que
viste para eventos y ECM. Llamas a un servicio de canal; un adaptador seleccionado por
una propiedad `firefly.notifications.*` (o, concretamente, por qué starter de proveedor
está en el classpath —`fireflyframework-notifications-sendgrid`, `-twilio`,
`-firebase`, `-resend`) enruta el mensaje al proveedor sin que tu código nombre a
ninguno de ellos.

### Un servicio de canal por medio

La superficie del módulo es una interfaz de servicio por medio —
`EmailService.sendEmail(...)` / `sendTemplateEmail(...)`, `SMSService`, `PushService`—
cada una devolviendo un `Mono` y cada una respaldada por un adaptador de proveedor.
Programar contra la interfaz, no contra el SDK del proveedor, es la misma promesa de
cambio-de-una-propiedad que los demás puertos de Firefly: cambiar de proveedor de SMS
es un adaptador en el classpath y una propiedad, no una reescritura de tu manejador.

```java
// Illustrative — not in the companion reactor.
Mono<EmailResponseDTO> sent = emailService.sendTemplateEmail(
        EmailTemplateRequestDTO.builder()
            .to(applicant.email())
            .templateId("loan-approved")        // FreeMarker template, shared engine
            .templateVariables(Map.of(
                    "name",   applicant.fullName(),
                    "amount", offer.amount()))
            .subject("Your loan is approved")
            .build());
// The provider adapter (SendGrid, Resend, ...) does the real send;
// the template is rendered by the shared FreeMarker engine before dispatch.
```

### Dos detalles que lo hacen más que un fino envoltorio de SDK

Primero, comparte el motor FreeMarker con la generación de documentos —el
`FreemarkerNotificationTemplateEngine` renderiza un correo con plantilla de la misma
manera que `TemplateRenderUtil` renderiza el contrato del préstamo, de modo que un
servicio tiene una sola historia de plantillas tanto para documentos como para
mensajes. Segundo, respeta las **preferencias de canal por usuario**: un
`NotificationPreferenceService` responde `isChannelEnabled(userId, channel)`, así que
un cliente que se dio de baja del SMS pero quiere correo recibe el mensaje en el canal
que eligió —decidido por el almacén de preferencias del framework en lugar de por
ramificaciones en tu manejador.

```java
// Illustrative — choose the channel from the customer's stored preference.
Mono<Boolean> wantsSms = preferenceService.isChannelEnabled(applicant.id(), "SMS");
```

### Dónde encaja

Un disparador natural para ese envío es un evento que ya publicas. El
`LoanApplicationRegisteredEvent` del capítulo 11 —o un posterior `OfferAcceptedEvent`—
es exactamente el anuncio al que reacciona un consumidor de notificaciones: un
`@EventListener` recibe el hecho y llama al servicio de canal, de modo que la mensajería
queda desacoplada de la escritura que la causó, igual que cualquier otro consumidor. El
productor no cambia; un nuevo consumidor simplemente se suscribe —y como la EDA de
Lumen Lending corre sobre el transporte en-JVM `APPLICATION_EVENT`, ese consumidor se
cablea sin ningún broker.

!!! note "Término clave — servicio de canal"
    Un **servicio de canal** es el puerto para un medio de entrega —`EmailService`,
    `SMSService` o `PushService`. Programas contra el servicio de canal y un adaptador
    de proveedor hace el envío real. Cambiar de un proveedor de SMS a otro es un cambio
    de propiedad y un adaptador en el classpath, no una reescritura de SDK —la misma
    promesa de cambio-de-una-propiedad que los demás puertos de Firefly.

!!! spring "Equivalente en Spring"
    Spring ofrece `JavaMailSender` para correo y nada unificado para SMS o push, así que
    cada servicio cría sus propios clientes de proveedor y su propia lógica de
    preferencias. El módulo de notificaciones de Firefly unifica los tres canales detrás
    de una API de un-puerto-por-medio, comparte el motor de plantillas con la generación
    de documentos, y centraliza las preferencias de canal en un
    `NotificationPreferenceService` —de modo que "notificar al cliente" es una sola
    llamada contra un puerto, no tres integraciones a medida.

## Webhooks frente a callbacks — ingesta entrante frente a entrega saliente

El último borde es el más delicado, porque corre en ambas direcciones, y las dos
direcciones *no* son simétricas. Un **webhook** es algo que el mundo exterior te envía
*a ti* —un procesador de pagos diciéndote que un desembolso se liquidó. Un **callback**
es algo que tú envías *al* mundo exterior —diciéndole a un socio corredor que una
solicitud alcanzó una decisión. Firefly los trata como dos capacidades con
preocupaciones genuinamente distintas, y confundirlas es el error que el módulo está
diseñado para prevenir.

### Webhooks entrantes — almacena rápido, luego procesa

Un webhook entrante llega de un sistema que no controlas, a menudo con una agresiva
política de entrega-y-reintento: confirma en unos pocos cientos de milisegundos o
reintenta, a veces reentregando el mismo evento muchas veces. Así que el manejo
entrante de Firefly es en **dos fases**. La fase uno valida la firma del proveedor y
*almacena de forma duradera* el payload crudo, y luego devuelve inmediatamente
`202 Accepted` —rápido, antes de que corra cualquier lógica de negocio. La fase dos
procesa el evento almacenado de forma asíncrona, con idempotencia basada en el id de
evento del proveedor, de modo que una reentrega se reconoce y se descarta en lugar de
aplicarse dos veces. La idea diferenciadora es *almacenar-luego-procesar*: nunca haces
que un llamante inestable espere a tu lógica de negocio, y nunca pierdes un evento
porque el procesamiento lanzó una excepción.

Concretamente, el módulo es un `WebhookController` que recibe el POST y responde
`ResponseEntity.status(HttpStatus.ACCEPTED)` una vez que el payload está encolado, un
puerto `WebhookSignatureValidator` que cada proveedor implementa con su propio esquema
HMAC (el propio Javadoc del puerto esboza validadores de Stripe, Twilio y GitHub), y un
`WebhookIdempotencyService` que deduplica por el id de evento del proveedor durante la
fase dos. Tu trabajo es la reacción de negocio de la fase dos:

```java
// Illustrative — not in the companion reactor.
// The framework's WebhookController has already validated the provider signature,
// stored the raw payload, returned 202 Accepted, and de-duplicated on the event id.
// You supply the phase-two business reaction:
public Mono<Void> onDisbursementSettled(DisbursementSettled event) {
    return loans.markDisbursed(event.loanId());
}
```

!!! warning "Un webhook entrante debe confirmar antes de procesar"
    Si haces el trabajo de negocio *antes* de devolver `2xx`, una llamada lenta a base
    de datos o un error transitorio se convierte en que el proveedor reintente —y ahora
    estás procesando el mismo evento dos, tres, cinco veces bajo carga. La división
    almacenar-luego-procesar existe precisamente para que la confirmación sea rápida (el
    controlador devuelve `202 Accepted` en el momento en que el payload está almacenado)
    y el procesamiento sea idempotente. Deja que el framework almacene, valide y
    confirme; haz tu trabajo en la fase dos.

### Callbacks salientes — firma, luego entrega de forma resiliente

Un callback saliente es la imagen especular. *Tú* eres ahora el llamante, así que la
carga recae sobre ti de probar la autenticidad y de sobrevivir a que el socio esté
caído. Firefly firma el payload con **HMAC** para que el receptor pueda verificar que
vino de ti y no fue manipulado, y lo entrega a través de un cliente guardado por un
**cortacircuitos** con reintentos —de modo que un socio que está agotando el tiempo de
espera dispara el cortacircuitos en lugar de ahogar tus hilos, y se recupera
automáticamente cuando vuelve. La frase diferenciadora: un callback saliente es una
*entrega* firmada con HMAC y con cortacircuitos, no solo un POST de HTTP.

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
nada y optimizas para *no perder* eventos que no pediste: valida, almacena, confirma, y
luego procesa de forma idempotente. Saliente, nadie confía en ti y optimizas para una
entrega *demostrable y resiliente*: firma, y luego entrega a través de un cortacircuitos.
La misma familia de palabras, posturas opuestas —y la simetría es una trampa, porque el
código de reintento que escribirías para uno es el código equivocado para el otro.

### Dónde encaja

En Lumen Lending el borde entrante sería un webhook de la pasarela de pago confirmando
un desembolso, aterrizando en el core para voltear un préstamo a desembolsado; el borde
saliente sería un callback de decisión a un socio corredor, disparado por el mismo
`OfferAcceptedEvent` (o un evento de decisión) al que reacciona el consumidor de
notificaciones. Ambos están aguas abajo de los eventos que ya publicas —la columna
vertebral de la EDA del capítulo 11 es la espina de la que cuelgan los bordes.

!!! note "Término clave — webhook frente a callback"
    En el vocabulario de Firefly un **webhook** es *entrante* (el mundo te llama) y un
    **callback** es *saliente* (tú llamas al mundo). Son capacidades separadas porque
    sus problemas son distintos: lo entrante trata de confirmación rápida
    (`202 Accepted`), validación de firma e idempotencia ante reentregas; lo saliente
    trata de firma HMAC y entrega con cortacircuitos y reintentos. Si te encuentras
    escribiendo el mismo código de reintento en ambos lados, probablemente has colapsado
    dos problemas distintos en uno.

!!! spring "Equivalente en Spring"
    Spring puro te da `@RestController` para el endpoint entrante y `WebClient` para la
    llamada saliente, y deja la validación de firma, el almacenamiento duradero, la
    idempotencia, la firma HMAC y el cortacircuitos enteramente a ti. Los módulos de
    webhook y callback de Firefly precablean esas preocupaciones de modo que cada
    servicio ingiere y emite integraciones de la misma manera —el contrato entrante de
    `202`-luego-procesar y la entrega saliente firmada-y-con-cortacircuitos, idénticos en
    toda la flota.

## Lo que has aprendido {.recap}

- Los **documentos** se renderizan con `TemplateRenderUtil` —un pipeline de
  FreeMarker → XHTML → PDF en `fireflyframework-utils`, accedido a través del estático
  `renderTemplateToPdfBytes(template, model)`— cuyos bytes alimentan el flujo de firma
  electrónica del apéndice C. La generación y la firma son pasos deliberadamente
  separados, y el disparador natural en Lumen Lending es la aceptación de la oferta.
- La **planificación** viene en dos intensidades: el `@Scheduled` simple de Spring para
  tics efímeros, y `@ScheduledSaga` / `@ScheduledWorkflow` de Firefly para trabajo de
  fondo duradero, de vuelo único y recuperable que muta estado de negocio. Una saga
  planificada lleva **ambas** `@Saga(name = ...)` (identidad, pasos, compensación) y
  `@ScheduledSaga(cron = ...)` (el disparador).
- Las **notificaciones** son servicios de canal —`EmailService`, `SMSService`,
  `PushService`— detrás de puertos de proveedor, compartiendo el motor FreeMarker con
  la generación de documentos vía `FreemarkerNotificationTemplateEngine` y respetando
  las preferencias de canal por usuario a través de
  `NotificationPreferenceService.isChannelEnabled(...)`, y se disparan naturalmente por
  los eventos de dominio del capítulo 11 sobre el transporte en-JVM `APPLICATION_EVENT`.
- Los **webhooks y callbacks** son asimétricos: los webhooks entrantes *almacenan
  rápido, luego procesan* (un `WebhookController` devuelve `202 Accepted`, un
  `WebhookSignatureValidator` valida el HMAC del proveedor, un
  `WebhookIdempotencyService` descarta reentregas); los callbacks salientes *firman,
  luego entregan* (firmas HMAC sobre un cliente guardado por un cortacircuitos). La
  misma familia de palabras, posturas opuestas.
- Nada de esto está en el reactor de acompañamiento; el capítulo es un mapa de dónde
  encaja cada capacidad en el flujo en vivo `exp → domain → core` que has construido, no
  una porción verificada —cada fragmento de arriba es un boceto fiel de la API real del
  módulo, no salida capturada.

## Pruébalo tú mismo {.exercises}

1. **Boceta la plantilla del contrato.** Escribe una pequeña `loan-agreement.ftl` que
   interpole un nombre de solicitante, principal, plazo y APR en XHTML, y anota la
   llamada que lo renderizaría: `TemplateRenderUtil.renderTemplateToPdfBytes(...)`. No
   necesitas renderizarla —el objetivo es ver que la plantilla de documento es el mismo
   FreeMarker que usarías para un cuerpo de correo.
2. **Elige el planificador adecuado.** Para cada uno de estos, decide entre
   `@Scheduled`, `@ScheduledSaga` y `@ScheduledWorkflow`, y justifícalo en una frase: un
   ping de salud cada cinco minutos; un trabajo nocturno que caduca ofertas y revierte
   trabajo parcial en caso de fallo; un calentamiento de caché cada hora. Para aquel en
   el que elijas `@ScheduledSaga`, escribe las *dos* anotaciones que necesita y di qué
   aporta cada una.
3. **Cablea una notificación a un evento.** Boceta un `@EventListener` (al estilo del
   capítulo 11) sobre `LoanApplicationRegisteredEvent` que llame a
   `emailService.sendTemplateEmail` con una plantilla `"loan-approved"`, condicionado a
   `preferenceService.isChannelEnabled(userId, "EMAIL")`. Nota que el productor no
   cambia —un nuevo consumidor simplemente reacciona, exactamente como en el capítulo de
   EDA.
4. **Defiende el webhook de dos fases.** En dos o tres frases, explica qué se rompe si
   un manejador de webhook entrante hace su trabajo de base de datos *antes* de devolver
   `2xx`. Nombra el modo de fallo (el proveedor reintenta), la confirmación rápida que
   el framework da en su lugar (`202 Accepted` una vez almacenado), y el arreglo
   (almacenar-luego-procesar con idempotencia por el id de evento del proveedor).
5. **Contrasta las posturas.** Escribe la frase única que distingue un webhook entrante
   de un callback saliente en el vocabulario de Firefly, luego lista la preocupación que
   es única de cada uno (idempotencia ante reentregas para uno; firma HMAC para el otro)
   y la pieza del framework que la posee (`WebhookIdempotencyService` frente al firmante
   HMAC saliente).

## Adónde ir ahora

Ya has visto los bordes de Lumen Lending tanto como su core. Lo que queda es confianza:
demostrar que todo funciona y mantenerlo funcionando en producción. El capítulo 23
reúne los patrones de prueba que el libro ha estado usando todo el tiempo —pruebas de
porción contra R2DBC en memoria, pruebas de EDA sin broker sobre `APPLICATION_EVENT`,
`StepVerifier` en cada publicador— en una estrategia deliberada, respaldada por las
**33 pruebas** reales del reactor (core 18, domain 6, exp 9). El capítulo 24 lleva luego
el servicio la última milla hasta producción.
