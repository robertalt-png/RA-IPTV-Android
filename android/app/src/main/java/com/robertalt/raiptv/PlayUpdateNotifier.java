package com.nenotv.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;
import com.google.android.play.core.appupdate.AppUpdateInfo;
import com.google.android.play.core.appupdate.AppUpdateManager;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.appupdate.AppUpdateOptions;
import com.google.android.play.core.install.InstallStateUpdatedListener;
import com.google.android.play.core.install.model.AppUpdateType;
import com.google.android.play.core.install.model.InstallStatus;
import com.google.android.play.core.install.model.UpdateAvailability;
import com.nenotv.player.storage.SettingsStore;

/** Google Play decides availability for this account/device, including its test track. */
public final class PlayUpdateNotifier {
    public static final int REQUEST_CODE = 9301;
    private final Activity activity;
    private final AppUpdateManager manager;
    private final SharedPreferences prefs;
    private final InstallStateUpdatedListener listener;
    private AlertDialog dialog;
    private boolean resumed, closed, checking, flowActive;
    private long lastCheck = -1;
    private int offeredVersion;
    private long restartDeferredUntil;

    public PlayUpdateNotifier(Activity activity) {
        this.activity = activity;
        prefs = activity.getSharedPreferences("nenotv_update_notifications", Activity.MODE_PRIVATE);
        manager = AppUpdateManagerFactory.create(activity.getApplicationContext());
        listener = state -> {
            if (state.installStatus() == InstallStatus.DOWNLOADED) {
                flowActive = false;
                if (resumed) showRestart();
            } else if (state.installStatus() == InstallStatus.FAILED
                    || state.installStatus() == InstallStatus.CANCELED) {
                flowActive = false;
                if (offeredVersion > 0) defer(offeredVersion);
            }
        };
        manager.registerListener(listener);
    }

    public void onResume() {
        resumed = true;
        if (closed || checking) return;
        long now = SystemClock.elapsedRealtime();
        // Always check after returning from Play's flow; otherwise limit background work.
        if (!flowActive && lastCheck >= 0 && now - lastCheck < 60_000) return;
        checking = true;
        lastCheck = now;
        manager.getAppUpdateInfo().addOnSuccessListener(info -> {
            checking = false;
            if (!usable()) return;
            if (info.installStatus() == InstallStatus.DOWNLOADED) {
                flowActive = false;
                showRestart();
                return;
            }
            if (info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE || flowActive) return;
            int version = info.availableVersionCode();
            if (UpdatePromptPolicy.shouldPrompt(BuildConfig.VERSION_CODE, version,
                    prefs.getInt("deferred_version", 0), prefs.getLong("deferred_at", 0),
                    System.currentTimeMillis())) showUpdate(info);
        }).addOnFailureListener(error -> {
            // Offline, non-Play installs and unsupported devices must never block playback.
            checking = false;
        });
    }

    private boolean usable() {
        return resumed && !closed && !activity.isFinishing() && !activity.isDestroyed();
    }

    private void showUpdate(AppUpdateInfo info) {
        if (!usable() || dialog != null) return;
        offeredVersion = info.availableVersionCode();
        dialog = new AlertDialog.Builder(activity)
                .setTitle(text("Nieuwe NenoTV-versie beschikbaar", "New NenoTV version available", "Neue NenoTV-Version verfügbar"))
                .setMessage(text("Er staat een update voor je klaar in Google Play. Je kunt nu bijwerken of later verder kijken.",
                        "An update is ready for you in Google Play. Update now or keep watching and update later.",
                        "Ein Update steht bei Google Play bereit. Jetzt aktualisieren oder später weitersehen."))
                .setPositiveButton(text("Bijwerken", "Update", "Aktualisieren"), (d, w) -> startUpdate(info))
                .setNegativeButton(text("Later", "Later", "Später"), (d, w) -> defer(offeredVersion))
                .setOnCancelListener(d -> defer(offeredVersion)).create();
        dialog.setOnDismissListener(d -> dialog = null);
        dialog.show();
    }

    private void startUpdate(AppUpdateInfo info) {
        if (info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {
            try {
                flowActive = manager.startUpdateFlowForResult(info, activity,
                        AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build(), REQUEST_CODE);
                if (flowActive) return;
            } catch (Exception ignored) { }
        }
        // Only reached after Play positively reports a newer eligible version.
        defer(offeredVersion);
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=" + activity.getPackageName())).setPackage("com.android.vending"));
        } catch (Exception ignored) {
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=" + activity.getPackageName())));
            } catch (Exception unavailable) { }
        }
    }

    private void showRestart() {
        if (!usable() || dialog != null || SystemClock.elapsedRealtime() < restartDeferredUntil) return;
        dialog = new AlertDialog.Builder(activity)
                .setTitle(text("Update klaar", "Update ready", "Update bereit"))
                .setMessage(text("Herstart NenoTV om de nieuwe versie te gebruiken.",
                        "Restart NenoTV to use the new version.", "Starte NenoTV neu, um die neue Version zu verwenden."))
                .setPositiveButton(text("Herstarten", "Restart", "Neustarten"), (d, w) -> manager.completeUpdate())
                .setNegativeButton(text("Later", "Later", "Später"), (d, w) -> postponeRestart())
                .setOnCancelListener(d -> postponeRestart()).create();
        dialog.setOnDismissListener(d -> dialog = null);
        dialog.show();
    }

    private void postponeRestart() { restartDeferredUntil = SystemClock.elapsedRealtime() + 60_000; }
    private void defer(int version) {
        prefs.edit().putInt("deferred_version", version).putLong("deferred_at", System.currentTimeMillis()).apply();
    }
    public void onActivityResult(int requestCode, int resultCode) {
        if (requestCode != REQUEST_CODE) return;
        if (resultCode != Activity.RESULT_OK) {
            flowActive = false;
            defer(offeredVersion);
        }
        lastCheck = -1;
    }
    public void onPause() {
        resumed = false;
        if (dialog != null) dialog.dismiss();
    }
    public void close() {
        closed = true;
        onPause();
        manager.unregisterListener(listener);
    }
    private String text(String nl, String en, String de) {
        String language = SettingsStore.language(activity);
        return "nl".equals(language) ? nl : "de".equals(language) ? de : en;
    }
}
