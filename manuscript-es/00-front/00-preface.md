## Prefacio

Spring Boot resolvió un problema real. Antes de él, levantar un servicio Java
significaba ensamblar a mano un contenedor de servlets, un mapeador de JSON, un
proveedor de validación, una capa de datos y una docena de piezas más en
movimiento — cada equipo a su manera. Spring Boot sustituyó esa ceremonia por la
*convención sobre configuración*: añades un starter y obtienes una porción que
funciona. Pero Spring Boot es deliberadamente neutral respecto al siguiente
problema — construir una **flota** de microservicios *reactivos*, consistentes y
listos para producción. Cada servicio sigue reinventando la misma fontanería
transversal: formas de error que nunca acaban de coincidir, idempotencia,
redacción de datos personales, identificadores de correlación que se desvanecen
al cruzar las fronteras de los hilos reactivos, DTOs de paginación y filtrado,
validación de IBANs e identificadores fiscales, publicación de eventos soldada a
un único broker, sagas montadas a mano en cada proyecto y N copias sutilmente
distintas de la configuración de resiliencia de `WebClient`. Multiplica eso por la
deriva de dependencias entre docenas de librerías versionadas de forma
independiente y obtienes el impuesto empresarial: APIs inconsistentes, código
repetitivo de copiar y pegar, errores sutiles en producción y una incorporación
lenta de nuevos miembros.

**El Firefly Framework** es la respuesta a *ese* problema. Es un metaframework
*por encima de* Spring Boot — un superconjunto curado, con opiniones y con todo
incluido para microservicios reactivos. Un POM padre y un BOM versionado por
calendario fijan Spring Boot, Spring Cloud y ~70 módulos del framework en un único
conjunto libre de conflictos. Un núcleo compartido da a cada servicio un único
modelo de error, expuesto como RFC 7807 en todas partes. Los módulos de
capacidades — CQRS, mensajería orientada a eventos, orquestación Saga/TCC, event
sourcing, caché, observabilidad con propagación *funcional* del contexto de
Reactor — se entregan como autoconfiguración de Spring activable y sustituible.
Los núcleos de integración hexagonales convierten un cambio de proveedor
(Kafka↔RabbitMQ, Keycloak↔Cognito, DocuSign↔Adobe Sign) en el cambio de una sola
propiedad. Cuatro starters alineados con las capas convierten «levantar un
microservicio correcto» en añadir una única dependencia. Y, fundamentalmente,
Firefly nunca bifurca Spring Boot ni lo oculta: sigues escribiendo
`@RestController`, `@SpringBootApplication`, `@ConfigurationProperties`; cada bean
de Firefly es sustituible; la adopción es aditiva y reversible.

Este libro enseña Firefly **haciendo**. Construyes una aplicación real desde una
carpeta vacía hasta un sistema seguro, observable, orientado a eventos y de tres
capas. Y el código de estas páginas no es pseudocódigo ilustrativo: cada listado
es una **porción literal** de un reactor Maven de acompañamiento que compila,
arranca y pasa sus pruebas en integración continua. Cuando la prosa se aparta del
código fuente, la compilación falla. Lo que lees es lo que de verdad funciona.

### Para quién es este libro

Este libro es para desarrolladores de Java que quieren construir sistemas backend
serios y quieren una forma coherente de hacerlo. Deberías sentirte cómodo con el
Java moderno (records, genéricos, lambdas) y con lo básico de los servicios HTTP.
*No* necesitas experiencia previa con Firefly, y no hace falta que seas un experto
en programación reactiva — un preludio en las páginas iniciales te pone al día
sobre Spring Boot, WebFlux, Project Reactor y R2DBC, y un capítulo angular
dedicado enseña el modelo reactivo desde los primeros principios antes de que
ninguna funcionalidad del framework dependa de él.

Si tu último Spring fue MVC y JPA, eres bienvenido aquí; las notas *Si vienes de
Spring MVC* facilitan el salto a la pila reactiva. Si llegas desde otro port de
Firefly como PyFly, reconocerás la forma y podrás avanzar deprisa.

### Lo que vas a construir

Cada capítulo hace avanzar **Lumen Lending**, un servicio de originación de
préstamos personales modelado sobre una plataforma real de banca core. La
narrativa sigue una historia de usuario natural — *solicitar → ser puntuado →
obtener una decisión → revisar ofertas → aceptar una oferta* — y el recorrido sigue
un arco deliberado:

- **Parte I — Fundamentos.** Aprendes *por qué* existe un metaframework sobre
  Spring Boot, montas y ejecutas tu primer servicio, haces que toda la pila sea
  coherente en versiones con el padre y el BOM, enlazas configuración tipada y
  dominas el modelo reactivo Mono/Flux sobre el que se asienta todo lo demás.
- **Parte II — Modelar y persistir.** Expones tu primera API HTTP reactiva con
  validación de nivel financiero y errores RFC 7807, generas un SDK tipado,
  persistes la solicitud de préstamo con R2DBC y Flyway, y modelas un agregado de
  dominio rico con un objeto de valor `Money`.
- **Parte III — CQRS, EDA y decisión.** Separas las escrituras de las lecturas con
  un bus de comandos/consultas, emites y enrutas eventos de dominio (en la propia
  JVM, y después sobre Kafka), opcionalmente aplicas event sourcing a un libro
  mayor, y conoces el motor de reglas reactivo donde la decisión crediticia
  automatizada *debe estar*.
- **Parte IV — Las cuatro capas.** Divides el sistema en capas de experiencia,
  dominio, core y datos que se comunican mediante SDKs generados; conectas clientes
  de servicio resilientes y multiprotocolo; construyes el BFF; y orquestas la
  **saga de registro** protagonista con fan-out paralelo y compensación automática.
- **Parte V — Asegurar · Observar · Desplegar.** Aseguras los endpoints, los
  cacheas y los fortaleces, haces el sistema observable, lo conectas con el mundo
  exterior mediante documentos, planificación, notificaciones, webhooks y
  callbacks, pruebas la pila completa, y la extiendes y la despliegas a producción.

Al llegar a la última página tienes un servicio multicapa funcional, probado,
observable y seguro — y el modelo mental para extenderlo.

### Cómo usar este libro

**Lee de forma secuencial.** Cada capítulo se apoya en el anterior, y el código de
Lumen Lending crece de forma incremental; saltar adelante deja huecos.

**Teclea cada listado tú mismo.** Leer y teclear a la vez es como se fijan los
patrones. Resiste la tentación de copiar y pegar hasta que hayas escrito cada
listado al menos una vez.

**Ejecútalo.** Lumen Lending se ejecuta de verdad. Siempre que un capítulo añada
una funcionalidad, arranca el servicio o sus pruebas y míralo funcionar —
`mvn verify` arranca el reactor y lo ejercita. Ver JSON real de vuelta desde un
endpoint real vale más que cien diagramas.

Cada capítulo se cierra con un **Resumen** de lo que cambió y un conjunto de
**Ejercicios** que empujan un paso más allá. Los ejercicios son opcionales, pero
recomendables para cualquier cosa que tengas intención de aplicar de inmediato.

### Convenciones en breve

Las convenciones tipográficas y estructurales — los pies de los listados de código,
los tipos de callout (incluido el callout **Equivalente en Spring** que asocia cada
idea de Firefly de vuelta con Spring Boot a secas) y la numeración de las figuras —
se demuestran con ejemplos en vivo en la sección **Convenciones** que viene a
continuación.

### El código de acompañamiento

El proyecto completo y ejecutable vive en el directorio `samples/lumen-lending` de
este repositorio: un reactor Maven en capas — `core-lending-loan-origination`,
`domain-lending-loan-origination`, `exp-lending` — que haces crecer capítulo a
capítulo. El código terminado que hay allí es el destino al que este libro te
lleva. Constrúyelo una vez y úsalo para comparar tu trabajo, ponerte al día si te
quedas atrás, o simplemente ejecutar las partes sobre las que estás leyendo.

### Lo que este libro *no* es

Este es un libro sobre el Firefly Framework de **Java**. El framework tiene ports
hermanos en Python (PyFly), Rust, Go y .NET, un metaframework agéntico en Python
con su `agentic-bridge`, y frameworks de frontend — son proyectos paralelos con su
propia documentación y quedan **fuera del alcance** de aquí. Donde Firefly se apoya
en Spring Boot, Spring Cloud o Project Reactor, este libro enseña lo justo para
usarlo bien; no es una referencia exhaustiva de esas plataformas ni de cada uno de
los ~70 módulos del framework. Es un camino guiado de cero a un servicio reactivo
multicapa real — y la base para ir desde ahí a donde quieras.
