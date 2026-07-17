package com.example.habittracker.backup

import android.content.ContentResolver
import android.net.Uri
import java.io.InputStream
import java.io.OutputStream

internal interface BackupStreamGateway {
    fun openOutputStream(documentUri: Uri, mode: String): OutputStream?
    fun openInputStream(documentUri: Uri): InputStream?
}

internal class AndroidBackupStreamGateway(
    private val contentResolver: ContentResolver,
) : BackupStreamGateway {
    override fun openOutputStream(documentUri: Uri, mode: String): OutputStream? {
        return contentResolver.openOutputStream(documentUri, mode)
    }

    override fun openInputStream(documentUri: Uri): InputStream? {
        return contentResolver.openInputStream(documentUri)
    }
}
