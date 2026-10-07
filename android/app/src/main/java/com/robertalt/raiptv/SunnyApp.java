package com.nenotv.player;

import com.google.android.play.core.splitcompat.SplitCompatApplication;

/**
 * SplitCompat makes the Pro module (proextras) usable as soon as Google Play has installed it,
 * without restarting the app after a purchase or trial start.
 */
public final class SunnyApp extends SplitCompatApplication {
    @Override public void onCreate() {
        super.onCreate();
        com.nenotv.player.storage.CrashGuard.install(this);
        AppVisibility.install(this);
    }
}
