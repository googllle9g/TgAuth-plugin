package net.millyland.auth.admin;

/** Pure helpers for the PIN brute-force lockout: escalating duration and readable formatting. */
public final class LockoutPolicy {

    private LockoutPolicy() {
    }

    /**
     * Duration of the next lockout. {@code level} is how many lockouts in a row happened before
     * this one (0 for the first): {@code base * growth^level}, never below {@code base} and
     * never above {@code max}. A growth of 1.0 keeps every lockout at {@code base}.
     */
    public static long lockoutSeconds(long baseSeconds, double growth, long maxSeconds, int level) {
        long base = Math.max(1L, baseSeconds);
        long max = Math.max(base, maxSeconds);
        double g = Math.max(1.0, growth);
        double value = base * Math.pow(g, Math.max(0, level));
        if (Double.isNaN(value) || value >= max) return max;
        return Math.max(base, (long) Math.ceil(value));
    }

    /** "1h 20m 5s"-style text; zero parts are skipped, and 0 seconds renders as "0s". */
    public static String format(long totalSeconds, String hourUnit, String minuteUnit, String secondUnit) {
        long total = Math.max(0L, totalSeconds);
        long hours = total / 3600;
        long minutes = (total % 3600) / 60;
        long seconds = total % 60;

        StringBuilder sb = new StringBuilder();
        if (hours > 0) sb.append(hours).append(hourUnit);
        if (minutes > 0) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(minutes).append(minuteUnit);
        }
        if (seconds > 0 || sb.length() == 0) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(seconds).append(secondUnit);
        }
        return sb.toString();
    }
}
