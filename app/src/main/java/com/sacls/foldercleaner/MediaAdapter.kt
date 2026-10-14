package com.sacls.foldercleaner

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Adapter lưới thumbnail. Ảnh được [ThumbLoader] load bất đồng bộ + cache; video chỉ hiện
 * icon nền + badge play. Bấm vào ô -> [onClick] (mở/play file).
 */
class MediaAdapter(
    private val thumbLoader: ThumbLoader,
    private val onClick: (CleanerEngine.MediaItem) -> Unit
) : RecyclerView.Adapter<MediaAdapter.VH>() {

    private val items = ArrayList<CleanerEngine.MediaItem>()

    fun submit(list: List<CleanerEngine.MediaItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun addItem(item: CleanerEngine.MediaItem) {
        items.add(item)
        notifyItemInserted(items.size - 1)
    }

    fun clear() {
        val n = items.size
        items.clear()
        notifyItemRangeRemoved(0, n)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_media, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.name.text = item.name

        if (item.isVideo) {
            // Video: nền tối + icon video + badge play. Không decode, nhẹ và an toàn.
            thumbLoader.cancel(holder.thumb)
            holder.thumb.setImageResource(R.drawable.ic_videocam)
            holder.thumb.scaleType = ImageView.ScaleType.CENTER
            holder.thumb.setBackgroundColor(0xFF37474F.toInt())
            holder.playBadge.visibility = View.VISIBLE
        } else {
            holder.thumb.scaleType = ImageView.ScaleType.CENTER_CROP
            holder.thumb.setBackgroundColor(0x00000000)
            holder.playBadge.visibility = View.GONE
            thumbLoader.load(item.uri, holder.thumb)
        }

        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun onViewRecycled(holder: VH) {
        thumbLoader.cancel(holder.thumb)
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val playBadge: ImageView = v.findViewById(R.id.playBadge)
        val name: TextView = v.findViewById(R.id.tvName)
    }
}
