package com.insophinia.pos.service;

import com.insophinia.pos.security.SecretCryptoService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SecretCryptoServiceTest {
    private final SecretCryptoService crypto = new SecretCryptoService("test-key-that-is-at-least-thirty-two-characters");
    @Test void encryptsAndDecryptsTotpSecret() {
        String secret = "JBSWY3DPEHPK3PXP";
        String encrypted = crypto.encrypt(secret);
        assertNotEquals(secret, encrypted);
        assertTrue(encrypted.startsWith("enc:v1:"));
        assertEquals(secret, crypto.decrypt(encrypted));
    }
    @Test void usesDifferentCiphertextForSamePlaintext() {
        assertNotEquals(crypto.encrypt("secret-value"), crypto.encrypt("secret-value"));
    }
    @Test void remainsCompatibleWithLegacyPlaintextForGradualMigration() {
        assertEquals("LEGACYSECRET", crypto.decrypt("LEGACYSECRET"));
    }
}
