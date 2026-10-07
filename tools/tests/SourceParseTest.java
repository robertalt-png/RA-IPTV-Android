package com.nenotv.player.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Checks core/SourceParse.java against the cases shared with the website parser (tools/tests/source-parse-cases.json). */
public final class SourceParseTest {
    public static void main(String[] args) throws Exception {
        Object root = new Json(new String(Files.readAllBytes(Paths.get("tools/tests/source-parse-cases.json")), StandardCharsets.UTF_8)).value();
        int failed = 0, count = 0;
        for (Object o : (List<?>) root) {
            Map<?, ?> c = (Map<?, ?>) o; count++;
            SourceParse.Result r = SourceParse.parse((String) c.get("text"), Boolean.TRUE.equals(c.get("ocr")));
            Map<String, String> got = new HashMap<>();
            got.put("type", r.type); got.put("server", r.server); got.put("username", r.username); got.put("password", r.password); got.put("m3u", r.m3u); got.put("reason", r.reason);
            for (Map.Entry<?, ?> e : ((Map<?, ?>) c.get("expect")).entrySet())
                if (!e.getValue().equals(got.get((String) e.getKey()))) { failed++; System.out.println("FAIL " + c.get("name") + ": " + e.getKey() + "=" + got.get((String) e.getKey()) + " expected " + e.getValue()); }
        }
        if (failed > 0) throw new AssertionError(failed + " failures");
        System.out.println("Source parse (Java): " + count + " cases passed");
    }

    /** Minimal JSON reader for the test file (objects, arrays, strings, booleans). */
    static final class Json {
        final String s; int i;
        Json(String s) { this.s = s; }
        Object value() {
            ws(); char c = s.charAt(i);
            if (c == '{') { i++; Map<String, Object> m = new LinkedHashMap<>(); ws(); if (s.charAt(i) == '}') { i++; return m; }
                while (true) { ws(); String k = str(); ws(); i++; m.put(k, value()); ws(); if (s.charAt(i++) == '}') return m; } }
            if (c == '[') { i++; List<Object> l = new ArrayList<>(); ws(); if (s.charAt(i) == ']') { i++; return l; }
                while (true) { l.add(value()); ws(); if (s.charAt(i++) == ']') return l; } }
            if (c == '"') return str();
            if (s.startsWith("true", i)) { i += 4; return true; }
            if (s.startsWith("false", i)) { i += 5; return false; }
            throw new IllegalStateException("Unsupported JSON at " + i);
        }
        void ws() { while (Character.isWhitespace(s.charAt(i))) i++; }
        String str() {
            StringBuilder b = new StringBuilder(); i++;
            while (true) { char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                char e = s.charAt(i++);
                if (e == 'n') b.append('\n'); else if (e == 't') b.append('\t');
                else if (e == 'u') { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                else b.append(e); }
        }
    }
}
