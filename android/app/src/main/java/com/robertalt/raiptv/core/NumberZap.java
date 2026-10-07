package com.nenotv.player.core;

/**
 * Step 3: channel number entry with the remote's number keys.
 * Digits collect for a short moment (max 4); then the number is matched against the channel list.
 * When the provider supplies channel numbers (M3U tvg-chno, Xtream "num") those are used,
 * otherwise the position in the current list (1 = first channel).
 */
public final class NumberZap {
    public static final int MAX_DIGITS = 4;
    public static final long COMMIT_AFTER_MS = 1800L;

    private final StringBuilder digits = new StringBuilder();

    /** Adds a digit; returns false when the input is full or the digit is invalid. */
    public boolean add(int digit) {
        if (digit < 0 || digit > 9 || digits.length() >= MAX_DIGITS) return false;
        if (digits.length() == 0 && digit == 0) return false; // a leading 0 is never a channel number
        digits.append((char) ('0' + digit));
        return true;
    }

    public boolean isEmpty() { return digits.length() == 0; }
    public String text() { return digits.toString(); }
    /** True when no further digit can be typed, so the number can be used at once. */
    public boolean full() { return digits.length() >= MAX_DIGITS; }

    /** Returns the typed number and clears the input; 0 when nothing was typed. */
    public int take() {
        int n = 0;
        for (int i = 0; i < digits.length(); i++) n = n * 10 + (digits.charAt(i) - '0');
        digits.setLength(0);
        return n;
    }

    public void clear() { digits.setLength(0); }

    /**
     * Index of channel {@code number} in a list, or -1.
     * {@code numbers[i]} is the provider number of item i (0 = none). Provider numbers are used
     * only when at least one item has one; then an item without a number cannot be reached by number.
     */
    public static int resolve(int number, int[] numbers) {
        if (number <= 0 || numbers == null || numbers.length == 0) return -1;
        boolean provider = false;
        for (int n : numbers) if (n > 0) { provider = true; break; }
        if (provider) {
            for (int i = 0; i < numbers.length; i++) if (numbers[i] == number) return i;
            return -1;
        }
        return number <= numbers.length ? number - 1 : -1;
    }

    /** The number shown for item {@code index}: its provider number, or its position. */
    public static int shown(int index, int[] numbers) {
        if (numbers == null || index < 0 || index >= numbers.length) return 0;
        boolean provider = false;
        for (int n : numbers) if (n > 0) { provider = true; break; }
        return provider ? numbers[index] : index + 1;
    }
}
