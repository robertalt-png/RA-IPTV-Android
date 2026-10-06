package com.nenotv.player.core;

import java.net.URI;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * G3 catch-up ("terugkijken" / "vanaf het begin") addresses for Xtream Codes panels.
 * The start time must be written in the panel's own time zone (player_api server_info.timezone).
 */
public final class CatchupUrls {
    private CatchupUrls() {}

    private static final DateTimeFormatter START = DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm");

    /** Panel base, username, password and stream id from an Xtream live URL, or null when it is not one. */
    public static String[] xtreamParts(String url) {
        if (url == null) return null;
        try {
            URI u = new URI(url.trim());
            String scheme = u.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")) || u.getRawAuthority() == null) return null;
            if (u.getRawQuery() != null) return null;
            String[] p = u.getRawPath() == null ? new String[0] : u.getRawPath().split("/");
            List<String> parts = new ArrayList<>();
            for (String x : p) if (!x.isEmpty()) parts.add(x);
            if (parts.size() == 4 && parts.get(0).equalsIgnoreCase("live")) parts.remove(0);
            if (parts.size() != 3) return null;
            String id = parts.get(2);
            int dot = id.lastIndexOf('.');
            if (dot > 0) id = id.substring(0, dot);
            if (!id.matches("\\d+")) return null;
            String base = scheme.toLowerCase(Locale.ROOT) + "://" + u.getRawAuthority();
            return new String[]{base, decode(parts.get(0)), decode(parts.get(1)), id};
        } catch (Exception notAUrl) { return null; }
    }

    private static String decode(String s) { try { return java.net.URLDecoder.decode(s, "UTF-8"); } catch (Exception e) { return s; } }

    public static String start(long startEpoch, ZoneId serverZone) {
        return Instant.ofEpochSecond(startEpoch).atZone(serverZone == null ? ZoneOffset.UTC : serverZone).format(START);
    }

    /** Whole minutes, rounded up, at least one. */
    public static long minutes(long startEpoch, long endEpoch) { return Math.max(1, (Math.max(0, endEpoch - startEpoch) + 59) / 60); }

    /** True when the programme has started and still lies inside the channel's archive. */
    public static boolean available(long startEpoch, long now, int archiveDays) {
        int days = archiveDays > 0 ? archiveDays : 3;
        return startEpoch > 0 && startEpoch < now && startEpoch >= now - days * 86400L;
    }

    /** HLS first (seeks best), then MPEG-TS, then the older timeshift.php form. */
    public static List<String> xtream(String base, String user, String pass, String streamId, long startEpoch, long endEpoch, ZoneId serverZone) {
        String b = XtreamUrls.base(base), u = XtreamUrls.enc(user), p = XtreamUrls.enc(pass), id = XtreamUrls.enc(streamId);
        String st = start(startEpoch, serverZone);
        long dur = minutes(startEpoch, endEpoch);
        String path = b + "/timeshift/" + u + "/" + p + "/" + dur + "/" + st + "/" + id;
        return Arrays.asList(path + ".m3u8", path + ".ts",
                b + "/streaming/timeshift.php?username=" + u + "&password=" + p + "&stream=" + id + "&start=" + st + "&duration=" + dur);
    }

    public static ZoneId zone(String name) {
        if (name == null || name.trim().isEmpty()) return null;
        try { return ZoneId.of(name.trim()); } catch (Exception unknown) { return null; }
    }
}
