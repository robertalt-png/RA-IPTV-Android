import unittest
import json
import re
import sqlite3
from pathlib import Path
from repair_responsiveness import repair_source
from repair_streaming_import import RUNTIME, main_source, provider_source, store_source


class StreamingRepairTests(unittest.TestCase):
    def setUp(self):
        template = (RUNTIME / "SectionImportMethods.java.txt").read_text(encoding="utf-8")
        statements = [json.loads('"' + match + '"') for match in re.findall(r'db\.execSQL\("((?:[^"\\]|\\.)*)"', template)]
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        self.db.execute(statements[0])
        self.commit_sql = statements[1]
        self.db.execute("CREATE TABLE entries(profile TEXT,item_key TEXT,type TEXT,name TEXT,name_norm TEXT,hay_norm TEXT,lang_tag TEXT,lang_scanned INTEGER,payload TEXT,PRIMARY KEY(profile,item_key))")
        self.db.executemany("INSERT INTO entries(profile,item_key,type,payload) VALUES(?,?,?,?)", [("p", "old", "live", "old"), ("other", "old", "live", "other"), ("p", "movie", "vod", "movie")])
        self.db.execute("INSERT INTO import_entries(session,profile,item_key,type,payload,started) VALUES('s','p','new','live','new',1)")
        self.db.commit()

    def publish(self):
        self.db.execute("DELETE FROM entries WHERE profile=? AND type=?", ("p", "live"))
        self.db.execute(self.commit_sql, ("s", "p", "live"))

    def test_sql_commit_preserves_other_profiles_and_sections(self):
        with self.db:
            self.publish()
        self.assertEqual(self.db.execute("SELECT payload FROM entries ORDER BY payload").fetchall(), [("movie",), ("new",), ("other",)])

    def test_sql_rollback_preserves_old_library(self):
        self.db.execute("BEGIN")
        self.publish()
        self.db.rollback()
        self.assertEqual(self.db.execute("SELECT payload FROM entries WHERE profile='p' AND type='live'").fetchall(), [("old",)])

    def test_sql_empty_snapshot_removes_only_target_section(self):
        self.db.execute("DELETE FROM import_entries")
        with self.db:
            self.publish()
        self.assertEqual(self.db.execute("SELECT COUNT(*) FROM entries").fetchone()[0], 2)

    def test_missing_markers_fail_closed(self):
        for repair in (main_source, provider_source, store_source):
            with self.subTest(repair=repair.__name__):
                with self.assertRaises(ValueError):
                    repair("not a supported donor")

    def test_duplicate_bulk_block_rejected(self):
        with self.assertRaises(ValueError):
            main_source("                        boolean bulkDone=false;" * 2)

    def test_actual_reference_source(self):
        java = Path(__file__).resolve().parents[2] / ".nenotv-tools/v0132-artifact/RA_IPTV_Android_v0.1/app/src/main/java/com/robertalt/raiptv"
        if not java.exists():
            self.skipTest("Local reference artifact is not available")
        main = main_source(repair_source((java / "MainActivity.java").read_text(encoding="utf-8")))
        provider = provider_source((java / "provider/XtreamProvider.java").read_text(encoding="utf-8"))
        store = store_source((java / "storage/SearchIndexStore.java").read_text(encoding="utf-8"))
        self.assertIn("importProvider.streamSection", main)
        self.assertIn("key.equals(profileKey())", main)
        self.assertNotIn("List<MediaEntry> bulk=provider.items", main)
        self.assertIn("batch.size()==80", provider)
        self.assertNotIn("JSONArray rows=arr(a,cat)", provider)
        self.assertIn("cache_write_failed", store)
        self.assertIn('db.delete("import_entries","started<?"', store)
        finish = store.split("public int finishSectionImport", 1)[1].split("public void abortSectionImport", 1)[0]
        self.assertLess(finish.index("db.beginTransaction()"), finish.index('db.delete("entries"'))
        self.assertLess(finish.rindex("checkCancelled()"), finish.index("db.setTransactionSuccessful()"))
        self.assertIn("finally {db.endTransaction();}", finish)
        for repair, result in ((main_source, main), (provider_source, provider), (store_source, store)):
            with self.assertRaises(ValueError):
                repair(result)


if __name__ == "__main__":
    unittest.main()
