"""Tests for the Java/XML verbatim-slice verifier (build/verify_code.py).

LOAD-BEARING guarantee under test: every ``::: listing <label>`` whose label
ends in .java/.xml must be a verbatim slice of the file ``<reactor>/<label>``.
"""
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "build"))
from verify_code import extract_listings, is_verbatim_slice, verify  # noqa: E402


def test_extract_only_java_and_xml_labels():
    md = (
        "intro\n"
        "::: listing src/main/java/Account.java | L1\n"
        "public record Account() {}\n"
        ":::\n"
        "::: listing pom.xml | L2\n"
        "<project/>\n"
        ":::\n"
        "::: listing notes.txt | not code\n"
        "noise\n"
        ":::\n"
        "::: listing diagram.py | also ignored\n"
        "x = 1\n"
        ":::\n"
    )
    listings = extract_listings(md)
    assert [l.label for l in listings] == ["src/main/java/Account.java", "pom.xml"]
    # body is captured without the directive/terminator lines
    assert listings[0].body == "public record Account() {}"
    assert listings[1].body == "<project/>"
    # line numbers point at the directive line (1-based)
    assert listings[0].line == 2


def test_is_verbatim_slice_exact_substring():
    file_text = (
        "package com.firefly.lumen;\n"
        "\n"
        "public record Account(String id) {}\n"
    )
    assert is_verbatim_slice("public record Account(String id) {}", file_text)


def test_is_verbatim_slice_rejects_one_char_change():
    file_text = "public record Account(String id) {}\n"
    # 'Acount' (one char dropped) must NOT match
    assert not is_verbatim_slice("public record Acount(String id) {}", file_text)


def test_verify_passes_for_verbatim_listing(tmp_path):
    reactor = tmp_path / "reactor"
    (reactor / "src").mkdir(parents=True)
    (reactor / "src" / "Account.java").write_text(
        "package x;\n\npublic record Account(String id) {}\n"
    )
    man = tmp_path / "manuscript"
    man.mkdir()
    (man / "ch01.md").write_text(
        "::: listing src/Account.java | L1\n"
        "public record Account(String id) {}\n"
        ":::\n"
    )
    assert verify(str(man), str(reactor)) == []


def test_verify_flags_missing_file_and_non_slice(tmp_path):
    reactor = tmp_path / "reactor"
    reactor.mkdir()
    (reactor / "Account.java").write_text("public record Account(String id) {}\n")
    man = tmp_path / "manuscript"
    man.mkdir()
    (man / "ch01.md").write_text(
        "::: listing Account.java | drifted\n"
        "public record Acount(String id) {}\n"   # typo -> not a slice
        ":::\n"
        "::: listing Missing.java | gone\n"
        "whatever\n"
        ":::\n"
    )
    fails = verify(str(man), str(reactor))
    reasons = {(f[2], f[3]) for f in fails}
    assert ("Account.java", "not a verbatim slice") in reasons
    assert ("Missing.java", "file not found in reactor") in reasons


def test_verify_missing_reactor_dir_is_success(tmp_path):
    # no listings anywhere + a non-existent reactor dir -> trivially passes
    man = tmp_path / "manuscript"
    man.mkdir()
    (man / "ch01.md").write_text("# Just prose, no listings\n")
    assert verify(str(man), str(tmp_path / "does-not-exist")) == []


def test_extract_handles_indented_close_without_swallowing_next():
    # md.py closes a listing on the first line whose .strip() == ':::', so an
    # INDENTED close is valid. The verifier must agree: the first listing must
    # end at its indented close, and the following listing must be extracted
    # independently (not swallowed into the first body).
    md = (
        "::: listing First.java | a\n"
        "class First {}\n"
        "    :::\n"                      # indented close
        "::: listing Second.java | b\n"
        "class Second {}\n"
        ":::\n"
    )
    listings = extract_listings(md)
    assert [l.label for l in listings] == ["First.java", "Second.java"]
    assert listings[0].body == "class First {}"
    assert "Second" not in listings[0].body
    assert listings[1].body == "class Second {}"


def test_is_verbatim_slice_tolerates_trailing_whitespace():
    # Manuscripts and editors routinely add/strip trailing spaces; a slice must
    # still match across that difference (the body has none, the file has some).
    file_text = "public record Account(String id) {}   \n"   # trailing spaces
    assert is_verbatim_slice("public record Account(String id) {}", file_text)


def test_verify_allows_elided_body(tmp_path):
    # bodies containing '...' are excerpts, not exact slices -> not checked
    reactor = tmp_path / "reactor"
    reactor.mkdir()
    (reactor / "Big.java").write_text("class Big { void a(){} void b(){} }\n")
    man = tmp_path / "manuscript"
    man.mkdir()
    (man / "ch01.md").write_text(
        "::: listing Big.java | excerpt\n"
        "class Big {\n"
        "  ...\n"
        "}\n"
        ":::\n"
    )
    assert verify(str(man), str(reactor)) == []
