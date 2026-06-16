## Convenciones

A lo largo del libro se repiten unas cuantas convenciones. Esta página muestra
cada una de ellas en directo, para que sepas exactamente qué estás viendo.

### Listados de código

El código aparece en **listados** con una pestaña de archivo y un pie numerado. La
pestaña de archivo indica dónde vive el código en el reactor de acompañamiento, de
modo que siempre puedas abrir el código fuente real:

::: listing Greeting | Listado C.1 — the shape of a listing
public record Greeting(String message) {
    public static Greeting of(String name) {
        return new Greeting("Hello, " + name + "!");
    }
}
:::

En los capítulos, esa pestaña lleva la ruta completa del archivo dentro del
reactor — por ejemplo `core-lending-loan-origination/src/main/java/com/firefly/lumen/core/Money.java`.
**Cada uno de estos listados es un fragmento literal de ese archivo**, comprobado
por el build: si un listado alguna vez se desvía del código fuente del que se
copió, la integración continua falla. Cuando un listado muestra solo una parte de
un archivo, unos puntos suspensivos (`...`) marcan la omisión.

El código en línea — una clase como `LoanApplication`, una anotación como
`@CommandHandlerComponent` o una propiedad como `firefly.cqrs.enabled` — aparece
en `monoespaciado`.

### Llamadas destacadas

Cuatro estilos de llamadas destacadas señalan apartes sin romper el hilo de la
lectura.

!!! note "Termino clave — reactivo (Mono/Flux)"
    Una **Nota** introduce una definición o un poco de contexto que necesitarás
    enseguida. Los términos clave se introducen así la primera vez que aparecen.

!!! tip "Punto de control"
    Un **Consejo** es un atajo, un buen valor por defecto o un punto de control
    del tipo "ejecútalo ahora y míralo pasar". La mayoría de los pasos de cada
    capítulo terminan con uno.

!!! warning "No bloquees el bucle de eventos"
    Una **Advertencia** señala un peligro — algo que compila pero que te morderá
    en producción, como una llamada bloqueante en un hilo reactivo.

!!! spring "Equivalente en Spring"
    Una llamada destacada de **Equivalente en Spring** relaciona una idea de
    Firefly con Spring Boot puro o Project Reactor, de modo que construyas sobre
    lo que ya conoces. Por ejemplo: un `@CommandHandlerComponent` de Firefly *es*
    un estereotipo de Spring — el bus lo descubre del mismo modo que Spring
    descubre un `@Service`.

### Figuras

Los diagramas aparecen como **figuras** numeradas:

::: figure art/figures/C1-anatomy.svg | Figura C.1 — la anatomía de un listado: pestaña de archivo, código y pie

Las figuras son arte vectorial, así que se mantienen nítidas tanto en el EPUB
como en el PDF de impresión.

### Resúmenes y ejercicios

Cada capítulo se cierra con dos secciones breves: **Lo que has construido**, un
resumen de lo que cambió en Lumen Lending, y **Pruebalo tu mismo**, un puñado de
ejercicios que extienden cada uno un archivo real del reactor.
