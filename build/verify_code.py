"""Verify that every ``::: listing <label>`` whose label ends in .java / .xml is
a verbatim slice of the file ``<reactor>/<label>`` in the built Maven reactor.

This is the load-bearing guarantee of *Firefly for Java by Example*: the code on
the page is exactly the code that compiles and runs in the sample reactor, never
a hand-edited paraphrase that has silently drifted.

NOTE on the directive syntax: the manuscript's custom listing directive (see
build/md.py) is the fenced form

    ::: listing src/main/java/com/firefly/Account.java | Listing 1.1 — caption
    <code lines...>
    :::

i.e. an opening ``::: listing <label> [| caption]`` line, the body, then a lone
``:::`` terminator -- NOT a triple-backtick fence. The regex below matches that
real form.

Run standalone:
    build/.venv/bin/python build/verify_code.py manuscript samples/lumen-lending
Exits 0 when every checkable listing is a verbatim slice (and 0 when there are no
listings or the reactor dir is missing -- nothing to verify is success).
"""
from __future__ import annotations
import re
import pathlib
from dataclasses import dataclass

# Opening directive line, the body (lazily), and the lone ':::' close.
_LISTING = re.compile(
    r"^:::[ \t]*listing[ \t]+(?P<label>[^|\n]+?)[ \t]*(?:\|[^\n]*)?\n"
    r"(?P<body>.*?)\n:::[ \t]*$",
    re.S | re.M,
)


@dataclass
class Listing:
    label: str
    body: str
    line: int


def extract_listings(md_text, exts=(".java", ".xml")):
    """Return the ``::: listing`` blocks whose label ends in one of ``exts``."""
    out = []
    for m in _LISTING.finditer(md_text):
        label = m.group("label").strip()
        if label.endswith(tuple(exts)):
            line = md_text[: m.start()].count("\n") + 1
            out.append(Listing(label, m.group("body"), line))
    return out


def _norm(s):
    """Normalize for comparison: strip leading/trailing blank lines and trailing
    whitespace on each line (manuscripts routinely lose trailing spaces)."""
    lines = [ln.rstrip() for ln in s.strip("\n").splitlines()]
    return "\n".join(lines)


def is_verbatim_slice(body, file_text):
    return _norm(body) in _norm(file_text)


def verify(manuscript_dir, reactor_dir):
    """Return a list of (md_file, line, label, reason) failures (empty == OK).

    A missing reactor dir is not itself an error: only listings that reference a
    file count, and each such reference fails with 'file not found in reactor'.
    With no listings at all the result is an empty list (trivially passing)."""
    failures = []
    reactor = pathlib.Path(reactor_dir)
    man = pathlib.Path(manuscript_dir)
    if not man.exists():
        return failures
    for md_file in sorted(man.rglob("*.md")):
        for lst in extract_listings(md_file.read_text(encoding="utf-8")):
            target = reactor / lst.label
            if not target.exists():
                failures.append((str(md_file), lst.line, lst.label, "file not found in reactor"))
                continue
            # An elided body ('...') is an excerpt, not an exact slice -- skip it.
            if "..." not in lst.body and not is_verbatim_slice(lst.body, target.read_text(encoding="utf-8")):
                failures.append((str(md_file), lst.line, lst.label, "not a verbatim slice"))
    return failures


if __name__ == "__main__":
    import sys
    manuscript = sys.argv[1] if len(sys.argv) > 1 else "manuscript"
    reactor = sys.argv[2] if len(sys.argv) > 2 else "samples/lumen-lending"
    fails = verify(manuscript, reactor)
    for f in fails:
        print("FAIL %s:%d [%s] %s" % f)
    print(f"verify_code: {len(fails)} failing listing(s)")
    sys.exit(1 if fails else 0)
