package com.nenotv.player;

import java.util.LinkedHashSet;
import java.util.Set;

/** Process-local cancellation; never stores an age, account, or SDK identifier. */
public final class ExtraPrivacySession {
    private static long generation;
    private static final Set<Runnable> listeners = new LinkedHashSet<>();
    private ExtraPrivacySession() {}
    public static synchronized long generation() { return generation; }
    public static synchronized boolean run(long expected, Runnable action) {
        if (expected != generation) return false;
        action.run();
        return true;
    }
    public static synchronized void addListener(Runnable listener) { listeners.add(listener); }
    public static synchronized void removeListener(Runnable listener) { listeners.remove(listener); }
    public static void invalidate() {
        Runnable[] pending;
        synchronized (ExtraPrivacySession.class) {
            generation++;
            pending = listeners.toArray(new Runnable[0]);
        }
        for (Runnable listener : pending) try { listener.run(); } catch (RuntimeException ignored) {}
    }
}
