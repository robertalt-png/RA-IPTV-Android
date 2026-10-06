package com.nenotv.player.core;

import java.time.*;
import java.util.*;

public final class CatchupUrlsTest {
    static int checks = 0;
    static void ok(boolean b, String m) { if (!b) throw new AssertionError(m); checks++; }

    public static void main(String[] args) {
        String[] x = CatchupUrls.xtreamParts("http://panel.example:8080/live/jan/geheim/12345.ts");
        ok(x != null && x[0].equals("http://panel.example:8080") && x[1].equals("jan") && x[2].equals("geheim") && x[3].equals("12345"), "live url " + Arrays.toString(x));
        x = CatchupUrls.xtreamParts("https://panel.example/jan/geheim/678");
        ok(x != null && x[3].equals("678"), "short url");
        ok(CatchupUrls.xtreamParts("https://cdn.example/hls/channel/index.m3u8") == null, "plain HLS is not Xtream");
        ok(CatchupUrls.xtreamParts("http://panel.example/live/jan/geheim/abc.ts") == null, "non-numeric id");
        ok(CatchupUrls.xtreamParts("http://panel.example/get.php?username=a&password=b") == null, "query url");
        ok(CatchupUrls.xtreamParts(null) == null, "null");

        ZoneId ams = ZoneId.of("Europe/Amsterdam");
        long start = LocalDateTime.of(2026, 10, 5, 20, 0).atZone(ams).toEpochSecond();
        ok(CatchupUrls.start(start, ams).equals("2026-10-05:20-00"), "server zone");
        ok(CatchupUrls.start(start, ZoneOffset.UTC).equals("2026-10-05:18-00"), "UTC zone");
        ok(CatchupUrls.minutes(start, start + 1801) == 31, "minutes round up");
        ok(CatchupUrls.minutes(start, start) == 1, "minimum one minute");
        List<String> u = CatchupUrls.xtream("http://panel.example:8080/", "jan", "ge heim", "12345", start, start + 3600, ams);
        ok(u.get(0).equals("http://panel.example:8080/timeshift/jan/ge+heim/60/2026-10-05:20-00/12345.m3u8"), u.get(0));
        ok(u.get(1).endsWith("/12345.ts"), u.get(1));
        ok(u.get(2).equals("http://panel.example:8080/streaming/timeshift.php?username=jan&password=ge+heim&stream=12345&start=2026-10-05:20-00&duration=60"), u.get(2));

        long now = start + 3600;
        ok(CatchupUrls.available(start, now, 7), "yesterday evening available");
        ok(!CatchupUrls.available(now + 60, now, 7), "future not available");
        ok(!CatchupUrls.available(now - 8 * 86400L, now, 7), "outside archive");
        ok(CatchupUrls.available(now - 2 * 86400L, now, 0), "unknown archive length uses 3 days");
        ok(CatchupUrls.zone("Europe/Amsterdam").equals(ams) && CatchupUrls.zone("Mars/Base") == null && CatchupUrls.zone("") == null, "zone parse");
        System.out.println("Catch-up urls: " + checks + " checks passed");
    }
}
