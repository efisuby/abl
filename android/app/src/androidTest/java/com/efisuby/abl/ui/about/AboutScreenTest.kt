package com.efisuby.abl.ui.about

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.efisuby.abl.R
import com.efisuby.abl.ui.settings.SettingsActivity
import com.efisuby.abl.ui.waitForView
import org.hamcrest.Matchers.containsString
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AboutScreenTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun showsVersionPolicyLicensesAndContactAndOpensTheLicenses() {
        ActivityScenario.launch<AboutActivity>(AboutActivity.intent(context)).use {
            val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
            onView(withText(context.getString(R.string.about_version, version))).check(matches(isDisplayed()))
            onView(withId(R.id.aboutPolicy)).check(matches(isDisplayed()))
            onView(withId(R.id.aboutContact)).check(matches(isDisplayed()))
            onView(withId(R.id.aboutLicenses)).perform(click())
            waitForView(withText(containsString("Apache License")))
            onView(withText(R.string.about_close)).perform(click())
        }
    }

    @Test
    fun isReachableFromSettings() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            waitForView(withId(R.id.themeRow))
            onView(withId(R.id.aboutRow)).perform(scrollTo(), click())
            waitForView(withId(R.id.aboutVersion))
        }
    }
}
