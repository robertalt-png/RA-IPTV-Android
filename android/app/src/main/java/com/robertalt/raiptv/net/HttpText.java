package com.nenotv.player.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;

public final class HttpText {
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_BODY = 100 * 1024 * 1024;

    private HttpText() {}

    public static String get(String url) throws IOException {
        if(com.nenotv.player.SiteEndpoints.isDemoUrl(url))return com.nenotv.player.DemoSource.PLAYLIST;
        return execute("GET", url, null, Collections.emptyMap());
    }

    public static String postJson(String url, String json, Map<String, String> headers) throws IOException {
        return execute("POST", url, json == null ? "" : json,
                headers == null ? Collections.emptyMap() : headers);
    }

    private static String execute(String method, String rawUrl, String payload,
                                  Map<String, String> headers) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("index_cancelled");
            try {
                return executeOnce(method, rawUrl, payload, headers);
            } catch (IOException e) {
                last = e;
                if (attempt == MAX_ATTEMPTS || !isRetryable(e)) throw friendly(rawUrl, e);
                try { Thread.sleep(350L * attempt); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw friendly(rawUrl, e);
                }
            }
        }
        throw friendly(rawUrl, last == null ? new IOException("Unknown network error") : last);
    }

    private static String executeOnce(String originalMethod, String rawUrl, String originalPayload,
                                      Map<String, String> headers) throws IOException {
        URL current = new URL(rawUrl);
        String method = originalMethod;
        String payload = originalPayload;

        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) current.openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(35000);
                c.setInstanceFollowRedirects(false);
                c.setUseCaches(false);
                c.setRequestProperty("Accept", "application/json,text/plain,*/*");
                c.setRequestProperty("Accept-Encoding", "identity");
                c.setRequestProperty("Connection", "keep-alive");
                c.setRequestProperty("Cache-Control", "no-cache");
                c.setRequestProperty("Pragma", "no-cache");
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 SunnyIPTV/0.9.2");
                for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());

                if ("POST".equals(method)) {
                    c.setDoOutput(true);
                    c.setRequestMethod("POST");
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    byte[] data = (payload == null ? "" : payload).getBytes(StandardCharsets.UTF_8);
                    c.setFixedLengthStreamingMode(data.length);
                    try (OutputStream out = c.getOutputStream()) { out.write(data); }
                } else c.setRequestMethod("GET");

                int code = c.getResponseCode();
                if (isRedirect(code)) {
                    String location = c.getHeaderField("Location");
                    if (location == null || location.trim().isEmpty()) throw new IOException("HTTP_" + code + "_NO_LOCATION");
                    current = new URL(current, location);
                    if (code == HttpURLConnection.HTTP_SEE_OTHER) { method = "GET"; payload = null; }
                    continue;
                }

                InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
                String body = read(in);
                if (code < 200 || code >= 300) throw new IOException("HTTP_" + code + (body.isEmpty() ? "" : ": " + shorten(body)));
                return body;
            } finally { if (c != null) c.disconnect(); }
        }
        throw new IOException("TOO_MANY_REDIRECTS");
    }

    private static boolean isRedirect(int code) { return code == 301 || code == 302 || code == 303 || code == 307 || code == 308; }

    private static boolean isRetryable(IOException e) {
        if (e instanceof SocketTimeoutException) return true;
        String m = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        return m.contains("software caused connection abort") || m.contains("connection abort") ||
                m.contains("connection reset") || m.contains("unexpected end") || m.contains("eof") ||
                m.contains("broken pipe") || m.contains("connection refused") || m.contains("timed out") || m.contains("timeout");
    }

    private static IOException friendly(String rawUrl, IOException cause) {
        String host = "server";
        try {
            URL u = new URL(rawUrl);
            host = u.getHost();
            if (u.getPort() > 0) host += ":" + u.getPort();
        } catch (Exception ignored) {}
        String reason = cause == null || cause.getMessage() == null ? "network error" : cause.getMessage();
        return new IOException("Connection to " + host + " failed: " + reason, cause);
    }

    private static String shorten(String s) {
        String clean = s.replaceAll("\\s+", " ").trim();
        return clean.length() <= 180 ? clean : clean.substring(0, 180) + "…";
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream input = in; ByteArrayOutputStream b = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = input.read(buf)) >= 0) {
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("index_cancelled");
                b.write(buf, 0, n);
                if (b.size() > MAX_BODY) throw new IOException("response_too_large");
            }
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
