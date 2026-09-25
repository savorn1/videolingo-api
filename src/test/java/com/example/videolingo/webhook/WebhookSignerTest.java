package com.example.videolingo.webhook;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookSignerTest {

    @Test
    void aReceiverCanVerifyTheSignature() {
        String secret = WebhookSigner.newSecret();
        String body = "{\"event\":\"job.succeeded\"}";
        String header = WebhookSigner.signature(secret, 1_700_000_000L, body);
        assertTrue(header.startsWith("t=1700000000,v1="));
        assertTrue(WebhookSigner.verify(secret, header, body));
    }

    @Test
    void rejectsTamperingAndTheWrongSecret() {
        String secret = WebhookSigner.newSecret();
        String header = WebhookSigner.signature(secret, 1L, "{}");
        assertFalse(WebhookSigner.verify(secret, header, "{ }"));
        assertFalse(WebhookSigner.verify(WebhookSigner.newSecret(), header, "{}"));
        assertFalse(WebhookSigner.verify(secret, header.replace("t=1", "t=2"), "{}"));
        assertFalse(WebhookSigner.verify(secret, null, "{}"));
    }
}
