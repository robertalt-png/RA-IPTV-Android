package com.nenotv.player.core;

import java.util.*;

/** G6: the XMLTV guide addresses of one source, in order of trust, without duplicates. */
public final class GuideSources {
    private GuideSources() {}
    public static final int MAX = 6;

    /** Splits "a.xml.gz, b.xml" (an M3U url-tvg may list several guides) and keeps http(s) addresses only. */
    public static List<String> split(String value) {
        List<String> out = new ArrayList<>();
        if (value == null) return out;
        for (String part : value.split("[,\\s]+")) {
            String u = part.trim();
            String lower = u.toLowerCase(Locale.ROOT);
            if (lower.startsWith("http://") || lower.startsWith("https://")) out.add(u);
        }
        return out;
    }

    /** Joins lists in order: the source's own guide first, then the user's extra guides. */
    @SafeVarargs
    public static List<String> merge(List<String>... lists) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (List<String> l : lists) if (l != null) for (String u : l) for (String x : split(u)) if (seen.size() < MAX) seen.add(x);
        return new ArrayList<>(seen);
    }
}
