package com.insophinia.pos.controller;

import com.insophinia.pos.dto.ApiResponse;
import com.insophinia.pos.dto.MiscDtos.BackupResponse;
import com.insophinia.pos.dto.MiscDtos.SystemLogResponse;
import com.insophinia.pos.entity.BackupType;
import com.insophinia.pos.repository.SystemLogRepository;
import com.insophinia.pos.repository.UserRepository;
import com.insophinia.pos.entity.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.RequestBody;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;
import com.insophinia.pos.security.SecurityUtils;
import com.insophinia.pos.service.BackupService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Backups (+ scheduled cron endpoint) and system log viewer. */
public final class BackupAndLogControllers {
    private BackupAndLogControllers() {}

    @RestController
    @RequestMapping("/api/backups")
    public static class BackupController {
        private final BackupService service;
        private final UserRepository users;
        private final PasswordEncoder passwordEncoder;
        public BackupController(BackupService service, UserRepository users, PasswordEncoder passwordEncoder) {
            this.service = service; this.users = users; this.passwordEncoder = passwordEncoder;
        }
        public record RestoreRequest(UUID backupId, String filename, String currentPassword, String confirmation) {}

        @GetMapping
        @PreAuthorize("hasAuthority('manage:settings')")
        public ApiResponse<List<BackupResponse>> list() {
            return ApiResponse.ok(service.list().stream().map(BackupService::toResponse).toList());
        }

        @PostMapping
        @PreAuthorize("hasAuthority('manage:settings')")
        public ApiResponse<BackupResponse> create() throws IOException {
            return ApiResponse.ok(BackupService.toResponse(
                    service.createBackup(BackupType.manual, SecurityUtils.requireUser().userId())),
                    "Backup created");
        }

        @GetMapping("/files")
        @PreAuthorize("hasAuthority('manage:settings')")
        public ApiResponse<List<Map<String, Object>>> files() throws IOException {
            return ApiResponse.ok(service.listFiles());
        }

        @PostMapping("/restore")
        @PreAuthorize("hasAuthority('manage:settings')")
        public ApiResponse<Map<String, Integer>> restore(@RequestBody RestoreRequest req) throws IOException {
            if (req == null || (req.backupId() == null && (req.filename() == null || req.filename().isBlank())) || req.currentPassword() == null)
                throw new com.insophinia.pos.exception.BadRequestException("backupId or filename, and currentPassword are required");
            var auth = SecurityUtils.requireUser();
            User user = users.findByIdAndDeletedAtIsNull(auth.userId())
                    .orElseThrow(() -> new com.insophinia.pos.exception.UnauthorizedException("User not found"));
            if (!passwordEncoder.matches(req.currentPassword(), user.getPassword()))
                throw new com.insophinia.pos.exception.UnauthorizedException("Re-authentication failed");
            var result = req.backupId() != null
                    ? service.restore(req.backupId(), req.confirmation(), auth.userId())
                    : service.restoreFile(req.filename(), req.confirmation(), auth.userId());
            return ApiResponse.ok(result, "Restore completed");
        }

        @GetMapping("/{id}/download")
        @PreAuthorize("hasAuthority('manage:settings')")
        public ResponseEntity<Resource> download(@PathVariable UUID id) {
            var path = service.resolveDownload(id);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + path.getFileName() + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(new FileSystemResource(path));
        }

        @DeleteMapping("/{id}")
        @PreAuthorize("hasAuthority('manage:settings')")
        public ApiResponse<Map<String, Boolean>> delete(@PathVariable UUID id) {
            service.delete(id);
            return ApiResponse.ok(Map.of("deleted", true));
        }

        @PostMapping("/restore/{id}")
        @PreAuthorize("hasAuthority('manage:settings')")
        public ApiResponse<Map<String, Integer>> verifyRestore(@PathVariable UUID id) throws IOException {
            return ApiResponse.ok(service.verifyRestore(id), "Backup verified; row counts per table returned");
        }

        @PostMapping("/preview-file")
        @PreAuthorize("hasAuthority('manage:settings')")
        public ApiResponse<Map<String, Integer>> previewFile(@RequestBody Map<String, String> body) throws IOException {
            return ApiResponse.ok(service.verifyRestoreFile(body == null ? null : body.get("filename")), "Backup file verified; row counts per table returned");
        }
    }

    @RestController
    @RequestMapping("/api/cron")
    public static class CronController {
        private final BackupService service;
        private final String cronSecret;

        public CronController(BackupService service,
                              @Value("${app.cron-secret}") String cronSecret) {
            this.service = service;
            this.cronSecret = cronSecret;
        }

        @PostMapping("/backups")
        public ApiResponse<BackupResponse> scheduledBackup(
                @RequestHeader(value = "X-Cron-Secret", required = false) String secret) throws IOException {
            if (secret == null || !java.security.MessageDigest.isEqual(cronSecret.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8))) {
                throw new com.insophinia.pos.exception.UnauthorizedException("Invalid cron secret");
            }
            return ApiResponse.ok(BackupService.toResponse(
                    service.createBackup(BackupType.automatic, null)), "Scheduled backup created");
        }
    }

    @RestController
    @RequestMapping("/api/logs")
    public static class LogController {
        private final SystemLogRepository logs;
        public LogController(SystemLogRepository logs) { this.logs = logs; }

        @GetMapping
        @PreAuthorize("hasAuthority('view:logs')")
        public com.insophinia.pos.dto.PagedResponse<SystemLogResponse> list(
                @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "50") int limit) {
            int safePage = Math.max(1, page); int safeLimit = Math.max(1, Math.min(limit, 100));
            return com.insophinia.pos.dto.PagedResponse.of(
                    logs.findAll(PageRequest.of(safePage - 1, safeLimit, Sort.by("createdAt").descending()))
                            .map(l -> new SystemLogResponse(l.getId(), l.getLevel(), l.getCategory(), l.getAction(),
                                    l.getMessage(), l.getMetadata(), l.getUserId(), l.getIpAddress(), l.getCreatedAt())));
        }

        @GetMapping("/aggregate")
        @PreAuthorize("hasAuthority('view:logs')")
        public ApiResponse<Map<String, Object>> aggregate() {
            var rows = logs.findAll(PageRequest.of(0, 10000, Sort.by("createdAt").descending())).getContent();
            Map<String, Long> byLevel = rows.stream().collect(Collectors.groupingBy(com.insophinia.pos.entity.SystemLog::getLevel, LinkedHashMap::new, Collectors.counting()));
            Map<String, Long> byCategory = rows.stream().collect(Collectors.groupingBy(com.insophinia.pos.entity.SystemLog::getCategory, LinkedHashMap::new, Collectors.counting()));
            return ApiResponse.ok(Map.of("sampledRows", rows.size(), "byLevel", byLevel, "byCategory", byCategory));
        }

        @GetMapping(value = "/export", produces = "text/csv")
        @PreAuthorize("hasAuthority('view:logs')")
        public ResponseEntity<String> export() {
            var rows = logs.findAll(PageRequest.of(0, 10000, Sort.by("createdAt").descending())).getContent();
            StringBuilder csv = new StringBuilder("id,createdAt,level,category,action,message,userId,ipAddress\n");
            for (var l : rows) csv.append(csv(l.getId())).append(',').append(csv(l.getCreatedAt())).append(',')
                    .append(csv(l.getLevel())).append(',').append(csv(l.getCategory())).append(',').append(csv(l.getAction())).append(',')
                    .append(csv(l.getMessage())).append(',').append(csv(l.getUserId())).append(',').append(csv(l.getIpAddress())).append('\n');
            return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=insophinia-logs.csv")
                    .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8)).body(csv.toString());
        }

        @PostMapping("/cleanup")
        @PreAuthorize("hasAuthority('manage:settings')")
        public ApiResponse<Map<String, Long>> cleanup(@RequestParam(defaultValue = "24") int retentionHours) {
            int hours = Math.max(24, Math.min(retentionHours, 24 * 365));
            long deleted = logs.deleteByCreatedAtBefore(Instant.now().minusSeconds(hours * 3600L));
            return ApiResponse.ok(Map.of("deleted", deleted, "retentionHours", (long) hours));
        }
        private static String csv(Object value) {
            String s = value == null ? "" : value.toString();
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
    }
}
