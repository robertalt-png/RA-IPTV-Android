package com.nenotv.player.core;

import java.time.*;
import java.util.*;
import java.util.regex.*;

/**
 * P3: M3U catch-up as used by IPTV playlists (Kodi PVR IPTV Simple conventions):
 * catchup="default" (catchup-source is the full address), "append" (catchup-source is added to the
 * channel address), "shift" (?utc=&lutc=), "flussonic" (index-START-DURATION.m3u8 / timeshift_abs-START.ts).
 * "xc" and Xtream addresses are handled by {@link CatchupUrls}.
 */
public final class CatchupTemplate {
    private CatchupTemplate() {}

    private static final Pattern TOKEN = Pattern.compile("\\$?\\{([A-Za-z]+)(?::([^}]*))?\\}");

    /** Fills {utc} {start} {utcend} {end} {lutc} {now} {timestamp} {duration} {duration:60} {offset:1} {Y} {m} {d} {H} {M} {S} (also ${...}). */
    public static String fill(String template, long start, long end, long now, ZoneId zone) {
        if (template == null) return "";
        ZonedDateTime t = Instant.ofEpochSecond(start).atZone(zone == null ? ZoneOffset.UTC : zone);
        Matcher m = TOKEN.matcher(template);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String name = m.group(1), arg = m.group(2), v;
            long div = 1;
            if (arg != null) try { div = Math.max(1, Long.parseLong(arg.trim())); } catch (NumberFormatException ignored) {}
            switch (name) {
                case "utc": case "start": v = String.valueOf(start); break;
                case "utcend": case "end": v = String.valueOf(end); break;
                case "lutc": case "now": case "timestamp": v = String.valueOf(now); break;
                case "duration": v = String.valueOf(Math.max(0, end - start) / div); break;
                case "offset": v = String.valueOf(Math.max(0, now - start) / div); break;
                case "Y": v = String.format(Locale.ROOT, "%04d", t.getYear()); break;
                case "m": v = String.format(Locale.ROOT, "%02d", t.getMonthValue()); break;
                case "d": v = String.format(Locale.ROOT, "%02d", t.getDayOfMonth()); break;
                case "H": v = String.format(Locale.ROOT, "%02d", t.getHour()); break;
                case "M": v = String.format(Locale.ROOT, "%02d", t.getMinute()); break;
                case "S": v = String.format(Locale.ROOT, "%02d", t.getSecond()); break;
                default: v = m.group(0);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(v));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Catch-up address for an M3U channel, or "" when the playlist gives no usable catch-up. */
    public static String url(String type, String source, String live, long start, long end, long now, ZoneId zone) {
        String kind = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        String src = source == null ? "" : source.trim();
        String base = live == null ? "" : live.trim();
        if (kind.equals("append")) return base.isEmpty() || src.isEmpty() ? "" : base + fill(src, start, end, now, zone);
        if (kind.equals("shift") || kind.equals("timeshift")) {
            if (base.isEmpty()) return "";
            return base + (base.contains("?") ? "&" : "?") + "utc=" + start + "&lutc=" + now;
        }
        if (kind.equals("flussonic") || kind.equals("flussonic-hls") || kind.equals("flussonic-ts") || kind.equals("fs")) return flussonic(base, start, end);
        if (!src.isEmpty() && (src.startsWith("http://") || src.startsWith("https://"))) return fill(src, start, end, now, zone);
        if (!src.isEmpty() && !base.isEmpty()) return base + fill(src, start, end, now, zone);
        return "";
    }

    static String flussonic(String live, long start, long end) {
        if (live.isEmpty()) return "";
        long dur = Math.max(1, end - start);
        Matcher hls = Pattern.compile("^(.*/)([^/?]+)\\.m3u8(\\?.*)?$").matcher(live);
        if (hls.matches()) {
            String file = hls.group(2), query = hls.group(3) == null ? "" : hls.group(3);
            String name = file.equals("index") || file.equals("mono") || file.startsWith("video") ? file : "index";
            String path = file.equals(name) ? hls.group(1) : hls.group(1) + file + "/";
            return path + name + "-" + start + "-" + dur + ".m3u8" + query;
        }
        Matcher ts = Pattern.compile("^(.*/)(mpegts|[^/?]+\\.ts)(\\?.*)?$").matcher(live);
        if (ts.matches()) return ts.group(1) + "timeshift_abs-" + start + ".ts" + (ts.group(3) == null ? "" : ts.group(3));
        return "";
    }
}
