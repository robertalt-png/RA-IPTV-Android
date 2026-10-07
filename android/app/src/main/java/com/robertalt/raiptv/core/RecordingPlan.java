package com.nenotv.player.core;

import java.util.*;

/**
 * Recording: pure rules (no Android) for when a recording runs, which stream address it uses,
 * how the file is named and how an HLS playlist is read. Tested in tools/tests/RecordingTest.java.
 */
public final class RecordingPlan {
    private RecordingPlan() {}

    /** Start this long before the programme and stop this long after it, because guide times are rarely exact. */
    public static final long PAD_BEFORE_S = 60, PAD_AFTER_S = 180;
    /** Stop when less than this much free space is left, keeping what was recorded. */
    public static final long MIN_FREE_BYTES = 300L * 1024 * 1024;
    /** A manual recording without a guide entry runs at most this long. */
    public static final long MAX_DURATION_S = 6 * 3600;

    public static long startAt(long programmeStartS) { return programmeStartS - PAD_BEFORE_S; }
    public static long stopAt(long programmeEndS) { return programmeEndS + PAD_AFTER_S; }

    /** Two recordings overlap when their padded windows overlap. */
    public static boolean overlaps(long aStart, long aEnd, long bStart, long bEnd) {
        return startAt(aStart) < stopAt(bEnd) && startAt(bStart) < stopAt(aEnd);
    }

    /** True when a window can still be recorded (it has not ended yet). */
    public static boolean stillRecordable(long endS, long nowS) { return stopAt(endS) > nowS; }

    /**
     * Stream addresses in the order to try: a continuous transport stream (.ts) first because it records
     * without gaps, then HLS (.m3u8), then the rest. Duplicates and empty values are dropped.
     */
    public static List<String> order(List<String> urls) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        if (urls != null) for (String u : urls) if (u != null && !u.trim().isEmpty()) seen.add(u.trim());
        List<String> out = new ArrayList<>(seen);
        out.sort(Comparator.comparingInt(RecordingPlan::rank));
        return out;
    }

    static int rank(String u) {
        String x = u.toLowerCase(Locale.ROOT);
        int q = x.indexOf('?'); if (q >= 0) x = x.substring(0, q);
        if (x.endsWith(".ts") || x.endsWith(".mpegts")) return 0;
        if (x.endsWith(".m3u8") || x.endsWith(".m3u")) return 1;
        return 2;
    }

    public static boolean looksLikeHls(String url, String contentType, String firstLine) {
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (ct.contains("mpegurl")) return true;
        if (firstLine != null && firstLine.trim().startsWith("#EXTM3U")) return true;
        String x = url == null ? "" : url.toLowerCase(Locale.ROOT);
        int q = x.indexOf('?'); if (q >= 0) x = x.substring(0, q);
        return x.endsWith(".m3u8");
    }

    /** File name for a recording: "2026-10-07 2030 NPO 1 - Journaal.ts", without characters file systems refuse. */
    public static String fileName(String channel, String title, long startS, TimeZone zone) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd HHmm", Locale.ROOT);
        f.setTimeZone(zone == null ? TimeZone.getDefault() : zone);
        String name = f.format(new Date(startS * 1000L)) + " " + clean(channel) + (title == null || title.trim().isEmpty() ? "" : " - " + clean(title));
        if (name.length() > 120) name = name.substring(0, 120).trim();
        return name + ".ts";
    }

    static String clean(String s) {
        if (s == null) return "";
        String x = s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        while (x.startsWith(".")) x = x.substring(1).trim();
        return x;
    }

    // ---- HLS ----

    public static final class Segment {
        public final long sequence; public final String uri; public final double duration;
        public final String keyMethod, keyUri, iv;
        Segment(long sequence, String uri, double duration, String keyMethod, String keyUri, String iv) {
            this.sequence = sequence; this.uri = uri; this.duration = duration; this.keyMethod = keyMethod; this.keyUri = keyUri; this.iv = iv;
        }
    }

    public static final class Playlist {
        public boolean master, ended;
        public String bestVariant = "", initUri = "";
        public double targetDuration = 6;
        public final List<Segment> segments = new ArrayList<>();
        public boolean unsupportedKey;
    }

    /** Reads an HLS playlist; relative addresses are resolved against {@code base}. */
    public static Playlist parse(String text, String base) {
        Playlist p = new Playlist();
        if (text == null) return p;
        long seq = 0; double dur = 0; long bestBandwidth = -1; boolean nextIsVariant = false; long variantBandwidth = 0;
        String keyMethod = "NONE", keyUri = "", iv = "";
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXT-X-STREAM-INF")) { p.master = true; nextIsVariant = true; variantBandwidth = longAttr(line, "BANDWIDTH"); continue; }
            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) { try { seq = Long.parseLong(line.substring(22).trim()); } catch (Exception ignored) {} continue; }
            if (line.startsWith("#EXT-X-TARGETDURATION:")) { try { p.targetDuration = Double.parseDouble(line.substring(22).trim()); } catch (Exception ignored) {} continue; }
            if (line.startsWith("#EXT-X-ENDLIST")) { p.ended = true; continue; }
            if (line.startsWith("#EXT-X-MAP:")) { String u = attr(line, "URI"); if (!u.isEmpty()) p.initUri = resolve(base, u); continue; }
            if (line.startsWith("#EXT-X-KEY:")) {
                keyMethod = attr(line, "METHOD"); if (keyMethod.isEmpty()) keyMethod = "NONE";
                keyUri = attr(line, "URI"); if (!keyUri.isEmpty()) keyUri = resolve(base, keyUri);
                iv = attr(line, "IV");
                if (!"NONE".equals(keyMethod) && !"AES-128".equals(keyMethod)) p.unsupportedKey = true;
                continue;
            }
            if (line.startsWith("#EXTINF:")) { String d = line.substring(8); int c = d.indexOf(','); try { dur = Double.parseDouble((c >= 0 ? d.substring(0, c) : d).trim()); } catch (Exception ignored) { dur = 0; } continue; }
            if (line.startsWith("#")) continue;
            if (nextIsVariant) {
                if (variantBandwidth > bestBandwidth) { bestBandwidth = variantBandwidth; p.bestVariant = resolve(base, line); }
                nextIsVariant = false; continue;
            }
            p.segments.add(new Segment(seq, resolve(base, line), dur, keyMethod, keyUri, iv));
            seq++; dur = 0;
        }
        return p;
    }

    static String attr(String line, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:^|[:,])" + name + "=(\"([^\"]*)\"|([^,]*))").matcher(line);
        if (!m.find()) return "";
        return m.group(2) != null ? m.group(2) : m.group(3).trim();
    }

    static long longAttr(String line, String name) { try { return Long.parseLong(attr(line, name)); } catch (Exception e) { return 0; } }

    public static String resolve(String base, String ref) {
        try { return base == null || base.isEmpty() ? ref : new java.net.URI(base).resolve(ref.replace(" ", "%20")).toString(); }
        catch (Exception e) { return ref; }
    }

    /** The 16-byte IV for an AES-128 segment: the playlist IV, or the segment sequence number. */
    public static byte[] iv(String hex, long sequence) {
        byte[] out = new byte[16];
        if (hex != null && (hex.startsWith("0x") || hex.startsWith("0X"))) {
            String h = hex.substring(2);
            while (h.length() < 32) h = "0" + h;
            for (int i = 0; i < 16; i++) out[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
            return out;
        }
        for (int i = 15; i >= 8; i--) { out[i] = (byte) (sequence & 0xFF); sequence >>>= 8; }
        return out;
    }
}
