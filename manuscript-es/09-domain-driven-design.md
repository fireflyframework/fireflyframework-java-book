El Capítulo 8 dotó a Lumen Lending de una capa de persistencia real: una fila
`loan_application`, una clave primaria UUID, columnas de auditoría y un repositorio
reactivo que la lee y la escribe. Esa fila es honesta sobre los datos, pero guarda
silencio sobre las *reglas*. Nada en un `BigDecimal requestedAmount` dice que el
importe nunca puede ser negativo. Nada en una columna `String status` dice que no
puedes aprobar una solicitud que aún es un borrador. Esas reglas viven, hoy, dispersas
por cualquier servicio que toque la fila — exactamente el modelo anémico que el Diseño
Dirigido por el Dominio (DDD) nació para curar.

Este capítulo asciende la fila a un modelo de dominio *rico*. Construirás un objeto de
valor `Money` que convierte "importe no negativo en unidades menores exactas" en una
propiedad del propio tipo, y moverás el ciclo de vida de la solicitud de préstamo —
enviar, revisar, aprobar, rechazar, cancelar — *al agregado*, de modo que la única
forma de cambiar su estado sea pedirle que realice una transición legal. La fila de
persistencia no desaparece; permanece, y un mapeador la mantiene a distancia de la API.
Al final, las reglas tienen un único hogar, y un sencillo test de JUnit — sin Spring,
sin base de datos — demuestra que se cumplen.

Este es un capítulo conceptual con una recompensa ejecutable. Todo lo que diseccionas
aquí es Java corriente y Spring Data; la aportación de Firefly es la disciplina que lo
rodea — los validadores financieros del Capítulo 6 protegen el *borde*, y el modelo de
dominio que construyes ahora protege el *núcleo*. Ambos se encuentran en el medio, y
ninguno confía en que el otro haga su trabajo.

## La fila anémica, y por qué tiene fugas

Esta es la forma que la mayoría de los equipos entrega primero. La entidad es un saco
de campos con getters y setters públicos; las reglas viven en un servicio que la muta
desde fuera:

```java
// Anemic: the entity is a data bag; rules live (and scatter) elsewhere.
public class LoanApplication {
    private BigDecimal requestedAmount;   // could be set to -500
    private ApplicationStatus status;     // could be set to APPROVED from anywhere
    // ... getters and setters for every field ...
}

// In some service, far from the data:
app.setStatus(ApplicationStatus.APPROVED);   // was it even under review? who knows.
```

El setter no sabe — *no puede* saber — si aprobar esta solicitud es legal en este
momento. Así que cada invocador tiene que acordarse de comprobarlo primero, y el día
que uno de ellos lo olvide, aprobarás un borrador. Lo mismo ocurre con el importe:
`setRequestedAmount` almacenará alegremente un número negativo, y el invariante "el
dinero nunca es negativo" se convierte en una convención de revisión de código en lugar
de una garantía.

La cura tiene dos partes. Primero, dale al primitivo peligroso un *tipo* que no pueda
albergar un valor ilegal — un **objeto de valor**. Segundo, haz que la entidad sea lo
único que pueda cambiar su propio estado, a través de métodos que codifiquen las
transiciones legales — una **raíz de agregado**. Los construimos en ese orden.

!!! note "Termino clave — modelo de dominio anémico vs. rico"
    Un modelo **anémico** separa los datos (entidades tontas con getters/setters) del
    comportamiento (clases de servicio que las mutan). Un modelo **rico** pone el
    comportamiento *en* la entidad, de modo que los invariantes se imponen allá donde
    vaya el objeto. El DDD favorece el modelo rico precisamente porque elimina el modo
    de fallo del "¿se acordaron todos de comprobar?".

## Paso 1 — Un objeto de valor Money con un invariante impuesto

El dinero es el objeto de valor de manual: dos importes del mismo valor son
intercambiables, no acarrea identidad, y tiene un invariante duro — nunca es negativo.
Lumen lo modela como un `record` de Java que contiene un recuento entero de *unidades
menores* (céntimos), nunca un `double` ni un `BigDecimal` desnudo, de modo que la
aritmética es exacta y el redondeo nunca está silenciosamente equivocado.

El constructor canónico del record no es, intencionadamente, donde vive el invariante;
sí lo es una factoría estática `of`, porque puede rechazar entradas ilegales con un
mensaje claro antes de que exista ningún `Money`:

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/Money.java | Listado 9.1 — Money: un objeto de valor no negativo en unidades menores exactas
public record Money(long minorUnits) {

    /**
     * Creates a {@code Money} of the given minor units.
     *
     * @param minorUnits amount in minor units
     * @return the value object
     * @throws IllegalArgumentException if {@code minorUnits} is negative
     */
    public static Money of(long minorUnits) {
        if (minorUnits < 0) {
            throw new IllegalArgumentException(
                    "Money cannot be negative: " + minorUnits);
        }
        return new Money(minorUnits);
    }
:::

Como el tipo es un `record`, obtienes `equals`, `hashCode` y `toString` gratis — que es
exactamente lo que hace que dos valores `Money` de `150_000` se comparen como iguales
en un test sin ninguna ceremonia. Y como `minorUnits` es un `long`, no hay ningún
importe en coma flotante que redondear.

El invariante da sus frutos en el momento en que haces aritmética. La resta se define
en términos de la misma factoría, de modo que un resultado que caería por debajo de
cero se rechaza en el origen en lugar de producir un saldo negativo sin sentido:

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/Money.java | Listado 9.2 — aritmética encauzada a través del invariante
    public Money minus(Money other) {
        return Money.of(this.minorUnits - other.minorUnits);
    }
}
:::

Fíjate en lo que *no* escribiste: no hay ningún `setMinorUnits`, ninguna forma de mutar
un `Money` tras su construcción, y ningún camino hacia uno negativo. El invariante no
es una regla que te acuerdas de aplicar; es una propiedad del tipo. Cualquier código
que sostenga un `Money` sostiene un importe válido, punto.

!!! spring "Equivalente en Spring"
    Aquí no hay nada específico de Firefly — `Money` es Java corriente. Esa es la
    cuestión: los objetos de valor del DDD son una técnica de *modelado*, no una
    característica del framework. Los validadores financieros de Firefly (Capítulo 6) y
    este objeto de valor son complementarios: `@ValidAmount` rechaza un número erróneo
    en el borde HTTP antes de que llegue siquiera a tu código; `Money.of` garantiza que
    *dentro* del dominio, un importe que existe es un importe válido. Cinturón y
    tirantes, a propósito.

## Paso 2 — La raíz de agregado posee su ciclo de vida

Ahora la entidad. `LoanApplication` sigue siendo un `@Table` de Spring Data R2DBC —
conserva el `@Id` UUID, los mapeos `@Column` en snake_case y las columnas de auditoría
`created_at`/`updated_at` que construiste en el Capítulo 8. Lo que cambia es que deja
de ser un saco pasivo de setters y empieza a *imponer su propio ciclo de vida*.

El ciclo de vida es una pequeña máquina de estados, nombrada por un enum. El detalle
crucial es el último método: el enum sabe qué estados son *terminales* — estados desde
los que ninguna transición posterior es legal — y el agregado lo consulta antes de
permitir un cambio:

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/domain/ApplicationStatus.java | Listado 9.3 — ApplicationStatus y el predicado de estado terminal
public enum ApplicationStatus {

    /** Captured but not yet submitted for review. */
    DRAFT,

    /** Submitted by the applicant; awaiting a credit officer. */
    SUBMITTED,

    /** A credit officer is actively reviewing the application. */
    UNDER_REVIEW,

    /** Approved; an offer may be proposed to the applicant. */
    APPROVED,

    /** Declined; a terminal state. */
    REJECTED,

    /** Withdrawn before a decision was reached; a terminal state. */
    CANCELLED;

    /**
     * @return {@code true} if no further transition is allowed from this state
     */
    public boolean isTerminal() {
        return this == APPROVED || this == REJECTED || this == CANCELLED;
    }
}
:::

Las transiciones legales forman un grafo sencillo: `DRAFT → SUBMITTED → UNDER_REVIEW →
APPROVED`, con `REJECTED` alcanzable desde `SUBMITTED` o `UNDER_REVIEW`, y `CANCELLED`
alcanzable desde cualquier estado no terminal. El agregado expresa cada arista como un
método. No hay setters públicos para `status`; la *única* forma de cambiarlo es pedir a
la solicitud que realice una transición, y cada transición comprueba primero que es
legal desde el estado actual.

El camino hacia adelante usa una guarda compartida, `requireStatus`, que lanza una
excepción si la solicitud no está en el estado de origen esperado:

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/domain/LoanApplication.java | Listado 9.4 — las transiciones hacia adelante protegen su estado de origen
    /** Moves a {@link ApplicationStatus#DRAFT} application to {@code SUBMITTED}. */
    public void submit() {
        requireStatus(ApplicationStatus.DRAFT, "submit");
        transitionTo(ApplicationStatus.SUBMITTED);
    }

    /** Moves a {@code SUBMITTED} application to {@code UNDER_REVIEW}. */
    public void startReview() {
        requireStatus(ApplicationStatus.SUBMITTED, "review");
        transitionTo(ApplicationStatus.UNDER_REVIEW);
    }

    /** Approves an application that is {@code UNDER_REVIEW}. */
    public void approve() {
        requireStatus(ApplicationStatus.UNDER_REVIEW, "approve");
        transitionTo(ApplicationStatus.APPROVED);
    }
:::

Lee `approve()` de nuevo: es imposible aprobar una solicitud que no esté `UNDER_REVIEW`,
porque el método se niega antes de tocar el estado. El modo de fallo del "¿se acordaron
todos de comprobar?" ha desaparecido — la comprobación es el método.

Las transiciones de decisión, `reject` y `cancel`, acarrean un poco más de regla: cada
una acepta más de un estado de origen legal, y cada una *requiere un motivo*, que
registra en el agregado como parte del mismo cambio atómico:

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/domain/LoanApplication.java | Listado 9.5 — las transiciones de decisión imponen un motivo obligatorio
    public void reject(String reason) {
        if (status != ApplicationStatus.SUBMITTED && status != ApplicationStatus.UNDER_REVIEW) {
            throw illegalTransition("reject");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A rejection reason is required");
        }
        this.decisionReason = reason;
        transitionTo(ApplicationStatus.REJECTED);
    }

    /**
     * Cancels an application that has not yet reached a terminal state.
     *
     * @param reason human-readable cancellation reason; required
     */
    public void cancel(String reason) {
        if (status != null && status.isTerminal()) {
            throw illegalTransition("cancel");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A cancellation reason is required");
        }
        this.decisionReason = reason;
        transitionTo(ApplicationStatus.CANCELLED);
    }
:::

`cancel` es donde `isTerminal()` se gana su sueldo: en lugar de enumerar cada estado de
origen legal, le pregunta al enum si el estado actual prohíbe cualquier cambio
posterior, y se niega si es así. Esto es el agregado y el objeto de valor colaborando —
comportamiento distribuido hacia donde vive el conocimiento.

Los tres ayudantes privados mantienen los métodos públicos declarativos. `transitionTo`
es el único punto de estrangulamiento que muta el estado, y estampa `updatedAt` en cada
cambio para que ninguna transición pueda olvidar el rastro de auditoría:

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/domain/LoanApplication.java | Listado 9.6 — un único punto de estrangulamiento para cada cambio de estado
    private void requireStatus(ApplicationStatus expected, String action) {
        if (status != expected) {
            throw illegalTransition(action);
        }
    }

    private void transitionTo(ApplicationStatus next) {
        this.status = next;
        this.updatedAt = LocalDateTime.now();
    }

    private IllegalStateException illegalTransition(String action) {
        return new IllegalStateException(
                "Cannot " + action + " a loan application in status " + status);
    }
:::

!!! note "Termino clave — raíz de agregado"
    Un **agregado** es un grupo de objetos tratados como una unidad a efectos de
    cambios, y la **raíz de agregado** es la única entidad a través de la cual fluyen
    todos los cambios. `LoanApplication` es la raíz aquí: el código externo sostiene una
    referencia a ella, nunca a su `status` directamente, y toda modificación pasa por un
    método que protege los invariantes del agregado. La raíz es la frontera de la
    consistencia.

!!! warning "Un agregado protege su estado solo si eliminas las puertas traseras"
    Un agregado rico en comportamiento es tan seguro como su camino de mutación más
    estrecho. Si dejas un `setStatus` público "para el mapeador" o "para los tests",
    cada invariante de arriba se vuelve opcional — cualquier invocador puede saltarse los
    métodos de transición y fijar un estado ilegal directamente. La disciplina consiste
    en exponer métodos de *intención* (`submit`, `approve`) y mantener privada la
    mutación cruda del estado. Donde un framework necesite acceso a los campos (Spring
    Data materializando una fila), deja que el *mapeador* sea el único puente, nunca un
    setter escrito a mano que invocas desde el código de negocio.

## Paso 3 — Proyectar la fila en un objeto de valor

El agregado almacena `requestedAmount` como un `BigDecimal` porque esa es la forma de
persistencia correcta para una columna decimal de escala fija. Pero el *dominio* quiere
un `Money`. El puente es un pequeño método de proyección en el agregado,
`requestedMoney()`, que convierte el decimal almacenado en unidades menores exactas —
multiplicando por 100 y tomando un `long` exacto, de modo que un valor que no encaje en
un número entero de céntimos falle ruidosamente en lugar de redondear en silencio:

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/domain/LoanApplication.java | Listado 9.7 — proyectar el decimal persistido en Money
    public Money requestedMoney() {
        if (requestedAmount == null) {
            return null;
        }
        return Money.of(requestedAmount.movePointRight(2).longValueExact());
    }
:::

`movePointRight(2)` desplaza `1500.00` a `150000`, y `longValueExact()` rechaza
cualquier importe que perdería precisión. El resultado fluye a través de `Money.of`, de
modo que incluso en el camino de lectura el invariante de no negatividad se reafirma. La
columna persistida y el objeto de valor del dominio permanecen sincronizados sin que
ninguno de los dos se filtre en las preocupaciones del otro.

## Paso 4 — Un mapeador aísla el dominio del cable

El agregado tiene ahora reglas, un ciclo de vida impuesto y una proyección `Money`. Lo
único que *no* debe hacer es filtrarse directamente a la API HTTP. La construcción
también es una decisión de dominio — una nueva solicitud comienza en `DRAFT`, con una
moneda normalizada — no una copia ciega campo por campo del cuerpo de la petición.

Lumen usa un mapeador MapStruct para ambas direcciones. El lado de la respuesta es una
proyección generada; el lado de la construcción está escrito a mano, precisamente
porque aplica valores por defecto del dominio en lugar de copiar campos:

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/mapper/LoanApplicationMapper.java | Listado 9.8 — el mapeador aplica valores por defecto del dominio en la construcción
@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.IGNORE
)
public interface LoanApplicationMapper {

    /**
     * Projects a persisted aggregate to its API response shape.
     *
     * @param entity the persisted application
     * @return the response DTO
     */
    LoanApplicationResponse toResponse(LoanApplication entity);

    /**
     * Builds a new, unsaved {@link LoanApplication} from a create request,
     * normalising the currency and starting the aggregate in
     * {@link com.firefly.lumen.core.domain.ApplicationStatus#DRAFT}. The
     * identifiers and timestamps are assigned by the service before persisting.
     *
     * @param request the validated create request
     * @return a transient entity ready to be submitted and saved
     */
    default LoanApplication toNewEntity(CreateLoanApplicationRequest request) {
        return LoanApplication.builder()
                .applicantId(request.applicantId())
                .requestedAmount(request.requestedAmount())
                .currency(request.currency() == null ? null : request.currency().toUpperCase())
                .termMonths(request.termMonths())
                .purpose(request.purpose())
                .build();
    }
}
:::

`toNewEntity` no fija a mano un campo de estado y no confía en que la petición
proporcione uno; la solicitud se construye y luego `submit()` (y el resto del ciclo de
vida) la lleva adelante a través de transiciones legales. El DTO nunca ve el agregado, y
el agregado nunca ve el DTO — el mapeador es la membrana entre ellos. Esta es la misma
separación que te da la fila de persistencia: el modelo de dominio es libre de
evolucionar sus interioridades sin arrastrar consigo la API ni el esquema de la base de
datos.

!!! spring "Equivalente en Spring"
    `componentModel = SPRING` le dice a MapStruct que genere la implementación como un
    bean de Spring, de modo que inyectas `LoanApplicationMapper` en un servicio
    exactamente como harías con cualquier `@Component` — sin maquinaria de Firefly de
    por medio. El patrón de mapeador es Spring puro; lo que añade el DDD es la *regla* de
    que el mapeador, no el código de negocio, posee la traducción entre filas de
    persistencia, agregados de dominio y DTOs del cable.

## Ejecútalo

Todo el sentido de mover el comportamiento al agregado es que ahora puedes probar las
*reglas* sin contexto de Spring y sin base de datos — solo los objetos. El test
construye una solicitud `DRAFT` con un builder y ejercita el ciclo de vida
directamente. Aquí están el camino feliz y la guarda de transición ilegal, codo con
codo:

::: listing core-lending-loan-origination/src/test/java/com/firefly/lumen/core/domain/LoanApplicationTest.java | Listado 9.9 — el ciclo de vida es comprobable unitariamente de forma aislada
    @Test
    void happyPathReachesApproved() {
        LoanApplication app = draft();
        app.submit();
        assertEquals(ApplicationStatus.SUBMITTED, app.getStatus());
        app.startReview();
        assertEquals(ApplicationStatus.UNDER_REVIEW, app.getStatus());
        app.approve();
        assertEquals(ApplicationStatus.APPROVED, app.getStatus());
        assertTrue(app.getStatus().isTerminal());
    }

    @Test
    void cannotApproveADraft() {
        LoanApplication app = draft();
        assertThrows(IllegalStateException.class, app::approve);
    }
:::

La proyección `Money` es igual de comprobable: un importe solicitado de `1500.00` se
proyecta a exactamente `150_000` unidades menores, y como `Money` es un record, la
aserción es un sencillo `assertEquals`:

::: listing core-lending-loan-origination/src/test/java/com/firefly/lumen/core/domain/LoanApplicationTest.java | Listado 9.10 — la proyección Money, comprobada por igualdad de valor
    @Test
    void requestedMoneyConvertsToMinorUnits() {
        LoanApplication app = draft();
        assertEquals(Money.of(150_000), app.requestedMoney());
    }
:::

Ejecuta el test del agregado desde la raíz del sample:

```text
mvn -q -pl core-lending-loan-origination -Dtest=LoanApplicationTest test
```

Deberías ver pasar los seis comportamientos:

```text
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

!!! tip "Punto de control"
    Seis tests en verde, sin Spring, sin base de datos. Ese es el dividendo de un modelo
    de dominio rico: las reglas viven en los objetos, así que las verificas con el test
    más rápido que existe — un sencillo test de JUnit que construye un agregado e invoca
    un método. Si puedes ejecutar esto en milisegundos, tus invariantes están en el
    lugar correcto.

## Lo que has construido {.recap}

- Un **objeto de valor `Money`** — un `record` inmutable que contiene unidades menores
  exactas, con el invariante de nunca-negativo impuesto por `Money.of` y reafirmado en
  cada operación aritmética, de modo que un importe que existe es siempre válido.
- Una **raíz de agregado `LoanApplication`** que posee su ciclo de vida: `submit`,
  `startReview`, `approve`, `reject` y `cancel` son las *únicas* formas de cambiar el
  estado, cada una negándose a una transición ilegal antes de tocar el estado, con
  `ApplicationStatus.isTerminal()` decidiendo cuándo no se permite ningún cambio
  posterior.
- Una **proyección `requestedMoney()`** que convierte el `BigDecimal` persistido en
  `Money` de forma exacta — `longValueExact` falla ruidosamente en lugar de redondear —
  manteniendo sincronizadas la forma de almacenamiento y el objeto de valor del dominio
  sin filtrar en ninguna dirección.
- Un **mapeador MapStruct** que es la membrana entre las filas de persistencia, el
  agregado y los DTOs del cable, aplicando valores por defecto del dominio (estado
  `DRAFT`, moneda normalizada) en la construcción para que el código de negocio nunca
  copie campos a mano.
- Un **test unitario de seis casos** que lo demuestra todo sin Spring y sin base de
  datos — la recompensa de poner el comportamiento donde viven los datos.

## Pruebalo tu mismo {.exercises}

1. **Añade un `plus` a `Money`.** Abre `Money.java` y añade un `Money plus(Money other)`
   que refleje a `minus`, encauzando el resultado a través de `Money.of`. Añade un test
   que afirme que `Money.of(100).plus(Money.of(50))` es igual a `Money.of(150)`. ¿Por
   qué `plus` no necesita una guarda extra, mientras que `minus` sí?
2. **Prohíbe el reenvío.** En `LoanApplicationTest`, añade un test que envíe un borrador
   y luego afirme que un segundo `submit()` lanza `IllegalStateException`. Confirma que
   pasa contra el `submit()` actual — y después explica qué línea del Listado 9.4 hace
   que pase.
3. **Restablece la cobertura de `startReview`.** La clase de test ejercita `submit`,
   `approve`, `reject` y `cancel`, pero `startReview` solo se alcanza en el camino
   feliz. Añade un test que invoque `startReview()` sobre un borrador fresco (antes de
   `submit`) y afirme que se rechaza. Ejecuta `-Dtest=LoanApplicationTest` y observa
   cómo el recuento sube a siete.
4. **Rompe un invariante a propósito.** Cambia temporalmente `Money.of` para eliminar la
   comprobación de negatividad, ejecuta la suite y observa que no falla nada — ninguno
   de los tests actuales construye un importe negativo. Restaura la comprobación, y
   luego añade un test que afirme que `Money.of(-1)` lanza una excepción. Por esto los
   invariantes necesitan sus *propios* tests, no solo cobertura incidental.
5. **Sigue el traspaso del borde al núcleo.** Vuelve a leer el validador `@ValidAmount`
   del Capítulo 6, y luego `Money.of` aquí. Esboza los dos lugares en los que se rechaza
   un importe negativo — en el borde HTTP y dentro del dominio — y argumenta por qué
   eliminar cualquiera de los dos es inseguro.

## Adonde ir ahora

El agregado ahora impone sus reglas, pero todavía cambia de estado a través de
invocaciones directas a métodos dentro de un único servicio. El Capítulo 10 introduce
**CQRS**: los comandos y las consultas fluyen a través de un bus, y el manejador que
aprueba una solicitud se convierte en una unidad discreta y comprobable despachada por
`@CommandHandlerComponent`. El modelo de dominio rico que has construido aquí es
exactamente lo que orquestarán esos manejadores.
