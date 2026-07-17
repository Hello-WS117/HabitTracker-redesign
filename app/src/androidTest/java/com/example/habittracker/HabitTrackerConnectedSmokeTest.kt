package com.example.habittracker

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HabitTrackerConnectedSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun createTaskAndNavigatePrimaryScreens() {
        val taskName = "Connected smoke ${System.currentTimeMillis()}"

        compose.onNodeWithTag("nav-tasks").performClick()
        compose.onNodeWithText("Task Editor").assertIsDisplayed()
        compose.onNodeWithTag("task-name-field").performTextInput(taskName)
        compose.onNodeWithTag("task-save-button").performClick()
        compose.waitUntilExists(taskName)

        compose.onNodeWithTag("nav-today").performClick()
        compose.onNodeWithText("Daily Checklist").assertIsDisplayed()
        compose.onNodeWithText("Daily checklist").assertIsDisplayed()

        compose.onNodeWithTag("nav-calendar").performClick()
        compose.onNodeWithText("Monthly Calendar").assertIsDisplayed()
        compose.onNodeWithText("Completed").assertIsDisplayed()
        compose.onAllNodesWithText("Pending").onFirst().assertIsDisplayed()

        compose.onNodeWithTag("nav-stats").performClick()
        compose.onNodeWithText("Task Detail").assertIsDisplayed()

        compose.onNodeWithTag("nav-settings").performClick()
        compose.onNodeWithText("Backup & Settings").assertIsDisplayed()
        compose.onNodeWithText("Day boundary").assertIsDisplayed()
    }
}

private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.waitUntilExists(text: String) {
    waitUntil(timeoutMillis = 10_000) {
        onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
}
