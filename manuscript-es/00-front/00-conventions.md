## Conventions

A few conventions recur throughout the book. This page demonstrates each one
live, so you know exactly what you are looking at.

### Code listings

Code appears in **listings** with a file tab and a numbered caption. The file
tab shows where the code lives in the companion reactor, so you can always open
the real source:

::: listing Greeting | Listing C.1 — the shape of a listing
public record Greeting(String message) {
    public static Greeting of(String name) {
        return new Greeting("Hello, " + name + "!");
    }
}
:::

In the chapters, that tab carries the file's full path inside the reactor — for
example `core-lending-loan-origination/src/main/java/com/firefly/lumen/core/Money.java`.
**Every such listing is a verbatim slice of that file**, checked by the build: if
a listing ever drifts from the source it was copied from, continuous integration
fails. When a listing shows only part of a file, an ellipsis (`...`) marks the
omission.

Inline code — a class like `LoanApplication`, an annotation like
`@CommandHandlerComponent`, or a property like `firefly.cqrs.enabled` — appears
in `monospace`.

### Callouts

Four callout styles flag asides without breaking the flow.

!!! note "Key term — reactive (Mono/Flux)"
    A **Note** introduces a definition or a piece of context you will need
    shortly. Key terms are introduced this way the first time they appear.

!!! tip "Checkpoint"
    A **Tip** is a shortcut, a good default, or a "run it now and watch it pass"
    checkpoint. Most chapter steps end with one.

!!! warning "Don't block the event loop"
    A **Warning** flags a foot-gun — something that compiles but will bite you in
    production, like a blocking call on a reactive thread.

!!! spring "Spring parity"
    A **Spring parity** callout maps a Firefly idea back to plain Spring Boot or
    Project Reactor, so you build on what you already know. For example: a Firefly
    `@CommandHandlerComponent` *is* a Spring stereotype — the bus discovers it the
    same way Spring discovers an `@Service`.

### Figures

Diagrams appear as numbered **figures**:

::: figure art/figures/C1-anatomy.svg | Figure C.1 — the anatomy of a listing: file tab, code, and caption

Figures are vector art, so they stay crisp in both the EPUB and the print PDF.

### Recaps and exercises

Every chapter closes with two short sections: **What you built**, a recap of what
changed in Lumen Lending, and **Try it yourself**, a handful of exercises that
each extend a real file in the reactor.
