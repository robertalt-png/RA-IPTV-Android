package com.nenotv.player.storage;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Text normalisation for the search index, free of Android types so it can be verified on a plain JVM
 * (tools/tests/language). Behaviour is identical to the former inline helpers in SearchIndexStore;
 * the patterns are compiled once instead of on every call, which ran several times per catalogue item.
 */
final class IndexText {
    private IndexText(){}
    private static final Pattern URL=Pattern.compile("(?i)(?:https?|rtsp|rtmp)://\\S+");
    private static final Pattern WHITESPACE=Pattern.compile("\\s+");

    static String safe(String s){return s==null?"":s;}

    /** Removes stream/web URLs from free text. Without "://" the pattern cannot match, so skip it. */
    static String metadata(String s){
        String value=safe(s);
        return value.contains("://")?URL.matcher(value).replaceAll(""):value;
    }

    static String norm(String s){return WHITESPACE.matcher(safe(s).toLowerCase(Locale.ROOT).replace('|',' ')).replaceAll(" ").trim();}
}
