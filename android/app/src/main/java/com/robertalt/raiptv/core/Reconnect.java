package com.nenotv.player.core;

/**
 * Step 3: automatic reconnect after a dropped stream.
 * Pure timing rules so they can be tested without Android: which errors are worth retrying,
 * how long to wait before each attempt, and when to stop and let the viewer decide.
 */
public final class Reconnect {
    private Reconnect() {}

    /** Media3 PlaybackException codes (copied so this class has no Android dependency). */
    public static final int BEHIND_LIVE_WINDOW = 1002, TIMEOUT = 1003,
            IO_UNSPECIFIED = 2000, IO_NETWORK_CONNECTION_FAILED = 2001, IO_NETWORK_CONNECTION_TIMEOUT = 2002,
            IO_INVALID_HTTP_CONTENT_TYPE = 2003, IO_BAD_HTTP_STATUS = 2004, IO_FILE_NOT_FOUND = 2005,
            IO_NO_PERMISSION = 2006, IO_CLEARTEXT_NOT_PERMITTED = 2007, IO_READ_POSITION_OUT_OF_RANGE = 2008;

    /** Stop retrying after this long without a working stream. */
    public static final long GIVE_UP_AFTER_MS = 10 * 60 * 1000L;
    /** An HTTP error (often the provider's connection limit or a short outage) is retried only a few times. */
    public static final int MAX_HTTP_ATTEMPTS = 3;

    private static final long[] DELAYS_MS = {2000L, 4000L, 8000L, 15000L, 30000L};

    /** Live streams that fell behind the live window restart at the live edge at once, without waiting. */
    public static boolean restartAtLiveEdge(int code) { return code == BEHIND_LIVE_WINDOW; }

    /** Whether an error is a connection problem that may go away by itself. */
    public static boolean retryable(int code, int attempt) {
        switch (code) {
            case BEHIND_LIVE_WINDOW:
            case TIMEOUT:
            case IO_UNSPECIFIED:
            case IO_NETWORK_CONNECTION_FAILED:
            case IO_NETWORK_CONNECTION_TIMEOUT:
                return true;
            case IO_BAD_HTTP_STATUS:
                return attempt < MAX_HTTP_ATTEMPTS;
            default:
                return false; // wrong format, missing file, no permission: retrying does not help
        }
    }

    /** Wait before attempt {@code attempt} (0-based): 2, 4, 8, 15, then every 30 seconds. */
    public static long delayMs(int attempt) {
        if (attempt < 0) attempt = 0;
        return DELAYS_MS[Math.min(attempt, DELAYS_MS.length - 1)];
    }

    /** True when the stream has been gone too long; {@code firstFailureAt} 0 means no failure yet. */
    public static boolean giveUp(long firstFailureAt, long now) {
        return firstFailureAt > 0 && now - firstFailureAt >= GIVE_UP_AFTER_MS;
    }
}
