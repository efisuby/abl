package com.efisuby.abl.playback

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.efisuby.abl.data.db.AblDatabase
import com.efisuby.abl.data.importer.BookImporter
import com.efisuby.abl.data.importer.BookStorage
import com.efisuby.abl.data.importer.CascadingChapterReader
import com.efisuby.abl.data.importer.FixtureDocumentsProvider
import com.efisuby.abl.data.importer.ImportSource
import com.efisuby.abl.data.importer.Media3ChapterReader
import com.efisuby.abl.data.importer.MediaMetadataTagReader
import com.efisuby.abl.data.importer.Mp4ChapterTrackReader
import com.efisuby.abl.data.importer.SafFileSource
import com.efisuby.abl.data.importer.SafTreeSource
import com.efisuby.abl.data.repository.RoomLibraryRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Plays imported fixture books through the same queue the service builds and checks that the
 * player moves from chapter 1 to chapter 2 by itself, for an mp3 folder and for an m4b.
 */
@RunWith(AndroidJUnit4::class)
class ChapterAdvanceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var context: Context
    private lateinit var database: AblDatabase
    private lateinit var library: RoomLibraryRepository
    private lateinit var storage: BookStorage
    private lateinit var importer: BookImporter
    private lateinit var resolver: android.content.ContentResolver
    private var player: ExoPlayer? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        resolver = FixtureDocumentsProvider.install(instrumentation.context, context, File(context.cacheDir, "fixtures"))
        database = Room.inMemoryDatabaseBuilder(context, AblDatabase::class.java).build()
        library = RoomLibraryRepository(database)
        storage = BookStorage(File(context.cacheDir, "books-playback").apply { deleteRecursively() })
        val chapters = CascadingChapterReader(listOf(Media3ChapterReader(context), Mp4ChapterTrackReader()))
        importer = BookImporter(storage, library, MediaMetadataTagReader(), chapters)
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { player?.release() }
        database.close()
        storage.root.deleteRecursively()
    }

    @Test
    fun mp3FolderAdvancesToTheNextFile() =
        assertAdvances(SafTreeSource(resolver, FixtureDocumentsProvider.treeUri("tagged")), clipped = false)

    @Test
    fun m4bAdvancesToTheNextChapterOfTheSameFile() =
        assertAdvances(SafFileSource(resolver, FixtureDocumentsProvider.documentUri("chapters.m4b")), clipped = true)

    private fun assertAdvances(source: ImportSource, clipped: Boolean) {
        val (book, queue) = runBlocking {
            val book = importer.import(source)
            book to ChapterQueue.build(book.id, library.getChapters(book.id))
        }
        assertTrue(queue.size >= 2)
        assertEquals(clipped, queue.first().clipStartMs != null)

        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(context).build().apply {
                setMediaItems(queue.map { it.toMediaItem(book) })
                prepare()
                play()
            }
        }

        val deadline = System.currentTimeMillis() + 15_000
        var index = 0
        var state = Player.STATE_IDLE
        while (System.currentTimeMillis() < deadline) {
            instrumentation.runOnMainSync {
                index = player!!.currentMediaItemIndex
                state = player!!.playbackState
            }
            if (index >= 1) break
            Thread.sleep(100)
        }
        assertEquals("chapter after the first one ended (state $state)", 1, index)

        var playWhenReady = false
        var mediaId = ""
        instrumentation.runOnMainSync {
            playWhenReady = player!!.playWhenReady
            mediaId = player!!.currentMediaItem!!.mediaId
        }
        assertTrue("still playing without user action", playWhenReady)
        assertEquals(queue[1].mediaId, mediaId)
    }
}
