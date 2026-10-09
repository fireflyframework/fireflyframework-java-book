# Changelog

All notable changes to *jafly by example* are documented here.
The project uses CalVer (`YY.MM.PATCH`). The `release.yml` workflow extracts the
section matching a `v*.*.*` tag as the GitHub Release notes.

## [Unreleased]

### Changed

- Adopt the shared Framework by Firefly identity for English and Spanish front and back covers.
- Introduce *jafly by example* as the collection title across both editions, with localized enterprise-architecture subtitles. Existing package identifiers and download filenames remain unchanged.
- Place PDF covers on full trim pages without running headers or page numbers.
- Include localized front and back cover documents in EPUB reading order, and retain chapter navigation.
- Include publisher metadata in both formats and rights metadata in EPUB.
- Replace the retired cover generator with canonical artwork validation and import.
- Apply the shared logo and palette to title pages, part dividers and chapter openers.
- Render localized chapter and prelude titles when the manuscript relies on manifest headings.

The technical manuscript and companion application are unchanged.

## [26.09.01] — 2026-09-24

### Fixed

- Wrap ordinary fenced code blocks in PDF and EPUB, matching the named listings, so long lines remain readable.
- Use JDK 25 for the companion application gates, matching the published framework dependencies.
- Install the book-tooling test dependency and pin the renderer dependencies.
- Validate both English and Spanish listings and run the tooling tests before publishing all four download formats.

The manuscript and companion application content are unchanged from the previous edition.

## [26.06.01] — 2026-06-17

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
