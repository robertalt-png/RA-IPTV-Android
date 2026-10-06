package com.nenotv.player;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import java.util.concurrent.atomic.AtomicInteger;

/** Whether one of the app's screens (including the Pro player) is on screen, for G5 auto-switching. */
public final class AppVisibility {
    private AppVisibility() {}
    private static final AtomicInteger STARTED = new AtomicInteger();
    private static volatile boolean installed = false;

    public static synchronized void install(Application app) {
        if (installed || app == null) return;
        installed = true;
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity a) { STARTED.incrementAndGet(); }
            @Override public void onActivityStopped(Activity a) { if (STARTED.decrementAndGet() < 0) STARTED.set(0); }
            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityResumed(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }

    public static boolean visible() { return STARTED.get() > 0; }
}
