package com.insophinia.pos.security;

import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;
import org.springframework.stereotype.Service;

/** TOTP two-factor auth (Google Authenticator compatible), replaces otplib. */
@Service
public class TotpService {

    private final SecretGenerator secretGenerator = new DefaultSecretGenerator();
    private final CodeVerifier verifier;

    public TotpService() {
        TimeProvider time = new SystemTimeProvider();
        CodeGenerator generator = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
        this.verifier = new DefaultCodeVerifier(generator, time);
    }

    public String generateSecret() {
        return secretGenerator.generate();
    }

    public boolean verify(String secret, String code) {
        return code != null && verifier.isValidCode(secret, code);
    }

    public String buildOtpAuthUri(String secret, String accountName) {
        QrData data = new QrData.Builder()
                .label(accountName)
                .secret(secret)
                .issuer("INSOPHINIA POS")
                .algorithm(HashingAlgorithm.SHA1)
                .digits(6)
                .period(30)
                .build();
        return data.getUri();
    }
}
