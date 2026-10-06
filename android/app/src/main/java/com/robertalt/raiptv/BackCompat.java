package com.nenotv.player;

import android.app.Activity;
import android.os.Build;

/**
 * Android 16 no longer calls Activity.onBackPressed() for apps targeting API 36.
 * Routing the system back callback to onBackPressed() keeps our custom back behaviour on every version.
 */
public final class BackCompat {
    private BackCompat() {}
    public static void route(Activity activity) {
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, activity::onBackPressed);
        }
    }
}
