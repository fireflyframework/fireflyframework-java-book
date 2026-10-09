"""Cover integration: localization, reading order, metadata and full-page layout."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
import zipfile

import pytest
import yaml
from PIL import Image
from weasyprint import HTML

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "build"))
import build as book_build
import gen_cover


@pytest.fixture
def edition(tmp_path, monkeypatch):
    art = tmp_path / "art"
    art.mkdir()
    manuscript = tmp_path / "manuscript"
    manuscript.mkdir()
    (manuscript / "chapter.md").write_text("# A short chapter\n\nBody text.")
    for name, color in (("front", "red"), ("back", "blue")):
        Image.new("RGB", (1500, 1850), color).save(art / f"{name}.png")
    config = {
        "title": "Java & arquitectura", "author": "Firefly Software Foundation",
        "publisher": "Firefly Software Foundation", "language": "es",
        "identifier": "urn:uuid:cover-test", "rights": "Apache-2.0",
        "cover_png": "art/front.png", "back_cover_png": "art/back.png",
        "cover_alt": "Portada: Java & arquitectura", "back_cover_alt": "Contraportada: Java",
        "trim_width": "7.5in", "trim_height": "9.25in",
        "manuscript_dir": "manuscript", "output_basename": "edition",
        "labels": {"contents": "Contenido", "cover": "Portada", "back_cover": "Contraportada"},
        "front": [], "parts": [{"title": "Parte I — Arquitectura", "chapters": [
            {"id": "ch1", "file": "chapter.md", "num": 1, "title": "A short chapter"}]}],
    }
    (tmp_path / "book.yaml").write_text(yaml.safe_dump(config))
    monkeypatch.setattr(book_build, "BOOK", tmp_path)
    monkeypatch.setattr(book_build, "DIST", tmp_path / "dist")
    return tmp_path, config


def test_localized_covers_bookend_epub_spine_and_preserve_navigation(edition):
    root, _ = edition
    book_build.main([])
    ns = {"opf": "http://www.idpf.org/2007/opf", "dc": "http://purl.org/dc/elements/1.1/",
          "x": "http://www.w3.org/1999/xhtml"}
    with zipfile.ZipFile(root / "dist/edition.epub") as archive:
        opf = ET.fromstring(archive.read("OEBPS/content.opf"))
        spine = [node.attrib["idref"] for node in opf.findall("opf:spine/opf:itemref", ns)]
        assert spine == ["cover", "toc", "parte-i", "ch1", "backcover"]
        assert opf.findtext("opf:metadata/dc:publisher", namespaces=ns) == "Firefly Software Foundation"
        assert opf.findtext("opf:metadata/dc:rights", namespaces=ns) == "Apache-2.0"
        assert opf.findtext("opf:metadata/dc:language", namespaces=ns) == "es"
        cover_images = opf.findall("opf:manifest/opf:item[@properties='cover-image']", ns)
        assert len(cover_images) == 1
        for filename, expected in (("cover.xhtml", "Portada: Java & arquitectura"),
                                   ("backcover.xhtml", "Contraportada: Java")):
            page = ET.fromstring(archive.read(f"OEBPS/{filename}"))
            assert page.find(".//x:img", ns).attrib["alt"] == expected
        nav = ET.fromstring(archive.read("OEBPS/nav.xhtml"))
        assert nav.find(".//x:h1", ns).text == "Contenido"
        assert "ch1.xhtml" in archive.read("OEBPS/nav.xhtml").decode()


def test_pdf_covers_fill_trim_without_headers_or_extra_pages(edition, monkeypatch):
    root, _ = edition
    rendered = []
    pdf_options = []
    original = HTML.write_pdf

    def capture(self, *args, **kwargs):
        pdf_options.append(kwargs)
        rendered.append(self.render(stylesheets=kwargs.get("stylesheets")))
        return original(self, *args, **kwargs)

    monkeypatch.setattr(HTML, "write_pdf", capture)
    book_build.main([])
    document = rendered[0]
    assert len(document.pages) == 5  # front, contents, part, chapter, back
    assert document.metadata.title == "Java & arquitectura"
    assert document.metadata.custom["publisher"] == "Firefly Software Foundation"
    assert pdf_options[0].get("custom_metadata") is True
    for page in (document.pages[0], document.pages[-1]):
        assert (page.width, page.height) == (720, 888)
        images = [box for box in page._page_box.descendants()
                  if getattr(box, "element_tag", None) == "img"]
        assert len(images) == 1
        image = images[0]
        assert (image.position_x, image.position_y, image.width, image.height) == (0, 0, 720, 888)
        assert not any(getattr(box, "text", "").strip() for box in page._page_box.descendants())


def test_declared_missing_cover_fails_instead_of_publishing_incomplete_book(edition):
    root, _ = edition
    (root / "art/back.png").unlink()
    with pytest.raises(FileNotFoundError, match="back.png"):
        book_build.main([])


def test_cover_tool_refuses_missing_canonical_artwork(tmp_path, monkeypatch):
    monkeypatch.setattr(gen_cover, "ART", tmp_path / "art")
    monkeypatch.setattr(sys, "argv", ["gen_cover.py", "--source", str(tmp_path / "missing")])
    with pytest.raises(FileNotFoundError):
        gen_cover.main()


def test_cover_tool_imports_localized_canonical_assets_without_redesign(tmp_path, monkeypatch):
    source = tmp_path / "canonical"
    source.mkdir()
    target = tmp_path / "art"
    svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1500 1850"><path d="M0 0h1500v1850H0Z"/></svg>'
    for name in ("cover", "cover-es", "back-cover", "back-cover-es"):
        (source / f"{name}.svg").write_text(svg)
        Image.new("RGB", (1500, 1850), "red").save(source / f"{name}.png")
    monkeypatch.setattr(gen_cover, "ART", target)
    monkeypatch.setattr(sys, "argv", ["gen_cover.py", "--source", str(source)])
    gen_cover.main()
    assert (target / "back-cover-es.svg").read_text() == svg
    assert Image.open(target / "cover.png").size == (1500, 1850)


def test_title_and_copyright_pages_do_not_repeat_or_capitalize_collection_name(edition, monkeypatch):
    root, config = edition
    (root / "manuscript/title.md").write_text("# jafly by example {.chtitle}\n\nFrom Spring Boot to a connected enterprise architecture.")
    (root / "manuscript/copyright.md").write_text("Copyright 2026 Firefly Software Foundation.")
    (root / "manuscript/chapter.md").write_text("# A short chapter {.chtitle}\n\nBody text.")
    config["front"] = [{"id": "title", "file": "title.md", "nav": False},
                       {"id": "copyright", "file": "copyright.md", "nav": False}]
    (root / "book.yaml").write_text(yaml.safe_dump(config))
    rendered = []
    original = HTML.write_pdf

    def capture(self, *args, **kwargs):
        rendered.append(self.render(stylesheets=kwargs.get("stylesheets")))
        return original(self, *args, **kwargs)

    monkeypatch.setattr(HTML, "write_pdf", capture)
    book_build.main([])
    text = lambda page: " ".join(getattr(box, "text", "") for box in page._page_box.descendants())
    title, copyright = map(text, rendered[0].pages[1:3])
    assert title.count("jafly by example") == 1
    assert "JAFLY BY EXAMPLE" not in title
    assert "jafly by example" not in copyright.lower()
    assert "A SHORT CHAPTER" in text(rendered[0].pages[-2])


def test_interior_brand_assets_reach_epub_and_pdf_without_changing_reading_order(edition, monkeypatch):
    root, config = edition
    Image.new("RGB", (327, 242), "black").save(root / "art/logo.png")
    (root / "art/opener.svg").write_text('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 720 140"><rect width="720" height="140" fill="#10110f"/></svg>')
    config["interior_logo"] = "art/logo.png"
    config["parts"][0]["chapters"][0]["opener"] = "art/opener.svg"
    (root / "book.yaml").write_text(yaml.safe_dump(config))
    rendered = []
    original = HTML.write_pdf

    def capture(self, *args, **kwargs):
        rendered.append(self.render(stylesheets=kwargs.get("stylesheets")))
        return original(self, *args, **kwargs)

    monkeypatch.setattr(HTML, "write_pdf", capture)
    book_build.main([])
    ns = {"x": "http://www.w3.org/1999/xhtml"}
    with zipfile.ZipFile(root / "dist/edition.epub") as archive:
        divider = ET.fromstring(archive.read("OEBPS/parte-i.xhtml"))
        chapter = ET.fromstring(archive.read("OEBPS/ch1.xhtml"))
        for node, expected in ((divider, b"\x89PNG"), (chapter, b"<svg")):
            img = node.find(".//x:img", ns)
            assert img is not None
            assert archive.read("OEBPS/" + img.attrib["src"]).startswith(expected)
        assert "ch1.xhtml" in archive.read("OEBPS/nav.xhtml").decode()
    assert len(rendered[0].pages) == 5
    for page in rendered[0].pages[2:4]:
        images = [box for box in page._page_box.descendants() if getattr(box, "element_tag", None) == "img"]
        assert len(images) == 1
        assert images[0].position_x >= 74
        assert images[0].position_x + images[0].width <= 646
        assert images[0].position_y + images[0].height < 806


def test_manifest_chapter_heading_is_visible_once_when_manuscript_has_no_title(edition):
    root, config = edition
    (root / "manuscript/chapter.md").write_text("Body text without a duplicated manuscript title.")
    config["parts"][0]["chapters"][0]["title"] = "Arquitectura & límites"
    (root / "book.yaml").write_text(yaml.safe_dump(config))
    book_build.main([])
    with zipfile.ZipFile(root / "dist/edition.epub") as archive:
        doc = ET.fromstring(archive.read("OEBPS/ch1.xhtml"))
        headings = doc.findall(".//{http://www.w3.org/1999/xhtml}h1")
        assert len(headings) == 1
        assert headings[0].text == "1. Arquitectura & límites"
        assert headings[0].attrib["class"] == "chtitle"
