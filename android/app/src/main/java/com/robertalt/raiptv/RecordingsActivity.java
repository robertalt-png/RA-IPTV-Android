package com.nenotv.player;

import android.app.*;
import android.content.Intent;
import android.os.*;
import android.widget.*;
import com.nenotv.player.core.RecordingPlan;
import com.nenotv.player.model.MediaEntry;
import com.nenotv.player.storage.RecordingStore;
import com.nenotv.player.storage.SettingsStore;
import java.io.File;
import java.text.DateFormat;
import java.util.*;

/** Recording (Pro): running, planned and finished recordings, with play, stop and delete, and where they are stored. */
public class RecordingsActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresh = () -> { if (!isFinishing()) { build(); schedule(); } };
    String T(String k) { return UiText.t(this, k); }

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        String tapped = getIntent() == null ? null : getIntent().getStringExtra(Recordings.EXTRA_ID);
        if (tapped != null) Recordings.startService(this, tapped);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 4106);
    }

    @Override protected void onResume() { super.onResume(); Recordings.rescheduleAll(this); build(); schedule(); }
    @Override protected void onPause() { ui.removeCallbacks(refresh); super.onPause(); }

    private void schedule() { ui.removeCallbacks(refresh); if (RecordingService.runningCount() > 0) ui.postDelayed(refresh, 3000); }

    private void build() {
        LinearLayout box = Tiles.page(this, T("recordings"));
        List<RecordingStore.Recording> all = RecordingStore.all(this);
        List<RecordingStore.Recording> running = new ArrayList<>(), planned = new ArrayList<>(), done = new ArrayList<>();
        for (RecordingStore.Recording r : all) {
            if (RecordingStore.RECORDING.equals(r.state)) running.add(r);
            else if (RecordingStore.SCHEDULED.equals(r.state)) planned.add(r);
            else done.add(r);
        }
        planned.sort(Comparator.comparingLong(r -> r.start));

        File folder = Recordings.folder(this);
        List<File> volumes = Recordings.volumes(this);
        int current = Recordings.volumeIndex(this);
        Tiles.note(this, box, String.format(T("recording_storage"), volumeName(current), RecordingService.size(folder.getUsableSpace())));
        TextView folderPath = Tiles.note(this, box, T("recording_folder") + "\n" + folder.getAbsolutePath());
        folderPath.setTextIsSelectable(true);
        if (volumes.size() > 1) {
            Tiles.Grid g = new Tiles.Grid(this, box);
            for (int i = 0; i < volumes.size(); i++) {
                final int idx = i;
                g.add(i == 0 ? "📱" : "💾", volumeName(i), RecordingService.size(volumes.get(i).getParentFile() == null ? 0 : volumes.get(i).getParentFile().getUsableSpace()), i == current, v -> { Recordings.setVolumeIndex(this, idx); build(); });
            }
            g.finish();
        }
        if (!Recordings.exactAlarmsAllowed(this))
            Tiles.link(this, box, "⏰ " + T("recording_exact_alarm"), v -> {
                try { startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:" + getPackageName()))); }
                catch (Exception e) { Toast.makeText(this, T("recording_exact_alarm"), Toast.LENGTH_LONG).show(); }
            });
        Tiles.note(this, box, T("recording_connection_note"));

        if (all.isEmpty()) { Tiles.note(this, box, T("recordings_empty")); return; }

        if (!running.isEmpty()) {
            Tiles.heading(this, box, "● " + T("recording_now"));
            Tiles.Grid g = new Tiles.Grid(this, box, Tiles.columns(this) == 3 ? 2 : 1);
            for (RecordingStore.Recording r : running) {
                long bytes = Math.max(r.bytes, RecordingService.bytes(r.id));
                g.add("🔴", label(r), r.channelName + " · " + RecordingService.size(bytes) + " · " + T("until") + " " + time(RecordingPlan.stopAt(r.end)) + fileLocation(r), false, v -> confirm(r, T("recording_stop"), () -> { Recordings.stop(this, r.id); ui.postDelayed(this::build, 800); }));
            }
            g.finish();
        }
        if (!planned.isEmpty()) {
            Tiles.heading(this, box, T("recordings_planned"));
            Tiles.Grid g = new Tiles.Grid(this, box, Tiles.columns(this) == 3 ? 2 : 1);
            for (RecordingStore.Recording r : planned)
                g.add("⏺", label(r), day(r.start) + " · " + time(r.start) + "–" + time(r.end) + " · " + r.channelName, false, v -> confirm(r, T("record_cancel"), () -> { Recordings.stop(this, r.id); build(); }));
            g.finish();
        }
        if (!done.isEmpty()) {
            Tiles.heading(this, box, T("recordings_finished"));
            Tiles.Grid g = new Tiles.Grid(this, box, Tiles.columns(this) == 3 ? 2 : 1);
            for (RecordingStore.Recording r : done) {
                String state = RecordingStore.DONE.equals(r.state) ? "" : " · " + T(RecordingStore.PARTIAL.equals(r.state) ? "recording_partial" : "recording_failed");
                String reason = r.error.isEmpty() || "cancelled".equals(r.error) && RecordingStore.PARTIAL.equals(r.state) ? "" : " · " + reason(r.error);
                g.add(RecordingStore.FAILED.equals(r.state) ? "⚠" : "🎬", label(r), day(r.start) + " · " + time(r.start) + " · " + r.channelName + " · " + RecordingService.size(r.bytes) + state + reason + fileLocation(r), false, v -> finishedMenu(r));
            }
            g.finish();
        }
    }

    private String fileLocation(RecordingStore.Recording r) {
        return r.file.isEmpty() ? "" : "\n" + T("recording_file") + "\n" + r.file;
    }

    private void finishedMenu(RecordingStore.Recording r) {
        MediaEntry e = Recordings.playable(this, r);
        List<String> items = new ArrayList<>(); List<Runnable> actions = new ArrayList<>();
        if (e != null) { items.add("▶ " + T("play")); actions.add(() -> play(e)); }
        items.add("🗑 " + T("recording_delete")); actions.add(() -> confirm(r, T("recording_delete"), () -> { Recordings.delete(this, r.id); build(); }));
        new AlertDialog.Builder(this).setTitle(label(r)).setItems(items.toArray(new String[0]), (d, w) -> actions.get(w).run()).setNegativeButton(T("close"), null).show();
    }

    private void play(MediaEntry e) {
        Intent i = ProModuleInstaller.playerIntent(this);
        i.putExtra("media", e);
        startActivity(i);
    }

    private void confirm(RecordingStore.Recording r, String action, Runnable yes) {
        new AlertDialog.Builder(this).setTitle(label(r)).setMessage(action + "?").setPositiveButton(action, (d, w) -> yes.run()).setNegativeButton(T("cancel"), null).show();
    }

    private String label(RecordingStore.Recording r) { return r.title.isEmpty() ? r.channelName : r.title; }

    private String volumeName(int i) { return i == 0 ? T("storage_device") : String.format(T("storage_usb"), String.valueOf(i)); }

    private String reason(String code) {
        switch (code) {
            case "missed": case "source_unavailable": case "no_space": case "encrypted": case "unavailable": case "time_limit": case "interrupted": case "family_filter": case "pro_required": case "cancelled":
                return T("rec_reason_" + code);
            default: return code;
        }
    }

    private String time(long s) { DateFormat f = DateFormat.getTimeInstance(DateFormat.SHORT, SettingsStore.appLocale(this)); return f.format(new Date(s * 1000L)); }
    private String day(long s) { DateFormat f = DateFormat.getDateInstance(DateFormat.MEDIUM, SettingsStore.appLocale(this)); return f.format(new Date(s * 1000L)); }
}
