package net.millyland.auth.update;

/**
 * Minimal, dependency-free version comparison for tags such as {@code v1.2.0}, {@code 1.10},
 * {@code 1.2.0-beta}. Numeric parts are compared numerically ("1.10" is newer than "1.9");
 * when they are equal, a release is newer than a build that carries a qualifier
 * ("1.2.0" &gt; "1.2.0-SNAPSHOT").
 */
public final class Versions {

    private Versions() {
    }

    private record Parsed(long[] numbers, String qualifier) {
    }

    /** True only if {@code candidate} is strictly newer than {@code current}; unparseable input is never newer. */
    public static boolean isNewer(String candidate, String current) {
        Parsed a = parse(candidate);
        Parsed b = parse(current);
        if (a == null || b == null) return false;
        return compare(a, b) > 0;
    }

    private static int compare(Parsed a, Parsed b) {
        int n = Math.max(a.numbers().length, b.numbers().length);
        for (int i = 0; i < n; i++) {
            long x = i < a.numbers().length ? a.numbers()[i] : 0L;
            long y = i < b.numbers().length ? b.numbers()[i] : 0L;
            if (x != y) return Long.compare(x, y);
        }
        boolean aRelease = a.qualifier().isEmpty();
        boolean bRelease = b.qualifier().isEmpty();
        if (aRelease && !bRelease) return 1;
        if (!aRelease && bRelease) return -1;
        return a.qualifier().compareToIgnoreCase(b.qualifier());
    }

    private static Parsed parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);

        int end = 0;
        while (end < s.length() && (Character.isDigit(s.charAt(end)) || s.charAt(end) == '.')) {
            end++;
        }
        String numeric = s.substring(0, end);
        if (numeric.isEmpty() || !Character.isDigit(numeric.charAt(0))) return null;

        String[] parts = numeric.split("\\.");
        long[] numbers = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) {
                numbers[i] = 0L;
                continue;
            }
            try {
                numbers[i] = Long.parseLong(parts[i]);
            } catch (NumberFormatException e) {
                numbers[i] = Long.MAX_VALUE;
            }
        }

        String qualifier = s.substring(end).replaceFirst("^[-+_.\\s]+", "");
        return new Parsed(numbers, qualifier);
    }
}
