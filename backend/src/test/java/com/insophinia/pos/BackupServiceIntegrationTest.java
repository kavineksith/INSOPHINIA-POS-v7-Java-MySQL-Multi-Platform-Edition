package com.insophinia.pos;

import com.insophinia.pos.entity.BackupType;
import com.insophinia.pos.exception.BadRequestException;
import com.insophinia.pos.service.BackupService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class BackupServiceIntegrationTest {
    @Autowired private BackupService backups;

    @Test void createsEncryptedBackupAndPreviewsRows() throws Exception {
        var backup = backups.createBackup(BackupType.manual, null);
        try {
            var path = backups.resolveDownload(backup.getId());
            byte[] bytes = Files.readAllBytes(path);
            assertTrue(bytes.length > 32);
            assertEquals('I', bytes[0]); assertEquals('P', bytes[1]);
            assertFalse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8).contains("insophinia-pos-encrypted-backup"));
            var preview = backups.verifyRestore(backup.getId());
            assertTrue(preview.containsKey("users"));
            assertTrue(preview.containsKey("bill_items"));
        } finally { backups.delete(backup.getId()); }
    }

    @Test void rejectsTamperedBackup() throws Exception {
        var backup = backups.createBackup(BackupType.manual, null);
        try {
            var path = backups.resolveDownload(backup.getId());
            byte[] bytes = Files.readAllBytes(path); bytes[bytes.length - 1] ^= 0x01; Files.write(path, bytes);
            assertThrows(BadRequestException.class, () -> backups.verifyRestore(backup.getId()));
        } finally { backups.delete(backup.getId()); }
    }
}
