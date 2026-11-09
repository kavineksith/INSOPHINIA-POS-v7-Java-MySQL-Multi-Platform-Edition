package com.insophinia.pos.desktop;

import java.util.List;
import java.net.URI;
import java.util.Locale;

/** Holds the authenticated session: JWT, user identity, role permissions. */
public final class Session {
    public static String baseUrl = System.getenv().getOrDefault("POS_API_URL", "http://localhost:8080");
    public static String token;
    public static String username;
    public static String role;
    public static String fullName;
    public static String displayChannel = loadDisplayChannel();
    public static String terminalId = System.getenv().getOrDefault("POS_TERMINAL_ID", java.util.UUID.randomUUID().toString());
    public static boolean customerDisplayOnly = Boolean.parseBoolean(System.getenv().getOrDefault("POS_CUSTOMER_DISPLAY_ONLY", "false"));

    private static String loadDisplayChannel() {
        String fromEnvironment = System.getenv().getOrDefault("POS_DISPLAY_CHANNEL", "").trim();
        if (fromEnvironment.length() >= 16) return fromEnvironment;
        try {
            java.nio.file.Path config = java.nio.file.Path.of(System.getProperty("user.home"), ".config", "insophinia-pos", "display-channel");
            if (java.nio.file.Files.isRegularFile(config)) {
                String fromFile = java.nio.file.Files.readString(config).trim();
                if (fromFile.length() >= 16) return fromFile;
            }
        } catch (java.io.IOException ignored) { }
        return "";
    }
    public static List<String> permissions = List.of();

    /** Refuse to send credentials/JWTs to a remote server over plain HTTP. */
    public static String validatedBaseUrl() {
        String raw = baseUrl == null ? "" : baseUrl.trim();
        URI uri;
        try { uri = URI.create(raw); } catch (Exception e) { throw new IllegalArgumentException("Enter a valid server URL."); }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        String bareHost = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if ((!scheme.equals("http") && !scheme.equals("https")) || host.isBlank() || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("Server URL must be an http(s) origin without credentials, query, or fragment.");
        boolean loopbackHttp = scheme.equals("http") && (bareHost.equals("localhost") || bareHost.equals("127.0.0.1") || bareHost.equals("::1"));
        if (!scheme.equals("https") && !loopbackHttp)
            throw new IllegalArgumentException("Remote servers must use HTTPS. Plain HTTP is allowed only for localhost/loopback.");
        String path = uri.getPath();
        if (path != null && !path.isEmpty() && !path.equals("/")) throw new IllegalArgumentException("Server URL must not include an API path.");
        String normalizedHost = bareHost.contains(":") ? "[" + bareHost + "]" : bareHost;
        String normalized = scheme + "://" + normalizedHost + (uri.getPort() >= 0 ? ":" + uri.getPort() : "");
        return normalized;
    }

    public static boolean has(String permission) {
        return permissions.contains(permission);
    }

    public static void clear() {
        token = null;
        username = null;
        role = null;
        fullName = null;
        permissions = List.of();
    }
}
