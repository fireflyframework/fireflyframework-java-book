"""Concept 4 — Modern slate & firefly-green (techy).

Charcoal-slate ground with a vivid firefly-green hero and a single warm amber
spark accent; a clean modern grid / dusk motif — a luminous green node-grid
fading into a dusk gradient, with one amber spark drifting free. Crisp and
contemporary. Canvas 1500 x 2100 px (book trim size).

Run:  build/.venv/bin/python build/concepts/concept4.py
      (writes art/previews/concept4.{svg,png})
"""
from __future__ import annotations
from pathlib import Path
from xml.sax.saxutils import escape as _xml_escape
import cairosvg

PREVIEWS = Path(__file__).resolve().parents[2] / "art" / "previews"
W, H = 1500, 2100
NAME = "concept4"

# --- Palette: modern slate & firefly-green ----------------------------------
SLATE      = "#1C2024"   # charcoal-slate — background
SLATE_2    = "#262B30"   # lifted slate — dusk band
SLATE_DUSK = "#11161C"   # deep dusk floor
GREEN      = "#43B02A"   # vivid firefly-green — hero
GREEN_BRT  = "#74D85A"   # bright green — node highlights
GREEN_DEEP = "#2C7A1C"   # deep green — grid lines in shadow
AMBER      = "#F0A828"   # warm amber spark — single accent
AMBER_BRT  = "#FFC558"   # bright amber — spark core
PAPER      = "#EDF1EE"   # crisp near-white — title text
PAPER_DIM  = "#9AA6A0"   # muted grey-green — subtitle / publisher

FONT = ("Avenir Next,Avenir,Helvetica Neue,Segoe UI,Helvetica,Arial,sans-serif")
MONO = ("SF Mono,Menlo,DejaVu Sans Mono,Consolas,monospace")


def grid_motif(y_top: float, y_bot: float) -> str:
    """A clean modern node-grid that fades upward into dusk; green nodes on
    the intersections, brighter toward the centre, one amber spark drifting."""
    g: list[str] = []
    cols, rows = 9, 6
    x0, x1 = 150, W - 150
    step_x = (x1 - x0) / (cols - 1)
    step_y = (y_bot - y_top) / (rows - 1)
    midc, midr = (cols - 1) / 2, (rows - 1) / 2

    # grid lines (thin, dim green)
    g.append(f'<g stroke="{GREEN_DEEP}" stroke-width="1" opacity="0.30">')
    for c in range(cols):
        x = x0 + c * step_x
        g.append(f'<line x1="{x:.1f}" y1="{y_top}" x2="{x:.1f}" y2="{y_bot}"/>')
    for r in range(rows):
        y = y_top + r * step_y
        g.append(f'<line x1="{x0}" y1="{y:.1f}" x2="{x1}" y2="{y:.1f}"/>')
    g.append('</g>')

    # nodes — size & brightness rise toward grid centre (focal swell)
    for c in range(cols):
        for r in range(rows):
            x = x0 + c * step_x
            y = y_top + r * step_y
            d = ((c - midc) ** 2 + (r - midr) ** 2) ** 0.5
            t = max(0.0, 1.0 - d / 4.2)            # 0..1 nearness to centre
            rad = 3.0 + 7.5 * t
            op = 0.30 + 0.65 * t
            if t > 0.55:
                g.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="{rad+8:.1f}" '
                         f'fill="{GREEN}" opacity="{0.10*t:.2f}"/>')
            fill = GREEN_BRT if t > 0.6 else GREEN
            g.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="{rad:.1f}" '
                     f'fill="{fill}" opacity="{op:.2f}"/>')

    # a single amber spark drifting free of the grid (the one accent)
    sx, sy = x0 + 6.2 * step_x, y_top + 0.7 * step_y
    g.append(f'<circle cx="{sx:.1f}" cy="{sy:.1f}" r="30" fill="{AMBER}" '
             f'opacity="0.18"/>')
    g.append(f'<circle cx="{sx:.1f}" cy="{sy:.1f}" r="14" fill="{AMBER}" '
             f'opacity="0.45"/>')
    g.append(f'<circle cx="{sx:.1f}" cy="{sy:.1f}" r="7" fill="{AMBER_BRT}"/>')
    # spark trail
    g.append(f'<path d="M{sx-90:.1f},{sy+70:.1f} C{sx-50:.1f},{sy+30:.1f} '
             f'{sx-22:.1f},{sy+12:.1f} {sx:.1f},{sy:.1f}" fill="none" '
             f'stroke="{AMBER}" stroke-width="2" opacity="0.40" '
             f'stroke-linecap="round"/>')
    return "<g>" + "".join(g) + "</g>"


def build_svg() -> str:
    p: list[str] = []

    # dusk gradient background
    p.append(
        f'<linearGradient id="dusk" x1="0" y1="0" x2="0" y2="1">'
        f'<stop offset="0" stop-color="{SLATE_2}"/>'
        f'<stop offset="0.5" stop-color="{SLATE}"/>'
        f'<stop offset="1" stop-color="{SLATE_DUSK}"/>'
        f'</linearGradient>'
    )
    p.append(f'<rect width="{W}" height="{H}" fill="url(#dusk)"/>')

    # clean modern grid / dusk motif
    p.append(grid_motif(220, 980))

    cx = W // 2

    # publisher — top, mono-ish, wide tracking
    p.append(
        f'<text x="{cx}" y="140" text-anchor="middle" fill="{PAPER_DIM}" '
        f'font-size="24" font-weight="500" letter-spacing="8" '
        f'font-family="{MONO}">FIREFLY SOFTWARE FOUNDATION</text>'
    )

    # green hero rule above the title
    RULE_Y = 1080
    p.append(f'<rect x="150" y="{RULE_Y}" width="{W-300}" height="4" '
             f'fill="{GREEN}" rx="2"/>')
    p.append(f'<rect x="150" y="{RULE_Y+12}" width="160" height="2" '
             f'fill="{AMBER}" rx="1"/>')

    # title — left aligned, crisp sans
    LX = 150
    p.append(
        f'<text x="{LX}" y="{RULE_Y+178}" fill="{PAPER}" '
        f'font-size="132" font-weight="700" letter-spacing="-2" '
        f'font-family="{FONT}">Firefly for</text>'
    )
    # hero "Java" — vivid firefly-green
    p.append(
        f'<text x="{LX-4}" y="{RULE_Y+402}" fill="{GREEN}" '
        f'font-size="244" font-weight="800" letter-spacing="-4" '
        f'font-family="{FONT}">Java</text>'
    )
    # amber spark separator
    p.append(f'<rect x="{LX}" y="{RULE_Y+460}" width="560" height="4" '
             f'fill="{GREEN}" opacity="0.55" rx="2"/>')
    p.append(f'<circle cx="{LX+600}" cy="{RULE_Y+462}" r="9" fill="{AMBER}"/>')
    # "by Example" — paper, light weight
    p.append(
        f'<text x="{LX}" y="{RULE_Y+566}" fill="{PAPER}" '
        f'font-size="92" font-weight="300" letter-spacing="0" '
        f'font-family="{FONT}">by Example</text>'
    )

    SUB_Y = RULE_Y + 660
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
