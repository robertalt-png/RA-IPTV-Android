package com.nenotv.player;

import android.content.Context;
import com.nenotv.player.model.MediaEntry;
import com.nenotv.player.storage.SearchIndexStore;
import java.util.*;

/** Master search (0.14.24): every language, scope per section, app language first. */
final class SearchChecks {
    private SearchChecks() {}

    static MediaEntry item(String id, String type, String name, String group) {
        MediaEntry e = new MediaEntry();
        e.id = id; e.type = type; e.name = name; e.group = group;
        if ("series".equals(type)) e.seriesId = id;
        return e;
    }

    static void run(Context c) throws Exception {
        String profile = "qa-search-" + UUID.randomUUID();
        MediaEntry silo = item("s1", "series", "Silo", "EN | Series");
        MediaEntry siloNl = item("s2", "series", "Silo de serie", "NL | Series");
        MediaEntry other = item("s3", "series", "Severance", "EN | Series");
        MediaEntry movie = item("v1", "vod", "Silo Story", "EN | Movies");
        MediaEntry channel = item("l1", "live", "Silo TV", "NL | Zenders");
        try (SearchIndexStore index = new SearchIndexStore(c)) {
            index.replaceSection(profile, "series", Arrays.asList(silo, siloNl, other));
            index.replaceSection(profile, "vod", Collections.singletonList(movie));
            index.replaceSection(profile, "live", Collections.singletonList(channel));
            try {
                // "All": every section and every language, app language (nl) first.
                List<MediaEntry> all = index.searchAll(profile, "", "silo", "nl", 100);
                Set<String> names = new HashSet<>();
                for (MediaEntry e : all) names.add(e.name);
                check(names.containsAll(Arrays.asList("Silo", "Silo de serie", "Silo Story", "Silo TV")) && !names.contains("Severance"),
                    "Master search missed results or matched unrelated titles: " + names);
                boolean seenOther = false;
                for (MediaEntry e : all) {
                    boolean preferred = "nl".equals(com.nenotv.player.ContentLanguage.detectTag(e));
                    if (!preferred) seenOther = true;
                    check(!(preferred && seenOther), "App-language results are not listed first: " + all.get(0).name);
                }
                // Series only: still every language, so the English "Silo" is found while Dutch is the app language.
                List<MediaEntry> series = index.searchAll(profile, "series", "silo", "nl", 100);
                Set<String> seriesNames = new HashSet<>();
                for (MediaEntry e : series) { check("series".equals(e.type), "Series scope returned " + e.type); seriesNames.add(e.name); }
                check(seriesNames.contains("Silo") && seriesNames.contains("Silo de serie"), "Series scope hid a language: " + seriesNames);
                // Movies and channels scopes stay inside their section.
                for (MediaEntry e : index.searchAll(profile, "vod", "silo", "nl", 100)) check("vod".equals(e.type), "Movies scope returned " + e.type);
                for (MediaEntry e : index.searchAll(profile, "live", "silo", "nl", 100)) check("live".equals(e.type), "Channels scope returned " + e.type);
            } finally {
                for (String section : new String[]{"series", "vod", "live"}) index.replaceSection(profile, section, Collections.emptyList());
            }
        }
        providerNames(c);
    }

    /** 0.14.34: provider names where the title carries the language and the category another one; no matches inside words. */
    static void providerNames(Context c) throws Exception {
        String profile = "qa-search-names-" + UUID.randomUUID();
        MediaEntry gr = item("p1", "series", "[GR] Silo", "|EN| SERIES 2023");
        MediaEntry ar = item("p2", "series", "|AR| Silo (2023)", "|EN| SERIES 2023");
        MediaEntry nl = item("p3", "series", "|NL| Silo", "|EN| SERIES 2023");
        MediaEntry multi = item("p4", "series", "|MULTI| Silo (2023)", "|EN| SERIES 2023");
        MediaEntry mind = item("p5", "series", "How to Change Your Mind", "|EN| SERIES 2022");
        mind.plot = "A docuseries about psilocybin and other psychedelics.";
        MediaEntry asilo = item("p6", "vod", "Una notte all'asilo", "|IT| FILM");
        MediaEntry tight = item("p8", "vod", "[DE]Silo Doku", "|DE| FILM");
        MediaEntry plot = item("p7", "vod", "Farm Story", "|EN| FILM");
        plot.plot = "A farmer guards the old silo.";
        try (SearchIndexStore index = new SearchIndexStore(c)) {
            index.replaceSection(profile, "series", Arrays.asList(gr, ar, nl, multi, mind));
            index.replaceSection(profile, "vod", Arrays.asList(asilo, plot, tight));
            try {
                List<MediaEntry> all = index.searchAll(profile, "", "silo", "nl", 100);
                List<String> names = new ArrayList<>();
                for (MediaEntry e : all) names.add(e.name);
                check(!all.isEmpty() && "|NL| Silo".equals(all.get(0).name), "Dutch title not first: " + names);
                check(names.size() > 1 && "|MULTI| Silo (2023)".equals(all.get(1).name), "MULTI not second: " + names);
                check(!names.contains("Una notte all'asilo") && !names.contains("How to Change Your Mind"), "Matched inside a word: " + names);
                check(names.contains("Farm Story"), "Whole word in the description no longer found: " + names);
                check(names.contains("[DE]Silo Doku"), "Title glued to its language tag not found: " + names);
                check(names.containsAll(Arrays.asList("[GR] Silo", "|AR| Silo (2023)")), "Other languages dropped: " + names);
            } finally {
                for (String section : new String[]{"series", "vod"}) index.replaceSection(profile, section, Collections.emptyList());
            }
        }
    }

    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
