package com.nenotv.player.core;

import java.time.*;

/** G2 time-window arithmetic for the guide grid: 2-hour views on half-hour slots, Pro 7 days back and ahead. */
public final class GuideWindow {
    private GuideWindow() {}

    public static final long SLOT = 1800L, SPAN = 7200L, DAY = 86400L, PRO_DAYS = 7;

    public static long nowSlot(long now) { return Math.floorDiv(now, SLOT) * SLOT; }

    /** The view start that may be shown: free always starts at the current slot, Pro stays within 7 days. */
    public static long clamp(long start, long now, boolean pro) {
        long base = nowSlot(now);
        if (!pro || start <= 0) return base;
        long min = base - PRO_DAYS * DAY, max = base + PRO_DAYS * DAY - SPAN;
        return Math.max(min, Math.min(max, Math.floorDiv(start, SLOT) * SLOT));
    }

    /** Days between today and the day of {@code start} (negative = past). */
    public static int dayOffset(long start, long now, ZoneId zone) {
        LocalDate today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate();
        LocalDate day = Instant.ofEpochSecond(start).atZone(zone).toLocalDate();
        return (int) java.time.temporal.ChronoUnit.DAYS.between(today, day);
    }

    /** The same time of day as {@code start}, moved to today + {@code offset} days. */
    public static long onDay(long start, int offset, long now, ZoneId zone) {
        LocalTime time = Instant.ofEpochSecond(start).atZone(zone).toLocalTime();
        LocalDate target = Instant.ofEpochSecond(now).atZone(zone).toLocalDate().plusDays(offset);
        return target.atTime(time).atZone(zone).toEpochSecond();
    }

    /** Horizontal position of the now-line in a timeline of {@code width}, or -1 when now is outside the view. */
    public static int nowLine(long now, long base, int width) {
        if (now < base || now >= base + SPAN) return -1;
        return (int) Math.round((now - base) * (double) width / SPAN);
    }
}
