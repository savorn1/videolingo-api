package com.example.videolingo.apikey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

// Generating and hashing keys. Pure/static so it's unit-tested.
public final class ApiKeys {

    public static final String PREFIX = "vl_";
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    // 40 base-62 characters ≈ 238 bits of randomness.
    private static final int LENGTH = 40;
    // Characters kept visible for telling keys apart: "vl_" + 8.
    static final int VISIBLE = PREFIX.length() + 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeys() {
    }

    public static String generate() {
        StringBuilder sb = new StringBuilder(PREFIX);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    public static String visiblePrefix(String key) {
        return key.substring(0, Math.min(VISIBLE, key.length()));
    }

    /** Whether `value` is shaped like one of our keys (so the Bearer header can carry either a key or a JWT). */
    public static boolean looksLikeKey(String value) {
        return value != null && value.startsWith(PREFIX) && value.length() == PREFIX.length() + LENGTH
                && value.substring(PREFIX.length()).chars().allMatch(c -> ALPHABET.indexOf(c) >= 0);
    }

    // A plain SHA-256 is enough here (unlike passwords): the key is long and
    // random, so there's nothing to brute-force from the hash.
    public static String hash(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
