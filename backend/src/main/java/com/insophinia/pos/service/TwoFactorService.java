package com.insophinia.pos.service;

import com.insophinia.pos.dto.AuthDtos.TwoFactorSetupResponse;
import com.insophinia.pos.entity.User;
import com.insophinia.pos.exception.BadRequestException;
import com.insophinia.pos.exception.ResourceNotFoundException;
import com.insophinia.pos.exception.UnauthorizedException;
import com.insophinia.pos.repository.UserRepository;
import com.insophinia.pos.security.TotpService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TwoFactorService {

    private final UserRepository userRepository;
    private final TotpService totpService;
    private final PasswordEncoder passwordEncoder;
    private final com.insophinia.pos.security.SecretCryptoService secretCrypto;
    private final SecurityEventService securityEvents;

    public TwoFactorService(UserRepository userRepository, TotpService totpService,
                            PasswordEncoder passwordEncoder, com.insophinia.pos.security.SecretCryptoService secretCrypto,
                            SecurityEventService securityEvents) {
        this.userRepository = userRepository;
        this.totpService = totpService;
        this.passwordEncoder = passwordEncoder;
        this.secretCrypto = secretCrypto;
        this.securityEvents = securityEvents;
    }

    @Transactional
    public TwoFactorSetupResponse setup(UUID userId, String password) {
        User user = getUser(userId);
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new UnauthorizedException("Password is incorrect");
        }
        if (user.isTwoFactorEnabled()) {
            throw new BadRequestException("Two-factor authentication is already enabled");
        }
        String secret = totpService.generateSecret();
        user.setTwoFactorSecret(secretCrypto.encrypt(secret)); // encrypted at rest
        userRepository.save(user);

        List<String> backupCodes = new AuthServiceHelper().genCodes();
        user.setTwoFactorBackupCodes(backupCodes.stream()
                .map(AuthService::hashBackupCode).collect(Collectors.joining(",")));
        userRepository.save(user);
        securityEvents.log(userId, "TWO_FACTOR_SETUP", "unknown", "", "2FA setup initiated", "medium");

        return new TwoFactorSetupResponse(secret, totpService.buildOtpAuthUri(secret, user.getUsername()), backupCodes);
    }

    @Transactional
    public void enable(UUID userId, String code) {
        User user = getUser(userId);
        if (user.getTwoFactorSecret() == null) {
            throw new BadRequestException("Run 2FA setup first");
        }
        if (!totpService.verify(secretCrypto.decrypt(user.getTwoFactorSecret()), code)) {
            throw new UnauthorizedException("Invalid verification code");
        }
        user.setTwoFactorEnabled(true);
        userRepository.save(user);
        securityEvents.log(userId, "TWO_FACTOR_ENABLED", "unknown", "", "2FA enabled", "medium");
    }

    @Transactional
    public void disable(UUID userId, String password) {
        User user = getUser(userId);
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new UnauthorizedException("Password is incorrect");
        }
        user.setTwoFactorEnabled(false);
        user.setTwoFactorSecret(null);
        user.setTwoFactorBackupCodes(null);
        userRepository.save(user);
        securityEvents.log(userId, "TWO_FACTOR_DISABLED", "unknown", "", "2FA disabled", "high");
    }

    private User getUser(UUID userId) {
        return userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    /** Delegates code generation so this class has no SecureRandom duplication concerns. */
    private static final class AuthServiceHelper {
        List<String> genCodes() {
            java.security.SecureRandom random = new java.security.SecureRandom();
            java.util.List<String> codes = new java.util.ArrayList<>();
            for (int i = 0; i < 10; i++) codes.add(String.format("%08x", random.nextInt()));
            return codes;
        }
    }
}
