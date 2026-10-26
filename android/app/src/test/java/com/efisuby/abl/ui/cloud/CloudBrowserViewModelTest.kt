package com.efisuby.abl.ui.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloudBrowserViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val disk = FakeDisk(
        mapOf(
            "root" to listOf(file("root/z.mp3"), file("root/notes.txt"), dir("root/B"), dir("root/a"), file("root/x.m4b")),
            "root/B" to listOf(file("root/B/1.mp3")),
            "root/a" to emptyList(),
        ),
    )

    @Test
    fun rootShowsFoldersFirstAndHidesNonAudio() = runTest(dispatcher) {
        val vm = CloudBrowserViewModel(disk, "root")
        advanceUntilIdle()
        assertEquals(listOf("a", "B", "x.m4b", "z.mp3"), vm.state.value.items.map { it.name })
        assertFalse(vm.state.value.loading)
        assertEquals(listOf("root"), disk.requested)
    }

    @Test
    fun openingAFolderRequestsOnlyItsContents() = runTest(dispatcher) {
        val vm = CloudBrowserViewModel(disk, "root")
        advanceUntilIdle()
        vm.open(vm.state.value.items.first { it.name == "B" })
        advanceUntilIdle()

        assertEquals(listOf("root", "root/B"), disk.requested)
        assertEquals(listOf("1.mp3"), vm.state.value.items.map { it.name })
        assertFalse(vm.state.value.isRoot)
    }

    @Test
    fun goingBackUsesTheCachedLevel() = runTest(dispatcher) {
        val vm = CloudBrowserViewModel(disk, "root")
        advanceUntilIdle()
        vm.open(vm.state.value.items.first { it.name == "B" })
        advanceUntilIdle()

        assertTrue(vm.up())
        assertEquals(4, vm.state.value.items.size)
        assertEquals(listOf("root", "root/B"), disk.requested)
        assertFalse(vm.up())
    }

    @Test
    fun missingRootIsEmptyNotAnError() = runTest(dispatcher) {
        val vm = CloudBrowserViewModel(disk, "nowhere")
        advanceUntilIdle()
        assertTrue(vm.state.value.items.isEmpty())
        assertFalse(vm.state.value.failed)
    }
}
