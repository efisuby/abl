package com.efisuby.abl.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.efisuby.abl.R
import com.efisuby.abl.appContainer
import com.efisuby.abl.data.auth.AuthRepository
import com.efisuby.abl.data.auth.AuthState
import com.efisuby.abl.data.auth.TokenStore
import com.efisuby.abl.data.importer.FixtureDocumentsProvider
import com.efisuby.abl.data.importer.SafTreeSource
import com.efisuby.abl.di.AppContainer
import com.efisuby.abl.playback.PlaybackService
import com.efisuby.abl.ui.player.PlayerActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A fresh install that never signs in to Yandex: the local features (import, playback, bookmarks)
 * must work without an account, and no cloud call may be needed for them.
 */
@RunWith(AndroidJUnit4::class)
class FirstRunTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var context: Context
    private lateinit var container: AppContainer
    private lateinit var originalAuth: AuthRepository
    private var bookId: String? = null
    private var probe: MediaController? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        container = context.appContainer
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        originalAuth = container.authRepository
        // An empty token file: signed out, and the real token (if any) is untouched.
        container.authRepository = AuthRepository(TokenStore(File(context.cacheDir, "first-run-token.bin"), PlainCipher))
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync {
            probe?.run {
                stop()
                clearMediaItems()
                release()
            }
        }
        container.authRepository = originalAuth
        bookId?.let {
            runBlocking {
                container.libraryRepository.deleteBook(it)
                container.bookStorage.delete(it)
            }
        }
    }

    @Test
    fun withoutSigningInABookCanBeImportedPlayedAndBookmarked() {
        assertEquals(AuthState.SIGNED_OUT, container.authRepository.state.value)

        val resolver = FixtureDocumentsProvider.install(instrumentation.context, context, File(context.cacheDir, "fixtures"))
        val id = runBlocking {
            container.bookImporter.import(SafTreeSource(resolver, FixtureDocumentsProvider.treeUri("long"))).id
        }
        bookId = id
        runBlocking { assertEquals(true, container.libraryRepository.observeBooks().first().any { it.id == id }) }
        assertNull(container.authRepository.token())

        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, id)).use {
            val controller = connectProbe()
            waitUntil("the book is loaded") { onMain { controller.currentMediaItem?.mediaId?.startsWith(id) == true } }

            onMain { controller.play() }
            waitUntil("playback advances") { onMain { controller.isPlaying && controller.currentPosition > 500 } }

            onView(withId(R.id.addBookmark)).perform(click())
            onView(withText(R.string.bookmark_save)).perform(click())
            waitUntil("the bookmark is stored") {
                runBlocking { container.bookmarkRepository.observe(id).first().size == 1 }
            }
        }
    }

    private fun connectProbe(): MediaController {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = onMain { MediaController.Builder(context, token).buildAsync() }
        return future.get(10, TimeUnit.SECONDS).also { probe = it }
    }

    private object PlainCipher : com.efisuby.abl.data.auth.TokenCipher {
        override fun encrypt(plain: ByteArray) = plain
        override fun decrypt(encrypted: ByteArray) = encrypted
    }
}
