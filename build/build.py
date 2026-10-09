"""Build *jafly by example* into EPUB + PDF from a book manifest.

Defaults to ``book.yaml`` (English). Pass ``--config book.es.yaml`` to build the
Spanish edition; each manifest names its own ``manuscript_dir``, ``language``,
localized ``labels`` (e.g. the Contents heading) and ``output_basename``.

    build/run.sh                       # English  -> firefly-java-by-example.{epub,pdf}
    build/run.sh --config book.es.yaml # Spanish  -> firefly-java-by-example-es.{epub,pdf}
"""
from __future__ import annotations
import argparse
import re
import sys
from pathlib import Path
from xml.sax.saxutils import escape, quoteattr
import yaml  # PyYAML ships with the framework env; ensure installed in book/.venv

sys.path.insert(0, str(Path(__file__).resolve().parent))
from md import render_markdown          # noqa: E402
from epub import EpubBuilder, Doc       # noqa: E402
from pdf import render_pdf              # noqa: E402

BOOK = Path(__file__).resolve().parents[1]
THEME = BOOK / "theme"
DIST = BOOK / "dist"


def _split_part(part_title: str) -> tuple[str, str]:
    """'Part I — Foundations' -> ('Part I', 'Foundations').

    Splits on an em/en dash (with optional spaces) or a plain ' - '. Falls back
    to the whole string as the title with an empty eyebrow when no dash is found.
    """
    m = re.match(r"\s*(.+?)\s*[—–-]\s*(.+?)\s*$", part_title)
    if m:
        return m.group(1).strip(), m.group(2).strip()
    return "", part_title.strip()


def _items_from_manifest(cfg: dict, man: Path, *, contents_label: str) -> list[dict]:
    """Ordered build items, each tagged ``kind`` and carrying the metadata that
    kind needs. Consumed by BOTH the EPUB and PDF assemblers.

    kinds:
      front    -> {id, title, path, in_nav}
      toc      -> {id, title}                     (Contents page; content generated)
      divider  -> {id, eyebrow, ptitle, part}     (full-page part opener)
      chapter  -> {id, title, num, path, part}    (a manuscript chapter)
    """
    items: list[dict] = []
    # 1) front matter
    for fm in cfg.get("front", []):
        p = man / fm["file"]
        if not p.exists():
            continue
        items.append({
            "kind": "front",
            "id": fm["id"],
            "title": fm.get("title", fm["id"].title()),
            "path": str(p),
            "in_nav": bool(fm.get("nav", True)) and "title" in fm,
        })
    # 2) Contents page — after front matter, before Part I
    items.append({"kind": "toc", "id": "toc", "title": contents_label})
    # 3) parts: a divider then each chapter
    for part in cfg.get("parts", []):
        ptitle_full = part["title"]
        eyebrow, ptitle = _split_part(ptitle_full)
        chapters = [ch for ch in part["chapters"] if (man / ch["file"]).exists()]
        if not chapters:
            continue
        # stable divider id from the eyebrow, e.g. "Part I" -> "part-i"
        slug = re.sub(r"[^a-z0-9]+", "-", eyebrow.lower()).strip("-") if eyebrow else ""
        did = slug if slug.startswith("part") else f"part-{slug or len(items)}"
        items.append({
            "kind": "divider",
            "id": did,
            "eyebrow": eyebrow,
            "ptitle": ptitle,
            "part": ptitle_full,
        })
        for ch in chapters:
            items.append({
                "kind": "chapter",
                "id": ch["id"],
                "title": (f'{ch["num"]}. {ch["title"]}' if ch.get("num") not in (None, "") else ch["title"]),
                "num": ch["num"],
                "path": str(man / ch["file"]),
                "part": ptitle_full,
                "opener": ch.get("opener"),
            })
    return items


def _toc_html(items: list[dict], *, href_fmt: str, label: str) -> str:
    """Generate the Contents body. ``href_fmt`` formats a chapter id into a link
    target: '#{cid}' for the single-document PDF, '{cid}.xhtml' for the EPUB."""
    parts: list[str] = []
    cur: str | None = None
    open_group = False
    for it in items:
        if it["kind"] == "divider":
            if open_group:
                parts.append("</ol></div>")
            eyebrow = f'<span class="toc-part-eyebrow">{escape(it["eyebrow"])}</span> ' \
                if it["eyebrow"] else ""
            parts.append('<div class="toc-part-group">'
                         f'<h2 class="toc-part-title">{eyebrow}{escape(it["ptitle"])}</h2>'
                         '<ol class="toc-chapters">')
            open_group = True
            cur = it["part"]
        elif it["kind"] == "chapter" and open_group:
            href = href_fmt.format(cid=it["id"])
            parts.append(f'<li><a class="toc-link" href="{escape(href)}">'
                         f'{escape(it["title"])}</a></li>')
    if open_group:
        parts.append("</ol></div>")
    body = "".join(parts)
    return f'<h1 class="chtitle">{escape(label)}</h1>{body}'


def _divider_html(eyebrow: str, ptitle: str, logo: str = "") -> str:
    eb = f'<span class="eyebrow part-eyebrow">{escape(eyebrow)}</span>' if eyebrow else ""
    return (f'<div class="part-divider-inner">{logo}{eb}'
            f'<h1 class="part-title">{escape(ptitle)}</h1></div>')


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Build jafly by example (EPUB + PDF).")
    ap.add_argument("--config", default="book.yaml",
                    help="Manifest file at the repo root (default: book.yaml).")
    ap.add_argument("--out", default=None,
                    help="Output basename (default: manifest 'output_basename' or 'firefly-java-by-example').")
    args = ap.parse_args(argv)

    cfg = yaml.safe_load((BOOK / args.config).read_text())
    man = BOOK / cfg.get("manuscript_dir", "manuscript")
    contents_label = cfg.get("labels", {}).get("contents", "Contents")
    out_base = args.out or cfg.get("output_basename") or "firefly-java-by-example"

    css_text = [(THEME / "book.css").read_text(), (THEME / "tokens.css").read_text(),
                (THEME / "pygments.css").read_text()]
    items = _items_from_manifest(cfg, man, contents_label=contents_label)

    covers = []
    for kind, key, label_key, default_label in (
        ("cover", "cover_png", "cover", "Cover"),
        ("backcover", "back_cover_png", "back_cover", "Back cover"),
    ):
        if not cfg.get(key):
            continue
        path = BOOK / cfg[key]
        if not path.is_file():
            raise FileNotFoundError(f"Required {kind} artwork is missing: {path}")
        label = cfg.get("labels", {}).get(label_key, default_label)
        alt = cfg.get(label_key + "_alt", f"{label}: {cfg['title']}")
        covers.append((kind, path, label, alt))

    interior_assets = {}
    if cfg.get("interior_logo"):
        interior_assets["brand-logo"] = BOOK / cfg["interior_logo"]
    for it in items:
        if it.get("opener"):
            interior_assets[f'opener-{it["id"]}'] = BOOK / it["opener"]
    for path in interior_assets.values():
        if not path.is_file():
            raise FileNotFoundError(f"Required interior artwork is missing: {path}")

    def interior_image(key: str, *, pdf: bool = False) -> str:
        if key not in interior_assets:
            return ""
        path = interior_assets[key]
        src = path.as_uri() if pdf else f"art/{key}{path.suffix}"
        cls = "brand-logo" if key == "brand-logo" else "chapter-opener"
        alt = "jafly by Firefly" if key == "brand-logo" else ""
        return f'<img class="{cls}" src={quoteattr(src)} alt={quoteattr(alt)}/>'

    # ---- EPUB ----
    epub = EpubBuilder(title=cfg["title"], author=cfg["author"], language=cfg["language"],
                       identifier=cfg["identifier"], css=css_text,
                       publisher=cfg.get("publisher", ""), rights=cfg.get("rights", ""),
                       contents_title=contents_label)
    for key, path in interior_assets.items():
        epub.add_file(path, f"art/{key}{path.suffix}", key)
    cover_docs = {}
    for kind, path, label, alt in covers:
        href = f"art/{kind}.png"
        epub.add_file(path, href, f"{kind}-img",
                      properties="cover-image" if kind == "cover" else "")
        cover_docs[kind] = Doc(id=kind, title=label, in_nav=False, kind=kind,
                              xhtml_body=f'<img src="{href}" alt={quoteattr(alt)}/>')
    if "cover" in cover_docs:
        epub.add_doc(cover_docs["cover"])
    for it in items:
        if it["kind"] == "toc":
            body = _toc_html(items, href_fmt="{cid}.xhtml", label=contents_label)
            epub.add_doc(Doc(id=it["id"], title=it["title"], xhtml_body=body,
                             in_nav=True, kind="toc"))
        elif it["kind"] == "divider":
            body = _divider_html(it["eyebrow"], it["ptitle"], interior_image("brand-logo"))
            epub.add_doc(Doc(id=it["id"], title=it["part"], xhtml_body=body,
                             in_nav=False, kind="divider", part=it["part"]))
        else:  # front | chapter
            body = render_markdown(Path(it["path"]).read_text(encoding="utf-8"), BOOK)
            if (it["kind"] == "chapter" or it.get("in_nav")) and not re.search(r"<h1(?:\s|>)", body):
                body = f'<h1 class="chtitle">{escape(it["title"])}</h1>' + body
            body = interior_image("brand-logo" if it["id"] == "title" else f'opener-{it["id"]}') + body
            epub.add_doc(Doc(id=it["id"], title=it["title"], xhtml_body=body,
                             in_nav=it.get("in_nav", True), kind=it["kind"],
                             part=it.get("part"), num=it.get("num")))
    if "backcover" in cover_docs:
        epub.add_doc(cover_docs["backcover"])
    DIST.mkdir(exist_ok=True)
    epub.build(DIST / f"{out_base}.epub")

    # ---- PDF (single concatenated document) ----
    parts_html: list[str] = []
    def pdf_cover(kind: str) -> str:
        for cover_kind, path, label, alt in covers:
            if cover_kind == kind:
                return (f'<div class="cover-page {kind}"><img src={quoteattr(path.as_uri())} '
                        f'alt={quoteattr(alt)}/></div>')
        return ""

    parts_html.append(pdf_cover("cover"))
    for it in items:
        if it["kind"] == "toc":
            body = _toc_html(items, href_fmt="#{cid}", label=contents_label)
            parts_html.append(f'<section class="toc" id="{it["id"]}">{body}</section>')
        elif it["kind"] == "divider":
            body = _divider_html(it["eyebrow"], it["ptitle"], interior_image("brand-logo", pdf=True))
            parts_html.append(f'<section class="part-divider" id="{it["id"]}">{body}</section>')
        else:  # front | chapter
            body = render_markdown(Path(it["path"]).read_text(encoding="utf-8"), BOOK)
            if (it["kind"] == "chapter" or it.get("in_nav")) and not re.search(r"<h1(?:\s|>)", body):
                body = f'<h1 class="chtitle">{escape(it["title"])}</h1>' + body
            body = interior_image("brand-logo" if it["id"] == "title" else f'opener-{it["id"]}', pdf=True) + body
            parts_html.append(f'<section class="chapter" id="{it["id"]}">{body}</section>')
    parts_html.append(pdf_cover("backcover"))
    full = (f'<!DOCTYPE html><html lang={quoteattr(cfg["language"])}><head><meta charset="utf-8">'
            f'<title>{escape(cfg["title"])}</title>'
            f'<meta name="author" content={quoteattr(cfg["author"])}>'
            f'<meta name="publisher" content={quoteattr(cfg.get("publisher", ""))}>'
            '</head><body>'
            + "\n".join(parts_html) + "</body></html>")
    render_pdf(full, base_url=BOOK,
               css_paths=[THEME / "tokens.css", THEME / "pygments.css",
                          THEME / "book.css", THEME / "print.css"],
               out=DIST / f"{out_base}.pdf")
    n = sum(1 for it in items if it["kind"] in ("front", "chapter"))
    print(f"[{cfg['language']}] Built {n} document(s) + TOC + "
          f"{sum(1 for it in items if it['kind']=='divider')} part divider(s) "
          f"-> {out_base}.epub + {out_base}.pdf in {DIST}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
