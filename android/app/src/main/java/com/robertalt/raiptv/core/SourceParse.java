package com.nenotv.player.core;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Source recognition: turns pasted text, a QR code or OCR text from a provider message into Xtream or M3U fields.
 * Same rules as server/sunnyiptv-source-parse.js (website); both are checked against tools/tests/source-parse-cases.json.
 */
public final class SourceParse {
    private SourceParse() {}

    public static final String XTREAM = "XTREAM", M3U = "M3U", UNSUPPORTED = "UNSUPPORTED", NONE = "NONE";

    public static final class Result {
        /** XTREAM, M3U, UNSUPPORTED (reason mac_portal) or NONE. XTREAM may have an empty server: the viewer adds it. */
        public String type = NONE, server = "", username = "", password = "", m3u = "", reason = "";
    }

    private static final String[][] LABELS = {
            {"username", "user name", "username", "gebruikersnaam", "benutzername", "gebruiker", "benutzer", "login", "user"},
            {"password", "password", "passwort", "wachtwoord", "pass", "pwd", "pw"},
            {"server", "server url", "xtream url", "serveradres", "server", "portal", "host", "dns", "url"},
            {"port", "port", "poort"},
            {"mac", "mac address", "mac-adres", "mac"}};
    private static final Pattern MAC = Pattern.compile("\\b([0-9A-F]{2}[:-]){5}[0-9A-F]{2}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"'`]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern STREAM_PATH = Pattern.compile("^/(?:live|movie|series|timeshift)/([^/]+)/([^/]+)/[^/]+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern API_PATH = Pattern.compile("(get|player_api|xmltv|panel_api)\\.php$", Pattern.CASE_INSENSITIVE);

    static String clean(String text) {
        return (text == null ? "" : text)
                .replaceAll("[\\u200B-\\u200D\\u2060\\uFEFF]", "")
                .replaceAll("[\\u00A0\\u2007\\u202F]", " ")
                .replaceAll("[\\u2018\\u2019\\u201A\\u201B]", "'")
                .replaceAll("[\\u201C\\u201D\\u201E]", "\"")
                .replaceAll("\\r\\n?", "\n");
    }

    /** OCR splits URLs with spaces ("http: //host. tv"); a URL never contains spaces, so they are removed after "http". */
    static String joinOcrUrls(String text) {
        StringBuilder out = new StringBuilder();
        Pattern start = Pattern.compile("https?\\s*:", Pattern.CASE_INSENSITIVE);
        for (String line : text.split("\n", -1)) {
            Matcher m = start.matcher(line);
            if (out.length() > 0) out.append('\n');
            out.append(m.find() ? line.substring(0, m.start()) + line.substring(m.start()).replaceAll("\\s+", "") : line);
        }
        return out.toString();
    }

    static String trimUrl(String u) { return u.replaceAll("[.,;:!?)\\]}*'\"]+$", ""); }

    private static URI uri(String u) {
        try {
            URI x = new URI(u);
            String scheme = x.getScheme() == null ? "" : x.getScheme().toLowerCase(Locale.ROOT);
            return (scheme.equals("http") || scheme.equals("https")) && x.getHost() != null ? x : null;
        } catch (Exception e) { return null; }
    }

    /** scheme://host[:port], without the default port, like URL.origin in the browser. */
    static String origin(URI x, String extraPort) {
        String scheme = x.getScheme().toLowerCase(Locale.ROOT);
        int port = x.getPort();
        if (port < 0 && extraPort != null && extraPort.matches("\\d{2,5}")) port = Integer.parseInt(extraPort);
        boolean standard = port < 0 || (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
        return scheme + "://" + x.getHost().toLowerCase(Locale.ROOT) + (standard ? "" : ":" + port);
    }

    private static String param(URI x, String... names) {
        String q = x.getRawQuery();
        if (q == null) return "";
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            String key = decode(eq < 0 ? pair : pair.substring(0, eq)).toLowerCase(Locale.ROOT);
            for (String n : names) if (n.equals(key)) return decode(eq < 0 ? "" : pair.substring(eq + 1)).trim();
        }
        return "";
    }

    private static String decode(String s) {
        try { return URLDecoder.decode(s, StandardCharsets.UTF_8.name()); } catch (Exception e) { return s; }
    }

    static Result fromUrl(String u) {
        URI x = uri(u);
        if (x == null) return null;
        String path = x.getRawPath() == null ? "" : x.getRawPath();
        String user = param(x, "username", "user"), pass = param(x, "password", "pass");
        Result r = new Result();
        if (!user.isEmpty() && !pass.isEmpty() && API_PATH.matcher(path).find()) {
            r.type = XTREAM; r.server = origin(x, null); r.username = user; r.password = pass; return r;
        }
        Matcher m = STREAM_PATH.matcher(path);
        if (m.find()) { r.type = XTREAM; r.server = origin(x, null); r.username = decode(m.group(1)); r.password = decode(m.group(2)); return r; }
        String query = x.getRawQuery() == null ? "" : x.getRawQuery();
        if (path.matches("(?i).*\\.m3u8?$") || query.toLowerCase(Locale.ROOT).contains("m3u") || path.toLowerCase(Locale.ROOT).contains("m3u")) {
            r.type = M3U; r.m3u = x.toString(); return r;
        }
        return null;
    }

    static Map<String, String> labelled(String text) {
        Map<String, String> out = new HashMap<>();
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            for (String[] group : LABELS) {
                String field = group[0];
                if (out.containsKey(field)) continue;
                for (int i = 1; i < group.length; i++) {
                    Matcher m = Pattern.compile("^[^A-Za-z0-9]*" + Pattern.quote(group[i]) + "[^A-Za-z0-9:=]*[:=]\\s*(.+)$", Pattern.CASE_INSENSITIVE).matcher(line);
                    if (!m.find()) continue;
                    String value = m.group(1).trim().replaceAll("^[*_\"'`]+|[*_\"'`]+$", "").trim();
                    if (!value.isEmpty()) { out.put(field, value); break; }
                }
            }
        }
        return out;
    }

    static String serverFrom(String value, String port) {
        if (value == null || value.trim().isEmpty()) return "";
        String v = trimUrl(value.trim());
        if (!v.matches("(?i)^https?://.*")) v = "http://" + v.replaceAll("^/+", "");
        URI x = uri(v);
        return x == null ? "" : origin(x, port);
    }

    public static Result parse(String text, boolean ocr) {
        String t = clean(text);
        if (ocr) t = joinOcrUrls(t);
        List<String> urls = new ArrayList<>();
        Matcher um = URL.matcher(t);
        while (um.find()) urls.add(trimUrl(um.group()));
        Result m3u = null;
        for (String u : urls) {
            Result r = fromUrl(u);
            if (r != null && XTREAM.equals(r.type)) return r;
            if (r != null && M3U.equals(r.type) && m3u == null) m3u = r;
        }
        Map<String, String> f = labelled(t);
        if (f.containsKey("username") && f.containsKey("password")) {
            Result r = new Result();
            r.type = XTREAM; r.username = f.get("username"); r.password = f.get("password");
            r.server = serverFrom(f.get("server"), f.get("port"));
            if (r.server.isEmpty()) for (String u : urls) {
                URI x = uri(u);
                if (x != null && (m3u == null || !u.equals(m3u.m3u))) { r.server = origin(x, f.get("port")); break; }
            }
            return r;
        }
        if (m3u != null) return m3u;
        Result r = new Result();
        boolean macLabel = f.containsKey("mac") && MAC.matcher(f.get("mac")).find();
        if (macLabel || (MAC.matcher(t).find() && Pattern.compile("portal|/c/?", Pattern.CASE_INSENSITIVE).matcher(t).find())) {
            r.type = UNSUPPORTED; r.reason = "mac_portal";
        }
        return r;
    }
}
