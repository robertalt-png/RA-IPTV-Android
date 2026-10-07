package com.nenotv.player.core;

import com.nenotv.player.model.MediaEntry;
import java.util.*;

public final class LanguageGroupsTest {
    static int checks = 0;
    static void ok(boolean b, String m) { if (!b) throw new AssertionError(m); checks++; }
    static MediaEntry e(String id, String type, String name, String year, String group) { MediaEntry x = new MediaEntry(); x.id = id; x.type = type; x.name = name; x.year = year; x.group = group; return x; }

    public static void main(String[] args) {
        ok(LanguageGroups.titleKey("|NL| Silo (2023) FHD").equals("silo"), "key nl");
        ok(LanguageGroups.titleKey("[GR] Silo").equals("silo"), "key gr");
        ok(LanguageGroups.titleKey("NL: Silo").equals("silo"), "key colon");
        ok(LanguageGroups.titleKey("Silo (2023)").equals("silo"), "key year");
        ok(LanguageGroups.titleKey("Amélie").equals("amelie"), "diacritics");
        ok(!LanguageGroups.titleKey("Silo 2").equals("silo"), "sequel kept apart");

        List<MediaEntry> found = Arrays.asList(
            e("1", "series", "[GR] Silo", "2023", "|EN| SERIES"),
            e("2", "series", "|AR| Silo (2023)", "2023", "|EN| SERIES"),
            e("3", "series", "|NL| Silo", "2023", "|EN| SERIES"),
            e("4", "series", "|MULTI| Silo (2023)", "2023", "|EN| SERIES"),
            e("5", "series", "|DE| Silo", "2023", "|DE| SERIEN"),
            e("6", "vod", "|NL| Silo", "2016", "|NL| FILMS"),
            e("7", "series", "|EN| The Office", "2005", "|EN| SERIES"),
            e("8", "series", "|EN| The Office", "2001", "|EN| SERIES"),
            e("9", "series", "|NL| Mystery Show", "", "|NL| SERIES"),
            e("10", "series", "|EN| Mystery Show", "", "|EN| SERIES"),
            e("11", "live", "NPO 1", "", "|NL| ZENDERS"),
            e("12", "live", "NPO 1", "", "|NL| ZENDERS"));
        List<LanguageGroups.Group> g = LanguageGroups.group(found, "nl");
        ok(g.get(0).versions.size() == 5, "five Silo series versions in one card: " + g.get(0).versions.size());
        ok(g.get(0).shown.id.equals("3"), "Dutch version shown: " + g.get(0).shown.name);
        ok(g.get(0).versions.get(1).id.equals("4"), "MULTI second");
        ok(g.size() == 1 + 1 + 2 + 2 + 2, "film, both Offices, both mystery shows and channels stay apart: " + g.size());
        ok(g.get(1).shown.id.equals("6"), "film with the same name stays separate");
        List<LanguageGroups.Group> en = LanguageGroups.group(found, "en");
        ok(en.get(0).shown.id.equals("4"), "no English version: MULTI shown, got " + en.get(0).shown.name);
        ok(LanguageGroups.group(null, "nl").isEmpty(), "null list");
        System.out.println("Language groups: " + checks + " checks passed");
    }
}
