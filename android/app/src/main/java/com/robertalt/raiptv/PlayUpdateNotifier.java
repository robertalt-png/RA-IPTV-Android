package com.nenotv.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import com.google.android.play.core.appupdate.AppUpdateInfo;
import com.google.android.play.core.appupdate.AppUpdateManager;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.appupdate.AppUpdateOptions;
import com.google.android.play.core.install.InstallStateUpdatedListener;
import com.google.android.play.core.install.model.AppUpdateType;
import com.google.android.play.core.install.model.InstallStatus;
import com.google.android.play.core.install.model.UpdateAvailability;
import com.nenotv.player.storage.SettingsStore;
import com.nenotv.player.storage.EntitlementStore;
import com.nenotv.player.storage.UpdateRequirementStore;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/** Google Play decides availability for this account/device, including its test track. */
public final class PlayUpdateNotifier {
    public static final int REQUEST_CODE = 9301;
    private final Activity activity;
    private final AppUpdateManager manager;
    private final UpdateRequirementStore requirements;
    private final Runnable accessChanged;
    private final InstallStateUpdatedListener listener;
    private AlertDialog dialog;
    private boolean resumed, closed, checking, flowActive;

    public PlayUpdateNotifier(Activity activity, Runnable accessChanged) {
        this.activity = activity;
        this.accessChanged = accessChanged;
        requirements = new UpdateRequirementStore(activity);
        manager = AppUpdateManagerFactory.create(activity.getApplicationContext());
        listener = state -> {
            if (state.installStatus() == InstallStatus.DOWNLOADED) {
                flowActive = false;
                if (resumed) showRestart();
            } else if (state.installStatus() == InstallStatus.FAILED
                    || state.installStatus() == InstallStatus.CANCELED) {
                flowActive = false;
            }
        };
        manager.registerListener(listener);
    }

    public void onResume() {
        resumed = true;
        if (closed || checking) return;
        checking = true;
        manager.getAppUpdateInfo().addOnSuccessListener(info -> {
            checking = false;
            if (!usable()) return;
            int version = info.availableVersionCode();
            if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                    || info.installStatus() == InstallStatus.DOWNLOADED) {
                requirements.observe(version, info.clientVersionStalenessDays());
            } else if (info.updateAvailability() == UpdateAvailability.UPDATE_NOT_AVAILABLE) {
                requirements.noEligibleUpdate();
            }
            accessChanged.run();
            if (info.installStatus() == InstallStatus.DOWNLOADED) {
                flowActive = false;
                showRestart();
                return;
            }
            if (flowActive) return;
            if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                    && UpdatePromptPolicy.shouldPrompt(BuildConfig.VERSION_CODE, version)) showUpdate(info);
            else showKnownUpdateOrRestoration();
        }).addOnFailureListener(error -> {
            checking = false;
            // Enforce only a previously confirmed deadline when Play is temporarily offline.
            if (usable()) { accessChanged.run(); showKnownUpdateOrRestoration(); }
        });
    }

    private boolean usable() {
        return resumed && !closed && !activity.isFinishing() && !activity.isDestroyed();
    }

    private void showUpdate(AppUpdateInfo info) {
        if (!usable() || dialog != null) return;
        dialog = new AlertDialog.Builder(activity)
                .setTitle(requirements.isBasicOnly()
                        ? text("SunnyIPTV werkt tijdelijk als Basic", "SunnyIPTV is temporarily Basic", "SunnyIPTV ist vorübergehend Basic")
                        : text("Nieuwe SunnyIPTV-versie beschikbaar", "New SunnyIPTV version available", "Neue SunnyIPTV-Version verfügbar"))
                .setMessage(updateMessage())
                .setPositiveButton(text("Bijwerken", "Update", "Aktualisieren"), (d, w) -> startUpdate(info))
                .setNegativeButton(text("Verder kijken", "Keep watching", "Weitersehen"), (d, w) -> {}).create();
        dialog.setOnDismissListener(d -> dialog = null);
        dialog.show();
    }

    private void startUpdate(AppUpdateInfo info) {
        if (info != null && info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {
            try {
                flowActive = manager.startUpdateFlowForResult(info, activity,
                        AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build(), REQUEST_CODE);
                if (flowActive) return;
            } catch (Exception ignored) { }
        }
        // Only reached after Play positively reports a newer eligible version.
        openPlay(activity);
    }

    public static void openPlay(Activity activity) {
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
        if (!usable() || dialog != null) return;
        dialog = new AlertDialog.Builder(activity)
                .setTitle(text("Update klaar", "Update ready", "Update bereit"))
                .setMessage(text("Herstart SunnyIPTV om de update te installeren. Alleen downloaden is nog niet voldoende.\n\n",
                        "Restart SunnyIPTV to install the update. Downloading alone is not enough.\n\n",
                        "Starte SunnyIPTV neu, um das Update zu installieren. Herunterladen allein reicht noch nicht.\n\n") + updateMessage())
                .setPositiveButton(text("Herstarten", "Restart", "Neustarten"), (d, w) -> manager.completeUpdate())
                .setNegativeButton(text("Later", "Later", "Später"), (d, w) -> {}).create();
        dialog.setOnDismissListener(d -> dialog = null);
        dialog.show();
    }

    private String updateMessage() {
        String language = SettingsStore.language(activity);
        String date = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.forLanguageTag(language))
                .format(new Date(requirements.deadline()));
        String restoration = text("Na installatie krijg je automatisch je eerdere toegang terug, zolang je abonnement of testerrechten nog geldig zijn.",
                "After installation, your previous access returns automatically, provided your subscription or tester rights are still valid.",
                "Nach der Installation erhältst du deinen bisherigen Zugang automatisch zurück, sofern dein Abonnement oder deine Testerrechte noch gültig sind.");
        if (requirements.isBasicOnly()) return text(
                "De termijn van 60 dagen is verstreken. Je gebruikt SunnyIPTV nu als Basic totdat je de update installeert. Je account en instellingen blijven bewaard.\n\n",
                "The 60-day deadline has passed. SunnyIPTV now runs as Basic until you install the update. Your account and settings are preserved.\n\n",
                "Die 60-Tage-Frist ist abgelaufen. SunnyIPTV läuft als Basic, bis du das Update installierst. Dein Konto und deine Einstellungen bleiben erhalten.\n\n") + restoration;
        long days = requirements.daysRemaining();
        return text("Installeer de update uiterlijk ", "Install the update by ", "Installiere das Update bis ")
                + date + text(" (nog ", " (", " (noch ") + days
                + text(" dagen). Daarna werkt SunnyIPTV tijdelijk als Basic.\n\n",
                        " days remaining). After that, SunnyIPTV temporarily runs as Basic.\n\n",
                        " Tage). Danach läuft SunnyIPTV vorübergehend als Basic.\n\n") + restoration;
    }

    private void showKnownUpdateOrRestoration() {
        if (!usable() || dialog != null || flowActive) return;
        if (requirements.hasPendingUpdate()) { showUpdate(null); return; }
        if (!requirements.hasRestorationNotice()) return;
        boolean pro = new EntitlementStore(activity).isPro();
        dialog = new AlertDialog.Builder(activity)
                .setTitle(text("SunnyIPTV bijgewerkt", "SunnyIPTV updated", "SunnyIPTV aktualisiert"))
                .setMessage(pro ? text("Je update is geïnstalleerd. Je eerdere Pro-toegang is automatisch hersteld.",
                        "Your update is installed. Your previous Pro access has been restored automatically.",
                        "Dein Update ist installiert. Dein bisheriger Pro-Zugang wurde automatisch wiederhergestellt.")
                        : text("Je update is geïnstalleerd. De tijdelijke updatebeperking is opgeheven. Je gebruikt Basic omdat je geen geldige Pro- of testerrechten hebt.",
                        "Your update is installed. The temporary update restriction has ended. You are using Basic because you have no valid Pro or tester rights.",
                        "Dein Update ist installiert. Die vorübergehende Update-Einschränkung ist aufgehoben. Ohne gültige Pro- oder Testerrechte nutzt du Basic."))
                .setPositiveButton("OK", (d, w) -> requirements.acknowledgeRestoration())
                .setOnCancelListener(d -> requirements.acknowledgeRestoration()).create();
        dialog.setOnDismissListener(d -> dialog = null);
        dialog.show();
    }
    public void onActivityResult(int requestCode, int resultCode) {
        if (requestCode != REQUEST_CODE) return;
        if (resultCode != Activity.RESULT_OK) {
            flowActive = false;
        }
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
