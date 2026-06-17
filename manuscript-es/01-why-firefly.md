Spring Boot resolvió un problema real, y lo resolvió bien. Antes de su llegada,
levantar un servicio Java significaba ensamblar a mano un contenedor web, un
mapeador de JSON, un proveedor de validación, una capa de datos y una docena de
piezas más — cada equipo de una forma un poco distinta. Spring Boot sustituyó esa
ceremonia por *convención sobre configuración*: añades un starter y obtienes una
porción funcional. Nunca ha sido tan fácil arrancar un único servicio.

Pero un banco no es un único servicio. Es una *flota* — decenas, y luego cientos,
de microservicios reactivos que deben coincidir entre sí y comportarse igual en
producción. Y sobre eso Spring Boot guarda un silencio deliberado. Te ofrece unos
bloques de construcción excelentes y ninguna opinión sobre cómo ensamblarlos de
forma coherente en toda una flota. Ese silencio es donde los equipos pierden
tiempo, y es el problema que este libro — y el Firefly Framework — existen para
resolver.

## El impuesto empresarial

Observa lo que ocurre cuando la misma organización construye su décimo servicio
Spring Boot. Cada uno reimplementa, de un modo ligeramente distinto, la misma
fontanería transversal:

- **Respuestas de error.** Un servicio devuelve una traza de pila, otro un bloque
  JSON a medida, un tercero un 500 a secas. Ninguno se pone de acuerdo en los
  códigos de estado ni en la forma, así que cada cliente escribe su propio manejo
  de errores por servicio.
- **Idempotencia.** Los endpoints de pago y de escritura necesitan deduplicar los
  reintentos. Cada equipo se inventa su propia cabecera y su propia caché o —más a
  menudo— se olvida de ello.
- **Redacción de PII.** Los logs filtran números de identificación nacional,
  números de tarjeta y tokens hasta que un auditor lo detecta, y entonces cada
  servicio parchea su logging a mano.
- **Correlación a través de fronteras reactivas.** La traza y el contexto de
  inquilino de una petición deben seguirla a través de cada salto `Mono`/`Flux`. En
  la pila reactiva esto está notoriamente roto, porque `ThreadLocal` y el MDC del
  logging **no** acompañan a los operadores de Reactor. Los equipos lo descubren por
  las malas, en producción, cuando una línea de log muestra el ID del cliente
  equivocado.
- **Paginación y filtrado.** Cada endpoint de listado reinventa los DTO de
  page/size/sort y parámetros de consulta improvisados.
- **Validación de dominio.** IBAN, BIC, identificadores fiscales, números de
  tarjeta — validados con expresiones regulares copiadas y pegadas que están
  sutilmente mal en tres sitios distintos.
- **Publicación de eventos.** El código está soldado al cliente de un único broker,
  de modo que pasar de RabbitMQ a Kafka supone una reescritura.
- **Transacciones distribuidas.** Las operaciones multipaso necesitan compensación
  cuando un paso falla; cada equipo improvisa su propia saga, normalmente sin
  recuperación ni ruta a cola de mensajes muertos.
- **Clientes resilientes.** Cada servicio desarrolla su propio `WebClient`
  ligeramente distinto, con sus propios ajustes de reintento y cortacircuitos.

Ahora multiplica eso por la deriva de dependencias: decenas de bibliotecas
versionadas de forma independiente a lo largo de decenas de servicios, sin que dos
estén del todo alineadas. El resultado es el **impuesto empresarial** — APIs
inconsistentes, código repetitivo de copiar y pegar, errores sutiles en producción,
incorporaciones lentas y una flota difícil de razonar precisamente porque cada
miembro es un poco diferente.

Puedes pagar este impuesto para siempre, servicio a servicio. O puedes codificar
las respuestas *una sola vez*, en una capa que cada servicio hereda. Esa capa es un
metaframework.

!!! note "Término clave — framework frente a metaframework"
    Un **framework** te ofrece bloques de construcción y un sitio donde poner tu
    código (Spring Boot es un framework). Un **metaframework** es un framework
    construido *sobre* otro, que añade opiniones, convenciones y comportamiento
    transversal precableado para que toda una flota sea coherente por defecto.
    Firefly es un metaframework sobre Spring Boot: no reemplaza a Spring Boot, sino
    que concentra el equivalente a las decisiones difíciles de toda una flota en una
    capa que añades en una sola línea.

## Lo que añade Firefly

Firefly responde al impuesto empresarial con cinco movimientos. Dedicarás el resto
del libro a usar cada uno a fondo; aquí tienes la forma del conjunto.

**1 — Coherencia de versiones en una línea.** Un POM padre y un BOM versionado por
calendario fijan Spring Boot, Spring Cloud y unos 70 módulos del framework en un
conjunto único sin conflictos. Tus servicios declaran las dependencias del
framework *sin versión* y nunca vuelven a pelearse con un error de convergencia de
dependencias. El capítulo 3 está dedicado a esto.

**2 — Un único modelo de error, en todas partes.** Un núcleo diminuto define una
única jerarquía de excepciones con un código de error tipado y un contexto
inmutable. Cada módulo lanza hacia ella, y la capa web la convierte en una
respuesta de detalle de problema **RFC 7807** estándar — automáticamente, de forma
idéntica, en cada servicio. El contraste es marcado:

```java
// Vanilla Spring Boot: every service invents its own error shape, by hand.
@ExceptionHandler(LoanNotFoundException.class)
public ResponseEntity<Map<String, Object>> handle(LoanNotFoundException ex) {
    var body = Map.of("error", "not_found", "message", ex.getMessage());
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body); // shape varies per team
}
```

```java
// Firefly: throw a semantic exception; the framework emits RFC 7807 consistently.
throw new ResourceNotFoundException("LoanApplication", id);
```

**3 — Capacidades como autoconfiguración activable.** Las preocupaciones
transversales difíciles — buses de comandos/consultas CQRS, publicación de eventos
agnóstica al transporte, orquestación de Saga/TCC/Workflow, event sourcing, caché
agnóstica al proveedor, observabilidad con propagación de contexto de Reactor *que
funciona*— se entregan como autoconfiguración de Spring Boot. Cada capacidad se
activa cuando su jar está en el classpath, se ajusta mediante propiedades
`firefly.*` y **se retira en el instante en que defines tu propio bean**. Optas por
ella añadiendo una dependencia, y la sobrescribes declarando un bean — nada está
oculto, nada está bloqueado.

**4 — Proveedores tras puertos.** Identidad, gestión de contenidos, firma
electrónica, notificaciones y webhooks entrantes/salientes son núcleos
*hexagonales*: dependes de un puerto (una interfaz) e insertas un adaptador de
proveedor elegido mediante una única propiedad. Cambiar Keycloak por Cognito, o
DocuSign por Adobe Sign, es un cambio de una línea en lugar de una reescritura del
SDK.

**5 — Servicios correctos en una dependencia.** Cuatro **starters** alineados con
las capas — `core`, `domain`, `data` y `application`— agrupan las capacidades
adecuadas y unos valores por defecto de nivel de producción (clientes resilientes,
idempotencia, enmascaramiento de PII, propagación de `X-Transaction-Id`, logging en
JSON, un banner de arranque) para cada tipo de servicio. Una CLI complementaria,
`flywork`, genera proyectos andamiados y arranca toda la construcción del framework.
«Levantar un microservicio correcto» se convierte en «añadir un starter».

!!! note "Término clave — reactivo (Mono/Flux)"
    De principio a fin, Firefly es reactivo de extremo a extremo: manejadores,
    repositorios, buses y clientes hablan todos el `Mono` (cero o uno) y el `Flux`
    (cero o muchos) de Project Reactor. El preludio los presentó; el capítulo 5 los
    enseña por completo. Lo más valioso que hace Firefly sobre la pila reactiva es
    lograr que el contexto de traza y de inquilino sobreviva a través de las
    fronteras de los operadores — el problema de correlación de la sección anterior —
    habilitando por ti la propagación automática de contexto.

## Un superconjunto, nunca un fork

Sería fácil malinterpretar todo esto como «un nuevo framework que oculta Spring
Boot». Es justo lo contrario. Firefly es un **superconjunto** estricto que
*depende de, configura y expone* Spring Boot — y nunca lo reemplaza ni lo bifurca.

- El POM padre importa los BOM de Spring Boot y Spring Cloud en lugar de extender
  `spring-boot-starter-parent`, de modo que Firefly coexiste con un POM padre
  corporativo.
- Cada capacidad de Firefly es una autoconfiguración real de Spring Boot, controlada
  con `@ConditionalOnProperty` y `@ConditionalOnMissingBean`. Se activa por la
  presencia en el classpath y cede ante cualquier bean que definas.
- Sigues escribiendo `@RestController`, `@SpringBootApplication`, `@Service`,
  `@ConfigurationProperties`; sigues usando Actuator, Spring Security, Spring Cloud
  y Micrometer. Las propias anotaciones de Firefly son estereotipos de Spring
  metaanotados o procesados por beans ordinarios de Spring.
- Todo es sobrescribible, y la adopción es **aditiva y reversible**: añade un starter
  para ganar comportamiento, declara un bean para cambiarlo, elimina la dependencia
  para descartarlo.

En resumen: Firefly depende de Spring Boot, lo autoconfigura con opiniones y lo
expone de forma transparente. Siempre estás escribiendo Spring Boot — solo que nunca
el mismo código repetitivo dos veces.

!!! spring "Equivalente en Spring"
    Aférrate a esta lente durante todo el libro: para casi cada característica de
    Firefly existe una respuesta en Spring puro a «¿cómo lo haría yo mismo?» — y un
    recuadro de **Equivalente en Spring** que la nombra. El valor de Firefly no es
    la novedad; es que la respuesta ya está cableada, es idéntica en toda la flota y
    es correcta desde el punto de vista reactivo.

## El territorio: cuatro capas

La aplicación que construyes, **Lumen Lending**, es una porción de una plataforma
de core bancario real y, como esa plataforma, está organizada en cuatro capas, cada
una respaldada por uno de los starters de Firefly:

- **Experiencia (`exp`)** — el Backend-for-Frontend orientado al canal. Composición
  sin estado: da forma a las peticiones para una app o un cliente web, llama a los
  servicios de dominio aguas abajo y devuelve DTO ligeros. Construida sobre
  `starter-application`.
- **Dominio** — orquestación de negocio. Traduce comandos gruesos en comandos y
  consultas CQRS, ejecuta sagas compensadoras y emite eventos de dominio. No posee
  base de datos; llama a los servicios de core a través de SDK generados. Construida
  sobre `starter-domain`.
- **Core** — el sistema de registro. Posee el esquema y los datos, expone CRUD
  reactivo simple y APIs de negocio sobre R2DBC. Construido sobre `starter-core`.
- **Data** — enriquecimiento, calidad de datos y linaje (por ejemplo, datos de buró
  de crédito). Construida sobre `starter-data`. La conoceremos en el capítulo 15.

Las capas nunca comparten base de datos; se comunican por HTTP a través de SDK
reactivos generados. Esa única regla — *integra sobre contratos, no sobre un esquema
compartido*— es lo que permite que una flota evolucione sin que cada cambio se
propague por todas partes.

## Lo que construirás

Para cuando llegues a la última página, Lumen Lending permitirá a un cliente
**solicitar** un préstamo personal, obtener una **puntuación**, recibir una
**decisión**, revisar **ofertas** y **aceptar** una — fluyendo desde la capa de
experiencia, a través de una saga de dominio, hasta el sistema de registro del core,
emitiendo eventos por el camino. Lo construirás capa a capa, y cada línea que leas
es una porción literal del reactor complementario, verificada por la construcción.

Pero primero necesitas tenerlo en marcha. El capítulo 2 te lleva de una carpeta
vacía a un servicio Firefly arrancando en unos minutos — para que el resto del libro
tenga algo sobre lo que crecer.

## Lo que has aprendido {.recap}

- Spring Boot hace fácil un servicio; una *flota* de servicios reactivos coherentes
  es un problema distinto y sin resolver — el **impuesto empresarial** de la
  fontanería transversal reimplementada y la deriva de dependencias.
- Firefly lo responde como un **metaframework**: coherencia de versiones mediante
  padre + BOM, un único modelo de error RFC 7807, capacidades como autoconfiguración
  activable, proveedores tras puertos de una sola propiedad y servicios correctos a
  partir de un único starter de capa.
- Firefly es un **superconjunto estricto** de Spring Boot — depende de él, lo
  configura, lo expone; cada bean es sobrescribible; la adopción es aditiva y
  reversible.
- El libro construye **Lumen Lending** a través de cuatro capas — experiencia,
  dominio, core, data — que se integran sobre contratos, nunca sobre una base de
  datos compartida.

## Pruébalo tú mismo {.exercises}

1. **Audita tu propio impuesto.** Enumera las preocupaciones transversales de «El
   impuesto empresarial» que cada uno de tus servicios actuales implementa por
   separado. ¿En cuántas de ellas todos tus servicios coinciden en el comportamiento
   exacto?
2. **Encuentra el desacuerdo.** Elige dos servicios en los que trabajes y compara la
   forma JSON de un 404 y de un error de validación. ¿Son idénticas? ¿Necesitaría un
   cliente un manejo por servicio?
3. **Detecta la fuga.** Busca en un archivo de log reciente cualquier cosa que
   debería haberse enmascarado — un correo electrónico, un ID, un token. ¿Cómo se
   aplica hoy el enmascaramiento?
4. **Traza una petición.** ¿Sobrevive un ID de correlación o de traza en tus
   servicios al cruzar una frontera asíncrona o reactiva hasta los logs de una
   llamada aguas abajo? Intenta seguir uno de extremo a extremo.

## Adónde ir ahora

El capítulo 2 genera el andamiaje y arranca tu primer servicio Firefly. Si las
referencias reactivas a `Mono`/`Flux` de arriba te parecieron rápidas, es algo
intencionado — el capítulo 5 es la piedra angular que enseña el modelo reactivo por
completo, y el preludio tiene lo suficiente para sostenerte hasta entonces.
