# Changelog

All notable changes to *Firefly for Java by Example* are documented here.
The project uses CalVer (`YY.MM.PATCH`). The `release.yml` workflow extracts the
section matching a `v*.*.*` tag as the GitHub Release notes.

## [Unreleased]

### Added — first complete edition (English + Spanish)

- **Full manuscript (English, American):** a Prelude (Spring Boot · WebFlux ·
  Reactor · R2DBC), 24 chapters across five parts, four appendices, and a glossary
  — the complete guided build of **Lumen Lending** (apply → score → decision →
  offer → accept) across the experience, domain, core, and data tiers.
- **Full manuscript (Spanish, Spain / es-ES):** the entire book translated, with
  code, identifiers, and annotations kept in English and only prose, captions,
  callouts, and headings localized.
- **Companion reactor `samples/lumen-lending`:** a three-module Maven reactor
  (core / domain / experience) on the Firefly Framework `26.06.01`, building green
  with **33 tests** and no Docker (H2 + in-JVM EDA). Every code listing in the book
  is a verbatim slice of this reactor, enforced by `build/verify_code.py`.
- **Distinct visual identity:** the "Reactive Java at dusk" cover and per-chapter
  openers — espresso base, amber-gold hero, firefly-green spark — plus a warm
  green+amber interior theme.
- **Bilingual build pipeline:** WeasyPrint EPUB + PDF for both editions, the Java
  listing verifier, and CI (`reactor` matrix + bilingual `book` build) and
  `release.yml` (CalVer tag → EN+ES EPUB+PDF Release assets).
- Dedicated to **Nacho Álvarez**.
