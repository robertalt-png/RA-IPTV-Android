package com.nenotv.player;

/** Update deadlines are independent of account entitlements and reminder dismissal. */
public final class UpdatePromptPolicy {
    public static final long DAY_MS = 86_400_000L;
    public static final long GRACE_MS = 60 * DAY_MS;
    private UpdatePromptPolicy() {}

    public static boolean shouldPrompt(int installed, int available) {
        return available > installed;
    }

    public static long firstAvailableAt(int installed, int pending, long firstSeen,
                                        int available, Integer stalenessDays, long now) {
        if (!shouldPrompt(installed, available)) return 0;
        long age = stalenessDays == null ? 0 : Math.max(0, Math.min(36500, stalenessDays));
        long detected = Math.max(1, now - age * DAY_MS);
        // A superseding release must not extend an outstanding deadline.
        return pending > installed && firstSeen > 0 ? Math.min(firstSeen, detected) : detected;
    }

    public static boolean basicOnly(int installed, int required, long firstSeen, long now) {
        return required > installed && firstSeen > 0 && now >= firstSeen
                && now - firstSeen >= GRACE_MS;
    }

    public static long daysRemaining(long firstSeen, long now) {
        if (firstSeen <= 0) return 60;
        long elapsed = Math.max(0, now - firstSeen);
        long left = Math.max(0, GRACE_MS - elapsed);
        return (left + DAY_MS - 1) / DAY_MS;
    }
}
