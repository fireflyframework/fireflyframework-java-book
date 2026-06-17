Lumen Lending toma una decisión de crédito, pero no *razona* sobre ella. Vuelve
sobre la porción que has construido y encuentra el momento en que se fija el precio de
un préstamo. Está en el `LoanOriginationService.submitApplication` de la capa de dominio,
que toma un `amount` y un `annualRateBps` como simples argumentos y los entrega
directamente a un `ProposeOfferCommand`. El paso `proposeOffer` de la saga registra esa
oferta; nada aguas arriba calcula si el solicitante *cumple los requisitos*, ni a qué
tipo de interés. La lógica de elegibilidad y de fijación de precios es, en efecto, una
constante suministrada a mano: la heurística más sencilla posible, decidida por el
llamador y trasladada tal cual.

Eso es honesto para una porción didáctica, y es exactamente el tipo de decisión que, en
una plataforma de préstamos real, *no* quieres tener soldada en Java y volver a desplegar
cada vez que riesgo cambia un umbral. La política de crédito se mueve a su propio ritmo:
un regulador endurece un techo de deuda sobre ingresos, un equipo de producto lanza un
tipo promocional, una señal de fraude recibe un nuevo peso. Codificar eso en código
compilado hace que cada cambio de política sea una entrega. Este capítulo trata sobre el
componente de Firefly construido precisamente para esta costura —
**`fireflyframework-rule-engine`**— y sobre *dónde* encaja en Lumen Lending para que el
paso de decisión se convierta en datos, redactados en YAML, versionados en una tabla y
evaluados de forma reactiva.

Una nota de honestidad por adelantado, porque este es un capítulo honesto. El reactor de
préstamos **no cablea el motor de reglas**. No hay ningún listado aquí que la compilación
verifique, y no hay ningún comando `mvn` que ejecutar al final: el código de abajo es
*ilustrativo*, mostrado en bloques de código estándar, para que puedas ver cómo se
redactaría y evaluaría una regla de elegibilidad de crédito o de fijación de precios.
Todo lo descrito es comportamiento real del motor, extraído del propio DSL y de la capa
de servicio de `fireflyframework-rule-engine`; lo que *no* es real es ninguna afirmación
de que Lumen lo llame hoy. Léelo como el mapa de dónde pertenece la toma de decisiones,
no como un recorrido por código ya implementado.

!!! note "Termino clave — motor de reglas"
    Un **motor de reglas** evalúa política de negocio expresada como *datos*: reglas que
    puedes redactar, versionar y cambiar sin recompilar el servicio. El de Firefly es un
    **motor de evaluación de expresiones** sin estado y reactivo: le entregas una regla
    YAML y un mapa de entradas, y devuelve salidas calculadas más el resultado de una
    condición y metadatos de auditoría. *No* es un motor de inferencia al estilo Drools:
    no hay memoria de trabajo, ni base de hechos, ni encadenamiento hacia adelante. Cada
    evaluación es una llamada a función independiente sobre un mapa de entrada, que es
    exactamente lo que es una decisión de crédito.

## Dónde vive la heurística y dónde iría el motor

Encuentra primero la costura; el resto del capítulo la rellena. En la capa de dominio, la
oferta es ensamblada por el servicio y registrada por un manejador de comandos. El
manejador es el punto de enganche: el único lugar donde una decisión real se *calcularía*
en lugar de pasarse:

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

El paso `proposeOffer` es una *decisión* disfrazada: afirma que un solicitante es elegible
y fija el precio del préstamo, pero la política tras esos números es implícita. Sustitúyela
por el motor de reglas y el mismo paso se vuelve explícito y configurable. Conceptualmente,
el manejador adquiere una dependencia del motor y le pide una decisión antes de construir
el comando:

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

La forma es la lección. La elegibilidad y la fijación de precios dejan de ser argumentos y
se convierten en las *salidas* de una regla llamada `credit-eligibility`, evaluada de forma
reactiva dentro del paso de la saga. Un analista de riesgo puede cambiar el umbral o la
banda de tipos editando YAML y volviendo a almacenar la regla: sin cambio de código, sin
redespliegue. El resto de este capítulo muestra cómo se redacta, almacena, evalúa, audita y
opera esa regla.

!!! spring "Equivalente en Spring"
    No hay un equivalente en Spring puro que te dé esto gratis. En Spring Boot básico
    tendrías que improvisar una abstracción de política —un `@Service` que lee umbrales
    desde `@ConfigurationProperties`, o un DSL casero— y reconstruir tú mismo el analizador,
    el cacheo, la auditoría y la validación. Ese es el mismo impuesto empresarial que nombró
    el Capítulo 1: cada equipo reinventa la toma de decisiones de forma ligeramente distinta.
    El motor de reglas de Firefly es una respuesta precableada y reactiva, activada al añadir
    `fireflyframework-rule-engine-core` al classpath.

## Redactar una regla de elegibilidad de crédito en el DSL

Una regla es un documento YAML con una pequeña columna vertebral obligatoria —`name`,
`description`, `inputs`, `output`— y una sección de lógica. Aquí está la decisión de
elegibilidad que el paso `proposeOffer` de Lumen consultaría, escrita de principio a fin:

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

Léela como la política que es. El bloque `when:` es una lista de condiciones, unidas con
AND: la puntuación debe ser **al menos** la constante `MIN_SCORE`, los ingresos al menos
`MIN_INCOME`, y el importe solicitado positivo. Cuando todas se cumplen, las acciones del
`then:` se disparan: fija el estado, calcula una banda de tipos con la función `if_else`
en línea (1100 puntos básicos para crédito de primera, 1450 en caso contrario), y registra
un motivo. Cuando alguna falla, `else:` deniega. Las tres variables de salida nombradas en
`output:` son lo que el motor devuelve a tu manejador.

Tres convenciones de nomenclatura cargan significado, y el motor las lee automáticamente.
**Las variables de entrada son `camelCase`** (`creditScore`) y provienen del mapa de
entrada que pasas. **Las constantes son `UPPER_CASE`** (`MIN_SCORE`) y se resuelven a partir
del bloque `constants:` de la regla o del almacén de constantes compartido, que es como un
umbral se convierte en un valor que riesgo puede cambiar de forma centralizada, no en un
literal enterrado en cien reglas. **Las variables calculadas son `snake_case`**
(`annual_rate_bps`) y se crean durante la evaluación.

!!! note "Termino clave — condiciones de comparación, lógicas y de expresión"
    El DSL ofrece tres sabores de condición. Las condiciones de **comparación** prueban un
    valor contra otro con más de 30 operadores: `at_least`, `equals`, `in_list`,
    `is_positive`, `is_credit_score`, `matches`, etcétera. Las condiciones **lógicas** las
    combinan con `and`, `or` y `not`, con paréntesis para agrupar:
    `(creditScore at_least 650 AND annualIncome greater_than 40000) OR hasGuarantor equals true`.
    Las condiciones de **expresión** evalúan aritmética y llamadas a funciones en línea:
    `debt_ratio at_most 0.4` donde `debt_ratio` se acaba de `calculate`. Una política de
    crédito usa las tres.

El vocabulario de acciones es igual de expresivo. `set` asigna; `calculate` evalúa
aritmética pura (`+ - * / % **`) con precedencia de operadores real; `run` invoca una
función. El motor incorpora una biblioteca integrada profunda: matemática (`max`, `round`,
`sqrt`), estadística (`avg`, `sum`), de cadenas (`upper`, `format`, `concat`), de fechas
(`datediff`, `calculate_age`), y una suite financiera adaptada precisamente a este dominio:

```yaml
# Illustrative then: fragment — pricing and risk math the engine ships built in
then:
  - calculate monthly_income as annualIncome / 12
  - run debt_ratio as debt_to_income_ratio(monthlyDebt, monthly_income)
  - run payment as calculate_loan_payment(requestedAmount, annual_rate_bps, termMonths)
  - run ltv as loan_to_value(requestedAmount, collateralValue)
  - if debt_ratio greater_than 0.43 then circuit_breaker "DTI_EXCEEDED"
```

`debt_to_income_ratio`, `calculate_loan_payment` y `loan_to_value` son funciones integradas
reales del motor: las matemáticas de préstamos que de otro modo reimplementarías ya están
ahí. La última línea muestra la acción **circuit_breaker**: una parada en seco que termina
la evaluación de forma anticipada con un resultado etiquetado, el equivalente para el autor
de reglas de "denegar inmediatamente, sin más preguntas".

!!! warning "`if_else` evalúa ambas ramas"
    El DSL no tiene operador `? :` al estilo C; usa la función `if_else(condition, then, else)`.
    Pero *no* hace cortocircuito: **ambas** expresiones de valor (la del then y la del else)
    se evalúan de forma anticipada, y luego se selecciona una. Si una rama llama a una función
    costosa o a un `rest_get`, se ejecuta de todas formas. Mantén las expresiones de las ramas
    baratas, o protege el trabajo costoso tras un `when:`/`then:` separado en lugar de
    enterrarlo en un `if_else`.

## Cómo se almacenan y versionan las reglas

Una regla redactada como cadena está bien para una prueba unitaria, pero una plataforma de
préstamos necesita reglas que operaciones pueda gestionar. El motor las persiste. El módulo
de modelos define una entidad R2DBC `RuleDefinition` respaldada por una tabla
`rule_definitions`, y la capa de servicio expone CRUD sobre ella. La forma de la tabla te
dice qué significa "gestionada" aquí:

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

Dos columnas importan más para la toma de decisiones. **`code`** es el identificador
estable y único por el que tu servicio evalúa —`credit-eligibility`—, de modo que el paso
de la saga nunca codifica YAML a fuego; nombra una regla que la plataforma posee.
**`version`** carga la versión semántica de la regla, e `is_active` marca qué definiciones
pueden evaluarse, con `created_by`/`updated_by` y marcas de tiempo que registran *quién*
cambió la política y *cuándo*. Juntas hacen que una regla de crédito sea auditable como un
artefacto gobernado: puedes ver que `credit-eligibility` está en la versión `2.3.0`, quién
la editó por última vez, y si está activa.

Ahí es donde el versionado del motor de reglas se detiene honestamente. Almacenar una fila
por `code` te da la definición *actual* más su etiqueta de versión y su rastro de autoría;
es un almacén gobernado, no un sistema de control de versiones completo con un historial de
cada cuerpo YAML previo. Si necesitas volver a la política del trimestre pasado o comparar
dos revisiones, eso lo añades por encima: mantén las reglas en Git como fuente de la verdad
y almacena la activa, o modela tu propia tabla de historial. El motor te da el identificador
estable, la etiqueta de versión y las columnas de autoría; la gobernanza circundante es tuya
de diseñar.

!!! spring "Equivalente en Spring"
    `RuleDefinition` es una entidad Spring Data R2DBC corriente y el repositorio es un
    repositorio reactivo corriente: la misma persistencia que conociste en el Capítulo 8.
    Nada exótico: el motor almacena reglas igual que Lumen almacena un `loan_application`. Lo
    que añade por encima es el analizador, el evaluador y la caché que convierten una cadena
    YAML almacenada en una decisión ejecutable.

## Evaluar de forma reactiva: directa, por código y por lotes

La evaluación es reactiva de principio a fin, devolviendo un `Mono`, que es por lo que
encaja limpiamente en la canalización de un paso de saga. Hay tres formas de entrar.

El camino de más bajo nivel es el motor **directo**: inyecta `ASTRulesEvaluationEngine` y
evalúa una cadena YAML con un mapa de entrada, sin base de datos de por medio. Este es el
camino de las pruebas unitarias, útil para probar una regla antes de almacenarla:

```java
// Illustrative: evaluate a YAML rule string directly, no persistence
Map<String, Object> inputs = Map.of(
    "creditScore", 720,
    "annualIncome", 84_000L,
    "requestedAmount", 15_000L);

Mono<ASTRulesEvaluationResult> result = engine.evaluateRulesReactive(ruleYaml, inputs);
// result.isSuccess(), result.getOutputData(), result.getExecutionTimeMs()
```

El camino de producción es **por código**: evalúa una regla almacenada por su `code` a
través de `RulesEvaluationService.evaluateRuleByCodeWithAudit`. Esta es la llamada que haría
el `ProposeOfferHandler` de arriba: carga `credit-eligibility` desde el almacén, la evalúa
contra el mapa de entrada y (como promete el nombre del método) escribe un registro de
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

El tercer camino es **por lotes**. Una plataforma de préstamos no solo decide una solicitud
a la vez; recalifica una cartera de préstamos cuando cambia la política, o precalifica un
segmento de marketing de la noche a la mañana. `BatchRulesEvaluationService` (y
`POST /api/v1/rules/batch/evaluate`) puntúa muchos conjuntos de entrada contra una regla en
una sola llamada, devolviendo resultados por fila más estadísticas agregadas, con endpoints
acompañantes `validate`, `statistics` y `health`. La misma regla `credit-eligibility` que
fija el precio de una nueva solicitud puede reordenar diez mil existentes, sin un trabajo por
lotes a medida.

!!! note "Termino clave — AST"
    El motor no interpreta el texto YAML en cada llamada. Analiza cada regla una vez en un
    **Árbol de Sintaxis Abstracta** (Abstract Syntax Tree) —un árbol tipado de nodos de
    condición, expresión y acción— y evalúa *ese*. El AST es lo que hace la evaluación rápida
    y con seguridad de tipos, y es lo más caliente que el motor cachea (sección siguiente).
    Cuando la referencia del DSL habla de "análisis basado en AST", este árbol es a lo que se
    refiere: tu YAML se convierte en estructura, y la estructura es lo que se ejecuta.

## Cacheo de definiciones y ASTs

Analizar YAML y cargar una fila desde PostgreSQL en cada decisión sería un despilfarro
cuando la misma regla `credit-eligibility` se dispara miles de veces por minuto. El motor
cachea a través de la abstracción de caché de Firefly (Capítulo 7) —Caffeine por defecto,
con Redis conectable para despliegues distribuidos— bajo el prefijo `firefly.rules.cache`.
Cuatro cachés importan:

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

La **caché de AST** es la que se gana su sustento: una regla se analiza una vez y el árbol
se reutiliza, de modo que el coste por decisión es la evaluación, no el reanálisis. La
**caché de definiciones** mantiene las reglas almacenadas calientes fuera de la base de
datos. Como se ejecuta sobre la abstracción de caché de Firefly, pasar de Caffeine en proceso
a un Redis compartido —para que cada instancia de la flota evalúe la misma política cacheada—
es el cambio de una sola propiedad que viste en el Capítulo 7: pon
`firefly.rules.cache.provider` en `REDIS`.

!!! warning "Los ASTs cacheados implican que las ediciones de reglas almacenadas no son instantáneas"
    La otra cara del cacheo es la obsolescencia. Edita `credit-eligibility` en el almacén y
    las instancias que retienen el AST antiguo lo seguirán usando hasta que la entrada de la
    caché expire (dos horas por defecto) o sea desalojada. Para política que debe cambiar
    *ahora* —un umbral exigido por un regulador— planifica un desalojo de caché explícito o un
    TTL más corto en tu runbook operativo, en lugar de asumir que volver a almacenar surte
    efecto en la siguiente petición.

## Validación antes de almacenar

Una regla defectuosa debería detectarse en tiempo de redacción, no cuando la solicitud de un
cliente la alcanza. El motor expone la validación de YAML como su propio endpoint —
`POST /api/v1/validation/yaml` (y un `/syntax` más ligero)— que comprueba tanto la sintaxis
del DSL como las convenciones de nomenclatura (`camelCase` para entradas, `UPPER_CASE` para
constantes, `snake_case` para variables calculadas) antes de que una regla se evalúe siquiera.
La superficie CRUD de definiciones de reglas también valida al escribir, así que un
`credit-eligibility` malformado no puede almacenarse activo.

```bash
# Illustrative: validate a rule's YAML before storing it
curl -X POST http://localhost:8080/api/v1/validation/yaml \
  -H 'Content-Type: application/json' \
  -d '{ "yamlContent": "name: ...\ninputs: [creditScore]\n..." }'
```

Esta es la puerta que hace segura la política redactada por analistas. Un analista de riesgo
edita YAML, el endpoint de validación informa de un operador mal escrito o de una variable
con el case equivocado, y la regla se corrige *antes* de que pueda denegar a un solicitante
real por el motivo equivocado. Los resultados de validación se cachean ellos mismos, así que
revalidar una regla sin cambios es barato.

## El rastro de auditoría

Cada decisión de crédito es un evento regulado: debes ser capaz de decir, meses después, por
qué un solicitante concreto fue aprobado o denegado, contra qué regla, con qué entradas. El
motor registra esto automáticamente. El módulo de modelos define una entidad `AuditTrail` y
`AuditTrailService` escribe un registro por evaluación, que es exactamente por qué el punto
de entrada de producción se llama `evaluateRuleByCodeWithAudit`. El rastro es consultable por
entidad, usuario o tipo de operación, con estadísticas y limpieza, y aflora sobre
`/api/v1/audit/trails`.

Para una decisión de préstamo, el valor es concreto: el registro de auditoría ata la decisión
al `code` y la `version` de la regla que la produjo, los datos de entrada y el resultado.
Cuando un auditor pregunta "¿por qué se denegó la solicitud X en marzo?", la respuesta es una
consulta, no una excavación arqueológica entre logs. Esta es la capacidad que hace
*defendible* una decisión configurable: cambia la política libremente, pero nunca pierdas el
registro de qué política decidió qué caso.

!!! spring "Equivalente en Spring"
    El modelo de errores RFC 7807 del Capítulo 6 y este rastro de auditoría son
    preocupaciones transversales complementarias: uno hace uniformes los fallos en el borde
    HTTP, el otro hace trazables las *decisiones* en la capa de datos. Ninguno es algo que
    querrías que cada equipo reinventara, y ambos vienen precableados, el recurrente trato de
    Firefly.

## Compilar una regla a Python

La característica más sorprendente es la portabilidad. `PythonCodeGenerator` /
`PythonCompilationService` emiten una *función Python equivalente* para una regla almacenada,
expuesta en `POST /api/v1/python/compile` y cacheada como todo lo demás. El mismo YAML de
`credit-eligibility` que el motor Java evalúa de forma reactiva puede compilarse a una función
Python autónoma que calcula la decisión idéntica.

¿Por qué querría esto una plataforma de préstamos? Dos razones. Primero, **ejecución offline**:
un equipo de ciencia de datos que hace backtesting de una nueva política de crédito contra
años de solicitudes históricas quiere ejecutarla en un notebook, en pandas, a escala, sin
levantar el servicio Java. Segundo, **portabilidad de runtime externo**: una plataforma de
servicio de modelos, un trabajo ETL o el entorno de un socio que habla Python puede ejecutar
la *misma* política que usa el servicio de producción, desde una única fuente redactada. El
YAML sigue siendo la única fuente de la verdad; el Python es un artefacto generado que mantiene
un runtime no-JVM en sincronía con la regla en vivo.

Aquí es genuinamente donde la tesis del motor de "regla como datos, no como código" da sus
frutos: una política de crédito redactada una vez se convierte en una decisión reactiva en
producción *y* en una función Python para análisis, sin una segunda implementación que se
desvíe.

## Lo que has aprendido {.recap}

- La porción de Lumen Lending toma su decisión de crédito con una **heurística suministrada a
  mano**: `LoanOriginationService` pasa un `amount` y un `annualRateBps` fijos a
  `ProposeOfferCommand`, y el paso de saga `proposeOffer` lo registra. El reactor **no**
  cablea el motor de reglas; este capítulo es el mapa de dónde encajaría.
- `fireflyframework-rule-engine` es un **motor de evaluación de expresiones sin estado y
  reactivo**: le das una regla YAML y un mapa de entrada, devuelve salidas calculadas, el
  resultado de una condición y metadatos de auditoría. No es Drools —sin memoria de trabajo,
  sin inferencia—, lo que encaja con una decisión de crédito, una llamada a función
  independiente sobre un único payload.
- Las reglas se redactan en un **DSL YAML** con condiciones de comparación, lógicas y de
  expresión; un rico vocabulario de acciones (`set`, `calculate`, `run`, `circuit_breaker`);
  y funciones financieras integradas (`calculate_loan_payment`, `debt_to_income_ratio`). Las
  convenciones de nomenclatura —`camelCase` para entradas, `UPPER_CASE` para constantes,
  `snake_case` para valores calculados— cargan significado que el motor lee automáticamente.
- Las reglas se **almacenan y versionan** como filas `RuleDefinition` identificadas por un
  `code` estable, se evalúan de forma **reactiva** de tres maneras (directa, por código, por
  lotes), se **cachean** como ASTs analizados y definiciones a través de la abstracción de
  caché de Firefly, se **validan** en un endpoint dedicado, y se **auditan** en cada
  evaluación, e incluso pueden **compilarse a Python** para puntuación offline y runtimes
  externos.
- El punto de enganche del paso de decisión en Lumen es el **`ProposeOfferHandler`**:
  sustituir el tipo pasado por una evaluación por código de una regla `credit-eligibility`
  convierte la política implícita en datos gobernados y modificables, sin redespliegue cuando
  riesgo mueve un umbral.

## Pruebalo tu mismo {.exercises}

Estos ejercicios redactan reglas en el DSL y razonan sobre ellas. No hay módulo reactor contra
el que ejecutarlos —el motor de reglas no está cableado en Lumen—, así que trata el YAML como
el entregable, comprobando tu trabajo contra las convenciones del DSL de este capítulo.

1. **Redacta una regla de elegibilidad mínima.** Escribe una regla YAML completa llamada
   `"Basic Eligibility"` con `inputs: [creditScore, annualIncome]`, una constante `MIN_SCORE`
   con valor por defecto `620`, un `when:` que requiera la puntuación `at_least MIN_SCORE` e
   ingresos `greater_than 30000`, y un `then:`/`else:` que fije `approval_status` a
   `"APPROVED"` o `"DECLINED"`. Confirma que cada variable sigue el casing correcto.
2. **Añade una banda de tipos con precio.** Extiende tu regla para que el bloque `then:`
   también fije una salida `annual_rate_bps`: usa `run annual_rate_bps as if_else(creditScore at_least 760,
   999, 1399)`. Declara `annual_rate_bps` en el mapa `output:`. ¿Por qué debe la variable ser
   `snake_case` y no `annualRateBps`?
3. **Protege con un cortacircuitos.** Añade una entrada `existingDebtRatio` y una línea que
   detenga la evaluación de forma anticipada —`if existingDebtRatio greater_than 0.43 then circuit_breaker
   "DTI_EXCEEDED"`— situada de modo que se ejecute antes de la lógica de aprobación. Describe,
   en una frase, qué devuelve el motor cuando el cortacircuitos se dispara.
4. **Esboza el enganche.** Vuelve a leer el `ProposeOfferHandler` ilustrativo de este capítulo
   y el real en el código de dominio del Capítulo 10. Escribe tres o cuatro frases nombrando
   exactamente qué línea cambia, qué nueva dependencia adquiere el manejador y de dónde viene
   ahora el tipo.
5. **Justifica la compilación a Python.** En un párrafo breve, argumenta por qué compilar
   `credit-eligibility` a Python mantiene *honesto* un notebook de backtesting; es decir, por
   qué un artefacto generado a partir del YAML en vivo es más seguro que un científico de datos
   reimplementando la política a mano.

## Adonde ir ahora

Este capítulo enmarcó el motor de reglas como el hogar de la toma de decisiones configurable y
mostró, honestamente, que la porción de Lumen aún no vive ahí. El Capítulo 14 vuelve a alejar
el zoom hacia el propio **modelo de cuatro capas** —experiencia, dominio, núcleo, datos— y por
qué cada capa elige su starter y habla con las demás sobre SDKs en lugar de una base de datos
compartida. El paso de decisión que mapeaste aquí pertenece a la capa de dominio; el próximo
capítulo explica los límites de capa que esa decisión atraviesa.
