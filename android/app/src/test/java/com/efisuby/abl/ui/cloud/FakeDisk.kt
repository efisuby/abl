package com.efisuby.abl.ui.cloud

import com.efisuby.abl.data.disk.DiskException
import com.efisuby.abl.data.disk.DiskItems
import com.efisuby.abl.data.disk.DiskResource
import com.efisuby.abl.data.disk.DiskSource

class FakeDisk(private val tree: Map<String, List<DiskResource>>) : DiskSource {
    val requested = mutableListOf<String>()

    override suspend fun list(path: String, limit: Int, offset: Int): DiskItems {
        requested += path
        val all = tree[path] ?: throw DiskException.NotFound(path)
        val page = all.drop(offset).take(limit)
        return DiskItems(items = page, total = all.size, offset = offset, limit = limit)
    }
}

fun dir(path: String) = DiskResource(name = path.substringAfterLast('/'), path = path, type = "dir")
fun file(path: String, size: Long = 100) = DiskResource(name = path.substringAfterLast('/'), path = path, type = "file", size = size)
