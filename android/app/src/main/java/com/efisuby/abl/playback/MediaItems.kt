package com.efisuby.abl.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.efisuby.abl.domain.model.Book
import java.io.File

/**
 * The Media3 item of a queue entry: the chapter is the title and the book the artist line of
 * the notification; m4b chapters are clips of their file (design D3).
 */
fun QueueEntry.toMediaItem(book: Book): MediaItem {
    val builder = MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(Uri.fromFile(File(filePath)))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(book.title)
                .setAlbumTitle(book.title)
                .setAlbumArtist(book.author)
                .setArtworkUri(book.coverPath?.let { Uri.fromFile(File(it)) })
                .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
                .build(),
        )
    if (clipStartMs != null) {
        builder.setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(clipStartMs)
                .setEndPositionMs(clipEndMs ?: C.TIME_END_OF_SOURCE)
                .build(),
        )
    }
    return builder.build()
}
