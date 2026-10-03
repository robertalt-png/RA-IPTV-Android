package com.nenotv.admin;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class ApiClient {
    public static final String BASE = "https://nenotv.com/wp-json/nenotv-dashboard/v1";

    public static final class ApiException extends Exception {
        public final int status;
        public ApiException(int status, String message) { super(message); this.status = status; }
    }

    public static String pair(String code) throws Exception {
        HttpURLConnection c = open(BASE + "/admin-pair", "POST");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setDoOutput(true);
        JSONObject body = new JSONObject().put("code", code);
        try (OutputStream out = c.getOutputStream()) {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        String raw = read(c);
        JSONObject json = new JSONObject(raw);
        if (!json.optBoolean("ok", false) || json.optString("token").isEmpty()) {
            throw new ApiException(c.getResponseCode(), json.optString("error", "Koppelen mislukt"));
        }
        return json.getString("token");
    }

    public static JSONObject dashboard(String token, int days) throws Exception {
        HttpURLConnection c = open(BASE + "/admin-app?days=" + days, "GET");
        c.setRequestProperty("Authorization", "Bearer " + token);
        String raw = read(c);
        return new JSONObject(raw);
    }

    private static HttpURLConnection open(String url, String method) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(18000);
        c.setRequestMethod(method);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "NenoTV-Admin/0.1.0 Android");
        c.setUseCaches(false);
        return c;
    }

    private static String read(HttpURLConnection c) throws Exception {
        int status = c.getResponseCode();
        InputStream in = status >= 200 && status < 300 ? c.getInputStream() : c.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
        }
        if (status < 200 || status >= 300) {
            String message = "Serverfout " + status;
            try {
                JSONObject err = new JSONObject(sb.toString());
                message = err.optString("message", err.optString("error", message));
            } catch (Exception ignored) {}
            throw new ApiException(status, message);
        }
        return sb.toString();
    }
}
