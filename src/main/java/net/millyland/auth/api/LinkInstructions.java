package net.millyland.auth.api;

import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * What a provider tells a player who chose it: a few lines of text and optionally one clickable link.
 * TgAuth decides how to show it: as chat messages, or inside the vanilla dialog window when the server
 * owner enabled {@code ui.vanilla-dialog}. The link is the only "button", so the same instructions work
 * in both.
 *
 * @param title     short heading of the dialog window
 * @param lines     the instruction lines, in order
 * @param link      a clickable link shown after the lines (for example "Join the Discord"), or {@code null}
 * @param reminder  one line repeated in chat every {@code auth.reminder-interval-seconds} while the player
 *                  still waits, or {@code null} for no reminders
 * @param copyText  a text (usually the link code) the vanilla dialog offers behind a "Copy" button that keeps
 *                  the dialog open, or {@code null}. Click events inside the lines are removed in the dialog
 *                  because clicking them closes it.
 */
public record LinkInstructions(@NotNull Component title, @NotNull List<Component> lines,
                               @Nullable Link link, @Nullable Component reminder,
                               @Nullable String copyText) {

    public LinkInstructions(@NotNull Component title, @NotNull List<Component> lines,
                            @Nullable Link link, @Nullable Component reminder) {
        this(title, lines, link, reminder, null);
    }

    /** @return a copy of these instructions with a "Copy" button for {@code text} in the dialog */
    public LinkInstructions withCopyText(@Nullable String text) {
        return new LinkInstructions(title, lines, link, reminder, text);
    }

    /** A clickable link that opens {@code url} in the player's browser. */
    public record Link(@NotNull Component label, @Nullable Component hover, @NotNull String url) {
    }
}
