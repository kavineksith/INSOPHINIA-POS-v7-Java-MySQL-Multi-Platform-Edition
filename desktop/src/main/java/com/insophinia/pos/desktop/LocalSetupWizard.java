package com.insophinia.pos.desktop;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.*;

/**
 * First-run local database wizard: writes a protected Compose .env and can
 * start MySQL + Spring Boot using docker compose.
 *
 * Assumes the repository root contains `docker-compose.yml` (the canonical
 * location since the MySQL migration). The wizard no longer searches for
 * `backend/docker-compose.yml`.
 */
public final class LocalSetupWizard {
    private final Stage stage = new Stage();
    private final TextField projectDir = new TextField(findProjectRoot());
    private final TextField dbName = new TextField("insophinia_pos");
    private final TextField dbUser = new TextField("insophinia");
    private final PasswordField dbPassword = new PasswordField();
    private final PasswordField dbRootPassword = new PasswordField();
    private final TextField dbPort = new TextField("3306");
    private final TextField apiPort = new TextField("8080");
    private final PasswordField adminPassword = new PasswordField();
    private final TextField smtpHost = new TextField();
    private final TextField smtpPort = new TextField("587");
    private final TextField smtpUser = new TextField();
    private final PasswordField smtpPassword = new PasswordField();
    private final Label status = new Label("Creates a local-only MySQL + Spring Boot installation using Docker Compose.");

    public LocalSetupWizard() {
        stage.setTitle("INSOPHINIA POS — Local Setup Wizard");
        stage.initModality(Modality.APPLICATION_MODAL);
        Button browse = new Button("Browse…");
        browse.setOnAction(e -> {
            DirectoryChooser c = new DirectoryChooser();
            c.setTitle("Select project root containing docker-compose.yml");
            java.io.File f = c.showDialog(stage);
            if (f != null) projectDir.setText(f.getAbsolutePath());
        });
        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(9); g.setPadding(new Insets(14));
        int r = 0;
        g.addRow(r++, new Label("Project root"), projectDir, browse);
        g.addRow(r++, new Label("Database name"), dbName);
        g.addRow(r++, new Label("Database user"), dbUser);
        g.addRow(r++, new Label("App DB password (blank = generate)"), dbPassword);
        g.addRow(r++, new Label("MySQL root password (blank = generate)"), dbRootPassword);
        g.addRow(r++, new Label("MySQL host port"), dbPort);
        g.addRow(r++, new Label("Local API port"), apiPort);
        g.addRow(r++, new Label("Initial admin password"), adminPassword);
        g.addRow(r++, new Label("SMTP host (optional)"), smtpHost);
        g.addRow(r++, new Label("SMTP port"), smtpPort);
        g.addRow(r++, new Label("SMTP username (optional)"), smtpUser);
        g.addRow(r++, new Label("SMTP password (optional)"), smtpPassword);
        Label warning = new Label("Security: ports bind to 127.0.0.1 by default. Do not expose the API/database to a shop LAN without a reviewed TLS and firewall configuration.");
        warning.setWrapText(true); warning.setStyle("-fx-text-fill:#9a3412;");
        Button save = new Button("Save configuration");
        Button start = new Button("Save and start local POS");
        Button close = new Button("Close");
        save.setOnAction(e -> configure(false));
        start.setDefaultButton(true);
        start.setOnAction(e -> configure(true));
        close.setOnAction(e -> stage.close());
        VBox root = new VBox(10, g, warning, new javafx.scene.layout.HBox(8, save, start, close), status);
        root.setPadding(new Insets(10));
        stage.setScene(new Scene(new ScrollPane(root), 800, 720));
    }

    public void show() { stage.showAndWait(); }

    private void configure(boolean startStack) {
        try {
            Path dir = Path.of(projectDir.getText().trim()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(dir.resolve("docker-compose.yml")))
                throw new IllegalArgumentException("Selected folder must contain docker-compose.yml.");
            String name = dbName.getText().trim();
            String user = dbUser.getText().trim();
            if (!name.matches("[A-Za-z0-9_]{1,50}") || !user.matches("[A-Za-z0-9_]{1,50}"))
                throw new IllegalArgumentException("Database name and username may contain letters, numbers and underscores only.");
            int mysqlPort = Integer.parseInt(dbPort.getText().trim());
            int api = Integer.parseInt(apiPort.getText().trim());
            int smtp = Integer.parseInt(smtpPort.getText().trim());
            if (mysqlPort < 1024 || mysqlPort > 65535 || api < 1024 || api > 65535 || smtp < 1 || smtp > 65535)
                throw new IllegalArgumentException("Port number is outside the allowed range.");
            String admin = adminPassword.getText();
            if (!admin.matches("(?s).{12,30}") || !admin.matches(".*[A-Z].*") || !admin.matches(".*[a-z].*")
                    || !admin.matches(".*[0-9].*") || !admin.matches(".*[^A-Za-z0-9].*"))
                throw new IllegalArgumentException("Initial admin password must be 12–30 characters and include uppercase, lowercase, digit and special character.");
            String password = dbPassword.getText().isBlank() ? randomSecret(24) : dbPassword.getText();
            String rootPassword = dbRootPassword.getText().isBlank() ? randomSecret(32) : dbRootPassword.getText();
            String displayChannel = randomSecret(24);

            // Compose env var names match docker-compose.yml (${...}).
            String env =
                "MYSQL_DATABASE=" + name + "\n" +
                "MYSQL_USER=" + user + "\n" +
                "MYSQL_PASSWORD=" + dotenv(password) + "\n" +
                "MYSQL_ROOT_PASSWORD=" + dotenv(rootPassword) + "\n" +
                "DATABASE_URL=jdbc:mysql://127.0.0.1:" + mysqlPort + "/" + name + "?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowPublicKeyRetrieval=true\n" +
                "DATABASE_USER=" + user + "\n" +
                "DATABASE_PASSWORD=" + dotenv(password) + "\n" +
                "SERVER_ADDRESS=127.0.0.1\n" +
                "PORT=" + api + "\n" +
                "JWT_SECRET=" + randomSecret(48) + "\n" +
                "JWT_HOURS=1\n" +
                "CRON_SECRET=" + randomSecret(32) + "\n" +
                "BACKUP_ENCRYPTION_KEY=" + randomSecret(32) + "\n" +
                "TOTP_ENCRYPTION_KEY=" + randomSecret(32) + "\n" +
                "POS_DISPLAY_CHANNEL=" + displayChannel + "\n" +
                "SEED_ADMIN_USERNAME=master\n" +
                "SEED_ADMIN_PASSWORD=" + dotenv(admin) + "\n" +
                "SMTP_HOST=" + dotenv(smtpHost.getText().trim()) + "\n" +
                "SMTP_PORT=" + smtp + "\n" +
                "SMTP_USER=" + dotenv(smtpUser.getText().trim()) + "\n" +
                "SMTP_PASSWORD=" + dotenv(smtpPassword.getText()) + "\n" +
                "SMTP_AUTH=" + (!smtpUser.getText().isBlank()) + "\n" +
                "SMTP_STARTTLS=true\n" +
                "LOG_RETENTION_HOURS=24\n" +
                "PASSWORD_EXPIRY_DAYS=7\n";
            Session.displayChannel = displayChannel;
            Path channelFile = Path.of(System.getProperty("user.home"), ".config", "insophinia-pos", "display-channel");
            Files.createDirectories(channelFile.getParent());
            Files.writeString(channelFile, displayChannel, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.setPosixFilePermissions(channelFile, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) { }
            Path envPath = dir.resolve(".env");
            Files.writeString(envPath, env, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.setPosixFilePermissions(envPath, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {}
            status.setText("Saved protected configuration: " + envPath);
            if (startStack) {
                ProcessBuilder pb = new ProcessBuilder("docker", "compose", "up", "-d");
                pb.directory(dir.toFile());
                pb.redirectErrorStream(true);
                Process p = pb.start();
                StringBuilder output = new StringBuilder();
                try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                    String line; while ((line = reader.readLine()) != null) output.append(line).append('\n');
                }
                int exit = p.waitFor();
                if (exit != 0) throw new IllegalStateException("Docker Compose failed (exit " + exit + ").\n" + output);
                Session.baseUrl = "http://127.0.0.1:" + api;
                status.setText("Local POS stack started. API: " + Session.baseUrl + ". Initial username: master. Customer-display channel (copy only to the paired terminal): " + displayChannel + ". Sign in with the admin password you entered.");
                new Alert(Alert.AlertType.INFORMATION,
                        "Local POS stack started.\n\nAPI: " + Session.baseUrl + "\nUsername: master\n\nKeep your database password and generated secrets private. Display channel (share only with paired terminal): " + displayChannel + ".").showAndWait();
            } else {
                new Alert(Alert.AlertType.INFORMATION,
                        "Configuration saved to:\n" + envPath + "\n\nDisplay channel (share only with the paired terminal): " + displayChannel + "\n\nThe file is restricted to your OS user where POSIX permissions are supported.").showAndWait();
            }
        } catch (Exception ex) {
            status.setText("Setup failed: " + ex.getMessage());
            new Alert(Alert.AlertType.ERROR, "Setup failed: " + ex.getMessage()).showAndWait();
        }
    }

    private static String randomSecret(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String dotenv(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "").replace("\r", "") + "'";
    }

    /** Walk up to 5 parent directories looking for the project root. */
    private static String findProjectRoot() {
        Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && p != null; i++, p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("docker-compose.yml"))) return p.toString();
        }
        return "";
    }
}
