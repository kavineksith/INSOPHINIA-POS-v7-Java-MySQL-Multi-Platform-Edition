package com.insophinia.pos.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.insophinia.pos.dto.MiscDtos.BackupResponse;
import com.insophinia.pos.entity.Backup;
import com.insophinia.pos.entity.BackupType;
import com.insophinia.pos.exception.BadRequestException;
import com.insophinia.pos.exception.ResourceNotFoundException;
import com.insophinia.pos.repository.BackupRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Encrypted, compressed, column-mapped snapshots with transactional restore. */
@Service
public class BackupService {
    private static final byte[] MAGIC = new byte[]{'I','P','B','1'};
    private static final int GCM_TAG_BITS = 128;
    private static final int NONCE_BYTES = 12;
    // Only application data tables are allowed. Never accept table names from a backup file.
    private static final List<String> TABLES = List.of("categories", "items", "promotions", "customers",
            "users", "settings", "device_profiles", "bills", "bill_items", "stock_movements");
    private static final List<String> DELETE_ORDER = List.of("bill_items", "stock_movements", "promotions",
            "bills", "items", "categories", "customers", "device_profiles", "settings", "users");

    private final BackupRepository repository;
    private final Path backupDir;
    private final ObjectMapper mapper;
    private final SyncHub syncHub;
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final String encryptionKey;
    private final SecureRandom random = new SecureRandom();

    @PersistenceContext private EntityManager em;

    public BackupService(BackupRepository repository, @Value("${app.backup.dir:./backups}") String dir,
                         @Value("${app.backup.encryption-key:}") String encryptionKey,
                         SyncHub syncHub, JdbcTemplate jdbc, DataSource dataSource) {
        this.repository = repository;
        this.backupDir = Path.of(dir);
        this.encryptionKey = encryptionKey;
        this.syncHub = syncHub;
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public List<Backup> list() { return repository.findAll(); }

    public List<Map<String, Object>> listFiles() throws IOException {
        Path dir = backupDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) return List.of();
        java.util.Set<String> registered = repository.findAll().stream().map(Backup::getFilename).collect(java.util.stream.Collectors.toSet());
        try (var paths = Files.list(dir)) {
            return paths.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(".posbak"))
                    .map(p -> { try { return Map.<String,Object>of("filename", p.getFileName().toString(), "size", Files.size(p),
                            "modifiedAt", Files.getLastModifiedTime(p).toInstant().toString(),
                            "registered", registered.contains(p.getFileName().toString())); }
                        catch (IOException e) { return Map.<String,Object>of("filename", p.getFileName().toString(), "error", "metadata unavailable"); } })
                    .toList();
        }
    }

    @Transactional
    public Backup createBackup(BackupType type, UUID createdBy) throws IOException {
        requireEncryptionKey();
        Files.createDirectories(backupDir);
        String filename = "backup-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                .withZone(ZoneOffset.UTC).format(Instant.now()) + ".posbak";
        Path target = backupDir.resolve(filename);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("format", "insophinia-pos-encrypted-backup");
        snapshot.put("version", 2);
        snapshot.put("createdAt", Instant.now().toString());
        Map<String, Object> tables = new LinkedHashMap<>();
        for (String table : TABLES) tables.put(table, jdbc.queryForList("SELECT * FROM " + table));
        snapshot.put("tables", tables);
        byte[] json = mapper.writeValueAsBytes(snapshot);
        byte[] compressed = gzip(json);
        byte[] encrypted;
        try { encrypted = encrypt(compressed); }
        catch (Exception e) { throw new IOException("Could not encrypt backup", e); }
        Files.write(target, encrypted);
        Backup backup = new Backup();
        backup.setFilename(filename);
        backup.setFilePath(target.toAbsolutePath().toString());
        backup.setFileSize(Files.size(target));
        backup.setBackupType(type);
        backup.setCreatedBy(createdBy);
        Backup saved = repository.save(backup);
        syncHub.broadcast("BACKUP_CREATED", "backup", Map.of("backupId", saved.getId().toString()));
        return saved;
    }

    public Path resolveDownload(UUID id) {
        Backup backup = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Backup not found"));
        Path path = Path.of(backup.getFilePath()).normalize();
        if (!path.startsWith(backupDir.toAbsolutePath().normalize()) || !Files.isRegularFile(path))
            throw new ResourceNotFoundException("Backup file is missing or outside the configured backup directory");
        return path;
    }

    @Transactional
    public void delete(UUID id) {
        Backup backup = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Backup not found"));
        try { Files.deleteIfExists(Path.of(backup.getFilePath())); }
        catch (IOException e) { throw new BadRequestException("Could not delete backup file"); }
        repository.delete(backup);
        syncHub.broadcast("BACKUP_DELETED", "backup", Map.of("backupId", id.toString()));
    }

    @Transactional(readOnly = true)
    public Map<String, Integer> verifyRestore(UUID id) throws IOException { return preview(readSnapshot(resolveDownload(id))); }

    @Transactional(readOnly = true)
    public Map<String, Integer> verifyRestoreFile(String filename) throws IOException { return preview(readSnapshot(resolveOrphanFile(filename))); }

    private Map<String, Integer> preview(Map<String, List<Map<String, Object>>> tables) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String table : TABLES) counts.put(table, tables.getOrDefault(table, List.of()).size());
        return counts;
    }

    /** Confirmation is deliberately explicit; the caller must re-authenticate before invoking this method. */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Integer> restore(UUID id, String confirmation, UUID actorId) throws IOException {
        return restoreFromPath(resolveDownload(id), confirmation, actorId, id.toString());
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Integer> restoreFile(String filename, String confirmation, UUID actorId) throws IOException {
        return restoreFromPath(resolveOrphanFile(filename), confirmation, actorId, filename);
    }

    private Map<String, Integer> restoreFromPath(Path source, String confirmation, UUID actorId, String sourceId) throws IOException {
        if (!"RESTORE".equals(confirmation)) throw new BadRequestException("Type RESTORE to confirm data replacement");
        requireEncryptionKey();
        // Preserve the current state before replacing any rows.
        createBackup(BackupType.automatic, actorId);
        Map<String, List<Map<String, Object>>> tables = readSnapshot(source);
        Map<String, Integer> restored = new LinkedHashMap<>();
        for (String table : DELETE_ORDER) {
            if (tableExists(table)) jdbc.update("DELETE FROM " + table);
        }
        for (String table : TABLES) {
            List<Map<String, Object>> rows = tables.getOrDefault(table, List.of());
            if (!tableExists(table)) {
                if (!rows.isEmpty()) throw new BadRequestException("Database table is missing: " + table);
                restored.put(table, 0); continue;
            }
            for (Map<String, Object> row : rows) insertRow(table, row);
            restored.put(table, rows.size());
        }
        syncHub.broadcast("BACKUP_RESTORED", "backup", Map.of("source", sourceId));
        return restored;
    }

    private Path resolveOrphanFile(String filename) {
        if (filename == null || filename.isBlank() || !filename.matches("[A-Za-z0-9._-]+\\.posbak"))
            throw new BadRequestException("A valid .posbak filename is required");
        Path dir = backupDir.toAbsolutePath().normalize();
        Path path = dir.resolve(filename).normalize();
        if (!path.startsWith(dir) || !Files.isRegularFile(path)) throw new ResourceNotFoundException("Backup file not found");
        return path;
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<Map<String, Object>>> readSnapshot(Path path) throws IOException {
        try {
            byte[] json = gunzip(decrypt(Files.readAllBytes(path)));
            Map<String, Object> snapshot = mapper.readValue(json, new TypeReference<>() {});
            if (!"insophinia-pos-encrypted-backup".equals(snapshot.get("format"))
                    || !(snapshot.get("version") instanceof Number n) || n.intValue() != 2)
                throw new BadRequestException("Unsupported or invalid encrypted INSOPHINIA backup");
            Object rawTables = snapshot.get("tables");
            if (!(rawTables instanceof Map<?, ?>)) throw new BadRequestException("Backup has no table data");
            Map<String, Object> raw = (Map<String, Object>) rawTables;
            Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
            for (String table : TABLES) {
                Object rowsObj = raw.getOrDefault(table, List.of());
                if (!(rowsObj instanceof List<?> list)) throw new BadRequestException("Invalid rows for " + table);
                List<Map<String, Object>> rows = new ArrayList<>();
                for (Object row : list) {
                    if (!(row instanceof Map<?, ?> m)) throw new BadRequestException("Invalid row in " + table);
                    Map<String, Object> converted = new LinkedHashMap<>();
                    m.forEach((k, v) -> {
                        if (!(k instanceof String key)) throw new BadRequestException("Invalid column name");
                        converted.put(key, v);
                    });
                    rows.add(converted);
                }
                result.put(table, rows);
            }
            return result;
        } catch (BadRequestException e) { throw e; }
        catch (Exception e) { throw new BadRequestException("Backup authentication or integrity verification failed"); }
    }

    private void insertRow(String table, Map<String, Object> row) throws IOException {
        if (row.isEmpty()) return;
        Map<String, ColumnInfo> columnTypes = columnTypes(table);
        List<String> columns = row.keySet().stream().filter(columnTypes::containsKey).toList();
        if (columns.isEmpty()) return;
        List<String> placeholders = new ArrayList<>();
        for (String column : columns) {
            // Enum-backed entity columns are VARCHAR after Flyway V8; bind strings normally.
            placeholders.add("?");
        }
        String quotedColumns = String.join(",", columns.stream().map(c -> "\"" + c + "\"").toList());
        String sql = "INSERT INTO \"" + table + "\" (" + quotedColumns + ") VALUES ("
                + String.join(",", placeholders) + ")";
        Object[] values = columns.stream().map(c -> convert(row.get(c), columnTypes.get(c).sqlType())).toArray();
        jdbc.update(sql, values);
    }

    private record ColumnInfo(int sqlType) {}
    private Map<String, ColumnInfo> columnTypes(String table) throws IOException {
        try {
            Map<String, ColumnInfo> result = new LinkedHashMap<>();
            try (java.sql.Connection connection = dataSource.getConnection()) {
                DatabaseMetaData meta = connection.getMetaData();
                try (ResultSet rs = meta.getColumns(null, null, table, null)) {
                    while (rs.next()) result.put(rs.getString("COLUMN_NAME"), new ColumnInfo(rs.getInt("DATA_TYPE")));
                }
                if (result.isEmpty()) try (ResultSet rs = meta.getColumns(null, null, table.toUpperCase(), null)) {
                    while (rs.next()) result.put(rs.getString("COLUMN_NAME"), new ColumnInfo(rs.getInt("DATA_TYPE")));
                }
            }
            return result;
        } catch (Exception e) { throw new IOException("Unable to inspect table schema " + table, e); }
    }

    private Object convert(Object value, int sqlType) {
        if (value == null) return null;
        String s = value.toString();
        try {
            return switch (sqlType) {
                case Types.BOOLEAN, Types.BIT -> value instanceof Boolean ? value : Boolean.valueOf(s);
                case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> value instanceof Number ? ((Number)value).intValue() : Integer.valueOf(s);
                case Types.BIGINT -> value instanceof Number ? ((Number)value).longValue() : Long.valueOf(s);
                case Types.NUMERIC, Types.DECIMAL -> value instanceof Number ? new java.math.BigDecimal(value.toString()) : new java.math.BigDecimal(s);
                case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> java.sql.Timestamp.from(parseInstant(s));
                case Types.DATE -> java.sql.Date.valueOf(s.substring(0, 10));
                case Types.OTHER -> { try { yield UUID.fromString(s); } catch (IllegalArgumentException ignored) { yield value; } }
                default -> value;
            };
        } catch (Exception ignored) { return value; }
    }
    private Instant parseInstant(String s) {
        try { return Instant.parse(s); }
        catch (Exception ignored) { return OffsetDateTime.parse(s).toInstant(); }
    }
    private boolean tableExists(String table) {
        try { jdbc.queryForObject("SELECT 1 FROM " + table + " WHERE 1=0", Integer.class); return true; }
        catch (Exception e) { return !(e instanceof org.springframework.jdbc.BadSqlGrammarException); }
    }
    private void requireEncryptionKey() {
        if (encryptionKey == null || encryptionKey.length() < 32)
            throw new IllegalStateException("Set BACKUP_ENCRYPTION_KEY to a random secret of at least 32 characters before using backups");
    }
    private byte[] encrypt(byte[] plaintext) throws Exception {
        byte[] nonce = new byte[NONCE_BYTES]; random.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, nonce));
        cipher.updateAAD(MAGIC);
        byte[] encrypted = cipher.doFinal(plaintext);
        ByteBuffer out = ByteBuffer.allocate(MAGIC.length + nonce.length + encrypted.length);
        out.put(MAGIC).put(nonce).put(encrypted); return out.array();
    }
    private byte[] decrypt(byte[] file) throws Exception {
        if (file.length < MAGIC.length + NONCE_BYTES + 16 || !Arrays.equals(MAGIC, Arrays.copyOf(file, MAGIC.length)))
            throw new IllegalArgumentException("Bad backup header");
        byte[] nonce = Arrays.copyOfRange(file, MAGIC.length, MAGIC.length + NONCE_BYTES);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, nonce));
        cipher.updateAAD(MAGIC);
        return cipher.doFinal(Arrays.copyOfRange(file, MAGIC.length + NONCE_BYTES, file.length));
    }
    private SecretKeySpec key() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(encryptionKey.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest, "AES");
    }
    private byte[] gzip(byte[] in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) { gzip.write(in); }
        return out.toByteArray();
    }
    private byte[] gunzip(byte[] in) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(in)); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            gzip.transferTo(out); return out.toByteArray();
        }
    }
    public static BackupResponse toResponse(Backup b) {
        return new BackupResponse(b.getId(), b.getFilename(), b.getFileSize(), b.getBackupType().name(), b.getCreatedAt());
    }
}
