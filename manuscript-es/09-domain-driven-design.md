El capítulo 8 le dio a Lumen Lending una capa de persistencia de verdad: una fila
`loan_application`, una clave primaria UUID, columnas de auditoría y un repositorio
reactivo que la lee y la escribe. Esa fila es honesta sobre los datos, pero guarda
silencio sobre las *reglas*. Nada en un `BigDecimal requestedAmount` dice que el
importe no puede ser nunca negativo. Nada en una columna `String status` dice que no
puedes aprobar una solicitud que sigue siendo un borrador. Esas reglas viven, hoy,
desperdigadas por cualquier servicio que toque la fila — exactamente el modelo
anémico que el diseño orientado al dominio (Domain-Driven Design) nació para curar.

Este capítulo asciende la fila a un modelo de dominio *rico*. Construirás un objeto
de valor `Money` que convierte "importe no negativo en unidades menores exactas" en
una propiedad del propio tipo, y moverás el ciclo de vida de la solicitud de préstamo
— enviar, revisar, aprobar, rechazar, cancelar — *al agregado*, de modo que la única
forma de cambiar su estado sea pedirle que ejecute una transición legal. La fila de
persistencia no desaparece; permanece, y un mapeador la mantiene a distancia de la
API. Al final las reglas tienen un único hogar, y un test JUnit sencillo — sin Spring,
sin base de datos — demuestra que se cumplen.

Este es un capítulo conceptual con una recompensa ejecutable. Todo lo que diseccionas
aquí es Java y Spring Data corrientes; la aportación de Firefly es la disciplina que
lo rodea — los validadores financieros del capítulo 6 guardan el *borde*, y el modelo
de dominio que construyes ahora guarda el *núcleo*. Los dos se encuentran en el medio,
y ninguno confía en que el otro haga su trabajo. El mismo agregado que endureces aquí
es el que el servicio de núcleo en vivo (`core-lending-loan-origination`, en `:8081`)
construye, *envía* y persiste cada vez que aterriza un `POST` — así que cuando la
llamada de creación del arranque rápido vuelve marcada como `SUBMITTED`, estás viendo
exactamente uno de los métodos de transición de más abajo dispararse en código de
producción.

## La fila anémica, y por qué tiene fugas

Esta es la forma que la mayoría de los equipos entregan primero. La entidad es un saco
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

El setter no sabe — *no puede* saber — si aprobar esta solicitud es legal ahora mismo.
Así que cada llamante tiene que acordarse de comprobarlo primero, y el día que uno de
ellos se olvide, apruebas un borrador. Lo mismo ocurre con el importe:
`setRequestedAmount` almacenará tan contento un número negativo, y el invariante "el
dinero nunca es negativo" se convierte en una convención de revisión de código en
lugar de una garantía.

La cura tiene dos partes. Primero, dale al primitivo peligroso un *tipo* que no pueda
contener un valor ilegal — un **objeto de valor**. Segundo, haz que la entidad sea lo
único que puede cambiar su propio estado, a través de métodos que codifican las
transiciones legales — un **agregado raíz** (aggregate root). Los construimos en ese
orden.

!!! note "Término clave — modelo de dominio anémico vs. rico"
    Un modelo **anémico** separa los datos (entidades tontas con getters/setters) del
    comportamiento (clases de servicio que las mutan). Un modelo **rico** pone el
    comportamiento *en* la entidad, de modo que los invariantes se aplican allá donde
    vaya el objeto. El DDD favorece el modelo rico precisamente porque elimina el modo
    de fallo "¿se acordó todo el mundo de comprobar?".

!!! spring "Equivalente en Spring"
    El estilo anémico no es un requisito de Spring — es un hábito que Spring hace
    *cómodo*. `@Data`, los setters públicos y una entidad JPA/R2DBC sin comportamiento
    son la forma por defecto de los tutoriales, y funcionan justo hasta que dos
    servicios discrepan sobre cuándo puede cambiar un estado. Spring no prohíbe poner
    comportamiento en la entidad; a nada del contenedor le importa si `LoanApplication`
    tiene setters o métodos de transición. El DDD es la decisión de usar esa libertad,
    y la disposición en capas de Firefly (un `core` sistema de registro que *posee* sus
    agregados) es donde esa decisión da sus frutos.

## Paso 1 — Un objeto de valor Money con un invariante aplicado

El dinero es el objeto de valor de manual: dos importes del mismo valor son
intercambiables, no acarrea identidad y tiene un invariante duro — nunca es negativo.
Lumen lo modela como un `record` de Java que contiene un conteo entero de *unidades
menores* (céntimos), nunca un `double` ni un `BigDecimal` pelado, de modo que la
aritmética es exacta y el redondeo nunca se equivoca en silencio.

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

Como el tipo es un `record`, obtienes `equals`, `hashCode` y `toString` gratis — que
es exactamente lo que hace que dos valores `Money` de `150_000` se comparen como
iguales en un test sin ceremonia alguna. Y como `minorUnits` es un `long`, no hay
ningún importe en coma flotante que redondear.

¿Por qué unidades menores y un `long`, en lugar del obvio `BigDecimal`? Porque el
dinero se cuenta, no se mide. Un `BigDecimal` de `0.1` más `0.2` es exacto, pero aun
así acarrea una *escala* que cualquier llamante puede cambiar — `1.5`, `1.50` y
`1.500` son tres objetos `BigDecimal` distintos que no son `equals`, lo cual rompe en
silencio todos los tests de igualdad de valor que querrías escribir. Contar céntimos
enteros en un `long` colapsa esa ambigüedad: `150_000` es `150_000`, punto. La
contrapartida — que debes recordar la escala implícita de 100 — es una única
conversión que el agregado posee (Paso 3), no una decisión desperdigada por la base de
código.

El invariante da sus frutos en el momento en que haces aritmética. La resta se define
en términos de la misma factoría, de modo que un resultado que caería por debajo de
cero se rechaza en el origen en lugar de producir un saldo negativo sin sentido:

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/Money.java | Listado 9.2 — la aritmética encauzada a través del invariante
    public Money minus(Money other) {
        return Money.of(this.minorUnits - other.minorUnits);
    }
}
:::

Fíjate en lo que *no* escribiste: no hay `setMinorUnits`, no hay forma de mutar un
`Money` tras la construcción, ni camino hacia uno negativo. El invariante no es una
regla que recuerdas aplicar; es una propiedad del tipo. Cualquier código que tenga un
`Money` tiene un importe válido, y punto. Y fíjate en la *autosimilitud*: `minus` no
reimplementa la comprobación de negatividad — encauza su resultado de vuelta a través
de `Money.of`, de modo que hay exactamente un lugar donde se define el invariante y
todo camino, construcción y aritmética por igual, debe pasar por él. Ese es el
superpoder silencioso del objeto de valor: añade un `plus`, un `times`, un `percentOf`
el año que viene, y mientras cada uno se encauce a través de `of`, el invariante no se
puede olvidar.

!!! note "Término clave — objeto de valor"
    Un **objeto de valor** (value object) es un tipo de dominio definido enteramente
    por sus atributos, sin identidad propia. Dos instancias de `Money` que contienen
    `150_000` *son* el mismo dinero — no hay ningún "cuál" por el que preguntar, como
    sí lo hay para dos solicitudes de préstamo con identificadores distintos. Los
    objetos de valor son inmutables y libremente compartibles, y son el hogar natural
    de los invariantes sobre una *cantidad* (un importe, un porcentaje, un rango de
    fechas), por oposición a los invariantes sobre el *ciclo de vida de una cosa*, que
    pertenecen al agregado.

!!! spring "Equivalente en Spring"
    Aquí no hay nada específico de Firefly — `Money` es Java corriente. Esa es la
    cuestión: los objetos de valor del DDD son una técnica de *modelado*, no una
    característica del framework. Los validadores financieros de Firefly (capítulo 6)
    y este objeto de valor son complementarios: `@ValidAmount` rechaza un número malo
    en el borde HTTP antes de que llegue a tu código; `Money.of` garantiza que
    *dentro* del dominio, un importe que existe es un importe válido. Cinturón y
    tirantes, a propósito.

## Paso 2 — El agregado raíz posee su ciclo de vida

Ahora la entidad. `LoanApplication` sigue siendo un `@Table` de Spring Data R2DBC —
mantiene el `@Id` UUID, los mapeos `@Column` en snake_case y las columnas de auditoría
`created_at`/`updated_at` que construiste en el capítulo 8. Lo que cambia es que deja
de ser un saco pasivo de setters y empieza a *aplicar su propio ciclo de vida*.

El ciclo de vida es una pequeña máquina de estados, nombrada por un enum. El detalle
crucial es el último método: el enum sabe qué estados son *terminales* — estados desde
los cuales ninguna transición posterior es legal — y el agregado lo consulta antes de
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
método. No hay setters públicos para `status` *en el código de negocio*; la forma
prevista de cambiarlo es pedirle a la solicitud que ejecute una transición, y cada
transición comprueba primero que es legal desde el estado actual.

¿Por qué poner `isTerminal()` en el enum en lugar de deletrear los estados terminales
dentro del método `cancel` del agregado? Porque "¿es este un estado final?" es
conocimiento *acerca del estado*, no sobre la cancelación específicamente — y si algún
día añades una transición `refund()` o `archive()` que también deba rechazar una
solicitud terminal, querrás una definición de "terminal", no tres copias de `status ==
APPROVED || status == REJECTED || ...` desviándose entre sí. El enum posee los hechos
sobre sí mismo; el agregado posee las transiciones; ninguno duplica al otro.

El camino hacia adelante usa una guarda compartida, `requireStatus`, que lanza si la
solicitud no está en el estado de origen esperado:

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/domain/LoanApplication.java | Listado 9.4 — las transiciones hacia adelante guardan su estado de origen
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

Lee `approve()` otra vez: es imposible aprobar una solicitud que no esté
`UNDER_REVIEW`, porque el método se niega antes de tocar el estado. El modo de fallo
"¿se acordó todo el mundo de comprobar?" ha desaparecido — la comprobación es el
método.

`submit()` es la transición que ya has visto ejecutarse en vivo. En el arranque rápido
del capítulo 2, un `POST /api/v1/loan-applications` volvió con `"status": "SUBMITTED"`,
*no* `"DRAFT"` — y esta es la línea que lo produce. El `create(...)` del servicio de
núcleo construye el agregado en `DRAFT`, luego llama a `application.submit()` antes de
guardar. Así que el JSON que viste por el cable es la poscondición literal de
`submit()`: `requireStatus(DRAFT, ...)` pasó, luego se ejecutó `transitionTo(SUBMITTED)`.
El mismo `submit()` también se dispara en la cima del flujo en vivo del reactor —
`exp-lending` en `:8080` lanza una creación que la `RegisterApplicationSaga` de la capa
de dominio reenvía al núcleo, que construye, envía y persiste exactamente este agregado
como su sistema de registro.

Las transiciones de decisión, `reject` y `cancel`, acarrean un poco más de regla: cada
una acepta más de un estado de origen legal, y cada una *requiere un motivo*, que
registra en el agregado como parte del mismo cambio atómico:

::: listing core-lending-loan-origination/src/main/java/com/firefly/lumen/core/domain/LoanApplication.java | Listado 9.5 — las transiciones de decisión exigen un motivo obligatorio
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

Aquí hay dos cosas en las que merece la pena detenerse. Primero, el *orden* de las
comprobaciones: cada método valida la legalidad de la transición (la guarda de estado
incorrecto) **antes** de validar el argumento (el motivo ausente). Eso es deliberado —
un `reject` sobre una solicitud ya `APPROVED` es un error de estado
(`IllegalStateException`), no un error de entrada, independientemente de si se aportó
un motivo. La pregunta probable del lector — "¿y si llamo a `reject(null)` sobre una
solicitud aprobada?" — tiene una respuesta precisa: obtienes el error de estado, porque
el estado es el problema más fundamental.

Segundo, `reject` y `cancel` expresan sus guardas de forma distinta, y la diferencia es
significativa. `reject` *enumera* sus dos estados de origen legales de forma directa,
porque el rechazo solo es legal desde precisamente esos dos. `cancel`, en cambio,
pregunta `isTerminal()`, porque la cancelación es legal desde *cualquier* estado no
terminal — enumerar los orígenes legales significaría listar `DRAFT`, `SUBMITTED` y
`UNDER_REVIEW` y luego acordarse de ampliar esa lista cada vez que apareciera un nuevo
estado no terminal. Preguntar al enum "¿eres final?" es la formulación abierta; listar
estados es la cerrada. Cada transición elige la formulación que encaja con su regla.
Esto es el agregado y el tipo de valor (el enum) colaborando — comportamiento
distribuido a donde vive el conocimiento.

Fíjate también en la guarda `status != null` de `cancel`: un agregado recién construido
podría en principio tener un estado nulo antes de que el servicio estampe `DRAFT`, y el
método se niega a desreferenciarlo — defensivo contra el objeto a medio construir, no
solo contra la transición ilegal.

Los tres helpers privados mantienen los métodos públicos declarativos. `transitionTo`
es el único punto de estrangulamiento que muta el estado, y estampa `updatedAt` en cada
cambio de modo que ninguna transición puede olvidar el rastro de auditoría:

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

La recompensa de embudar cada mutación a través de `transitionTo` es la estampa de
`updatedAt`: es imposible cambiar el estado sin avanzar también la marca de tiempo de
auditoría, porque no hay otro camino de código que escriba `this.status`. Si un
contribuidor futuro añade una séptima transición y se olvida del campo de auditoría, no
puede — la única forma de fijar el estado es llamar al punto de estrangulamiento que
fija ambos. Esa es la misma disciplina de un-solo-lugar-para-la-regla que `Money.of` le
dio al objeto de valor, aplicada al agregado.

!!! note "Término clave — agregado raíz"
    Un **agregado** es un clúster de objetos tratados como una unidad a efectos de
    cambios, y el **agregado raíz** (aggregate root) es la única entidad a través de la
    cual fluyen todos los cambios. `LoanApplication` es la raíz aquí: el código externo
    tiene una referencia a ella, nunca a su `status` directamente, y toda modificación
    pasa por un método que protege los invariantes del agregado. La raíz es la frontera
    de la consistencia — todo lo que hay dentro de ella se actualiza junto,
    atómicamente, o no se actualiza en absoluto.

!!! note "Término clave — invariante"
    Un **invariante** es una verdad sobre el modelo que debe cumplirse en cada momento
    en que un llamante puede observarlo: *el dinero nunca es negativo*; *una solicitud
    terminal nunca vuelve a transicionar*; *cada cambio de estado avanza `updatedAt`*.
    Los invariantes no son validaciones que ejecutas bajo demanda — son propiedades que
    el tipo estructuralmente no puede violar. Todo el arte de este capítulo es mover
    reglas de "comprobado cuando alguien se acuerda" a "verdadero por construcción".

!!! warning "Un agregado guarda su estado solo si quitas las puertas traseras"
    Un agregado rico en comportamiento es tan seguro como su camino de mutación más
    estrecho. Esta entidad es una clase `@Data` de Lombok, así que *sí* genera un
    `setStatus` público — Spring Data R2DBC y el paso de construcción del servicio
    necesitan acceso a los campos. La disciplina, entonces, no es "no hay setters" sino
    "el código de negocio nunca llama al setter crudo para cambiar un estado de *ciclo
    de vida*". Aprobar, rechazar y cancelar pasan por los métodos de intención
    (`approve`, `reject`, `cancel`); los únicos llamantes legítimos de `setStatus` son
    el framework materializando una fila y el servicio sembrando el `DRAFT` inicial
    antes de `submit()`. Si dejas que código de negocio arbitrario eche mano de
    `setStatus(APPROVED)`, cada invariante de más arriba se vuelve opcional. Los métodos
    de transición son el contrato; el setter es fontanería.

## Paso 3 — Proyectar la fila en un objeto de valor

El agregado almacena `requestedAmount` como un `BigDecimal` porque esa es la forma de
persistencia adecuada para una columna decimal de escala fija. Pero el *dominio* quiere
un `Money`. El puente es un pequeño método de proyección sobre el agregado,
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

Lee esa única línea con atención, porque hace tres trabajos. `movePointRight(2)`
desplaza `1500.00` a `150000` — un desplazamiento de escala, no una multiplicación, así
que es exacto para cualquier decimal de escala fija. `longValueExact()` rechaza
entonces cualquier importe que perdería precisión: un `BigDecimal` de `1500.001` (una
décima de céntimo) lanza `ArithmeticException` en lugar de truncar en silencio a
`150000`. Finalmente el resultado fluye a través de `Money.of`, de modo que incluso en
este camino de *lectura* el invariante de no negatividad se reafirma — una fila
corrupta que de algún modo contuviera un importe negativo se detectaría aquí, no se
propagaría. La columna persistida y el objeto de valor del dominio se mantienen
sincronizados sin que ninguno de los dos se filtre en las preocupaciones del otro.

La guarda `null` responde a la pregunta obvia — ¿qué pasa con una solicitud capturada
antes de que se fijara un importe? — devolviendo `null` en lugar de lanzar, porque "aún
sin importe" es un estado legítimo de un borrador, distinto de "un importe ilegal". La
proyección solo afirma el invariante sobre importes que realmente existen.

!!! spring "Equivalente en Spring"
    Una entidad de Spring Data corriente expondría `getRequestedAmount()` devolviendo
    un `BigDecimal` crudo y se detendría ahí, dejando que cada llamante decidiera cómo
    interpretar la escala. `requestedMoney()` es el refinamiento del DDD: un *accesor de
    dominio* que devuelve un tipo de dominio. La columna de persistencia sigue siendo un
    `BigDecimal` como Spring Data lo quiere, pero el vocabulario del modelo es `Money`.
    Spring queda intacto; el agregado simplemente ofrece una lectura más rica.

## Paso 4 — Un mapeador aísla el dominio del cable

El agregado ahora tiene reglas, un ciclo de vida aplicado y una proyección `Money`. Lo
único que *no* debe hacer es filtrarse directamente a la API HTTP. La construcción
también es una decisión de dominio — una solicitud nueva empieza en `DRAFT`, con una
divisa normalizada — no una copia ciega campo por campo del cuerpo de la petición.

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

`toNewEntity` no fija un campo de estado a partir de la petición y no confía en que la
petición suministre uno; construye el agregado solo con los campos aportados por el
solicitante, normalizando la divisa a mayúsculas por el camino. El estado es entonces
una decisión de dominio tomada *fuera* del cuerpo de la petición — y aquí está el
traspaso preciso al flujo en ejecución. El `create(...)` del servicio toma la entidad
transitoria de `toNewEntity`, estampa el identificador sustituto, el número público y
las marcas de tiempo, siembra el estado a `DRAFT`, llama a `submit()`, la marca como
nueva y la guarda. Por eso la API en vivo responde `SUBMITTED`: el mapeador la
construye, el servicio la conduce a través de `submit()`, y el método de transición del
Listado 9.4 — no un campo copiado del cable — fija el estado final. El DTO nunca ve los
métodos de transición del agregado, y el agregado nunca ve las anotaciones de
validación del DTO — el mapeador es la membrana entre ellos.

Esta es la misma separación que te da la fila de persistencia: el modelo de dominio es
libre de evolucionar sus interioridades sin arrastrar consigo la API ni el esquema de
la base de datos. Tres formas — el DTO del cable
(`CreateLoanApplicationRequest`/`LoanApplicationResponse`), el agregado
(`LoanApplication`) y la fila — se encuentran solo en el mapeador y el repositorio,
nunca directamente.

!!! note "Término clave — DTO vs. agregado"
    Un **DTO** (data transfer object, objeto de transferencia de datos) es una forma
    plana y sin comportamiento para cruzar una frontera — aquí, JSON en el cable HTTP.
    El **agregado** es el objeto de dominio rico con reglas. Mantenerlos como tipos
    separados parece duplicación ("¡los dos tienen un campo `currency`!") pero compra
    independencia: la petición puede exigir `@ValidAmount` y `@NotBlank`, la respuesta
    puede omitir la bandera `newEntity`, y el agregado puede acarrear métodos de
    transición, nada de lo cual los demás necesitan conocer. El mapeador es donde la
    duplicación se *resuelve*, una vez, a propósito.

!!! spring "Equivalente en Spring"
    `componentModel = SPRING` le dice a MapStruct que genere la implementación como un
    bean de Spring, así que inyectas `LoanApplicationMapper` en un servicio exactamente
    como harías con cualquier `@Component` — sin maquinaria de Firefly de por medio.
    `unmappedTargetPolicy = IGNORE` mantiene callado al `toResponse` generado sobre los
    campos de destino que no rellena. El patrón del mapeador es Spring puro; lo que el
    DDD añade es la *regla* de que el mapeador, no el código de negocio, posee la
    traducción entre filas de persistencia, agregados de dominio y DTOs del cable.

## Paso 5 — Demuestra las reglas sin Spring y sin base de datos

Todo el sentido de mover el comportamiento al agregado es que ahora puedes probar las
*reglas* sin contexto de Spring y sin base de datos — solo los objetos. El test
construye una solicitud `DRAFT` con un builder y ejercita el ciclo de vida
directamente. Aquí están el camino feliz y la guarda de transición ilegal, una al lado
de la otra:

::: listing core-lending-loan-origination/src/test/java/com/firefly/lumen/core/domain/LoanApplicationTest.java | Listado 9.9 — el ciclo de vida es testeable de forma unitaria y aislada
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

`happyPathReachesApproved` recorre el camino completo hacia adelante `DRAFT → SUBMITTED
→ UNDER_REVIEW → APPROVED` y confirma que el destino es terminal; `cannotApproveADraft`
demuestra que la guarda rechaza el atajo ilegal. Entre los dos cubren ambas mitades del
contrato de la máquina de estados: las aristas que existen y las que no. La clase de
test acarrea cuatro casos más allá de estos dos — `rejectRequiresAReason` (un motivo en
blanco lanza `IllegalArgumentException`), `rejectRecordsReasonAndIsTerminal` (el motivo
se registra y el estado pasa a terminal), `cannotCancelATerminalApplication` (la guarda
`isTerminal()` se dispara) y el test de proyección de más abajo — para un total de
seis.

La proyección `Money` es igual de testeable: un importe solicitado de `1500.00` se
proyecta a exactamente `150_000` unidades menores, y como `Money` es un record, la
aserción es un `assertEquals` sencillo:

::: listing core-lending-loan-origination/src/test/java/com/firefly/lumen/core/domain/LoanApplicationTest.java | Listado 9.10 — la proyección Money, afirmada por igualdad de valor
    @Test
    void requestedMoneyConvertsToMinorUnits() {
        LoanApplication app = draft();
        assertEquals(Money.of(150_000), app.requestedMoney());
    }
:::

Ese único `assertEquals` es el `equals` del objeto de valor y la exactitud de la
proyección, ambos demostrados en una línea — no hay ninguna escala de `BigDecimal` con
la que andar, porque `Money` ya ha colapsado el importe a un `long`.

Ejecuta el test del agregado desde la raíz del ejemplo:

```text
$ mvn -q -pl core-lending-loan-origination -Dtest=LoanApplicationTest test
```

Deberías ver pasar los seis comportamientos:

```text
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- in com.firefly.lumen.core.domain.LoanApplicationTest
[INFO] BUILD SUCCESS
```

Esos seis son un subconjunto estricto de los dieciocho que ejecuta todo el módulo
`core` (el `Tests run: 18` del arranque rápido), que a su vez forma parte de los
treinta y tres del reactor a través de `core` (18), `domain` (6) y `exp` (9). Los tests
del agregado son los más rápidos de todos, porque no tocan más que los objetos.

!!! tip "Punto de control"
    Seis tests en verde, sin Spring, sin base de datos. Ese es el dividendo de un modelo
    de dominio rico: las reglas viven en los objetos, así que las verificas con el test
    más rápido que existe — un test JUnit sencillo que construye un agregado y llama a
    un método. Si puedes ejecutar esto en milisegundos, tus invariantes están en el
    lugar correcto. El *mismo* agregado, el *mismo* `submit()`, se ejecuta luego dentro
    del servicio `core` en vivo en `:8081` (arráncalo con `mvn spring-boot:run`, o como
    el `java -jar` reempaquetado según `samples/lumen-lending/README.md`) — el test
    unitario y el `SUBMITTED` que viste por el cable son dos vistas de un mismo modelo.

## Lo que has construido {.recap}

- Un **objeto de valor `Money`** — un `record` inmutable que contiene unidades menores
  exactas, con el invariante de nunca negativo aplicado por `Money.of` y reafirmado en
  cada operación aritmética, de modo que un importe que existe siempre es válido. El
  almacenamiento en `long` de unidades menores da una igualdad de valor limpia donde
  una escala de `BigDecimal` te traicionaría.
- Un **agregado raíz `LoanApplication`** que posee su ciclo de vida: `submit`,
  `startReview`, `approve`, `reject` y `cancel` son las formas previstas de cambiar el
  estado, cada una rechazando una transición ilegal antes de tocar el estado, con
  `ApplicationStatus.isTerminal()` decidiendo cuándo no se permite ningún cambio
  adicional y `transitionTo` como el único punto de estrangulamiento que además estampa
  `updatedAt`.
- Una **proyección `requestedMoney()`** que convierte el `BigDecimal` persistido en
  `Money` de forma exacta — `movePointRight(2)` y luego `longValueExact` fallan
  ruidosamente en lugar de redondear — y reafirma el invariante de no negatividad
  incluso en el camino de lectura.
- Un **mapeador MapStruct** que es la membrana entre las filas de persistencia, el
  agregado y los DTOs del cable, aplicando valores por defecto del dominio (divisa
  normalizada, inicio en `DRAFT`) en la construcción para que el servicio pueda conducir
  el agregado a través de `submit()` en lugar de copiar un estado del cable — la razón
  misma por la que la creación en vivo devuelve `SUBMITTED`.
- Un **test unitario de seis casos** que demuestra todo ello sin Spring y sin base de
  datos — el subconjunto rápido de los dieciocho del módulo y los treinta y tres del
  reactor.

## Pruébalo tú mismo {.exercises}

1. **Añade un `plus` a `Money`.** Abre `Money.java` y añade un `Money plus(Money other)`
   que refleje a `minus`, encauzando el resultado a través de `Money.of`. Añade un test
   que afirme que `Money.of(100).plus(Money.of(50))` es igual a `Money.of(150)`. ¿Por
   qué `plus` no necesita una guarda extra, mientras que `minus` sí — y por qué encauzar
   a través de `Money.of` sigue siendo el hábito correcto incluso cuando la guarda no se
   puede disparar?
2. **Prohíbe el reenvío.** En `LoanApplicationTest`, añade un test que envíe un borrador
   y luego afirme que un segundo `submit()` lanza `IllegalStateException`. Confirma que
   pasa contra el `submit()` actual — luego explica qué línea del Listado 9.4 hace que
   pase (pista: `requireStatus(DRAFT, ...)` contra una solicitud ahora `SUBMITTED`).
3. **Restablece la cobertura de `startReview`.** La clase de test ejercita `submit`,
   `approve`, `reject` y `cancel`, pero `startReview` solo se toca en el camino feliz.
   Añade un test que llame a `startReview()` sobre un borrador recién creado (antes de
   `submit`) y afirme que se rechaza. Ejecuta `-Dtest=LoanApplicationTest` y observa cómo
   el conteo sube a siete.
4. **Rompe un invariante a propósito.** Cambia temporalmente `Money.of` para que omita
   la comprobación de negatividad, ejecuta la suite y observa que no falla nada — ninguno
   de los tests actuales construye un importe negativo. Restaura la comprobación, luego
   añade un test que afirme que `Money.of(-1)` lanza. Por eso los invariantes necesitan
   sus *propios* tests, no solo cobertura incidental.
5. **Rastrea el traspaso del borde al núcleo.** Vuelve a leer el `@ValidAmount` del
   capítulo 6 en `CreateLoanApplicationRequest`, luego `Money.of` aquí. Un importe
   negativo se rechaza en *dos* lugares — en el borde HTTP (400, RFC 7807) y dentro del
   dominio (`IllegalArgumentException`). Esboza ambos, luego argumenta por qué eliminar
   cualquiera de los dos es inseguro aunque el otro siga disparándose.
6. **Sigue el `SUBMITTED` hasta el final.** Abre
   `core-lending-loan-origination/.../service/LoanApplicationService.java` y encuentra el
   método `create(...)`. Identifica la línea exacta que llama a `submit()`, luego
   conéctala con el JSON `"status": "SUBMITTED"` del arranque rápido del capítulo 2.
   ¿Cuál cambiaría la respuesta: editar `toNewEntity` o editar `submit()`?

## Adónde ir ahora

El agregado ahora aplica sus reglas, pero todavía cambia de estado mediante llamadas
directas a métodos dentro de un servicio. El capítulo 10 introduce **CQRS**: los
comandos y las consultas fluyen a través de un bus, y el manejador que aprueba una
solicitud se convierte en una unidad discreta y testeable despachada por
`@CommandHandlerComponent`. El modelo de dominio rico que construiste aquí es
exactamente lo que esos manejadores orquestarán — y el mismo agregado que la
`RegisterApplicationSaga` de la capa de dominio ya conduce a través del reactor en vivo.
