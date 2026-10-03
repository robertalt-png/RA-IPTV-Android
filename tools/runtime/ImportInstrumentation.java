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
    private Bundle args;

    @Override public void onCreate(Bundle args) {
        this.args = args == null ? new Bundle() : args;
        super.onCreate(args);
        start();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static MediaEntry entry(String id, String type) {
        MediaEntry e = new MediaEntry();
        e.id = id; e.streamId = id; e.type = type; e.name = "QA " + id; e.group = "NL test";
        return e;
    }

    private static ArrayList<MediaEntry> range(String prefix, int from, int to, String type) {
        ArrayList<MediaEntry> out = new ArrayList<>();
        for (int i=from;i<to;i++) out.add(entry(prefix+i,type));
        return out;
    }

    private void prepareResume(Bundle result) throws Exception {
        try (SearchIndexStore store = new SearchIndexStore(getTargetContext())) {
            SQLiteDatabase db=store.getWritableDatabase();
            db.delete("entries","profile=?",new String[]{PROFILE});
            db.delete("meta","profile=?",new String[]{PROFILE});
            store.clearImportProgress(PROFILE,"live");
            String session=store.beginSectionImport();
            try { db.delete("import_entries","profile=?",new String[]{PROFILE}); } catch(Exception ignored) {}
            store.importBatch(session,PROFILE,"live",range("resume",0,80,"live"));
            store.importBatch(session,PROFILE,"live",range("resume",80,160,"live"));
            store.checkpointImport(PROFILE,"live",session,"cat-2",160);
            SearchIndexStore.ImportProgress p=store.importProgress(PROFILE,"live");
            require(p!=null,"Resume checkpoint missing");
            require(session.equals(p.session),"Resume session mismatch");
            require("cat-2".equals(p.cursor),"Resume cursor mismatch");
            require(p.itemCount==160,"Resume item count mismatch");
            require(store.importCount(session,PROFILE,"live")==160,"Staged resume rows missing");
            result.putString("NENOTV_RESUME_PREPARE","passed");
            finish(Activity.RESULT_OK,result);
        }
    }

    private void verifyResume(Bundle result) throws Exception {
        try (SearchIndexStore store = new SearchIndexStore(getTargetContext())) {
            SearchIndexStore.ImportProgress p=store.importProgress(PROFILE,"live");
            require(p!=null,"Checkpoint did not survive process boundary");
            require("cat-2".equals(p.cursor),"Cursor did not survive process boundary");
            require(p.itemCount==160,"Item count did not survive process boundary");
            require(store.importCount(p.session,PROFILE,"live")==160,"Staged rows did not survive process boundary");
            String session=store.beginSectionImport(p.session);
            require(session.equals(p.session),"Resume did not reuse session");
            store.importBatch(session,PROFILE,"live",range("resume",160,200,"live"));
            store.checkpointImport(PROFILE,"live",session,"cat-3",200);
            require(store.importCount(session,PROFILE,"live")==200,"Resume did not append staging rows");
            require(store.finishSectionImport(session,PROFILE,"live")==200,"Resumed snapshot commit count");
            store.clearImportProgress(PROFILE,"live");
            require(store.importProgress(PROFILE,"live")==null,"Resume checkpoint not cleared");
            require(store.countSection(PROFILE,"live")==200,"Final resumed library count");
            result.putString("NENOTV_RESUME_VERIFY","passed");
            finish(Activity.RESULT_OK,result);
        }
    }

    private void normalSuite(Bundle result) throws Exception {
        try (SearchIndexStore store = new SearchIndexStore(getTargetContext())) {
            SQLiteDatabase db = store.getWritableDatabase();
            db.delete("entries", "profile=?", new String[]{PROFILE});
            db.delete("meta", "profile=?", new String[]{PROFILE});
            store.clearImportProgress(PROFILE,"live");
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
            require(store.countSection(PROFILE, "live") == 5000, "Rollback erased old library");
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
            store.clearImportProgress(PROFILE,"live");
            result.putString("NENOTV_IMPORT_TESTS", "passed");
            finish(Activity.RESULT_OK, result);
        }
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        String phase=args.getString("phase","");
        try {
            if ("prepare_resume".equals(phase)) prepareResume(result);
            else if ("verify_resume".equals(phase)) verifyResume(result);
            else normalSuite(result);
        } catch (Throwable failure) {
            String key="prepare_resume".equals(phase)?"NENOTV_RESUME_PREPARE":"verify_resume".equals(phase)?"NENOTV_RESUME_VERIFY":"NENOTV_IMPORT_TESTS";
            result.putString(key, "failed: " + failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage()));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
