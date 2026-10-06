package com.nenotv.player.core;

import java.time.*;

public final class CatchupTemplateTest {
    static int checks = 0;
    static void eq(String a, String b) { if (!a.equals(b)) throw new AssertionError("'" + a + "' != '" + b + "'"); checks++; }
    public static void main(String[] args) {
        ZoneId ams = ZoneId.of("Europe/Amsterdam");
        long start = LocalDateTime.of(2026, 10, 5, 20, 0).atZone(ams).toEpochSecond(), end = start + 3600, now = start + 7200;
        eq(CatchupTemplate.fill("https://cdn/x?utc={utc}&end={utcend}&now={lutc}&d={duration}", start, end, now, ams), "https://cdn/x?utc=" + start + "&end=" + end + "&now=" + now + "&d=3600");
        eq(CatchupTemplate.fill("/{Y}/{m}/{d}/{H}-{M}-{S}.ts?min=${duration:60}&off={offset:60}", start, end, now, ams), "/2026/10/05/20-00-00.ts?min=60&off=120");
        eq(CatchupTemplate.fill("a{unknown}b", start, end, now, ams), "a{unknown}b");
        eq(CatchupTemplate.url("default", "https://arch/ch1?start={start}", "https://live/ch1.m3u8", start, end, now, ams), "https://arch/ch1?start=" + start);
        eq(CatchupTemplate.url("append", "?utc={utc}&lutc={lutc}", "https://live/ch1.m3u8", start, end, now, ams), "https://live/ch1.m3u8?utc=" + start + "&lutc=" + now);
        eq(CatchupTemplate.url("shift", "", "https://live/ch1.m3u8?token=a", start, end, now, ams), "https://live/ch1.m3u8?token=a&utc=" + start + "&lutc=" + now);
        eq(CatchupTemplate.url("flussonic", "", "https://fs.example/ch1/index.m3u8?token=a", start, end, now, ams), "https://fs.example/ch1/index-" + start + "-3600.m3u8?token=a");
        eq(CatchupTemplate.url("flussonic", "", "https://fs.example/ch1/mpegts?token=a", start, end, now, ams), "https://fs.example/ch1/timeshift_abs-" + start + ".ts?token=a");
        eq(CatchupTemplate.url("", "", "https://live/ch1.m3u8", start, end, now, ams), "");
        eq(CatchupTemplate.url("default", "", "https://live/ch1.m3u8", start, end, now, ams), "");
        eq(CatchupTemplate.url("append", "", "https://live/ch1.m3u8", start, end, now, ams), "");
        System.out.println("Catch-up templates: " + checks + " checks passed");
    }
}
