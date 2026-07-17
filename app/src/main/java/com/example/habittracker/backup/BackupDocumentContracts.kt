package com.example.habittracker.backup

import android.content.Intent

object BackupDocumentContracts {
    const val MIME_TYPE = "application/json"

    fun createBackupDocumentIntent(): Intent {
        return Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = MIME_TYPE
            putExtra(Intent.EXTRA_TITLE, manualBackupPendingFileName())
        }
    }

    fun openBackupDocumentIntent(): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = MIME_TYPE
        }
    }
}
