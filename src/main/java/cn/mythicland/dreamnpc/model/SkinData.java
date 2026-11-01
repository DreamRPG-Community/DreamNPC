package cn.mythicland.dreamnpc.model;

import java.util.Objects;

/**
 * A configured premium name with optional resolved or fixed signed textures.
 *
 * @param playerName original requested player name
 * @param value      base64 texture value, or null until resolved
 * @param signature  Mojang texture signature, or null until resolved
 * @param fixed      whether the supplied signed texture is persisted and used without fetching
 */
public record SkinData(
        String playerName,
        String value,
        String signature,
        boolean fixed
) {

    public SkinData(String playerName, String value, String signature) {
        this(playerName, value, signature, false);
    }

    public SkinData {
        playerName = requireText(playerName, "playerName");
        if (!playerName.matches("[A-Za-z0-9_]{1,16}"))
            throw new IllegalArgumentException("Invalid premium player name: " + playerName);
        if ((value == null) != (signature == null))
            throw new IllegalArgumentException("Skin texture and signature must be supplied together");
        if (fixed && value == null) throw new IllegalArgumentException("Fixed skin requires signed textures");
        if (value != null) {
            value = requireText(value, "value");
            signature = requireText(signature, "signature");
        }
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isBlank()) throw new IllegalArgumentException(name + " cannot be blank");
        return normalized;
    }
}
