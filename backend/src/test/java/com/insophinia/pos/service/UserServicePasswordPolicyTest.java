package com.insophinia.pos.service;

import com.insophinia.pos.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UserServicePasswordPolicyTest {
    @Test void acceptsComplexPassword() {
        assertDoesNotThrow(() -> UserService.validatePassword("L0ng!Secure-Pass", "kavin"));
    }
    @Test void rejectsShortPassword() {
        assertThrows(BadRequestException.class, () -> UserService.validatePassword("Sh0rt!", "kavin"));
    }
    @Test void rejectsMissingComplexity() {
        assertThrows(BadRequestException.class, () -> UserService.validatePassword("longpasswordonly", "kavin"));
    }
    @Test void rejectsUsernameInsidePassword() {
        assertThrows(BadRequestException.class, () -> UserService.validatePassword("Kavin!LongPass123", "kavin"));
    }
}
