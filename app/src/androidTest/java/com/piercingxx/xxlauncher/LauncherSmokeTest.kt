package com.piercingxx.xxlauncher

import android.content.res.Configuration
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.piercingxx.xxlauncher.data.SettingsRepository
import com.piercingxx.xxlauncher.data.SlotEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Smoke tests that run on a device/emulator. */
@RunWith(AndroidJUnit4::class)
class LauncherSmokeTest {

    private val app: LauncherApplication
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun homeActivityLaunchesAndRendersSlots() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val slots =
                    activity.findViewById<android.view.ViewGroup>(R.id.homeSlotsContainer)
                assertEquals(
                    app.settings.slotCount.coerceIn(0, SettingsRepository.MAX_SLOTS),
                    slots.childCount,
                )
            }
        }
    }

    @Test
    fun homeIsPortraitLocked() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(
                    Configuration.ORIENTATION_PORTRAIT,
                    activity.resources.configuration.orientation,
                )
            }
        }
    }

    @Test
    fun drawerLaunchesWithSearchField() {
        ActivityScenario.launch(AppDrawerActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val search = activity.findViewById<android.widget.EditText>(R.id.searchEditText)
                assertTrue(search.isShown)
            }
        }
    }

    @Test
    fun appPickerLaunches() {
        ActivityScenario.launch(AppPickerActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
            }
        }
    }

    @Test
    fun settingsActivityShowsPreferences() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
            }
        }
    }

    @Test
    fun slotAssignmentsSurviveRepositoryReload() {
        val settings = app.settings
        val original = settings.getSlot(1)
        try {
            settings.setSlot(
                1,
                SlotEntry(label = "Test", packageName = "com.example.test", userToken = "personal"),
            )
            // A fresh repository over the same prefs file sees the write.
            val reloaded = SettingsRepository(app).getSlot(1)
            assertEquals("Test", reloaded.label)
            assertEquals("com.example.test", reloaded.packageName)
        } finally {
            settings.setSlot(1, original)
        }
    }
}
