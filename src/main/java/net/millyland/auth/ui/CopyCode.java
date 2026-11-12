package net.millyland.auth.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.millyland.auth.TgAuthPlugin;

/** Makes a code inside a message clickable: a click copies the code to the clipboard. */
public final class CopyCode {

    private CopyCode() {
    }

    /** @param legacyText a message with section-sign colour codes (as {@code Lang} returns it) */
    public static Component inText(TgAuthPlugin plugin, String legacyText, String code) {
        return in(plugin, LegacyComponentSerializer.legacySection().deserialize(legacyText), code);
    }

    /** Removes every click event from the component and its children (clicking one closes a dialog). */
    public static Component stripClicks(Component component) {
        Component plain = component.clickEvent(null);
        return plain.children(plain.children().stream().map(CopyCode::stripClicks).toList());
    }

    /** Like {@link #inText(TgAuthPlugin, String, String)}, but a click copies {@code copyValue} (e.g. "/link 123456"). */
    public static Component inText(TgAuthPlugin plugin, String legacyText, String code, String copyValue) {
        return in(plugin, LegacyComponentSerializer.legacySection().deserialize(legacyText), code, copyValue);
    }

    public static Component in(TgAuthPlugin plugin, Component message, String code) {
        return in(plugin, message, code, code);
    }

    /** Makes every occurrence of {@code code} clickable; a click copies {@code copyValue}. */
    public static Component in(TgAuthPlugin plugin, Component message, String code, String copyValue) {
        if (code == null || code.isEmpty()) return message;
        Component hover = LegacyComponentSerializer.legacySection().deserialize(plugin.lang().get("copy.hover"));
        return message.replaceText(TextReplacementConfig.builder()
                .matchLiteral(code)
                .replacement((match, builder) -> builder
                        .clickEvent(ClickEvent.copyToClipboard(copyValue))
                        .hoverEvent(HoverEvent.showText(hover)))
                .build());
    }
}
