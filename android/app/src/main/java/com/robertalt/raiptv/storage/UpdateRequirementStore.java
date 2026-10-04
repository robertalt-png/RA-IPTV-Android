package com.nenotv.player.storage;

import android.content.Context;
import android.content.SharedPreferences;
import com.nenotv.player.BuildConfig;
import com.nenotv.player.UpdatePromptPolicy;

/** Temporary access restriction; never writes to the account's entitlement preferences. */
public final class UpdateRequirementStore {
    private static final Object LOCK = new Object();
    private final SharedPreferences prefs;

    public UpdateRequirementStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences("nenotv_update_notifications", Context.MODE_PRIVATE);
        synchronized (LOCK) {
            int required = requiredVersion();
            if (required > 0 && BuildConfig.VERSION_CODE >= required) {
                boolean restored = prefs.getBoolean("basic_applied", false);
                prefs.edit().remove("required_version").remove("first_available_at")
                        .remove("last_known_time").remove("basic_applied")
                        .putBoolean("access_restored", restored || hasRestorationNotice()).apply();
            }
        }
    }

    public int requiredVersion() { return prefs.getInt("required_version", 0); }
    public long firstAvailableAt() { return prefs.getLong("first_available_at", 0); }
    public boolean hasPendingUpdate() { return requiredVersion() > BuildConfig.VERSION_CODE; }

    public void observe(int availableVersion, Integer stalenessDays) {
        if (availableVersion <= BuildConfig.VERSION_CODE) return;
        synchronized (LOCK) {
            long now = currentTime();
            long first = UpdatePromptPolicy.firstAvailableAt(BuildConfig.VERSION_CODE, requiredVersion(),
                    firstAvailableAt(), availableVersion, stalenessDays, now);
            prefs.edit().putInt("required_version", Math.max(requiredVersion(), availableVersion))
                    .putLong("first_available_at", first).putLong("last_known_time", now).apply();
            isBasicOnly();
        }
    }

    public boolean isBasicOnly() {
        synchronized (LOCK) {
            if (!hasPendingUpdate()) return false;
            long now = currentTime();
            boolean basic = UpdatePromptPolicy.basicOnly(BuildConfig.VERSION_CODE, requiredVersion(), firstAvailableAt(), now);
            prefs.edit().putLong("last_known_time", now).putBoolean("basic_applied",
                    basic || prefs.getBoolean("basic_applied", false)).apply();
            return basic;
        }
    }

    public long daysRemaining() { return UpdatePromptPolicy.daysRemaining(firstAvailableAt(), currentTime()); }
    public long deadline() { return firstAvailableAt() + UpdatePromptPolicy.GRACE_MS; }
    private long currentTime() { return Math.max(System.currentTimeMillis(), prefs.getLong("last_known_time", 0)); }
    public boolean hasRestorationNotice() { return prefs.getBoolean("access_restored", false); }
    public void acknowledgeRestoration() { prefs.edit().remove("access_restored").apply(); }

    public void noEligibleUpdate() {
        synchronized (LOCK) {
            // Play can withdraw a release or change this account's eligible test track.
            prefs.edit().remove("required_version").remove("first_available_at")
                    .remove("last_known_time").remove("basic_applied").apply();
        }
    }
}
