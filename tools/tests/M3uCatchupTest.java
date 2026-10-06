package com.nenotv.player.core;

import com.nenotv.player.model.MediaEntry;
import java.util.List;

public final class M3uCatchupTest {
    static int checks = 0;
    static void ok(boolean b, String m) { if (!b) throw new AssertionError(m); checks++; }
    public static void main(String[] args) {
        String text = "#EXTM3U url-tvg=\"https://g/epg.xml\" catchup=\"append\" catchup-source=\"?utc={utc}\" catchup-days=\"5\"\n"
            + "#EXTINF:-1 tvg-id=\"npo1.nl\",NPO 1\nhttps://live/npo1.m3u8\n"
            + "#EXTINF:-1 catchup=\"flussonic\" catchup-days=\"2\",RTL 4\nhttps://fs/rtl4/index.m3u8\n"
            + "#EXTINF:-1 catchup=\"0\",SBS 6\nhttps://live/sbs6.m3u8\n";
        List<MediaEntry> l = M3uParser.parse(text).items;
        ok(l.size() == 3, "three channels");
        ok(l.get(0).catchup && "append".equals(l.get(0).catchupType) && "?utc={utc}".equals(l.get(0).catchupSource) && l.get(0).catchupDays == 5, "playlist catch-up inherited");
        ok(l.get(1).catchup && "flussonic".equals(l.get(1).catchupType) && l.get(1).catchupDays == 2, "own catch-up wins");
        ok(!l.get(2).catchup && l.get(2).catchupType.isEmpty(), "catchup=0 switches off");
        ok("https://g/epg.xml".equals(M3uParser.parse(text).epgUrl), "guide address still read");
        System.out.println("M3U catch-up: " + checks + " checks passed");
    }
}
