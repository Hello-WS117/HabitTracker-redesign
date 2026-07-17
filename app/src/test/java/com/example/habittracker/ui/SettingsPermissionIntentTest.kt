package com.example.habittracker.ui

import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
class SettingsPermissionIntentTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        ShadowAlarmManager.reset()
    }

    @Test
    @Config(sdk = [35])
    fun exactAlarmAccessReflectsAlarmManagerPermissionOnAndroidTwelveAndNewer() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertFalse(hasExactAlarmAccess(context))

        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertTrue(hasExactAlarmAccess(context))
    }

    @Test
    @Config(sdk = [30])
    fun exactAlarmAccessIsGrantedBeforeAndroidTwelve() {
        assertTrue(hasExactAlarmAccess(context))
    }

    @Test
    @Config(sdk = [35])
    fun exactAlarmSettingsIntentOpensScheduleExactAlarmScreenWithPackageUri() {
        val intent = exactAlarmSettingsIntent(context)

        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, intent.action)
        assertEquals("package:${context.packageName}", intent.data.toString())
    }

    @Test
    @Config(sdk = [30])
    fun exactAlarmSettingsIntentFallsBackToAppDetailsBeforeAndroidTwelve() {
        val intent = exactAlarmSettingsIntent(context)

        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:${context.packageName}", intent.data.toString())
    }

    @Test
    @Config(sdk = [35])
    fun appDetailsSettingsIntentTargetsCurrentPackage() {
        val intent = appDetailsSettingsIntent(context)

        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:${context.packageName}", intent.data.toString())
    }

    @Test
    @Config(sdk = [35])
    fun launchExactAlarmSettingsUsesScheduleExactAlarmIntent() {
        val launchedActions = mutableListOf<String?>()

        launchExactAlarmSettings(context) { intent ->
            launchedActions += intent.action
        }

        assertEquals(listOf(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM), launchedActions)
    }

    @Test
    @Config(sdk = [35])
    fun launchExactAlarmSettingsFallsBackToAppDetailsWhenExactAlarmScreenIsMissing() {
        val launchedActions = mutableListOf<String?>()

        launchExactAlarmSettings(context) { intent ->
            launchedActions += intent.action
            if (intent.action == Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM) {
                throw ActivityNotFoundException()
            }
        }

        assertEquals(
            listOf(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            ),
            launchedActions,
        )
    }

    @Test
    @Config(sdk = [33])
    fun notificationAccessReflectsRuntimePermissionOnAndroidThirteenAndNewer() {
        val shadowContext = shadowOf(context as ContextWrapper)
        shadowContext.denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(hasNotificationAccess(context))

        shadowContext.grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(hasNotificationAccess(context))
    }

    @Test
    @Config(sdk = [30])
    fun notificationAccessIsGrantedBeforeAndroidThirteen() {
        assertTrue(hasNotificationAccess(context))
    }
}
