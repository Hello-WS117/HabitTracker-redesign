package com.example.habittracker

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManifestPrivacyTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun manifestKeepsLocalFirstPermissionSurface() {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val permissions = packageInfo.requestedPermissions.orEmpty().toSet()

        assertFalse(Manifest.permission.INTERNET in permissions)
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in permissions)
        assertTrue(Manifest.permission.RECEIVE_BOOT_COMPLETED in permissions)
        assertTrue(Manifest.permission.SCHEDULE_EXACT_ALARM in permissions)
        assertTrue(Manifest.permission.FOREGROUND_SERVICE in permissions)
        assertTrue(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK in permissions)
    }

    @Test
    fun manifestDisablesAndroidBackupAndKeepsReceiversScoped() {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_RECEIVERS,
        )
        val receiversByName = packageInfo.receivers.orEmpty().associateBy { it.name }
        val applicationInfo = requireNotNull(packageInfo.applicationInfo)

        assertEquals(0, applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertEquals(false, receiversByName.getValue("com.example.habittracker.reminders.HabitReminderReceiver").exported)
        assertEquals(false, receiversByName.getValue("com.example.habittracker.reminders.BootCompletedReceiver").exported)
    }

    @Test
    fun exerciseTimerServiceIsPrivateAndUsesMediaPlaybackForegroundMode() {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SERVICES,
        )
        val service = packageInfo.services.orEmpty()
            .single { it.name == "com.example.habittracker.timers.ExerciseTimerService" }

        assertEquals(false, service.exported)
        assertTrue(service.enabled)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    @Test
    fun dataExtractionRulesExcludeLocalStateFromBackupsAndTransfers() {
        val expectedExclusions = setOf(
            "database" to ".",
            "sharedpref" to ".",
            "file" to ".",
        )
        val exclusionsBySection = mutableMapOf<String, MutableSet<Pair<String, String>>>()
        val parser = context.resources.getXml(R.xml.data_extraction_rules)
        var currentSection: String? = null

        try {
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "cloud-backup",
                        "device-transfer",
                        -> currentSection = parser.name

                        "exclude" -> {
                            val section = requireNotNull(currentSection)
                            exclusionsBySection.getOrPut(section) { mutableSetOf() } +=
                                parser.getAttributeValue(null, "domain") to parser.getAttributeValue(null, "path")
                        }
                    }

                    XmlPullParser.END_TAG -> when (parser.name) {
                        "cloud-backup",
                        "device-transfer",
                        -> currentSection = null
                    }
                }
                eventType = parser.next()
            }
        } finally {
            parser.close()
        }

        assertEquals(expectedExclusions, exclusionsBySection.getValue("cloud-backup"))
        assertEquals(expectedExclusions, exclusionsBySection.getValue("device-transfer"))
    }

    @Test
    fun legacyBackupRulesExcludeLocalStateBeforeAndroid12() {
        val expectedExclusions = setOf(
            "database" to ".",
            "sharedpref" to ".",
            "file" to ".",
        )
        val parser = context.resources.getXml(R.xml.backup_rules)
        val exclusions = mutableSetOf<Pair<String, String>>()

        try {
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "exclude") {
                    exclusions += parser.getAttributeValue(null, "domain") to parser.getAttributeValue(null, "path")
                }
                eventType = parser.next()
            }
        } finally {
            parser.close()
        }

        assertEquals(expectedExclusions, exclusions)
    }
}
