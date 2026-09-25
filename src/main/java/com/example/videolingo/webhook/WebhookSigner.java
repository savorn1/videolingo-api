package com.example.videolingo.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

// How webhook messages are signed. The receiver recomputes
//   HMAC-SHA256(secret, "<timestamp>.<raw body>")
// and compares it with the v1 value of the X-VideoLingo-Signature header
// ("t=<timestamp>,v1=<hex>"). Including the timestamp lets receivers
// reject old messages being replayed.
public final class WebhookSigner {

    public static final String SIGNATURE_HEADER = "X-VideoLingo-Signature";
    private static final SecureRandom RANDOM = new SecureRandom();

    private WebhookSigner() {
    }

    public static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "whsec_" + HexFormat.of().formatHex(bytes);
    }

    public static String signature(String secret, long timestampSeconds, String body) {
        return "t=" + timestampSeconds + ",v1=" + hmac(secret, timestampSeconds + "." + body);
    }

    /** What a receiver does — used by the tests, and a reference for integrators. */
    public static boolean verify(String secret, String header, String body) {
        if (header == null) {
            return false;
        }
        Long t = null;
        String v1 = null;
        for (String part : header.split(",")) {
            String[] kv = part.strip().split("=", 2);
            if (kv.length == 2 && kv[0].equals("t")) {
                try {
                    t = Long.parseLong(kv[1]);
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if (kv.length == 2 && kv[0].equals("v1")) {
                v1 = kv[1];
            }
        }
        if (t == null || v1 == null) {
            return false;
        }
        return MessageDigest.isEqual(hmac(secret, t + "." + body).getBytes(StandardCharsets.US_ASCII), v1.getBytes(StandardCharsets.US_ASCII));
    }

    private static String hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
