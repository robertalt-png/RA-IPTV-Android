package com.nenotv.player.storage;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import com.nenotv.player.BuildConfig;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/**
 * Sends app crashes to sunnyiptv.com so they can be read without the tester's phone.
 * Only technical data leaves the device: the cleaned stack trace, app and Android version, phone model,
 * the open screen and the public device code. URLs are cut to their host, so provider accounts,
 * passwords and stream links never leave the device; e-mail addresses and long tokens are masked.
 */
public final class CrashReporter {
    private CrashReporter() {}
    static final String ENDPOINT = "https://sunnyiptv.com/wp-json/sunnyiptv-monitor/v1/crash";
    private static final String PREFS = "crash_reporter";
    private static volatile String screen = "";
    private static volatile boolean installed = false;

    /** Remembers the visible screen and sends a crash that could not be sent last time. */
    public static synchronized void install(Application app) {
        if (installed || app == null) return;
        installed = true;
        try {
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityCreated(Activity a, Bundle b) { screen = a.getClass().getSimpleName() + " (created)"; }
                @Override public void onActivityStarted(Activity a) {}
                @Override public void onActivityResumed(Activity a) { screen = a.getClass().getSimpleName(); }
                @Override public void onActivityPaused(Activity a) {}
                @Override public void onActivityStopped(Activity a) {}
                @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
                @Override public void onActivityDestroyed(Activity a) {}
            });
        } catch (Throwable ignored) {}
        final Context c = app;
        Thread t = new Thread(() -> sendPending(c), "SunnyIPTV-crash-upload");
        t.setDaemon(true);
        t.start();
    }

    /** Called from the uncaught-exception handler: store first, then try to send within 2.5 seconds. */
    public static void capture(Context c, Thread thread, Throwable error) {
        try {
            JSONObject report = build(c, thread, error);
            prefs(c).edit().putString("pending", report.toString()).commit();
            Thread t = new Thread(() -> sendPending(c), "SunnyIPTV-crash-upload-now");
            t.setDaemon(true);
            t.start();
            t.join(2500);
        } catch (Throwable ignored) {}
    }

    static JSONObject build(Context c, Thread thread, Throwable error) throws Exception {
        StringWriter sw = new StringWriter();
        error.printStackTrace(new PrintWriter(sw));
        String trace = clean(sw.toString());
        if (trace.length() > 16000) trace = trace.substring(0, 16000);
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        JSONObject o = new JSONObject();
        o.put("app_version", BuildConfig.VERSION_NAME);
        o.put("version_code", BuildConfig.VERSION_CODE);
        o.put("android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        o.put("model", (Build.MANUFACTURER + " " + Build.MODEL).trim());
        o.put("screen", screen);
        o.put("thread", thread == null ? "" : clean(thread.getName()));
        o.put("type", error.getClass().getName());
        o.put("root_type", root.getClass().getName());
        o.put("message", clean(String.valueOf(root.getMessage())));
        o.put("trace", trace);
        o.put("splits", splits(c));
        o.put("at", System.currentTimeMillis());
        try { o.put("public_device_id", new EntitlementStore(c).publicDeviceId()); } catch (Throwable ignored) { o.put("public_device_id", ""); }
        return o;
    }

    static String splits(Context c) {
        try {
            String[] names = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).splitNames;
            return names == null ? "" : String.join(",", names);
        } catch (Throwable e) { return "?"; }
    }

    /** Removes anything that could identify a provider account or a person. */
    public static String clean(String s) {
        if (s == null) return "";
        s = s.replaceAll("(?i)\\b([a-z][a-z0-9+.-]{1,15})://(?:[^\\s/@]*@)?([^\\s/:?#]+)[^\\s]*", "$1://$2/…");
        s = s.replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "[email]");
        s = s.replaceAll("(?i)(user(name)?|pass(word)?|token|key|auth)=[^\\s&,;]+", "$1=[x]");
        s = s.replaceAll("\\b[A-Za-z0-9_-]{32,}\\b", "[id]");
        return s;
    }

    static void sendPending(Context c) {
        try {
            SharedPreferences p = prefs(c);
            String pending = p.getString("pending", "");
            if (pending == null || pending.isEmpty()) return;
            byte[] body = pending.getBytes(StandardCharsets.UTF_8);
            HttpURLConnection h = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            try {
                h.setConnectTimeout(2000);
                h.setReadTimeout(3000);
                h.setRequestMethod("POST");
                h.setDoOutput(true);
                h.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                h.setRequestProperty("User-Agent", "SunnyIPTV/" + BuildConfig.VERSION_NAME + " Android");
                h.setFixedLengthStreamingMode(body.length);
                try (OutputStream os = h.getOutputStream()) { os.write(body); }
                int code = h.getResponseCode();
                // Accepted, or refused for good (too large, rate limited): never resend the same report forever.
                if ((code >= 200 && code < 300) || code == 400 || code == 413 || code == 429)
                    p.edit().remove("pending").apply();
            } finally { h.disconnect(); }
        } catch (Throwable offline) { /* kept for the next start */ }
    }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
}
