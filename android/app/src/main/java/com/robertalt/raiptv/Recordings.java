package com.nenotv.player;

import android.app.*;
import android.content.*;
import android.os.Build;
import android.os.Environment;
import com.nenotv.player.core.RecordingPlan;
import com.nenotv.player.model.MediaEntry;
import com.nenotv.player.storage.RecordingStore;
import java.io.File;
import java.util.*;

/**
 * Recording (Pro): plans recordings with an exact alarm and hands them to {@link RecordingService}.
 * Files go to the app's own folder on the device or on a USB stick / SD card; no storage permission is needed.
 */
public final class Recordings {
    private Recordings() {}

    public static final String ACTION_ALARM = "com.nenotv.player.RECORD";
    public static final String EXTRA_ID = "sunnyiptv_recording";
    static final String PREFS = "sunnyiptv_recording_settings";

    /** Outcome of planning a recording. */
    public static final class Planned {
        public RecordingStore.Recording recording;
        /** Empty when fine, else: ended, demo, exists. */
        public String refused = "";
        /** Other recordings in the same time window: the provider may allow only one connection. */
        public int overlapping;
    }

    public static Planned plan(Context c, MediaEntry channel, String title, long start, long end) {
        Planned p = new Planned();
        long now = System.currentTimeMillis() / 1000L;
        if (channel == null || DemoSource.isEntry(channel)) { p.refused = "demo"; return p; }
        if (end <= start || !RecordingPlan.stillRecordable(end, now)) { p.refused = "ended"; return p; }
        if (RecordingStore.has(c, channel, start)) { p.refused = "exists"; return p; }
        for (RecordingStore.Recording o : RecordingStore.all(c))
            if (!o.finished() && RecordingPlan.overlaps(start, end, o.start, o.end)) p.overlapping++;
        MediaEntry copy = channel;
        p.recording = RecordingStore.add(c, copy, title, start, end);
        if (RecordingPlan.startAt(start) <= now + 5) startService(c, p.recording.id);
        else schedule(c, p.recording);
        return p;
    }

    /** Records the channel from now on for {@code minutes}. */
    public static Planned recordNow(Context c, MediaEntry channel, String title, int minutes) {
        long now = System.currentTimeMillis() / 1000L;
        long m = Math.max(1, Math.min(RecordingPlan.MAX_DURATION_S / 60, minutes));
        // The padding is added by RecordingPlan; subtract it here so a manual recording starts now and lasts {@code minutes}.
        return plan(c, channel, title, now + RecordingPlan.PAD_BEFORE_S, now + m * 60 - RecordingPlan.PAD_AFTER_S);
    }

    static PendingIntent alarm(Context c, String id) {
        Intent i = new Intent(c, RecordingReceiver.class).setAction(ACTION_ALARM).putExtra(EXTRA_ID, id).setData(android.net.Uri.parse("sunnyiptv-recording:" + id));
        return PendingIntent.getBroadcast(c, id.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static boolean exactAlarmsAllowed(Context c) {
        try { return Build.VERSION.SDK_INT < 31 || ((AlarmManager) c.getSystemService(Context.ALARM_SERVICE)).canScheduleExactAlarms(); }
        catch (Exception e) { return false; }
    }

    static void schedule(Context c, RecordingStore.Recording r) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            long at = Math.max(System.currentTimeMillis() + 2000, RecordingPlan.startAt(r.start) * 1000L);
            PendingIntent pi = alarm(c, r.id);
            if (exactAlarmsAllowed(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (Exception ignored) {}
    }

    static void cancelAlarm(Context c, String id) {
        try { ((AlarmManager) c.getSystemService(Context.ALARM_SERVICE)).cancel(alarm(c, id)); } catch (Exception ignored) {}
    }

    /** After a reboot, an app update or an app start: Android does not keep alarms across reboots. */
    public static void rescheduleAll(Context c) {
        long now = System.currentTimeMillis() / 1000L;
        for (RecordingStore.Recording r : RecordingStore.all(c)) {
            if (RecordingStore.RECORDING.equals(r.state) && !RecordingService.isRunning(r.id)) {
                // The app or device stopped during a recording: continue if the window is still open, else keep what was recorded.
                if (RecordingPlan.stillRecordable(r.end, now)) { r.state = RecordingStore.SCHEDULED; RecordingStore.save(c, r); schedule(c, r); }
                else { r.state = r.bytes > 0 ? RecordingStore.PARTIAL : RecordingStore.FAILED; if (r.error.isEmpty()) r.error = "interrupted"; RecordingStore.save(c, r); }
            } else if (RecordingStore.SCHEDULED.equals(r.state)) {
                if (RecordingPlan.stillRecordable(r.end, now)) schedule(c, r);
                else { r.state = RecordingStore.FAILED; r.error = "missed"; RecordingStore.save(c, r); }
            }
        }
    }

    public static void startService(Context c, String id) {
        Intent i = new Intent(c, RecordingService.class).setAction(RecordingService.ACTION_START).putExtra(EXTRA_ID, id);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (RuntimeException blocked) {
            // Android refused to start a background recording (no exact-alarm permission): ask the viewer to tap.
            RecordingStore.Recording r = RecordingStore.get(c, id);
            if (r != null) RecordingService.notifyTapToStart(c, r);
        }
    }

    /** Running recordings for this channel only, including recordings started outside the player. */
    public static List<String> runningForChannel(Context c, MediaEntry channel) {
        List<String> ids = new ArrayList<>();
        if (channel == null) return ids;
        for (RecordingStore.Recording r : RecordingStore.all(c))
            if (RecordingStore.RECORDING.equals(r.state) && r.channel != null
                    && channel.uniqueKey().equals(r.channel.uniqueKey())) ids.add(r.id);
        return ids;
    }

    /** Stops a running recording or cancels a planned one; finished recordings stay. */
    public static void stop(Context c, String id) {
        RecordingStore.Recording r = RecordingStore.get(c, id);
        if (r == null) return;
        if (RecordingStore.SCHEDULED.equals(r.state)) { cancelAlarm(c, id); RecordingStore.remove(c, id); return; }
        if (RecordingStore.RECORDING.equals(r.state) && !RecordingService.isRunning(id)) {
            // Left over from a stopped app: nothing is running, keep what was recorded.
            File f = r.file.isEmpty() ? null : new File(r.file);
            r.bytes = f != null && f.isFile() ? f.length() : 0;
            r.state = r.bytes > 0 ? RecordingStore.PARTIAL : RecordingStore.FAILED; r.error = "cancelled";
            RecordingStore.save(c, r);
            return;
        }
        if (RecordingStore.RECORDING.equals(r.state)) {
            try { c.startService(new Intent(c, RecordingService.class).setAction(RecordingService.ACTION_STOP).putExtra(EXTRA_ID, id)); } catch (RuntimeException ignored) {}
        }
    }

    /** Deletes a recording and its file. */
    public static void delete(Context c, String id) {
        RecordingStore.Recording r = RecordingStore.get(c, id);
        if (r == null) return;
        if (RecordingStore.RECORDING.equals(r.state) && RecordingService.isRunning(id)) { stop(c, id); return; } // deleted from the list once it has stopped
        cancelAlarm(c, id);
        if (!r.file.isEmpty() && insideRecordingFolders(c, new File(r.file))) new File(r.file).delete();
        RecordingStore.remove(c, id);
    }

    // ---- storage ----

    /** Recording folders on every available volume: [0] is the device, later ones are USB sticks or SD cards. */
    public static List<File> volumes(Context c) {
        List<File> out = new ArrayList<>();
        File[] dirs = c.getExternalFilesDirs(Environment.DIRECTORY_MOVIES);
        if (dirs != null) for (File d : dirs) if (d != null) out.add(new File(d, "Recordings"));
        if (out.isEmpty()) out.add(new File(c.getFilesDir(), "Recordings"));
        return out;
    }

    public static int volumeIndex(Context c) {
        int i = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("volume", 0);
        return i >= 0 && i < volumes(c).size() ? i : 0;
    }

    public static void setVolumeIndex(Context c, int i) { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("volume", i).apply(); }

    public static File folder(Context c) {
        File f = volumes(c).get(volumeIndex(c));
        if (!f.exists()) f.mkdirs();
        return f;
    }

    public static boolean insideRecordingFolders(Context c, File f) {
        try {
            String path = f.getCanonicalPath();
            for (File v : volumes(c)) if (path.startsWith(v.getCanonicalPath() + File.separator)) return true;
        } catch (Exception ignored) {}
        return false;
    }

    /** A finished recording as a playable item; null when the file is missing. */
    public static MediaEntry playable(Context c, RecordingStore.Recording r) {
        if (r == null || r.file.isEmpty()) return null;
        File f = new File(r.file);
        if (!f.isFile() || !insideRecordingFolders(c, f)) return null;
        MediaEntry e = new MediaEntry();
        e.type = "recording"; e.id = r.id; e.name = r.title.isEmpty() ? r.channelName : r.title;
        e.group = r.channel == null ? "" : r.channel.group; e.categoryId = r.channel == null ? "" : r.channel.categoryId;
        e.logo = r.channel == null ? "" : r.channel.logo;
        e.url = android.net.Uri.fromFile(f).toString(); e.candidates.add(e.url);
        return e;
    }

    /** True for a recording file of this app, which plays without a source connection. */
    public static boolean isLocalRecording(Context c, MediaEntry e) {
        if (e == null || !"recording".equals(e.type) || e.url == null || !e.url.startsWith("file:")) return false;
        try { File f = new File(android.net.Uri.parse(e.url).getPath()); return f.isFile() && insideRecordingFolders(c, f); }
        catch (Exception ex) { return false; }
    }
}
