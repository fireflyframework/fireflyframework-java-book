"""Generate art/cover.svg and art/cover.png for *Firefly for Java by Example*.

A clean, self-contained typographic cover in the "Reactive Java at dusk" palette:
a warm espresso base, amber-gold as the hero color (Java's warmth) and
firefly-green as the spark (brand continuity) — a deliberate green+amber duotone,
distinct from PyFly (light/green) and the Rust book (navy/amber). There is NO
external logo dependency: the firefly mark is drawn inline as SVG, so the
generator needs nothing under ~/Downloads or art/logo/. The canvas is
1500 x 2100 px (7.5 x 9.25 in at 200 dpi) — the book's trim size.

Run:  build/.venv/bin/python build/gen_cover.py     (writes art/cover.{svg,png})
"""
from __future__ import annotations
import math
from pathlib import Path
from xml.sax.saxutils import escape as _xml_escape
import cairosvg

ART = Path(__file__).resolve().parents[1] / "art"
W, H = 1500, 2100  # 7.5 x 9.25 in at 200 dpi

# ---------------------------------------------------------------------------
# Palette ("Reactive Java at dusk" — espresso base, amber hero, green spark)
# ---------------------------------------------------------------------------
INK        = "#1B1610"   # espresso base — background
DEEP       = "#2A2014"   # deeper espresso layer — layering / accent nodes
AMBER      = "#E8A23A"   # amber hero — Java's warmth
AMBER_BRT  = "#F4C24E"   # bright amber — highlights / pulse glints
AMBER_DEEP = "#C8801F"   # deep amber — dim network lines / shadowed strokes
GREEN      = "#43B02A"   # firefly green (spark) — brand continuity
GREEN_DEEP = "#2C8A1C"   # deep green
GREEN_MID  = "#2C8A1C"   # mid green — node fills (kept name for callers)
GREEN_DIM  = "#2C8A1C"   # dim green — subtle peer-ring lines
CREAM      = "#F3ECDD"   # cream — body / title text on dark
WHITE      = "#F3ECDD"   # title text (cream, kept name for callers)
LIGHT      = "#F3ECDD"   # cream — "by Example" sits in green, motes in cream
MUTED      = "#C9B896"   # warm muted — publisher / secondary text
MUTED_LT   = "#C9B896"   # warm muted — subtitle

FONT = "Avenir Next,Avenir,Helvetica Neue,Helvetica,Arial,sans-serif"


# ---------------------------------------------------------------------------
# Network motif helpers
# ---------------------------------------------------------------------------
def hex_points(cx: float, cy: float, r: float) -> str:
    """Return SVG polygon points string for a flat-top hexagon."""
    pts = []
    for i in range(6):
        angle = math.radians(30 + 60 * i)  # flat-top orientation
        pts.append(f"{cx + r * math.cos(angle):.2f},{cy + r * math.sin(angle):.2f}")
    return " ".join(pts)


def node(cx: float, cy: float, r: float, fill: str, stroke: str,
         stroke_w: float = 2.0, opacity: float = 1.0) -> str:
    pts = hex_points(cx, cy, r)
    op = f' opacity="{opacity}"' if opacity < 1.0 else ""
    return (
        f'<polygon points="{pts}" fill="{fill}" '
        f'stroke="{stroke}" stroke-width="{stroke_w}"{op}/>'
    )


def quad_path(x1: float, y1: float, x2: float, y2: float) -> str:
    """Smooth quadratic bezier between two points, gently curved outward."""
    mx, my = (x1 + x2) / 2, (y1 + y2) / 2
    dx, dy = x2 - x1, y2 - y1
    cpx = mx - dy * 0.18
    cpy = my + dx * 0.18
    return f"M{x1:.1f},{y1:.1f} Q{cpx:.1f},{cpy:.1f} {x2:.1f},{y2:.1f}"


def firefly_mark(cx: float, cy: float, scale: float = 1.0) -> str:
    """A clean, self-contained firefly/spark mark — the cover's brand emblem.

    A tilted firefly with a softly glowing abdomen and swept-back wings, sitting
    in a faint amber bloom. Drawn entirely inline (no external image), so the
    cover is reproducible from this script alone.
    """
    return (
        f'<g transform="translate({cx},{cy}) scale({scale})">'
        # ambient bloom rings
        f'<circle r="118" fill="{AMBER}" opacity="0.06"/>'
        f'<circle r="78" fill="{AMBER}" opacity="0.09"/>'
        f'<circle r="46" fill="{AMBER}" opacity="0.16"/>'
        # light trail curving in from the lower-left (two strokes = a taper)
        f'<path d="M-150,150 C-86,80 -44,32 -14,-4" fill="none" stroke="{AMBER}" '
        f'stroke-width="4" opacity="0.16" stroke-linecap="round"/>'
        f'<path d="M-150,150 C-86,80 -44,32 -14,-4" fill="none" stroke="{AMBER_BRT}" '
        f'stroke-width="1.6" opacity="0.34" stroke-linecap="round"/>'
        # the firefly, gently tilted for life
        '<g transform="rotate(-16)">'
        f'<circle cx="0" cy="52" r="42" fill="{AMBER}" opacity="0.20"/>'
        f'<circle cx="0" cy="52" r="25" fill="{AMBER_BRT}" opacity="0.48"/>'
        # wings, swept back and translucent (warm cream)
        f'<path d="M-5,-10 C-72,-54 -94,-2 -28,14 Z" fill="{CREAM}" opacity="0.20"/>'
        f'<path d="M5,-10 C72,-54 94,-2 28,14 Z" fill="{CREAM}" opacity="0.20"/>'
        # glowing abdomen
        f'<ellipse cx="0" cy="46" rx="20" ry="29" fill="{AMBER}"/>'
        f'<ellipse cx="0" cy="49" rx="11" ry="18" fill="#FFF1D2"/>'
        # dark thorax + head with a green rim (brand spark)
        f'<ellipse cx="0" cy="4" rx="16" ry="23" fill="{INK}" stroke="{GREEN}" stroke-width="2.8"/>'
        f'<ellipse cx="0" cy="-23" rx="10" ry="12" fill="{INK}" stroke="{GREEN}" stroke-width="2.4"/>'
        # antennae
        f'<path d="M-5,-32 C-16,-50 -23,-54 -30,-60" fill="none" stroke="{GREEN}" '
        f'stroke-width="2.6" stroke-linecap="round"/>'
        f'<path d="M5,-32 C16,-50 23,-54 30,-60" fill="none" stroke="{GREEN}" '
        f'stroke-width="2.6" stroke-linecap="round"/>'
        '</g>'
        # satellite motes — amber glints + one green spark
        f'<circle cx="92" cy="-82" r="3.4" fill="{AMBER_BRT}" opacity="0.78"/>'
        f'<circle cx="110" cy="50" r="2.8" fill="{GREEN}" opacity="0.85"/>'
        f'<circle cx="-96" cy="-72" r="2.8" fill="{AMBER_BRT}" opacity="0.68"/>'
        '</g>'
    )


# ---------------------------------------------------------------------------
# Build SVG
# ---------------------------------------------------------------------------
def build_svg() -> str:
    # -------------------------------------------------------------------------
    # EDA Network motif  (illustration zone: y = 80 ... 1080)
    # Hub: center, slightly above midpoint of illustration zone
    # -------------------------------------------------------------------------
    HUB_X, HUB_Y, HUB_R = 750, 580, 72

    # Satellite nodes: (cx, cy, radius, label)
    SATS = [
        (260,  210, 44, "CMD"),
        (650,  185, 40, "EVT"),
        (1080, 240, 44, "SAGA"),
        (1220, 540, 38, "Q"),
        (1110, 870, 44, "HTTP"),
        (390,  930, 40, "DATA"),
        (150,  610, 38, "MSG"),
    ]

    # Small accent nodes (no labels, atmospheric depth)
    ACCENT_NODES = [
        (500,  100, 22),
        (930,  130, 18),
        (1350, 360, 20),
        (1370, 760, 17),
        (980, 1020, 19),
        (200,  990, 16),
        (80,   400, 18),
    ]

    # Peer-to-peer connections (satellite ring, one hop)
    CONNECTIONS_PEER = [(0, 1), (1, 2), (2, 3), (3, 4), (4, 5), (5, 6), (6, 0)]

    # SVG fragment list
    parts: list[str] = []

    # 1. Background fill
    parts.append(f'<rect width="{W}" height="{H}" fill="{INK}"/>')

    # 2. Very faint amber hex-grid texture over whole canvas
    parts.append(f'<g opacity="0.04" fill="none" stroke="{AMBER}" stroke-width="0.8">')
    for row in range(15):
        for col in range(10):
            gx = col * 185 - 60
            gy = row * 165 + (80 if col % 2 else 0) - 30
            pts = hex_points(gx, gy, 78)
            parts.append(f'<polygon points="{pts}"/>')
    parts.append('</g>')

    # 3. Radial amber glow behind hub (defined inline; hoisted to <defs>)
    grad_id = "hubglow"
    parts.append(
        f'<radialGradient id="{grad_id}" cx="{HUB_X/W:.4f}" cy="{HUB_Y/H:.4f}" r="0.30" '
        f'fx="{HUB_X/W:.4f}" fy="{HUB_Y/H:.4f}" gradientUnits="objectBoundingBox">'
        f'<stop offset="0" stop-color="{AMBER}" stop-opacity="0.22"/>'
        f'<stop offset="1" stop-color="{AMBER}" stop-opacity="0"/>'
        f'</radialGradient>'
    )
    parts.append(f'<rect width="{W}" height="{H}" fill="url(#{grad_id})"/>')

    # 4. Peer connection lines (dim green ring)
    parts.append(
        f'<g fill="none" stroke="{GREEN_DIM}" stroke-width="1.5" opacity="0.42">'
    )
    for (a, b) in CONNECTIONS_PEER:
        sx, sy = SATS[a][0], SATS[a][1]
        ex, ey = SATS[b][0], SATS[b][1]
        parts.append(f'<path d="{quad_path(sx, sy, ex, ey)}"/>')
    parts.append('</g>')

    # 5. Hub spokes — amber, brighter and thicker (the hero radiates)
    parts.append(
        f'<g fill="none" stroke="{AMBER}" stroke-width="2.5" opacity="0.60">'
    )
    for (sx, sy, _, _) in SATS:
        parts.append(f'<path d="{quad_path(HUB_X, HUB_Y, sx, sy)}"/>')
    parts.append('</g>')

    # 6. Event-pulse dots along spokes at 1/3 and 2/3
    PULSE_COLORS = [AMBER, GREEN, AMBER, GREEN, AMBER, GREEN, AMBER]
    for i, (sx, sy, _, _) in enumerate(SATS):
        col = PULSE_COLORS[i % len(PULSE_COLORS)]
        for t, r_dot, op_dot in [(0.32, 6, 0.90), (0.65, 4, 0.55)]:
            px = HUB_X + (sx - HUB_X) * t
            py = HUB_Y + (sy - HUB_Y) * t
            parts.append(
                f'<circle cx="{px:.1f}" cy="{py:.1f}" r="{r_dot}" '
                f'fill="{col}" opacity="{op_dot}"/>'
            )

    # 7. Accent nodes (tiny, atmospheric — espresso fill, dim green rim)
    for (ax, ay, ar) in ACCENT_NODES:
        parts.append(node(ax, ay, ar, DEEP, GREEN_DIM, 1.4, opacity=0.55))

    # 8. Satellite nodes (green-mid) with green outer ring + cream label
    for (sx, sy, sr, lbl) in SATS:
        parts.append(node(sx, sy, sr + 9, "none", GREEN, 1.2, opacity=0.30))
        parts.append(node(sx, sy, sr, GREEN_MID, GREEN, 2.2))
        fs = 23 if len(lbl) <= 3 else 19
        parts.append(
            f'<text x="{sx}" y="{sy + 1}" text-anchor="middle" '
            f'dominant-baseline="middle" fill="{CREAM}" '
            f'font-size="{fs}" font-weight="700" '
            f'font-family="{FONT}" letter-spacing="1">{lbl}</text>'
        )

    # 9. Hub node — the firefly mark sits at the network's heart.
    #    Green rims for brand continuity, an amber inner ring for the hero glow.
    parts.append(node(HUB_X, HUB_Y, HUB_R + 16, "none", GREEN, 1.8, opacity=0.42))
    parts.append(node(HUB_X, HUB_Y, HUB_R + 4,  "none", GREEN, 2.8, opacity=0.78))
    parts.append(node(HUB_X, HUB_Y, HUB_R, GREEN_MID, AMBER, 3.8))
    # the drawn firefly emblem, centered on the hub
    parts.append(firefly_mark(HUB_X, HUB_Y, scale=0.62))

    # 10. Atmospheric micro-labels (subtle, spaced out)
    MICRO = [
        (500,   76, "EVENTS"),
        (1320, 315, "ASYNC"),
        (160,  375, "REACTIVE"),
        (960,  1050, "CQRS"),
    ]
    for (mx, my, mlbl) in MICRO:
        parts.append(
            f'<text x="{mx}" y="{my}" text-anchor="middle" fill="{AMBER}" '
            f'font-size="18" font-weight="400" opacity="0.45" '
            f'font-family="{FONT}" letter-spacing="3.5">{mlbl}</text>'
        )

    # -------------------------------------------------------------------------
    # Amber + green divider rules
    # -------------------------------------------------------------------------
    RULE_Y = 1110
    parts.append(
        f'<rect x="100" y="{RULE_Y}" width="1300" height="6" fill="{AMBER}" rx="3"/>'
    )
    parts.append(
        f'<rect x="100" y="{RULE_Y + 14}" width="1300" height="1.5" '
        f'fill="{GREEN}" opacity="0.45" rx="1"/>'
    )

    # -------------------------------------------------------------------------
    # Publisher label — top edge, small caps, generous letter-spacing
    # -------------------------------------------------------------------------
    parts.append(
        f'<text x="{W // 2}" y="68" text-anchor="middle" '
        f'fill="{MUTED}" font-size="26" font-weight="500" '
        f'letter-spacing="9" font-family="{FONT}">'
        f'FIREFLY SOFTWARE FOUNDATION</text>'
    )

    # -------------------------------------------------------------------------
    # Title block
    # -------------------------------------------------------------------------
    TY = RULE_Y + 68   # baseline anchor of first title line

    # "Firefly for" — heavy, cream
    parts.append(
        f'<text x="108" y="{TY + 120}" '
        f'fill="{CREAM}" font-size="150" font-weight="800" '
        f'font-family="{FONT}" letter-spacing="-4">Firefly for</text>'
    )

    # "Java" — the BIG amber-gold hero (the standout word)
    parts.append(
        f'<text x="108" y="{TY + 300}" '
        f'fill="{AMBER}" font-size="220" font-weight="800" '
        f'font-family="{FONT}" letter-spacing="-6">Java</text>'
    )

    # Thin green spark separator under "Java" (brand continuity)
    RULE2_Y = TY + 326
    parts.append(
        f'<rect x="112" y="{RULE2_Y}" width="700" height="3" '
        f'fill="{GREEN}" opacity="0.65" rx="1.5"/>'
    )

    # "by Example" — firefly-green, demi-bold
    parts.append(
        f'<text x="112" y="{RULE2_Y + 110}" '
        f'fill="{GREEN}" font-size="100" font-weight="600" '
        f'font-family="{FONT}" letter-spacing="-1">by Example</text>'
    )

    # Subtitle (two lines)
    SUB_Y = RULE2_Y + 188
    for i, line in enumerate([
        "Reactive Microservices with Spring Boot,",
        "WebFlux & the Firefly Framework",
    ]):
        parts.append(
            f'<text x="112" y="{SUB_Y + i * 50}" '
            f'fill="{MUTED_LT}" font-size="37" font-weight="400" '
            f'font-family="{FONT}" letter-spacing="0.5">{_xml_escape(line)}</text>'
        )

    # -------------------------------------------------------------------------
    # Assemble: hoist gradient elements into <defs>
    # -------------------------------------------------------------------------
    defs_tags = ("<radialGradient", "<linearGradient")
    defs_parts = [p for p in parts if p.startswith(defs_tags)]
    body_parts = [p for p in parts if not p.startswith(defs_tags)]

    svg = (
        f'<svg xmlns="http://www.w3.org/2000/svg" '
        f'xmlns:xlink="http://www.w3.org/1999/xlink" '
        f'width="{W}" height="{H}" viewBox="0 0 {W} {H}">\n'
        f'<defs>{"".join(defs_parts)}</defs>\n'
        + "\n".join(body_parts)
        + "\n</svg>"
    )
    return svg


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
