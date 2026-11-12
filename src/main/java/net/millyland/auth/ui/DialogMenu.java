package net.millyland.auth.ui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.millyland.auth.TgAuthPlugin;
import net.millyland.auth.api.LinkInstructions;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Vanilla Dialog screens (Minecraft 1.21.6+, Paper Dialog API). Only this class touches the dialog classes and
 * {@link ChoiceMenu} only uses it after {@link #available()} returned true, so older servers never load it.
 *
 * <p>Every dialog is built with {@code pause(false)}: vanilla rejects a dialog that pauses the game when its
 * {@code afterAction} is {@code NONE} (the copy screens), and pausing means nothing on a server anyway.
 */
final class DialogMenu {

    /** One button of the choice screen. */
    record Choice(String id, Component label, Component tooltip) {
    }

    private DialogMenu() {
    }

    static boolean available() {
        try {
            Class.forName("io.papermc.paper.dialog.Dialog");
            Class.forName("io.papermc.paper.registry.data.dialog.body.DialogBody");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** The method choice: one button per method; it cannot be closed with Escape. */
    static void showChoice(TgAuthPlugin plugin, Player player, Component title, List<Component> body,
                           List<Choice> choices, boolean closable) {
        List<ActionButton> buttons = new ArrayList<>();
        for (Choice choice : choices) {
            buttons.add(ActionButton.create(choice.label(), choice.tooltip(), 200,
                    DialogAction.customClick((view, audience) ->
                            // the callback is not guaranteed to run on the main thread
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                if (player.isOnline()) plugin.authManager().choose(player, choice.id());
                            }), ClickCallback.Options.builder().build())));
        }
        DialogBase base = DialogBase.builder(title).pause(false).canCloseWithEscape(closable).body(plain(body)).build();
        Dialog dialog = Dialog.create(f -> f.empty().base(base).type(DialogType.multiAction(buttons, null, 1)));
        player.showDialog(dialog);
    }

    /**
     * Text screen. Vanilla requires at least one button, so there is a "Back" button (returns to the method
     * choice) when the player had a choice, otherwise "Close". The optional link is a clickable line of the text.
     *
     * <p>With {@code copyText} the screen gets a "Copy" button first and stays open after it is pressed
     * ({@code afterAction NONE}); the Back/Close button then does its own work, because with NONE no button
     * closes the dialog by itself. Click events in the body are removed: clicking one closes the dialog.
     */
    static void showInfo(TgAuthPlugin plugin, Player player, Component title, List<Component> lines,
                         LinkInstructions.Link link, Component buttonLabel, boolean back,
                         String copyText, Component copyLabel) {
        boolean copy = copyText != null && !copyText.isEmpty();
        List<Component> shown = new ArrayList<>();
        for (Component line : lines) shown.add(copy ? net.millyland.auth.ui.CopyCode.stripClicks(line) : line);
        List<DialogBody> body = plain(shown);
        if (link != null) {
            Component label = link.label().clickEvent(ClickEvent.openUrl(link.url()));
            if (link.hover() != null) {
                label = label.hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(link.hover()));
            }
            body.add(DialogBody.plainMessage(label));
        }

        DialogAction leave = back
                ? DialogAction.customClick((view, audience) ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (player.isOnline()) plugin.authManager().backToChoice(player);
                        }), ClickCallback.Options.builder().build())
                : null;

        if (!copy) {
            ActionButton button = ActionButton.create(buttonLabel, null, 160, leave);
            DialogBase base = DialogBase.builder(title).pause(false).canCloseWithEscape(true).body(body).build();
            Dialog dialog = Dialog.create(f -> f.empty().base(base).type(DialogType.notice(button)));
            player.showDialog(dialog);
            return;
        }

        if (leave == null) {
            // "Close": with afterAction NONE nothing closes the dialog by itself
            leave = DialogAction.customClick((view, audience) ->
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) player.closeDialog();
                    }), ClickCallback.Options.builder().build());
        }
        ActionButton copyButton = ActionButton.create(copyLabel, null, 160,
                DialogAction.staticAction(ClickEvent.copyToClipboard(copyText)));
        ActionButton leaveButton = ActionButton.create(buttonLabel, null, 160, leave);
        DialogBase base = DialogBase.builder(title).pause(false).canCloseWithEscape(true)
                .afterAction(DialogBase.DialogAfterAction.NONE).body(body).build();
        Dialog dialog = Dialog.create(f -> f.empty().base(base)
                .type(DialogType.multiAction(List.of(copyButton, leaveButton), null, 2)));
        player.showDialog(dialog);
    }

    static void close(Player player) {
        player.closeDialog();
    }

    private static List<DialogBody> plain(List<Component> lines) {
        List<DialogBody> out = new ArrayList<>();
        for (Component line : lines) out.add(DialogBody.plainMessage(line));
        return out;
    }
}
