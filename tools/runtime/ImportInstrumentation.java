package com.nenotv.player;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import com.nenotv.player.model.MediaEntry;
import com.nenotv.player.storage.SearchIndexStore;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;

public final class ImportInstrumentation extends Instrumentation {
    private static final String PROFILE = "nenotv-import-qa";

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static MediaEntry entry(String id, String type) {
        MediaEntry e = new MediaEntry();
        e.id = id; e.streamId = id; e.type = type; e.name = "QA " + id; e.group = "NL test";
        return e;
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try (SearchIndexStore store = new SearchIndexStore(getTargetContext())) {
            SQLiteDatabase db = store.getWritableDatabase();
            db.delete("entries", "profile=?", new String[]{PROFILE});
            db.delete("meta", "profile=?", new String[]{PROFILE});
            store.upsert(PROFILE, Collections.singletonList(entry("old", "live")));
            store.upsert(PROFILE, Collections.singletonList(entry("movie", "vod")));
            String session = store.beginSectionImport();
            for (int offset = 0; offset < 5000; offset += 80) {
                ArrayList<MediaEntry> batch = new ArrayList<>();
                for (int i = offset; i < Math.min(5000, offset + 80); i++) batch.add(entry("new" + i, "live"));
                store.importBatch(session, PROFILE, "live", batch);
                require(store.countSection(PROFILE, "live") == 1, "Uncommitted rows became visible");
            }
            require(store.finishSectionImport(session, PROFILE, "live") == 5000, "Snapshot count");
            require(store.countSection(PROFILE, "live") == 5000, "Published count");
            require(store.countSection(PROFILE, "vod") == 1, "Other section changed");
            session = store.beginSectionImport();
            store.importBatch(session, PROFILE, "live", Collections.singletonList(entry("cancelled", "live")));
            Thread.currentThread().interrupt();
            boolean cancelled = false;
            try { store.finishSectionImport(session, PROFILE, "live"); }
            catch (InterruptedIOException expected) { cancelled = true; }
            finally { Thread.interrupted(); }
            require(cancelled, "Cancelled snapshot committed");
            store.abortSectionImport(session);
            require(store.countSection(PROFILE, "live") == 5000, "Cancel erased old snapshot");
            session = store.beginSectionImport();
            store.importBatch(session, PROFILE, "live", Collections.singletonList(entry("failed", "live")));
            db.execSQL("CREATE TEMP TRIGGER qa_import_failure BEFORE INSERT ON meta WHEN NEW.profile='nenotv-import-qa' BEGIN SELECT RAISE(ABORT,'qa_failure'); END");
            boolean failed = false;
            try { store.finishSectionImport(session, PROFILE, "live"); }
            catch (Exception expected) { failed = true; }
            finally { db.execSQL("DROP TRIGGER qa_import_failure"); }
            require(failed, "Write failure was swallowed");
            require(store.countSection(PROFILE, "live") == 5000, "Rollback erased old snapshot");
            store.abortSectionImport(session);
            session = store.beginSectionImport();
            require(store.finishSectionImport(session, PROFILE, "live") == 0, "Empty snapshot count");
            require(store.isComplete(PROFILE, "live"), "Empty valid snapshot incomplete");
            require(store.countSection(PROFILE, "vod") == 1, "Empty import changed another section");
            Activity account = startActivitySync(new Intent(getTargetContext(), AccountActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            require(account != null && !account.isFinishing(), "Account did not open");
            runOnMainSync(account::finish);
            waitForIdleSync();
            db.delete("entries", "profile=?", new String[]{PROFILE});
            db.delete("meta", "profile=?", new String[]{PROFILE});
            result.putString("NENOTV_IMPORT_TESTS", "passed");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("NENOTV_IMPORT_TESTS", "failed: " + failure.getClass().getSimpleName());
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
