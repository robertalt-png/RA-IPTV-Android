package com.nenotv.player.core;

import java.util.*;

public final class GuideMatcherTest {
    static int checks = 0;
    static void same(String a, String b) { String x = GuideMatcher.norm(a), y = GuideMatcher.norm(b); if (x.isEmpty() || !x.equals(y)) throw new AssertionError("Should match: '" + a + "' (" + x + ") vs '" + b + "' (" + y + ")"); checks++; }
    static void differ(String a, String b) { String x = GuideMatcher.norm(a), y = GuideMatcher.norm(b); if (x.equals(y)) throw new AssertionError("Should differ: '" + a + "' vs '" + b + "' (" + x + ")"); checks++; }

    public static void main(String[] args) {
        same("NPO 1 HD", "NPO1.nl");
        same("NL: NPO 1 FHD", "NPO 1");
        same("[NL] SBS 6", "SBS6.nl");
        same("NL | RTL 4 HD", "RTL 4");
        same("|NL| Veronica", "Veronica");
        same("BBC One HD", "BBC One");
        same("DE: Das Erste HD", "Das Erste");
        same("Canal+ Sport", "Canal Plus Sport");
        same("ZDF_HD", "ZDF");
        same("Arte (FR) HEVC", "arte (fr)");
        same("Télé Bruxelles", "Tele Bruxelles");
        differ("Ziggo Sport 2 HD", "Ziggo Sport");
        differ("RTL 4 +1", "RTL 4");
        differ("NPO 1", "NPO 2");
        differ("ESPN", "ESPN 2");
        differ("TV Oost", "Oost");
        if (!GuideMatcher.norm("HD").isEmpty() && GuideMatcher.norm("HD").length() > 2) throw new AssertionError("Quality-only name kept text");
        checks++;
        List<String> keys = GuideMatcher.channelKeys("NPO1.nl", "NPO 1 HD", "NL: NPO 1 HD");
        if (!keys.get(0).equals("id:npo1.nl") || !keys.contains("norm:npo1")) throw new AssertionError("Channel key order: " + keys);
        checks++;
        List<String> guide = GuideMatcher.guideKeys("NPO1.nl", Arrays.asList("NPO 1", "NPO1"));
        if (!guide.contains("id:npo1.nl") || !guide.contains("name:npo 1") || !guide.contains("norm:npo1")) throw new AssertionError("Guide keys: " + guide);
        checks++;
        System.out.println("Guide matcher: " + checks + " checks passed");
    }
}
