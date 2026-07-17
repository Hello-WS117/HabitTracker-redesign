package com.example.habittracker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReleasePackagingContractTest {
    @Test
    fun releaseSigningSupportsPrivateKeystoreConfigurationAndFailsReleasePackagingWhenMissing() {
        val buildFile = findProjectFile("app/build.gradle.kts")
        val script = buildFile.readText()

        assertTrue(script.contains("create(\"privateRelease\")"))
        assertTrue(script.contains("habit.release.storeFile"))
        assertTrue(script.contains("HABIT_RELEASE_STORE_FILE"))
        assertTrue(script.contains("signingConfigs.findByName(\"privateRelease\")"))
        assertTrue(script.contains("packageRelease"))
        assertTrue(script.contains("bundleRelease"))
        assertTrue(script.contains("Private release signing is required for release packaging"))
    }

    @Test
    fun releaseSigningDocumentationListsRequiredLocalKeys() {
        val signingDoc = findProjectFile("docs/release-signing.md").readText()

        assertTrue(signingDoc.contains("habit.release.storeFile"))
        assertTrue(signingDoc.contains("habit.release.storePassword"))
        assertTrue(signingDoc.contains("habit.release.keyAlias"))
        assertTrue(signingDoc.contains("habit.release.keyPassword"))
        assertTrue(signingDoc.contains("HABIT_RELEASE_STORE_FILE"))
        assertFalse(signingDoc.contains("falls back to the debug signing"))
    }

    private fun findProjectFile(relativePath: String): File {
        val workingDir = requireNotNull(System.getProperty("user.dir"))
        var current = File(workingDir).absoluteFile
        while (current.parentFile != null) {
            val candidate = File(current, relativePath)
            if (candidate.exists()) return candidate
            current = requireNotNull(current.parentFile)
        }
        throw AssertionError("Could not locate $relativePath from $workingDir")
    }
}
