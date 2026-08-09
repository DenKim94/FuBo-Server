package de.fubo.appserver.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Erzeugt opake Session-Tokens und bildet deren Speicher-Hash. */
public final class TokenGenerator {

    private static final int TOKEN_BYTES = 32;   // 256 Bit Entropie
    private static final SecureRandom ZUFALL = new SecureRandom();

    /** Erzeugt einen neuen Token in URL-sicherer Base64-Kodierung (43 Zeichen). */
    public static String erzeugeToken() {
        byte[] raw = new byte[TOKEN_BYTES];
        ZUFALL.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** Bildet den SHA-256-Hash als Hex-Zeichenkette (64 Zeichen, passend zu CHAR(64)). */
    public static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 nicht verfügbar", e);
        }
    }
}
