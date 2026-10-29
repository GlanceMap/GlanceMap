#!/usr/bin/env python3
"""Validate discovered Android watch locales without rewriting resources."""
import argparse
import base64
import collections
import csv
import json
import os
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
# Explicit positional s/d/f conversions are the app's supported Java format subset.
FORMAT = re.compile(r"%(?:(?P<special>[%n])|(?P<index>[1-9]\d*)\$(?P<flags>[-#+ 0,(]*)(?P<width>[1-9]\d*)?(?:\.(?P<precision>\d+))?(?P<conversion>[sdf]))")
FORMAT_HINT = re.compile(r"%(?:\d+\$|<|[-#+ 0,(]*\d*(?:\.\d+)?[A-Za-z%])")
QUANTITIES = {"zero", "one", "two", "few", "many", "other"}
BCP = re.compile(r"b\+[a-z]{2,3}(?:\+[A-Z][a-z]{3})?(?:\+(?:[A-Z]{2}|\d{3}))?(?:\+(?:[A-Za-z0-9]{5,8}|\d[A-Za-z0-9]{3}))*")
ALIASES = {"iw": "he", "in": "id", "ji": "yi"}
SYMBOLS = ("•", "…", "→", "↑", "↓", "▲", "▼", "▾", "▸", "▽", "±", "°")
TITLE_PLURALS = {"download_bundle_status_installed", "download_bundle_status_refreshed"}
TOOLS = "{http://schemas.android.com/tools}"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def locale_directory(name):
    """Return (BCP-47 locale, Android locale, configuration qualifiers), or None."""
    kind, separator, qualifiers = name.partition("-")
    if kind not in {"values", "raw"} or not separator:
        return None
    parts = qualifiers.split("-")
    configuration = []
    for pattern in (r"mcc\d{3}", r"mnc\d{1,3}"):
        if parts and re.fullmatch(pattern, parts[0]):
            configuration.append(parts.pop(0))
    if not parts:
        return None
    language = parts.pop(0)
    android = language
    if language.startswith("b+"):
        require(BCP.fullmatch(language), f"Invalid BCP-47 Android locale: {name}")
        tag = language[2:].replace("+", "-")
    elif re.fullmatch(r"[a-z]{2,3}", language) and language != "car":
        # car is an Android UI-mode qualifier; use b+car for the language.
        tag = ALIASES.get(language, language)
        if parts and re.fullmatch(r"r\d{3}", parts[0]):
            raise ValueError(f"Invalid numeric Android region in {name}; use b+{language}+{parts[0][1:]}")
        if parts and re.fullmatch(r"r[A-Z]{2}", parts[0]):
            region = parts.pop(0)
            android += "-" + region
            tag += "-" + region[1:]
    else:
        return None
    return tag, android, "-".join(configuration + parts)


def value(element):
    text = "".join(element.itertext())
    if text.startswith('"') and text.endswith('"'):
        text = text[1:-1]
    return re.sub(r'''\\([ntr'"\\])''', lambda m: {"n": "\n", "t": "\t", "r": "\r"}.get(m[1], m[1]), text)


def load(path):
    text = path.read_text(encoding="utf-8")
    require(not re.search(r"<!\s*(?:DOCTYPE|ENTITY)\b", text, re.I), f"DTD/entities are not allowed: {path}")
    root = ET.fromstring(text)
    require(root.tag == "resources", f"Expected resources root: {path}")
    result = {}
    for element in root:
        if element.tag not in {"string", "plurals"}:
            continue
        name = element.get("name")
        require(name and name not in result, f"Missing/duplicate resource name in {path}: {name}")
        result[name] = element
    return result


def discover(res):
    groups = {}
    for directory in sorted(res.iterdir()):
        if not directory.is_dir():
            continue
        parsed = locale_directory(directory.name)
        if parsed is None:
            continue
        tag, android, configuration = parsed
        kind = directory.name.split("-", 1)[0]
        if kind == "values":
            files = [p for p in sorted(directory.glob("*.xml")) if load(p)]
        else:
            files = sorted(directory.glob("*.md"))
        if not files:
            continue
        key = (tag, configuration)
        group = groups.setdefault(key, {"locale": tag, "android_locale": android, "configuration": configuration})
        require(kind not in group, f"Duplicate equivalent locale directories: {directory}")
        group[kind] = directory
    return sorted(groups.values(), key=lambda item: (item["locale"], item["configuration"]))


def signatures(text, label="format"):
    """Consume every percent sequence; never silently skip malformed syntax."""
    tokens, offset = collections.Counter(), 0
    while (offset := text.find("%", offset)) >= 0:
        token = FORMAT.match(text, offset)
        require(token is not None,
                f"{label}: malformed/unsupported Java format at character {offset}: {text[offset:offset + 24]!r}; use positional s/d/f, %% or %n (no relative indexing)")
        if not token["special"]:
            flags = token["flags"]
            allowed = {"s": "-", "d": "-+ 0,(", "f": "-#+ 0,("}[token["conversion"]]
            require(len(flags) == len(set(flags)) and set(flags) <= set(allowed), f"{label}: invalid/repeated format flags in {token[0]}")
            require(not ({"+", " "} <= set(flags) or {"-", "0"} <= set(flags)), f"{label}: conflicting format flags in {token[0]}")
            require(not set(flags) & {"-", "0"} or token["width"], f"{label}: format flag requires width in {token[0]}")
            require(token["conversion"] != "d" or token["precision"] is None, f"{label}: integer format cannot have precision: {token[0]}")
            require(all(part is None or int(part) <= 2147483647 for part in (token["index"], token["width"], token["precision"])),
                    f"{label}: format index/width/precision exceeds Java integer range")
        tokens[token[0]] += 1
        offset = token.end()
    return tokens


def markup(element):
    return [(child.tag, sorted(child.attrib.items()), markup(child)) for child in element]


def plural_forms(element, label):
    require(all(child.tag == "item" for child in element), f"{label}: invalid plural child")
    forms = {child.get("quantity"): child for child in element}
    require(len(forms) == len(element), f"{label}: duplicate plural quantity")
    require("other" in forms and forms.keys() <= QUANTITIES, f"{label}: invalid plural quantities {set(forms)}")
    return forms


def is_icu(element):
    # formatted=false is also legal for ordinary literal strings.
    return element.get("formatted") == "false" and bool(re.search(r"\bother\s*\{", value(element)))


def compare(original, translation, label):
    en, localized = value(original), value(translation)
    require(localized.strip() or not en.strip(), f"{label}: empty translation")
    if original.get("formatted") != "false" and FORMAT_HINT.search(en):
        require(signatures(en, label) == signatures(localized, label), f"{label}: placeholder index/type/precision/count mismatch")
    else:
        # Literal percentage labels and ICU patterns are not Java format strings.
        require(en.count("%") == localized.count("%"), f"{label}: literal percent count changed")
        if original.get("formatted") != "false":
            require(not re.search(r"%(?:\d+\$|<)", localized), f"{label}: unexpected Java argument in literal text")
    require(markup(original) == markup(translation), f"{label}: markup/annotation structure changed")
    require(en.count("\n") == localized.count("\n"), f"{label}: intentional newline count changed")
    for edge, pattern in (("leading", r"^\s*"), ("trailing", r"\s*$")):
        require(re.search(pattern, en)[0] == re.search(pattern, localized)[0], f"{label}: {edge} whitespace changed")
    for symbol in SYMBOLS:
        require(en.count(symbol) == localized.count(symbol), f"{label}: structural symbol {symbol} changed")
    expansion = round(100 * (len(localized) - len(en)) / max(len(en), 1), 1)
    risk = "review" if len(en) <= 80 and expansion >= 20 else "normal"
    if (len(en) <= 40 and expansion >= 50 and len(localized) >= 10) or (len(en) <= 80 and len(localized) >= 55):
        risk = "high"
    return {"resource": label, "english": en, "translation": localized, "english_length": len(en),
            "translation_length": len(localized), "expansion_percent": expansion, "risk": risk}


def validate_resources(group, english):
    directory = group["values"]
    translated = {}
    for path in sorted(directory.glob("*.xml")):
        for name, element in load(path).items():
            require(name not in translated, f"{directory.name}/{name}: duplicate across files")
            translated[name] = element
    primary = not group["configuration"]
    if primary:
        require((directory / "strings.xml").is_file(), f"{directory}: missing strings.xml")
        require(english.keys() == translated.keys(),
                f"{directory.name}: missing={sorted(english.keys() - translated.keys())}, extra/nontranslatable={sorted(translated.keys() - english.keys())}")
    else:
        require(translated.keys() <= english.keys(), f"{directory.name}: unknown configuration-override resource")
    rows, records = [], []
    for name, target in translated.items():
        source = english[name]
        label = directory.name + "/" + name
        require(source.tag == target.tag, f"{label}: resource type changed")
        require(target.get("translatable") != "false", f"{label}: translation marked nontranslatable")
        require(source.get("formatted") == target.get("formatted"), f"{label}: formatted attribute changed")
        if source.tag == "plurals":
            forms = plural_forms(target, label)
            originals = plural_forms(source, name)
            pairs = [(name + "." + q, originals.get(q, originals["other"]), child) for q, child in forms.items()]
            records.append(("plural", group["locale"], label, ",".join(sorted(forms))))
        else:
            pairs = [(name, source, target)]
            if is_icu(source):
                records.append(("icu", group["locale"], label, base64.b64encode(value(target).encode()).decode()))
        for key, original, translation in pairs:
            row = compare(original, translation, directory.name + "/" + key)
            row["resource"] = key
            if not is_icu(source):
                rows.append(row)
        for item in target.iter():
            ignore = item.get(TOOLS + "ignore")
            if ignore:
                require(name in TITLE_PLURALS and item.get("quantity") == "one" and ignore == "ImpliedQuantity",
                        f"{label}: unexpected lint suppression")
    return translated, rows, records


def validate_documents(directory, originals, complete=True):
    documents = {p.name: p for p in directory.glob("*.md")}
    require(documents.keys() <= originals.keys(), f"{directory}: unknown legal/raw document")
    if complete:
        require(documents.keys() == originals.keys(), f"{directory}: missing legal/raw documents")
    for name, path in documents.items():
        en, localized = originals[name].read_text(), path.read_text()
        for pattern in (r"https?://[^\s`]+", r"`([^`]+)`", r"\b20\d{2}-\d{2}-\d{2}\b", r"\b[a-f0-9]{64}\b"):
            require(collections.Counter(re.findall(pattern, en)) == collections.Counter(re.findall(pattern, localized)),
                    f"{directory.name}/{name}: technical quotation, URL, date or hash changed")
        require(re.findall(r"(?m)^(#+) ", en) == re.findall(r"(?m)^(#+) ", localized), f"{directory.name}/{name}: heading structure changed")
    return len(documents)


def preserve_existing_files(res, base_ref):
    """Reject silent deletion of approved locale files from the PR/push base."""
    require(re.fullmatch(r"[0-9a-f]{40,64}", base_ref), "Preservation base must be an exact Git commit SHA")
    prefix = "app/src/main/res/"
    paths = subprocess.check_output(["git", "ls-tree", "-r", "--name-only", base_ref, "--", prefix], cwd=ROOT, text=True).splitlines()
    count = 0
    for path in paths:
        relative = Path(path.removeprefix(prefix))
        if len(relative.parts) != 2 or locale_directory(relative.parts[0]) is None:
            continue
        if relative.suffix not in {".xml", ".md"}:
            continue
        require((res / relative).is_file(), f"Existing translation file deleted: {relative}; preserve approved work")
        count += 1
    print(f"PASS preservation: {count} existing locale XML/raw files retained from {base_ref[:12]}")


def validate(res, report_dir=None):
    english = {name: e for name, e in load(res / "values/strings.xml").items() if e.get("translatable") != "false"}
    groups = discover(res)
    require(groups, "No translated locale resources found")
    records = []
    for name, element in english.items():
        if element.tag == "plurals":
            records.append(("plural", "en", "values/" + name, ",".join(sorted(plural_forms(element, name)))))
        elif is_icu(element):
            records.append(("icu", "en", "values/" + name, base64.b64encode(value(element).encode()).decode()))
    originals = {p.name: p for p in (res / "raw").glob("legal_*.md")}
    inventory, errors = [], []
    for group in groups:
        try:
            translated, rows = {}, []
            if "values" in group:
                translated, rows, checks = validate_resources(group, english)
                records.extend(checks)
            count = validate_documents(group["raw"], originals, not group["configuration"]) if "raw" in group else 0
            android = group["android_locale"]
            variant = android + ("-" + group["configuration"] if group["configuration"] else "")
            counts = collections.Counter(e.tag for e in translated.values())
            item = {"locale": group["locale"], "android_locale": android, "configuration": group["configuration"],
                    "strings_xml": str(group["values"] / "strings.xml") if "values" in group else None,
                    "strings": counts["string"], "plurals": counts["plurals"], "raw_documents": count}
            inventory.append(item)
            print(f"PASS {variant}: {counts['string']} strings, {counts['plurals']} plurals, {count} legal/raw documents")
            flags = collections.Counter(row["risk"] for row in rows)
            print(f"WARNING {variant}: length-review flags {dict(flags)} (non-blocking; inspect on a watch)")
            print(f"INFO {variant}: visual-length audit excludes spoken ICU patterns; ICU correctness is checked separately")
            risks = sorted((row for row in rows if row["risk"] == "high"), key=lambda row: row["expansion_percent"], reverse=True)
            for row in risks[:5]:
                print(f"  REVIEW {variant}/{row['resource']}: {row['english_length']} → {row['translation_length']} characters ({row['expansion_percent']:+.1f}%)")
            if "raw" not in group:
                print(f"INFO {variant}: legal/raw resources fall back to English; no document copies required for a new UI locale")
            if report_dir and rows:
                report_dir.mkdir(parents=True, exist_ok=True)
                rows.sort(key=lambda row: (row["english_length"] <= 40, row["expansion_percent"]), reverse=True)
                with (report_dir / f"length-audit-{variant}.csv").open("w", newline="") as output:
                    writer = csv.DictWriter(output, fieldnames=rows[0].keys())
                    writer.writeheader()
                    writer.writerows(rows)
        except (ValueError, ET.ParseError) as error:
            errors.append(str(error))
    configured_file = res / "xml/locales_config.xml"
    if configured_file.exists():
        configured = [e.get("{http://schemas.android.com/apk/res/android}name") for e in ET.parse(configured_file).getroot()]
        primary = {group["locale"] for group in groups if not group["configuration"] and "values" in group}
        require(len(configured) == len(set(configured)), "Duplicate configured app locale")
        require(set(configured) <= {"en", *primary}, "A configured/shipped locale has no complete base translation")
        for tag in sorted(primary - set(configured)):
            print(f"INFO {tag}: resources validated; not yet advertised by locales_config/picker (maintainer release decision)")
    require(not errors, "\n".join(errors))
    if report_dir:
        report_dir.mkdir(parents=True, exist_ok=True)
        (report_dir / "inventory.json").write_text(json.dumps(inventory, indent=2) + "\n")
    return inventory, records


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--res-dir", type=Path, default=ROOT / "app/src/main/res")
    parser.add_argument("--report-dir", type=Path)
    parser.add_argument("--icu4j-jar", type=Path, default=os.environ.get("ICU4J_JAR"))
    parser.add_argument("--preserve-from", help="Exact PR/push base commit; reject deletion of existing locale files")
    parser.add_argument("--structure-only", action="store_true", help="Explicitly skip JVM ICU/CLDR checks; never used by CI")
    args = parser.parse_args()
    if args.preserve_from:
        preserve_existing_files(args.res_dir, args.preserve_from)
    _, records = validate(args.res_dir, args.report_dir)
    if args.structure_only:
        print("WARNING: ICU parsing, decimal rendering and CLDR category checks were NOT run")
        return
    require(args.icu4j_jar and args.icu4j_jar.is_file(), "Run scripts/check-watch-translations.sh, or supply --icu4j-jar; full validation requires ICU4J")
    with tempfile.TemporaryDirectory(prefix="glancemap-translation-check-") as temporary:
        manifest = Path(temporary) / "checks.tsv"
        manifest.write_text("\n".join("\t".join(record) for record in records) + "\n")
        subprocess.run(["java", "-cp", str(args.icu4j_jar.resolve()), str(Path(__file__).with_name("WatchTranslationIcuCheck.java")), str(manifest)], check=True)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, ET.ParseError, subprocess.CalledProcessError) as error:
        raise SystemExit("Translation validation failed: " + str(error)) from error
