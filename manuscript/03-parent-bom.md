Open any reactive Spring Boot service and the first thing you meet, before a single
line of business code, is the `pom.xml` — and the first place a fleet goes wrong.
Two services pull `spring-boot-starter-webflux` at slightly different versions; a
third drags in a Reactor patch that disagrees with both; a fourth pins Netty by
hand to silence a CVE scanner. Nothing here is dramatic on its own. Together they
are the dependency drift that Chapter 1 called part of the enterprise tax, and they
cost real hours in convergence errors and "works on my machine" mysteries.

Firefly's answer is the most boring kind of good engineering: pin everything,
once, in a place every service inherits. This chapter is short because the payoff
is short — a parent POM, a BOM, one property — and after this page your modules
declare framework dependencies with *no version at all*. You already saw the shape
in the prelude ("inherit a parent, add a starter, omit versions"). Here you see the
real reactor wiring that makes it true, and you learn when to inherit and when to
import.

We work entirely in the Lumen Lending build files. By the end you will be able to
read every `pom.xml` in the reactor and know exactly where each version comes from.

## The two coordination files

A Maven multi-module build has a **reactor root** — the top `pom.xml` that lists
the modules and sets shared policy — and one `pom.xml` per module. Version coherence
lives almost entirely in the root. Lumen Lending's root does three things that
matter, and we will take them one at a time: it *inherits* a Firefly parent, it
*imports* a Firefly BOM, and it sets one version property that ties them together.

Here is the parent declaration at the top of the reactor root.

::: listing pom.xml | Listing 3.1 — the reactor inherits fireflyframework-parent
    <parent>
        <groupId>org.fireflyframework</groupId>
        <artifactId>fireflyframework-parent</artifactId>
        <version>26.06.01</version>
        <relativePath/>
    </parent>
:::

That single block is the foundation. By inheriting `fireflyframework-parent`, the
reactor takes on a large amount of policy it never has to spell out itself. The
comment in the file names what the parent brings, and it is worth reading as the
chapter's thesis.

::: listing pom.xml | Listing 3.2 — what the parent brings (reactor root comment)
    <!--
      Lumen Lending — trimmed reactor mirroring the firefly-oss lending vertical.

      We inherit the Firefly Framework parent directly (the real firefly-oss services
      sit on a thin internal `firefly-parent` that in turn inherits this one). The parent
      brings the Spring Boot/Cloud BOMs, the compiler/enforcer/surefire plugin config,
      the Java 25 baseline, and the `java21` profile.
    -->
:::

Read that list again, because it is the whole value proposition in five clauses.
The parent supplies the Spring Boot and Spring Cloud BOMs (so Firefly stays aligned
with the Spring releases it builds on), the build-plugin configuration (compiler,
enforcer, Surefire), the **Java 25 baseline**, and a `java21` profile for shops not
yet on 25. You inherit all of it by writing the five lines of Listing 3.1.

!!! spring "Spring parity"
    In a typical Spring Boot project you would write
    `<parent>spring-boot-starter-parent</parent>` to inherit Spring's plugin
    management and dependency versions. `fireflyframework-parent` plays the same
    role, one level up: it *imports* the Spring Boot BOM internally rather than
    extending `spring-boot-starter-parent`, which is precisely why it can coexist
    with a corporate parent. You get Spring Boot's curated versions plus Firefly's,
    without giving up your organization's own parent POM.

## Importing the BOM

Inheriting the parent pins Spring and the build plugins. It does **not**, by itself,
pin the ~70 `org.fireflyframework` modules — the starters, the web and R2DBC
helpers, the validators, the CQRS and event modules you will meet later. Those
versions come from a separate **Bill of Materials**, imported in the reactor root's
`dependencyManagement`.

::: listing pom.xml | Listing 3.3 — importing fireflyframework-bom pins every framework module
    <properties>
        <firefly.version>26.06.01</firefly.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <!-- Firefly Framework BOM: pins all org.fireflyframework module versions. -->
            <dependency>
                <groupId>org.fireflyframework</groupId>
                <artifactId>fireflyframework-bom</artifactId>
                <version>${firefly.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
:::

Two details carry the weight. The `<type>pom</type>` with `<scope>import</scope>`
is Maven's idiom for *pulling another POM's `dependencyManagement` into your own* —
the BOM is a long table of `groupId:artifactId → version` entries, and importing it
merges that table into yours without adding a single dependency to the build. And
the version is `${firefly.version}`, a property declared one block up, so the parent
version and the BOM version are stated in exactly one place each and read at a glance.

!!! note "Key term — BOM (Bill of Materials)"
    A **BOM** is a POM whose only job is its `dependencyManagement` section: a
    curated list pinning the versions of a family of artifacts. You *import* it
    (rather than depend on it), and from then on you reference those artifacts with
    no `<version>` of your own — the BOM supplies it. `spring-boot-dependencies` is
    the BOM you already rely on; `fireflyframework-bom` is the same pattern for the
    Firefly modules.

## Inherit the parent, or import the BOM?

You have now seen both mechanisms in one file, which raises the obvious question:
if both pin versions, when do you use which? They are not redundant — they solve
different halves of the problem, and most services want both, as Lumen does.

- **Inherit `fireflyframework-parent`** when you want the *build policy* too: the
  Spring/Cloud BOMs, the compiler and enforcer and Surefire configuration, the Java
  baseline and the `java21` profile. Inheritance is all-or-nothing and single —
  a POM has exactly one parent — so you inherit the parent when Firefly is allowed
  to own your build conventions.
- **Import `fireflyframework-bom`** when you want *only version coherence* for the
  framework modules, with no opinion about plugins or Java level. Import is additive
  and unlimited — you can import several BOMs side by side — so a service that
  already inherits a corporate parent it cannot replace simply imports the Firefly
  BOM and keeps its own build policy.

In other words: inheritance gives you policy *and* versions but costs you your one
parent slot; import gives you versions only but composes freely. Lumen Lending takes
the parent because it is a greenfield reactor and wants Firefly's build conventions,
and *also* imports the BOM because the parent alone does not pin the framework
modules. If your organization mandates its own parent, drop Listing 3.1, keep
Listing 3.3, and you still get version-coherent Firefly dependencies — you just wire
the Java level and plugins yourself.

!!! spring "Spring parity"
    This is the same choice Spring Boot offers. Inheriting
    `spring-boot-starter-parent` gives you plugin management plus the dependency
    BOM; importing `spring-boot-dependencies` as a BOM gives you only the versions,
    leaving you free to keep another parent. Firefly mirrors the pattern exactly, so
    the decision you already know how to make for Spring Boot is the decision you
    make for Firefly.

## CalVer: reading 26.06.01

The version you keep seeing — `26.06.01` — is not SemVer. Firefly uses **CalVer**,
calendar versioning, in a `YY.MM.PATCH` scheme: the `26.06` says this release line
was cut in June 2026, and `01` is the patch within that line. A later patch in the
same line would be `26.06.02`; the next monthly line would be `26.07.0x`.

The point of CalVer here is coordination, not novelty. Every Firefly artifact in a
given line shares the same `YY.MM.PATCH`, so "are these modules from the same
release?" is answered by eye, and upgrading the whole stack is a one-token edit. In
Lumen that token is the `firefly.version` property from Listing 3.3 — bump it once,
and the BOM (and through it every framework module) moves together. Because the
parent and the BOM are the same line, you keep them in lockstep: when you raise
`firefly.version`, raise the `<parent>` version to match.

!!! note "Key term — CalVer (calendar versioning)"
    **CalVer** encodes *when* a release was made rather than the SemVer promise of
    *what changed*. Firefly's `YY.MM.PATCH` (here `26.06.01`) means: year 26, month
    06, patch 01. It makes a fleet legible — a service running `26.06.x` is
    immediately known to be on the June 2026 line — and it makes "upgrade
    everything" a single coherent step instead of a per-module negotiation.

## The Java 25 baseline and the java21 profile

The parent sets the language baseline at **Java 25** (Listing 3.2). That is the
default the reactor compiles against, and it is why the listings throughout this
book use modern Java — records, sealed types, pattern-matching `switch` — without
apology. The reactor you just built ran on it.

Not every shop is on Java 25 the week it ships, so the parent also defines a
`java21` profile. Activating it with `-Pjava21` retargets the build to Java 21 — the
previous long-term-support release — so a team still on 21 can consume the same
Firefly line without forking anything. The profile is the escape hatch; Java 25 is
the road.

```text
# default: build against the Java 25 baseline
mvn verify

# opt down to the Java 21 LTS baseline
mvn -Pjava21 verify
```

!!! warning "The profile changes the target, not the JDK you run"
    `-Pjava21` lowers the *bytecode target and source level* the build compiles to.
    It does not downgrade your installed JDK, and it cannot conjure Java 25 features
    on a Java 21 runtime — code that uses a 25-only API still will not run on a 21
    JVM. Treat the profile as "produce 21-compatible artifacts," not as a way to mix
    language levels within one build.

## The payoff: version-less framework dependencies

Everything so far has been setup in the root. Now open a module and see what it
buys. `core-lending-loan-origination` is the system-of-record service you will build
out in later chapters; here we only read its dependency block.

::: listing core-lending-loan-origination/pom.xml | Listing 3.4 — framework dependencies, declared with no version
    <dependencies>
        <!-- Core/infrastructure-layer microservice starter (WebFlux, EDA, CQRS, resilience). -->
        <dependency>
            <groupId>org.fireflyframework</groupId>
            <artifactId>fireflyframework-starter-core</artifactId>
        </dependency>
        <!-- Reactive persistence (R2DBC) abstractions. -->
        <dependency>
            <groupId>org.fireflyframework</groupId>
            <artifactId>fireflyframework-r2dbc</artifactId>
        </dependency>
        <!-- Reactive web layer helpers (controllers, error handling). -->
        <dependency>
            <groupId>org.fireflyframework</groupId>
            <artifactId>fireflyframework-web</artifactId>
        </dependency>
:::

Look at what is *not* there: no `<version>` on any of them. `starter-core`,
`fireflyframework-r2dbc`, `fireflyframework-web` — each names a `groupId` and an
`artifactId` and stops. The version is supplied by the BOM you imported in the root
(Listing 3.3), resolved through the module's parent chain. This is the entire point
of the chapter made concrete: the module declares *what* it needs, never *which
version*, and a single property in one file decides for all of them.

That is also why the module's own `pom.xml` carries no `<version>` and no
`<groupId>` of its own at the top — it inherits both from the reactor root, so it
only states its `<artifactId>`. The further down the tree you go, the less version
information you write, until at the leaf you write almost none.

!!! spring "Spring parity"
    This is exactly how a `spring-boot-starter-*` dependency looks once you inherit
    or import Spring's BOM — `groupId` and `artifactId`, no version. Firefly extends
    the same convenience to its ~70 modules. If omitting versions on Spring starters
    already feels natural, Firefly asks nothing new of you; it just widens the set of
    artifacts the trick applies to.

## Run it

You do not need to write code to prove the wiring works — building the reactor *is*
the proof. From the reactor root, run a full verify:

```text
mvn -q verify
```

Maven reads the root `pom.xml`, resolves `fireflyframework-parent` and the imported
`fireflyframework-bom`, and uses them to give a concrete version to every
version-less dependency in Listing 3.4. If a single artifact could not be pinned —
a typo'd coordinate, a BOM that did not import — the build would fail at resolution,
before any test ran. It does not. The core module compiles and runs its tests, and
the reactor finishes green.

```text
[INFO] Building Lumen Lending - Core (Loan Origination) 0.1.0-SNAPSHOT    [2/4]
[INFO] Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
...
[INFO] BUILD SUCCESS
```

!!! tip "Checkpoint"
    Run `mvn -q verify` from `samples/lumen-lending` and confirm you see
    `BUILD SUCCESS`. That single line is the version-coherence guarantee paying off:
    a parent, a BOM, one `firefly.version`, and every framework dependency across
    three modules resolved to one conflict-free set — with not one `<version>` on a
    framework artifact.

## What you learned {.recap}

- A Firefly reactor coordinates versions in its **root `pom.xml`** through three
  pieces: an inherited `fireflyframework-parent`, an imported `fireflyframework-bom`,
  and a single `firefly.version` property tying them together.
- **Inherit the parent** to get build policy *and* versions (Spring/Cloud BOMs,
  plugins, the Java baseline, the `java21` profile); **import the BOM** to get
  *only* framework-module versions when you must keep another parent. Lumen does
  both; a corporate-parent shop keeps just the import.
- Firefly uses **CalVer** (`YY.MM.PATCH`, here `26.06.01`), so a whole release line
  moves together and upgrading the stack is a one-token edit.
- The baseline is **Java 25**, with `-Pjava21` as the opt-down profile for teams on
  the previous LTS.
- The payoff is that modules declare framework dependencies — `starter-core`,
  `fireflyframework-r2dbc`, `fireflyframework-web` — with **no `<version>`**, and the
  reactor still builds to `BUILD SUCCESS`.

## Try it yourself {.exercises}

1. **Trace a version to its source.** In `core-lending-loan-origination/pom.xml`,
   pick `fireflyframework-web` and follow how it gets a version: which file pins it,
   and which property feeds that pin? Write the chain in one sentence.
2. **Bump the line.** Change `firefly.version` in `samples/lumen-lending/pom.xml`
   to a later patch (for example `26.06.02`), and update the `<parent>` version to
   match. Run `mvn -q dependency:tree` and observe how the framework artifacts move
   together. Then revert.
3. **Resolve effective versions.** Run `mvn -q help:effective-pom` in the core
   module and search the output for `fireflyframework-starter-core`. Find the
   concrete version Maven injected from the BOM, even though the module's POM names
   none.
4. **Try the LTS profile.** Run `mvn -q -Pjava21 verify` from the reactor root and
   confirm it still reaches `BUILD SUCCESS`. Note in the log that this is the same
   build retargeted, not a different JDK.
5. **Drop the parent, keep the BOM.** On a throwaway copy of
   `samples/lumen-lending/pom.xml`, delete the `<parent>` block and add explicit
   `<groupId>` and `<version>` to the reactor's own coordinates. Predict what breaks
   (hint: the Java baseline and plugin config the parent supplied) before you run it.

## Where to go next

The build is coherent; now you make it *do* something. Chapter 4 turns to
configuration — `application.yml`, profiles, and `@ConfigurationProperties` — so the
same version-locked JAR can run differently in dev and production without a rebuild.
