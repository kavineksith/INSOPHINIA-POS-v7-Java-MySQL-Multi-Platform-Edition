package com.insophinia.pos.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** AES-256-GCM envelope encryption for TOTP seeds stored in the database. */
@Service
public class SecretCryptoService {
    private final String configuredKey; private final SecureRandom random = new SecureRandom();
    public SecretCryptoService(@Value("${app.security.totp-encryption-key:}") String configuredKey) { this.configuredKey = configuredKey; }
    public String encrypt(String plain) {
        if (plain == null) return null; if (plain.startsWith("enc:v1:")) return plain; requireKey();
        try { byte[] nonce = new byte[12]; random.nextBytes(nonce); Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, nonce)); c.updateAAD("INSOPHINIA:TOTP:v1".getBytes(StandardCharsets.UTF_8));
            byte[] data = c.doFinal(plain.getBytes(StandardCharsets.UTF_8)); ByteBuffer b = ByteBuffer.allocate(nonce.length + data.length); b.put(nonce).put(data);
            return "enc:v1:" + Base64.getEncoder().encodeToString(b.array());
        } catch (Exception e) { throw new IllegalStateException("Unable to encrypt TOTP secret", e); }
    }
    public String decrypt(String value) {
        if (value == null) return null; if (!value.startsWith("enc:v1:")) return value; requireKey();
        try { byte[] data = Base64.getDecoder().decode(value.substring(7)); ByteBuffer b = ByteBuffer.wrap(data); byte[] nonce = new byte[12]; b.get(nonce); byte[] cipherText = new byte[b.remaining()]; b.get(cipherText);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, nonce)); c.updateAAD("INSOPHINIA:TOTP:v1".getBytes(StandardCharsets.UTF_8));
            return new String(c.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IllegalStateException("TOTP secret decryption failed; check TOTP_ENCRYPTION_KEY", e); }
    }
    private void requireKey() { if (configuredKey == null || configuredKey.length() < 32) throw new IllegalStateException("Set TOTP_ENCRYPTION_KEY to a random secret of at least 32 characters"); }
    private SecretKeySpec key() throws Exception { return new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(configuredKey.getBytes(StandardCharsets.UTF_8)), "AES"); }
}
