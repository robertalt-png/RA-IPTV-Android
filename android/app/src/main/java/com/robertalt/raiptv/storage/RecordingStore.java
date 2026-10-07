package com.nenotv.player.storage;

import android.content.Context;
import android.content.SharedPreferences;
import com.nenotv.player.model.MediaEntry;
import java.util.*;
import org.json.JSONObject;

/**
 * Recording: the list of planned, running and finished recordings on this device.
 * The channel (which holds the stream address) is encrypted with the Android keystore, like reminders.
 */
public final class RecordingStore {
    private RecordingStore() {}

    public static final String SCHEDULED = "scheduled", RECORDING = "recording", DONE = "done", PARTIAL = "partial", FAILED = "failed";

    public static final class Recording {
        public String id = "", title = "", state = SCHEDULED, file = "", error = "", channelName = "";
        /** Programme start and end (epoch seconds); the padded window is in RecordingPlan. */
        public long start, end, bytes, createdAt;
        public MediaEntry channel;
        public boolean finished() { return DONE.equals(state) || PARTIAL.equals(state) || FAILED.equals(state); }
    }

    static SharedPreferences prefs(Context c) { return c.getApplicationContext().getSharedPreferences("sunnyiptv_recordings", Context.MODE_PRIVATE); }

    public static String id(MediaEntry channel, long start) { return "rec" + ReminderStore.id(channel, start).substring(1); }

    public static synchronized Recording add(Context c, MediaEntry channel, String title, long start, long end) {
        Recording r = new Recording();
        r.id = id(channel, start); r.channel = channel; r.channelName = channel.name == null ? "" : channel.name;
        r.title = title == null ? "" : title; r.start = start; r.end = end; r.createdAt = System.currentTimeMillis() / 1000L;
        save(c, r);
        return r;
    }

    public static synchronized void save(Context c, Recording r) {
        try {
            JSONObject x = new JSONObject();
            x.put("title", r.title); x.put("state", r.state); x.put("file", r.file); x.put("error", r.error); x.put("channelName", r.channelName);
            x.put("start", r.start); x.put("end", r.end); x.put("bytes", r.bytes); x.put("createdAt", r.createdAt);
            x.put("channel", new CryptoBox().encrypt(ReminderStore.encode(r.channel)));
            prefs(c).edit().putString(r.id, x.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** Fast check for the guide (no decryption): a recording exists for this programme and has not failed. */
    public static boolean has(Context c, MediaEntry channel, long start) {
        String raw = prefs(c).getString(id(channel, start), "");
        if (raw.isEmpty()) return false;
        try { return !FAILED.equals(new JSONObject(raw).optString("state")); } catch (Exception e) { return false; }
    }

    public static synchronized void remove(Context c, String id) { prefs(c).edit().remove(id).apply(); }

    public static synchronized Recording get(Context c, String id) {
        String raw = prefs(c).getString(id == null ? "" : id, "");
        if (raw.isEmpty()) return null;
        try {
            JSONObject x = new JSONObject(raw);
            Recording r = new Recording();
            r.id = id; r.title = x.optString("title"); r.state = x.optString("state", SCHEDULED); r.file = x.optString("file"); r.error = x.optString("error");
            r.channelName = x.optString("channelName"); r.start = x.optLong("start"); r.end = x.optLong("end"); r.bytes = x.optLong("bytes"); r.createdAt = x.optLong("createdAt");
            r.channel = ReminderStore.decode(new CryptoBox().decrypt(x.optString("channel")));
            return r.channel == null ? null : r;
        } catch (Exception broken) { return null; }
    }

    /** All recordings, newest programme first. */
    public static synchronized List<Recording> all(Context c) {
        List<Recording> out = new ArrayList<>();
        for (String id : new ArrayList<>(prefs(c).getAll().keySet())) { Recording r = get(c, id); if (r != null) out.add(r); }
        out.sort((a, b) -> Long.compare(b.start, a.start));
        return out;
    }
}
