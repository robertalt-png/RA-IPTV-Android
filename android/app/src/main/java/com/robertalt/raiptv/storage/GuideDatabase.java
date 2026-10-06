package com.nenotv.player.storage;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.*;
import com.nenotv.player.core.GuideMatcher;
import com.nenotv.player.model.EpgEntry;
import java.io.InputStream;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/**
 * G1 guide database: the complete XMLTV guide of each source, stored once on the device instead of
 * 50 programmes per channel for 12 minutes (EpgStore). A source is the SHA-256 of its guide address,
 * so no credentials are stored. Pro keeps 7 days back and 7 days ahead; free keeps now + 24 hours.
 */
public final class GuideDatabase extends SQLiteOpenHelper {
    private static final String DB = "sunnyiptv_guide.db";
    private static final int VERSION = 1;
    public static final long DAY = 24L * 60 * 60;
    private static final int DESCRIPTION_MAX = 600;
    private static final Object IMPORT_LOCK = new Object();
    private static volatile GuideDatabase instance;

    public static GuideDatabase get(Context c) {
        if (instance == null) synchronized (GuideDatabase.class) { if (instance == null) instance = new GuideDatabase(c.getApplicationContext()); }
        return instance;
    }

    GuideDatabase(Context c) { super(c, DB, null, VERSION); }

    @Override public void onConfigure(SQLiteDatabase db) { db.enableWriteAheadLogging(); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE programmes(source TEXT NOT NULL, channel TEXT NOT NULL, start INTEGER NOT NULL, stop INTEGER NOT NULL, title TEXT NOT NULL, descr TEXT NOT NULL DEFAULT '', PRIMARY KEY(source, channel, start))");
        db.execSQL("CREATE INDEX idx_programmes_title ON programmes(source, title)");
        db.execSQL("CREATE TABLE channel_keys(source TEXT NOT NULL, k TEXT NOT NULL, channel TEXT NOT NULL, PRIMARY KEY(source, k))");
        db.execSQL("CREATE TABLE meta(source TEXT PRIMARY KEY, updated INTEGER NOT NULL, back_s INTEGER NOT NULL, ahead_s INTEGER NOT NULL, programmes INTEGER NOT NULL, channels INTEGER NOT NULL, failed_at INTEGER NOT NULL DEFAULT 0)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int a, int b) {
        for (String t : new String[]{"programmes", "channel_keys", "meta"}) db.execSQL("DROP TABLE IF EXISTS " + t);
        onCreate(db);
    }

    public static String sourceKey(String guideUrl) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest((guideUrl == null ? "" : guideUrl.trim()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder s = new StringBuilder("g");
            for (int i = 0; i < 12; i++) s.append(String.format(Locale.ROOT, "%02x", d[i] & 255));
            return s.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** True when the stored guide is younger than maxAgeMs and covers at least the requested window. */
    public boolean fresh(String source, long maxAgeMs, long backS, long aheadS) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT updated, back_s, ahead_s FROM meta WHERE source=?", new String[]{source})) {
            return c.moveToFirst() && System.currentTimeMillis() - c.getLong(0) < maxAgeMs && c.getLong(1) >= backS && c.getLong(2) >= aheadS;
        } catch (Exception e) { return false; }
    }

    public long lastFailure(String source) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT failed_at FROM meta WHERE source=?", new String[]{source})) { return c.moveToFirst() ? c.getLong(0) : 0L; }
        catch (Exception e) { return 0L; }
    }

    public void markFailed(String source) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.execSQL("INSERT OR IGNORE INTO meta(source, updated, back_s, ahead_s, programmes, channels, failed_at) VALUES(?,0,0,0,0,0,0)", new Object[]{source});
            db.execSQL("UPDATE meta SET failed_at=? WHERE source=?", new Object[]{System.currentTimeMillis(), source});
        } catch (Exception ignored) {}
    }

    /** Counts of the stored guide for a source: {programmes, channels}. */
    public long[] counts(String source) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT programmes, channels FROM meta WHERE source=?", new String[]{source})) { return c.moveToFirst() ? new long[]{c.getLong(0), c.getLong(1)} : new long[]{0, 0}; }
        catch (Exception e) { return new long[]{0, 0}; }
    }

    /**
     * Streams an XMLTV document into the database in one transaction. Programmes outside
     * [now-backS, now+aheadS] are skipped. A broken or empty document leaves the previous guide untouched.
     */
    public long[] importXmltv(String source, InputStream in, long nowS, long backS, long aheadS, String preferredLanguage) throws Exception {
        synchronized (IMPORT_LOCK) {
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                db.delete("programmes", "source=?", new String[]{source});
                db.delete("channel_keys", "source=?", new String[]{source});
                Importer h = new Importer(db, source, nowS - backS, nowS + aheadS, preferredLanguage);
                SAXParserFactory f = SAXParserFactory.newInstance();
                try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false); } catch (Exception ignored) {}
                try { f.newSAXParser().parse(in, h); h.finish(); }
                finally { h.close(); }
                if (h.programmes == 0) throw new java.io.IOException("GUIDE_EMPTY");
                db.execSQL("INSERT OR REPLACE INTO meta(source, updated, back_s, ahead_s, programmes, channels, failed_at) VALUES(?,?,?,?,?,?,0)",
                    new Object[]{source, System.currentTimeMillis(), backS, aheadS, h.programmes, h.channels});
                db.setTransactionSuccessful();
                return new long[]{h.programmes, h.channels};
            } finally { db.endTransaction(); }
        }
    }

    /** Removes everything stored for one source (used when a source is deleted, and by tests). */
    public void forget(String source) {
        synchronized (IMPORT_LOCK) {
            SQLiteDatabase db = getWritableDatabase();
            for (String t : new String[]{"programmes", "channel_keys", "meta"}) db.delete(t, "source=?", new String[]{source});
        }
    }

    /** XMLTV channel id for a playlist channel, or "" when the guide has none. */
    public String channelFor(String source, String tvgId, String tvgName, String name) {
        SQLiteDatabase db = getReadableDatabase();
        for (String k : GuideMatcher.channelKeys(tvgId, tvgName, name)) {
            try (Cursor c = db.rawQuery("SELECT channel FROM channel_keys WHERE source=? AND k=?", new String[]{source, k})) { if (c.moveToFirst()) return c.getString(0); }
            catch (Exception ignored) {}
        }
        return "";
    }

    /** Programmes of one guide channel that are still running at fromS or start before toS, oldest first. */
    public List<EpgEntry> entries(String source, String channel, long fromS, long toS, int limit) {
        ArrayList<EpgEntry> out = new ArrayList<>();
        if (channel == null || channel.isEmpty()) return out;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT start, stop, title, descr FROM programmes WHERE source=? AND channel=? AND stop>? AND start<? ORDER BY start LIMIT ?",
                new String[]{source, channel, String.valueOf(fromS), String.valueOf(toS), String.valueOf(Math.max(1, limit))})) {
            while (c.moveToNext()) {
                EpgEntry e = new EpgEntry();
                e.startEpoch = c.getLong(0); e.endEpoch = c.getLong(1); e.title = c.getString(2); e.description = c.getString(3);
                e.startRaw = String.valueOf(e.startEpoch); e.endRaw = String.valueOf(e.endEpoch);
                out.add(e);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static final class Importer extends DefaultHandler {
        final SQLiteDatabase db; final String source, preferred; final long from, to;
        final SQLiteStatement insertProgramme, insertKey;
        long programmes = 0, channels = 0;
        String channelId = "", channel = "", field = "", fieldLang = ""; long start = 0, stop = 0;
        final List<String> displayNames = new ArrayList<>();
        final StringBuilder chars = new StringBuilder();
        final LinkedHashMap<String, String> titles = new LinkedHashMap<>(), descs = new LinkedHashMap<>();

        Importer(SQLiteDatabase db, String source, long from, long to, String preferred) {
            this.db = db; this.source = source; this.from = from; this.to = to; this.preferred = base(preferred);
            insertProgramme = db.compileStatement("INSERT OR REPLACE INTO programmes(source, channel, start, stop, title, descr) VALUES(?,?,?,?,?,?)");
            insertKey = db.compileStatement("INSERT OR IGNORE INTO channel_keys(source, k, channel) VALUES(?,?,?)");
        }

        @Override public void startElement(String u, String l, String q, Attributes a) {
            switch (q) {
                case "channel": channelId = nz(a.getValue("id")); displayNames.clear(); break;
                case "display-name": field = q; chars.setLength(0); break;
                case "programme": channel = nz(a.getValue("channel")); start = parse(a.getValue("start")); stop = parse(a.getValue("stop")); titles.clear(); descs.clear(); break;
                case "title": case "desc": field = q; fieldLang = base(a.getValue("lang")); chars.setLength(0); break;
                default: break;
            }
        }

        @Override public void characters(char[] ch, int s, int n) { if (!field.isEmpty()) chars.append(ch, s, n); }

        @Override public void endElement(String u, String l, String q) {
            if (q.equals(field)) {
                String v = chars.toString().trim();
                if (!v.isEmpty()) {
                    if ("display-name".equals(q)) displayNames.add(v);
                    else if ("title".equals(q)) titles.put(fieldLang, v);
                    else descs.put(fieldLang, v);
                }
                field = "";
            } else if ("channel".equals(q) && !channelId.isEmpty()) {
                for (String k : GuideMatcher.guideKeys(channelId, displayNames)) { insertKey.bindString(1, source); insertKey.bindString(2, k); insertKey.bindString(3, channelId); insertKey.executeInsert(); }
                channels++; channelId = "";
            } else if ("programme".equals(q)) {
                if (!channel.isEmpty() && start > 0 && stop > start && stop > from && start < to) {
                    String title = pick(titles); if (title.isEmpty()) title = "—";
                    String d = pick(descs); if (d.length() > DESCRIPTION_MAX) d = d.substring(0, DESCRIPTION_MAX - 1) + "…";
                    insertProgramme.bindString(1, source); insertProgramme.bindString(2, channel); insertProgramme.bindLong(3, start); insertProgramme.bindLong(4, stop);
                    insertProgramme.bindString(5, title); insertProgramme.bindString(6, d); insertProgramme.executeInsert();
                    programmes++;
                }
                channel = "";
            }
        }

        /** Channels that only appear in programmes (no <channel> element) still get their id as key. */
        void finish() {
            try (Cursor c = db.rawQuery("SELECT DISTINCT channel FROM programmes WHERE source=?", new String[]{source})) {
                while (c.moveToNext()) for (String k : GuideMatcher.guideKeys(c.getString(0), Collections.emptyList())) { insertKey.bindString(1, source); insertKey.bindString(2, k); insertKey.bindString(3, c.getString(0)); insertKey.executeInsert(); }
            }
        }

        void close() { try { insertProgramme.close(); } catch (Exception ignored) {} try { insertKey.close(); } catch (Exception ignored) {} }

        String pick(LinkedHashMap<String, String> m) { if (m.isEmpty()) return ""; String x = m.get(preferred); if (x != null) return x; x = m.get(""); if (x != null) return x; return m.values().iterator().next(); }
        static String nz(String s) { return s == null ? "" : s.trim(); }
        static String base(String x) { if (x == null) return ""; String z = x.trim().toLowerCase(Locale.ROOT).replace('_', '-'); int i = z.indexOf('-'); return i > 0 ? z.substring(0, i) : z; }
    }

    /** XMLTV time "yyyyMMddHHmmss +hhmm" (offset optional, then UTC) to epoch seconds; 0 when unreadable. */
    static long parse(String s) {
        try {
            if (s == null) return 0;
            String t = s.trim(); if (t.length() < 14) return 0;
            LocalDateTime local = LocalDateTime.parse(t.substring(0, 14), DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            ZoneOffset off = ZoneOffset.UTC;
            String rest = t.substring(14).trim();
            if (rest.matches("[+-]\\d{4}.*")) off = ZoneOffset.of(rest.substring(0, 3) + ":" + rest.substring(3, 5));
            return local.toEpochSecond(off);
        } catch (Exception e) { return 0; }
    }
}
