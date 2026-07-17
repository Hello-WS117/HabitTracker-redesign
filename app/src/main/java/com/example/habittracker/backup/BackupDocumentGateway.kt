package com.example.habittracker.backup

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns

internal data class BackupDocumentMetadata(
    val size: Long?,
    val isPartial: Boolean?,
    val supportsRename: Boolean,
)

internal interface BackupDocumentGateway {
    fun createDocument(parentUri: Uri, mimeType: String, displayName: String): Uri?
    fun renameDocument(documentUri: Uri, displayName: String): Uri?
    fun deleteDocument(documentUri: Uri): Boolean
    fun metadata(documentUri: Uri): BackupDocumentMetadata?
}

internal class AndroidBackupDocumentGateway(
    private val contentResolver: ContentResolver,
) : BackupDocumentGateway {
    override fun createDocument(parentUri: Uri, mimeType: String, displayName: String): Uri? {
        return DocumentsContract.createDocument(contentResolver, parentUri, mimeType, displayName)
    }

    override fun renameDocument(documentUri: Uri, displayName: String): Uri? {
        return DocumentsContract.renameDocument(contentResolver, documentUri, displayName)
    }

    override fun deleteDocument(documentUri: Uri): Boolean {
        return DocumentsContract.deleteDocument(contentResolver, documentUri)
    }

    override fun metadata(documentUri: Uri): BackupDocumentMetadata? {
        return runCatching {
            contentResolver.query(
                documentUri,
                arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_FLAGS),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val flagsIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                val flags = if (flagsIndex >= 0 && !cursor.isNull(flagsIndex)) cursor.getInt(flagsIndex) else null
                BackupDocumentMetadata(
                    size = size,
                    isPartial = flags?.let {
                        it and DocumentsContract.Document.FLAG_PARTIAL != 0
                    },
                    supportsRename = flags?.let {
                        it and DocumentsContract.Document.FLAG_SUPPORTS_RENAME != 0
                    } == true,
                )
            }
        }.getOrNull()
    }
}
