package com.nenotv.player.storage;

import android.content.Context;
import android.content.SharedPreferences;
import com.nenotv.player.ExtraPrivacyPolicy;

public final class ExtraPrivacyStore {
    public static final String PREFS = "sunnyiptv_extra_privacy_v1";
    private ExtraPrivacyStore() {}
    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
    public static boolean answered(Context c) { return prefs(c).getBoolean("answered", false); }
    public static void clear(Context c) { prefs(c).edit().clear().commit(); }
    public static boolean allowsSdk(Context c) {
        return ExtraPrivacyPolicy.allowsSdk(prefs(c).getString("age_group", "unknown"), FamilyStore.active(c));
    }
    public static void choose(Context c, String group) {
        String safe = "under_13".equals(group) || "13_plus".equals(group) ? group : "unknown";
        prefs(c).edit().putString("age_group", safe).putBoolean("answered", true).commit();
    }
}
