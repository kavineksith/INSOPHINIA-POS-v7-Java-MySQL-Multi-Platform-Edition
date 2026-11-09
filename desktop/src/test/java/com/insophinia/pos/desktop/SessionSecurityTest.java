package com.insophinia.pos.desktop;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SessionSecurityTest {
    @Test void allowsLoopbackHttpForLocalDesktopServer() {
        Session.baseUrl = "http://localhost:8080/";
        assertEquals("http://localhost:8080", Session.validatedBaseUrl());
    }
    @Test void rejectsRemotePlainHttpToProtectCredentialsAndTokens() {
        Session.baseUrl = "http://192.168.1.10:8080";
        assertThrows(IllegalArgumentException.class, Session::validatedBaseUrl);
    }
    @Test void allowsHttpsRemoteApi() {
        Session.baseUrl = "https://pos.example.com";
        assertEquals("https://pos.example.com", Session.validatedBaseUrl());
    }
    @Test void rejectsApiPathInBaseUrl() {
        Session.baseUrl = "https://pos.example.com/api";
        assertThrows(IllegalArgumentException.class, Session::validatedBaseUrl);
    }
}
