"""Generate compact chapter banners using the approved, unmodified jafly logo.

The embedded PNG comes directly from the shared brand kit; its outlined SVG
master remains alongside it in art/brand. Numbers work in both book languages.
"""
from __future__ import annotations
import base64
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ART = ROOT / "art" / "openers"
N_CHAPTERS = 24


def build_one(num: int) -> str:
    logo = base64.b64encode((ROOT / "art/brand/logo-dark.png").read_bytes()).decode()
    return (f'<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" '
            f'viewBox="0 0 720 140" role="img" aria-label="jafly — {num:02d}">'
            '<rect width="720" height="140" fill="#10110f"/>'
            '<rect width="5" height="140" fill="#ffb34a"/>'
            f'<text x="32" y="92" fill="#f3f1eb" font-family="Helvetica,Arial,sans-serif" '
            f'font-size="64" font-weight="700">{num:02d}</text>'
            f'<image x="554" y="16" width="146" height="108" xlink:href="data:image/png;base64,{logo}"/>'
            '</svg>\n')


def main() -> None:
    ART.mkdir(parents=True, exist_ok=True)
    for num in range(1, N_CHAPTERS + 1):
        (ART / f"ch{num:02d}.svg").write_text(build_one(num), encoding="utf-8")
    print(f"Wrote {N_CHAPTERS} approved-brand chapter openers to {ART}")


if __name__ == "__main__":
    main()
