# Book cover provenance

Both language editions use the collection title **jafly by example**. Runtime
package identifiers and the existing download filenames remain unchanged.

The canonical source is the private Framework by Firefly digital brand kit,
`Framework-Brand-Kit/11-Books/java/`. The assets use its product-first A2 identity
with the official Firefly endorsement and outlined Manrope typography. The cover
artwork requires no installed fonts. The interior uses the same paper, charcoal,
amber and accessible gold palette, retaining the existing reading and code fonts.

| Edition | Front | Back |
| --- | --- | --- |
| English | `cover.svg`, `cover.png` | `back-cover.svg`, `back-cover.png` |
| Spanish | `cover-es.svg`, `cover-es.png` | `back-cover-es.svg`, `back-cover-es.png` |

All canvases are 1500 × 1850 units/pixels, matching the book's 7.5 × 9.25 inch trim
at 200 pixels per inch. These are individual digital cover faces, not a printer's
wraparound jacket: no bleed, binding allowance, barcode, or production spine is
implied. The brand kit's separate web spine is not part of the PDF or EPUB.

Use `build/gen_cover.py --source /path/to/Framework-Brand-Kit/11-Books/java` to
import an approved revision, then rebuild and visually review both editions.
Running the tool without `--source` validates the checked-in art and rasterizes
missing PNGs. It refuses missing SVGs, wrong dimensions, or live text.

Firefly Software Foundation remains the book's author and publisher. The book's
Apache-2.0 license and existing attribution remain in force; the brand identity
does not change the technical content or imply additional trademark rights.

## Interior identity

`art/brand/` contains exact approved logo and banner exports from
`Framework-Brand-Kit/12-Frameworks/java/`, including the gradient final y.
The light logo appears on title and part-divider pages; the dark logo is embedded
without redrawing it in the 24 compact numbered chapter openers. Regenerate those
with `build/.venv/bin/python build/gen_openers.py` after importing approved logos.
The same opener assets are used by both languages; chapter numbers are decorative
and the localized chapter headings remain the accessible document titles.

The existing code-anatomy figure and concept-preview generator receive palette
changes only. Their geometry and technical text are preserved. Syntax colors and
warning colors retain semantic distinctions.
