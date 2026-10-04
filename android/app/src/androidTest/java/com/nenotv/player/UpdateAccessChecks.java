package com.nenotv.player;

import android.content.Context;
import android.content.SharedPreferences;
import com.nenotv.player.storage.EntitlementStore;
import com.nenotv.player.storage.UpdateRequirementStore;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

final class UpdateAccessChecks {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    static void run(Context context) throws Exception {
        SharedPreferences updates = context.getSharedPreferences("nenotv_update_notifications", Context.MODE_PRIVATE);
        SharedPreferences account = context.getSharedPreferences("nenotv_entitlement", Context.MODE_PRIVATE);
        Map<String, ?> oldUpdates = updates.getAll(), oldAccount = account.getAll();
        try {
            updates.edit().clear().commit();
            EntitlementStore entitlement = new EntitlementStore(context);
            entitlement.applyServer(new JSONObject().put("level", "pro").put("status", "active")
                    .put("account_email", "tester@example.invalid").put("max_devices", 5));
            Map<String, ?> originalRights = account.getAll();
            UpdateRequirementStore requirement = new UpdateRequirementStore(context);
            check(entitlement.isPro(), "Valid Pro missing before deadline");
            requirement.observe(BuildConfig.VERSION_CODE + 1, 59);
            check(!requirement.isBasicOnly() && entitlement.isPro(), "Pro removed before 60 days");
            long first = requirement.firstAvailableAt();
            requirement.observe(BuildConfig.VERSION_CODE + 2, 0);
            check(requirement.firstAvailableAt() == first, "Superseding update reset deadline");
            requirement.observe(BuildConfig.VERSION_CODE + 2, 60);
            check(requirement.isBasicOnly() && !entitlement.isPro(), "Overdue Pro did not become Basic");
            check(new EntitlementStore(context).level() == EntitlementStore.Level.FREE, "Basic state lost on relaunch");
            check(account.getAll().equals(originalRights), "Basic overwrote account/tester rights");
            // Simulate the next app binary reaching the recorded required version.
            updates.edit().putInt("required_version", BuildConfig.VERSION_CODE).commit();
            entitlement = new EntitlementStore(context);
            check(entitlement.isPro(), "Installed update did not restore valid Pro");
            requirement = new UpdateRequirementStore(context);
            check(!requirement.hasPendingUpdate() && requirement.hasRestorationNotice(), "Restoration notice missing");
            check(account.getAll().equals(originalRights), "Restoration modified original rights");
            requirement.acknowledgeRestoration();
            check(!new UpdateRequirementStore(context).hasRestorationNotice(), "Restoration notice repeated after acknowledgement");

            requirement.observe(BuildConfig.VERSION_CODE + 1, 60);
            entitlement.applyServer(new JSONObject().put("level", "free").put("status", "revoked"));
            updates.edit().putInt("required_version", BuildConfig.VERSION_CODE).commit();
            check(!new EntitlementStore(context).isPro(), "Update restored revoked tester/Pro rights");

            entitlement.applyServer(new JSONObject().put("level", "pro").put("status", "active")
                    .put("expires_at_ms", System.currentTimeMillis() - 1000));
            check(!entitlement.isPro(), "Update restored expired subscription");
            entitlement.applyServer(new JSONObject().put("level", "pro_trial").put("status", "trial_active")
                    .put("expires_at_ms", System.currentTimeMillis() + UpdatePromptPolicy.DAY_MS));
            check(entitlement.isPro() && entitlement.isTrial(), "Valid trial was not restored");
            requirement.observe(BuildConfig.VERSION_CODE + 1, 60);
            entitlement.applyServer(new JSONObject().put("level", "pro_trial").put("status", "trial_active")
                    .put("expires_at_ms", System.currentTimeMillis() - 1000));
            updates.edit().putInt("required_version", BuildConfig.VERSION_CODE).commit();
            check(!new EntitlementStore(context).isPro(), "Update restarted an expired trial");

            entitlement.applyServer(new JSONObject().put("level", "pro").put("status", "active"));
            requirement.observe(BuildConfig.VERSION_CODE + 1, 60);
            check(!entitlement.isPro(), "Overdue restriction missing");
            updates.edit().putLong("last_known_time", System.currentTimeMillis() + 10 * UpdatePromptPolicy.DAY_MS).commit();
            check(new UpdateRequirementStore(context).isBasicOnly(), "Clock change restored overdue Pro");
            requirement.noEligibleUpdate();
            check(entitlement.isPro(), "Withdrawn/ineligible update kept account in Basic");
        } finally {
            restore(updates, oldUpdates);
            restore(account, oldAccount);
        }
    }

    @SuppressWarnings("unchecked")
    private static void restore(SharedPreferences prefs, Map<String, ?> values) {
        SharedPreferences.Editor editor = prefs.edit().clear();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
            if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof Set) editor.putStringSet(key, (Set<String>) value);
        }
        editor.commit();
    }
}
