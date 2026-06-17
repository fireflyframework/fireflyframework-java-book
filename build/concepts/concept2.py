"""Concept 2 — Midnight navy & copper (classic tech).

Deep midnight navy with warm copper/amber and a cool steel-blue secondary; a
confident geometric motif — a subtle reactive-stream / Mono-Flux marble diagram
flourish across the upper field. Authoritative, O'Reilly-meets-modern.
Canvas 1500 x 2100 px (book trim size).

Run:  build/.venv/bin/python build/concepts/concept2.py
      (writes art/previews/concept2.{svg,png})
"""
from __future__ import annotations
from pathlib import Path
from xml.sax.saxutils import escape as _xml_escape
import cairosvg

PREVIEWS = Path(__file__).resolve().parents[2] / "art" / "previews"
W, H = 1500, 2100
NAME = "concept2"

# --- Palette: midnight navy & copper ----------------------------------------
NAVY       = "#0E1A2B"   # deep midnight navy — background
NAVY_2     = "#13243A"   # lifted navy — panel / band
COPPER     = "#D98A3D"   # warm copper/amber — hero
COPPER_BRT = "#F0B36A"   # bright copper — highlights / marbles
COPPER_DEEP= "#A85F22"   # deep copper — shadowed strokes
STEEL      = "#6FA8C7"   # cool steel-blue secondary
STEEL_DIM  = "#3E6178"   # dim steel — timeline rails
PAPER      = "#F2F4F7"   # cool near-white — title text
PAPER_DIM  = "#A9B8C7"   # muted steel-grey — subtitle / publisher

FONT = ("Avenir Next,Avenir,Helvetica Neue,Segoe UI,Helvetica,Arial,sans-serif")
SERIF = ("Iowan Old Style,Palatino,Palatino Linotype,Georgia,"
         "Times New Roman,serif")


def marble_diagram(y0: float) -> str:
    """A reactive-stream marble diagram flourish: two timelines (a Flux and a
    Mono), copper/steel marbles, an operator box, terminal bar/arrow."""
    g: list[str] = []
    L, R = 150, W - 150               # rail extents
    rails = [
        (y0,      "Flux  source",  STEEL,  [200, 360, 560, 760, 960, 1160]),
        (y0+330,  "Mono result",   COPPER, [820]),
    ]
    # operator box between the two rails (map / flatMap)
    opx, opy = W // 2 - 150, y0 + 120
    g.append(
        f'<rect x="{opx}" y="{opy}" width="300" height="88" rx="10" '
        f'fill="{NAVY_2}" stroke="{COPPER}" stroke-width="2"/>'
    )
    g.append(
        f'<text x="{W//2}" y="{opy+54}" text-anchor="middle" fill="{COPPER_BRT}" '
        f'font-size="34" font-weight="600" font-family="{FONT}" '
        f'letter-spacing="1">flatMap()</text>'
    )

    for ry, label, mcol, marbles in rails:
        # rail
        g.append(
            f'<line x1="{L}" y1="{ry}" x2="{R-40}" y2="{ry}" '
            f'stroke="{STEEL_DIM}" stroke-width="2.5"/>'
        )
        # terminal arrowhead
        g.append(
            f'<path d="M{R-40},{ry-12} L{R-10},{ry} L{R-40},{ry+12} Z" '
            f'fill="{STEEL_DIM}"/>'
        )
        # completion tick near the end
        g.append(
            f'<line x1="{R-90}" y1="{ry-22}" x2="{R-90}" y2="{ry+22}" '
            f'stroke="{STEEL}" stroke-width="3"/>'
        )
        # rail label
        g.append(
            f'<text x="{L}" y="{ry-26}" fill="{PAPER_DIM}" font-size="22" '
            f'font-weight="500" letter-spacing="2" font-family="{FONT}">'
            f'{label.upper()}</text>'
        )
        # marbles
        for i, mx in enumerate(marbles):
            r = 26 if mcol is COPPER else 22
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
                f'r="{r*0.30:.1f}" fill="{COPPER_BRT if mcol is COPPER else STEEL}" '
                f'opacity="0.85"/>'
            )
    # faint connecting threads from Flux marbles into the operator
    for mx in [360, 560, 760]:
        g.append(
            f'<path d="M{mx},{y0} C{mx},{y0+60} {W//2},{opy-40} {W//2},{opy}" '
            f'fill="none" stroke="{COPPER}" stroke-width="1.4" opacity="0.30"/>'
        )
    # thread from operator down to the Mono marble
    g.append(
        f'<path d="M{W//2},{opy+88} C{W//2},{y0+260} 820,{y0+260} 820,{y0+330}" '
        f'fill="none" stroke="{COPPER}" stroke-width="1.6" opacity="0.40"/>'
    )
    return "<g>" + "".join(g) + "</g>"


def build_svg() -> str:
    p: list[str] = []

    p.append(
        f'<linearGradient id="sky" x1="0" y1="0" x2="0" y2="1">'
        f'<stop offset="0" stop-color="{NAVY_2}"/>'
        f'<stop offset="0.55" stop-color="{NAVY}"/>'
        f'<stop offset="1" stop-color="#0A1422"/>'
        f'</linearGradient>'
    )
    p.append(f'<rect width="{W}" height="{H}" fill="url(#sky)"/>')

    # faint dotted grid texture in the upper field
    p.append(f'<g fill="{STEEL}" opacity="0.06">')
    for r in range(7):
        for c in range(13):
            p.append(f'<circle cx="{90 + c*110}" cy="{120 + r*110}" r="2.4"/>')
    p.append('</g>')

    # publisher band — top
    p.append(
        f'<text x="{W//2}" y="120" text-anchor="middle" fill="{PAPER_DIM}" '
        f'font-size="25" font-weight="500" letter-spacing="10" '
        f'font-family="{FONT}">FIREFLY SOFTWARE FOUNDATION</text>'
    )

    # the reactive-stream marble diagram flourish
    p.append(marble_diagram(380))

    # copper rule separating motif from title
    RULE_Y = 1080
    p.append(f'<rect x="150" y="{RULE_Y}" width="{W-300}" height="4" '
             f'fill="{COPPER}" rx="2"/>')
    p.append(f'<rect x="150" y="{RULE_Y+12}" width="{W-300}" height="1.5" '
             f'fill="{STEEL}" opacity="0.5"/>')

    # title — left aligned, authoritative
    LX = 150
    p.append(
        f'<text x="{LX}" y="{RULE_Y+180}" fill="{PAPER}" '
        f'font-size="132" font-weight="700" letter-spacing="-2" '
        f'font-family="{FONT}">Firefly for</text>'
    )
    # hero "Java" in copper
    p.append(
        f'<text x="{LX-4}" y="{RULE_Y+400}" fill="{COPPER}" '
        f'font-size="240" font-weight="800" letter-spacing="-4" '
        f'font-family="{FONT}">Java</text>'
    )
    # steel-blue separator
    p.append(
        f'<rect x="{LX}" y="{RULE_Y+454}" width="620" height="3" '
        f'fill="{STEEL}" opacity="0.7" rx="1.5"/>'
    )
    # "by Example" — steel-blue serif italic for contrast
    p.append(
        f'<text x="{LX}" y="{RULE_Y+548}" fill="{STEEL}" '
        f'font-size="92" font-weight="500" font-style="italic" '
        f'font-family="{SERIF}">by Example</text>'
    )

    SUB_Y = RULE_Y + 638
    for i, line in enumerate([
        "Reactive Microservices with Spring Boot,",
        "WebFlux & the Firefly Framework",
    ]):
        p.append(
            f'<text x="{LX}" y="{SUB_Y + i*52}" fill="{PAPER_DIM}" '
            f'font-size="38" font-weight="400" letter-spacing="0.5" '
            f'font-family="{FONT}">{_xml_escape(line)}</text>'
        )

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
    PREVIEWS.mkdir(parents=True, exist_ok=True)
    svg = build_svg()
    (PREVIEWS / f"{NAME}.svg").write_text(svg, encoding="utf-8")
    cairosvg.svg2png(bytestring=svg.encode(),
                     write_to=str(PREVIEWS / f"{NAME}.png"),
                     output_width=W, output_height=H)
    print(f"wrote {NAME}.svg and {NAME}.png")


if __name__ == "__main__":
    main()
