package com.efisuby.abl.ui.cloud

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.efisuby.abl.R
import com.efisuby.abl.appContainer
import com.efisuby.abl.data.auth.AesGcmCipher
import com.efisuby.abl.data.auth.AuthRepository
import com.efisuby.abl.data.auth.TokenStore
import com.efisuby.abl.data.disk.DiskApi
import com.efisuby.abl.data.disk.DiskException
import com.efisuby.abl.data.disk.DiskItems
import com.efisuby.abl.data.disk.DiskResource
import com.efisuby.abl.data.disk.DiskSource
import com.efisuby.abl.di.AppContainer
import com.efisuby.abl.ui.waitForView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.crypto.KeyGenerator

@RunWith(AndroidJUnit4::class)
class CloudScreenTest {

    private class FakeDisk(private val tree: Map<String, List<DiskResource>>) : DiskSource {
        val requested = mutableListOf<String>()

        override suspend fun list(path: String, limit: Int, offset: Int): DiskItems {
            requested += path
            val all = tree[path] ?: throw DiskException.NotFound(path)
            return DiskItems(items = all.drop(offset).take(limit), total = all.size, offset = offset, limit = limit)
        }
    }

    private fun dir(path: String) = DiskResource(name = path.substringAfterLast('/'), path = path, type = "dir")
    private fun file(path: String) = DiskResource(name = path.substringAfterLast('/'), path = path, type = "file", size = 1024)

    private val root = DiskApi.BOOKS_ROOT
    private lateinit var context: Context
    private lateinit var container: AppContainer
    private lateinit var originalAuth: AuthRepository
    private lateinit var originalDisk: DiskSource
    private lateinit var tokenFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        container = context.appContainer
        originalAuth = container.authRepository
        originalDisk = container.diskSource
        // A signed-in state of its own: the real token of the device is never touched.
        tokenFile = File(context.cacheDir, "cloud-test-token.bin")
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        container.authRepository = AuthRepository(TokenStore(tokenFile, AesGcmCipher { key })).also { it.signIn("test") }
    }

    @After
    fun tearDown() {
        container.authRepository = originalAuth
        container.diskSource = originalDisk
        tokenFile.delete()
    }

    @Test
    fun openingAFolderRequestsOnlyItsContents() {
        val disk = FakeDisk(
            mapOf(
                root to listOf(dir("$root/Series"), dir("$root/Other"), file("$root/Single.m4b")),
                "$root/Series" to listOf(file("$root/Series/01.mp3")),
                "$root/Other" to listOf(file("$root/Other/zzz.mp3")),
            ),
        )
        container.diskSource = disk

        ActivityScenario.launch(CloudActivity::class.java).use {
            waitForView(withText("Series"))
            onView(withText("Single.m4b")).check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
            assertEquals(listOf(root), disk.requested)

            onView(withText("Series")).perform(click())
            waitForView(withText("01.mp3"))
            onView(withText("Other")).check(doesNotExist())
            assertEquals(listOf(root, "$root/Series"), disk.requested) // "Other" was never requested
        }
    }

    @Test
    fun emptyRootShowsHowToAddBooks() {
        container.diskSource = FakeDisk(mapOf(root to emptyList()))
        ActivityScenario.launch(CloudActivity::class.java).use {
            waitForView(withId(R.id.noticeText))
            onView(withId(R.id.noticeText)).check(androidx.test.espresso.assertion.ViewAssertions.matches(withText(R.string.cloud_empty)))
        }
    }
}
