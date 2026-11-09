package com.insophinia.pos.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BackupCodeHashTest {
    @Test void saltedBackupCodeHashVerifiesAndIsNotDeterministic() {
        String first = AuthService.hashBackupCode("ABCD1234");
        String second = AuthService.hashBackupCode("ABCD1234");
        assertNotEquals(first, second);
        assertTrue(AuthService.verifyBackupCode("abcd1234", first));
        assertFalse(AuthService.verifyBackupCode("wrong-code", first));
    }
}
