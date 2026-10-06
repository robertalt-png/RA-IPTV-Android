package com.nenotv.player;

public final class ExtraPrivacyPolicy {
    private ExtraPrivacyPolicy() {}
    // The app targets 13+ only (Play Console target audience), so no age question is asked.
    // Google Cast and Pro translation stay off while the family filter is active.
    public static boolean allowsSdk(String ageGroup, boolean childMode) {
        return !childMode;
    }
}
