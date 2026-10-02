import tempfile
import unittest
from pathlib import Path
import xml.etree.ElementTree as ET

from repair_account_manifest import ACCOUNT, ANDROID, repair_manifest


class AccountManifestTest(unittest.TestCase):
    def check_manifest(self, activity):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'AndroidManifest.xml'
            path.write_text(
                '<manifest xmlns:android="' + ANDROID + '"><application>'
                '<activity android:name=".MainActivity" android:exported="false"/>'
                + activity + '</application></manifest>', encoding='utf-8')
            repair_manifest(path)
            first = path.read_bytes()
            repair_manifest(path)
            self.assertEqual(first, path.read_bytes())
            activities = ET.parse(path).getroot().find('application').findall('activity')
            account = [item for item in activities
                       if item.get('{' + ANDROID + '}name') == ACCOUNT]
            self.assertEqual(len(account), 1)
            self.assertEqual(account[0].get('{' + ANDROID + '}exported'), 'false')
            self.assertEqual(len(activities), 2)

    def test_missing_registration(self):
        self.check_manifest('')

    def test_relative_registration(self):
        self.check_manifest('<activity android:name=".AccountActivity" android:exported="true"/>')

    def test_existing_registration(self):
        self.check_manifest('<activity android:name="' + ACCOUNT + '" android:exported="false"/>')

    def test_invalid_manifest_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'AndroidManifest.xml'
            path.write_text('<manifest/>', encoding='utf-8')
            with self.assertRaises(ValueError):
                repair_manifest(path)
            self.assertEqual(path.read_text(), '<manifest/>')


if __name__ == '__main__':
    unittest.main()
