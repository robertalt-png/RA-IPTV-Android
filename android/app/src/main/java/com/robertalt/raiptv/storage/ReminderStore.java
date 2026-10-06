package com.nenotv.player.storage;

import android.content.Context;
import android.content.SharedPreferences;
import com.nenotv.player.model.EpgEntry;
import com.nenotv.player.model.MediaEntry;
import java.io.*;
import java.util.*;
import org.json.JSONObject;

/** G5: programme reminders kept on the device (channel, start, title); the channel, which holds the stream address, is encrypted with the Android keystore like the library. */
public final class ReminderStore {
    private ReminderStore() {}

    public static final class Reminder {
        public String id = "", title = "";
        public long start, end;
        public MediaEntry channel;
    }

    static SharedPreferences prefs(Context c) { return c.getApplicationContext().getSharedPreferences("sunnyiptv_reminders", Context.MODE_PRIVATE); }

    public static String id(MediaEntry channel, long start) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest((channel.uniqueKey() + "|" + start).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder s = new StringBuilder("r");
            for (int i = 0; i < 10; i++) s.append(String.format(Locale.ROOT, "%02x", d[i] & 255));
            return s.toString();
        } catch (Exception e) { return "r" + Integer.toHexString((channel.uniqueKey() + "|" + start).hashCode()); }
    }

    public static Reminder add(Context c, MediaEntry channel, EpgEntry programme) {
        Reminder r = new Reminder();
        r.id = id(channel, programme.startEpoch); r.start = programme.startEpoch; r.end = programme.endEpoch; r.title = programme.title == null ? "" : programme.title; r.channel = channel;
        try {
            JSONObject x = new JSONObject();
            x.put("start", r.start); x.put("end", r.end); x.put("title", r.title); x.put("channel", new CryptoBox().encrypt(encode(channel)));
            prefs(c).edit().putString(r.id, x.toString()).apply();
        } catch (Exception ignored) {}
        return r;
    }

    public static boolean has(Context c, MediaEntry channel, long start) { return prefs(c).contains(id(channel, start)); }
    public static void remove(Context c, String id) { prefs(c).edit().remove(id).apply(); }

    public static Reminder get(Context c, String id) {
        String raw = prefs(c).getString(id == null ? "" : id, "");
        if (raw.isEmpty()) return null;
        try {
            JSONObject x = new JSONObject(raw);
            Reminder r = new Reminder();
            r.id = id; r.start = x.optLong("start"); r.end = x.optLong("end"); r.title = x.optString("title"); r.channel = decode(new CryptoBox().decrypt(x.optString("channel")));
            return r.channel == null ? null : r;
        } catch (Exception broken) { return null; }
    }

    /** All reminders still to come, soonest first; reminders more than an hour past their start are removed. */
    public static List<Reminder> upcoming(Context c, long nowS) {
        List<Reminder> out = new ArrayList<>();
        SharedPreferences.Editor ed = prefs(c).edit();
        for (String id : new ArrayList<>(prefs(c).getAll().keySet())) {
            Reminder r = get(c, id);
            if (r == null || r.start < nowS - 3600) { ed.remove(id); continue; }
            out.add(r);
        }
        ed.apply();
        out.sort(Comparator.comparingLong(r -> r.start));
        return out;
    }

    static String encode(MediaEntry e) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ObjectOutputStream o = new ObjectOutputStream(b)) { o.writeObject(e); }
        return android.util.Base64.encodeToString(b.toByteArray(), android.util.Base64.NO_WRAP);
    }

    static MediaEntry decode(String raw) {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(android.util.Base64.decode(raw, android.util.Base64.NO_WRAP)))) {
            Object o = in.readObject();
            return o instanceof MediaEntry ? (MediaEntry) o : null;
        } catch (Exception broken) { return null; }
    }
}
