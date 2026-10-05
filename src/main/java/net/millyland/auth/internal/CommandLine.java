package net.millyland.auth.internal;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** A parsed Telegram command message such as {@code /Balance@MyBot  a b}. */
public record CommandLine(String name, List<String> args, String rawArgs) {

    /** @return the parsed command, or null if the text is not a slash command */
    public static CommandLine parse(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.length() < 2 || t.charAt(0) != '/') return null;

        int space = firstWhitespace(t);
        String head = space < 0 ? t.substring(1) : t.substring(1, space);
        String raw = space < 0 ? "" : t.substring(space).trim();

        int at = head.indexOf('@');
        if (at >= 0) head = head.substring(0, at);
        if (head.isEmpty()) return null;

        List<String> args = raw.isEmpty() ? List.of() : List.of(raw.split("\\s+"));
        return new CommandLine(head.toLowerCase(Locale.ROOT), args, raw);
    }

    private static int firstWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }

    /** Normalizes a registration name: strips the slash, lower-cases; null if invalid. */
    public static String normalizeName(String name) {
        if (name == null) return null;
        String n = name.trim();
        if (n.startsWith("/")) n = n.substring(1);
        n = n.toLowerCase(Locale.ROOT);
        return n.matches("[a-z0-9_]{1,32}") ? n : null;
    }

    @Override
    public String toString() {
        return name + Arrays.toString(args.toArray());
    }
}
