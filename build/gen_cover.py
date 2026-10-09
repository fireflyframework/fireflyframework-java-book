"""Validate or import canonical Framework by Firefly book artwork.

The shared digital brand kit owns the design. This tool cannot recreate the
retired cover. Import all four localized covers with --source /path/to/11-Books/java,
or run without arguments to validate the checked-in artwork. Missing PNGs are
rasterized from the outlined SVG originals.
"""
from __future__ import annotations
import argparse
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET

import cairosvg
from PIL import Image

ART = Path(__file__).resolve().parents[1] / "art"
NAMES = ("cover", "cover-es", "back-cover", "back-cover-es")
SIZE = (1500, 1850)


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, help="Canonical brand-kit 11-Books/java directory")
    args = parser.parse_args(argv)
    source = args.source or ART
    for name in NAMES:
        svg = source / f"{name}.svg"
        root = ET.fromstring(svg.read_bytes())
        if [float(n) for n in root.get("viewBox", "").split()] != [0, 0, *SIZE]:
            raise ValueError(f"{svg}: expected 1500 × 1850 viewBox (7.5 × 9.25 inch trim)")
        if any(node.tag.rsplit("}", 1)[-1] == "text" for node in root.iter()):
            raise ValueError(f"{svg}: canonical cover typography must be outlined")
        png = source / f"{name}.png"
        if png.exists():
            with Image.open(png) as image:
                if image.size != SIZE:
                    raise ValueError(f"{png}: expected {SIZE} pixels")
    ART.mkdir(parents=True, exist_ok=True)
    for name in NAMES:
        svg, png = source / f"{name}.svg", source / f"{name}.png"
        target_svg, target_png = ART / svg.name, ART / png.name
        if svg.resolve() != target_svg.resolve():
            shutil.copyfile(svg, target_svg)
        if png.exists():
            if png.resolve() != target_png.resolve():
                shutil.copyfile(png, target_png)
        else:
            cairosvg.svg2png(bytestring=svg.read_bytes(), write_to=str(target_png),
                            output_width=SIZE[0], output_height=SIZE[1])
    print("Validated EN/ES front and back covers: 1500 × 1850, outlined canonical artwork")


if __name__ == "__main__":
    main()
