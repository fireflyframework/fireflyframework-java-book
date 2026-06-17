Un préstamo no termina en "aprobado". En algún punto se genera, se presenta, se
firma y se almacena un contrato como evidencia duradera — y en una entidad
prestamista regulada ese rastro documental es la diferencia entre un préstamo
formalizado y un hallazgo de cumplimiento normativo. El módulo de Enterprise
Content Management (ECM) de Firefly se encarga de ello y, como toda integración de
Firefly, es hexagonal: dependes de puertos y un adaptador de proveedor hace el
trabajo real.

!!! warning "Honestidad sobre Lumen Lending"
    El reactor de acompañamiento **no** implementa el flujo del contrato. En la
    porción recortada de originación la historia se detiene en `SUBMITTED`: un `POST`
    al BFF de experiencia (`/api/v1/experience/lending/applications` en `:8080`)
    devuelve `201` y fluye `exp → domain → core` — el dominio ejecuta
    `RegisterApplicationSaga`, cuyo paso raíz escribe en el sistema de registro del
    core en `:8081`. No hay aceptación de oferta, ni generación de contrato, ni paso
    de firma en disco; el `signContract(...)` de un servicio real sería un stub que
    devuelve `ACTIVE`. Por tanto, este apéndice enseña el modelo de ECM y muestra
    cómo *se construiría* el paso del contrato sobre ese flujo. **Cada fragmento
    aquí es ilustrativo** — código entre vallas plano, no una porción literal del
    reactor — y `fireflyframework-ecm` **no** está en el classpath del ejemplo.

## La forma de ECM

`fireflyframework-ecm` define una familia de interfaces de puerto reactivas agrupadas
por área de interés — documentos y contenido, firma electrónica, procesamiento
inteligente de documentos (IDP), carpetas, seguridad de contenido y auditoría.
Dependes del puerto que necesitas; un componente de proveedor al estilo
`@EcmAdapter` registra una implementación, seleccionada mediante propiedades
`firefly.ecm.*` (el mismo patrón de adaptador dirigido por propiedades que el
Apéndice B describe para los demás módulos de integración). Dos reglas de diseño
recorren todo el módulo:

- **La seguridad deniega por defecto.** Un puerto de permisos al que no se le ha
  dicho que un principal puede leer un documento responde "no", no "sí". Optas por
  conceder el acceso, no optas por retirarlo.
- **Las capacidades ausentes degradan de forma segura.** Si un proveedor configurado
  no implementa, por ejemplo, la evidencia electrónica cualificada, el puerto
  afectado recurre a un no-op documentado o a un error tipado de "no soportado" en
  lugar de fallar de forma cerrada en un punto sorprendente a mitad de flujo.

Cada puerto devuelve tipos reactivos — `Mono<T>` para un único resultado, `Flux<T>`
para un flujo — de modo que una llamada de ECM se compone en la misma tubería
reactiva que el resto de un servicio Firefly. Nada de esto bloquea un hilo del
event-loop de Netty.

!!! note "Término clave — puerto ECM frente a adaptador"
    Un **puerto ECM** es una interfaz neutral respecto al proveedor — `almacenar un
    documento`, `crear un sobre de firma`, `extraer campos de un escaneo`. Un
    **adaptador** es la implementación concreta para un proveedor (S3, DocuSign, un
    servicio de OCR en la nube). El código de tu servicio solo nombra el puerto; las
    propiedades `firefly.ecm.*` lo vinculan a un adaptador en el arranque, de modo
    que cambiar DocuSign por Adobe Sign es un cambio de configuración, no un cambio
    de código.

## Paso 1 — Generar el acuerdo (TemplateRenderUtil)

La generación de documentos no forma parte de ECM propiamente dicho; vive en
`fireflyframework-utils` como `TemplateRenderUtil`, un motor de FreeMarker a XHTML a
PDF con marcas de agua, cifrado, metadatos de documento y caché de plantillas. El
paso del contrato renderizaría el acuerdo de préstamo a partir de la solicitud
aprobada y la oferta aceptada:

```java
// Illustrative — not in the companion reactor.
byte[] pdf = templateRenderUtil.renderPdf(
        "loan-agreement.ftl",
        Map.of("application", application, "offer", acceptedOffer));
```

Hay algunas cosas que importan sobre dónde encaja esto. `TemplateRenderUtil` es un
bean de utilidad sencillo, no un puerto ECM — convierte una plantilla más un modelo
de datos en bytes y ahí se detiene; almacenar, asegurar y firmar esos bytes es
tarea de ECM. El motor renderiza FreeMarker (`.ftl`) a XHTML y luego a PDF, de modo
que el autor de la plantilla escribe marcado ordinario con expresiones `${...}`, y
la marca de agua, el cifrado de propietario/usuario y los metadatos del PDF (título,
autor, palabras clave) son opciones de renderizado en lugar de fontanería de PDF
hecha a mano. La caché de plantillas significa que un flujo de formalización de alto
volumen compila cada `.ftl` una sola vez.

Y lo que es crucial, el *mismo* motor de FreeMarker respalda las plantillas de
notificación del capítulo de notificaciones, así que un servicio tiene una única
historia de plantillas tanto para documentos como para mensajes — el correo de
bienvenida y el acuerdo de préstamo se renderizan con la misma maquinaria a partir
del mismo tipo de plantilla.

!!! note "Término clave — modelo de renderizado"
    El **modelo de renderizado** es el `Map<String, Object>` (o un contenedor tipado)
    que entregas a la plantilla. Mantenlo con la forma del documento, no de los
    internos de tu dominio: pasa una vista `application` y una vista `acceptedOffer`
    con exactamente los campos que el acuerdo imprime, para que la plantilla siga
    siendo una preocupación de presentación y un cambio en una entidad interna no
    reconfigure silenciosamente un documento legal.

## Paso 2 — Almacenarlo (puertos de documento y contenido)

Los bytes renderizados se convierten en un documento almacenado y versionado a
través de los puertos de documento y contenido, detrás del adaptador de
almacenamiento que esté configurado (por ejemplo AWS S3 o Azure Blob):

```java
// Illustrative.
Mono<DocumentRef> stored = documentPort.store(
        DocumentSpec.builder()
            .name("loan-agreement-" + application.id() + ".pdf")
            .contentType("application/pdf")
            .bytes(pdf)
            .folder("loans/" + application.id())
            .build());
```

El puerto devuelve un `DocumentRef` — un manejador estable (un id, un tipo de
contenido, una versión) que persistes frente a la solicitud, no los bytes en sí. El
almacenamiento es problema del adaptador: la misma llamada `documentPort.store(...)`
deposita el PDF en un bucket de S3 o en un contenedor de Azure dependiendo
únicamente de `firefly.ecm.*`, y un puerto de carpetas organiza los documentos en
una jerarquía (`loans/{applicationId}`) para que las políticas de recuperación y
retención tengan dónde apoyarse. Como el adaptador, y no tu código, es dueño del
nombre del bucket, la región y las credenciales, el flujo de formalización que
escribes en la capa de dominio es idéntico en desarrollo (donde un adaptador
local/en memoria podría sustituirlo) y en producción.

## Paso 3 — Firmarlo (puertos de firma electrónica)

La familia de firma electrónica modela un sobre, una petición de firma, la
validación y la prueba. El proveedor — DocuSign, Adobe Sign o un proveedor de
evidencia electrónica cualificada como Logalty — es una propiedad, de modo que la
entidad prestamista puede cambiar de proveedor sin tocar este código:

```java
// Illustrative.
Mono<SignatureEnvelope> envelope = signatureEnvelopePort.create(
        EnvelopeSpec.builder()
            .document(stored.ref())
            .signer(applicant.email(), applicant.fullName())
            .build());
// later: validationPort.validate(envelopeId), proofPort.retrieve(envelopeId)
```

Lee el ciclo de vida en tres tiempos. El **sobre** agrupa uno o más documentos y uno
o más firmantes en una única transacción de firma; `create(...)` entrega el
documento al proveedor y devuelve un manejador más (con un proveedor remoto) una URL
de firma que el BFF puede mostrar al solicitante. La **validación** pregunta al
proveedor si las firmas completadas son criptográfica y procedimentalmente sólidas —
firmante correcto, documento sin manipular, certificado válido. La **prueba**
recupera el paquete de evidencia sellado (el PDF firmado más el rastro de auditoría
/ certificado de finalización) que un examinador exigirá más adelante.

La firma es intrínsecamente asíncrona: el solicitante firma minutos o días después de
que se crea el sobre. Así que el cableado realista está dirigido por eventos, no es
una espera bloqueante. El adaptador de proveedor señala la finalización (mediante un
webhook o un sondeo), el servicio publica un evento de dominio, y un manejador —
exactamente la clase de manejador de eventos de EDA que construye el capítulo de
eventos, y el mismo transporte `APPLICATION_EVENT` en la JVM que el ejemplo ya usa
para su saga — recupera la prueba, la almacena junto al acuerdo a través del puerto
de documento y mueve la solicitud a su estado formalizado. El paso del contrato no
es, por tanto, un único método síncrono; es *generar → almacenar → crear sobre* en
la petición, y luego *firma completada → recuperar prueba → almacenar prueba →
formalizar* en el evento.

!!! note "Término clave — prueba de firma"
    La **prueba** es la evidencia duradera de una firma completada: el documento
    firmado, un sello a prueba de manipulaciones y el rastro de auditoría (quién
    firmó, cuándo, desde dónde, con qué certificado). Almacenar la prueba — y no solo
    el PDF firmado — es lo que convierte "el cliente pulsó firmar" en algo defendible
    ante un regulador. Trata la prueba como el sistema de registro del contrato,
    almacenada y asegurada como cualquier otro documento de ECM.

## Dónde se situaría el paso del contrato en el ejemplo

En el reactor en ejecución la ruta viva es `exp → domain → core`: la
`RegisterApplicationSaga` del dominio ya orquesta una escritura de varios pasos a
través de las capas. El flujo del contrato es la secuela natural de un paso de
*aceptación de oferta* que la porción todavía no tiene. Conceptualmente sería una
saga más (o una continuación de la existente) en la capa de **dominio** — la capa de
orquestación es exactamente donde corresponde una secuencia "generar, almacenar,
firmar, formalizar" — invocando los puertos ECM del mismo modo que el paso raíz de
la saga actual invoca el cliente `WebClient` del core. La capa core persistiría el
`DocumentRef` resultante y el estado formalizado; el BFF de experiencia expondría la
URL de firma al canal. Nada de eso está cableado hoy, pero encaja en las costuras que
el ejemplo ya demuestra.

## Los demás puertos de ECM, brevemente

- **Procesamiento inteligente de documentos (IDP)** — puertos de extracción,
  clasificación y extracción de datos para documentos *entrantes* (una nómina
  subida, un escaneo de un documento de identidad), de modo que el onboarding pueda
  *leer* un documento en lugar de solo archivarlo. Estos puertos convierten una
  imagen escaneada en campos estructurados sobre los que una regla puede actuar; en
  una entidad prestamista real alimentan la decisión de crédito en lugar del
  contrato, pero comparten el mismo modelo de adaptador y seguridad. (Como todo en
  este apéndice, el IDP no está cableado en el ejemplo.)
- **Seguridad de contenido** — puertos de permisos y de seguridad de documentos, que
  deniegan por defecto y gobiernan quién puede leer o cambiar un documento
  almacenado, de modo que el acuerdo firmado no sea legible por todo el mundo solo
  porque se almacenó con éxito.
- **Auditoría** — un puerto de auditoría que registra cada acceso y cambio en un
  documento, que es precisamente lo que un examinador pide ver y lo que cierra el
  círculo sobre la prueba almacenada en el Paso 3.

## Adónde ir ahora

El flujo del contrato es el ejercicio más gratificante que este libro deja sobre la
mesa: genera el acuerdo con `TemplateRenderUtil`, almacénalo y asegúralo a través de
los puertos de documento y contenido, crea y valida un sobre de firma electrónica,
deja que un evento de EDA transporte la prueba completada y formaliza el préstamo —
extendiendo la misma saga `exp → domain → core` que el ejemplo ejecuta en vivo.
Constrúyelo primero contra valores por defecto en proceso o locales y cablea
proveedores reales (S3/Azure para el almacenamiento, DocuSign/Adobe/Logalty para la
firma) más tarde, cambiando cada uno con una única propiedad `firefly.ecm.*` y sin
ningún cambio en el código que escribiste.
