package com.efisuby.abl.ui

import android.view.View
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matcher
import org.junit.Assert.assertTrue

/** Waits until a view matching [matcher] is displayed; Room flows update the screen asynchronously. */
fun waitForView(matcher: Matcher<View>, timeoutMs: Long = 10_000) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        try {
            onView(matcher).check(matches(isDisplayed()))
            return
        } catch (e: RuntimeException) {
            if (System.currentTimeMillis() > deadline) throw e
        } catch (e: AssertionError) {
            if (System.currentTimeMillis() > deadline) throw e
        }
        Thread.sleep(100)
    }
}

fun <T> onMain(block: () -> T): T {
    var result: Result<T>? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
    return result!!.getOrThrow()
}

fun waitUntil(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        assertTrue("timed out waiting until $what", System.currentTimeMillis() < deadline)
        Thread.sleep(100)
    }
}

/** Waits until no view matches [matcher]; removed list rows linger for their exit animation. */
fun waitForGone(matcher: Matcher<View>, timeoutMs: Long = 10_000) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        try {
            onView(matcher).check(doesNotExist())
            return
        } catch (e: AssertionError) {
            if (System.currentTimeMillis() > deadline) throw e
        }
        Thread.sleep(100)
    }
}
