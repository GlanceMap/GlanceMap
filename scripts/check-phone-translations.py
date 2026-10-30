#!/usr/bin/env python3
"""Validate phone translations against every Android base resource file."""
import argparse
import base64
import importlib.util
import json
import os
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent
WATCH_SCRIPT = HERE / "check-watch-translations.py"
if os.environ.get("WATCH_TRANSLATION_SCRIPT"):
    WATCH_SCRIPT = Path(os.environ["WATCH_TRANSLATION_SCRIPT"])
SPEC = importlib.util.spec_from_file_location("watch_translations", WATCH_SCRIPT)
WATCH = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(WATCH)
ROOT = WATCH.ROOT


def preserve_existing_files(res, base_ref):
    WATCH.require(re.fullmatch(r"[0-9a-f]{40,64}", base_ref), "Preservation base must be an exact Git commit SHA")
    prefix = "translations/phone/"
    paths = subprocess.check_output(
        ["git", "ls-tree", "-r", "--name-only", base_ref, "--", prefix], cwd=ROOT, text=True
    ).splitlines()
    for path in paths:
        relative = Path(path.removeprefix(prefix))
        if len(relative.parts) == 2 and WATCH.locale_directory(relative.parts[0]) and relative.suffix == ".xml":
            WATCH.require((res / relative).is_file(), f"Existing phone translation file deleted: {relative}")


def validate(res, allow_incomplete=False, source_res=None):
    english = {}
    owners = {}
    for path in sorted(((source_res or res) / "values").glob("*.xml")):
        for name, element in WATCH.load(path).items():
            WATCH.require(name not in english, f"Duplicate base resource: {name}")
            english[name] = element
            owners[name] = path.name
    english = {name: element for name, element in english.items() if element.get("translatable") != "false"}
    WATCH.require(english, "No translatable phone base resources found")
    groups = [group for group in WATCH.discover(res) if "values" in group]
    WATCH.require(groups, "No translated phone locale resources found")
    records = []
    for name, element in english.items():
        if element.tag == "plurals":
            records.append(("plural", "en", "values/" + name, ",".join(sorted(WATCH.plural_forms(element, name)))))
        elif WATCH.is_icu(element):
            records.append(("icu", "en", "values/" + name, base64.b64encode(WATCH.value(element).encode()).decode()))
    inventory = []
    for group in groups:
        present = set()
        for path in sorted(group["values"].glob("*.xml")):
            for name in WATCH.load(path):
                WATCH.require(name in owners and owners[name] == path.name,
                              f"{path}: {name} belongs to source component {owners.get(name, 'unknown')}")
                present.add(name)
        if not allow_incomplete and not group["configuration"]:
            missing = sorted(english.keys() - present)
            WATCH.require(not missing, f"{group['android_locale']}: {len(missing)} missing resources; examples: {missing[:8]}")
        # The watch validator can validate a subset as a configuration override.
        validation_group = dict(group)
        if allow_incomplete and not group["configuration"]:
            validation_group["configuration"] = "draft"
        translated, _, checks = WATCH.validate_resources(validation_group, english)
        records.extend(checks)
        missing = sorted(english.keys() - translated.keys()) if not group["configuration"] else []
        inventory.append({"locale": group["locale"], "configuration": group["configuration"],
                          "translated": len(translated), "total": len(english), "missing": len(missing)})
        print(f"{group['android_locale']}: {len(translated)}/{len(english)} resources; {len(missing)} missing")
    return inventory, records


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--res-dir", type=Path, default=ROOT / "translations/phone")
    parser.add_argument("--source-res-dir", type=Path, default=ROOT / "glancemapcompanionapp/src/main/res")
    parser.add_argument("--report-dir", type=Path)
    parser.add_argument("--preserve-from", help="Exact PR/push base commit; reject deletion of phone locale files")
    parser.add_argument("--icu4j-jar", type=Path, default=os.environ.get("ICU4J_JAR"))
    parser.add_argument("--allow-incomplete", action="store_true", help="Validate drafts, but report all missing resources")
    parser.add_argument("--structure-only", action="store_true", help="Skip ICU/CLDR checks; not a release validation")
    args = parser.parse_args()
    if args.preserve_from:
        preserve_existing_files(args.res_dir, args.preserve_from)
    inventory, records = validate(args.res_dir, args.allow_incomplete, args.source_res_dir)
    if args.report_dir:
        args.report_dir.mkdir(parents=True, exist_ok=True)
        (args.report_dir / "phone-inventory.json").write_text(json.dumps(inventory, indent=2) + "\n")
    if args.structure_only:
        print("WARNING: ICU/CLDR checks skipped; this is not a release validation")
        return
    WATCH.require(args.icu4j_jar and args.icu4j_jar.is_file(), "A verified ICU4J jar is required")
    with tempfile.TemporaryDirectory(prefix="glancemap-phone-translation-") as temporary:
        manifest = Path(temporary) / "checks.tsv"
        manifest.write_text("\n".join("\t".join(record) for record in records) + "\n")
        subprocess.run(["java", "-cp", str(args.icu4j_jar.resolve()),
                        str(WATCH_SCRIPT.with_name("WatchTranslationIcuCheck.java")), str(manifest)], check=True)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, ET.ParseError, subprocess.CalledProcessError) as error:
        raise SystemExit("Phone translation validation failed: " + str(error)) from error
