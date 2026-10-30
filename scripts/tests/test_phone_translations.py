"""Phone resources live in several base XML files; validate the whole set."""
import importlib.util
import re
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import xml.etree.ElementTree as ET

SCRIPT = Path(__file__).resolve().parents[1] / "check-phone-translations.py"
if not SCRIPT.exists():
    SCRIPT = Path(__file__).resolve().parents[3] / "scripts/check-phone-translations.py"
SPEC = importlib.util.spec_from_file_location("phone_translations", SCRIPT)
CHECK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECK)


class PhoneTranslationValidationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.res = Path(self.temp.name)
        (self.res / "values").mkdir()
        (self.res / "values" / "strings.xml").write_text('<resources><string name="save">Save</string></resources>')
        (self.res / "values" / "map.xml").write_text('<resources><string name="distance">Distance %1$s</string></resources>')
        (self.res / "values-fr").mkdir()
        (self.res / "values-fr" / "strings.xml").write_text('<resources><string name="save">Enregistrer</string></resources>')

    def test_strict_validation_requires_resources_from_every_base_file(self):
        with self.assertRaisesRegex(ValueError, "distance"):
            CHECK.validate(self.res)

    def test_draft_validation_reports_missing_without_accepting_broken_placeholders(self):
        inventory, _ = CHECK.validate(self.res, allow_incomplete=True)
        self.assertEqual(1, inventory[0]["missing"])
        (self.res / "values-fr" / "map.xml").write_text(
            '<resources><string name="distance">Distance</string></resources>'
        )
        with self.assertRaisesRegex(ValueError, "placeholder"):
            CHECK.validate(self.res, allow_incomplete=True)

    def test_complete_translation_passes(self):
        (self.res / "values-fr" / "map.xml").write_text(
            '<resources><string name="distance">Distance %1$s</string></resources>'
        )
        inventory, _ = CHECK.validate(self.res)
        self.assertEqual(0, inventory[0]["missing"])

    def test_duplicate_base_names_are_rejected(self):
        (self.res / "values" / "map.xml").write_text('<resources><string name="save">Save again</string></resources>')
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            CHECK.validate(self.res, allow_incomplete=True)

    def test_draft_in_wrong_component_is_rejected(self):
        (self.res / "values-fr" / "strings.xml").write_text(
            '<resources><string name="distance">Distance %1$s</string></resources>'
        )
        with self.assertRaisesRegex(ValueError, "component"):
            CHECK.validate(self.res, allow_incomplete=True)

    def test_drafts_can_use_separate_source_resources(self):
        drafts = self.res / "drafts"
        drafts.mkdir()
        (self.res / "values-fr").rename(drafts / "values-fr")
        inventory, _ = CHECK.validate(drafts, allow_incomplete=True, source_res=self.res)
        self.assertEqual(1, inventory[0]["missing"])

    def test_preservation_rejects_deleted_draft_file(self):
        with patch.object(CHECK.subprocess, "check_output", return_value="translations/phone/values-fr/map.xml\n"):
            with self.assertRaisesRegex(ValueError, "deleted"):
                CHECK.preserve_existing_files(self.res, "a" * 40)

    def test_weblate_discovery_covers_only_translatable_phone_base_files(self):
        documentation = (CHECK.ROOT / "docs/weblate-setup.md").read_text()
        section = documentation.split("## Phone app component discovery", 1)[1]
        pattern = re.compile(section.split("```regex\n", 1)[1].split("\n```", 1)[0])
        base = CHECK.ROOT / "glancemapcompanionapp/src/main/res/values"
        for path in base.glob("*.xml"):
            translatable = any(
                item.tag in {"string", "plurals"} and item.get("translatable") != "false"
                for item in ET.parse(path).getroot()
            )
            self.assertEqual(translatable, bool(pattern.fullmatch(path.relative_to(CHECK.ROOT).as_posix())), path.name)
