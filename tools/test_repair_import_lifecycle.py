import unittest
from pathlib import Path
from repair_responsiveness import repair_source as responsiveness
from repair_streaming_import import main_source
from repair_import_lifecycle import repair_source


class ImportLifecycleTests(unittest.TestCase):
    def test_missing_source_rejected(self):
        with self.assertRaises(ValueError):
            repair_source("unsupported donor")

    def test_actual_reference(self):
        path = Path(__file__).resolve().parents[2] / ".nenotv-tools/v0132-artifact/RA_IPTV_Android_v0.1/app/src/main/java/com/robertalt/raiptv/MainActivity.java"
        if not path.exists():
            self.skipTest("Local reference artifact unavailable")
        result = repair_source(main_source(responsiveness(path.read_text(encoding="utf-8"))))
        ui_pause = result.split("void pauseBackgroundIndexForUi(){", 1)[1].split("void scheduleBackgroundIndex", 1)[0]
        playback_pause = result.split("void pauseIndexForPlayback(){", 1)[1].split("void resumeIndexSoon", 1)[0]
        self.assertNotIn("cancel(true)", ui_pause + playback_pause)
        self.assertIn("playbackActive=true", playback_pause)
        self.assertIn("while(activityPaused&&!playbackActive", result)
        self.assertNotIn("epgStore=new EpgStore(this);migrate", result)
        self.assertNotIn('try{searchIndex.clearAll();}catch(Exception ignored){}refreshSearchIndex(true);', result)
        worker = result.split("void refreshSearchIndex(boolean force){", 1)[1].split("void waitWhilePaused", 1)[0]
        self.assertIn("final Provider indexProvider=provider", worker)
        self.assertNotIn("searchIndex.clearAll()", worker)
        self.assertIn("cacheCursorKey(key,type)", worker)
        self.assertIn("finishSectionImport(importSession,key,type)", worker)
        self.assertNotIn("futures.get(offset).get()", worker)
        foreground = result.split("void loadAllIncremental", 1)[1].split("void loadShowcase", 1)[0]
        self.assertNotIn("aggregate", foreground)
        self.assertNotIn("markSection", foreground)
        self.assertIn("loadCachedSection", foreground)
        self.assertIn("streamCategory", foreground)
        with self.assertRaises(ValueError):
            repair_source(result)
