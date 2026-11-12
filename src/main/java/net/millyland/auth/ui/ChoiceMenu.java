package net.millyland.auth.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.millyland.auth.TgAuthPlugin;
import net.millyland.auth.api.LinkInstructions;
import net.millyland.auth.api.LoginProvider;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the player sees while picking and using a login method: the choice and the instructions, as
 * clickable chat text or, when the server owner turned on {@code ui.vanilla-dialog}, in the vanilla dialog
 * window. If the dialog is off, not supported by the server or fails to open, chat is used.
 */
public final class ChoiceMenu {

    private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.legacySection();

    /** Why the choice is shown: a new player linking, a linked player confirming a login, or /add-auth-method. */
    public enum Mode {
        LOGIN("", false), CONFIRM("-confirm", false), ADD("-add", true);

        final String suffix;
        /** The player is already logged in, so Escape may close the dialog. */
        final boolean closable;

        Mode(String suffix, boolean closable) {
            this.suffix = suffix;
            this.closable = closable;
        }
    }

    private final TgAuthPlugin plugin;
    private boolean warnedNoDialogs;

    public ChoiceMenu(TgAuthPlugin plugin) {
        this.plugin = plugin;
    }

    private Component text(String path, Object... kv) {
        return SECTION.deserialize(plugin.lang().get(path, kv));
    }

    private List<Component> lines(String path, Object... kv) {
        List<Component> out = new ArrayList<>();
        for (String line : plugin.lang().getList(path, kv)) out.add(SECTION.deserialize(line));
        return out;
    }

    private boolean useDialogs() {
        if (!plugin.cfg().uiVanillaDialog()) return false;
        if (DialogMenu.available()) return true;
        if (!warnedNoDialogs) {
            warnedNoDialogs = true;
            plugin.getLogger().warning("ui.vanilla-dialog is enabled but this server has no Dialog API "
                    + "(needs Paper 1.21.7+). Using chat instead.");
        }
        return false;
    }

    // ------------------------------------------------------------------ the choice

    public void showChoice(Player player, boolean telegram, List<LoginProvider> providers, Mode mode) {
        if (useDialogs()) {
            try {
                List<DialogMenu.Choice> choices = new ArrayList<>();
                if (telegram) {
                    choices.add(new DialogMenu.Choice("telegram", text("dialog.choose.telegram"),
                            text("dialog.choose.telegram-tooltip" + mode.suffix)));
                }
                for (LoginProvider p : providers) {
                    choices.add(new DialogMenu.Choice(p.getId(), p.getChoiceLabel(), p.getChoiceHover()));
                }
                DialogMenu.showChoice(plugin, player, text("dialog.choose.title" + mode.suffix),
                        lines("dialog.choose.body" + mode.suffix), choices, mode.closable);
                if (!plugin.cfg().uiAlsoSendChat()) return;
            } catch (Throwable t) {
                plugin.getLogger().warning("Could not open the dialog, using chat: " + t);
            }
        }
        showChoiceInChat(player, telegram, providers, mode);
    }

    private void showChoiceInChat(Player player, boolean telegram, List<LoginProvider> providers, Mode mode) {
        Component row = Component.empty();
        boolean first = true;
        if (telegram) {
            row = row.append(text("choose.telegram")
                    .clickEvent(ClickEvent.runCommand("/tgchoose telegram"))
                    .hoverEvent(HoverEvent.showText(text("choose.telegram-hover" + mode.suffix))));
            first = false;
        }
        for (LoginProvider p : providers) {
            if (!first) row = row.append(text("choose.or"));
            row = row.append(p.getChoiceLabel()
                    .clickEvent(ClickEvent.runCommand("/tgchoose " + p.getId()))
                    .hoverEvent(HoverEvent.showText(p.getChoiceHover())));
            first = false;
        }
        player.sendMessage(text("choose.header" + mode.suffix));
        player.sendMessage(row);
    }

    // ------------------------------------------------------------------ instructions

    /** Instructions of a provider (the dialog replaces the choice window; chat gets the plain lines). */
    public void showInstructions(Player player, LinkInstructions instructions, boolean canGoBack) {
        if (useDialogs()) {
            try {
                DialogMenu.showInfo(plugin, player, instructions.title(), instructions.lines(), instructions.link(),
                        text(canGoBack ? "dialog.back" : "dialog.close"), canGoBack,
                        instructions.copyText(), text("dialog.copy"));
                if (!plugin.cfg().uiAlsoSendChat()) return;
            } catch (Throwable t) {
                plugin.getLogger().warning("Could not open the dialog, using chat: " + t);
            }
        }
        for (Component line : instructions.lines()) player.sendMessage(line);
        LinkInstructions.Link link = instructions.link();
        if (link != null) {
            Component button = link.label().clickEvent(ClickEvent.openUrl(link.url()));
            if (link.hover() != null) button = button.hoverEvent(HoverEvent.showText(link.hover()));
            player.sendMessage(button);
        }
    }

    /**
     * The Telegram code screen. TgAuth always prints its normal chat message, so this only adds the dialog.
     */
    public void showTelegramInstructions(Player player, String botUsername, String code, boolean canGoBack) {
        if (!useDialogs()) return;
        try {
            List<Component> body = lines("dialog.telegram.body", "%bot%", "@" + botUsername, "%code%", code);
            DialogMenu.showInfo(plugin, player, text("dialog.telegram.title"), body, null,
                    text(canGoBack ? "dialog.back" : "dialog.close"), canGoBack, "/link " + code, text("dialog.copy"));
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not open the dialog: " + t);
        }
    }

    /** Closes the dialog (if one is open): after a failed pick, or when the player got logged in. */
    public void closeDialog(Player player) {
        if (!useDialogs()) return;
        try {
            DialogMenu.close(player);
        } catch (Throwable ignored) {
        }
    }
}
