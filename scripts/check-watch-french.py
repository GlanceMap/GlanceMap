#!/usr/bin/env python3
"""Check watch French resources and optionally write the full length/unchanged-text audits."""
from pathlib import Path
from collections import Counter
import argparse
import csv
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
FORMAT = re.compile(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[A-Za-z%]")
TOOLS = "{http://schemas.android.com/tools}"
TICK = chr(96)
TITLE_PLURALS = {"download_bundle_status_installed", "download_bundle_status_refreshed"}
UNITS = set("km mi m mm h s min ft mph kg lb MB GB KB B MiB dp hPa dBm mAh GPS GPX POI BLE REC DEM W D v A N E S bpm".casefold().split())
SHARED_FRENCH = set("zoom cadence distance pause source standard destination gravel ok point latitude longitude menu notifications place places camping parking services altitude auto mode normal pack packs page sources style version".split())
ENGLISH_LEFTOVER = re.compile(r"\b(?:the|with|without|from|failed|waiting|download|settings|screen|watch|please|then|unavailable|recording)\b", re.I)


def text(element):
    value = "".join(element.itertext())
    if value.startswith('"') and value.endswith('"'):
        value = value[1:-1]
    escapes = {"n": "\n", "t": "\t", "r": "\r", "'": "'", '"': '"', "\\": "\\"}
    return re.sub(r"""\\([ntr'"\\])""", lambda m: escapes[m[1]], value)


def arguments(value):
    return Counter(FORMAT.findall(value))


def load(path):
    root = ET.parse(path).getroot()
    names = [e.attrib["name"] for e in root]
    assert len(names) == len(set(names)), f"Duplicate resource name: {path}"
    return {e.attrib["name"]: e for e in root}


def identical_reason(value):
    if value in {"Google fused", "Bluetooth"}:
        return "Provider or product name"
    words = {word.casefold() for word in re.findall(r"[A-Za-zÀ-ÿ]+", FORMAT.sub("", value))}
    if words <= UNITS:
        return "Technical symbol, unit, abbreviation or formatting template"
    assert words <= UNITS | SHARED_FRENCH, f"Review unchanged English-looking text: {value!r}"
    return "Valid shared French spelling or established French borrowing"


def run(report_dir):
    # Check the audit itself: reordering is allowed, type/precision changes are not.
    assert arguments("%2$s / %1$d %%") == arguments("%1$d / %2$s %%")
    assert arguments("%1$d") != arguments("%1$s")
    assert arguments("%1$.1f") != arguments("%1$.2f")
    assert text(ET.fromstring('<string>" a\\nb "</string>')) == " a\nb "
    base = load(ROOT / "app/src/main/res/values/strings.xml")
    english = {name: e for name, e in base.items() if e.get("translatable") != "false"}
    french = load(ROOT / "app/src/main/res/values-fr/strings.xml")
    assert english.keys() == french.keys(), {
        "missing": sorted(english.keys() - french.keys()),
        "extra_or_nontranslatable": sorted(french.keys() - english.keys()),
    }
    rows, identical = [], []
    for name, en in english.items():
        fr = french[name]
        assert fr.tag == en.tag and fr.get("translatable") != "false", name
        assert fr.get("formatted") == en.get("formatted"), (name, "formatted attribute")
        if en.tag == "string":
            pairs = [(name, en, fr)]
            assert [(e.tag, e.attrib) for e in en] == [(e.tag, e.attrib) for e in fr], (name, "markup")
        else:
            assert en.tag == "plurals", (name, en.tag)
            eq = {e.get("quantity"): e for e in en}
            fq = {e.get("quantity"): e for e in fr}
            assert len(fq) == len(fr) and set(fq) == {"one", "many", "other"}, name
            pairs = [(name + "." + q, eq.get(q, eq["other"]), e) for q, e in fq.items()]
        for key, a, b in pairs:
            x, y = text(a), text(b)
            assert arguments(x) == arguments(y), (key, "argument type/precision/count")
            assert x.count("\n") == y.count("\n"), (key, "newlines")
            assert x[:1].isspace() == y[:1].isspace(), (key, "leading whitespace")
            assert x[-1:].isspace() == y[-1:].isspace(), (key, "trailing whitespace")
            for glyph in ("•", "…", "→", "↑", "↓", "▲", "▼", "▾", "▸", "▽", "±", "°"):
                assert x.count(glyph) == y.count(glyph), (key, glyph)
            assert not any(c in y for c in "\u00a0\u202f\u2009"), (key, "unusual spacing")
            assert not ENGLISH_LEFTOVER.search(y), (key, "possible English leftover")
            if x == y:
                identical.append({"resource": key, "value": y, "classification": identical_reason(y)})
            expansion = round(100 * (len(y) - len(x)) / max(len(x), 1), 1)
            risk = "normal"
            if len(x) <= 80 and expansion >= 20:
                risk = "review"
            if len(x) <= 40 and expansion >= 50 and len(y) >= 10:
                risk = "high"
            if len(x) <= 80 and len(y) >= 55:
                risk = "high"
            rows.append({
                "resource": key, "english": x, "french": y,
                "english_length": len(x), "french_length": len(y),
                "expansion_percent": expansion, "risk": risk,
            })
        for item in fr.iter():
            ignore = item.get(TOOLS + "ignore")
            if ignore:
                assert name in TITLE_PLURALS and item.get("quantity") == "one" and ignore == "ImpliedQuantity"
    raw = ROOT / "app/src/main/res/raw"
    translated_raw = ROOT / "app/src/main/res/raw-fr"
    originals = {p.name for p in raw.glob("legal_*.md")}
    assert originals == {p.name for p in translated_raw.glob("*.md")}
    for name in sorted(originals):
        en = (raw / name).read_text()
        fr = (translated_raw / name).read_text()
        for pattern in (r"https?://[^\s" + TICK + "]+", TICK + "([^" + TICK + "]+)" + TICK, r"\b20\d{2}-\d{2}-\d{2}\b", r"\b[a-f0-9]{64}\b"):
            assert Counter(re.findall(pattern, en)) == Counter(re.findall(pattern, fr)), (name, pattern)
        assert re.findall(r"(?m)^(#+) ", en) == re.findall(r"(?m)^(#+) ", fr), (name, "heading structure")
        prose = re.sub(TICK + "[^" + TICK + "]+" + TICK + r"|https?://\S+", "", fr)
        assert not re.search(r"\b(?:the|with|without|from|Last reviewed|Last updated)\b", prose.replace("AI and the Commons", "")), name
    if report_dir:
        report_dir.mkdir(parents=True, exist_ok=True)
        rows.sort(key=lambda r: (r["english_length"] <= 40, r["expansion_percent"]), reverse=True)
        for filename, data in (("length-audit.csv", rows), ("unchanged-text-audit.csv", identical)):
            with (report_dir / filename).open("w", newline="") as output:
                writer = csv.DictWriter(output, fieldnames=data[0].keys())
                writer.writeheader()
                writer.writerows(data)
    counts = Counter(e.tag for e in english.values())
    print(f"PASS: {counts['string']} strings, {counts['plurals']} plurals, {len(rows)} French value variants.")
    print(f"PASS: {len(originals)} localized documents; names, placeholders, markup, whitespace, technical quotations and URLs preserved.")
    print(f"PASS: {len(identical)} identical variants classified; no accidental English leftovers detected.")
    print("Length flags:", dict(Counter(row["risk"] for row in rows)))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report-dir", type=Path)
    run(parser.parse_args().report_dir)
