package com.nenotv.player.core;

import java.util.*;

public final class GuideSourcesTest {
    static int checks = 0;
    static void ok(boolean b, String m) { if (!b) throw new AssertionError(m); checks++; }
    public static void main(String[] args) {
        ok(GuideSources.split("https://a.example/guide.xml.gz, http://b.example/epg.xml").equals(Arrays.asList("https://a.example/guide.xml.gz", "http://b.example/epg.xml")), "comma list");
        ok(GuideSources.split("ftp://x/y, file:///sdcard/a.xml, ").isEmpty(), "only http(s)");
        ok(GuideSources.split(null).isEmpty() && GuideSources.split("").isEmpty(), "empty");
        List<String> m = GuideSources.merge(Collections.singletonList("http://panel/xmltv.php?username=a&password=b"), Arrays.asList("https://extra/one.xml", "http://panel/xmltv.php?username=a&password=b"), Collections.singletonList("https://extra/two.xml,https://extra/one.xml"));
        ok(m.equals(Arrays.asList("http://panel/xmltv.php?username=a&password=b", "https://extra/one.xml", "https://extra/two.xml")), "merge order and dedupe " + m);
        List<String> many = new ArrayList<>(); for (int i = 0; i < 10; i++) many.add("https://g/" + i);
        ok(GuideSources.merge(many).size() == GuideSources.MAX, "cap");
        ok(GuideSources.merge((List<String>) null).isEmpty(), "null list");
        System.out.println("Guide sources: " + checks + " checks passed");
    }
}
