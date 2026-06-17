Spring Boot resolvió un problema real, y lo resolvió bien. Antes de él, levantar un
servicio Java significaba ensamblar a mano un contenedor web, un mapeador de JSON, un
proveedor de validación, una capa de datos y una docena de piezas más — cada equipo de
una forma un poco distinta. Spring Boot sustituyó esa ceremonia por *convención sobre
configuración*: añades un starter y obtienes una porción funcionando. Arrancar un único
servicio nunca ha sido tan fácil.

Pero un banco no es un único servicio. Es una *flota* — docenas, y luego cientos, de
microservicios reactivos que deben ponerse de acuerdo entre sí y comportarse igual en
producción. Y sobre eso, Spring Boot guarda un silencio deliberado. Te da unos bloques de
construcción soberbios y ninguna opinión sobre cómo ensamblarlos de forma coherente a lo
largo de una flota. Ese silencio es donde los equipos sangran tiempo, y es el problema que
este libro — y el Firefly Framework — existen para resolver.

## El impuesto empresarial

Observa lo que ocurre cuando la misma organización construye su décimo servicio Spring
Boot. Cada uno reimplementa, de forma ligeramente distinta, la misma fontanería
transversal:

- **Respuestas de error.** Un servicio devuelve una traza de pila, otro un blob JSON
  artesanal, un tercero un escueto 500. Ninguno coincide en códigos de estado ni en forma,
  así que cada cliente escribe un manejo de errores por servicio.
- **Idempotencia.** Los endpoints de pago y de escritura necesitan deduplicar los
  reintentos. Cada equipo se monta su propia cabecera y su propia caché o — más a menudo —
  se olvida.
- **Redacción de PII.** Los logs filtran identificadores nacionales, números de tarjeta y
  tokens hasta que un auditor lo nota, y entonces cada servicio parchea su logging a mano.
- **Correlación a través de fronteras reactivas.** La traza y el contexto de tenant de una
  petición deben acompañarla en cada salto `Mono`/`Flux`. En la pila reactiva esto está
  notoriamente roto, porque `ThreadLocal` y el MDC del logging **no** acompañan a los
  operadores de Reactor. Los equipos lo descubren por las malas, en producción, cuando una
  línea de log muestra el ID del cliente equivocado.
- **Paginación y filtrado.** Cada endpoint de listado reinventa los DTO de page/size/sort y
  parámetros de consulta improvisados.
- **Validación de dominio.** IBAN, BIC, identificadores fiscales, números de tarjeta —
  validados mediante expresiones regulares copiadas y pegadas que están sutilmente mal en
  tres sitios.
- **Publicación de eventos.** El código se suelda al cliente de un único broker, así que
  pasar de RabbitMQ a Kafka significa una reescritura.
- **Transacciones distribuidas.** Las operaciones de varios pasos necesitan compensación
  cuando un paso falla; cada equipo se monta a mano una saga, normalmente sin recuperación
  ni una ruta de mensajes muertos.
- **Clientes resilientes.** Cada servicio cría su propio `WebClient` ligeramente distinto
  con sus propios ajustes de reintento y de cortacircuitos.

Ahora multiplica eso por la deriva de dependencias: docenas de librerías versionadas de
forma independiente a lo largo de docenas de servicios, sin que dos estén del todo
alineadas. El resultado es el **impuesto empresarial** — APIs inconsistentes, boilerplate
copiado y pegado, errores sutiles en producción, incorporaciones lentas y una flota que
cuesta razonar precisamente porque cada miembro es un poco distinto.

El impuesto es insidioso porque ninguna instancia concreta de él es cara. Montar a mano un
manejador de 404 cuesta una tarde; lo mismo le cuesta al siguiente equipo, y al siguiente.
El coste está en el *agregado y en la deriva* — un centenar de soluciones casi idénticas
que nadie puede cambiar de golpe, que discrepan en los bordes y que cada nueva
incorporación debe aprender servicio a servicio. Puedes pagar este impuesto eternamente,
un servicio cada vez. O puedes codificar las respuestas *una sola vez*, en una capa que
todo servicio hereda. Esa capa es un metaframework.

!!! note "Término clave — framework frente a metaframework"
    Un **framework** te da bloques de construcción y un sitio donde poner tu código (Spring
    Boot es un framework). Un **metaframework** es un framework construido *sobre* otro, que
    añade opiniones, convenciones y comportamiento transversal precableado de modo que toda
    una flota sea coherente por defecto. Firefly es un metaframework sobre Spring Boot: no
    sustituye a Spring Boot, concentra el equivalente a toda una flota de decisiones
    ganadas a pulso en una capa que añades en una línea.

## Lo que añade Firefly

Firefly responde al impuesto empresarial con cinco movimientos. Pasarás el resto del libro
usando cada uno de ellos en serio; aquí está la forma del conjunto.

**1 — Coherencia de versiones en una línea.** Un POM padre y un BOM versionado por
calendario fijan Spring Boot, Spring Cloud y unos 70 módulos del framework en un conjunto
único y libre de conflictos. Tus servicios declaran las dependencias del framework *sin
versión* y nunca vuelven a pelear contra un error de convergencia de dependencias. El
capítulo 3 está dedicado a esto — y lo verás de primera mano en el capítulo 2, donde el
`pom.xml` del quickstart lista las dependencias de Firefly con el elemento `<version>`
llamativamente ausente.

**2 — Un único modelo de error, en todas partes.** Un núcleo diminuto define una única
jerarquía de excepciones con un código de error tipado y un contexto inmutable. Cada módulo
lanza hacia ella, y la capa web la convierte en una respuesta problem-detail estándar
**RFC 7807** — automáticamente, de forma idéntica, en cada servicio. El contraste es
rotundo:

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

La recompensa es concreta y la verás en el mismísimo capítulo siguiente: un id desconocido
vuelve como un problem detail 404 con un `type`, un `title`, un `status` y un `detail`, más
un objeto `extensions` que transporta los IDs de traza y una `suggestion` de remediación —
la *misma* forma de cuerpo, byte a byte, que emite cualquier otro servicio Firefly.

**3 — Capacidades como autoconfiguración conmutable.** Las preocupaciones transversales
duras — buses de comando/consulta CQRS, publicación de eventos agnóstica al transporte,
orquestación de Saga/TCC/Workflow, event sourcing, caché agnóstica al proveedor,
observabilidad con propagación de contexto de Reactor *que funciona* — vienen como
autoconfiguración de Spring Boot. Cada capacidad se activa cuando su jar está en el
classpath, se ajusta mediante propiedades `firefly.*` y **se retira en el instante en que
defines tu propio bean**. Optas por ella añadiendo una dependencia, y anulas cualquier cosa
declarando un bean — nada está oculto, nada está cerrado bajo llave.

**4 — Proveedores detrás de puertos.** Identidad, gestión de contenidos, firma
electrónica, notificaciones y webhooks entrantes/salientes son núcleos *hexagonales*:
dependes de un puerto (una interfaz) y enchufas un adaptador de proveedor elegido mediante
una única propiedad. Cambiar Keycloak por Cognito, o DocuSign por Adobe Sign, es un cambio
de una línea en lugar de una reescritura de SDK.

**5 — Servicios correctos en una sola dependencia.** Cuatro **starters** alineados con las
capas — `core`, `domain`, `data` y `application` — empaquetan las capacidades adecuadas y
unos valores por defecto de grado producción (clientes resilientes, idempotencia,
enmascarado de PII, propagación de `X-Transaction-Id`, logging JSON, un banner de arranque)
para cada tipo de servicio. Una CLI complementaria, `flywork`, anda los proyectos y arranca
toda la build del framework. "Levantar un microservicio correcto" se convierte en "añadir
un starter".

!!! note "Término clave — reactivo (Mono/Flux)"
    A lo largo de todo el libro, Firefly es reactivo de principio a fin: manejadores,
    repositorios, buses y clientes hablan todos el `Mono` (cero-o-uno) y el `Flux`
    (cero-a-muchos) de Project Reactor. El preludio los presentó; el capítulo 5 los enseña
    por completo. Lo más valioso que hace Firefly sobre la pila reactiva es lograr que el
    contexto de traza y de tenant sobreviva a través de las fronteras de los operadores —
    el problema de correlación de la sección anterior — habilitando por ti la propagación
    automática de contexto.

## Un superconjunto, nunca un fork

Sería fácil malinterpretar todo esto como "un nuevo framework que oculta Spring Boot". Es
lo contrario. Firefly es un **superconjunto** estricto que *depende de, configura y expone*
Spring Boot — y nunca lo sustituye ni lo bifurca.

- El POM padre importa los BOM de Spring Boot y de Spring Cloud en lugar de extender
  `spring-boot-starter-parent`, de modo que Firefly coexiste con un padre corporativo.
- Cada capacidad de Firefly es una autoconfiguración real de Spring Boot, controlada con
  `@ConditionalOnProperty` y `@ConditionalOnMissingBean`. Se activa por presencia en el
  classpath y cede ante cualquier bean que definas.
- Sigues escribiendo `@RestController`, `@SpringBootApplication`, `@Service`,
  `@ConfigurationProperties`; sigues usando Actuator, Spring Security, Spring Cloud y
  Micrometer. Las propias anotaciones de Firefly son metaanotaciones de estereotipos de
  Spring o las procesan beans de Spring corrientes.
- Todo es anulable, y la adopción es **aditiva y reversible**: añade un starter para ganar
  comportamiento, declara un bean para cambiarlo, elimina la dependencia para quitarlo.

Esa cláusula de "cede ante cualquier bean que definas" es todo el contrato en miniatura, y
no es teórica — el reactor complementario se apoya en ella directamente. La capa de dominio
incluye una `@AutoConfiguration` que registra un cliente in-JVM por defecto para que el
servicio arranque de forma autónoma, *y* un cliente respaldado por un `WebClient` real que
toma el relevo en el momento en que se configura una ruta base de core; ambos se apartan
para el stub propio de los tests de porción. Cada uno está controlado exactamente con las
guardas `@ConditionalOnProperty` / `@ConditionalOnMissingBean` descritas arriba. Leerás ese
cableado en la parte II — por ahora, lo importante es que "anulable mediante un bean" es
cómo el framework se configura *a sí mismo*, no una cortesía atornillada para los usuarios.

En resumen: Firefly depende de Spring Boot, lo autoconfigura con opinión y lo expone de
forma transparente. Siempre estás escribiendo Spring Boot — solo que nunca el mismo
boilerplate dos veces.

!!! spring "Equivalente en Spring"
    Conserva esta lente durante todo el libro: para casi cada característica de Firefly hay
    una respuesta de Spring puro a "¿cómo lo haría yo mismo?" — y un callout de **Equivalente
    en Spring** que la nombra. El valor de Firefly no es la novedad; es que la respuesta ya
    está cableada, es idéntica a lo largo de la flota y es correcta a nivel reactivo.

## El territorio: cuatro capas

La aplicación que construyes, **Lumen Lending**, es una porción de una plataforma de core
bancario real, y como esa plataforma está organizada en cuatro capas, cada una respaldada
por uno de los starters de Firefly:

- **Experiencia (`exp`)** — el Backend-for-Frontend orientado al canal. Composición sin
  estado: da forma a las peticiones para un cliente de app o web, llama a los servicios de
  dominio aguas abajo, devuelve DTO ligeros. Construida sobre
  `fireflyframework-starter-application`.
- **Dominio** — orquestación de negocio. Traduce comandos gruesos en comandos y consultas
  CQRS, ejecuta sagas compensatorias y emite eventos de dominio. No posee ninguna base de
  datos; llama a los servicios de core sobre clientes reactivos. Construida sobre
  `fireflyframework-starter-domain`.
- **Core** — el sistema de registro. Posee el esquema y los datos, expone APIs CRUD
  reactivas planas y de negocio sobre R2DBC. Construida sobre
  `fireflyframework-starter-core`.
- **Datos** — enriquecimiento, calidad de datos y linaje (por ejemplo, datos de buró de
  crédito). Construida sobre `fireflyframework-starter-data`. La porción de préstamos no
  necesita una capa de datos, así que la conocemos por su cuenta en el capítulo 15, donde
  se enchufa.

Las capas nunca comparten una base de datos; se hablan sobre HTTP a través de costuras de
cliente reactivo. Esa única regla — *integra sobre contratos, no sobre un esquema
compartido* — es lo que permite que una flota evolucione sin que cada cambio se propague por
todas partes. La dirección de las dependencias es estrictamente unidireccional, **exp →
domain → core**: la capa de experiencia conoce el dominio, el dominio conoce el core, y
nada apunta de vuelta aguas arriba.

!!! note "Término clave — capa y starter"
    Una **capa** es un rol en la arquitectura — experiencia, dominio, core o datos — y cada
    capa tiene exactamente un **starter** de Firefly que la acompaña y que empaqueta las
    capacidades que ese rol necesita. Elegir un starter es elegir una capa: un servicio
    `core` hereda los valores por defecto de persistencia y CRUD, un servicio `domain`
    hereda CQRS y orquestación, un servicio `application` (experiencia) hereda la pila BFF.
    El capítulo 2 anda uno de estos a partir del arquetipo `flywork` correspondiente.

!!! spring "Equivalente en Spring"
    Un starter de capa de Firefly es un starter de Spring Boot, el mismo mecanismo que
    `spring-boot-starter-webflux` — una dependencia curada que arrastra un conjunto coherente
    y dispara la autoconfiguración. La diferencia es la altitud: un starter vanilla cablea
    *una* capacidad (la pila web); un starter de capa de Firefly cablea la *base* opinada
    completa para un tipo de servicio. La misma maquinaria, más cosas dentro de la caja.

## Lo que construirás

Lumen Lending no es un diagrama en este libro — es un reactor en marcha que arrancas en tu
propia máquina, con **nada de Docker, nada de base de datos externa y nada de broker de
mensajes**. Las tres capas existen hoy y corren de extremo a extremo: la persistencia es
**H2** en memoria (con una migración de Flyway), y los eventos fluyen sobre el transporte
in-JVM `APPLICATION_EVENT`. Cada capa corre con `mvn spring-boot:run` desde el directorio de
su módulo, o como un ejecutable autocontenido `java -jar` (el repackaging de Spring Boot
está cableado) — el **core** en el puerto 8081, el **domain** en el 8082 y el BFF de
**experiencia** en el 8080. Los comandos exactos viven en
`samples/lumen-lending/README.md`.

La prueba de que las capas componen es una única petición en vivo. Un `POST` de canal al
BFF de experiencia —

```text
POST http://localhost:8080/api/v1/experience/lending/applications
```

— devuelve `201 Created` con un `status` de `SUBMITTED`, habiendo fluido todo el camino
hacia abajo: la capa de experiencia llama al dominio sobre HTTP, el dominio ejecuta la
`RegisterApplicationSaga`, el paso raíz de la saga escribe en el sistema de registro de core
sobre HTTP, y el id asignado por el core vuelve a través de ambas costuras. Después puedes
leer ese mismísimo registro directamente del core en el puerto 8081 y ver los pasos de la
saga registrándose en la capa de dominio. Ese submit de extremo a extremo es la espina
dorsal de todo lo que sigue; el capítulo 2 te lleva hasta él una capa cada vez.

Para la última página, Lumen Lending le permite a un cliente **solicitar** un préstamo
personal, ser **puntuado**, recibir una **decisión**, revisar **ofertas** y **aceptar** una
— fluyendo desde la capa de experiencia, a través de una saga de dominio, hasta el sistema
de registro de core, emitiendo eventos por el camino. Lo construirás capa a capa, y cada
línea que lees es una porción literal del reactor complementario, verificada por la build
(33 tests a lo largo del reactor — 18 en core, 6 en domain, 9 en experiencia — todos en
verde).

!!! note "Honestidad — qué ejercita y qué no la porción de préstamos"
    El libro construye un vertical deliberadamente *mínimo pero real*, y lo dice cada vez. El
    mapeo en vivo de dominio → core es intencionadamente fino — la costura de escritura
    recortada transporta el solicitante y el importe, así que unos pocos campos del core
    (divisa, plazo, propósito) aterrizan como valores por defecto; un mapeo más rico es
    tarea del SDK generado (capítulo 7). Varias capacidades pesadas se enseñan *donde se
    enchufan* en lugar de forzarse dentro de la porción: el motor de reglas (capítulo 13),
    la capa de datos (capítulo 15) y el event sourcing (capítulo 12). Cuando una
    característica es ilustrativa en lugar de estar cableada en el ejemplo en marcha, el
    texto lo dirá con claridad.

Pero primero necesitas tenerlo en marcha. El capítulo 2 te lleva de una carpeta vacía a un
servicio Firefly arrancando en unos pocos minutos — para que el resto del libro tenga algo
sobre lo que crecer.

## Lo que has aprendido {.recap}

- Spring Boot hace fácil un servicio; una *flota* de servicios reactivos coherentes es un
  problema distinto, sin resolver — el **impuesto empresarial** de fontanería transversal
  reimplementada y deriva de dependencias, cuyo coste vive en el agregado y en la deriva, no
  en ninguna instancia concreta.
- Firefly responde a él como un **metaframework**: coherencia de versiones vía padre + BOM,
  un único modelo de error RFC 7807, capacidades como autoconfiguración conmutable,
  proveedores detrás de puertos de una propiedad, y servicios correctos a partir de un único
  starter de capa.
- Firefly es un **superconjunto estricto** de Spring Boot — depende de él, lo configura, lo
  expone; cada bean es anulable; la adopción es aditiva y reversible. El framework incluso se
  configura *a sí mismo* de esta manera, con guardas `@ConditionalOnProperty` /
  `@ConditionalOnMissingBean`.
- El libro construye **Lumen Lending** a lo largo de cuatro capas — experiencia, dominio,
  core, datos — que integran sobre contratos, nunca una base de datos compartida, en la
  dirección estricta **exp → domain → core**.
- Las tres capas de préstamos corren hoy con **nada de Docker**: core en el 8081, domain en
  el 8082, experiencia en el 8080, con un submit `exp → domain (saga) → core` en vivo y
  probado que devuelve `201 SUBMITTED` y persiste en el sistema de registro de core.

## Pruébalo tú mismo {.exercises}

1. **Audita tu propio impuesto.** Lista las preocupaciones transversales de "El impuesto
   empresarial" que tus servicios actuales implementan cada uno por separado. ¿En cuántas de
   ellas todos tus servicios coinciden en el comportamiento exacto?
2. **Encuentra la discrepancia.** Elige dos servicios en los que trabajes y compara la forma
   JSON de un 404 y de un error de validación. ¿Son idénticas? ¿Necesitaría un cliente un
   manejo por servicio?
3. **Detecta la filtración.** Busca en un fichero de log reciente algo que debería haber
   sido enmascarado — un email, un ID, un token. ¿Cómo se impone el enmascarado hoy?
4. **Traza una petición.** ¿Un ID de correlación o de traza en tus servicios sobrevive a
   través de una frontera asíncrona o reactiva hasta los logs de una llamada aguas abajo?
   Intenta seguir uno de extremo a extremo.
5. **Mapea las capas.** Abre `samples/lumen-lending/README.md` y encuentra en qué puerto
   sirve cada capa y sobre qué starter está construida. Predice, antes del capítulo 2, qué
   significa la dirección de dependencias `exp → domain → core` para qué capa puede arrancar
   sin que las demás estén corriendo.

## Adónde ir ahora

El capítulo 2 anda y arranca tu primer servicio Firefly — la capa **core** en el puerto 8081
— y luego conduce su API de originación de préstamos a mano y la prueba con los propios tests
del reactor. Si las referencias reactivas de `Mono`/`Flux` de arriba se sintieron rápidas,
eso es por diseño — el capítulo 5 es la piedra angular que enseña el modelo reactivo por
completo, y el preludio tiene lo suficiente para sostenerte hasta entonces.
