#!/usr/bin/env python3
"""Validate all shipped watch translations; optionally export watch-length review CSVs."""
import argparse
import collections
import csv
import re
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
FORMAT = re.compile(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[A-Za-z%]")
QUANTITIES = {"fr": {"one", "many", "other"}, "es": {"one", "many", "other"}, "de": {"one", "other"}, "it": {"one", "many", "other"}, "pl": {"one", "few", "many", "other"}}


def value(element):
    text = "".join(element.itertext())
    if text.startswith('"') and text.endswith('"'):
        text = text[1:-1]
    return re.sub(r'''\\([ntr'"\\])''', lambda m: {"n": "\n", "t": "\t", "r": "\r"}.get(m[1], m[1]), text)


def load(path):
    elements = list(ET.parse(path).getroot())
    names = [e.attrib["name"] for e in elements]
    assert len(names) == len(set(names)), (path, "duplicate keys")
    return {e.attrib["name"]: e for e in elements}


def signatures(text):
    return collections.Counter(FORMAT.findall(text))


def validate(language, english, report_dir):
    translated = load(RES / f"values-{language}/strings.xml")
    assert english.keys() == translated.keys(), (language, "missing", english.keys() - translated.keys(), "extra", translated.keys() - english.keys())
    rows = []
    for name, source in english.items():
        target = translated[name]
        assert source.tag == target.tag, (language, name, "type")
        assert target.get("translatable") != "false", (language, name, "untranslated")
        assert source.get("formatted") == target.get("formatted"), (language, name, "formatted")
        if source.tag == "plurals":
            quantities = [e.get("quantity") for e in target]
            assert len(quantities) == len(set(quantities)), (language, name, "duplicate quantity")
            assert set(quantities) == QUANTITIES[language], (language, name, quantities)
            originals = {e.get("quantity"): e for e in source}
            pairs = [(name + "." + e.get("quantity"), originals.get(e.get("quantity"), originals["other"]), e) for e in target]
        else:
            assert source.tag == "string", source.tag
            assert [(e.tag, e.attrib) for e in source] == [(e.tag, e.attrib) for e in target], (language, name, "markup")
            pairs = [(name, source, target)]
        for key, original, translation in pairs:
            en, localized = value(original), value(translation)
            assert signatures(en) == signatures(localized), (language, key, "placeholders")
            assert en.count("\n") == localized.count("\n"), (language, key, "newlines")
            assert en[:1].isspace() == localized[:1].isspace(), (language, key, "leading whitespace")
            assert en[-1:].isspace() == localized[-1:].isspace(), (language, key, "trailing whitespace")
            for symbol in ("•", "…", "→", "↑", "↓", "▲", "▼", "▾", "▸", "▽", "±", "°"):
                assert en.count(symbol) == localized.count(symbol), (language, key, symbol)
            if original.get("formatted") == "false":
                # Android instrumentation checks actual ICU parsing and decimal rendering.
                categories = ("one", "few", "many", "other") if language == "pl" else ("one", "other")
                pattern = " ".join(category + r"\{[^{}]*#[^{}]*\}" for category in categories)
                assert re.fullmatch(pattern, localized), (language, key, "ICU shape")
            expansion = round(100 * (len(localized) - len(en)) / max(len(en), 1), 1)
            risk = "review" if len(en) <= 80 and expansion >= 20 else "normal"
            if (len(en) <= 40 and expansion >= 50 and len(localized) >= 10) or (len(en) <= 80 and len(localized) >= 55):
                risk = "high"
            rows.append({"resource": key, "english": en, "translation": localized, "english_length": len(en), "translation_length": len(localized), "expansion_percent": expansion, "risk": risk})
        for item in target.iter():
            ignore = item.get("{http://schemas.android.com/tools}ignore")
            if ignore:
                assert name in {"download_bundle_status_installed", "download_bundle_status_refreshed"} and item.get("quantity") == "one" and ignore == "ImpliedQuantity", (language, name, "unexpected lint suppression")
    originals = {p.name: p for p in (RES / "raw").glob("legal_*.md")}
    documents = {p.name: p for p in (RES / f"raw-{language}").glob("*.md")}
    assert originals.keys() == documents.keys(), (language, "legal documents")
    for name, source in originals.items():
        en, localized = source.read_text(), documents[name].read_text()
        for pattern in (r"https?://[^\s`]+", r"`([^`]+)`", r"\b20\d{2}-\d{2}-\d{2}\b", r"\b[a-f0-9]{64}\b"):
            assert collections.Counter(re.findall(pattern, en)) == collections.Counter(re.findall(pattern, localized)), (language, name, pattern)
        assert re.findall(r"(?m)^(#+) ", en) == re.findall(r"(?m)^(#+) ", localized), (language, name, "headings")
    if report_dir:
        report_dir.mkdir(parents=True, exist_ok=True)
        rows.sort(key=lambda row: (row["english_length"] <= 40, row["expansion_percent"]), reverse=True)
        with (report_dir / f"length-audit-{language}.csv").open("w", newline="") as output:
            writer = csv.DictWriter(output, fieldnames=rows[0].keys())
            writer.writeheader()
            writer.writerows(rows)
    counts = collections.Counter(e.tag for e in english.values())
    print(f"PASS {language}: {counts['string']} strings, {counts['plurals']} plurals, {len(documents)} documents; names, placeholders, markup and whitespace match.")
    print(f"Length flags {language}: {dict(collections.Counter(row['risk'] for row in rows))}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report-dir", type=Path)
    args = parser.parse_args()
    # Reordering remains legal, changing an argument type or precision does not.
    assert signatures("%2$s %1$d %%") == signatures("%1$d %% %2$s")
    assert signatures("%1$d") != signatures("%1$s")
    assert signatures("%1$.1f") != signatures("%1$.2f")
    english = {name: e for name, e in load(RES / "values/strings.xml").items() if e.get("translatable") != "false"}
    configured = [e.get("{http://schemas.android.com/apk/res/android}name") for e in ET.parse(RES / "xml/locales_config.xml").getroot()]
    assert len(configured) == len(set(configured)), "Duplicate configured locale"
    assert set(configured) == {"en", *QUANTITIES}, "Update validation rules when adding a locale"
    for language in QUANTITIES:
        validate(language, english, args.report_dir)


if __name__ == "__main__":
    main()
