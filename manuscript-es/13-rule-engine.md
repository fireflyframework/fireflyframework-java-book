Lumen Lending toma una decisión de crédito, pero no *razona* sobre ella. Vuelve
sobre el corte que has construido y encuentra el momento en que se fija el precio de un
préstamo. Está en el `LoanOriginationService.submitApplication` de la capa de dominio, que toma un `amount`
y un `annualRateBps` como argumentos planos y los pasa directamente a un
`ProposeOfferCommand`. A partir de ahí, el paso `proposeOffer` de la saga despacha ese
comando en el `CommandBus`, y `ProposeOfferHandler` registra la oferta llamando a
`client.proposeOffer(loanApplicationId, amount, annualRateBps)` — pasando el mismo
tipo que recibió, sin tocar. Nada a lo largo de ese camino calcula si el solicitante
*cumple los requisitos*, ni a qué tipo. La lógica de elegibilidad y de fijación de precio es, en efecto, una
constante suministrada a mano — la heurística más simple posible, decidida por quien llama y
enhebrada a través del comando hasta el cliente.

Eso es honesto para un corte didáctico, y es exactamente el tipo de decisión que, en
una plataforma de préstamos real, *no* quieres soldar en Java y volver a desplegar cada vez
que riesgo cambia un umbral. La política de crédito se mueve a su propio ritmo: un regulador endurece
un techo de deuda sobre ingresos, un equipo de producto lanza un tipo promocional, una señal de fraude recibe un
nuevo peso. Codificar eso en código compilado convierte cada cambio de política en una release. Este
capítulo trata sobre el componente de Firefly construido precisamente para esta costura —
**`fireflyframework-rule-engine`** — y sobre *dónde* encaja en Lumen Lending para que
el paso de decisión se convierta en datos, redactados en YAML, versionados en una tabla y
evaluados de forma reactiva.

Una nota de honestidad por adelantado, porque este es un capítulo honesto. El reactor de préstamos
**no cablea el motor de reglas**. Aquí no hay ningún corte `::: listing` que la compilación
verifique, y no hay ningún comando `mvn` que ejecutar al final — el código de abajo es
*ilustrativo*, mostrado en bloques cercados estándar, para que puedas ver cómo se redactaría y evaluaría una regla
de elegibilidad crediticia o de fijación de precio. Todo lo descrito es comportamiento real del motor,
extraído del propio DSL y de la capa de servicio de `fireflyframework-rule-engine`; lo que *no* es
real es cualquier afirmación de que Lumen lo llame hoy. Las piezas móviles reales sobre las que este capítulo
habla *en torno* — el `ProposeOfferCommand` y su `ProposeOfferHandler` (el corte CQRS del
Capítulo 10) y la `RegisterApplicationSaga` que los dirige (Capítulo 18) — son
genuinas y verificadas; el motor de reglas es la pieza que *encajaría* entre ellas. Lee
esto como el mapa de dónde pertenece la toma de decisiones, no como un recorrido por código ya implantado.

!!! note "Término clave — motor de reglas"
    Un **motor de reglas** evalúa la política de negocio expresada como *datos* — reglas que puedes
    redactar, versionar y cambiar sin recompilar el servicio. El de Firefly es un
    **motor de evaluación de expresiones** sin estado y reactivo: le entregas una regla YAML y
    un mapa de entradas, y devuelve salidas calculadas más un resultado de condición y
    metadatos de auditoría. *No* es un motor de inferencia al estilo de Drools — no hay memoria de
    trabajo, ni base de hechos, ni encadenamiento hacia adelante. Cada evaluación es una llamada a función
    independiente sobre un mapa de entrada, que es exactamente lo que es una decisión de crédito.

## Dónde vive la heurística, y dónde iría el motor

Encuentra primero la costura; el resto del capítulo la rellena. En la capa de dominio el tipo
nace como argumento: `LoanOriginationService.submitApplication` acepta `annualRateBps`
y lo empaqueta en un `ProposeOfferCommand`. Ese comando cabalga a través del paso `proposeOffer`
de la saga (Capítulo 18) y aterriza en `ProposeOfferHandler` (el corte CQRS del
Capítulo 10), cuyo único trabajo es reenviar el número al núcleo a través de la costura del SDK:

```java
// Today, in the slice: the offer's amount and rate arrive as arguments.
public Mono<SagaResult> submitApplication(String applicantName, long amount, int annualRateBps) {
    StepInputs inputs = StepInputs.builder()
        // ... root + applicant steps ...
        .forStepId(RegisterApplicationSaga.STEP_PROPOSE_OFFER,
            new ProposeOfferCommand(amount, annualRateBps))   // <-- hand-supplied heuristic
        .build();
    return sagaEngine.execute(RegisterApplicationSaga.SAGA_NAME, inputs);
}
```

El manejador real es lo más fino que puede ser — no decide nada, retransmite:

```java
// Today, in the slice (verbatim shape): the handler just forwards the passed-in rate.
@Override
protected Mono<UUID> doHandle(ProposeOfferCommand command) {
    return client.proposeOffer(command.getLoanApplicationId(),
        command.getAmount(), command.getAnnualRateBps());   // rate came in as an argument
}
```

El paso `proposeOffer` es, por tanto, una *decisión* disfrazada: afirma que un solicitante
es elegible y fija el precio del préstamo, pero la política detrás de esos números no vive en ningún sitio del
código — fue decidida por quienquiera que llamase a `submitApplication`. Cambia por el motor de reglas
y el mismo manejador se vuelve explícito y configurable. Conceptualmente, adquiere una
dependencia del motor y le pide una decisión antes de llamar al cliente:

```java
// Illustrative: the decision step consults a stored rule instead of a constant.
@CommandHandlerComponent
public class ProposeOfferHandler extends CommandHandler<ProposeOfferCommand, UUID> {

    private final LoanOriginationClient client;
    private final RulesEvaluationService rules;   // from fireflyframework-rule-engine

    @Override
    protected Mono<UUID> doHandle(ProposeOfferCommand command) {
        var request = new RuleEvaluationByCodeRequestDTO();
        request.setRuleDefinitionCode("credit-eligibility");        // a stored, versioned rule
        request.setInputData(Map.of(
            "creditScore", command.getCreditScore(),
            "annualIncome", command.getAnnualIncome(),
            "requestedAmount", command.getAmount()));
        return rules.evaluateRuleByCodeWithAudit(request, /* exchange */ null)
            .flatMap(result -> {
                var out = result.getOutputData();                  // computed outputs
                if (!"APPROVED".equals(out.get("approval_status"))) {
                    return Mono.error(new DeclinedException(out.get("reason")));
                }
                int rateBps = ((Number) out.get("annual_rate_bps")).intValue();
                return client.proposeOffer(command.getLoanApplicationId(),
                    command.getAmount(), rateBps);                 // rate now comes from policy
            });
    }
}
```

La forma es la lección. La elegibilidad y la fijación de precio dejan de ser argumentos y se convierten en las
*salidas* de una regla llamada `credit-eligibility`, evaluada reactivamente dentro del paso
de la saga. Un analista de riesgos puede cambiar el umbral o la banda de tipos editando YAML y
volviendo a almacenar la regla — sin cambio de código, sin redespliegue. El resto de este capítulo muestra cómo
se redacta, almacena, evalúa, audita y opera esa regla.

Mantente honesto sobre la brecha, eso sí. El `ProposeOfferCommand` de hoy lleva solo `amount`,
`annualRateBps` y el `loanApplicationId` inyectado — el `command.getCreditScore()` y el
`command.getAnnualIncome()` del manejador ilustrativo asumen un comando *más rico*
que el corte no tiene. Cablear el motor de verdad significa dos cambios, no uno:
llevar las señales de crédito al comando (y subir por el canal del BFF que las suministra),
*y* reemplazar la llamada de retransmisión por la evaluación por código de arriba. El capítulo
muestra la segunda mitad, la más difícil; la primera es fontanería ordinaria que los capítulos de capas anteriores
ya enseñaron.

!!! spring "Equivalente en Spring"
    No hay un equivalente en Spring puro que te dé esto gratis. En Spring Boot
    estándar tendrías que confeccionar a mano una abstracción de política — un `@Service` que lea umbrales desde
    `@ConfigurationProperties`, o un DSL propio — y reconstruir el parser, el cacheo, la
    auditoría y la validación tú mismo. Ese es el mismo impuesto empresarial que nombró el Capítulo 1:
    cada equipo reinventa la toma de decisiones ligeramente distinta. El motor de reglas de Firefly es una
    respuesta reactiva precableada, activada al añadir `fireflyframework-rule-engine-core`
    al classpath.

## Redactar una regla de elegibilidad crediticia en el DSL

Una regla es un documento YAML con una pequeña columna vertebral obligatoria — `name`, `description`,
`inputs`, `output` — y una sección de lógica. Aquí está la decisión de elegibilidad que el paso
`proposeOffer` de Lumen consultaría, escrita de principio a fin:

```yaml
# credit-eligibility.yaml — authored by a risk analyst, stored by code
name: "Credit Eligibility"
description: "Decide loan eligibility and price the rate from credit score and income"
inputs:
  creditScore:
    type: number
    default: 0
  annualIncome:
    type: number
    default: 0
  requestedAmount:
    type: number
    default: 0
output: {approval_status: text, annual_rate_bps: number, reason: text}

constants:
  - code: MIN_SCORE
    defaultValue: 650
  - code: MIN_INCOME
    defaultValue: 50000

when:
  - creditScore at_least MIN_SCORE
  - annualIncome at_least MIN_INCOME
  - requestedAmount is_positive
then:
  - set approval_status to "APPROVED"
  - run annual_rate_bps as if_else(creditScore at_least 750, 1100, 1450)
  - set reason to "Meets score and income thresholds"
else:
  - set approval_status to "DECLINED"
  - set annual_rate_bps to 0
  - set reason to "Below minimum score or income"
```

Léela como la política que es. El bloque `when:` es una lista de condiciones, unidas por AND:
la puntuación debe ser **al menos** la constante `MIN_SCORE`, los ingresos al menos
`MIN_INCOME`, y el importe solicitado positivo. Cuando todas se cumplen, las acciones del `then:`
se disparan — establecen el estado, calculan una banda de tipos con la función `if_else` en línea (1100
puntos básicos para crédito prime, 1450 en caso contrario) y registran un motivo. Cuando alguna falla,
`else:` deniega. Las tres variables de salida nombradas en `output:` son lo que el motor
devuelve a tu manejador.

Tres convenciones de nomenclatura conllevan significado, y el motor las lee automáticamente.
**Las variables de entrada son `camelCase`** (`creditScore`) y provienen del mapa de entrada que
pasas. **Las constantes son `UPPER_CASE`** (`MIN_SCORE`) y se resuelven desde el bloque
`constants:` de la regla o desde el almacén de constantes compartido — que es cómo un umbral se convierte en un
valor que riesgo puede cambiar de forma centralizada, no un literal enterrado en cien reglas. **Las variables
calculadas son `snake_case`** (`annual_rate_bps`) y se crean durante la evaluación.

!!! note "Término clave — condiciones de comparación, lógicas y de expresión"
    El DSL ofrece tres sabores de condición. Las condiciones de **comparación** prueban un valor
    contra otro con más de 30 operadores — `at_least`, `equals`, `in_list`,
    `is_positive`, `is_credit_score`, `matches`, etc. Las condiciones **lógicas**
    las combinan con `and`, `or` y `not`, con paréntesis para agrupar:
    `(creditScore at_least 650 AND annualIncome greater_than 40000) OR hasGuarantor equals true`.
    Las condiciones de **expresión** evalúan aritmética y llamadas a función en línea —
    `debt_ratio at_most 0.4` donde `debt_ratio` se acababa de `calculate`. Una política de crédito
    usa las tres.

El vocabulario de acciones es igual de expresivo. `set` asigna; `calculate` evalúa aritmética
pura (`+ - * / % **`) con verdadera precedencia de operadores; `run` invoca una función.
El motor incluye una profunda biblioteca incorporada — matemática (`max`, `round`, `sqrt`),
estadística (`avg`, `sum`), de cadenas (`upper`, `format`, `concat`), de fechas (`datediff`,
`calculate_age`) y una suite financiera hecha a medida exactamente para este dominio:

```yaml
# Illustrative then: fragment — pricing and risk math the engine ships built in
then:
  - calculate monthly_income as annualIncome / 12
  - run debt_ratio as debt_to_income_ratio(monthlyDebt, monthly_income)
  - run payment as calculate_loan_payment(requestedAmount, annual_rate_bps, termMonths)
  - run ltv as loan_to_value(requestedAmount, collateralValue)
  - if debt_ratio greater_than 0.43 then circuit_breaker "DTI_EXCEEDED"
```

`debt_to_income_ratio`, `calculate_loan_payment` y `loan_to_value` son funciones incorporadas reales
del motor — la matemática de préstamos que de otro modo reimplementarías ya está ahí. La
última línea muestra la acción **circuit_breaker**: una parada en seco que termina la evaluación
antes de tiempo con un resultado etiquetado, el equivalente para el autor de la regla a "denegar inmediatamente,
sin más preguntas."

!!! warning "`if_else` evalúa ambas ramas"
    El DSL no tiene un operador `? :` al estilo de C; usa la función `if_else(condition, then, else)`.
    Pero *no* hace cortocircuito — **ambas** expresiones, la del then y la del else,
    se evalúan con avidez, y luego se selecciona una. Si una rama llama a una
    función costosa o a un `rest_get`, se ejecuta independientemente de la condición. Mantén
    las expresiones de las ramas baratas, o aísla el trabajo costoso detrás de un `when:`/`then:` separado
    en lugar de enterrarlo en un `if_else`.

## Cómo se almacenan y versionan las reglas

Una regla redactada como una cadena está bien para una prueba unitaria, pero una plataforma de préstamos necesita reglas
que operaciones pueda gestionar. El motor las persiste. El módulo de modelos define una
entidad R2DBC `RuleDefinition` respaldada por una tabla `rule_definitions`, y la capa de
servicio expone CRUD sobre ella. La forma de la tabla te dice qué significa "gestionada" aquí:

```sql
-- from fireflyframework-rule-engine-models: the stored-rule schema
CREATE TABLE rule_definitions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(100) NOT NULL UNIQUE,   -- stable handle: "credit-eligibility"
    name VARCHAR(200) NOT NULL,
    yaml_content TEXT NOT NULL,          -- the DSL document itself
    version VARCHAR(20),                 -- semantic version of this rule
    is_active BOOLEAN NOT NULL DEFAULT true,
    tags VARCHAR(500),
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

Dos columnas importan más para la toma de decisiones. **`code`** es el identificador estable y único por el que tu
servicio evalúa — `credit-eligibility` — para que el paso de la saga nunca codifique YAML a fuego; nombra
una regla que la plataforma posee. **`version`** lleva la versión semántica de la regla, e
`is_active` señala qué definiciones pueden evaluarse, con `created_by`/`updated_by`
y marcas de tiempo que registran *quién* cambió la política y *cuándo*. Juntas, estas hacen que una regla
de crédito sea auditable como un artefacto gobernado: puedes ver que `credit-eligibility` está en la
versión `2.3.0`, quién la editó por última vez, y si está en vivo.

Ahí es donde el versionado del motor de reglas honestamente se detiene. Almacenar una fila por `code`
te da la definición *actual* más su etiqueta de versión y su rastro de autoría; es un
almacén gobernado, no un sistema de control de versiones completo con un historial de cada cuerpo YAML
anterior. Si necesitas revertir a la política del trimestre pasado o comparar dos revisiones, eso lo
añades por encima — mantén las reglas en Git como fuente de verdad y almacena la activa, o modela
tu propia tabla de historial. El motor te da el identificador estable, la etiqueta de versión y las
columnas de autoría; la gobernanza circundante es tuya para diseñar.

!!! spring "Equivalente en Spring"
    `RuleDefinition` es una entidad Spring Data R2DBC plana y el repositorio es un repositorio
    reactivo plano — la misma persistencia que conociste en el Capítulo 8. Nada exótico:
    el motor almacena reglas igual que Lumen almacena un `loan_application`. Lo que añade encima
    es el parser, el evaluador y la caché que convierten una cadena YAML almacenada en una
    decisión ejecutable.

## Evaluar reactivamente — directa, por código y por lotes

La evaluación es reactiva de principio a fin, devolviendo un `Mono`, que es por lo que cae limpiamente
en la pipeline de un paso de saga. Hay tres maneras de entrar.

El camino de más bajo nivel es el motor **directo**: inyecta `ASTRulesEvaluationEngine` y
evalúa una cadena YAML con un mapa de entrada, sin base de datos involucrada. Este es el camino de la prueba unitaria
— práctico para probar una regla antes de almacenarla:

```java
// Illustrative: evaluate a YAML rule string directly, no persistence
Map<String, Object> inputs = Map.of(
    "creditScore", 720,
    "annualIncome", 84_000L,
    "requestedAmount", 15_000L);

Mono<ASTRulesEvaluationResult> result = engine.evaluateRulesReactive(ruleYaml, inputs);
// result.isSuccess(), result.getOutputData(), result.getExecutionTimeMs()
```

El camino de producción es **por código**: evalúa una regla almacenada por su `code` a través de
`RulesEvaluationService.evaluateRuleByCodeWithAudit`. Esta es la llamada que haría el
`ProposeOfferHandler` de arriba — carga `credit-eligibility` desde el almacén,
la evalúa contra el mapa de entrada y (como promete el nombre del método) escribe un registro de
auditoría. Sobre REST la misma superficie es `POST /api/v1/rules/evaluate/by-code`:

```bash
# Illustrative: ask the stored credit rule for a decision
curl -X POST http://localhost:8080/api/v1/rules/evaluate/by-code \
  -H 'Content-Type: application/json' \
  -d '{
        "ruleDefinitionCode": "credit-eligibility",
        "inputData": { "creditScore": 720, "annualIncome": 84000, "requestedAmount": 15000 },
        "includeDetails": true
      }'
```

El tercer camino es por **lotes**. Una plataforma de préstamos no solo decide una solicitud
a la vez; vuelve a puntuar una cartera de préstamos cuando cambia la política, o precalifica un segmento de
marketing de la noche a la mañana. `BatchRulesEvaluationService` (y `POST /api/v1/rules/batch/evaluate`)
puntúa muchos conjuntos de entrada contra una regla en una sola llamada, devolviendo resultados por fila más
estadísticas agregadas, con endpoints acompañantes de `validate`, `statistics` y `health`.
La misma regla `credit-eligibility` que fija el precio de una nueva solicitud puede reordenar diez
mil existentes — sin un trabajo por lotes a medida.

!!! spring "Equivalente en Spring"
    Como cada punto de entrada devuelve un `Mono`, el motor se compone en la misma
    pipeline reactiva que Lumen ya ejecuta. El paso `proposeOffer` en vivo hace
    `commandBus.send(command)` y encadena operadores aguas abajo sobre el resultado; un
    paso respaldado por reglas simplemente haría `.flatMap` de `evaluateRuleByCodeWithAudit(...)` en esa
    misma cadena — sin puente bloqueante, sin `block()`, sin traspaso de hilos. Un servicio de
    decisión de Spring puro que devolviera un valor plano (o peor, que bloqueara en una lectura de base de datos)
    forzaría exactamente el tipo de costura reactiva contra la que advirtió el Capítulo 5. El motor
    habla `Mono` de forma nativa, así que desaparece dentro del flujo.

!!! note "Término clave — AST"
    El motor no interpreta el texto YAML en cada llamada. Parsea cada regla una vez
    a un **Árbol de Sintaxis Abstracta** — un árbol tipado de nodos de condición, expresión y acción
    — y evalúa *ese*. El AST es lo que hace la evaluación rápida y con seguridad de tipos,
    y es la cosa más caliente que el motor cachea (siguiente sección). Cuando la referencia del DSL
    habla de "parseo basado en AST", este árbol es lo que significa: tu YAML se convierte en
    estructura, y la estructura es lo que se ejecuta.

## Cachear definiciones y ASTs

Parsear YAML y cargar una fila de PostgreSQL en cada decisión sería un desperdicio cuando
la misma regla `credit-eligibility` se dispara miles de veces por minuto. El motor cachea
a través de la abstracción de caché de Firefly (Capítulo 20) — Caffeine por defecto, Redis enchufable
para despliegues distribuidos — bajo el prefijo `firefly.rules.cache`. Importan cuatro cachés:

```yaml
# Illustrative: the rule engine's cache configuration (real default keys)
firefly:
  rules:
    cache:
      provider: CAFFEINE          # set to REDIS for distributed evaluation
      caffeine:
        ast-cache:                # parsed ASTs — the hottest cache
          maximum-size: 1000
          expire-after-write: 2h
        rule-definitions-cache:   # stored definitions loaded from the database
          maximum-size: 200
          expire-after-write: 10m
        constants-cache:          # shared constants
        validation-cache:         # validation results
```

La **caché de AST** es la que se gana su sustento: una regla se parsea una vez y el árbol se
reutiliza, así que el coste por decisión es evaluación, no reparseo. La **caché de definiciones**
mantiene fuera de la base de datos las reglas almacenadas calientes. Como funciona sobre la abstracción de caché
de Firefly, cambiar de un Caffeine en proceso a un Redis compartido — para que cada instancia de la
flota evalúe la misma política cacheada — es el cambio de una sola propiedad que conoces en el
Capítulo 20: pon `firefly.rules.cache.provider` a `REDIS`.

!!! warning "Los ASTs cacheados implican que las ediciones de reglas almacenadas no son instantáneas"
    La otra cara del cacheo es la obsolescencia. Edita `credit-eligibility` en el almacén y las
    instancias que retienen el AST antiguo siguen usándolo hasta que la entrada de caché expira (por defecto,
    dos horas) o es desalojada. Para política que debe cambiar *ahora* — un umbral ordenado por un regulador
    — planifica un desalojo de caché explícito o un TTL más corto en tu runbook
    operativo, en lugar de asumir que un re-almacenamiento surte efecto en la siguiente petición.

## Validación antes de almacenar

Una regla mala debería detectarse en el momento de redacción, no cuando la solicitud de un cliente
choca con ella. El motor expone la validación de YAML como su propio endpoint — `POST /api/v1/validation/yaml`
(y un `/syntax` más ligero) — que comprueba tanto la sintaxis del DSL como las convenciones de nomenclatura
(entradas `camelCase`, constantes `UPPER_CASE`, variables calculadas `snake_case`) antes de que una
regla se evalúe siquiera. La superficie CRUD de definiciones de reglas valida también al escribir, así que una
`credit-eligibility` malformada no puede almacenarse activa.

```bash
# Illustrative: validate a rule's YAML before storing it
curl -X POST http://localhost:8080/api/v1/validation/yaml \
  -H 'Content-Type: application/json' \
  -d '{ "yamlContent": "name: ...\ninputs: [creditScore]\n..." }'
```

Esta es la puerta que hace segura la política redactada por analistas. Un analista de riesgos edita YAML, el
endpoint de validación reporta un operador mal escrito o una variable con mayúsculas/minúsculas incorrectas, y la regla
se corrige *antes* de que pueda denegar a un solicitante real por la razón equivocada. Los resultados de
validación se cachean ellos mismos, así que revalidar una regla sin cambios es barato.

## El rastro de auditoría

Cada decisión de crédito es un evento regulado: debes poder decir, meses después, por qué un
solicitante específico fue aprobado o denegado, contra qué regla, con qué entradas. El
motor lo registra automáticamente. El módulo de modelos define una entidad `AuditTrail` y
`AuditTrailService` escribe un registro por evaluación — que es exactamente por qué el
punto de entrada de producción se llama `evaluateRuleByCodeWithAudit`. El rastro es consultable por
entidad, usuario o tipo de operación, con estadísticas y limpieza, y aflora sobre
`/api/v1/audit/trails`.

Para una decisión de préstamo, el valor es concreto: el registro de auditoría ata la decisión a la
regla `code` y `version` que la produjeron, los datos de entrada y el resultado. Cuando un
auditor pregunta "¿por qué se denegó la solicitud X en marzo?", la respuesta es una consulta, no una
excavación arqueológica por los logs. Esta es la capacidad que hace *defendible* una decisión configurable:
cambia la política libremente, pero nunca pierdas el registro de qué política decidió
qué caso.

!!! spring "Equivalente en Spring"
    El modelo de errores RFC 7807 del Capítulo 6 y este rastro de auditoría son preocupaciones
    transversales complementarias: uno uniformiza los fallos en el borde HTTP, el otro hace
    *trazables* las decisiones en la capa de datos. Ninguno es algo que querrías que cada
    equipo reinventara — y ambos vienen precableados, el recurrente trato de Firefly.

## Compilar una regla a Python

La característica más sorprendente es la portabilidad. `PythonCodeGenerator` /
`PythonCompilationService` emiten una *función Python equivalente* para una regla almacenada,
expuesta en `POST /api/v1/python/compile` y cacheada como todo lo demás. El mismo
YAML de `credit-eligibility` que el motor Java evalúa reactivamente puede compilarse a una
función Python autónoma que computa la decisión idéntica.

¿Por qué querría esto una plataforma de préstamos? Dos razones. Primera, **ejecución offline** — un
equipo de ciencia de datos que hace backtesting de una nueva política de crédito contra años de solicitudes
históricas quiere ejecutarla en un notebook, en pandas, a escala, sin levantar el
servicio Java. Segunda, **portabilidad a runtime externo** — una plataforma de servido de modelos, un
trabajo de ETL o el entorno de un socio que habla Python pueden ejecutar la *misma* política que el
servicio de producción usa, desde una única fuente redactada. El YAML sigue siendo la única fuente de
verdad; el Python es un artefacto generado que mantiene un runtime no JVM en sincronía con la
regla en vivo.

Aquí es genuinamente donde la tesis del motor de "la regla como datos, no como código" rinde frutos: una política
de crédito redactada una vez se convierte en una decisión reactiva en producción *y* en una función Python
para análisis, sin una segunda implementación que se desvíe.

## Lo que has aprendido {.recap}

- El corte de Lumen Lending toma su decisión de crédito con una **heurística suministrada a mano** —
  `LoanOriginationService` empaqueta un `amount` y un `annualRateBps` fijos en
  `ProposeOfferCommand`, el paso de saga `proposeOffer` lo despacha, y
  `ProposeOfferHandler` retransmite el tipo directamente al cliente del núcleo. El reactor
  **no** cablea el motor de reglas; este capítulo es el mapa de dónde encajaría.
- `fireflyframework-rule-engine` es un **motor de evaluación de expresiones sin estado y
  reactivo**: le das una regla YAML y un mapa de entrada, devuelve salidas calculadas, un
  resultado de condición y metadatos de auditoría. No es Drools — sin memoria de trabajo, sin
  inferencia — lo que conviene a una decisión de crédito, una llamada a función independiente sobre un
  payload.
- Las reglas se redactan en un **DSL YAML** con condiciones de comparación, lógicas y de expresión;
  un rico vocabulario de acciones (`set`, `calculate`, `run`, `circuit_breaker`);
  y funciones financieras incorporadas (`calculate_loan_payment`, `debt_to_income_ratio`).
  Las convenciones de nomenclatura — entradas `camelCase`, constantes `UPPER_CASE`, valores calculados `snake_case`
  — conllevan significado que el motor lee automáticamente.
- Las reglas se **almacenan y versionan** como filas `RuleDefinition` indexadas por un `code` estable,
  se evalúan **reactivamente** de tres maneras (directa, por código, por lotes), se **cachean** como ASTs
  parseados y definiciones a través de la abstracción de caché de Firefly, se **validan** en un endpoint
  dedicado, y se **auditan** en cada evaluación — e incluso pueden **compilarse a Python**
  para puntuación offline y runtimes externos.
- El punto de enchufe del paso de decisión en Lumen es el **`ProposeOfferHandler`** — hoy una
  retransmisión de una sola línea que reenvía el `annualRateBps` recibido al cliente del núcleo.
  Reemplazar esa línea por una evaluación por código de una regla `credit-eligibility` (y
  enriquecer `ProposeOfferCommand` para que lleve las señales de crédito) convierte la política implícita
  en datos gobernados y cambiables — sin redespliegue cuando riesgo mueve un umbral.

## Pruébalo tú mismo {.exercises}

Estos ejercicios redactan y razonan sobre reglas en el DSL. No hay módulo reactor contra el que
ejecutarlas — el motor de reglas no está cableado en Lumen — así que trata el YAML como el
entregable, comprobando tu trabajo contra las convenciones del DSL de este capítulo.

1. **Redacta una regla de elegibilidad mínima.** Escribe una regla YAML completa llamada
   `"Basic Eligibility"` con `inputs: [creditScore, annualIncome]`, una constante `MIN_SCORE`
   con valor por defecto `620`, un `when:` que requiera que la puntuación sea `at_least MIN_SCORE`
   y los ingresos `greater_than 30000`, y un `then:`/`else:` que ponga `approval_status` a
   `"APPROVED"` o `"DECLINED"`. Confirma que cada variable sigue las mayúsculas/minúsculas correctas.
2. **Añade una banda de tipos con precio.** Extiende tu regla para que el bloque `then:` también ponga una
   salida `annual_rate_bps`: usa `run annual_rate_bps as if_else(creditScore at_least 760,
   999, 1399)`. Declara `annual_rate_bps` en el mapa `output:`. ¿Por qué debe ser la variable
   `snake_case` y no `annualRateBps`?
3. **Protege con un circuit breaker.** Añade una entrada `existingDebtRatio` y una línea que
   detenga la evaluación antes de tiempo — `if existingDebtRatio greater_than 0.43 then circuit_breaker
   "DTI_EXCEEDED"` — colocada de modo que se ejecute antes de la lógica de aprobación. Describe, en una
   frase, qué devuelve el motor cuando el breaker salta.
4. **Esboza el enchufe.** Vuelve a leer el `ProposeOfferHandler` ilustrativo de este capítulo
   y el real del código de dominio del Capítulo 10 — el `doHandle` real es una sola línea,
   `client.proposeOffer(command.getLoanApplicationId(), command.getAmount(),
   command.getAnnualRateBps())`. Escribe tres o cuatro frases que nombren exactamente qué línea
   cambia, qué nueva dependencia adquiere el manejador, de dónde viene ahora el tipo, y
   qué debe llevar el propio `ProposeOfferCommand` que no lleva hoy.
5. **Rastrea la auditoría.** Supón que `credit-eligibility` deniega a un solicitante. Nombra las dos
   columnas del `RuleDefinition` almacenado que un auditor necesita (el capítulo las llama el
   identificador y la etiqueta de versión), y el método cuyo nombre promete que el rastro se
   escribe. En una frase, di por qué el camino por código — no el motor directo — es el que
   cableas en producción.
6. **Justifica la compilación a Python.** En un párrafo corto, argumenta por qué compilar
   `credit-eligibility` a Python mantiene *honesto* un notebook de backtesting — es decir, por qué un
   artefacto generado a partir del YAML en vivo es más seguro que un científico de datos reimplementando
   la política a mano.

## Adónde ir ahora

Este capítulo enmarcó el motor de reglas como el hogar de la toma de decisiones configurable y mostró,
honestamente, que el corte de Lumen aún no vive ahí. El Capítulo 14 vuelve a ampliar al
**modelo de cuatro capas** en sí — experiencia, dominio, núcleo, datos — y por qué cada capa elige su
starter y habla con las demás sobre SDKs en lugar de una base de datos compartida. El paso de decisión que
mapeaste aquí pertenece a la capa de dominio; el próximo capítulo explica las fronteras de capa que
esa decisión cruza.
