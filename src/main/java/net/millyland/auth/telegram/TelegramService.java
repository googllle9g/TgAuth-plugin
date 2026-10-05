package net.millyland.auth.telegram;

import net.millyland.auth.TgAuthPlugin;
import net.millyland.auth.admin.AdminPinService;
import net.millyland.auth.storage.LinkedAccount;
import net.kyori.adventure.text.Component;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TelegramService extends TelegramLongPollingBot {

    private static final int ADMIN_PAGE_SIZE = 5;

    private final TgAuthPlugin plugin;
    private final String username;

    private final Set<Long> awaitingSearchInput = ConcurrentHashMap.newKeySet();
    private final Map<Long, PendingAdminAction> awaitingReasonInput = new ConcurrentHashMap<>();

    private static final long PIN_PROMPT_TTL_MS = 180_000L;

    private enum PinStep { ENTER, SETUP_FIRST, SETUP_CONFIRM }

    /**
     * State of the inline PIN keypad of one admin: which step they are on, the digits typed so far
     * and the id of the keypad message that is edited in place.
     */
    private record PinPrompt(PinStep step, AdminPinService.PinDraft draft, long expiresAt,
                             String digits, int messageId) {
    }

    private final Map<Long, PinPrompt> awaitingPin = new ConcurrentHashMap<>();

    private record PendingAdminAction(String type, UUID uuid, String playerName, String reason) {
        PendingAdminAction(String type, UUID uuid, String playerName) {
            this(type, uuid, playerName, null);
        }
    }

    public TelegramService(TgAuthPlugin plugin) {
        super(plugin.cfg().botToken());
        this.plugin = plugin;
        this.username = plugin.cfg().botUsername();
    }

    public void start() throws TelegramApiException {
        TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
        botsApi.registerBot(this);
    }

    @Override
    public String getBotUsername() {
        return username;
    }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasCallbackQuery()) {
                handleCallback(update.getCallbackQuery());
            } else if (update.hasMessage() && update.getMessage().hasText()) {
                handleMessage(update.getMessage());
            }
        } catch (Exception e) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Error handling Telegram update", e);
        }
    }

    public boolean isAdmin(long telegramId) {
        return adminReason(telegramId) == null;
    }

    /** @return null if the user is an admin, otherwise a short explanation why not (for the console). */
    private String adminReason(long telegramId) {
        if (!plugin.cfg().adminPanelEnabled()) return "telegram.admin-panel-enabled is false";
        if (plugin.cfg().adminTelegramIds().contains(telegramId)) return null;

        var linkedAll = plugin.database().findAllByTelegramId(telegramId);
        if (linkedAll.isEmpty()) {
            return "this Telegram ID is not linked to any player and is not listed in admin-ids";
        }
        // With several linked accounts, the user is an admin if ANY of them has the permission.
        List<UUID> uuids = linkedAll.stream().map(net.millyland.auth.storage.LinkedAccount::uuid).toList();
        String names = String.join(", ", linkedAll.stream().map(net.millyland.auth.storage.LinkedAccount::username).toList());

        java.util.Map<UUID, Boolean> online;
        try {
            online = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                java.util.Map<UUID, Boolean> result = new java.util.HashMap<>();
                for (UUID u : uuids) {
                    Player p = Bukkit.getPlayer(u);
                    if (p != null) result.put(u, p.hasPermission("tgauth.admin"));
                }
                return result;
            }).get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return "could not check the permissions of " + names + ": " + e;
        }
        if (online.containsValue(true)) return null;

        List<UUID> offline = uuids.stream().filter(u -> !online.containsKey(u)).toList();
        if (offline.isEmpty()) {
            return names + " is online but lacks the tgauth.admin permission";
        }

        if (plugin.luckPermsHook().isPresent()) {
            for (UUID u : offline) {
                if (plugin.luckPermsHook().hasPermission(u, "tgauth.admin")) return null;
            }
            return names + " (offline) has no tgauth.admin permission in LuckPerms";
        }

        try {
            boolean anyOp = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                for (UUID u : offline) {
                    if (Bukkit.getOfflinePlayer(u).isOp()) return true;
                }
                return false;
            }).get(5, java.util.concurrent.TimeUnit.SECONDS);
            return anyOp ? null
                    : names + " is offline and not an operator (add the Telegram ID to telegram.admin-ids to be safe)";
        } catch (Exception e) {
            return "could not check operator status of " + names + ": " + e;
        }
    }

    private void handleMessage(Message message) {
        long chatId = message.getChatId();
        long telegramId = message.getFrom().getId();
        String text = message.getText().trim();

        PendingAdminAction pending = awaitingReasonInput.remove(telegramId);
        if (pending != null) {
            if (adminAccessOrPrompt(telegramId, message)) {
                if (pending.type().equals("ban_reason")) {
                    awaitingReasonInput.put(telegramId, new PendingAdminAction("ban_duration", pending.uuid(), pending.playerName(), text));
                    send(chatId, "Send the ban duration (e.g. 1s, 5m, 2h, 7d) or 'p' for permanent.",
                            keyboard(inlineRow(button("Cancel", "admin:cancelreason:" + pending.uuid()))));
                } else if (pending.type().equals("ban_duration")) {
                    executeBan(telegramId, chatId, pending, text);
                } else if (pending.type().equals("warn_reason")) {
                    awaitingReasonInput.put(telegramId, new PendingAdminAction("warn_duration", pending.uuid(), pending.playerName(), text));
                    send(chatId, "Send the warn duration (e.g. 1s, 5m, 2h, 7d) or 'p' for no expiry.",
                            keyboard(inlineRow(button("Cancel", "admin:cancelreason:" + pending.uuid()))));
                } else if (pending.type().equals("warn_duration")) {
                    executeWarn(telegramId, chatId, pending, text);
                } else {
                    executeAdminAction(telegramId, chatId, pending, text);
                }
            }
            return;
        }

        if (awaitingSearchInput.remove(telegramId)) {
            if (adminAccessOrPrompt(telegramId, message)) {
                adminSearch(chatId, text);
            }
            return;
        }

        if (isPrivateChat(message.getChat()) && plugin.api().dispatchTelegramCommand(
                chatId, telegramId, message.getFrom().getUserName(), text)) {
            return;
        }

        if (text.startsWith("/start")) {
            send(chatId, plugin.lang().rawGet("telegram.start"));
            return;
        }

        if (text.startsWith("/admin")) {
            String denied = adminReason(telegramId);
            if (denied != null) {
                plugin.getLogger().info("Ignored /admin from Telegram ID " + telegramId + ": " + denied);
            }
            if (adminAccessOrPrompt(telegramId, message)) {
                sendAdminMenu(chatId);
            }
            return;
        }

        if (text.startsWith("/link")) {
            String[] parts = text.split("\\s+");
            if (parts.length < 2) {
                send(chatId, plugin.lang().rawGet("telegram.link-usage"));
                return;
            }
            String code = parts[1].trim();
            plugin.authManager().handleLinkAttempt(code, telegramId, message.getFrom().getUserName(), chatId);
            return;
        }

        send(chatId, plugin.lang().rawGet("telegram.unknown-command"));
    }

    private void handleCallback(CallbackQuery callback) {
        String data = callback.getData();
        long chatId = callback.getMessage().getChatId();
        int messageId = callback.getMessage().getMessageId();
        long telegramId = callback.getFrom().getId();

        if (data == null) return;

        if (data.startsWith("confirm:") || data.startsWith("reject:")) {
            boolean approve = data.startsWith("confirm:");
            String token = data.substring(data.indexOf(':') + 1);
            plugin.authManager().handleConfirmCallback(token, approve, chatId, messageId);
            answerCallback(callback.getId(), null);
            return;
        }

        if (data.startsWith("pin:")) {
            handlePinCallback(callback, data.substring("pin:".length()), chatId, messageId, telegramId);
            return;
        }

        if (data.startsWith("admin:")) {
            if (!isAdmin(telegramId)) {
                answerCallback(callback.getId(), "Not authorized.");
                return;
            }
            AdminPinService pin = plugin.adminPin();
            if (pin.enabled()) {
                if (!isPrivateCallback(callback)) {
                    answerCallback(callback.getId(), plugin.lang().rawGet("pin.private-only"));
                    return;
                }
                if (!pin.isUnlocked(telegramId)) {
                    answerCallback(callback.getId(), plugin.lang().rawGet("pin.required"));
                    promptPin(chatId, telegramId);
                    return;
                }
            }
            handleAdminCallback(telegramId, data.substring("admin:".length()), chatId, messageId);
            answerCallback(callback.getId(), null);
        }
    }

    private void handleAdminCallback(long telegramId, String action, long chatId, int messageId) {
        awaitingReasonInput.remove(telegramId);
        if (!action.equals("search")) {
            awaitingSearchInput.remove(chatId);
        }

        if (action.equals("lock")) {
            plugin.adminPin().lock(telegramId);
            awaitingPin.remove(telegramId);
            editText(chatId, messageId, plugin.lang().rawGet("pin.locked-manual"));
        } else if (action.equals("menu")) {
            editToAdminMenu(chatId, messageId);
        } else if (action.equals("search")) {
            awaitingSearchInput.add(chatId);
            editText(chatId, messageId, "Send the player's Minecraft name as your next message.");
        } else if (action.startsWith("list:")) {
            int page = parseIntOr(action.substring("list:".length()), 0);
            editToAdminList(chatId, messageId, page);
        } else if (action.startsWith("view:")) {
            editToAccountView(chatId, messageId, action.substring("view:".length()));
        } else if (action.startsWith("unlink:")) {
            String uuid = action.substring("unlink:".length());
            editText(chatId, messageId, "Unlink this account? This cannot be undone.",
                    keyboard(inlineRow(button("✅ Confirm unlink", "admin:unlinkconfirm:" + uuid),
                            button("Cancel", "admin:view:" + uuid))));
        } else if (action.startsWith("unlinkconfirm:")) {
            String uuidStr = action.substring("unlinkconfirm:".length());
            withAccount(uuidStr, a -> {
                boolean ok = plugin.database().unlink(a.uuid());
                editText(chatId, messageId, ok ? "✅ Unlinked." : "❌ Could not unlink (already removed?).",
                        keyboard(inlineRow(button("« Back", "admin:menu"))));
                if (ok) {
                    plugin.api().fireUnlinked(a);
                    notifyAdmins(telegramId, "unlinked " + a.username() + " from Telegram");
                }
            }, () -> editText(chatId, messageId, "❌ Could not unlink (already removed?).",
                    keyboard(inlineRow(button("« Back", "admin:menu")))));
        } else if (action.startsWith("unpremium:")) {
            String uuidStr = action.substring("unpremium:".length());
            withAccount(uuidStr, a -> {
                plugin.database().setPremium(a.uuid(), false);
                boolean fastLoginPresent = plugin.fastLoginHook().isFastLoginPresent();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (fastLoginPresent) {
                        try {
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "unpremium " + a.username());
                        } catch (Exception e) {
                            plugin.getLogger().warning("Could not run FastLogin's '/unpremium " + a.username() + "': " + e);
                        }
                    }
                });
                editText(chatId, messageId, "✅ Premium flag cleared" + (fastLoginPresent
                        ? " (also ran FastLogin's /unpremium)." : "."),
                        keyboard(inlineRow(button("« Back", "admin:view:" + uuidStr))));
                notifyAdmins(telegramId, "removed premium from " + a.username());
            }, () -> editText(chatId, messageId, "❌ Account not found."));
        } else if (action.startsWith("unban:")) {
            String uuidStr = action.substring("unban:".length());
            withAccount(uuidStr, a -> {
                String custom = plugin.cfg().unbanCommand();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!custom.isBlank()) {
                        dispatchConfiguredCommand(custom, a.username(), null, null);
                    } else {
                        Bukkit.getBanList(BanList.Type.NAME).pardon(a.username());
                    }
                });
                editText(chatId, messageId, "✅ Unbanned " + a.username() + ".",
                        keyboard(inlineRow(button("« Back", "admin:view:" + uuidStr))));
                notifyAdmins(telegramId, "unbanned " + a.username());
            }, () -> editText(chatId, messageId, "❌ Account not found."));
        } else if (action.startsWith("kick:")) {
            promptForReason(telegramId, chatId, messageId, "kick", action.substring("kick:".length()));
        } else if (action.startsWith("ban:")) {
            promptForReason(telegramId, chatId, messageId, "ban_reason", action.substring("ban:".length()));
        } else if (action.startsWith("warn:")) {
            promptForReason(telegramId, chatId, messageId, "warn_reason", action.substring("warn:".length()));
        } else if (action.startsWith("cancelreason:")) {
            editToAccountView(chatId, messageId, action.substring("cancelreason:".length()));
        }
    }

    private void promptForReason(long telegramId, long chatId, int messageId, String type, String uuidStr) {
        withAccount(uuidStr, a -> {
            awaitingReasonInput.put(telegramId, new PendingAdminAction(type, a.uuid(), a.username()));
            String verb = switch (type) {
                case "ban_reason" -> "banning";
                case "warn_reason" -> "warning";
                default -> type + "ing";
            };
            editText(chatId, messageId, "Send the reason for " + verb + " " + a.username() + " as your next message.",
                    keyboard(inlineRow(button("Cancel", "admin:cancelreason:" + uuidStr))));
        }, () -> editText(chatId, messageId, "❌ Account not found."));
    }

    private void executeAdminAction(long telegramId, long chatId, PendingAdminAction action, String reason) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            switch (action.type()) {
                case "kick" -> {
                    String custom = plugin.cfg().kickCommand();
                    if (!custom.isBlank()) {
                        boolean ok = dispatchConfiguredCommand(custom, action.playerName(), reason, null);
                        send(chatId, ok ? "✅ Ran kick command for " + action.playerName() + ": " + reason
                                : "❌ Kick command failed for " + action.playerName() + " (check console).");
                        if (ok) notifyAdmins(telegramId, action.playerName() + " kicked: " + reason);
                        return;
                    }
                    Player p = Bukkit.getPlayer(action.uuid());
                    if (p != null && p.isOnline()) {
                        p.kick(Component.text(reason));
                        send(chatId, "✅ Kicked " + action.playerName() + ": " + reason);
                        notifyAdmins(telegramId, action.playerName() + " kicked: " + reason);
                    } else {
                        send(chatId, "⚠ " + action.playerName() + " is not online - could not kick.");
                    }
                }
                default -> {
                }
            }
        });
    }

    private boolean dispatchConfiguredCommand(String template, String player, String reason, String duration) {
        String cmd = template
                .replace("%player%", player)
                .replace("%reason%", reason == null ? "" : reason)
                .replace("%duration%", duration == null ? "" : duration);
        try {
            return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        } catch (Exception e) {
            plugin.getLogger().warning("Configured admin command '" + cmd + "' threw an error: " + e);
            return false;
        }
    }

    /** Notifies every configured/eligible admin in Telegram about an action taken in the admin
     *  panel, except the admin who performed it. */
    private void notifyAdmins(long actingTelegramId, String message) {
        String adminName = plugin.database().findByTelegramId(actingTelegramId)
                .map(net.millyland.auth.storage.LinkedAccount::username)
                .orElse("Telegram admin (" + actingTelegramId + ")");
        String text = "[TgAuth] " + adminName + " " + message;

        java.util.Set<Long> recipients = new java.util.HashSet<>(plugin.cfg().adminTelegramIds());
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("tgauth.admin")) {
                plugin.database().findByUuid(p.getUniqueId()).ifPresent(acc -> recipients.add(acc.telegramId()));
            }
        }
        for (long id : recipients) {
            if (id != actingTelegramId) {
                send(id, text);
            }
        }
    }

    private static final long DURATION_PERMANENT = 0L;
    private static final long DURATION_INVALID = -1L;

    private long parseDuration(String input) {
        String s = input.trim().toLowerCase();
        if (s.equals("p") || s.equals("perm") || s.equals("permanent")) return DURATION_PERMANENT;
        var m = java.util.regex.Pattern.compile("^(\\d+)([smhdw])$").matcher(s);
        if (!m.matches()) return DURATION_INVALID;
        long amount = Long.parseLong(m.group(1));
        long unit = switch (m.group(2)) {
            case "s" -> 1000L;
            case "m" -> 60_000L;
            case "h" -> 3_600_000L;
            case "d" -> 86_400_000L;
            case "w" -> 604_800_000L;
            default -> -1L;
        };
        long total = amount * unit;
        return total <= 0 ? DURATION_INVALID : total;
    }

    private void executeBan(long telegramId, long chatId, PendingAdminAction action, String durationInput) {
        long parsed = parseDuration(durationInput);
        if (parsed == DURATION_INVALID) {
            awaitingReasonInput.put(telegramId, action);
            send(chatId, "❌ Invalid duration '" + durationInput + "'. Use e.g. 1s, 5m, 2h, 7d, or 'p' for permanent.",
                    keyboard(inlineRow(button("Cancel", "admin:cancelreason:" + action.uuid()))));
            return;
        }

        boolean permanent = parsed == DURATION_PERMANENT;

        Bukkit.getScheduler().runTask(plugin, () -> {
            String custom = permanent ? plugin.cfg().banCommand() : plugin.cfg().tempbanCommand();
            if (!custom.isBlank()) {
                boolean ok = dispatchConfiguredCommand(custom, action.playerName(), action.reason(), durationInput);
                send(chatId, ok ? "✅ Ran " + (permanent ? "ban" : "tempban") + " command for "
                                + action.playerName() + ": " + action.reason()
                        : "❌ " + (permanent ? "Ban" : "Tempban") + " command failed for " + action.playerName()
                                + " (check console).");
                if (ok) {
                    notifyAdmins(telegramId, action.playerName() + " banned "
                            + (permanent ? "permanently" : "for " + durationInput) + ": " + action.reason());
                }
                return;
            }

            java.util.Date expiry = permanent ? null : new java.util.Date(System.currentTimeMillis() + parsed);
            Bukkit.getBanList(BanList.Type.NAME).addBan(action.playerName(), action.reason(), expiry, null);
            Player p = Bukkit.getPlayer(action.uuid());
            if (p != null && p.isOnline()) {
                p.kick(Component.text("Banned: " + action.reason()));
            }
            String durationText = permanent ? "permanently" : "until " + expiry;
            send(chatId, "✅ Banned " + action.playerName() + " " + durationText + ": " + action.reason());
            notifyAdmins(telegramId, action.playerName() + " banned "
                    + (permanent ? "permanently" : "for " + durationInput) + ": " + action.reason());
        });
    }

    private void executeWarn(long telegramId, long chatId, PendingAdminAction action, String durationInput) {
        long parsed = parseDuration(durationInput);
        if (parsed == DURATION_INVALID) {
            awaitingReasonInput.put(telegramId, action);
            send(chatId, "❌ Invalid duration '" + durationInput + "'. Use e.g. 1s, 5m, 2h, 7d, or 'p' for no expiry.",
                    keyboard(inlineRow(button("Cancel", "admin:cancelreason:" + action.uuid()))));
            return;
        }

        boolean permanent = parsed == DURATION_PERMANENT;

        Bukkit.getScheduler().runTask(plugin, () -> {
            String custom = permanent ? plugin.cfg().warnCommand() : plugin.cfg().tempwarnCommand();
            if (!custom.isBlank()) {
                boolean ok = dispatchConfiguredCommand(custom, action.playerName(), action.reason(), durationInput);
                send(chatId, ok ? "✅ Ran " + (permanent ? "warn" : "tempwarn") + " command for "
                                + action.playerName() + ": " + action.reason()
                        : "❌ " + (permanent ? "Warn" : "Tempwarn") + " command failed for " + action.playerName()
                                + " (check console).");
                if (ok) {
                    notifyAdmins(telegramId, action.playerName() + " warned "
                            + (permanent ? "" : "for " + durationInput + " ") + ": " + action.reason());
                }
                return;
            }

            // Built-in fallback has no persistent warn storage/expiry of its own - the duration
            // is only noted in the message text for the player's/admin's information, it isn't
            // actually enforced. Configure tempwarn-command above for real duration enforcement
            // via a dedicated punishment plugin.
            Player p = Bukkit.getPlayer(action.uuid());
            String durationNote = permanent ? "" : " (expires in " + durationInput + ")";
            if (p != null && p.isOnline()) {
                p.sendMessage(Component.text("Warning: " + action.reason() + durationNote));
                send(chatId, "✅ Warned " + action.playerName() + ": " + action.reason());
            } else {
                send(chatId, "⚠ " + action.playerName() + " is not online - could not deliver warning.");
            }
            notifyAdmins(telegramId, action.playerName() + " warned"
                    + (permanent ? "" : " for " + durationInput) + ": " + action.reason());
        });
    }

    private void withAccount(String uuidStr, java.util.function.Consumer<LinkedAccount> onFound, Runnable onMissing) {
        Optional<LinkedAccount> acc;
        try {
            acc = plugin.database().findByUuid(UUID.fromString(uuidStr));
        } catch (IllegalArgumentException e) {
            acc = Optional.empty();
        }
        if (acc.isPresent()) {
            onFound.accept(acc.get());
        } else {
            onMissing.run();
        }
    }

    private void adminSearch(long chatId, String name) {
        var acc = plugin.database().findByUsername(name);
        if (acc.isEmpty()) {
            send(chatId, "No linked account found for '" + name + "'.");
            sendAdminMenu(chatId);
            return;
        }
        sendAccountView(chatId, acc.get());
    }

    private void sendAdminMenu(long chatId) {
        int total = plugin.database().countAll();
        SendMessage msg = new SendMessage();
        msg.setChatId(chatId);
        msg.setText("TgAuth admin panel — " + total + " linked account(s).");
        msg.setReplyMarkup(adminMenuMarkup());
        try {
            execute(msg);
        } catch (TelegramApiException e) {
            plugin.getLogger().warning("Failed to send admin menu: " + e.getMessage());
        }
    }

    private void editToAdminMenu(long chatId, int messageId) {
        int total = plugin.database().countAll();
        editText(chatId, messageId, "TgAuth admin panel — " + total + " linked account(s).", adminMenuMarkup());
    }

    private InlineKeyboardMarkup adminMenuMarkup() {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(inlineRow(button("🔍 Search player", "admin:search")));
        rows.add(inlineRow(button("📋 List accounts", "admin:list:0")));
        if (plugin.adminPin().enabled()) {
            rows.add(inlineRow(button(plugin.lang().rawGet("pin.button-lock"), "admin:lock")));
        }
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
    }

    private void editToAdminList(long chatId, int messageId, int page) {
        int total = plugin.database().countAll();
        int maxPage = Math.max(0, (total - 1) / ADMIN_PAGE_SIZE);
        page = Math.max(0, Math.min(page, maxPage));

        List<LinkedAccount> pageItems = plugin.database().findPage(page * ADMIN_PAGE_SIZE, ADMIN_PAGE_SIZE);
        StringBuilder sb = new StringBuilder("Linked accounts (page " + (page + 1) + "/" + (maxPage + 1) + "):");
        if (pageItems.isEmpty()) {
            sb.append("\n\n(none)");
        }

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (LinkedAccount a : pageItems) {
            rows.add(inlineRow(button("👤 " + a.username(), "admin:view:" + a.uuid())));
        }

        List<InlineKeyboardButton> navRow = new ArrayList<>();
        if (page > 0) navRow.add(button("« Prev", "admin:list:" + (page - 1)));
        navRow.add(button("Menu", "admin:menu"));
        if (page < maxPage) navRow.add(button("Next »", "admin:list:" + (page + 1)));
        rows.add(navRow);

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        editText(chatId, messageId, sb.toString(), markup);
    }

    private void sendAccountView(long chatId, LinkedAccount a) {
        send(chatId, formatAccount(a), accountViewMarkup(a));
    }

    private void editToAccountView(long chatId, int messageId, String uuidStr) {
        withAccount(uuidStr, a -> editText(chatId, messageId, formatAccount(a), accountViewMarkup(a)),
                () -> editText(chatId, messageId, "❌ Account not found.", keyboard(inlineRow(button("« Menu", "admin:menu")))));
    }

    private InlineKeyboardMarkup accountViewMarkup(LinkedAccount a) {
        String uuid = a.uuid().toString();
        return keyboard(
                inlineRow(button("🔗 Unlink", "admin:unlink:" + uuid), button("⭐ Un-premium", "admin:unpremium:" + uuid)),
                inlineRow(button("👢 Kick", "admin:kick:" + uuid), button("🔨 Ban", "admin:ban:" + uuid)),
                inlineRow(button("♻ Unban", "admin:unban:" + uuid), button("⚠ Warn", "admin:warn:" + uuid)),
                inlineRow(button("« Menu", "admin:menu")));
    }

    private String formatAccount(LinkedAccount a) {
        String tgUser = (a.telegramUsername() == null || a.telegramUsername().isBlank())
                ? "(no username)" : "@" + a.telegramUsername();
        return a.username() + "\n"
                + "  Telegram ID: " + a.telegramId() + "\n"
                + "  Telegram: " + tgUser + "\n"
                + "  Premium: " + (a.premium() ? "yes" : "no");
    }

    // ------------------------------------------------------------------ admin PIN

    /**
     * In telegrambots 6.9+ a callback's message is a MaybeInaccessibleMessage (it has no getChat());
     * only a regular Message carries the chat. An inaccessible (old) message counts as "not private".
     */
    private boolean isPrivateCallback(CallbackQuery callback) {
        return callback.getMessage() instanceof Message message && isPrivateChat(message.getChat());
    }

    private boolean isPrivateChat(Chat chat) {
        return chat != null && "private".equals(chat.getType());
    }

    /**
     * Returns true if this Telegram user is an admin whose panel is open to them right now. If they
     * are an admin but the panel is locked, starts the PIN prompt and returns false. Non-admins get
     * no reaction at all, as before.
     */
    private boolean adminAccessOrPrompt(long telegramId, Message message) {
        if (!isAdmin(telegramId)) return false;

        AdminPinService pin = plugin.adminPin();
        if (!pin.enabled()) return true;

        long chatId = message.getChatId();
        if (!isPrivateChat(message.getChat())) {
            send(chatId, plugin.lang().rawGet("pin.private-only"));
            return false;
        }
        if (pin.isUnlocked(telegramId)) return true;

        promptPin(chatId, telegramId);
        return false;
    }

    /** Opens a fresh inline PIN keypad (enter the PIN, or set one if the admin has none yet). */
    private void promptPin(long chatId, long telegramId) {
        AdminPinService pin = plugin.adminPin();

        long lockLeft = pin.lockRemainingSeconds(telegramId);
        if (lockLeft > 0) {
            send(chatId, plugin.lang().rawGet("pin.locked", "%time%", pin.formatDuration(lockLeft)));
            return;
        }

        PinStep step = pin.hasPin(telegramId) ? PinStep.ENTER : PinStep.SETUP_FIRST;
        showKeypad(chatId, telegramId,
                new PinPrompt(step, null, System.currentTimeMillis() + PIN_PROMPT_TTL_MS, "", 0), null, 0);
    }

    private String keypadText(PinPrompt prompt, String notice) {
        String header = switch (prompt.step()) {
            case ENTER -> plugin.lang().rawGet("pin.kp-enter");
            case SETUP_FIRST -> plugin.lang().rawGet("pin.kp-setup-first",
                    "%min%", String.valueOf(plugin.cfg().adminPinMinLength()),
                    "%max%", String.valueOf(plugin.cfg().adminPinMaxLength()));
            case SETUP_CONFIRM -> plugin.lang().rawGet("pin.kp-setup-confirm");
        };
        int n = prompt.digits().length();
        String mask = n == 0 ? "—" : "●".repeat(n);
        return (notice != null ? notice + "\n\n" : "") + header + "\n\n"
                + plugin.lang().rawGet("pin.kp-display", "%mask%", mask);
    }

    private InlineKeyboardMarkup keypadMarkup() {
        return keyboard(
                inlineRow(button("1", "pin:d:1"), button("2", "pin:d:2"), button("3", "pin:d:3")),
                inlineRow(button("4", "pin:d:4"), button("5", "pin:d:5"), button("6", "pin:d:6")),
                inlineRow(button("7", "pin:d:7"), button("8", "pin:d:8"), button("9", "pin:d:9")),
                inlineRow(button("⌫", "pin:bs"), button("0", "pin:d:0"),
                        button(plugin.lang().rawGet("pin.kp-button-ok"), "pin:ok")),
                inlineRow(button(plugin.lang().rawGet("pin.kp-button-cancel"), "pin:cancel")));
    }

    /**
     * Shows the keypad: edits the existing keypad message when {@code editMessageId} is set,
     * otherwise sends a new one, and remembers the prompt state.
     */
    private void showKeypad(long chatId, long telegramId, PinPrompt prompt, String notice, int editMessageId) {
        String text = keypadText(prompt, notice);
        int messageId = editMessageId;
        if (editMessageId > 0) {
            editText(chatId, editMessageId, text, keypadMarkup());
        } else {
            SendMessage msg = new SendMessage();
            msg.setChatId(chatId);
            msg.setText(text);
            msg.setReplyMarkup(keypadMarkup());
            try {
                messageId = execute(msg).getMessageId();
            } catch (TelegramApiException e) {
                plugin.getLogger().warning("Failed to send the PIN keypad: " + e.getMessage());
                return;
            }
        }
        awaitingPin.put(telegramId, new PinPrompt(prompt.step(), prompt.draft(), prompt.expiresAt(),
                prompt.digits(), messageId));
    }

    private void handlePinCallback(CallbackQuery callback, String action, long chatId, int messageId, long telegramId) {
        if (!isAdmin(telegramId)) {
            answerCallback(callback.getId(), "Not authorized.");
            return;
        }
        if (!isPrivateCallback(callback)) {
            answerCallback(callback.getId(), plugin.lang().rawGet("pin.private-only"));
            return;
        }

        PinPrompt prompt = awaitingPin.get(telegramId);
        if (prompt == null || prompt.messageId() != messageId || System.currentTimeMillis() > prompt.expiresAt()) {
            if (prompt != null && prompt.messageId() == messageId) {
                awaitingPin.remove(telegramId);
            }
            editText(chatId, messageId, plugin.lang().rawGet("pin.kp-expired"));
            answerCallback(callback.getId(), plugin.lang().rawGet("pin.kp-expired"));
            return;
        }

        AdminPinService pin = plugin.adminPin();
        long lockLeft = pin.lockRemainingSeconds(telegramId);
        if (lockLeft > 0) {
            awaitingPin.remove(telegramId);
            editText(chatId, messageId, plugin.lang().rawGet("pin.locked", "%time%", pin.formatDuration(lockLeft)));
            answerCallback(callback.getId(), null);
            return;
        }

        answerCallback(callback.getId(), null);

        switch (action) {
            case "bs" -> {
                String d = prompt.digits();
                if (!d.isEmpty()) {
                    updateDigits(chatId, telegramId, prompt, d.substring(0, d.length() - 1));
                }
            }
            case "cancel" -> {
                awaitingPin.remove(telegramId);
                editText(chatId, messageId, plugin.lang().rawGet("pin.kp-cancelled"));
            }
            case "ok" -> submitPin(chatId, telegramId, messageId, prompt);
            default -> {
                if (action.length() == 3 && action.startsWith("d:")
                        && action.charAt(2) >= '0' && action.charAt(2) <= '9') {
                    if (prompt.digits().length() < plugin.cfg().adminPinMaxLength()) {
                        updateDigits(chatId, telegramId, prompt, prompt.digits() + action.charAt(2));
                    }
                }
            }
        }
    }

    private void updateDigits(long chatId, long telegramId, PinPrompt prompt, String digits) {
        showKeypad(chatId, telegramId, new PinPrompt(prompt.step(), prompt.draft(), prompt.expiresAt(),
                digits, prompt.messageId()), null, prompt.messageId());
    }

    private void submitPin(long chatId, long telegramId, int messageId, PinPrompt prompt) {
        AdminPinService pin = plugin.adminPin();
        String input = prompt.digits();
        long expiresAt = System.currentTimeMillis() + PIN_PROMPT_TTL_MS;

        if (input.isEmpty()) return;
        awaitingPin.remove(telegramId);

        switch (prompt.step()) {
            case ENTER -> {
                AdminPinService.VerifyResult result = pin.verify(telegramId, input);
                switch (result.status()) {
                    case OK -> {
                        pin.unlock(telegramId);
                        editText(chatId, messageId, plugin.lang().rawGet("pin.unlocked"));
                        sendAdminMenu(chatId);
                    }
                    case WRONG -> showKeypad(chatId, telegramId,
                            new PinPrompt(PinStep.ENTER, null, expiresAt, "", messageId),
                            plugin.lang().rawGet("pin.wrong", "%left%", String.valueOf(result.attemptsLeft())),
                            messageId);
                    case LOCKED -> editText(chatId, messageId, plugin.lang().rawGet("pin.locked",
                            "%time%", pin.formatDuration(result.lockSeconds())));
                    case NO_PIN -> showKeypad(chatId, telegramId,
                            new PinPrompt(PinStep.SETUP_FIRST, null, expiresAt, "", messageId), null, messageId);
                }
            }
            case SETUP_FIRST -> {
                if (!pin.validFormat(input)) {
                    showKeypad(chatId, telegramId,
                            new PinPrompt(PinStep.SETUP_FIRST, null, expiresAt, "", messageId),
                            plugin.lang().rawGet("pin.invalid-format",
                                    "%min%", String.valueOf(plugin.cfg().adminPinMinLength()),
                                    "%max%", String.valueOf(plugin.cfg().adminPinMaxLength())),
                            messageId);
                    return;
                }
                showKeypad(chatId, telegramId,
                        new PinPrompt(PinStep.SETUP_CONFIRM, pin.draft(input), expiresAt, "", messageId),
                        null, messageId);
            }
            case SETUP_CONFIRM -> {
                if (prompt.draft() == null || !pin.matches(prompt.draft(), input)) {
                    showKeypad(chatId, telegramId,
                            new PinPrompt(PinStep.SETUP_FIRST, null, expiresAt, "", messageId),
                            plugin.lang().rawGet("pin.kp-mismatch"), messageId);
                    return;
                }
                if (!pin.savePin(telegramId, prompt.draft())) {
                    editText(chatId, messageId, plugin.lang().rawGet("pin.save-failed"));
                    return;
                }
                pin.unlock(telegramId);
                plugin.authManager().onAdminPinSet(telegramId);
                editText(chatId, messageId, plugin.lang().rawGet("pin.setup-done"));
                notifyAdmins(telegramId, plugin.lang().rawGet("pin.notify-set"));
                sendAdminMenu(chatId);
            }
        }
    }

    private int parseIntOr(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public void sendConfirmRequest(long chatId, String text, String token) {
        SendMessage msg = new SendMessage();
        msg.setChatId(chatId);
        msg.setText(text);
        msg.setReplyMarkup(keyboard(inlineRow(
                button(plugin.lang().rawGet("confirm.button-confirm"), "confirm:" + token),
                button(plugin.lang().rawGet("confirm.button-reject"), "reject:" + token))));

        try {
            var sent = execute(msg);
            plugin.authManager().registerTelegramMessage(token, chatId, sent.getMessageId());
        } catch (TelegramApiException e) {
            plugin.getLogger().warning("Failed to send Telegram confirm request: " + e.getMessage());
        }
    }

    public void editConfirmResult(long chatId, int messageId, String newText) {
        try {
            EditMessageText edit = new EditMessageText();
            edit.setChatId(chatId);
            edit.setMessageId(messageId);
            edit.setText(newText);
            execute(edit);

            EditMessageReplyMarkup clearMarkup = new EditMessageReplyMarkup();
            clearMarkup.setChatId(chatId);
            clearMarkup.setMessageId(messageId);
            clearMarkup.setReplyMarkup(null);
            execute(clearMarkup);
        } catch (TelegramApiException e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("message is not modified")) {

                return;
            }
            plugin.getLogger().warning("Failed to edit Telegram message: " + msg);
        }
    }

    public void send(long chatId, String text) {
        send(chatId, text, null);
    }

    private void send(long chatId, String text, InlineKeyboardMarkup markup) {
        SendMessage msg = new SendMessage();
        msg.setChatId(chatId);
        msg.setText(text);
        if (markup != null) msg.setReplyMarkup(markup);
        try {
            execute(msg);
        } catch (TelegramApiException e) {
            plugin.getLogger().warning("Failed to send Telegram message: " + e.getMessage());
        }
    }

    private void editText(long chatId, int messageId, String text) {
        editText(chatId, messageId, text, null);
    }

    private void editText(long chatId, int messageId, String text, InlineKeyboardMarkup markup) {
        try {
            EditMessageText edit = new EditMessageText();
            edit.setChatId(chatId);
            edit.setMessageId(messageId);
            edit.setText(text);
            if (markup != null) edit.setReplyMarkup(markup);
            execute(edit);
        } catch (TelegramApiException e) {
            String msg = e.getMessage();
            if (msg == null || !msg.contains("message is not modified")) {
                plugin.getLogger().warning("Failed to edit Telegram message: " + msg);
            }
        }
    }

    private InlineKeyboardButton button(String text, String callbackData) {
        InlineKeyboardButton btn = new InlineKeyboardButton();
        btn.setText(text);
        btn.setCallbackData(callbackData);
        return btn;
    }

    private List<InlineKeyboardButton> inlineRow(InlineKeyboardButton... buttons) {
        List<InlineKeyboardButton> row = new ArrayList<>();
        for (InlineKeyboardButton b : buttons) row.add(b);
        return row;
    }

    @SafeVarargs
    private InlineKeyboardMarkup keyboard(List<InlineKeyboardButton>... rows) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();
        for (List<InlineKeyboardButton> row : rows) keyboard.add(row);
        markup.setKeyboard(keyboard);
        return markup;
    }

    private void answerCallback(String callbackId, String text) {
        try {
            AnswerCallbackQuery answer = new AnswerCallbackQuery();
            answer.setCallbackQueryId(callbackId);
            if (text != null) answer.setText(text);
            execute(answer);
        } catch (TelegramApiException ignored) {
        }
    }
}
