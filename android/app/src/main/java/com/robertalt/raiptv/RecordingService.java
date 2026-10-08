package com.nenotv.player;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import com.nenotv.player.core.RecordingEngine;
import com.nenotv.player.core.RecordingPlan;
import com.nenotv.player.model.MediaEntry;
import com.nenotv.player.storage.EntitlementStore;
import com.nenotv.player.storage.FamilyStore;
import com.nenotv.player.storage.RecordingStore;
import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Recording (Pro): runs recordings in the background with the required ongoing notification.
 * Each recording has its own thread and {@link RecordingEngine}; the service stops when none are left.
 */
public final class RecordingService extends Service {
    public static final String ACTION_START = "com.nenotv.player.RECORD_START", ACTION_STOP = "com.nenotv.player.RECORD_STOP", ACTION_STOP_ALL = "com.nenotv.player.RECORD_STOP_ALL";
    static final String CHANNEL = "recordings", CHANNEL_DONE = "recordings_done";
    static final int ONGOING_ID = 0x5EC0;

    private static final Map<String, RecordingEngine> RUNNING = new ConcurrentHashMap<>();
    private static final Map<String, Long> BYTES = new ConcurrentHashMap<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wake;
    private android.net.wifi.WifiManager.WifiLock wifi;
    private volatile boolean timedOut;

    public static boolean isRunning(String id) { return id != null && RUNNING.containsKey(id); }
    public static long bytes(String id) { Long b = BYTES.get(id); return b == null ? 0 : b; }
    public static int runningCount() { return RUNNING.size(); }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : String.valueOf(intent.getAction());
        String id = intent == null ? null : intent.getStringExtra(Recordings.EXTRA_ID);
        if (ACTION_STOP_ALL.equals(action)) { for (RecordingEngine e : RUNNING.values()) e.cancel(); stopIfIdle(); return START_NOT_STICKY; }
        if (ACTION_STOP.equals(action)) { RecordingEngine e = id == null ? null : RUNNING.get(id); if (e != null) e.cancel(); stopIfIdle(); return START_NOT_STICKY; }
        if (!goForeground()) {
            RecordingStore.Recording r = id == null ? null : RecordingStore.get(this, id);
            if (r != null && !r.finished()) finish(r, RecordingStore.FAILED, "unavailable");
            stopIfIdle();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action) && id != null && !RUNNING.containsKey(id)) begin(id);
        stopIfIdle();
        return START_NOT_STICKY;
    }

    private void begin(String id) {
        RecordingStore.Recording r = RecordingStore.get(this, id);
        if (r == null || r.finished()) return;
        long now = System.currentTimeMillis() / 1000L;
        if (!RecordingPlan.stillRecordable(r.end, now)) { finish(r, RecordingStore.FAILED, "missed"); return; }
        if (!new EntitlementStore(this).isPro()) { finish(r, RecordingStore.FAILED, "pro_required"); return; }
        if (!FamilyStore.allowed(this, r.channel)) { finish(r, RecordingStore.FAILED, "family_filter"); return; }
        try { com.nenotv.player.provider.PlaybackSourceRoute.resolve(this, r.channel, true); }
        catch (Exception unavailable) { finish(r, RecordingStore.FAILED, "source_unavailable"); return; }
        File folder = Recordings.folder(this);
        File out = r.file.isEmpty() ? unique(folder, RecordingPlan.fileName(r.channelName, r.title, r.start, null)) : new File(r.file);
        if (!Recordings.insideRecordingFolders(this, out)) out = unique(folder, RecordingPlan.fileName(r.channelName, r.title, r.start, null));
        r.file = out.getAbsolutePath(); r.state = RecordingStore.RECORDING; r.error = "";
        RecordingStore.save(this, r);
        final File target = out;
        RecordingEngine engine = new RecordingEngine(null, "Mozilla/5.0 (Linux; Android) SunnyIPTV/" + BuildConfig.VERSION_NAME, 15000, 30000);
        RUNNING.put(id, engine);
        BYTES.put(id, target.length());
        holdLocks(true);
        updateNotification();
        Thread t = new Thread(() -> run(id, engine, target), "recording-" + id);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    private void run(String id, RecordingEngine engine, File out) {
        RecordingStore.Recording r = RecordingStore.get(this, id);
        RecordingEngine.Result result = null;
        try {
            if (r == null) return;
            MediaEntry ch = r.channel;
            List<String> urls = new ArrayList<>(ch.candidates);
            if (urls.isEmpty() && ch.url != null && !ch.url.isEmpty()) urls.add(ch.url);
            long stopAtMs = RecordingPlan.stopAt(r.end) * 1000L;
            final long[] lastSave = {0};
            final long already = out.length();
            result = engine.record(urls, out, stopAtMs, new RecordingEngine.Listener() {
                @Override public void progress(long bytes) {
                    BYTES.put(id, already + bytes);
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastSave[0] > 15000) { lastSave[0] = now; RecordingStore.Recording cur = RecordingStore.get(RecordingService.this, id); if (cur != null) { cur.bytes = already + bytes; RecordingStore.save(RecordingService.this, cur); } ui.post(RecordingService.this::updateNotification); }
                }
                @Override public boolean spaceLeft() { return out.getParentFile() == null || out.getParentFile().getUsableSpace() > RecordingPlan.MIN_FREE_BYTES; }
            });
        } catch (Throwable unexpected) {
            result = null;
        } finally {
            // Save the final state before leaving RUNNING, so anyone who sees "not running" also sees the result.
            RecordingStore.Recording cur = RecordingStore.get(this, id);
            if (cur != null) {
                cur.bytes = out.length();
                String error = result == null ? "unavailable" : result.error;
                if (timedOut && (error.isEmpty() || "cancelled".equals(error))) error = "time_limit";
                String state = result != null && result.reachedEnd ? RecordingStore.DONE : cur.bytes > 0 ? RecordingStore.PARTIAL : RecordingStore.FAILED;
                if (RecordingStore.DONE.equals(state) && "cancelled".equals(error)) error = "";
                if (cur.bytes == 0) out.delete();
                finish(cur, state, error);
            }
            RUNNING.remove(id);
            BYTES.remove(id);
            ui.post(() -> { updateNotification(); stopIfIdle(); });
        }
    }

    private void finish(RecordingStore.Recording r, String state, String error) {
        r.state = state; r.error = error == null ? "" : error;
        RecordingStore.save(this, r);
        notifyFinished(this, r);
    }

    private static File unique(File folder, String name) {
        File f = new File(folder, name);
        if (!f.exists()) return f;
        String base = name.endsWith(".ts") ? name.substring(0, name.length() - 3) : name;
        for (int i = 2; i < 100; i++) { File g = new File(folder, base + " (" + i + ").ts"); if (!g.exists()) return g; }
        return new File(folder, base + " " + System.currentTimeMillis() + ".ts");
    }

    private void stopIfIdle() {
        if (!RUNNING.isEmpty()) return;
        holdLocks(false);
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
        stopSelf();
    }

    /** Android 15 limits background data work to 6 hours a day: stop cleanly and keep what was recorded. */
    @Override public void onTimeout(int startId, int fgsType) {
        timedOut = true;
        // Android requires the service to stop before network workers finish unwinding.
        stopSelf();
        for (RecordingEngine e : RUNNING.values()) e.cancel();
    }

    @Override public void onDestroy() {
        for (RecordingEngine e : RUNNING.values()) e.cancel();
        holdLocks(false);
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    private void holdLocks(boolean on) {
        try {
            if (on) {
                if (wake == null) { wake = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SunnyIPTV:recording"); wake.setReferenceCounted(false); }
                if (!wake.isHeld()) wake.acquire(RecordingPlan.MAX_DURATION_S * 1000L + 600_000L);
                if (wifi == null) { android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE); if (wm != null) { wifi = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SunnyIPTV:recording"); wifi.setReferenceCounted(false); } }
                if (wifi != null && !wifi.isHeld()) wifi.acquire();
            } else {
                if (wake != null && wake.isHeld()) wake.release();
                if (wifi != null && wifi.isHeld()) wifi.release();
            }
        } catch (Exception ignored) {}
    }

    // ---- notifications ----

    static void channels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, UiText.t(c, "recordings"), NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_DONE, UiText.t(c, "recordings_done"), NotificationManager.IMPORTANCE_DEFAULT));
    }

    private Notification ongoing() {
        channels(this);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        int n = RUNNING.size();
        String text;
        if (n == 1) {
            String id = RUNNING.keySet().iterator().next();
            RecordingStore.Recording r = RecordingStore.get(this, id);
            text = (r == null ? "" : (r.title.isEmpty() ? r.channelName : r.title)) + " · " + size(bytes(id));
        } else text = String.format(UiText.t(this, "recordings_running"), String.valueOf(Math.max(n, 1)));
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, RecordingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, RecordingService.class).setAction(ACTION_STOP_ALL), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        b.setSmallIcon(android.R.drawable.presence_video_busy).setContentTitle(UiText.t(this, "recording_now")).setContentText(text)
                .setOngoing(true).setContentIntent(open).setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, UiText.t(this, "recording_stop"), stop).build());
        return b.build();
    }

    private boolean goForeground() {
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ONGOING_ID, ongoing(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(ONGOING_ID, ongoing());
            return true;
        } catch (RuntimeException e) { return false; }
    }

    private void updateNotification() {
        if (RUNNING.isEmpty()) return;
        try { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(ONGOING_ID, ongoing()); } catch (Exception ignored) {}
    }

    static String size(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f GB", bytes / 1073741824.0);
        return String.format(Locale.ROOT, "%d MB", bytes / 1048576L);
    }

    static void notifyFinished(Context c, RecordingStore.Recording r) {
        try {
            channels(c);
            NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL_DONE) : new Notification.Builder(c);
            String key = RecordingStore.DONE.equals(r.state) ? "recording_done" : RecordingStore.PARTIAL.equals(r.state) ? "recording_partial" : "recording_failed";
            PendingIntent open = PendingIntent.getActivity(c, r.id.hashCode(), new Intent(c, RecordingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.setSmallIcon(android.R.drawable.presence_video_online).setContentTitle(UiText.t(c, key)).setContentText(r.title.isEmpty() ? r.channelName : r.title)
                    .setAutoCancel(true).setContentIntent(open);
            nm.notify(r.id.hashCode(), b.build());
        } catch (Exception ignored) {}
    }

    /** When Android blocks a background start, a tap on this notification starts the recording from the app. */
    static void notifyTapToStart(Context c, RecordingStore.Recording r) {
        try {
            channels(c);
            NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL_DONE) : new Notification.Builder(c);
            Intent i = new Intent(c, RecordingsActivity.class).putExtra(Recordings.EXTRA_ID, r.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent open = PendingIntent.getActivity(c, r.id.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.setSmallIcon(android.R.drawable.presence_video_busy).setContentTitle(UiText.t(c, "recording_tap_to_start")).setContentText(r.title.isEmpty() ? r.channelName : r.title)
                    .setAutoCancel(true).setContentIntent(open).setCategory(Notification.CATEGORY_REMINDER);
            nm.notify(r.id.hashCode(), b.build());
        } catch (Exception ignored) {}
    }
}
