`flywork` es el compañero de línea de comandos de Firefly: la herramienta que
arranca el framework en una máquina nueva y genera el andamiaje de nuevos servicios
para que nazcan ya con las convenciones correctas. (Resulta que está escrito en Go y
se distribuye como un único binario; eso es un detalle de implementación: nunca
escribes Go para usarlo, y este sigue siendo un libro sobre el framework de Java.)

## Arrancar el framework

El framework son muchos repositorios, y se compilan en orden de dependencias.
`flywork setup` los clona todos y los instala en tu repositorio Maven local
(`~/.m2`) en la secuencia correcta, de modo que `fireflyframework-kernel` se compile
antes que los módulos que dependen de él, y así sucesivamente subiendo por el grafo.

```text
$ flywork setup
Resolving framework dependency graph … 41 repositories, 7 layers
Layer 1/7  kernel, utils, validators …            installed
Layer 2/7  observability, cache, eda …            installed
…
Layer 7/7  starter-core, starter-domain …         installed
Done. Framework <version> available in ~/.m2.
```

Una vez que esto termina, un proyecto que herede de `fireflyframework-parent` o
importe `fireflyframework-bom` se resuelve completamente sin conexión, que es
exactamente la razón por la que el reactor de acompañamiento de este libro se
compila con `mvn -o verify`, sin necesidad de red.

## Generar el andamiaje de un servicio

`flywork create` genera un nuevo proyecto a partir de uno de cuatro arquetipos, cada
uno alineado con una capa (capítulo 14):

```text
$ flywork create --archetype domain --name lending-loan-origination
```

| Arquetipo | Produce | Starter |
|---|---|---|
| `core` | un servicio de sistema de registro (R2DBC, Flyway, web) | `starter-core` |
| `domain` | un servicio de orquestación (CQRS, saga, EDA) | `starter-domain` |
| `application` | un servicio de experiencia/BFF (`@Secure`, clientes SDK) | `starter-application` |
| `library` | un módulo de biblioteca compartida | — |

El proyecto generado ya tiene el parent correcto, el starter de la capa, una
disposición de paquetes sensata, un `application.yml` y una prueba de humo que pasa:
la misma forma que los módulos que fuiste construyendo a lo largo de este libro.

## Resolución de problemas

Una breve guía de campo de los problemas con los que es más probable que te
encuentres.

!!! tip "Punto de control — ¿está instalado el framework?"
    Si una compilación falla al resolver `org.fireflyframework:*`, ejecuta `flywork
    setup` (o confirma que `~/.m2/repository/org/fireflyframework/` está poblado).
    Todos los demás problemas que siguen suponen que el framework está instalado.

- **`BUILD FAILURE` al resolver un artefacto del framework.** La versión que
  declaraste no está en `~/.m2`. O bien te alineas con la versión que instaló
  `flywork setup`, o bien importas el `fireflyframework-bom` correspondiente. Mezclar
  dos versiones del framework es la causa más común de un confuso `NoSuchMethodError`
  en tiempo de ejecución.
- **Un endpoint reactivo se bloquea bajo carga / se atasca de vez en cuando.** Algo
  en la ruta de la petición está bloqueando el bucle de eventos: una llamada JDBC, un
  `.block()`, un SDK síncrono de terceros. Sácalo del bucle de eventos
  (`subscribeOn(Schedulers.boundedElastic())`) o sustitúyelo por un cliente reactivo.
  Vuelve a leer la advertencia de "nunca bloquees" del capítulo 5.
- **Falta un ID de correlación o de traza en un log aguas abajo.** Confirma que
  `fireflyframework-observability` está presente: habilita la propagación automática
  del contexto de Reactor. Sin él, los valores de `ThreadLocal`/MDC no siguen a los
  operadores.
- **Un `@EventListener` no se dispara nunca.** El runtime hace coincidir los
  `eventTypes` por el nombre simple de la clase del payload; comprueba que el nombre
  coincide y que EDA está habilitado (`firefly.eda.enabled=true`) con un transporte
  configurado. El capítulo 11 cubre esto.
- **Un endpoint de listado ignora un parámetro de filtro.** Los campos de ID se
  excluyen del motor de filtros genérico a menos que se anoten con `@FilterableId`.
  Consulta el capítulo 8.
- **`@Secure` devuelve 401/403 en una prueba.** El endpoint está genuinamente
  protegido; proporciona una configuración de seguridad de prueba permisiva o un
  contexto de prueba autenticado, como hace el slice de la capa de experiencia
  (capítulo 17).
- **Las pruebas necesitan un Docker del que no dispones.** Prefiere los valores por
  defecto en proceso: H2 para R2DBC, el transporte EDA en la JVM, Caffeine para la
  caché, exactamente como hace el reactor de este libro. Recurre a Testcontainers
  (capítulo 23) solo cuando un capítulo requiera un backend real.

## Adonde ir ahora

Con `flywork setup` ejecutado una sola vez y `flywork create` para cada nuevo
servicio, levantar un microservicio correcto y consistente es un único comando, que
es la promesa entera del capítulo 1, ahora al alcance de tu mano.
