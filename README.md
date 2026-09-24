# Firefly for Java by Example

> *Reactive Microservices with Spring Boot, WebFlux & the Firefly Framework.*

The canonical, build-it-yourself guide to the **Java Firefly Framework** — a metaframework on top of Spring Boot (WebFlux · Project Reactor · R2DBC). You build one real application, a personal-loan origination service (**Lumen Lending**), from an empty folder to a secured, observable, event-driven, three-tier system — making every concept concrete before the next.

Published by the **Firefly Software Foundation** under Apache-2.0, in **American English** and **Spain Spanish (es-ES)**.

## What makes this book different

Every code listing in these pages is a **verbatim slice** of the companion Maven reactor under [`samples/lumen-lending`](samples/lumen-lending), which is **built and tested in CI**. When prose drifts from working code, the build fails. What you read is what actually compiles and passes its tests.

## Building the book

The book is a print-grade EPUB + PDF produced by a self-contained Python (WeasyPrint) pipeline.

```bash
# one-time
python3 -m venv build/.venv
./build/.venv/bin/pip install -r build/requirements.txt

# build (macOS — uses the DYLD shim for cairo/pango)
./build/run.sh --config book.yaml        # English  -> dist/firefly-java-by-example.{epub,pdf}
./build/run.sh --config book.es.yaml     # Spanish  -> dist/firefly-java-by-example-es.{epub,pdf}

# build (Linux / CI)
./build/build-book.sh --config book.yaml
```

Use JDK 25 for the companion application and verify that `mvn -version` reports that JDK. The published framework dependencies used by this sample target Java 25.

Verify the sample reactor, book tooling, and both editions’ listings:

```bash
mvn -f samples/lumen-lending/pom.xml verify
./build/.venv/bin/python -m pytest tests -q
./build/.venv/bin/python build/verify_code.py manuscript samples/lumen-lending
./build/.venv/bin/python build/verify_code.py manuscript-es samples/lumen-lending
```

## Releases

Built editions (EN + ES, EPUB + PDF) are attached to each [GitHub Release](../../releases) on a CalVer (`YY.MM.PATCH`) tag. The release workflow prepares a draft with all four files; publish the draft after reviewing the rendered editions.

## Scope

This book is about the **Java** Firefly Framework only. The Python (PyFly), Rust, Go, and .NET ports, the Python agentic metaframework, the `agentic-bridge`, and the frontend frameworks are parallel projects and are **not** covered here.

---

*Copyright © 2026 Firefly Software Foundation. Licensed under Apache-2.0.*
