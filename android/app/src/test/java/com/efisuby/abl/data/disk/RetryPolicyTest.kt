package com.efisuby.abl.data.disk

import com.efisuby.abl.data.importer.DownloadFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class RetryPolicyTest {

    @Test
    fun retriesEveryFifteenSecondsFifteenTimesInTotal() {
        assertEquals(15_000L, RetryPolicy.DELAY_MS)
        assertEquals(15, RetryPolicy.MAX_ATTEMPTS)
    }

    @Test
    fun networkAndServerTroubleIsTransient() {
        assertTrue(RetryPolicy.isTransient(UnknownHostException()))
        assertTrue(RetryPolicy.isTransient(IOException("reset")))
        assertTrue(RetryPolicy.isTransient(DiskException.Http(503)))
        assertTrue(RetryPolicy.isTransient(DiskException.RateLimited()))
    }

    @Test
    fun finalErrorsAreNotRetried() {
        assertFalse(RetryPolicy.isTransient(DiskException.Http(403)))
        assertFalse(RetryPolicy.isTransient(DiskException.Unauthorized()))
        assertFalse(RetryPolicy.isTransient(DiskException.NotFound("x")))
        assertFalse(RetryPolicy.isTransient(ChecksumMismatchException("f")))
    }

    @Test
    fun fileServerErrorsAreTransientButClientErrorsAreNot() {
        assertTrue(RetryPolicy.isTransient(DownloadHttpException(500)))
        assertTrue(RetryPolicy.isTransient(DownloadHttpException(429)))
        assertFalse(RetryPolicy.isTransient(DownloadHttpException(403)))
        assertFalse(RetryPolicy.isTransient(DownloadHttpException(404)))
        assertTrue(RetryPolicy.isTransient(FileDownloadException("f", DownloadHttpException(502))))
        assertFalse(RetryPolicy.isTransient(FileDownloadException("f", ChecksumMismatchException("f"))))
    }

    @Test
    fun theSameFileFailingTwiceWithAServerErrorIsStuck() {
        val first = DownloadFailure("audio/17.mp3", 500)
        assertFalse(RetryPolicy.isStuck(null, first))
        assertTrue(RetryPolicy.isStuck(first, DownloadFailure("audio/17.mp3", 500)))
        assertFalse("another file", RetryPolicy.isStuck(first, DownloadFailure("audio/18.mp3", 500)))
        assertFalse("no server answer", RetryPolicy.isStuck(DownloadFailure("a", null), DownloadFailure("a", null)))
    }
}
