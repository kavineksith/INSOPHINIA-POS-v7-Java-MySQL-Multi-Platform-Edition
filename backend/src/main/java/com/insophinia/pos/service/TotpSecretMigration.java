package com.insophinia.pos.service;

import com.insophinia.pos.entity.User;
import com.insophinia.pos.repository.UserRepository;
import com.insophinia.pos.security.SecretCryptoService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** One-time in-place migration of legacy plaintext TOTP seeds to AES-GCM envelopes. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class TotpSecretMigration implements CommandLineRunner {
    private final UserRepository users;
    private final SecretCryptoService crypto;
    public TotpSecretMigration(UserRepository users, SecretCryptoService crypto) { this.users = users; this.crypto = crypto; }
    @Override @Transactional
    public void run(String... args) {
        for (User user : users.findAll()) {
            String secret = user.getTwoFactorSecret();
            if (secret != null && !secret.startsWith("enc:v1:")) {
                user.setTwoFactorSecret(crypto.encrypt(secret));
                users.save(user);
            } else if (secret != null) {
                // Fail fast if the configured key is absent or wrong; otherwise all 2FA logins would fail later.
                crypto.decrypt(secret);
            }
        }
    }
}
