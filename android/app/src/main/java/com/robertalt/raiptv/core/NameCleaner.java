package com.nenotv.player.core;

import java.util.regex.Pattern;

/**
 * Presentation cleanup of provider decorations that carry no information for the viewer:
 * record/catch-up markers ("⏺rec", "🔴 REC", "ʳᵉᶜ", "[REC]") — the app already shows catch-up as "↶ 3D" —
 * and emoji used as decoration. The raw name stays untouched for search, matching and playback.
 */
public final class NameCleaner {
    private NameCleaner() {}

    // A record symbol, optionally followed by "rec" in normal, small-caps or superscript letters.
    private static final String SYMBOL = "(?:\\u23FA\\uFE0F?|\\uD83D\\uDD34|\\uD83D\\uDFE0|\\uD83D\\uDD18|\\u25CF|\\u25C9|\\u2B55|\\u26AB|\\u26AA|\\uD83D\\uDFE1|\\uD83D\\uDCF9|\\uD83C\\uDFA5)";
    private static final String REC = "(?:rec|REC|Rec|\\u02B3\\u1D49\\u1D9C|\\u1D3F\\u1D31\\u1D9C|\\u1D3F\\u1D31\\u1D04|\\u0280\\u1D07\\u1D04)";
    private static final Pattern MARKER = Pattern.compile("\\s*(?:" + SYMBOL + "\\s*" + REC + "?|[\\[(]\\s*" + REC + "\\s*[\\])]|\\u02B3\\u1D49\\u1D9C|\\u1D3F\\u1D31\\u1D9C)\\s*");
    private static final Pattern SPACES = Pattern.compile("\\s{2,}");

    public static String clean(String name) {
        if (name == null) return "";
        String out = MARKER.matcher(name).replaceAll(" ");
        out = SPACES.matcher(out).replaceAll(" ").trim();
        return out.isEmpty() ? name.trim() : out;
    }
}
