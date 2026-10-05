package com.nenotv.player;

import com.nenotv.player.model.Profile;

public final class ProfileCacheKey {
    private ProfileCacheKey() {}
    private static String value(String text) { return text == null ? "" : text; }
    public static String of(Profile profile) {
        if (profile == null) return "none";
        String base = profile.type.name() + "|" + value(profile.server) + "|"
                + value(profile.username) + "|" + value(profile.m3uUrl);
        if (DemoPolicy.isDemo(profile)) base += "|family-demo-v1";
        return Integer.toHexString(base.hashCode()) + ":" + profile.type.name();
    }
}
