package com.nenotv.player;

import android.content.Context;
import android.os.SystemClock;
import com.nenotv.player.model.MediaEntry;
import com.nenotv.player.storage.RecordingStore;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Recording on the device: a local transport stream is recorded by the foreground service, stopped, played back and deleted. */
final class RecordingChecks {
    private RecordingChecks() {}

    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

    static void run(Context c) throws Exception {
        ServerSocket server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        Thread serve = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket s = server.accept()) {
                    BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
                    String line; while ((line = r.readLine()) != null && !line.isEmpty()) {}
                    OutputStream o = s.getOutputStream();
                    o.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    byte[] packet = new byte[188]; packet[0] = 0x47;
                    long until = SystemClock.elapsedRealtime() + 60000;
                    while (SystemClock.elapsedRealtime() < until) { for (int i = 0; i < 50; i++) o.write(packet); o.flush(); Thread.sleep(50); }
                } catch (Exception closed) { /* client stopped or server closed */ }
            }
        }, "recording-qa-server");
        serve.setDaemon(true);
        serve.start();
        String id = null;
        try {
            MediaEntry ch = new MediaEntry();
            ch.type = "live"; ch.id = "rec-qa"; ch.name = "QA Recording"; ch.group = "NL | Algemeen";
            ch.url = "http://127.0.0.1:" + server.getLocalPort() + "/live.ts"; ch.candidates.add(ch.url);
            for (RecordingStore.Recording old : RecordingStore.all(c)) Recordings.delete(c, old.id);

            Recordings.Planned p = Recordings.recordNow(c, ch, "QA programme", 5);
            check(p.refused.isEmpty() && p.recording != null, "Recording was refused: " + p.refused);
            id = p.recording.id;
            check(RecordingStore.has(c, ch, p.recording.start), "Recording not listed for the guide");
            long deadline = SystemClock.elapsedRealtime() + 30000;
            RecordingStore.Recording r = RecordingStore.get(c, id);
            while (SystemClock.elapsedRealtime() < deadline && (r == null || r.file.isEmpty() || new File(r.file).length() < 188 * 200)) { Thread.sleep(500); r = RecordingStore.get(c, id); }
            check(r != null && RecordingStore.RECORDING.equals(r.state), "Recording did not start: " + (r == null ? "missing" : r.state + " " + r.error));
            check(RecordingService.isRunning(id), "Recording service is not running");
            File f = new File(r.file);
            check(f.length() >= 188 * 200, "Recording file did not grow: " + f.length());
            check(Recordings.insideRecordingFolders(c, f), "Recording written outside the app folder: " + f);

            Recordings.stop(c, id);
            deadline = SystemClock.elapsedRealtime() + 30000;
            while (SystemClock.elapsedRealtime() < deadline && RecordingService.isRunning(id)) Thread.sleep(300);
            r = RecordingStore.get(c, id);
            check(r != null && RecordingStore.PARTIAL.equals(r.state) && "cancelled".equals(r.error), "Stopped recording state: " + (r == null ? "missing" : r.state + " " + r.error));
            check(r.bytes == f.length() && r.bytes > 0, "Recorded size not saved");

            MediaEntry play = Recordings.playable(c, r);
            check(play != null && Recordings.isLocalRecording(c, play) && "recording".equals(play.type), "Recording is not playable");
            MediaEntry outside = new MediaEntry(); outside.type = "recording"; outside.url = "file:///system/etc/hosts";
            check(!Recordings.isLocalRecording(c, outside), "A file outside the recording folder was accepted");

            Recordings.Planned again = Recordings.plan(c, ch, "Ended", System.currentTimeMillis() / 1000L - 7200, System.currentTimeMillis() / 1000L - 3600);
            check("ended".equals(again.refused), "An ended programme was accepted");

            Recordings.delete(c, id);
            check(!f.exists() && RecordingStore.get(c, id) == null, "Recording was not deleted");
            id = null;
        } finally {
            if (id != null) { Recordings.stop(c, id); Thread.sleep(1500); Recordings.delete(c, id); }
            server.close();
        }
    }
}
