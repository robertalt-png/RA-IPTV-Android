package com.nenotv.player;

import android.content.*;
import com.nenotv.player.storage.RecordingStore;

/** Recording: starts a planned recording at its time, and puts plans back after a reboot or update. */
public final class RecordingReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        String action = intent == null ? "" : String.valueOf(intent.getAction());
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) { Recordings.rescheduleAll(c); return; }
        if (!Recordings.ACTION_ALARM.equals(action)) return;
        String id = intent.getStringExtra(Recordings.EXTRA_ID);
        RecordingStore.Recording r = RecordingStore.get(c, id);
        if (r == null || !RecordingStore.SCHEDULED.equals(r.state)) return;
        Recordings.startService(c, id);
    }
}
