"""Concept 1 — Refined espresso & gold (premium dark).

The current direction, elevated: a deep warm espresso ground, a refined gold
hero word "Java", crisp ivory title, a restrained firefly-green accent, and a
single elegant glowing-firefly focal mark. Less busy than the EDA-network
cover — more premium book-jacket. Canvas 1500 x 2100 px (book trim size).

Run:  build/.venv/bin/python build/concepts/concept1.py
      (writes art/previews/concept1.{svg,png})
"""
from __future__ import annotations
from pathlib import Path
from xml.sax.saxutils import escape as _xml_escape
import cairosvg

PREVIEWS = Path(__file__).resolve().parents[2] / "art" / "previews"
W, H = 1500, 2100
NAME = "concept1"

# --- Palette: refined espresso & gold ---------------------------------------
ESPRESSO   = "#17120D"   # deep warm espresso — background
ESPRESSO_2 = "#241B12"   # slightly lifted espresso — vignette / panel
GOLD       = "#E3B23C"   # refined gold — hero word "Java"
GOLD_BRT   = "#F6D27A"   # bright gold — highlights / glow core
GOLD_DEEP  = "#A87A20"   # deep gold — fine rules in shadow
IVORY      = "#F4EEE0"   # crisp ivory — title text
IVORY_DIM  = "#CDBFA6"   # warm muted ivory — subtitle / publisher
GREEN      = "#4FB23A"   # restrained firefly-green accent
GREEN_DEEP = "#2F7D26"   # deep green

FONT = ("Optima,Palatino,Palatino Linotype,Hoefler Text,Georgia,"
        "Times New Roman,serif")
SANS = ("Avenir Next,Avenir,Helvetica Neue,Helvetica,Arial,sans-serif")


def firefly(cx: float, cy: float, scale: float = 1.0) -> str:
    """A single elegant glowing firefly — soft gold bloom, green-rimmed body."""
    return (
        f'<g transform="translate({cx},{cy}) scale({scale})">'
        # soft layered bloom
        f'<circle r="150" fill="url(#bloom)"/>'
        f'<circle r="60"  fill="{GOLD}" opacity="0.10"/>'
        f'<circle r="34"  fill="{GOLD_BRT}" opacity="0.18"/>'
        # a single light trail sweeping up from the lower-left
        f'<path d="M-170,168 C-96,84 -46,30 -10,-6" fill="none" '
        f'stroke="{GOLD}" stroke-width="3" opacity="0.20" stroke-linecap="round"/>'
        f'<path d="M-170,168 C-96,84 -46,30 -10,-6" fill="none" '
        f'stroke="{GOLD_BRT}" stroke-width="1.2" opacity="0.40" stroke-linecap="round"/>'
        '<g transform="rotate(-15)">'
        # glowing abdomen
        f'<circle cx="0" cy="50" r="40" fill="{GOLD}" opacity="0.22"/>'
        f'<circle cx="0" cy="50" r="22" fill="{GOLD_BRT}" opacity="0.55"/>'
        # translucent ivory wings
        f'<path d="M-5,-8 C-66,-50 -88,-2 -26,14 Z" fill="{IVORY}" opacity="0.16"/>'
        f'<path d="M5,-8 C66,-50 88,-2 26,14 Z" fill="{IVORY}" opacity="0.16"/>'
        f'<ellipse cx="0" cy="46" rx="18" ry="27" fill="{GOLD}"/>'
        f'<ellipse cx="0" cy="49" rx="10" ry="16" fill="#FFF3D6"/>'
        # dark body with green rim (the brand spark)
        f'<ellipse cx="0" cy="4" rx="15" ry="22" fill="{ESPRESSO}" '
        f'stroke="{GREEN}" stroke-width="2.6"/>'
        f'<ellipse cx="0" cy="-22" rx="9" ry="11" fill="{ESPRESSO}" '
        f'stroke="{GREEN}" stroke-width="2.2"/>'
        f'<path d="M-5,-30 C-15,-47 -22,-51 -28,-57" fill="none" '
        f'stroke="{GREEN}" stroke-width="2.2" stroke-linecap="round"/>'
        f'<path d="M5,-30 C15,-47 22,-51 28,-57" fill="none" '
        f'stroke="{GREEN}" stroke-width="2.2" stroke-linecap="round"/>'
        '</g>'
        # a few quiet satellite glints
        f'<circle cx="98" cy="-88" r="3.0" fill="{GOLD_BRT}" opacity="0.70"/>'
        f'<circle cx="118" cy="44" r="2.4" fill="{GREEN}" opacity="0.80"/>'
        f'<circle cx="-104" cy="-66" r="2.4" fill="{GOLD_BRT}" opacity="0.55"/>'
        '</g>'
    )


def build_svg() -> str:
    p: list[str] = []

    # defs: radial vignette + firefly bloom
    p.append(
        f'<radialGradient id="vignette" cx="0.5" cy="0.40" r="0.75">'
        f'<stop offset="0" stop-color="{ESPRESSO_2}"/>'
        f'<stop offset="1" stop-color="{ESPRESSO}"/>'
        f'</radialGradient>'
    )
    p.append(
        f'<radialGradient id="bloom" cx="0.5" cy="0.5" r="0.5">'
        f'<stop offset="0" stop-color="{GOLD_BRT}" stop-opacity="0.30"/>'
        f'<stop offset="0.45" stop-color="{GOLD}" stop-opacity="0.10"/>'
        f'<stop offset="1" stop-color="{GOLD}" stop-opacity="0"/>'
        f'</radialGradient>'
    )

    # background
    p.append(f'<rect width="{W}" height="{H}" fill="{ESPRESSO}"/>')
    p.append(f'<rect width="{W}" height="{H}" fill="url(#vignette)"/>')

    # a slim gold frame inset — premium jacket feel
    p.append(
        f'<rect x="56" y="56" width="{W-112}" height="{H-112}" fill="none" '
        f'stroke="{GOLD_DEEP}" stroke-width="1.5" opacity="0.55" rx="6"/>'
    )

    # publisher — top, small caps, wide tracking
    p.append(
        f'<text x="{W//2}" y="150" text-anchor="middle" fill="{IVORY_DIM}" '
        f'font-size="25" font-weight="500" letter-spacing="9" '
        f'font-family="{SANS}">FIREFLY SOFTWARE FOUNDATION</text>'
    )

    # focal firefly — sits in the upper third, the single hero illustration
    p.append(firefly(W // 2, 560, scale=1.18))

    # twin gold rules above the title block
    RULE_Y = 1090
    p.append(f'<rect x="150" y="{RULE_Y}" width="{W-300}" height="3" '
             f'fill="{GOLD}" rx="1.5"/>')
    p.append(f'<rect x="150" y="{RULE_Y+11}" width="{W-300}" height="1" '
             f'fill="{GREEN}" opacity="0.55"/>')

    # title block, centred for jacket symmetry
    cx = W // 2
    p.append(
        f'<text x="{cx}" y="{RULE_Y+185}" text-anchor="middle" fill="{IVORY}" '
        f'font-size="128" font-weight="600" letter-spacing="1" '
        f'font-family="{FONT}">Firefly for</text>'
    )
    # hero word "Java" in refined gold
    p.append(
        f'<text x="{cx}" y="{RULE_Y+400}" text-anchor="middle" fill="{GOLD}" '
        f'font-size="248" font-weight="700" letter-spacing="2" '
        f'font-family="{FONT}">Java</text>'
    )
    # green spark separator
    p.append(
        f'<rect x="{cx-150}" y="{RULE_Y+438}" width="300" height="2.5" '
        f'fill="{GREEN}" opacity="0.70" rx="1.25"/>'
    )
    # "by Example" — green, italic serif
    p.append(
        f'<text x="{cx}" y="{RULE_Y+560}" text-anchor="middle" fill="{GREEN}" '
        f'font-size="96" font-weight="500" font-style="italic" '
        f'letter-spacing="1" font-family="{FONT}">by Example</text>'
    )

    # subtitle (two lines)
    SUB_Y = RULE_Y + 660
    for i, line in enumerate([
        "Reactive Microservices with Spring Boot,",
        "WebFlux & the Firefly Framework",
    ]):
        p.append(
            f'<text x="{cx}" y="{SUB_Y + i*52}" text-anchor="middle" '
            f'fill="{IVORY_DIM}" font-size="38" font-weight="400" '
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
