"""Tests for the Java-retargeted Markdown renderer (build/md.py).

The real entry point is ``render_markdown(text, base)`` and the custom listing
directive is the fenced form ``::: listing <label> | <caption>`` with the body
terminated by a lone ``:::`` line (NOT a triple-backtick fence).
"""
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "build"))
from md import render_markdown  # noqa: E402


def test_java_listing_emits_java_keyword_spans(tmp_path):
    src = (
        "::: listing lumen/Account.java | Listing 1.1 — a record\n"
        "public record Account(String id, long balanceCents) {}\n"
        ":::\n"
    )
    html = render_markdown(src, tmp_path)
    # filetab + caption survive the directive
    assert 'class="filetab">lumen/Account.java<' in html
    assert "Listing 1.1" in html
    assert 'class="listing"' in html
    # Pygments' Java lexer emits a keyword-declaration span (class="kd") around
    # 'public'/'record'. The Python lexer would NOT classify these as kd, so a
    # kd span proves the listing was highlighted as Java, not Python.
    assert 'class="kd"' in html
    assert ">public<" in html
    assert ">record<" in html
    # the literal code text survives highlighting
    assert "Account" in html and "balanceCents" in html


def test_xml_listing_renders_content(tmp_path):
    src = (
        "::: listing pom.xml | Listing 1.2 — a dependency\n"
        "<dependency>\n"
        "  <groupId>com.firefly</groupId>\n"
        "  <artifactId>lib-core</artifactId>\n"
        "</dependency>\n"
        ":::\n"
    )
    html = render_markdown(src, tmp_path)
    assert 'class="filetab">pom.xml<' in html
    assert 'class="listing"' in html
    # XML content survives (angle brackets escaped into the highlighted body)
    assert "dependency" in html
    assert "com.firefly" in html
    assert "lib-core" in html


def test_dotless_label_defaults_to_java(tmp_path):
    # a label with no extension should still highlight as Java, not Python
    src = (
        "::: listing Snippet | Listing 1.3\n"
        "public class Foo {}\n"
        ":::\n"
    )
    html = render_markdown(src, tmp_path)
    assert 'class="kd"' in html and ">public<" in html


def test_figure_inlines_svg(tmp_path):
    (tmp_path / "f.svg").write_text('<svg xmlns="http://www.w3.org/2000/svg"><rect/></svg>')
    html = render_markdown("::: figure f.svg | Figure 1.1 — demo\n", tmp_path)
    assert "<figure" in html and "<svg" in html and "Figure 1.1" in html


def test_spring_callout(tmp_path):
    html = render_markdown('!!! spring "Spring parity"\n    Same as Spring.\n', tmp_path)
    assert "admonition spring" in html and "Spring parity" in html
