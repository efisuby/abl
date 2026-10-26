package com.efisuby.abl.ui

import android.app.LocaleManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.os.Build
import android.util.TypedValue
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.efisuby.abl.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Dark-only, Russian-only (app-shell spec), whatever the system is set to. */
@RunWith(AndroidJUnit4::class)
class AppShellTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun appIsDarkWhenTheSystemIsLight() {
        val before = shell("cmd uimode night").trim() // "Night mode: no" / "yes" / "auto"
        shell("cmd uimode night no")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val night = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                    assertEquals(Configuration.UI_MODE_NIGHT_YES, night)
                    val surface = TypedValue().also { activity.theme.resolveAttribute(com.google.android.material.R.attr.colorSurface, it, true) }
                    assertTrue("the surface is dark", ColorUtils.calculateLuminance(surface.data or Color.BLACK) < 0.2)
                }
            }
        } finally {
            shell("cmd uimode night ${before.substringAfter(": ", "no")}")
        }
    }

    @Test
    fun interfaceIsRussianUnderAnotherSystemLanguage() {
        assumeTrue("the device itself must not be Russian", systemLanguage() != "ru")

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForView(withText("Библиотека"))
            scenario.onActivity { activity ->
                assertEquals("ru", activity.resources.configuration.locales[0].language)
                // Library strings follow too, not only the app's own values/.
                assertNotEquals("Search", activity.getString(androidx.appcompat.R.string.abc_searchview_description_search))
            }
        }
    }

    /** The device language; `Resources.getSystem()` already reports the app's own locale on Android 13+. */
    private fun systemLanguage(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.targetContext.getSystemService(LocaleManager::class.java).systemLocales[0].language
        } else {
            Resources.getSystem().configuration.locales[0].language
        }

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { pfd ->
            java.io.FileInputStream(pfd.fileDescriptor).bufferedReader().readText()
        }
}
