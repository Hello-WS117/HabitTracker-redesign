package com.example.habittracker.backup

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupDocumentContractsTest {
    @Test
    fun createBackupIntentUsesSafCreateDocumentWithPendingFilename() {
        val intent = BackupDocumentContracts.createBackupDocumentIntent()

        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals(BackupDocumentContracts.MIME_TYPE, intent.type)
        assertTrue(
            intent.getStringExtra(Intent.EXTRA_TITLE)
                .orEmpty()
                .matches(Regex("personal_scheduler_backup_v1_\\d{8}-\\d{6}\\.json\\.pending")),
        )
        assertTrue(intent.categories.orEmpty().contains(Intent.CATEGORY_OPENABLE))
    }

    @Test
    fun openBackupIntentUsesSafOpenDocument() {
        val intent = BackupDocumentContracts.openBackupDocumentIntent()

        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals(BackupDocumentContracts.MIME_TYPE, intent.type)
        assertTrue(intent.categories.orEmpty().contains(Intent.CATEGORY_OPENABLE))
    }
}
