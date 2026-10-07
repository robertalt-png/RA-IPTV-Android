package com.nenotv.player.core;

import com.nenotv.player.ContentLanguage;
import com.nenotv.player.model.MediaEntry;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/**
 * One card per film or series instead of one per language ("[GR] Silo", "|NL| Silo", "Silo (2023) MULTI"…).
 * Only films and series are grouped, and only when type, cleaned title and year are equal; a version without
 * a year is never merged, so two different titles with the same name stay apart. The card shows the version
 * in the app language, else MULTI, else the first one.
 */
public final class LanguageGroups {
    private LanguageGroups() {}

    public static final class Group {
        public final MediaEntry shown;
        public final List<MediaEntry> versions;
        Group(MediaEntry shown, List<MediaEntry> versions) { this.shown = shown; this.versions = versions; }
    }

    private static final Pattern LEAD = Pattern.compile("^\\s*(?:[\\[(|│┃¦｜]\\s*[^\\])|│┃¦｜]{1,18}\\s*[\\])|│┃¦｜]|[A-Za-z]{2,5}\\s*[:|\\-–]\\s+|[A-Za-z]{2,5}:)\\s*");
    private static final Pattern YEAR_TAIL = Pattern.compile("[\\s\\-·|]*[\\[(]?\\s*((?:19|20)\\d{2})\\s*[\\])]?\\s*$");
    private static final Pattern QUALITY = Pattern.compile("(?i)\\b(4k|uhd|fhd|hd|sd|hdr|hevc|multi|multiaudio|dual|vostfr|vost|vo)\\b");
    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

    /** Language from the title alone ("|NL| Silo" → nl, "[GR] Silo" → gr, "Silo MULTI" → multi), or "". */
    public static String titleLanguage(MediaEntry e) {
        String l = language(e, false);
        return l;
    }

    /** Language of one version as its title shows it ("|NL| Silo" → nl), else the stored detection. */
    public static String language(MediaEntry e) { return language(e, true); }

    private static String language(MediaEntry e, boolean fallback) {
        String name = e == null || e.name == null ? "" : e.name;
        Matcher m = LEAD.matcher(name);
        if (m.find()) {
            String token = NON_ALNUM.matcher(m.group()).replaceAll(" ").trim();
            String x = ContentLanguage.categoryTag(token);
            if (!x.isEmpty()) return x;
            // A tag in the title that is no known language (e.g. GR) still belongs to the title, not to the category.
            if (token.matches("[A-Za-z]{2,5}")) return token.toLowerCase(Locale.ROOT);
        }
        if (QUALITY.matcher(name).find() && name.toLowerCase(Locale.ROOT).matches(".*\\b(multi|multiaudio|dual)\\b.*")) return "multi";
        return fallback ? ContentLanguage.detectTag(e) : "";
    }

    static String year(MediaEntry e) {
        String y = e.displayYear();
        if (y != null && y.matches("(?:19|20)\\d{2}")) return y;
        Matcher m = YEAR_TAIL.matcher(e.name == null ? "" : e.name);
        return m.find() ? m.group(1) : "";
    }

    /** "|NL| Silo (2023) FHD" → "silo". */
    public static String titleKey(String name) {
        String s = name == null ? "" : name;
        for (int i = 0; i < 3; i++) { Matcher m = LEAD.matcher(s); if (!m.find()) break; s = s.substring(m.end()); }
        s = QUALITY.matcher(s).replaceAll(" ").trim();
        s = YEAR_TAIL.matcher(s).replaceAll("");
        s = MARKS.matcher(Normalizer.normalize(s, Normalizer.Form.NFD)).replaceAll("");
        return NON_ALNUM.matcher(s.toLowerCase(Locale.ROOT)).replaceAll("");
    }

    static String key(MediaEntry e) {
        if (e == null || !("vod".equals(e.type) || "series".equals(e.type))) return "";
        String t = titleKey(e.name), y = year(e);
        if (t.isEmpty() || y.isEmpty()) return "";
        return e.type + "|" + t + "|" + y;
    }

    /** 0 app language, 1 MULTI, 2 unknown, 3 another language. */
    static int rank(MediaEntry e, String preferred) {
        String x = language(e);
        if (x.isEmpty()) return 2;
        if ("multi".equals(x)) return 1;
        return preferred != null && preferred.equalsIgnoreCase(x) ? 0 : 3;
    }

    /** Groups in the order of the list given (each group where its first version stood). */
    public static List<Group> group(List<MediaEntry> items, String preferredLanguage) {
        LinkedHashMap<String, List<MediaEntry>> byKey = new LinkedHashMap<>();
        int solo = 0;
        for (MediaEntry e : items == null ? Collections.<MediaEntry>emptyList() : items) {
            String k = key(e);
            if (k.isEmpty()) k = "solo:" + (solo++);
            byKey.computeIfAbsent(k, x -> new ArrayList<>()).add(e);
        }
        List<Group> out = new ArrayList<>();
        for (List<MediaEntry> versions : byKey.values()) {
            List<MediaEntry> sorted = new ArrayList<>(versions);
            sorted.sort(Comparator.comparingInt(v -> rank(v, preferredLanguage)));
            out.add(new Group(sorted.get(0), Collections.unmodifiableList(sorted)));
        }
        return out;
    }
}
