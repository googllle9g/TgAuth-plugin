package net.millyland.auth.storage;

/** A stored admin-panel PIN: PBKDF2 hash + salt (both Base64), never the PIN itself. */
public record AdminPinRecord(long telegramId, String salt, String hash, int iterations, long setAt) {
}
