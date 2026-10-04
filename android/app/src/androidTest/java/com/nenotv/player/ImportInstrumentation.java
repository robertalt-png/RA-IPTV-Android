package com.nenotv.player;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import com.nenotv.player.model.MediaEntry;
import com.nenotv.player.model.Category;
import com.nenotv.player.storage.SearchIndexStore;
import com.nenotv.player.storage.SettingsStore;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;

public class ImportInstrumentation extends Instrumentation {
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
            ArrayList<MediaEntry> resumeBatch1=range("resume",0,80,"live");
            ArrayList<MediaEntry> resumeBatch2=range("resume",80,160,"live");
            store.importBatch(session,PROFILE,"live",resumeBatch1);store.upsert(PROFILE,resumeBatch1);
            store.importBatch(session,PROFILE,"live",resumeBatch2);store.upsert(PROFILE,resumeBatch2);
            SettingsStore.prefs(getTargetContext()).edit().putInt("first_sync_done_count_"+PROFILE,2).putInt("first_sync_total_count_"+PROFILE,5).putInt("first_sync_titles_"+PROFILE,160).commit();
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
            require(store.count(PROFILE)>=160,"Visible partial library returned to zero after restart");
            android.content.SharedPreferences progressPrefs=SettingsStore.prefs(getTargetContext());
            require(progressPrefs.getInt("first_sync_titles_"+PROFILE,0)>=160,"Visible title counter returned to zero after restart");
            require(progressPrefs.getInt("first_sync_done_count_"+PROFILE,0)==2,"Visible category progress was lost after restart");
            require(progressPrefs.getInt("first_sync_total_count_"+PROFILE,0)==5,"Visible category total was lost after restart");
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
            android.content.Context demoContext=getTargetContext();
            android.content.SharedPreferences demoPrefs=SettingsStore.prefs(demoContext);
            demoPrefs.edit().remove("demo_consumed").remove("demo_started_at").remove("demo_expires_at").commit();
            long demoNow=1700000000000L;
            long demoExpiry=DemoPolicy.startOrKeep(demoContext,demoNow);
            require(demoExpiry==demoNow+DemoPolicy.DURATION_MS,"Demo duration incorrect");
            require(DemoPolicy.startOrKeep(demoContext,demoNow+1000L)==demoExpiry,"Demo restart extended expiry");
            require(!DemoPolicy.expiredAt(demoContext,demoExpiry-1L),"Demo expired too early");
            require(DemoPolicy.expiredAt(demoContext,demoExpiry),"Demo expiry not enforced");
            require(DemoPolicy.startOrKeep(demoContext,demoExpiry+1L)<0L,"Expired demo restarted");
            CategoryCompletionWindow completionWindow=new CategoryCompletionWindow(4,0);
            require(completionWindow.markDone(1)==-1,"Out-of-order category advanced cursor");
            require(completionWindow.markDone(0)==1,"Contiguous cursor did not advance");
            require(completionWindow.markDone(3)==1,"Gap advanced cursor");
            require(completionWindow.markDone(2)==3,"Cursor did not close gap");

            String concurrentSession=store.beginSectionImport();
            java.util.concurrent.ExecutorService concurrentPool=java.util.concurrent.Executors.newFixedThreadPool(2);
            java.util.concurrent.Future<?> concurrentA=concurrentPool.submit(()->{
                try{store.importBatch(concurrentSession,PROFILE,"live",range("parallelA",0,80,"live"));}
                catch(Exception e){throw new RuntimeException(e);}
            });
            java.util.concurrent.Future<?> concurrentB=concurrentPool.submit(()->{
                try{store.importBatch(concurrentSession,PROFILE,"live",range("parallelB",0,80,"live"));}
                catch(Exception e){throw new RuntimeException(e);}
            });
            concurrentA.get();concurrentB.get();concurrentPool.shutdownNow();
            require(store.importCount(concurrentSession,PROFILE,"live")==160,"Concurrent category import lost rows");
            store.abortSectionImport(concurrentSession);
            ArrayList<Category> qaCategories = new ArrayList<>();
            qaCategories.add(new Category("qa-cat","QA category","live"));
            store.replaceCategories(PROFILE,"live",qaCategories);
            require(store.cachedCategories(PROFILE,"live").size()==1,"Category cache missing");
            require(store.categoriesFresh(PROFILE,"live",30L*60L*1000L),"Category cache not fresh");
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

    private void demoSuite(Bundle result) throws Exception {
        android.content.Context c=getTargetContext();
        com.nenotv.player.storage.SecureProfileStore profiles=new com.nenotv.player.storage.SecureProfileStore(c);
        profiles.clear();
        SettingsStore.prefs(c).edit().remove("demo_consumed").remove("demo_started_at").remove("demo_expires_at").putString("language","nl").commit();
        require(DemoSource.URL.equals(BuildConfig.NENOTV_DEMO_M3U_URL),"Build uses an unreviewed demo source");
        com.nenotv.player.model.Profile p=new com.nenotv.player.model.Profile();
        p.type=com.nenotv.player.model.Profile.Type.M3U;p.name="Existing demo";p.m3uUrl=DemoSource.URL;
        profiles.save(p);DemoPolicy.startOrKeep(c,System.currentTimeMillis());
        require(DemoPolicy.isDemo(p),"Saved profile is not demo");
        long expiry=DemoPolicy.expiresAt(c);
        require(expiry>System.currentTimeMillis(),"Demo did not start");
        require(DemoPolicy.startOrKeep(c,System.currentTimeMillis())==expiry,"Repeated tap extended demo");
        com.nenotv.player.provider.M3uProvider provider=new com.nenotv.player.provider.M3uProvider(p,"nl");
        provider.authenticate();
        java.util.List<MediaEntry> entries=provider.items("live","all");
        require(entries.size()==DemoSource.LIVE_COUNT,"Live count");
        java.util.List<MediaEntry> films=provider.items("vod","all");require(films.size()==DemoSource.FILM_COUNT,"Film count");
        entries.addAll(films);
        java.util.HashSet<String> ids=new java.util.HashSet<>();
        for(MediaEntry e:entries){require(ids.add(e.tvgId),"Duplicate catalogue item");require(e.plot.contains("https://"),"Missing credits");require(provider.items(e.type,e.group).contains(e),"Category lost entry");}
        for(MediaEntry item:entries){
            final androidx.media3.exoplayer.ExoPlayer[] player={null};
            final android.graphics.SurfaceTexture[] texture={null};
            final android.view.Surface[] surface={null};
            final java.util.concurrent.atomic.AtomicBoolean frame=new java.util.concurrent.atomic.AtomicBoolean();
            final java.util.concurrent.atomic.AtomicReference<String> error=new java.util.concurrent.atomic.AtomicReference<>();
            try{
                runOnMainSync(()->{
                    texture[0]=new android.graphics.SurfaceTexture(0);texture[0].setDefaultBufferSize(1280,720);
                    surface[0]=new android.view.Surface(texture[0]);
                    player[0]=new androidx.media3.exoplayer.ExoPlayer.Builder(c).setMediaSourceFactory(DemoSource.mediaSourceFactory(c,item)).build();
                    player[0].setVideoSurface(surface[0]);
                    player[0].addListener(new androidx.media3.common.Player.Listener(){
                        @Override public void onRenderedFirstFrame(){frame.set(true);}
                        @Override public void onPlayerError(androidx.media3.common.PlaybackException e){error.set(e.getErrorCodeName()+": "+String.valueOf(e.getCause()));}
                    });
                    player[0].setMediaItem(androidx.media3.common.MediaItem.fromUri(item.url));player[0].prepare();player[0].play();
                });
                long end=android.os.SystemClock.elapsedRealtime()+240000;
                final java.util.concurrent.atomic.AtomicBoolean played=new java.util.concurrent.atomic.AtomicBoolean();
                while(!played.get()&&error.get()==null&&android.os.SystemClock.elapsedRealtime()<end){
                    runOnMainSync(()->played.set(frame.get()&&player[0].getCurrentPosition()>=1500&&player[0].getAudioFormat()!=null));
                    Thread.sleep(100);
                }
                require(error.get()==null,"Demo playback error: "+item.tvgId+": "+error.get());
                require(played.get(),"Demo did not render video and decode audio: "+item.tvgId);
                result.putString("NENOTV_DEMO_STREAM_"+item.tvgId,"passed");
            }finally{runOnMainSync(()->{if(player[0]!=null)player[0].release();if(surface[0]!=null)surface[0].release();if(texture[0]!=null)texture[0].release();});}
            Thread.sleep(5000);
        }
        p.name="Renamed demo";profiles.save(p);
        SettingsStore.prefs(c).edit().putLong("demo_expires_at",System.currentTimeMillis()-1).commit();
        require(DemoPolicy.blockPlayback(c),"Renaming bypassed demo expiry");
        require(DemoPolicy.startOrKeep(c,System.currentTimeMillis())<0,"Expired demo restarted");
        p.m3uUrl="https://example.com/own.m3u";profiles.save(p);
        require(!DemoPolicy.blockPlayback(c),"Demo expiry blocked own source");
        for(String language:new String[]{"nl","en","de"}){
            SettingsStore.setPrimaryLanguage(c,language);
            ProfileActivity expired=(ProfileActivity)startActivitySync(new Intent(c,ProfileActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            runOnMainSync(()->{require(!expired.demoRadio.isEnabled(),"Expired demo enabled in "+language);expired.finish();});
        }
        // Persist a valid trial for the next instrumentation process.
        SettingsStore.prefs(c).edit().remove("demo_consumed").remove("demo_started_at").remove("demo_expires_at").commit();
        long persistedExpiry=DemoPolicy.startOrKeep(c,System.currentTimeMillis());
        SettingsStore.prefs(c).edit().putLong("qa_demo_expected_expiry",persistedExpiry).commit();
        p.name="NenoTV Demo";p.m3uUrl=DemoSource.URL;profiles.save(p);
        result.putString("NENOTV_DEMO_TESTS","passed");
        finish(Activity.RESULT_OK,result);
    }

    private void verifyDemoResume(Bundle result) throws Exception {
        android.content.Context c=getTargetContext();
        long expected=SettingsStore.prefs(c).getLong("qa_demo_expected_expiry",0);
        require(expected>System.currentTimeMillis(),"Trial expiry missing after restart");
        require(DemoPolicy.expiresAt(c)==expected,"Trial expiry changed after restart");
        require(DemoPolicy.startOrKeep(c,System.currentTimeMillis())==expected,"Restart extended trial");
        com.nenotv.player.storage.SecureProfileStore store=new com.nenotv.player.storage.SecureProfileStore(c);
        require(store.exists(),"Demo profile file missing after restart");
        com.nenotv.player.model.Profile restored=store.load();
        require(DemoPolicy.isDemo(restored),"Demo identity lost after restart: type="+restored.type+", name="+restored.name+", demoUri="+DemoSource.URL.equals(restored.m3uUrl));
        com.nenotv.player.provider.M3uProvider provider=new com.nenotv.player.provider.M3uProvider(store.load());
        provider.authenticate();require(provider.items("live","all").size()==DemoSource.LIVE_COUNT&&provider.items("vod","all").size()==DemoSource.FILM_COUNT,"Demo list lost after restart");
        store.clear();SettingsStore.prefs(c).edit().remove("qa_demo_expected_expiry").commit();
        result.putString("NENOTV_DEMO_RESUME","passed");finish(Activity.RESULT_OK,result);
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        String phase=args.getString("phase","");
        try {
            if ("demo".equals(phase)) demoSuite(result);
            else if ("demo_resume".equals(phase)) verifyDemoResume(result);
            else if ("prepare_resume".equals(phase)) prepareResume(result);
            else if ("verify_resume".equals(phase)) verifyResume(result);
            else normalSuite(result);
        } catch (Throwable failure) {
            String key="demo_resume".equals(phase)?"NENOTV_DEMO_RESUME":"demo".equals(phase)?"NENOTV_DEMO_TESTS":"prepare_resume".equals(phase)?"NENOTV_RESUME_PREPARE":"verify_resume".equals(phase)?"NENOTV_RESUME_VERIFY":"NENOTV_IMPORT_TESTS";
            result.putString(key, "failed: " + failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage()));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
