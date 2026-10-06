package com.nenotv.player.core;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Links a playlist channel to an XMLTV guide channel (G1).
 * Order: exact tvg-id, exact name, then a normalised name without quality tags (HD, FHD, 4K...),
 * country prefixes (NL:, [NL], NL |) and punctuation, so "NL: NPO 1 HD" finds the guide of "NPO1.nl".
 * Pure Java: unit-tested without Android (tools/tests/GuideMatcherTest.java).
 */
public final class GuideMatcher {
    private GuideMatcher() {}

    private static final Pattern PREFIX = Pattern.compile("^\\s*(?:[\\[(|]\\s*[a-z]{2,3}\\s*[\\])|]|[a-z]{2,3}\\s*[:|])\\s*");
    private static final Pattern QUALITY = Pattern.compile("\\b(?:uhd|fhd|hd|sd|4k|8k|hevc|h\\.?265|h\\.?264|1080[pi]?|720p|2160p|50fps|60fps|backup|raw|vip)\\b");
    private static final Pattern COUNTRY_DOMAIN = Pattern.compile("\\.(?:[a-z]{2}|uk|com|tv)$");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

    /** Comparable form of a channel name or guide id; "" when nothing meaningful is left. */
    public static String norm(String raw) {
        if (raw == null) return "";
        String s = Normalizer.normalize(raw.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        s = MARKS.matcher(s).replaceAll("");
        s = COUNTRY_DOMAIN.matcher(s).replaceAll("");
        String before;
        do { before = s; s = PREFIX.matcher(s).replaceFirst(""); } while (!s.equals(before) && !s.isEmpty());
        s = s.replace('_', ' ').replace('-', ' ').replace('.', ' ');
        s = QUALITY.matcher(s).replaceAll(" ");
        s = s.replace("+", " plus ");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) { char c = s.charAt(i); if (Character.isLetterOrDigit(c)) out.append(c); }
        return out.toString();
    }

    /** Lookup keys for a playlist channel, best first. */
    public static List<String> channelKeys(String tvgId, String tvgName, String name) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        add(keys, "id:", lower(tvgId));
        add(keys, "name:", lower(tvgName));
        add(keys, "name:", lower(name));
        add(keys, "id:", lower(tvgName));
        add(keys, "norm:", norm(tvgId));
        add(keys, "norm:", norm(tvgName));
        add(keys, "norm:", norm(name));
        return new ArrayList<>(keys);
    }

    /** Keys under which an XMLTV channel can be found. */
    public static List<String> guideKeys(String channelId, Collection<String> displayNames) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        add(keys, "id:", lower(channelId));
        if (displayNames != null) for (String n : displayNames) add(keys, "name:", lower(n));
        add(keys, "norm:", norm(channelId));
        if (displayNames != null) for (String n : displayNames) add(keys, "norm:", norm(n));
        return new ArrayList<>(keys);
    }

    private static String lower(String s) { return s == null ? "" : s.trim().toLowerCase(Locale.ROOT); }
    private static void add(Set<String> keys, String prefix, String value) { if (value != null && !value.isEmpty()) keys.add(prefix + value); }
}
