package com.nenotv.player;

import android.content.Context;
import android.app.Activity;
import android.content.Intent;
import android.view.View;
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

    static Object field(Activity a, String name) throws Exception {
        java.lang.reflect.Field f = a.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(a);
    }

    static void run(UiInstrumentation ui, Context c, Activity owner) throws Exception {
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
        Activity player = null;
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
            check(Recordings.runningForChannel(c, ch).contains(id), "Player cannot find its active recording");
            MediaEntry other = new MediaEntry(); other.type = "live"; other.id = "other-channel"; other.url = "http://127.0.0.1/other.ts";
            check(Recordings.runningForChannel(c, other).isEmpty(), "Player would stop another channel's recording");
            check(Recordings.runningForChannel(c, null).isEmpty(), "Missing channel matched a recording");
            File f = new File(r.file);
            check(f.length() >= 188 * 200, "Recording file did not grow: " + f.length());
            check(Recordings.insideRecordingFolders(c, f), "Recording written outside the app folder: " + f);

            player = ui.startActivitySync(ProModuleInstaller.playerIntent(owner).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("media", ch));
            ui.waitForIdleSync();
            Activity visiblePlayer = player;
            View stop = (View) field(player, "stopRecording");
            View timer = (View) field(player, "recordDuration");
            View controls = (View) field(player, "controls");
            ui.runOnMainSync(() -> {
                check(stop.getVisibility() == View.VISIBLE && stop.isEnabled(), "Player has no accessible stop button");
                check(!timer.isEnabled(), "Player allows a duplicate timed recording");
                controls.setVisibility(View.GONE);
                check(stop.isShown(), "Stop button disappeared with playback controls");
            });
            ui.snapshot("pro-recording-stop");
            ui.runOnMainSync(() -> check(stop.performClick(), "Stop button has no action"));
            deadline = SystemClock.elapsedRealtime() + 30000;
            while (SystemClock.elapsedRealtime() < deadline && RecordingService.isRunning(id)) Thread.sleep(300);
            r = RecordingStore.get(c, id);
            check(r != null && RecordingStore.PARTIAL.equals(r.state) && "cancelled".equals(r.error), "Stopped recording state: " + (r == null ? "missing" : r.state + " " + r.error));
            check(r.bytes == f.length() && r.bytes > 0, "Recorded size not saved");
            check(Recordings.runningForChannel(c, ch).isEmpty(), "Player stop button remains active after stopping");
            ui.runOnMainSync(() -> { visiblePlayer.finish(); ui.callActivityOnPause(visiblePlayer); try { check(field(visiblePlayer, "exo") == null, "Closing the player retained its audio decoder"); } catch (Exception e) { throw new RuntimeException(e); } });
            player = null;

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
            if (player != null) { Activity closing = player; ui.runOnMainSync(closing::finish); }
            if (id != null) { Recordings.stop(c, id); Thread.sleep(1500); Recordings.delete(c, id); }
            server.close();
        }
    }

    /** A local test stream that keeps sending MPEG-TS packets for {@code millis}. */
    static ServerSocket tsServer(long millis) throws IOException {
        ServerSocket server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        Thread serve = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket s = server.accept()) {
                    BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
                    String line; while ((line = r.readLine()) != null && !line.isEmpty()) {}
                    OutputStream o = s.getOutputStream();
                    o.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    byte[] packet = new byte[188]; packet[0] = 0x47;
                    long until = SystemClock.elapsedRealtime() + millis;
                    while (SystemClock.elapsedRealtime() < until) { for (int i = 0; i < 50; i++) o.write(packet); o.flush(); Thread.sleep(50); }
                } catch (Exception closed) { /* client stopped or server closed */ }
            }
        }, "recording-qa-server-left");
        serve.setDaemon(true);
        serve.start();
        return server;
    }

    /**
     * Device-matrix scenario: leaves a recording running and a second one planned, then returns
     * without stopping either. The test script then kills the app mid-recording and opens it again,
     * which is the real-life sequence (record, close, reopen) that has to start without crashing.
     */
    static void leaveRunning(Context c) throws Exception {
        ServerSocket server = tsServer(600000);
        for (RecordingStore.Recording old : RecordingStore.all(c)) Recordings.delete(c, old.id);
        MediaEntry ch = new MediaEntry();
        ch.type = "live"; ch.id = "rec-qa-left"; ch.name = "QA Recording left running"; ch.group = "NL | Algemeen";
        ch.url = "http://127.0.0.1:" + server.getLocalPort() + "/live.ts"; ch.candidates.add(ch.url);
        Recordings.Planned p = Recordings.recordNow(c, ch, "QA left running", 10);
        check(p.refused.isEmpty() && p.recording != null, "Recording was refused: " + p.refused);
        String id = p.recording.id;
        long deadline = SystemClock.elapsedRealtime() + 30000;
        RecordingStore.Recording r = RecordingStore.get(c, id);
        while (SystemClock.elapsedRealtime() < deadline && (r == null || r.file.isEmpty() || new File(r.file).length() < 188 * 200)) { Thread.sleep(500); r = RecordingStore.get(c, id); }
        check(r != null && RecordingStore.RECORDING.equals(r.state), "Recording did not start: " + (r == null ? "missing" : r.state + " " + r.error));
        check(RecordingService.isRunning(id), "Recording service is not running");
        MediaEntry later = new MediaEntry();
        later.type = "live"; later.id = "rec-qa-later"; later.name = "QA Recording planned"; later.group = "NL | Algemeen";
        later.url = "http://127.0.0.1:9/later.ts"; later.candidates.add(later.url);
        long now = System.currentTimeMillis() / 1000L;
        Recordings.Planned planned = Recordings.plan(c, later, "QA planned", now + 3600, now + 5400);
        check(planned.recording != null, "Planned recording was refused: " + planned.refused);
    }
}
