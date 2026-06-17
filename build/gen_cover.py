"""Generate art/cover.svg and art/cover.png for *Firefly for Java by Example*.

A clean, self-contained typographic cover in the "firefly amber + Java-logo"
palette: a deep Java-navy field, the official Java logo's blue and orange as the
hero pairing, and firefly amber as the warm accent. The composition adapts
concept2 (the reactive-stream marble diagram on deep navy): a Flux source rail of
Java-blue marbles flows through a Java-orange ``flatMap()`` box down to a single
amber Mono marble. The palette has no leaf tones at all, and there is NO external
logo dependency — everything is drawn inline as SVG, so the generator needs nothing under
~/Downloads or art/logo/. The canvas is 1500 x 2100 px (7.5 x 9.25 in at 200 dpi)
— the book's trim size.

Run:  build/.venv/bin/python build/gen_cover.py     (writes art/cover.{svg,png})
"""
from __future__ import annotations
from pathlib import Path
from xml.sax.saxutils import escape as _xml_escape
import cairosvg

ART = Path(__file__).resolve().parents[1] / "art"
W, H = 1500, 2100  # 7.5 x 9.25 in at 200 dpi

# ---------------------------------------------------------------------------
# Palette ("firefly amber + Java logo" — Java navy base, Java blue + orange
# as the hero pairing, firefly amber as the warm accent). No leaf tones.
# ---------------------------------------------------------------------------
NAVY        = "#0E2233"   # Java dark navy — background
NAVY_2      = "#123047"   # lifted navy — gradient top / panel / box fill
NAVY_DEEP   = "#0A1B29"   # deepest navy — gradient bottom
JAVA_BLUE   = "#5382A1"   # Java-logo blue — secondary accent, marbles, "by Example"
JAVA_BLUE_LT= "#7FB0CE"   # lighter Java blue — marble glints, separators
JAVA_BLUE_DIM = "#3E6178" # dim blue — timeline rails
JAVA_ORANGE = "#E76F00"   # Java-logo orange — HERO color: "Java", flatMap box
JAVA_ORANGE_LT = "#F89820" # lighter Java orange — highlights
AMBER       = "#E8B33A"   # firefly amber — accent rules, Mono marble, highlights
AMBER_LT    = "#F2C961"   # bright amber — Mono marble glint
CREAM       = "#F5EFE2"   # cream — "Firefly for" + body text on dark
WARM_COOL   = "#A9B7C2"   # cool warm-muted — publisher / secondary text
WARM_WARM   = "#C9B896"   # warm warm-muted — subtitle

FONT = "Avenir Next,Avenir,Helvetica Neue,Segoe UI,Helvetica,Arial,sans-serif"
SERIF = ("Iowan Old Style,Palatino,Palatino Linotype,Georgia,"
         "Times New Roman,serif")


# ---------------------------------------------------------------------------
# Reactive-stream marble diagram flourish (adapted from concept2)
# ---------------------------------------------------------------------------
def marble_diagram(y0: float) -> str:
    """Two timelines: a Flux source (a row of Java-blue marbles) flowing through
    a Java-orange flatMap() box down to a single amber Mono marble."""
    g: list[str] = []
    L, R = 150, W - 150               # rail extents
    rails = [
        (y0,      "Flux  source",  JAVA_BLUE,   JAVA_BLUE_LT,
         [200, 360, 560, 760, 960, 1160]),
        (y0 + 330, "Mono result",  AMBER,       AMBER_LT, [820]),
    ]
    # operator box between the two rails (flatMap) — Java-orange hero
    opx, opy = W // 2 - 150, y0 + 120
    g.append(
        f'<rect x="{opx}" y="{opy}" width="300" height="88" rx="10" '
        f'fill="{NAVY_2}" stroke="{JAVA_ORANGE}" stroke-width="2.5"/>'
    )
    g.append(
        f'<text x="{W//2}" y="{opy+54}" text-anchor="middle" fill="{JAVA_ORANGE_LT}" '
        f'font-size="34" font-weight="600" font-family="{FONT}" '
        f'letter-spacing="1">flatMap()</text>'
    )

    for ry, label, mcol, glint, marbles in rails:
        # rail
        g.append(
            f'<line x1="{L}" y1="{ry}" x2="{R-40}" y2="{ry}" '
            f'stroke="{JAVA_BLUE_DIM}" stroke-width="2.5"/>'
        )
        # terminal arrowhead
        g.append(
            f'<path d="M{R-40},{ry-12} L{R-10},{ry} L{R-40},{ry+12} Z" '
            f'fill="{JAVA_BLUE_DIM}"/>'
        )
        # completion tick near the end
        g.append(
            f'<line x1="{R-90}" y1="{ry-22}" x2="{R-90}" y2="{ry+22}" '
            f'stroke="{JAVA_BLUE}" stroke-width="3"/>'
        )
        # rail label
        g.append(
            f'<text x="{L}" y="{ry-26}" fill="{WARM_COOL}" font-size="22" '
            f'font-weight="500" letter-spacing="2" font-family="{FONT}">'
            f'{label.upper()}</text>'
        )
        # marbles
        for mx in marbles:
            r = 26 if mcol is AMBER else 22
            g.append(
                f'<circle cx="{mx}" cy="{ry}" r="{r+7}" fill="{mcol}" '
                f'opacity="0.16"/>'
            )
            g.append(
                f'<circle cx="{mx}" cy="{ry}" r="{r}" fill="{NAVY}" '
                f'stroke="{mcol}" stroke-width="3"/>'
            )
            g.append(
                f'<circle cx="{mx-r*0.32:.0f}" cy="{ry-r*0.32:.0f}" '
                f'r="{r*0.30:.1f}" fill="{glint}" opacity="0.85"/>'
            )
    # faint connecting threads from Flux marbles into the operator
    for mx in [360, 560, 760]:
        g.append(
            f'<path d="M{mx},{y0} C{mx},{y0+60} {W//2},{opy-40} {W//2},{opy}" '
            f'fill="none" stroke="{JAVA_ORANGE}" stroke-width="1.4" opacity="0.32"/>'
        )
    # thread from operator down to the Mono (amber) marble
    g.append(
        f'<path d="M{W//2},{opy+88} C{W//2},{y0+260} 820,{y0+260} 820,{y0+330}" '
        f'fill="none" stroke="{AMBER}" stroke-width="1.6" opacity="0.42"/>'
    )
    return "<g>" + "".join(g) + "</g>"


# ---------------------------------------------------------------------------
# Build SVG
# ---------------------------------------------------------------------------
def build_svg() -> str:
    p: list[str] = []

    # background — deep Java navy with a subtle vertical gradient
    p.append(
        f'<linearGradient id="sky" x1="0" y1="0" x2="0" y2="1">'
        f'<stop offset="0" stop-color="{NAVY_2}"/>'
        f'<stop offset="0.55" stop-color="{NAVY}"/>'
        f'<stop offset="1" stop-color="{NAVY_DEEP}"/>'
        f'</linearGradient>'
    )
    p.append(f'<rect width="{W}" height="{H}" fill="url(#sky)"/>')

    # faint dotted grid texture in the upper field (Java-blue)
    p.append(f'<g fill="{JAVA_BLUE}" opacity="0.06">')
    for r in range(7):
        for c in range(13):
            p.append(f'<circle cx="{90 + c*110}" cy="{120 + r*110}" r="2.4"/>')
    p.append('</g>')

    # publisher band — top
    p.append(
        f'<text x="{W//2}" y="120" text-anchor="middle" fill="{WARM_COOL}" '
        f'font-size="25" font-weight="500" letter-spacing="10" '
        f'font-family="{FONT}">FIREFLY SOFTWARE FOUNDATION</text>'
    )

    # the reactive-stream marble diagram flourish
    p.append(marble_diagram(380))

    # amber rule separating motif from title (firefly amber hero accent)
    RULE_Y = 1080
    p.append(f'<rect x="150" y="{RULE_Y}" width="{W-300}" height="5" '
             f'fill="{AMBER}" rx="2.5"/>')
    p.append(f'<rect x="150" y="{RULE_Y+14}" width="{W-300}" height="1.5" '
             f'fill="{JAVA_BLUE}" opacity="0.5"/>')

    # title — left aligned, authoritative
    LX = 150
    # "Firefly for" — cream
    p.append(
        f'<text x="{LX}" y="{RULE_Y+180}" fill="{CREAM}" '
        f'font-size="132" font-weight="700" letter-spacing="-2" '
        f'font-family="{FONT}">Firefly for</text>'
    )
    # hero "Java" — the BIG Java-orange standout word
    p.append(
        f'<text x="{LX-4}" y="{RULE_Y+400}" fill="{JAVA_ORANGE}" '
        f'font-size="240" font-weight="800" letter-spacing="-4" '
        f'font-family="{FONT}">Java</text>'
    )
    # Java-blue separator under "Java"
    p.append(
        f'<rect x="{LX}" y="{RULE_Y+454}" width="620" height="3" '
        f'fill="{JAVA_BLUE}" opacity="0.75" rx="1.5"/>'
    )
    # "by Example" — Java-blue serif italic for contrast
    p.append(
        f'<text x="{LX}" y="{RULE_Y+548}" fill="{JAVA_BLUE}" '
        f'font-size="92" font-weight="500" font-style="italic" '
        f'font-family="{SERIF}">by Example</text>'
    )

    # subtitle (two lines) — warm-muted
    SUB_Y = RULE_Y + 638
    for i, line in enumerate([
        "Reactive Microservices with Spring Boot,",
        "WebFlux & the Firefly Framework",
    ]):
        p.append(
            f'<text x="{LX}" y="{SUB_Y + i*52}" fill="{WARM_WARM}" '
            f'font-size="38" font-weight="400" letter-spacing="0.5" '
            f'font-family="{FONT}">{_xml_escape(line)}</text>'
        )

    # -------------------------------------------------------------------------
    # Assemble: hoist gradient elements into <defs>
    # -------------------------------------------------------------------------
    defs_tags = ("<radialGradient", "<linearGradient")
    defs = [x for x in p if x.startswith(defs_tags)]
    body = [x for x in p if not x.startswith(defs_tags)]
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" '
        f'xmlns:xlink="http://www.w3.org/1999/xlink" '
        f'width="{W}" height="{H}" viewBox="0 0 {W} {H}">\n'
        f'<defs>{"".join(defs)}</defs>\n'
        + "\n".join(body) + "\n</svg>"
    )


def main() -> None:
    ART.mkdir(parents=True, exist_ok=True)
    svg = build_svg()
    (ART / "cover.svg").write_text(svg, encoding="utf-8")
    cairosvg.svg2png(
        bytestring=svg.encode(),
        write_to=str(ART / "cover.png"),
        output_width=W,
        output_height=H,
    )
    print("wrote cover.svg and cover.png")


if __name__ == "__main__":
    main()
