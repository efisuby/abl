package com.efisuby.abl.ui.cloud

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.efisuby.abl.data.disk.DiskException
import com.efisuby.abl.data.disk.DiskResource
import com.efisuby.abl.data.disk.DiskSource
import com.efisuby.abl.data.disk.listAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.IOException

data class CloudBrowserState(
    /** Path of the level on screen. */
    val path: String,
    val isRoot: Boolean,
    val loading: Boolean = true,
    /** Folders first, then supported audio files; other files are hidden. */
    val items: List<DiskResource> = emptyList(),
    /** The level could not be loaded (network, server); a missing root is just empty. */
    val failed: Boolean = false,
)

/**
 * Browses the cloud one level at a time: only the opened folder is requested, and levels
 * already visited are kept so going back does not hit the network again.
 */
class CloudBrowserViewModel(
    private val source: DiskSource,
    private val root: String,
) : ViewModel() {

    private val stack = ArrayDeque(listOf(root))
    private val levels = mutableMapOf<String, List<DiskResource>>()
    private var job: Job? = null

    private val _state = MutableStateFlow(CloudBrowserState(root, isRoot = true))
    val state: StateFlow<CloudBrowserState> = _state

    init {
        show(root)
    }

    fun open(folder: DiskResource) {
        if (!folder.isFolder) return
        stack.addLast(folder.path)
        show(folder.path)
    }

    /** Returns false when already at the root, so the screen can close. */
    fun up(): Boolean {
        if (stack.size <= 1) return false
        stack.removeLast()
        show(stack.last())
        return true
    }

    fun retry() {
        levels.remove(stack.last())
        show(stack.last())
    }

    private fun show(path: String) {
        job?.cancel()
        val cached = levels[path]
        if (cached != null) {
            _state.value = CloudBrowserState(path, path == root, loading = false, items = cached)
            return
        }
        _state.value = CloudBrowserState(path, path == root)
        job = viewModelScope.launch {
            val items = try {
                source.listAll(path).filter { it.isFolder || it.isAudio }
                    .sortedWith(compareBy({ !it.isFolder }, { it.name.lowercase() }))
            } catch (e: CancellationException) {
                throw e
            } catch (e: DiskException.NotFound) {
                emptyList() // the books folder has not been created yet
            } catch (e: DiskException) {
                // Class and HTTP code only: neither the token nor response bodies are logged.
                Log.w(TAG, "Listing failed: ${e.javaClass.simpleName}" + ((e as? DiskException.Http)?.let { " ${it.code}" } ?: ""))
                return@launch fail(path)
            } catch (e: IOException) {
                Log.w(TAG, "Listing failed: ${e.javaClass.simpleName}")
                return@launch fail(path)
            }
            levels[path] = items
            _state.value = CloudBrowserState(path, path == root, loading = false, items = items)
        }
    }

    private fun fail(path: String) {
        _state.value = CloudBrowserState(path, path == root, loading = false, failed = true)
    }

    private companion object {
        const val TAG = "CloudBrowser"
    }
}
