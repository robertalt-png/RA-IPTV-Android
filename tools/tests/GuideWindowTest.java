package com.nenotv.player.core;

import java.time.*;

public final class GuideWindowTest {
    static int checks = 0;
    static void eq(long a, long b, String m) { if (a != b) throw new AssertionError(m + ": " + a + " != " + b); checks++; }

    public static void main(String[] args) {
        ZoneId ams = ZoneId.of("Europe/Amsterdam");
        long now = LocalDateTime.of(2026, 10, 6, 20, 47).atZone(ams).toEpochSecond();
        long slot = LocalDateTime.of(2026, 10, 6, 20, 30).atZone(ams).toEpochSecond();
        eq(GuideWindow.nowSlot(now), slot, "now slot");
        eq(GuideWindow.clamp(0, now, true), slot, "Pro default");
        eq(GuideWindow.clamp(slot - 3 * 86400, now, false), slot, "free cannot leave now");
        eq(GuideWindow.clamp(slot - 30 * 86400, now, true), slot - 7 * 86400, "Pro past limit");
        eq(GuideWindow.clamp(slot + 30 * 86400, now, true), slot + 7 * 86400 - 7200, "Pro future limit");
        eq(GuideWindow.clamp(slot + 7200 + 600, now, true), slot + 7200, "snaps to slot");
        eq(GuideWindow.dayOffset(slot, now, ams), 0, "today");
        long yesterday = GuideWindow.onDay(slot, -1, now, ams);
        eq(GuideWindow.dayOffset(yesterday, now, ams), -1, "yesterday");
        eq(Instant.ofEpochSecond(yesterday).atZone(ams).getHour(), 20, "keeps time of day");
        // Across the end of summer time (25 October 2026) the clock time stays the same.
        long after = GuideWindow.onDay(slot, 20, now, ams);
        LocalDateTime local = Instant.ofEpochSecond(after).atZone(ams).toLocalDateTime();
        eq(local.getHour() * 100 + local.getMinute(), 2030, "DST keeps 20:30");
        eq(local.getDayOfMonth(), 26, "DST day");
        eq(GuideWindow.nowLine(now, slot, 240), Math.round(17 * 60 * 240 / 7200.0), "now line");
        eq(GuideWindow.nowLine(now, slot + 7200, 240), -1, "now line outside");
        eq(GuideWindow.nowLine(now, slot - 7200, 240), -1, "now line before");
        System.out.println("Guide window: " + checks + " checks passed");
    }
}
