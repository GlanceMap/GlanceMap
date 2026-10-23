"""Regression cases for locale discovery and rejected translation corruption."""
import base64
import contextlib
import importlib.util
import io
import os
import re
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("watch_translations", SCRIPTS / "check-watch-translations.py")
CHECK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECK)

BASE = '''<resources>
<string name="save">Save</string>
<string name="format">%2$s - %1$d %%</string>
<string name="precision">%1$.1f m</string>
<string name="lines">" A\\nB "</string>
<string name="styled">"2<annotation superscript="ordinal">nd</annotation> point"</string>
<string name="distance" formatted="false">"one{for # mile} other{for # miles}"</string>
<string name="brand" translatable="false">GlanceMap</string>
<plurals name="files"><item quantity="one">%1$d file</item><item quantity="other">%1$d files</item></plurals>
</resources>'''
TARGET = BASE.replace('<string name="brand" translatable="false">GlanceMap</string>', '').replace('>Save<', '>Speichern<')


class LocaleDiscoveryTest(unittest.TestCase):
    def test_configuration_qualifiers_are_not_languages(self):
        for name in ("values", "values-round", "values-night", "values-v31", "values-car", "values-land", "raw-watch", "values-anydpi-v26"):
            self.assertIsNone(CHECK.locale_directory(name), name)

    def test_regions_scripts_legacy_codes_and_qualifiers(self):
        cases = {
            "values-fr": ("fr", "fr", ""),
            "raw-pt-rBR": ("pt-BR", "pt-rBR", ""),
            "values-b+es+419": ("es-419", "b+es+419", ""),
            "values-fil": ("fil", "fil", ""),
            "values-b+sr+Latn": ("sr-Latn", "b+sr+Latn", ""),
            "values-b+zh+Hant+TW-night": ("zh-Hant-TW", "b+zh+Hant+TW", "night"),
            "values-iw": ("he", "iw", ""),
            "values-in-rID": ("id-ID", "in-rID", ""),
            "values-mcc310-mnc004-de-rDE-night-v31": ("de-DE", "de-rDE", "mcc310-mnc004-night-v31"),
        }
        for name, expected in cases.items():
            self.assertEqual(expected, CHECK.locale_directory(name))

    def test_numeric_regions_require_bcp_syntax(self):
        for name in ("values-es-r419", "raw-es-r419", "values-es-r419-night"):
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "use b\\+es\\+419"):
                CHECK.locale_directory(name)
        for language in ("de", "es", "fr", "it", "pl"):
            self.assertEqual((language, language, ""), CHECK.locale_directory("values-" + language))

    def test_invalid_bcp_locale_is_rejected(self):
        with self.assertRaises(ValueError):
            CHECK.locale_directory("values-b+sr+wrongscript")

    def test_weblate_filter_matches_base_locales_and_excludes_display_overrides(self):
        setup = (SCRIPTS.parent / 'docs/weblate-setup.md').read_text()
        pattern = re.search(r'```regex\n(.+)\n```', setup)[1]
        for name in ('de', 'es', 'fr', 'it', 'pl', 'pt-rBR', 'b+es+419', 'b+sr+Latn', 'b+zh+Hant+TW'):
            self.assertRegex(name, pattern)
        for name in ('round', 'night', 'v31', 'car', 'fr-night', 'es-r419'):
            self.assertIsNone(re.fullmatch(pattern, name))

    def test_icu_download_checksum_is_enforced_even_under_python_optimization(self):
        with tempfile.TemporaryDirectory() as temporary:
            jar = Path(temporary) / 'bad.jar'
            jar.write_bytes(b'not an ICU tool')
            env = {**os.environ, 'ICU4J_JAR': str(jar), 'PYTHONOPTIMIZE': '1'}
            result = subprocess.run(['bash', str(SCRIPTS / 'check-watch-translations.sh')], env=env, capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn('ICU4J checksum mismatch', result.stderr)


class TranslationValidationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.res = Path(self.temp.name)
        self.write("values/strings.xml", BASE)
        self.target = self.write("values-de/strings.xml", TARGET)

    def write(self, path, text):
        destination = self.res / path
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(text)
        return destination

    def validate(self):
        with contextlib.redirect_stdout(io.StringIO()):
            return CHECK.validate(self.res)

    def changed_target_fails(self, old, new):
        self.assertIn(old, self.target.read_text())
        self.target.write_text(self.target.read_text().replace(old, new))
        with self.assertRaises(ValueError):
            self.validate()

    def test_complete_locale_and_argument_reordering_pass(self):
        self.target.write_text(TARGET.replace('%2$s - %1$d %%', '%1$d %% - %2$s'))
        inventory, _ = self.validate()
        self.assertEqual(["de"], [item["locale"] for item in inventory])
        self.assertEqual(6, inventory[0]["strings"])
        self.assertEqual(1, inventory[0]["plurals"])

    def test_supported_format_tokens_and_modifiers_pass(self):
        for text in ('%1$s', '%1$d', '%1$.1f', '%1$.0f', '%%', '%n', '%1$-8.3s', '%1$08d', '%1$+08.2f'):
            with self.subTest(text=text):
                self.assertEqual({text: 1}, CHECK.signatures(text))
        self.assertEqual(CHECK.signatures('%2$s %1$d %% %n'), CHECK.signatures('%1$d %n %% %2$s'))

    def test_malformed_formats_report_resource_and_locale(self):
        examples = (('%1$s', '%<s %1$s'), ('%1$d', '%1$d %'), ('%1$s', '%1$s %2$'),
                    ('%1$s', '%1$s %<s'), ('%1$s', '%1$s %z'))
        for source, target in examples:
            with self.subTest(target=target):
                self.write('values/strings.xml', BASE.replace('%2$s - %1$d %%', source))
                self.target.write_text(TARGET.replace('%2$s - %1$d %%', target.replace('<', '&lt;')))
                with self.assertRaisesRegex(ValueError, 'values-de/format: malformed/unsupported Java format'):
                    self.validate()

    def test_invalid_format_modifiers_and_implicit_arguments_fail(self):
        for text in ('%s', '%0$s', '%1$+s', '%1$++d', '%1$-d', '%1$.1d', '%1$-08d', '%1$+ d',
                     '%1$2147483648s', '%2147483648$s', '%1$.2147483648f'):
            with self.subTest(text=text), self.assertRaises(ValueError):
                CHECK.signatures(text)

    def test_literal_percentage_labels_remain_valid(self):
        self.write('values/strings.xml', BASE.replace('>Save<', '>Default (100%)<'))
        self.target.write_text(TARGET.replace('>Speichern<', '>Standard (100%)<'))
        self.validate()
        for text in ('Standard (100%<s)', 'Standard (100%1$s)'):
            self.target.write_text(TARGET.replace('>Speichern<', '>' + text.replace('<', '&lt;') + '<'))
            with self.assertRaisesRegex(ValueError, 'unexpected Java argument in literal text'):
                self.validate()

    def test_spoken_icu_patterns_are_not_visual_length_warnings(self):
        self.target.write_text(TARGET.replace('one{for # mile} other{for # miles}',
                                             'one{über # Meile mit langem gesprochenem Text} other{über # Meilen mit langem gesprochenem Text}'))
        english = {name: e for name, e in CHECK.load(self.res / 'values/strings.xml').items() if e.get('translatable') != 'false'}
        _, rows, records = CHECK.validate_resources({'values': self.target.parent, 'locale': 'de', 'configuration': ''}, english)
        self.assertNotIn('distance', {row['resource'] for row in rows})
        self.assertTrue(any(row['resource'] == 'save' for row in rows))
        self.assertTrue(any(record[:2] == ('icu', 'de') and record[2].endswith('/distance') for record in records))

    def test_new_regional_language_requires_no_language_list_or_legal_copies(self):
        self.write("values-pt-rBR/strings.xml", TARGET.replace('</plurals>', '<item quantity="many">%1$d files</item></plurals>'))
        inventory, records = self.validate()
        self.assertEqual({"de", "pt-BR"}, {item["locale"] for item in inventory})
        self.assertTrue(all(item["raw_documents"] == 0 for item in inventory))
        self.assertIn(('plural', 'pt-BR', 'values-pt-rBR/files', 'many,one,other'), records)

    def test_round_override_is_ignored_and_localized_night_override_is_checked(self):
        self.write("values-round/strings.xml", '<resources><string name="not_a_translation">Round</string></resources>')
        self.write("values-de-night/strings.xml", '<resources><string name="save">Speichern</string></resources>')
        inventory, _ = self.validate()
        self.assertEqual(2, len(inventory))
        self.write("values-de-night/strings.xml", '<resources><string name="unknown">Wrong</string></resources>')
        with self.assertRaises(ValueError):
            self.validate()

    def test_missing_string_fails(self):
        self.changed_target_fails('<string name="save">Speichern</string>', '')

    def test_extra_or_nontranslatable_copy_fails(self):
        self.changed_target_fails('</resources>', '<string name="brand">GlanceMap</string></resources>')

    def test_placeholder_type_fails(self):
        self.changed_target_fails('%1$d %%', '%1$s %%')

    def test_placeholder_precision_fails(self):
        self.changed_target_fails('%1$.1f', '%1$.2f')

    def test_placeholder_index_fails(self):
        self.changed_target_fails('%2$s', '%3$s')

    def test_lost_escaped_percent_fails(self):
        self.changed_target_fails(' %%', ' %')

    def test_empty_translation_fails(self):
        self.changed_target_fails('>Speichern<', '><')

    def test_annotation_attribute_fails(self):
        self.changed_target_fails('superscript="ordinal"', 'superscript="wrong"')

    def test_newline_and_edge_whitespace_fail(self):
        self.changed_target_fails('" A\\nB "', '" A B "')
        self.target.write_text(TARGET)
        self.changed_target_fails('" A\\nB "', '"A\\nB "')

    def test_unknown_duplicate_and_missing_other_quantities_fail(self):
        self.changed_target_fails('quantity="other"', 'quantity="invalid"')
        self.target.write_text(TARGET)
        self.changed_target_fails('quantity="other"', 'quantity="one"')

    def test_xml_and_dtd_are_rejected(self):
        self.target.write_text('<resources><string>')
        with self.assertRaises(ET.ParseError):
            self.validate()
        self.target.write_text('<!DOCTYPE resources []>' + TARGET)
        with self.assertRaises(ValueError):
            self.validate()

    def test_duplicate_keys_across_files_fail(self):
        self.write('values-de/duplicate.xml', '<resources><string name="save">Speichern</string></resources>')
        with self.assertRaises(ValueError):
            self.validate()

    def test_length_expansion_warns_without_rejecting(self):
        self.target.write_text(TARGET.replace('Speichern', 'Ein sehr langes, aber gültiges Wort'))
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            CHECK.validate(self.res)
        self.assertIn('WARNING de: length-review', output.getvalue())

    def test_configured_language_cannot_disappear(self):
        self.write('xml/locales_config.xml', '<locale-config xmlns:android="http://schemas.android.com/apk/res/android"><locale android:name="fr"/></locale-config>')
        with self.assertRaises(ValueError):
            self.validate()

    def test_configured_locale_reports_the_actual_format_error(self):
        self.write('xml/locales_config.xml', '<locale-config xmlns:android="http://schemas.android.com/apk/res/android"><locale android:name="de"/></locale-config>')
        self.target.write_text(TARGET.replace('%2$s', '%2$d'))
        with self.assertRaisesRegex(ValueError, 'placeholder index/type/precision/count mismatch'):
            self.validate()

    def test_existing_locale_and_legal_files_cannot_disappear(self):
        listing = 'app/src/main/res/values-de/strings.xml\napp/src/main/res/raw-de/legal_safety.md\n'
        with patch.object(CHECK.subprocess, 'check_output', return_value=listing):
            with self.assertRaisesRegex(ValueError, 'Existing translation file deleted'):
                CHECK.preserve_existing_files(self.res, 'a' * 40)
            self.write('raw-de/legal_safety.md', '# Sicherheit\n')
            CHECK.preserve_existing_files(self.res, 'a' * 40)
        with self.assertRaises(ValueError):
            CHECK.preserve_existing_files(self.res, '--not-a-commit')

    def test_legal_documents_preserve_urls_and_cannot_be_silently_removed(self):
        source = '# Safety\nUse `GPX`. https://example.com/ 2026-10-23\n'
        self.write('raw/legal_safety.md', source)
        self.write('raw-de/legal_safety.md', source.replace('Safety', 'Sicherheit'))
        self.validate()
        self.write('raw-de/legal_safety.md', source.replace('https://example.com/', 'https://wrong.example/'))
        with self.assertRaises(ValueError):
            self.validate()
        self.write('raw-de/legal_safety.md', source)
        self.write('raw/legal_second.md', '# Second\n')
        with self.assertRaises(ValueError):
            self.validate()


class WorkflowWhitespaceTest(unittest.TestCase):
    def check_workflow(self, event, before='', parent=True, dirty_file=None):
        workflow = (SCRIPTS.parent / '.github/workflows/localization.yml').read_text()
        block = re.search(r'      - name: Check submitted whitespace\n        run: \|\n((?:          .*\n)+)', workflow)[1]
        script = '\n'.join(line[10:] for line in block.splitlines())
        # Fake only Git metadata; the dirty-file case uses Git's real whitespace checker.
        fake_git = '''git() {
          case "$1" in
            rev-parse) [[ "$MOCK_PARENT" == yes ]] || return 1; echo bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb ;;
            mktree) echo cccccccccccccccccccccccccccccccccccccccc ;;
            diff)
              [[ "$2" == --check && "$4" == HEAD && "$5" == -- ]] || return 1
              echo "CHECK $3 HEAD"
              if [[ -n "$MOCK_DIRTY_FILE" ]]; then
                command git diff --check --no-index /dev/null "$MOCK_DIRTY_FILE"
              fi
              ;;
            *) return 1 ;;
          esac
        }
        '''
        env = {**os.environ, 'GITHUB_EVENT_NAME': event, 'TRANSLATION_BASE_SHA': before,
               'MOCK_PARENT': 'yes' if parent else 'no', 'MOCK_DIRTY_FILE': str(dirty_file) if dirty_file else ''}
        return subprocess.run(['bash', '-euo', 'pipefail', '-c', fake_git + script], env=env, capture_output=True, text=True)

    def test_exact_event_bases_and_empty_tree_fallbacks(self):
        cases = [('pull_request', 'a' * 40, True, 'a' * 40), ('push', 'd' * 40, True, 'd' * 40),
                 ('push', '0' * 40, True, 'c' * 40), ('push', '', True, 'c' * 40),
                 ('workflow_dispatch', '', True, 'b' * 40), ('workflow_dispatch', '', False, 'c' * 40)]
        for event, before, parent, expected in cases:
            with self.subTest(event=event, before=before, parent=parent):
                result = self.check_workflow(event, before, parent)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertIn('CHECK ' + expected + ' HEAD', result.stdout)

    def test_missing_or_invalid_pr_base_is_rejected(self):
        for before in ('', '0' * 40, 'not-a-sha'):
            result = self.check_workflow('pull_request', before)
            self.assertNotEqual(0, result.returncode)
            self.assertNotIn('CHECK ', result.stdout)

    def test_submitted_whitespace_errors_fail_the_step(self):
        with tempfile.TemporaryDirectory() as temporary:
            dirty = Path(temporary) / 'submitted.txt'
            dirty.write_text('submitted trailing whitespace \n')
            result = self.check_workflow('pull_request', 'a' * 40, dirty_file=dirty)
            self.assertNotEqual(0, result.returncode)
            self.assertIn('trailing whitespace', result.stdout)


@unittest.skipUnless(os.environ.get("ICU4J_JAR") and shutil.which("javac"), "Set ICU4J_JAR and use JDK 17 for full ICU regressions")
class IcuValidationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.temp.cleanup)
        cls.classes = Path(cls.temp.name)
        cls.jar = Path(os.environ["ICU4J_JAR"]).resolve()
        subprocess.run(['javac', '--release', '17', '-cp', str(cls.jar), '-d', str(cls.classes), str(SCRIPTS / 'WatchTranslationIcuCheck.java')], check=True)

    def check(self, records, success):
        manifest = self.classes / 'test.tsv'
        manifest.write_text('\n'.join('\t'.join(row) for row in records) + '\n')
        result = subprocess.run(['java', '-cp', str(self.jar) + os.pathsep + str(self.classes), 'WatchTranslationIcuCheck', str(manifest)], capture_output=True, text=True)
        self.assertEqual(success, result.returncode == 0, result.stdout + result.stderr)

    def pattern(self, locale, pattern):
        return ('icu', locale, 'distance', base64.b64encode(pattern.encode()).decode())

    def test_arabic_japanese_and_regional_plural_categories_are_discovered(self):
        self.check([('plural', 'ar', 'files', 'zero,one,two,few,many,other'), ('plural', 'ja', 'files', 'other'),
                    ('plural', 'pt-BR', 'files', 'one,many,other')], True)
        self.check([('plural', 'ar', 'files', 'one,other')], False)

    def test_decimal_patterns_and_romance_million_fallback_pass(self):
        self.check([self.pattern('pl', 'one{przez # kilometr} few{przez # kilometry} many{przez # kilometrów} other{przez # kilometra}'),
                    self.pattern('fr', 'one{sur # kilomètre} other{sur # kilomètres}'),
                    self.pattern('ja', 'other{# キロメートル}')], True)

    def test_malformed_duplicate_and_missing_distance_patterns_fail(self):
        for text in ('one{for # mile} other{for # miles', 'one{#} one{#} other{#}', 'one{mile} other{miles}', 'offset:1 one{#} other{#}'):
            self.check([self.pattern('en', text)], False)

    def test_polish_does_not_inherit_english_plural_assumptions(self):
        self.check([self.pattern('pl', 'one{# kilometr} other{# kilometra}')], False)

    def test_unknown_language_fails(self):
        self.check([('plural', 'zz', 'files', 'one,other')], False)


if __name__ == '__main__':
    unittest.main()
