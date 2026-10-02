import unittest
from pathlib import Path
from repair_responsiveness import repair_source, replace_once


class RepairTests(unittest.TestCase):
    def test_missing_marker_rejected(self):
        with self.assertRaises(ValueError):
            replace_once("other", "expected", "replacement")

    def test_duplicate_marker_rejected(self):
        with self.assertRaises(ValueError):
            replace_once("xx", "x", "replacement")

    def test_actual_reference_source(self):
        source = Path(__file__).resolve().parents[2] / ".nenotv-tools/v0132-artifact/RA_IPTV_Android_v0.1/app/src/main/java/com/robertalt/raiptv/MainActivity.java"
        if not source.exists():
            self.skipTest("Local reference artifact is not available")
        result = repair_source(source.read_text(encoding="utf-8"))
        self.assertIn("loadLanguageGroupWithCache", result)
        self.assertIn("int cached,List<MediaEntry> cachedPage)", result)
        self.assertIn("loadOtherGroupWithCache", result)
        self.assertNotIn('if(epg){List<MediaEntry>x=sortItems(visibleItems(searchIndex.otherPage', result)
        self.assertIn("sectionComplete&&done==cats.size()&&current(token)", result)
        self.assertNotIn('T("first_sync_wait")+" · "+pct', result)
        self.assertNotIn("int n=searchIndex.countLanguage(profileKey(),section,tag)", result)
        self.assertNotIn('int indexed=searchIndex.count(profileKey());if(x.isEmpty()', result)
        with self.assertRaises(ValueError):
            repair_source(result)


if __name__ == "__main__":
    unittest.main()
