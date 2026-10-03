package com.nenotv.player;

/** Pure policy: only newer versions, bounded reminders, clock rollback tolerated. */
public final class UpdatePromptPolicy {
    public static final long REMIND_AFTER_MS = 24 * 60 * 60 * 1000L;
    private UpdatePromptPolicy() {}
    public static boolean shouldPrompt(int installed, int available, int deferredVersion,
                                       long deferredAt, long now) {
        if (available <= installed) return false;
        return available != deferredVersion || deferredAt <= 0 || now < deferredAt
                || now - deferredAt >= REMIND_AFTER_MS;
    }
}
