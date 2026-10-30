import importlib.util
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).resolve().parents[1] / 'check-phone-ui-strings.py'
spec = importlib.util.spec_from_file_location('phone_ui_strings', MODULE_PATH)
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)

class PhoneUiStringsTest(unittest.TestCase):
    def test_reports_direct_labels_and_titles(self):
        source = 'Text("Hello")\nText(text = "Save")\nbuilder.setContentTitle("Recording")'
        self.assertEqual([x[1] for x in checker.findings(source)], ['Hello', 'Save', 'Recording'])

    def test_allows_resources_brands_and_values(self):
        source = 'Text(stringResource(R.string.hello))\nText("OSM")\nText("Refuges.info")\nText("$percent%")'
        self.assertEqual(list(checker.findings(source)), [])

    def test_allows_interpolated_values_with_unit_symbols(self):
        source = 'Text("${distance} · ↑ ${ascent} m")'
        self.assertEqual(list(checker.findings(source)), [])

    def test_reports_mixed_interpolated_text(self):
        self.assertEqual([x[1] for x in checker.findings('Text("$name saved")')], ['$name saved'])

    def test_ignores_non_ui_helpers(self):
        self.assertEqual(list(checker.findings('data.trailText("name")\ncall.respondText("OK")')), [])

if __name__ == '__main__':
    unittest.main()
