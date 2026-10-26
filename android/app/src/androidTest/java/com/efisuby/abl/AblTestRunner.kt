package com.efisuby.abl

import android.content.res.Configuration
import android.os.Build
import androidx.test.runner.AndroidJUnitRunner
import com.efisuby.abl.data.settings.AppLanguage
import com.efisuby.abl.ui.common.Appearance
import java.util.Locale

/**
 * Most UI tests find views by their Russian text, so the app language is pinned to Russian
 * whatever the emulator is set to. Tests of other languages change it themselves and put it back.
 */
class AblTestRunner : AndroidJUnitRunner() {

    override fun onStart() {
        runOnMainSync { Appearance.apply(targetContext, AppLanguage.RUSSIAN) }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // Before Android 13 the app language reaches only the screens; text a test takes from the
            // application context must agree with them.
            val resources = targetContext.resources
            val configuration = Configuration(resources.configuration).apply { setLocale(Locale("ru")) }
            @Suppress("DEPRECATION")
            resources.updateConfiguration(configuration, resources.displayMetrics)
        }
        // The system pushes the new configuration to the process a moment later.
        val deadline = System.currentTimeMillis() + 5_000
        while (targetContext.resources.configuration.locales[0].language != "ru" && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        super.onStart()
    }
}
