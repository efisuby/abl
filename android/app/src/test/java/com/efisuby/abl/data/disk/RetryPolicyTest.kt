package com.efisuby.abl.data.disk

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
}
