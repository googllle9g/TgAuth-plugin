package net.millyland.auth.storage;

import java.util.UUID;

/** A game account linked through a login provider (Discord, ...). */
public record ExternalLink(UUID uuid, String provider, String externalId, String externalName,
                           String username, long linkedAt, boolean premium) {
}
