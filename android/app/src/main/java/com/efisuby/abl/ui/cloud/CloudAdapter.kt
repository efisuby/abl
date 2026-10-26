package com.efisuby.abl.ui.cloud

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.efisuby.abl.R
import com.efisuby.abl.data.disk.DiskResource
import com.efisuby.abl.databinding.ItemCloudBinding

class CloudAdapter(
    private val onFolder: (DiskResource) -> Unit,
    private val onDownload: (DiskResource) -> Unit,
) : ListAdapter<DiskResource, CloudAdapter.Holder>(Diff) {

    inner class Holder(val binding: ItemCloudBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemCloudBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        val context = holder.itemView.context
        holder.binding.name.text = item.name
        holder.binding.icon.setImageResource(if (item.isFolder) R.drawable.ic_folder else R.drawable.ic_audio_file)
        holder.binding.detail.text = if (item.isFolder) {
            context.getString(R.string.cloud_folder)
        } else {
            Formatter.formatShortFileSize(context, item.size)
        }
        holder.itemView.isClickable = item.isFolder
        holder.itemView.setOnClickListener(if (item.isFolder) View.OnClickListener { onFolder(item) } else null)
        holder.binding.download.contentDescription = context.getString(R.string.cloud_download_item, item.name)
        holder.binding.download.setOnClickListener { onDownload(item) }
    }

    private object Diff : DiffUtil.ItemCallback<DiskResource>() {
        override fun areItemsTheSame(a: DiskResource, b: DiskResource) = a.path == b.path
        override fun areContentsTheSame(a: DiskResource, b: DiskResource) = a == b
    }
}
