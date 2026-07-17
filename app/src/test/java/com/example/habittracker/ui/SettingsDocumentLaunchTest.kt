package com.example.habittracker.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import com.example.habittracker.backup.BackupFailureStage
import com.example.habittracker.backup.BackupOperationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsDocumentLaunchTest {
    @Test
    fun launchBackupDocumentUsesFinalJsonFilenameForProviderCompatibility() {
        var launchedFilename: String? = null
        var unavailable = false

        launchBackupDocument(
            launch = { launchedFilename = it },
            onNoDocumentPicker = { unavailable = true },
        )

        assertTrue(
            launchedFilename
                .orEmpty()
                .matches(Regex("personal_scheduler_backup_v1_\\d{8}-\\d{6}\\.json")),
        )
        assertFalse(unavailable)
    }

    @Test
    fun launchBackupDocumentReportsUnavailableWhenPickerIsMissing() {
        var unavailable = false

        launchBackupDocument(
            launch = { throw ActivityNotFoundException() },
            onNoDocumentPicker = { unavailable = true },
        )

        assertTrue(unavailable)
    }

    @Test
    fun backupStatusUsesVerifiedSizeAndNonTechnicalProviderFailure() {
        assertEquals("429 KB", backupByteCountLabel(439_266L))
        assertEquals(
            "Backup failed: destination stayed empty",
            manualBackupFailureStatus(IllegalStateException("Backup file was empty after writing")),
        )
    }

    @Test
    fun backupStatusReportsTheExactProviderStageInsteadOfAGenericFailure() {
        val error = BackupOperationException(
            stage = BackupFailureStage.WRITE_DESTINATION,
            message = "All provider modes failed",
            cause = UnsupportedOperationException("Provider-specific failure"),
        )

        assertEquals(
            "Backup failed: destination rejected every supported write mode",
            manualBackupFailureStatus(error),
        )
    }

    @Test
    fun launchRestoreDocumentUsesJsonAndFallbackMimeTypes() {
        var launchedMimeTypes: Array<String>? = null
        var unavailable = false

        launchRestoreDocument(
            launch = { launchedMimeTypes = it },
            onNoDocumentPicker = { unavailable = true },
        )

        assertArrayEquals(arrayOf("application/json", "text/*", "*/*"), launchedMimeTypes)
        assertFalse(unavailable)
    }

    @Test
    fun launchRestoreDocumentReportsUnavailableWhenPickerIsMissing() {
        var unavailable = false

        launchRestoreDocument(
            launch = { throw ActivityNotFoundException() },
            onNoDocumentPicker = { unavailable = true },
        )

        assertTrue(unavailable)
    }

    @Test
    fun launchAutoBackupFolderUsesOpenDocumentTreeWithPersistableWriteAccess() {
        var launchedIntent: Intent? = null
        var unavailable = false

        launchAutoBackupFolder(
            launch = { launchedIntent = it },
            onNoDocumentPicker = { unavailable = true },
        )

        val intent = requireNotNull(launchedIntent)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, intent.action)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        assertTrue(intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0)
        assertFalse(unavailable)
    }

    @Test
    fun launchAutoBackupFolderReportsUnavailableWhenPickerIsMissing() {
        var unavailable = false

        launchAutoBackupFolder(
            launch = { throw ActivityNotFoundException() },
            onNoDocumentPicker = { unavailable = true },
        )

        assertTrue(unavailable)
    }

    @Test
    fun restoreReplacementWarningEncouragesBackupBeforeConfirming() {
        assertTrue(RESTORE_REPLACEMENT_WARNING.contains("replaces current tasks"))
        assertTrue(RESTORE_REPLACEMENT_WARNING.contains("history"))
        assertTrue(RESTORE_REPLACEMENT_WARNING.contains("settings"))
        assertTrue(RESTORE_REPLACEMENT_WARNING.contains("Back up first"))
    }

    @Test
    fun restoreFailureStatusUsesNonTechnicalCategories() {
        assertEquals(
            "Restore failed: unsupported backup version",
            restoreFailureStatus(IllegalArgumentException("Unsupported backup schema 99")),
        )
        assertEquals(
            "Restore failed: invalid backup file",
            restoreFailureStatus(IllegalArgumentException("Backup is missing schema version")),
        )
        assertEquals(
            "Restore failed: corrupted backup",
            restoreFailureStatus(IllegalArgumentException("Backup contains an occurrence with an unknown sequence item")),
        )
        assertEquals(
            "Restore failed: backup file is too large",
            restoreFailureStatus(IllegalStateException("Backup file is too large")),
        )
        assertEquals(
            "Restore failed: could not open selected file",
            restoreFailureStatus(IllegalStateException("Could not open backup source")),
        )
        assertEquals(
            "Restore failed: restore failed",
            restoreFailureStatus(RuntimeException("disk full")),
        )
    }
}
