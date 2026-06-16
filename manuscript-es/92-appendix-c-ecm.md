Un préstamo no termina en "aprobado". En algún punto se genera un contrato, se
presenta, se firma y se almacena como evidencia duradera, y en un prestamista
regulado ese rastro documental es la diferencia entre un préstamo formalizado y un
hallazgo de incumplimiento. El módulo de Enterprise Content Management (ECM) de
Firefly se encarga de ello, y como toda integración de Firefly es hexagonal:
dependes de puertos, y un adaptador de proveedor hace el trabajo real.

!!! warning "Honestidad sobre Lumen Lending"
    El reactor de acompañamiento **no** implementa el flujo de contratos: en la
    rebanada recortada de originación, aceptar una oferta es donde se detiene la
    historia, y el `signContract(...)` de un servicio real sería un stub que
    devuelve `ACTIVE`. Por tanto, este apéndice enseña el modelo de ECM y muestra
    cómo *se construiría* el paso del contrato; los fragmentos son ilustrativos, no
    rebanadas del reactor.

## La forma de ECM

`fireflyframework-ecm` define alrededor de dieciocho interfaces de puerto reactivas
agrupadas en familias: documentos, firma electrónica, procesamiento inteligente de
documentos, carpetas, seguridad y auditoría. Dependes del puerto que necesitas; un
`@EcmAdapter` registra una implementación, seleccionada por las propiedades
`firefly.ecm.*` (consulta el Apéndice B). Los puertos de seguridad deniegan por
defecto, y las capacidades ausentes recurren a no-ops seguros en lugar de fallar de
forma cerrada de maneras sorprendentes.

## Paso 1 — Generar el acuerdo (TemplateRenderUtil)

La generación de documentos no forma parte de ECM propiamente dicho; vive en
`fireflyframework-utils` como `TemplateRenderUtil`, un motor de FreeMarker a XHTML a
PDF con marcas de agua, cifrado, metadatos y caché de plantillas. Renderizas el
acuerdo de préstamo a partir de la solicitud aprobada y la oferta aceptada:

```java
// Illustrative — not in the companion reactor.
byte[] pdf = templateRenderUtil.renderPdf(
        "loan-agreement.ftl",
        Map.of("application", application, "offer", acceptedOffer));
```

El mismo motor de FreeMarker respalda las plantillas de notificaciones del Capítulo
22, de modo que un servicio tiene una única historia de plantillas tanto para
documentos como para mensajes.

## Paso 2 — Almacenarlo (puertos de documentos)

Los bytes renderizados se convierten en un documento almacenado y versionado a
través de los puertos de documentos y de contenido, detrás del adaptador de
almacenamiento que esté configurado (AWS S3 o Azure Blob):

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

## Paso 3 — Firmarlo (puertos de firma electrónica)

La familia de firma electrónica modela un sobre, una petición de firma, validación
y prueba. El proveedor — DocuSign, Adobe Sign o Logalty (evidencia electrónica
cualificada) — es una propiedad, de modo que el prestamista puede cambiar de
proveedor sin tocar este código:

```java
// Illustrative.
Mono<SignatureEnvelope> envelope = signatureEnvelopePort.create(
        EnvelopeSpec.builder()
            .document(stored.ref())
            .signer(applicant.email(), applicant.fullName())
            .build());
// later: validationPort.validate(envelopeId), proofPort.retrieve(envelopeId)
```

Cuando la firma se completa, la prueba (el paquete de evidencia sellado) se almacena
junto al documento, y un evento de dominio — gestionado exactamente igual que los
eventos del Capítulo 11 — lleva la solicitud a su estado formalizado.

## Los demás puertos de ECM, en breve

- **Procesamiento inteligente de documentos** — puertos de extracción, clasificación
  y extracción de datos para documentos entrantes (una nómina o un documento de
  identidad subidos), de modo que el onboarding pueda leer un documento en lugar de
  limitarse a archivarlo.
- **Seguridad de contenido** — puertos de permisos y de seguridad de documentos,
  deniegan por defecto, que gobiernan quién puede leer o modificar un documento
  almacenado.
- **Auditoría** — un puerto de auditoría que registra cada acceso y cada cambio, que
  es justo lo que un inspector pide ver.

## Adónde ir ahora

El flujo de contratos es la secuela natural del paso de aceptación de la oferta de
la Parte IV: genera con `TemplateRenderUtil`, almacena y asegura a través de los
puertos de documentos, firma a través de los puertos de firma electrónica y deja que
un evento de dominio formalice el préstamo. Construirlo de principio a fin —
primero contra los valores por defecto en proceso, luego con proveedores reales — es
el ejercicio más gratificante de este libro.
