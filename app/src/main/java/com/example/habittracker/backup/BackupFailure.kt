package com.example.habittracker.backup

internal enum class BackupFailureStage(val userLabel: String) {
    PREPARE("local backup preparation failed"),
    CREATE_FILE("destination could not create the backup file"),
    OPEN_DESTINATION("destination could not be opened for writing"),
    WRITE_DESTINATION("destination rejected every supported write mode"),
    READ_BACK("destination could not read back the saved file"),
    VERIFY_CONTENT("saved file failed byte or data verification"),
    PROVIDER_SYNC("destination did not finish syncing the file"),
}

internal class BackupOperationException(
    val stage: BackupFailureStage,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal fun backupFailureUserLabel(error: Throwable): String {
    val failures = error.failureChain()
    if (failures.any { it is SecurityException }) return "folder permission expired"

    val message = failures.joinToString(" ") { it.message.orEmpty() }
    val validationDetail = failures
        .asSequence()
        .map { it.message.orEmpty() }
        .mapNotNull { failureMessage ->
            failureMessage.substringAfter("Local data cannot be exported:", missingDelimiterValue = "")
                .trim()
                .takeIf { it.isNotEmpty() }
        }
        .firstOrNull()
        ?.removePrefix("Backup contains ")
        ?.replaceFirstChar(Char::lowercase)
    val specificFailure = when {
        validationDetail != null -> "local data validation failed: $validationDetail"
        message.contains("Local data cannot be exported", ignoreCase = true) -> "local data validation failed"
        message.contains("too large", ignoreCase = true) -> "backup exceeds the 10 MB limit"
        message.contains("empty", ignoreCase = true) -> "destination stayed empty"
        message.contains("file size", ignoreCase = true) -> "destination reported the wrong size"
        message.contains("did not match", ignoreCase = true) -> "destination changed the backup data"
        message.contains("incomplete", ignoreCase = true) -> "destination did not finish syncing the file"
        message.contains("could not create", ignoreCase = true) -> "destination could not create the backup file"
        message.contains("rejected the write", ignoreCase = true) -> "destination rejected the backup write"
        message.contains("read back", ignoreCase = true) -> "destination could not read back the saved file"
        message.contains("Could not open", ignoreCase = true) -> "destination could not be opened"
        else -> null
    }
    if (specificFailure != null) return specificFailure
    failures.filterIsInstance<BackupOperationException>().firstOrNull()?.let { failure ->
        return failure.stage.userLabel
    }
    return "unexpected backup error (${failures.last()::class.java.simpleName})"
}

internal fun backupFailureStoredReason(error: Throwable): String {
    val label = backupFailureUserLabel(error)
    return when (label) {
        "destination stayed empty" -> "Destination remained empty"
        else -> label.replaceFirstChar(Char::uppercase)
    }
}

private fun Throwable.failureChain(): List<Throwable> {
    val failures = mutableListOf<Throwable>()
    var current: Throwable? = this
    while (current != null && current !in failures && failures.size < 12) {
        failures += current
        current = current.cause
    }
    return failures
}
