package com.nenotv.player.storage;

import android.content.Context;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPInputStream;

/**
 * Keeps the G1 guide database up to date in the background: one download per source,
 * at most every 12 hours, 30 minutes after a failure. Callers never wait for it; until the
 * first import finishes the existing per-channel guide is used (EpgStore).
 */
public final class GuideRefresher {
    private GuideRefresher() {}

    public static final long MAX_AGE_MS = 12L * 60 * 60 * 1000;
    static final long RETRY_AFTER_FAILURE_MS = 30L * 60 * 1000;
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "sunnyiptv-guide"); t.setPriority(Thread.MIN_PRIORITY); t.setDaemon(true); return t; });
    private static final Set<String> RUNNING = ConcurrentHashMap.newKeySet();

    /** Seconds kept before now: 7 days for Pro, 3 hours for free (the programme that is on now). */
    public static long backSeconds(boolean pro) { return pro ? 7 * GuideDatabase.DAY : 3L * 60 * 60; }
    /** Seconds kept after now: 7 days for Pro, 24 hours for free. */
    public static long aheadSeconds(boolean pro) { return pro ? 7 * GuideDatabase.DAY : GuideDatabase.DAY; }

    /** Starts a background import when the stored guide is missing, too old or too short for this tier. */
    public static void ensure(Context context, String guideUrl, boolean pro, String language) {
        if (guideUrl == null || guideUrl.trim().isEmpty()) return;
        String url = guideUrl.trim();
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return;
        GuideDatabase db = GuideDatabase.get(context);
        String source = GuideDatabase.sourceKey(url);
        long back = backSeconds(pro), ahead = aheadSeconds(pro);
        if (db.fresh(source, MAX_AGE_MS, back, ahead)) return;
        if (System.currentTimeMillis() - db.lastFailure(source) < RETRY_AFTER_FAILURE_MS) return;
        if (!RUNNING.add(source)) return;
        EXEC.execute(() -> {
            try { download(db, source, url, back, ahead, language); }
            catch (Throwable failure) { db.markFailed(source); }
            finally { RUNNING.remove(source); }
        });
    }

    static long[] download(GuideDatabase db, String source, String url, long back, long ahead, String language) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "SunnyIPTV-Android"); c.setRequestProperty("Accept-Encoding", "gzip");
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new java.io.IOException("GUIDE_HTTP_" + code);
            InputStream in = new java.io.BufferedInputStream(c.getInputStream(), 64 * 1024);
            in.mark(2);
            int b1 = in.read(), b2 = in.read();
            in.reset();
            if (b1 == 0x1f && b2 == 0x8b) in = new GZIPInputStream(in, 64 * 1024);
            try (InputStream stream = in) {
                return db.importXmltv(source, stream, System.currentTimeMillis() / 1000L, back, ahead, language);
            }
        } finally { c.disconnect(); }
    }
}
