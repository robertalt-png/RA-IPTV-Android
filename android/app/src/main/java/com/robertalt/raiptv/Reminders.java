package com.nenotv.player;

import android.app.*;
import android.content.*;
import android.os.Build;
import com.nenotv.player.model.EpgEntry;
import com.nenotv.player.model.MediaEntry;
import com.nenotv.player.storage.ReminderStore;

/**
 * G5: schedules programme reminders. One minute before the start the app switches to the channel
 * itself when it is open (with a 10-second countdown), otherwise it posts a notification.
 * Without the exact-alarm permission Android may deliver the alarm a few minutes late in deep sleep.
 */
public final class Reminders {
    private Reminders() {}

    public static final String ACTION = "com.nenotv.player.REMINDER";
    public static final String EXTRA_ID = "sunnyiptv_reminder";
    public static final String EXTRA_TAP = "sunnyiptv_reminder_tap";
    static final String CHANNEL = "programme_reminders";
    static final long LEAD_S = 60;

    public static ReminderStore.Reminder add(Context c, MediaEntry channel, EpgEntry programme) {
        ReminderStore.Reminder r = ReminderStore.add(c, channel, programme);
        schedule(c, r);
        return r;
    }

    public static void remove(Context c, String id) { cancel(c, id); ReminderStore.remove(c, id); }

    static PendingIntent alarm(Context c, String id) {
        Intent i = new Intent(c, ReminderReceiver.class).setAction(ACTION).putExtra(EXTRA_ID, id).setData(android.net.Uri.parse("sunnyiptv-reminder:" + id));
        return PendingIntent.getBroadcast(c, id.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void schedule(Context c, ReminderStore.Reminder r) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            long at = Math.max(System.currentTimeMillis() + 1000, (r.start - LEAD_S) * 1000L);
            PendingIntent pi = alarm(c, r.id);
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (Exception ignored) {}
    }

    static void cancel(Context c, String id) {
        try { ((AlarmManager) c.getSystemService(Context.ALARM_SERVICE)).cancel(alarm(c, id)); } catch (Exception ignored) {}
    }

    /** After a reboot or app start: alarms are not kept by Android across reboots. */
    public static void rescheduleAll(Context c) {
        for (ReminderStore.Reminder r : ReminderStore.upcoming(c, System.currentTimeMillis() / 1000L)) schedule(c, r);
    }

    static void notify(Context c, ReminderStore.Reminder r) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel(CHANNEL, UiText.t(c, "reminders"), NotificationManager.IMPORTANCE_HIGH));
        Intent open = new Intent(c, MainActivity.class).putExtra(EXTRA_ID, r.id).putExtra(EXTRA_TAP, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent tap = PendingIntent.getActivity(c, r.id.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
        b.setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle(r.title).setContentText(UiText.t(c, "starts_on") + " " + DisplayText.title(r.channel))
                .setContentIntent(tap).setAutoCancel(true).setCategory(Notification.CATEGORY_REMINDER);
        try { nm.notify(r.id.hashCode(), b.build()); } catch (SecurityException notAllowed) {}
    }
}
