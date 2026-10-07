package com.nenotv.player.core;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

/**
 * Recording: copies a live stream to a file until the stop time. Plain Java (no Android) so it can be
 * tested against a local HTTP server. A continuous transport stream is copied as it arrives; an HLS
 * stream is followed by polling the playlist and appending each new segment (AES-128 is decrypted).
 * A dropped connection is retried with the reconnect delays until the stop time; what was recorded is kept.
 */
public final class RecordingEngine {
    public interface Clock { long nowMs(); }
    public interface Listener {
        void progress(long bytes);
        /** Returns false when the disk is (almost) full. */
        default boolean spaceLeft() { return true; }
    }

    public static final class Result {
        public long bytes;
        /** True when the stop time was reached (the recording covers the whole window, gaps aside). */
        public boolean reachedEnd;
        public int reconnects;
        /** Empty, or: cancelled, no_space, encrypted, unavailable. */
        public String error = "";
    }

    private final Clock clock;
    private final String userAgent;
    private final int connectTimeoutMs, readTimeoutMs;
    private volatile boolean cancelled;
    private volatile HttpURLConnection current;

    public RecordingEngine(Clock clock, String userAgent, int connectTimeoutMs, int readTimeoutMs) {
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.userAgent = userAgent == null ? "SunnyIPTV" : userAgent;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    /** Stops a running recording from another thread; the file keeps what was written. */
    public void cancel() {
        cancelled = true;
        HttpURLConnection c = current;
        if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
    }

    public boolean isCancelled() { return cancelled; }

    public Result record(List<String> urls, File out, long stopAtMs, Listener listener) {
        Result r = new Result();
        List<String> order = RecordingPlan.order(urls);
        if (order.isEmpty()) { r.error = "unavailable"; return r; }
        Listener l = listener == null ? bytes -> {} : listener;
        int attempt = 0, index = 0;
        try (OutputStream file = new BufferedOutputStream(new FileOutputStream(out, true), 64 * 1024)) {
            HlsState hls = new HlsState();
            while (!cancelled && clock.nowMs() < stopAtMs) {
                String url = order.get(index % order.size());
                long before = r.bytes;
                try {
                    copy(url, file, stopAtMs, r, l, hls);
                } catch (NoSpace e) {
                    r.error = "no_space"; break;
                } catch (Encrypted e) {
                    r.error = "encrypted"; index++; if (index >= order.size() * 2) break; continue;
                } catch (IOException dropped) {
                    // fall through to the retry below
                }
                file.flush();
                if (cancelled || clock.nowMs() >= stopAtMs) break;
                boolean gotData = r.bytes > before;
                if (gotData) attempt = 0;
                else { attempt++; index++; }
                r.reconnects++;
                // After a stream that delivered data ends, reconnect almost at once to keep the gap small.
                long wait = Math.min(gotData ? 500L : Reconnect.delayMs(attempt - 1), Math.max(0, stopAtMs - clock.nowMs()));
                sleep(wait);
            }
            file.flush();
        } catch (IOException e) {
            if (r.error.isEmpty()) r.error = "no_space";
        }
        if (cancelled && r.error.isEmpty()) r.error = "cancelled";
        r.reachedEnd = !cancelled && clock.nowMs() >= stopAtMs && r.bytes > 0;
        if (r.bytes == 0 && r.error.isEmpty()) r.error = "unavailable";
        return r;
    }

    // ---- one connection ----

    static final class NoSpace extends IOException {}
    static final class Encrypted extends IOException {}

    static final class HlsState {
        long lastSequence = Long.MIN_VALUE;
        boolean initWritten;
        String keyUri = ""; byte[] key;
    }

    private HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(connectTimeoutMs);
        c.setReadTimeout(readTimeoutMs);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", userAgent);
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("Accept-Encoding", "identity");
        current = c;
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) { c.disconnect(); throw new IOException("HTTP " + code); }
        return c;
    }

    private void copy(String url, OutputStream file, long stopAtMs, Result r, Listener l, HlsState hls) throws IOException {
        HttpURLConnection c = open(url);
        try {
            BufferedInputStream in = new BufferedInputStream(c.getInputStream(), 64 * 1024);
            in.mark(64);
            byte[] head = new byte[7];
            int n = in.read(head);
            in.reset();
            String first = n > 0 ? new String(head, 0, n, "ISO-8859-1") : "";
            if (RecordingPlan.looksLikeHls(url, c.getContentType(), first)) {
                String text = readText(in);
                c.disconnect();
                followHls(url, text, file, stopAtMs, r, l, hls);
                return;
            }
            byte[] buf = new byte[64 * 1024];
            int read;
            long lastCheck = 0;
            while (!cancelled && clock.nowMs() < stopAtMs && (read = in.read(buf)) > 0) {
                file.write(buf, 0, read);
                r.bytes += read;
                if (r.bytes - lastCheck >= 4L * 1024 * 1024) { lastCheck = r.bytes; l.progress(r.bytes); if (!l.spaceLeft()) throw new NoSpace(); }
            }
        } finally {
            c.disconnect();
            current = null;
        }
    }

    private void followHls(String playlistUrl, String text, OutputStream file, long stopAtMs, Result r, Listener l, HlsState hls) throws IOException {
        String url = playlistUrl;
        RecordingPlan.Playlist p = RecordingPlan.parse(text, url);
        if (p.master && !p.bestVariant.isEmpty()) { url = p.bestVariant; p = RecordingPlan.parse(fetchText(url), url); }
        while (!cancelled && clock.nowMs() < stopAtMs) {
            if (p.unsupportedKey) throw new Encrypted();
            if (!hls.initWritten && !p.initUri.isEmpty()) { r.bytes += fetchTo(p.initUri, file, null, null); hls.initWritten = true; }
            boolean any = false;
            for (RecordingPlan.Segment s : p.segments) {
                if (cancelled || clock.nowMs() >= stopAtMs) return;
                if (s.sequence <= hls.lastSequence) continue;
                byte[] key = null;
                if ("AES-128".equals(s.keyMethod)) {
                    if (!s.keyUri.equals(hls.keyUri) || hls.key == null) { hls.key = fetchBytes(s.keyUri); hls.keyUri = s.keyUri; }
                    key = hls.key;
                }
                r.bytes += fetchTo(s.uri, file, key, key == null ? null : RecordingPlan.iv(s.iv, s.sequence));
                hls.lastSequence = s.sequence;
                any = true;
                l.progress(r.bytes);
                if (!l.spaceLeft()) throw new NoSpace();
            }
            if (p.ended) return;
            long wait = (long) (Math.max(1.0, p.targetDuration) * (any ? 500 : 1000));
            sleep(Math.min(wait, Math.max(0, stopAtMs - clock.nowMs())));
            if (cancelled || clock.nowMs() >= stopAtMs) return;
            p = RecordingPlan.parse(fetchText(url), url);
        }
    }

    private String fetchText(String url) throws IOException {
        HttpURLConnection c = open(url);
        try { return readText(c.getInputStream()); } finally { c.disconnect(); current = null; }
    }

    private static String readText(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) { b.write(buf, 0, n); if (b.size() > 4 * 1024 * 1024) break; }
        return b.toString("UTF-8");
    }

    private byte[] fetchBytes(String url) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        } finally { c.disconnect(); current = null; }
    }

    private long fetchTo(String url, OutputStream file, byte[] key, byte[] iv) throws IOException {
        if (key != null) {
            byte[] data = fetchBytes(url);
            try {
                javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
                cipher.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"), new javax.crypto.spec.IvParameterSpec(iv));
                byte[] plain = cipher.doFinal(data);
                file.write(plain);
                return plain.length;
            } catch (java.security.GeneralSecurityException e) { throw new Encrypted(); }
        }
        HttpURLConnection c = open(url);
        long total = 0;
        try (InputStream in = c.getInputStream()) {
            byte[] buf = new byte[64 * 1024]; int n;
            while ((n = in.read(buf)) > 0) { file.write(buf, 0, n); total += n; if (cancelled) break; }
        } finally { c.disconnect(); current = null; }
        return total;
    }

    private void sleep(long ms) {
        long until = clock.nowMs() + ms;
        while (!cancelled && clock.nowMs() < until) {
            try { Thread.sleep(Math.min(250, Math.max(1, until - clock.nowMs()))); } catch (InterruptedException e) { cancelled = true; Thread.currentThread().interrupt(); }
        }
    }
}
