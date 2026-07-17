package com.example.habittracker.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HabitTrackerViewModelTest {
    @Test
    fun viewModelOwnsContextBackedStoreWithStartupJob() {
        val application = ApplicationProvider.getApplicationContext<Application>()

        val viewModel = HabitTrackerViewModel(application)

        assertNotNull(viewModel.store.startupJob)
        viewModel.store.startupJob?.cancel()
    }
}
