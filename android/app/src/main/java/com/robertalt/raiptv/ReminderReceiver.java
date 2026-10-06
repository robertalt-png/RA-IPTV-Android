package com.nenotv.player;

import android.content.*;
import com.nenotv.player.storage.ReminderStore;

/** G5: fires a programme reminder, and puts reminders back after a reboot. */
public final class ReminderReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        String action = intent == null ? "" : String.valueOf(intent.getAction());
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) { Reminders.rescheduleAll(c); return; }
        if (!Reminders.ACTION.equals(action)) return;
        String id = intent.getStringExtra(Reminders.EXTRA_ID);
        ReminderStore.Reminder r = ReminderStore.get(c, id);
        if (r == null) return;
        if (AppVisibility.visible()) {
            try {
                c.startActivity(new Intent(c, MainActivity.class).putExtra(Reminders.EXTRA_ID, r.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                return;
            } catch (RuntimeException blocked) { /* fall back to the notification */ }
        }
        Reminders.notify(c, r);
    }
}
