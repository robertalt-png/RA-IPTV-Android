package com.nenotv.player;

import android.content.Context;
import com.nenotv.player.model.EpgEntry;
import com.nenotv.player.storage.GuideDatabase;
import com.nenotv.player.storage.GuideRefresher;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** G1 guide database: streaming import, Pro/free windows, matching, keeping the old guide on a broken import. */
final class GuideChecks {
    private GuideChecks() {}

    static String t(long epoch) { return DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.ofEpochSecond(epoch, 0, ZoneOffset.ofHours(2))) + " +0200"; }

    static String xml(long now) {
        long h = 3600, d = 24 * h;
        StringBuilder x = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><tv>");
        x.append("<channel id=\"NPO1.nl\"><display-name>NPO 1</display-name></channel>");
        x.append("<channel id=\"rtl4.nl\"><display-name lang=\"nl\">RTL 4</display-name></channel>");
        long[][] slots = {{now - 8 * d, now - 8 * d + h}, {now - 2 * d, now - 2 * d + h}, {now - h / 2, now + h / 2}, {now + d / 2, now + d / 2 + h}, {now + 5 * d, now + 5 * d + h}, {now + 9 * d, now + 9 * d + h}};
        String[] names = {"Te oud", "Twee dagen geleden", "Journaal nu", "Vanavond", "Over vijf dagen", "Te ver"};
        for (int i = 0; i < slots.length; i++)
            x.append("<programme start=\"").append(t(slots[i][0])).append("\" stop=\"").append(t(slots[i][1])).append("\" channel=\"NPO1.nl\"><title lang=\"en\">EN ").append(names[i]).append("</title><title lang=\"nl\">").append(names[i]).append("</title><desc>Omschrijving</desc></programme>");
        x.append("<programme start=\"").append(t(now - h / 2)).append("\" stop=\"").append(t(now + h)).append("\" channel=\"rtl4.nl\"><title>RTL Nieuws</title></programme>");
        x.append("</tv>");
        return x.toString();
    }

    static void run(Context c) throws Exception {
        GuideDatabase db = GuideDatabase.get(c);
        String source = GuideDatabase.sourceKey("https://qa.invalid/xmltv-" + UUID.randomUUID());
        long now = System.currentTimeMillis() / 1000L;
        byte[] doc = xml(now).getBytes(StandardCharsets.UTF_8);
        try {
            // Pro window: 7 days back and ahead.
            long[] counts = db.importXmltv(source, new ByteArrayInputStream(doc), now, GuideRefresher.backSeconds(true), GuideRefresher.aheadSeconds(true), "nl");
            check(counts[0] == 5 && counts[1] == 2, "Pro import kept " + counts[0] + " programmes and " + counts[1] + " channels");
            String npo = db.channelFor(source, "", "NL: NPO 1 HD", "NL: NPO 1 HD");
            check("NPO1.nl".equals(npo), "Normalised name did not match NPO1.nl: '" + npo + "'");
            check("rtl4.nl".equals(db.channelFor(source, "rtl4.nl", "", "")), "tvg-id did not match");
            check(db.channelFor(source, "", "Unknown Channel", "Unknown Channel").isEmpty(), "Unknown channel matched a guide");
            List<EpgEntry> past = db.entries(source, npo, now - 7 * GuideDatabase.DAY, now, 50);
            check(past.size() == 2 && "Twee dagen geleden".equals(past.get(0).title), "Pro guide lost the past days: " + titles(past));
            // G2 grid: a 2-hour view two days back shows exactly that programme.
            List<EpgEntry> view = db.entries(source, npo, now - 2 * GuideDatabase.DAY - 1800, now - 2 * GuideDatabase.DAY + 5400, 200);
            check(view.size() == 1 && "Twee dagen geleden".equals(view.get(0).title), "2-hour view two days back: " + titles(view));
            List<EpgEntry> ahead = db.entries(source, npo, now, now + 7 * GuideDatabase.DAY, 50);
            check(ahead.size() == 3 && "Journaal nu".equals(ahead.get(0).title) && "Over vijf dagen".equals(ahead.get(2).title), "Pro guide ahead: " + titles(ahead));
            check("Omschrijving".equals(ahead.get(0).description), "Description lost");
            check(db.fresh(source, GuideRefresher.MAX_AGE_MS, GuideRefresher.backSeconds(true), GuideRefresher.aheadSeconds(true)), "Fresh Pro guide reported stale");

            // G4: programme search by title.
            List<GuideDatabase.Hit> found = db.search(source, "journaal", now - 7 * GuideDatabase.DAY, now, 50);
            check(found.size() == 1 && "NPO1.nl".equals(found.get(0).channel) && "Journaal nu".equals(found.get(0).entry.title), "Search journaal: " + found.size());
            List<GuideDatabase.Hit> days = db.search(source, "DAGEN", now - 7 * GuideDatabase.DAY, now, 50);
            check(days.size() == 2 && "Over vijf dagen".equals(days.get(0).entry.title) && "Twee dagen geleden".equals(days.get(1).entry.title), "Upcoming before past");
            check(db.search(source, "%", now - 7 * GuideDatabase.DAY, now, 50).isEmpty() && db.search(source, "_ _", now - 7 * GuideDatabase.DAY, now, 50).isEmpty(), "LIKE wildcards not escaped");
            check(db.search(source, "o", now - 7 * GuideDatabase.DAY, now, 50).isEmpty(), "One letter searched");
            check(db.search(source, "geleden", now, now, 50).isEmpty(), "Search ignored the window start");
            List<String> keys = db.keysOf(source, "NPO1.nl");
            check(!keys.isEmpty() && keys.get(0).startsWith("id:"), "Guide keys order: " + keys);
            check(db.search(source, "nieuws", now - 7 * GuideDatabase.DAY, now, 50).get(0).channel.equals("rtl4.nl"), "RTL Nieuws channel");

            // A broken download must keep the previous guide.
            boolean failed = false;
            try { db.importXmltv(source, new ByteArrayInputStream("<tv><programme".getBytes(StandardCharsets.UTF_8)), now, GuideRefresher.backSeconds(true), GuideRefresher.aheadSeconds(true), "nl"); }
            catch (Exception expected) { failed = true; }
            check(failed && db.entries(source, npo, now, now + 7 * GuideDatabase.DAY, 50).size() == 3, "Broken import removed the stored guide");

            // Free window: the programme on now plus 24 hours.
            counts = db.importXmltv(source, new ByteArrayInputStream(doc), now, GuideRefresher.backSeconds(false), GuideRefresher.aheadSeconds(false), "nl");
            check(counts[0] == 3, "Free import kept " + counts[0] + " programmes");
            check(!db.fresh(source, GuideRefresher.MAX_AGE_MS, GuideRefresher.backSeconds(true), GuideRefresher.aheadSeconds(true)), "Free guide counted as a Pro guide");
            List<EpgEntry> free = db.entries(source, npo, now, now + 7 * GuideDatabase.DAY, 50);
            check(free.size() == 2 && "Vanavond".equals(free.get(1).title), "Free guide: " + titles(free));
            // G3: a catch-up item keeps the channel's source and is never mistaken for live TV.
            com.nenotv.player.model.MediaEntry ch = new com.nenotv.player.model.MediaEntry();
            ch.id = "42"; ch.streamId = "42"; ch.name = "NPO 1"; ch.sourceId = "second"; ch.catchup = true; ch.catchupDays = 7;
            EpgEntry prog = past.get(0);
            com.nenotv.player.model.MediaEntry cu = MainActivity.catchupEntry(ch, prog, Arrays.asList("http://p.invalid/timeshift/u/p/60/x/42.m3u8", "http://p.invalid/timeshift/u/p/60/x/42.ts"));
            check("catchup".equals(cu.type) && "second".equals(cu.sourceId) && cu.candidates.size() == 2 && cu.url.endsWith(".m3u8") && !cu.uniqueKey().equals(ch.uniqueKey()), "Catch-up item: " + cu.type + " " + cu.sourceId + " " + cu.candidates);
            // G5: a reminder survives a round trip (channel encrypted), is scheduled and can be removed.
            EpgEntry later = ahead.get(1);
            com.nenotv.player.storage.ReminderStore.Reminder r = Reminders.add(c, ch, later);
            com.nenotv.player.storage.ReminderStore.Reminder back = com.nenotv.player.storage.ReminderStore.get(c, r.id);
            check(back != null && "NPO 1".equals(back.channel.name) && "second".equals(back.channel.sourceId) && back.start == later.startEpoch && "Vanavond".equals(back.title), "Reminder round trip");
            check(com.nenotv.player.storage.ReminderStore.has(c, ch, later.startEpoch), "Reminder not found");
            boolean listed = false; for (com.nenotv.player.storage.ReminderStore.Reminder x : com.nenotv.player.storage.ReminderStore.upcoming(c, now)) listed |= x.id.equals(r.id);
            check(listed, "Reminder not upcoming");
            Reminders.remove(c, r.id);
            check(!com.nenotv.player.storage.ReminderStore.has(c, ch, later.startEpoch), "Reminder not removed");
        } finally { db.forget(source); }
        check(db.entries(source, "NPO1.nl", 0, Long.MAX_VALUE, 10).isEmpty(), "Guide not removed");
        multipleSources(c);
    }

    /** G6: a channel missing from the first guide is found in the next guide of the same source. */
    static void multipleSources(Context c) throws Exception {
        String id = UUID.randomUUID().toString();
        final String empty = "https://qa.invalid/empty-" + id, full = "https://qa.invalid/full-" + id;
        GuideDatabase db = GuideDatabase.get(c);
        long now = System.currentTimeMillis() / 1000L;
        String fullSource = GuideDatabase.sourceKey(full), emptySource = GuideDatabase.sourceKey(empty);
        db.importXmltv(emptySource, new ByteArrayInputStream(("<tv><channel id=\"other\"><display-name>Other</display-name></channel><programme start=\"" + t(now - 600) + "\" stop=\"" + t(now + 600) + "\" channel=\"other\"><title>Other show</title></programme></tv>").getBytes(StandardCharsets.UTF_8)), now, GuideRefresher.backSeconds(true), GuideRefresher.aheadSeconds(true), "nl");
        db.importXmltv(fullSource, new ByteArrayInputStream(xml(now).getBytes(StandardCharsets.UTF_8)), now, GuideRefresher.backSeconds(true), GuideRefresher.aheadSeconds(true), "nl");
        com.nenotv.player.provider.Provider two = new com.nenotv.player.provider.Provider() {
            public void authenticate() {}
            public List<com.nenotv.player.model.Category> categories(String type) { return Collections.emptyList(); }
            public List<com.nenotv.player.model.MediaEntry> items(String type, String category) { return Collections.emptyList(); }
            public List<String> guideUrls() { return Arrays.asList(empty, full); }
        };
        com.nenotv.player.model.MediaEntry ch = new com.nenotv.player.model.MediaEntry();
        ch.name = "NL: NPO 1 HD"; ch.tvgName = "NL: NPO 1 HD";
        try (com.nenotv.player.storage.EpgStore store = new com.nenotv.player.storage.EpgStore(c)) {
            check(store.guideSources(two, ch).equals(Arrays.asList(empty, full)), "Guide sources order");
            List<EpgEntry> w = store.guideWindow(two, ch, now - 2 * GuideDatabase.DAY - 1800, now - 2 * GuideDatabase.DAY + 5400, "nl");
            check(w.size() == 1 && "Twee dagen geleden".equals(w.get(0).title), "Second guide not used: " + titles(w));
        } finally { db.forget(fullSource); db.forget(emptySource); }
    }

    static List<String> titles(List<EpgEntry> rows) { List<String> o = new ArrayList<>(); for (EpgEntry e : rows) o.add(e.title); return o; }
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
