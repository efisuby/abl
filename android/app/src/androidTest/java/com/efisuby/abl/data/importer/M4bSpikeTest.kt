package com.efisuby.abl.data.importer

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Spike for task 2.4 on real books (`docs/spikes/m4b-chapters.md`). Skipped unless files were
 * pushed with `adb push <files> /data/local/tmp/abl-spike/`; results go to logcat tag `M4bSpike`.
 */
@RunWith(AndroidJUnit4::class)
class M4bSpikeTest {

    @Test
    fun reportChaptersOfRealBooks() {
        val files = File(SPIKE_DIR).listFiles { f -> AudioFormats.isSupported(f.name) }.orEmpty()
        assumeTrue("no spike files in $SPIKE_DIR", files.isNotEmpty())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val media3 = Media3ChapterReader(context)
        val track = Mp4ChapterTrackReader()
        val tags = MediaMetadataTagReader()

        for (original in files.sortedBy { it.name }) {
            // The media server cannot open files labelled for the shell, even through a descriptor;
            // a copy in the app's own storage behaves like an imported book.
            val file = File(context.cacheDir, "spike-" + original.name.hashCode() + "." + original.extension)
            original.copyTo(file, overwrite = true)
            var started = System.nanoTime()
            val markers = media3.read(file)
            val media3Ms = (System.nanoTime() - started) / 1_000_000
            started = System.nanoTime()
            val trackMarkers = track.read(file)
            val trackMs = (System.nanoTime() - started) / 1_000_000
            val agree = markers.map { it.startMs to it.title } == trackMarkers.map { it.startMs to it.title }
            val fileTags = tags.read(file)
            val spans = ChapterBoundaries.build(markers, fileTags.durationMs)
            Log.i(TAG, "${file.name}: size=${file.length()} duration=${fileTags.durationMs} " +
                "chpl(media3)=${markers.size} in ${media3Ms}ms track=${trackMarkers.size} in ${trackMs}ms " +
                "agree=$agree spans=${spans.size} " +
                "title=${fileTags.title} artist=${fileTags.artist} album=${fileTags.album} " +
                "cover=${tags.embeddedPicture(file)?.size}")
            markers.zip(trackMarkers).firstOrNull { (a, b) -> a != b }?.let { (a, b) ->
                Log.i(TAG, "  first difference: media3=${a.startMs} '${a.title}' track=${b.startMs} '${b.title}'")
            }
            (spans.take(3) + spans.takeLast(2)).distinct().forEach {
                Log.i(TAG, "  ${it.startMs} +${it.durationMs} ${TtsMarkup.strip(it.title)?.take(60)}")
            }
            file.delete()
        }
    }

    private companion object {
        const val SPIKE_DIR = "/data/local/tmp/abl-spike"
        const val TAG = "M4bSpike"
    }
}
