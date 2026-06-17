"""Concept 3 — Warm editorial light (cream).

An ivory/cream ground, deep-ink title, firefly-green plus a warm amber accent,
generous whitespace, and a single fine-lined illustrative motif (a delicately
drawn firefly with a looping flight path). Minimal, elegant — a "designed book",
the opposite of the dark covers. Canvas 1500 x 2100 px (book trim size).

Run:  build/.venv/bin/python build/concepts/concept3.py
      (writes art/previews/concept3.{svg,png})
"""
from __future__ import annotations
from pathlib import Path
from xml.sax.saxutils import escape as _xml_escape
import cairosvg

PREVIEWS = Path(__file__).resolve().parents[2] / "art" / "previews"
W, H = 1500, 2100
NAME = "concept3"

# --- Palette: warm editorial light ------------------------------------------
CREAM      = "#F7F1E3"   # ivory/cream — background
CREAM_2    = "#EFE7D3"   # slightly deeper cream — faint panels / rules
INK        = "#1A1B18"   # deep ink — title text
INK_DIM    = "#5C5A50"   # soft ink-grey — subtitle / publisher
GREEN      = "#2E7D32"   # firefly-green — hero accent
GREEN_DEEP = "#1F5C24"   # deep green
AMBER      = "#D9952B"   # warm amber accent
AMBER_BRT  = "#F0B651"   # bright amber — glow core

FONT = ("Iowan Old Style,Palatino,Palatino Linotype,Hoefler Text,Georgia,"
        "Times New Roman,serif")
SANS = ("Avenir Next,Avenir,Helvetica Neue,Helvetica,Arial,sans-serif")


def fineline_firefly(cx: float, cy: float, scale: float = 1.0) -> str:
    """A single fine-lined illustrative firefly with a looping flight path —
    drawn in ink line-work, lit by one small amber glow."""
    g: list[str] = []
    g.append(f'<g transform="translate({cx},{cy}) scale({scale})">')
    # looping flight path — one elegant continuous fine line
    g.append(
        f'<path d="M-300,120 C-220,30 -120,-40 -40,-30 C40,-20 70,60 0,80 '
        f'C-70,100 -60,10 10,-20 C90,-52 200,-40 280,40" '
        f'fill="none" stroke="{AMBER}" stroke-width="2.2" opacity="0.55" '
        f'stroke-linecap="round"/>'
    )
    # small dots punctuating the path (fading trail)
    for dx, dy, op in [(-300,120,0.30),(-150,-22,0.45),(60,68,0.40),
                       (200,-30,0.55),(280,40,0.70)]:
        g.append(f'<circle cx="{dx}" cy="{dy}" r="3.2" fill="{AMBER}" '
                 f'opacity="{op}"/>')
    # the firefly, fine ink line-work, sitting at the head of the path
    g.append('<g transform="translate(40,-40) rotate(-18)">')
    # one warm amber glow at the abdomen
    g.append(f'<circle cx="0" cy="40" r="34" fill="{AMBER_BRT}" opacity="0.30"/>')
    g.append(f'<circle cx="0" cy="40" r="16" fill="{AMBER}" opacity="0.55"/>')
    # wings — open outlines
    g.append(f'<path d="M-4,-6 C-56,-44 -78,-2 -22,12" fill="none" '
             f'stroke="{INK}" stroke-width="2" opacity="0.85"/>')
    g.append(f'<path d="M4,-6 C56,-44 78,-2 22,12" fill="none" '
             f'stroke="{INK}" stroke-width="2" opacity="0.85"/>')
    # body — abdomen filled green, thorax/head outlined
    g.append(f'<ellipse cx="0" cy="40" rx="14" ry="22" fill="{GREEN}" '
             f'stroke="{GREEN_DEEP}" stroke-width="1.5"/>')
    g.append(f'<ellipse cx="0" cy="4" rx="11" ry="16" fill="{CREAM}" '
             f'stroke="{INK}" stroke-width="2"/>')
    g.append(f'<circle cx="0" cy="-18" r="8" fill="{CREAM}" '
             f'stroke="{INK}" stroke-width="2"/>')
    # antennae
    g.append(f'<path d="M-4,-24 C-12,-38 -18,-42 -24,-46" fill="none" '
             f'stroke="{INK}" stroke-width="1.8" stroke-linecap="round"/>')
    g.append(f'<path d="M4,-24 C12,-38 18,-42 24,-46" fill="none" '
             f'stroke="{INK}" stroke-width="1.8" stroke-linecap="round"/>')
    g.append('</g>')
    g.append('</g>')
    return "".join(g)


def build_svg() -> str:
    p: list[str] = []

    # background
    p.append(f'<rect width="{W}" height="{H}" fill="{CREAM}"/>')
    # a faint deeper-cream baseline band grounding the title (very subtle)
    p.append(f'<rect x="0" y="1180" width="{W}" height="{H-1180}" '
             f'fill="{CREAM_2}" opacity="0.45"/>')

    # thin double frame, ink — editorial restraint
    p.append(f'<rect x="70" y="70" width="{W-140}" height="{H-140}" fill="none" '
             f'stroke="{INK}" stroke-width="1.5" opacity="0.55"/>')
    p.append(f'<rect x="80" y="80" width="{W-160}" height="{H-160}" fill="none" '
             f'stroke="{INK}" stroke-width="0.6" opacity="0.35"/>')

    cx = W // 2

    # publisher — top, ink-grey, wide tracking
    p.append(
        f'<text x="{cx}" y="172" text-anchor="middle" fill="{INK_DIM}" '
        f'font-size="24" font-weight="500" letter-spacing="9" '
        f'font-family="{SANS}">FIREFLY SOFTWARE FOUNDATION</text>'
    )
    # a small green-amber rule under the publisher
    p.append(f'<rect x="{cx-60}" y="200" width="120" height="2" fill="{GREEN}" '
             f'rx="1"/>')

    # single fine-lined illustrative motif — generous whitespace around it
    p.append(fineline_firefly(cx, 560, scale=1.15))

    # delicate centred rule above the title
    RULE_Y = 1050
    p.append(f'<rect x="{cx-260}" y="{RULE_Y}" width="200" height="2" '
             f'fill="{INK}" opacity="0.6"/>')
    p.append(f'<circle cx="{cx}" cy="{RULE_Y+1}" r="5" fill="{AMBER}"/>')
    p.append(f'<rect x="{cx+60}" y="{RULE_Y}" width="200" height="2" '
             f'fill="{INK}" opacity="0.6"/>')

    # title — centred serif, deep ink
    p.append(
        f'<text x="{cx}" y="{RULE_Y+165}" text-anchor="middle" fill="{INK}" '
        f'font-size="120" font-weight="600" letter-spacing="0.5" '
        f'font-family="{FONT}">Firefly for</text>'
    )
    # hero "Java" — large; ink with a green underline accent
    p.append(
        f'<text x="{cx}" y="{RULE_Y+372}" text-anchor="middle" fill="{INK}" '
        f'font-size="236" font-weight="700" letter-spacing="1" '
        f'font-family="{FONT}">Java</text>'
    )
    # green accent underline beneath Java + amber tick
    p.append(f'<rect x="{cx-180}" y="{RULE_Y+402}" width="360" height="4" '
             f'fill="{GREEN}" rx="2"/>')
    p.append(f'<circle cx="{cx}" cy="{RULE_Y+404}" r="9" fill="{AMBER}"/>')
    # "by Example" — green italic serif
    p.append(
        f'<text x="{cx}" y="{RULE_Y+520}" text-anchor="middle" fill="{GREEN}" '
        f'font-size="92" font-weight="500" font-style="italic" '
        f'font-family="{FONT}">by Example</text>'
    )

    SUB_Y = RULE_Y + 612
    for i, line in enumerate([
        "Reactive Microservices with Spring Boot,",
        "WebFlux & the Firefly Framework",
    ]):
        p.append(
            f'<text x="{cx}" y="{SUB_Y + i*52}" text-anchor="middle" '
            f'fill="{INK_DIM}" font-size="37" font-weight="400" '
            f'letter-spacing="0.5" font-family="{SANS}">{_xml_escape(line)}</text>'
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
