package com.efisuby.abl.ui

import android.app.Activity
import android.graphics.Color
import android.util.TypedValue
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.efisuby.abl.MainActivity
import com.efisuby.abl.R
import com.efisuby.abl.appContainer
import com.efisuby.abl.data.settings.SyncableSettings
import com.efisuby.abl.data.settings.ThemeMode
import com.efisuby.abl.data.settings.AppLanguage
import com.efisuby.abl.ui.common.Appearance
import com.efisuby.abl.ui.settings.SettingsActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Theme (system / light / dark) and language (ru / en), both device-local (app-shell spec). */
@RunWith(AndroidJUnit4::class)
class AppShellTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val container get() = instrumentation.targetContext.appContainer
    private lateinit var nightBefore: String

    @Before
    fun setUp() {
        nightBefore = shell("cmd uimode night").trim().substringAfter(": ", "no")
    }

    @After
    fun tearDown() {
        runBlocking { container.settingsRepository.setThemeMode(ThemeMode.SYSTEM) }
        shell("cmd uimode night $nightBefore")
        setAppLanguage("ru")
    }

    @Test
    @SdkSuppress(minSdkVersion = 29) // `cmd uimode` does not exist before Android 10
    fun theSystemThemeFollowsTheDeviceSetting() {
        setTheme(ThemeMode.SYSTEM)
        shell("cmd uimode night no")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForSurface(scenario, dark = false)
            shell("cmd uimode night yes")
            waitForSurface(scenario, dark = true)
        }
    }

    @Test
    fun aForcedDarkThemeSurvivesALightSystemAndARestart() {
        shell("cmd uimode night no")
        setTheme(ThemeMode.DARK)
        ActivityScenario.launch(MainActivity::class.java).use { waitForSurface(it, dark = true) }
        // A restart reads the stored value again.
        ActivityScenario.launch(MainActivity::class.java).use { waitForSurface(it, dark = true) }
        assertEquals(ThemeMode.DARK, runBlocking { container.settingsRepository.settings.first().themeMode })
    }

    @Test
    @SdkSuppress(minSdkVersion = 29) // `cmd uimode` does not exist before Android 10
    fun choosingLightInSettingsSwitchesTheOpenScreen() {
        shell("cmd uimode night yes")
        setTheme(ThemeMode.SYSTEM)
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            waitForSurface(scenario, dark = true)
            onView(withId(R.id.themeRow)).perform(click())
            onView(withText(R.string.settings_theme_light)).perform(click())
            waitForSurface(scenario, dark = false)
        }
        assertEquals(ThemeMode.LIGHT, runBlocking { container.settingsRepository.settings.first().themeMode })
    }

    @Test
    fun theThemeIsNeverSynchronized() {
        assertFalse("theme_mode" in SyncableSettings.keys)
        assertFalse("language" in SyncableSettings.keys)
    }

    @Test
    fun choosingEnglishInSettingsChangesTheInterfaceAndPersists() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            waitForView(withText("Настройки"))
            onView(withId(R.id.languageRow)).perform(click())
            onView(withText(R.string.settings_language_en)).perform(click())
            waitForView(withText("Settings"))
            waitForView(withText("Appearance"))
        }
        // The choice is stored by the system, so a new screen comes up in English as well.
        ActivityScenario.launch(MainActivity::class.java).use { waitForView(withText("Library")) }
    }

    @Test
    fun theLibraryAndSettingsAreCompleteInEnglish() {
        setAppLanguage("en")
        ActivityScenario.launch(MainActivity::class.java).use { waitForView(withText("Library")) }
        ActivityScenario.launch(SettingsActivity::class.java).use {
            waitForView(withText("Download over Wi-Fi only"))
            waitForView(withText("Delete all downloads"))
        }
    }

    /** Returns once the system holds [tag] as the app language, so the next test does not see the old one. */
    private fun setAppLanguage(tag: String) {
        val language = AppLanguage.entries.first { it.tag == tag }
        instrumentation.runOnMainSync { Appearance.apply(instrumentation.targetContext, language) }
        val deadline = System.currentTimeMillis() + 5_000
        while (Appearance.currentLanguage(instrumentation.targetContext) != language) {
            check(System.currentTimeMillis() < deadline) { "the app language did not become $tag" }
            Thread.sleep(50)
        }
    }

    private fun setTheme(mode: ThemeMode) = runBlocking { container.settingsRepository.setThemeMode(mode) }

    private fun waitForSurface(scenario: ActivityScenario<out Activity>, dark: Boolean, timeoutMs: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var isDark: Boolean? = null
        while (true) {
            scenario.onActivity { activity ->
                val surface = TypedValue().also {
                    activity.theme.resolveAttribute(com.google.android.material.R.attr.colorSurface, it, true)
                }
                isDark = ColorUtils.calculateLuminance(surface.data or Color.BLACK) < 0.2
            }
            if (isDark == dark) return
            check(System.currentTimeMillis() < deadline) { "expected dark=$dark but the surface is dark=$isDark" }
            Thread.sleep(100)
        }
    }

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { pfd ->
            java.io.FileInputStream(pfd.fileDescriptor).bufferedReader().readText()
        }
}
