package com.nenotv.player;

public final class ExtraPrivacyPolicy {
    private ExtraPrivacyPolicy() {}
    public static boolean allowsSdk(String ageGroup, boolean childMode) {
        return !childMode && "13_plus".equals(ageGroup);
    }
}
