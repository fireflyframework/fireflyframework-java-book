"""Generate on-brand chapter-opener SVGs for *Firefly for Java by Example*.

Each opener is a 720x300 banner sharing one visual language with the cover:
a deep forest-green field with a faint event-mesh, and a darker panel on the
right holding a glowing **firefly** emblem (the same bioluminescent motif as the
cover). The emblem + firefly-green palette are constant so the set reads as a
family; a per-chapter abstract "constellation" varies the left-hand field so no
two openers look identical — no chapter-specific text is required.

This writes EXACTLY the files the manifests reference: art/openers/ch01.svg
through art/openers/ch24.svg (24 files). The prelude and appendices have no
opener in book.yaml / book.es.yaml, so none are generated for them.

Run:  build/.venv/bin/python build/gen_openers.py     (writes art/openers/chNN.svg)
"""
from __future__ import annotations
import math
import random
from pathlib import Path
from xml.sax.saxutils import escape as _xml_escape

ART = Path(__file__).resolve().parents[1] / "art" / "openers"

# How many openers to emit — kept in lockstep with the manifests (ch01..chNN).
N_CHAPTERS = 24


def esc(s: str) -> str:
    """XML-escape SVG text content (these SVGs are inlined into XHTML, so raw
    & / < / > would make the chapter document malformed)."""
    return _xml_escape(str(s))


# ---- palette (firefly-green; shared with the cover) ------------------------
FIELD1  = "#16331a"   # deep forest — field, cool top
FIELD2  = "#1e4620"   # slightly lighter ink for layering
PANEL1  = "#0f2613"   # darker panel, top
PANEL2  = "#0a1c0d"   # darker panel, bottom
GREEN   = "#43b02a"   # brand green
GREEN_B = "#6fd34a"   # bright green accent
GREEN_D = "#255e17"   # dim green — mesh lines
AMBER   = "#ffc24b"   # firefly glow
AMBER_B = "#ffd980"   # bright amber
AMBER_D = "#c97e10"
LIGHT   = "#eaf6df"   # very light green — text on the field
MUTED   = "#8dbd7a"   # muted green — secondary text

W, H = 720, 300
# the night-sky panel on the right
PX, PY, PW, PH = 490, 14, 216, 272
FONT = "Avenir Next,Avenir,Helvetica Neue,Helvetica,Arial,sans-serif"
MONO = "SF Mono,JetBrains Mono,Menlo,Consolas,monospace"


def header(label: str) -> str:
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" '
        f'xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 {W} {H}" '
        f'role="img" aria-label="{esc(label)}" font-family="{FONT}">'
    )


def defs() -> str:
    # NOTE: WeasyPrint's SVG engine drops gradients when a <marker>/<filter>
    # shares the <defs>, and resolves objectBoundingBox gradients unreliably.
    # So we use ONLY userSpaceOnUse gradients here and fake glows with stacked
    # translucent circles.
    return (
        '<defs>'
        f'<linearGradient id="fld" x1="0" y1="0" x2="{W}" y2="{H}" gradientUnits="userSpaceOnUse">'
        f'<stop offset="0" stop-color="{FIELD1}"/>'
        f'<stop offset="1" stop-color="{FIELD2}"/></linearGradient>'
        # the darker panel gradient:
        f'<linearGradient id="pnl" x1="{PX}" y1="{PY}" x2="{PX+PW}" y2="{PY+PH}" gradientUnits="userSpaceOnUse">'
        f'<stop offset="0" stop-color="{PANEL1}"/>'
        f'<stop offset="1" stop-color="{PANEL2}"/></linearGradient>'
        # root-space horizontal green gradient for the accent bar:
        f'<linearGradient id="grh" x1="0" y1="0" x2="{W}" y2="0" gradientUnits="userSpaceOnUse">'
        f'<stop offset="0" stop-color="{GREEN_B}"/>'
        f'<stop offset="1" stop-color="{GREEN}"/></linearGradient>'
        '</defs>'
    )


def emblem() -> str:
    """The constant glowing-firefly emblem on the panel (right), matching the
    cover's bioluminescent mark."""
    cx, cy = 601, 150
    return (
        f'<g transform="translate({cx},{cy})">'
        # ambient bloom on the panel
        f'<circle r="66" fill="{AMBER}" opacity="0.07"/>'
        f'<circle r="40" fill="{AMBER_B}" opacity="0.10"/>'
        # light trail curving in from lower-left (two strokes = a taper)
        f'<path d="M-80,84 C-46,44 -24,18 -8,-2" fill="none" stroke="{AMBER}" '
        f'stroke-width="2.4" opacity="0.10" stroke-linecap="round"/>'
        f'<path d="M-80,84 C-46,44 -24,18 -8,-2" fill="none" stroke="{AMBER_B}" '
        f'stroke-width="1" opacity="0.22" stroke-linecap="round"/>'
        # the firefly, gently tilted for life
        '<g transform="rotate(-16)">'
        f'<circle cx="0" cy="30" r="24" fill="{AMBER}" opacity="0.18"/>'
        f'<circle cx="0" cy="30" r="14" fill="{AMBER_B}" opacity="0.45"/>'
        # wings, swept back and translucent
        f'<path d="M-3,-6 C-40,-30 -52,-2 -16,8 Z" fill="#ddf0c4" opacity="0.22"/>'
        f'<path d="M3,-6 C40,-30 52,-2 16,8 Z" fill="#ddf0c4" opacity="0.22"/>'
        # glowing abdomen
        f'<ellipse cx="0" cy="26" rx="11" ry="16" fill="{AMBER}"/>'
        f'<ellipse cx="0" cy="28" rx="6" ry="10" fill="#fff2cf"/>'
        # dark thorax + head with a green rim (brand)
        f'<ellipse cx="0" cy="2" rx="9" ry="13" fill="{PANEL2}" stroke="{GREEN}" stroke-width="1.6"/>'
        f'<ellipse cx="0" cy="-13" rx="5.5" ry="6.5" fill="{PANEL2}" stroke="{GREEN}" stroke-width="1.3"/>'
        # antennae
        f'<path d="M-3,-18 C-9,-28 -13,-30 -17,-33" fill="none" stroke="{GREEN}" stroke-width="1.5" stroke-linecap="round"/>'
        f'<path d="M3,-18 C9,-28 13,-30 17,-33" fill="none" stroke="{GREEN}" stroke-width="1.5" stroke-linecap="round"/>'
        '</g>'
        # satellite motes (one bioluminescent green, matching the cover)
        f'<circle cx="50" cy="-46" r="2" fill="{AMBER_B}" opacity="0.7"/>'
        f'<circle cx="62" cy="28" r="1.6" fill="{GREEN_B}" opacity="0.75"/>'
        f'<circle cx="-54" cy="-40" r="1.6" fill="{AMBER_B}" opacity="0.6"/>'
        '</g>'
    )


def frame(num: str) -> str:
    """Forest-green field, green accent bar, darker panel + motes, kicker."""
    return (
        f'<rect width="{W}" height="{H}" fill="url(#fld)"/>'
        f'<rect x="0" y="0" width="8" height="{H}" fill="url(#grh)"/>'
        # darker panel on the right
        f'<rect x="{PX}" y="{PY}" width="{PW}" height="{PH}" rx="16" fill="url(#pnl)"/>'
        f'<rect x="{PX}" y="{PY}" width="{PW}" height="{PH}" rx="16" fill="none" '
        f'stroke="{GREEN}" stroke-width="1" opacity="0.22"/>'
        # a few distant motes inside the panel
        f'<g fill="{AMBER_B}"><circle cx="528" cy="56" r="1.4" opacity="0.6"/>'
        f'<circle cx="678" cy="236" r="1.4" opacity="0.55"/>'
        f'<circle cx="644" cy="66" r="1.1" opacity="0.5"/>'
        f'<circle cx="512" cy="210" r="1.1" opacity="0.45"/></g>'
        f'<circle cx="552" cy="246" r="1.3" fill="{GREEN_B}" opacity="0.6"/>'
        # chapter number — a TOP-left kicker, clear of the scene below it
        f'<text x="40" y="40" fill="{GREEN_B}" font-size="15" font-weight="800" '
        f'letter-spacing="2.5">{esc(num)}</text>'
        f'<rect x="40" y="49" width="46" height="4" rx="2" fill="{GREEN}"/>'
    )


def constellation(seed: int) -> str:
    """A per-chapter abstract event-mesh on the left-hand field: a small set of
    hexagonal nodes joined by gently curved spokes, with amber/green pulse dots
    travelling along them. Deterministic from ``seed`` so each chapter differs
    yet the whole set stays one coherent family. Stays within x < 470 so it
    never collides with the panel."""
    rng = random.Random(seed)

    def hexagon(cx, cy, r, fill, stroke, sw=2.0, op=1.0):
        pts = " ".join(
            f"{cx + r*math.cos(math.radians(30 + 60*i)):.1f},"
            f"{cy + r*math.sin(math.radians(30 + 60*i)):.1f}"
            for i in range(6)
        )
        o = f' opacity="{op}"' if op < 1.0 else ""
        return f'<polygon points="{pts}" fill="{fill}" stroke="{stroke}" stroke-width="{sw}"{o}/>'

    def quad(x1, y1, x2, y2):
        mx, my = (x1 + x2) / 2, (y1 + y2) / 2
        dx, dy = x2 - x1, y2 - y1
        return f"M{x1:.1f},{y1:.1f} Q{mx - dy*0.18:.1f},{my + dx*0.18:.1f} {x2:.1f},{y2:.1f}"

    # central hub, biased toward the middle-left of the field
    hub = (rng.uniform(190, 250), rng.uniform(140, 170))
    n_sat = rng.randint(4, 6)
    sats = []
    for k in range(n_sat):
        ang = (2 * math.pi * k / n_sat) + rng.uniform(-0.3, 0.3)
        dist = rng.uniform(95, 150)
        sx = max(60, min(440, hub[0] + dist * math.cos(ang)))
        sy = max(70, min(238, hub[1] + dist * math.sin(ang)))
        sats.append((sx, sy, rng.uniform(16, 24)))

    parts: list[str] = []
    # faint atmospheric accent nodes
    for _ in range(rng.randint(3, 5)):
        ax, ay = rng.uniform(60, 450), rng.uniform(60, 240)
        parts.append(hexagon(ax, ay, rng.uniform(7, 12), FIELD2, GREEN_D, 1.2, op=0.6))
    # spokes from hub to each satellite
    parts.append(f'<g fill="none" stroke="{GREEN}" stroke-width="2" opacity="0.45">')
    for (sx, sy, _) in sats:
        parts.append(f'<path d="{quad(hub[0], hub[1], sx, sy)}"/>')
    parts.append('</g>')
    # peer ring (satellite to next satellite), dim
    parts.append(f'<g fill="none" stroke="{GREEN_D}" stroke-width="1.3" opacity="0.4">')
    for i in range(len(sats)):
        a, b = sats[i], sats[(i + 1) % len(sats)]
        parts.append(f'<path d="{quad(a[0], a[1], b[0], b[1])}"/>')
    parts.append('</g>')
    # pulse dots travelling along the spokes
    for j, (sx, sy, _) in enumerate(sats):
        col = AMBER if j % 2 == 0 else GREEN_B
        for t, r_dot, op in [(0.34, 4.5, 0.9), (0.66, 3.0, 0.55)]:
            px = hub[0] + (sx - hub[0]) * t
            py = hub[1] + (sy - hub[1]) * t
            parts.append(f'<circle cx="{px:.1f}" cy="{py:.1f}" r="{r_dot}" fill="{col}" opacity="{op}"/>')
    # satellite nodes
    for (sx, sy, sr) in sats:
        parts.append(hexagon(sx, sy, sr + 6, "none", GREEN, 1.1, op=0.25))
        parts.append(hexagon(sx, sy, sr, FIELD2, GREEN, 2.0))
    # hub node — slightly larger, amber-rimmed (echoes the cover hub)
    parts.append(hexagon(hub[0], hub[1], 30, "none", GREEN, 1.6, op=0.4))
    parts.append(hexagon(hub[0], hub[1], 22, GREEN_D, AMBER, 2.6))
    parts.append(
        f'<text x="{hub[0]:.1f}" y="{hub[1] + 1:.1f}" text-anchor="middle" '
        f'dominant-baseline="middle" fill="{LIGHT}" font-size="20" font-weight="800" '
        f'letter-spacing="-1">F</text>'
    )
    return "".join(parts)


def build_one(num: int) -> str:
    """num is the 1-based chapter number (1..N_CHAPTERS)."""
    kicker = f"CHAPTER {num}"
    label = f"Firefly for Java by Example — opener for chapter {num}"
    return (
        header(label) + defs() + frame(kicker)
        + constellation(seed=1000 + num)
        + emblem() + "</svg>\n"
    )


def main() -> None:
    ART.mkdir(parents=True, exist_ok=True)
    for num in range(1, N_CHAPTERS + 1):
        (ART / f"ch{num:02d}.svg").write_text(build_one(num), encoding="utf-8")
    print(f"wrote {N_CHAPTERS} chapter openers (ch01..ch{N_CHAPTERS:02d}) to {ART}")


if __name__ == "__main__":
    main()
