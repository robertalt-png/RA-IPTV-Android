import com.nenotv.player.core.RecordingEngine;
import com.nenotv.player.core.RecordingPlan;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Recording rules and the engine against a local HTTP server: TS stream, dropped connection, HLS (plain, master, AES-128). */
public class RecordingTest {
    static int checks = 0;
    static void check(String name, boolean ok) { if (!ok) throw new AssertionError("FAILED: " + name); checks++; }

    public static void main(String[] a) throws Exception {
        plan();
        engine();
        System.out.println("Recording: " + checks + " checks passed");
    }

    static void plan() {
        check("pad before", RecordingPlan.startAt(1000) == 940);
        check("pad after", RecordingPlan.stopAt(2000) == 2180);
        check("overlap", RecordingPlan.overlaps(1000, 2000, 2100, 3000));
        check("no overlap", !RecordingPlan.overlaps(1000, 2000, 2300, 3000));
        check("recordable while running", RecordingPlan.stillRecordable(2000, 2100) && !RecordingPlan.stillRecordable(2000, 2180));
        List<String> o = RecordingPlan.order(Arrays.asList("http://x/a.m3u8", "", "http://x/a.ts?token=1", null, "http://x/a.m3u8", "http://x/a"));
        check("ts first", o.get(0).equals("http://x/a.ts?token=1"));
        check("hls second, deduplicated", o.get(1).equals("http://x/a.m3u8") && o.size() == 3);
        check("hls detection", RecordingPlan.looksLikeHls("http://x/a.ts", "application/vnd.apple.mpegurl", "") && RecordingPlan.looksLikeHls("http://x/live", "", "#EXTM3U") && !RecordingPlan.looksLikeHls("http://x/a.ts", "video/mp2t", "G@"));
        String fn = RecordingPlan.fileName("NPO 1 HD", "Journaal: 20/20 *live*", 1_791_000_000L, TimeZone.getTimeZone("Europe/Amsterdam"));
        check("file name safe", fn.endsWith(".ts") && !fn.contains("/") && !fn.contains(":") && !fn.contains("*") && fn.contains("NPO 1 HD - Journaal"));
        check("file name date", fn.startsWith("2026-10-0"));
        RecordingPlan.Playlist p = RecordingPlan.parse("#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:7\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:4.0,\nseg7.ts\n#EXTINF:4.0,\n/abs/seg8.ts\n#EXT-X-ENDLIST\n", "http://h/p/list.m3u8");
        check("segments", p.segments.size() == 2 && p.segments.get(0).sequence == 7 && p.segments.get(1).sequence == 8);
        check("relative resolve", p.segments.get(0).uri.equals("http://h/p/seg7.ts") && p.segments.get(1).uri.equals("http://h/abs/seg8.ts"));
        check("init and end", p.initUri.equals("http://h/p/init.mp4") && p.ended && p.targetDuration == 4);
        RecordingPlan.Playlist m = RecordingPlan.parse("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=3000000,CODECS=\"avc1,mp4a\"\nhigh.m3u8\n", "http://h/master.m3u8");
        check("master picks best", m.master && m.bestVariant.equals("http://h/high.m3u8"));
        RecordingPlan.Playlist k = RecordingPlan.parse("#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"k\"\n#EXTINF:2,\na.ts\n", "http://h/");
        check("sample-aes unsupported", k.unsupportedKey);
        byte[] iv = RecordingPlan.iv(null, 258);
        check("iv from sequence", iv[15] == 2 && iv[14] == 1 && iv[0] == 0);
        check("iv from hex", RecordingPlan.iv("0x0102", 0)[15] == 2 && RecordingPlan.iv("0x0102", 0)[14] == 1);
    }

    static void engine() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger tsRequests = new AtomicInteger();
        // A TS stream that sends 3 chunks per connection and then drops; the first request fails with 503.
        server.createContext("/live.ts", ex -> {
            int n = tsRequests.incrementAndGet();
            if (n == 1) { ex.sendResponseHeaders(503, -1); ex.close(); return; }
            ex.getResponseHeaders().add("Content-Type", "video/mp2t");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream o = ex.getResponseBody()) {
                for (int i = 0; i < 3; i++) { o.write(new byte[188 * 10]); o.flush(); try { Thread.sleep(40); } catch (InterruptedException ignored) {} }
            } catch (IOException ignored) {}
        });
        // HLS: a live playlist that grows by one segment per request.
        AtomicInteger hlsPolls = new AtomicInteger();
        server.createContext("/hls/master.m3u8", ex -> send(ex, "application/vnd.apple.mpegurl", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=900\nhigh.m3u8\n".getBytes("UTF-8")));
        server.createContext("/hls/high.m3u8", ex -> {
            int n = hlsPolls.incrementAndGet();
            StringBuilder s = new StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:" + n + "\n");
            for (int i = n; i < n + 2; i++) s.append("#EXTINF:1.0,\nseg").append(i).append(".ts\n");
            send(ex, "application/vnd.apple.mpegurl", s.toString().getBytes("UTF-8"));
        });
        server.createContext("/hls/seg", ex -> send(ex, "video/mp2t", new byte[1000]));
        // AES-128 HLS (ended playlist), key 16 bytes, IV from sequence.
        byte[] key = "0123456789abcdef".getBytes("UTF-8");
        byte[] plain = "secret transport stream bytes".getBytes("UTF-8");
        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"), new javax.crypto.spec.IvParameterSpec(RecordingPlan.iv(null, 5)));
        byte[] enc = c.doFinal(plain);
        server.createContext("/aes/list.m3u8", ex -> send(ex, "application/vnd.apple.mpegurl", "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:5\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:1,\nseg.ts\n#EXT-X-ENDLIST\n".getBytes("UTF-8")));
        server.createContext("/aes/key.bin", ex -> send(ex, "application/octet-stream", key));
        server.createContext("/aes/seg.ts", ex -> send(ex, "video/mp2t", enc));
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            File dir = Files.createTempDirectory("rec").toFile();

            File ts = new File(dir, "ts.ts");
            RecordingEngine e1 = new RecordingEngine(null, "test", 2000, 2000);
            long stop = System.currentTimeMillis() + 4500;
            List<Long> progress = new ArrayList<>();
            RecordingEngine.Result r1 = e1.record(Arrays.asList(base + "/live.ts"), ts, stop, progress::add);
            check("ts recorded across drops", r1.bytes >= 3 * 188 * 10 * 2 && ts.length() == r1.bytes);
            check("ts reconnected after 503 and drops", tsRequests.get() >= 3 && r1.reconnects >= 2);
            check("ts reached end", r1.reachedEnd && r1.error.isEmpty());

            File hls = new File(dir, "hls.ts");
            RecordingEngine e2 = new RecordingEngine(null, "test", 2000, 2000);
            RecordingEngine.Result r2 = e2.record(Arrays.asList(base + "/hls/master.m3u8"), hls, System.currentTimeMillis() + 3500, null);
            check("hls follows best variant and appends new segments only", r2.bytes >= 3000 && r2.bytes % 1000 == 0 && hlsPolls.get() >= 2);
            check("hls segments not duplicated", r2.bytes <= (hlsPolls.get() + 1) * 1000L);

            File aes = new File(dir, "aes.ts");
            RecordingEngine e3 = new RecordingEngine(null, "test", 2000, 2000);
            RecordingEngine.Result r3 = e3.record(Arrays.asList(base + "/aes/list.m3u8"), aes, System.currentTimeMillis() + 1500, null);
            check("aes decrypted", Arrays.equals(Files.readAllBytes(aes.toPath()), plain) && r3.bytes == plain.length);

            File cancelled = new File(dir, "cancel.ts");
            RecordingEngine e4 = new RecordingEngine(null, "test", 2000, 2000);
            new Thread(() -> { try { Thread.sleep(700); } catch (InterruptedException ignored) {} e4.cancel(); }).start();
            long t0 = System.currentTimeMillis();
            RecordingEngine.Result r4 = e4.record(Arrays.asList(base + "/live.ts"), cancelled, t0 + 60_000, null);
            check("cancel stops quickly and keeps data", System.currentTimeMillis() - t0 < 5000 && "cancelled".equals(r4.error) && !r4.reachedEnd);

            File none = new File(dir, "none.ts");
            RecordingEngine e5 = new RecordingEngine(null, "test", 500, 500);
            RecordingEngine.Result r5 = e5.record(Arrays.asList(base + "/missing.ts"), none, System.currentTimeMillis() + 2500, null);
            check("missing stream reported", r5.bytes == 0 && "unavailable".equals(r5.error) && !r5.reachedEnd);

            File full = new File(dir, "full.ts");
            RecordingEngine e6 = new RecordingEngine(null, "test", 2000, 2000);
            RecordingEngine.Result r6 = e6.record(Arrays.asList(base + "/hls/master.m3u8"), full, System.currentTimeMillis() + 10_000, new RecordingEngine.Listener() {
                public void progress(long b) {}
                public boolean spaceLeft() { return false; }
            });
            check("disk full stops and keeps data", "no_space".equals(r6.error) && r6.bytes > 0 && full.length() == r6.bytes);
        } finally {
            server.stop(0);
        }
    }

    static void send(com.sun.net.httpserver.HttpExchange ex, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(body); }
    }
}
