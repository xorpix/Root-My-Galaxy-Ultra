#!/usr/bin/env python3
import tempfile
from pathlib import Path
import unittest

import check_translations as translations


class TranslationChecks(unittest.TestCase):
    def test_reordered_arguments_keep_their_types(self):
        self.assertEqual(translations.arguments('%1$d: %2$s'),
                         translations.arguments('%2$s: %1$d%%'))
        self.assertNotEqual(translations.arguments('%1$d'), translations.arguments('%1$s'))

    def fixture(self, root, localized):
        res = root / 'app/src/main/res'
        (res / 'values').mkdir(parents=True)
        (res / 'values-ru').mkdir()
        (res / 'xml').mkdir()
        (res / 'values/strings.xml').write_text('<resources><string name="count">%1$d files</string>'
            '<plurals name="runs"><item quantity="one">One run</item>'
            '<item quantity="other">%1$d runs</item></plurals></resources>')
        (res / 'values-ru/strings.xml').write_text(localized)
        (res / 'xml/locales_config.xml').write_text('<locale-config xmlns:android="http://schemas.android.com/apk/res/android">'
            '<locale android:name="en"/><locale android:name="ru"/></locale-config>')
        activity = root / 'app/src/main/java/dev/busung/s25uroot/MainActivity.kt'
        activity.parent.mkdir(parents=True)
        activity.write_text('LanguageOption(R.string.en, "en"), LanguageOption(R.string.ru, "ru")')

    def test_missing_resource_and_invalid_format_are_reported(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.fixture(root, '<resources><string name="count">%1$s файлов</string></resources>')
            errors, _, _ = translations.check(root)
            self.assertTrue(any('missing runs' in error for error in errors))
            self.assertTrue(any('format arguments' in error for error in errors))

    def test_locale_specific_plurals_and_count_in_one_are_accepted(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.fixture(root, '<resources><string name="count">%1$d файлов</string><plurals name="runs">'
                '<item quantity="one">%1$d запуск</item><item quantity="few">%1$d запуска</item>'
                '<item quantity="many">%1$d запусков</item><item quantity="other">%1$d запуска</item>'
                '</plurals></resources>')
            self.assertEqual(translations.check(root)[0], [])

    def test_missing_russian_plural_categories_are_reported(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.fixture(root, '<resources><string name="count">%1$d файлов</string><plurals name="runs">'
                '<item quantity="one">Один запуск</item><item quantity="other">%1$d запусков</item>'
                '</plurals></resources>')
            errors, _, _ = translations.check(root)
            self.assertTrue(any('plural quantity few' in error for error in errors))
            self.assertTrue(any('plural quantity many' in error for error in errors))

    def test_duplicate_resources_are_reported(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.fixture(root, '<resources><string name="count">%1$d</string>'
                '<string name="count">%1$d</string></resources>')
            errors, _, _ = translations.check(root)
            self.assertTrue(any('duplicate resource count' in error for error in errors))

    def test_unresolved_draft_characters_are_reported(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.fixture(root, '<resources><string name="count">%1$d ⁇</string></resources>')
            errors, _, _ = translations.check(root)
            self.assertTrue(any('unresolved translation' in error for error in errors))


if __name__ == '__main__':
    unittest.main()
