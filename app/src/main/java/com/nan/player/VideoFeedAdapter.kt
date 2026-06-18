package com.nan.player

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import java.lang.ref.WeakReference

class VideoFeedAdapter(
    activity: FragmentActivity,
    private val videos: List<VideoItem>
) : FragmentStateAdapter(activity) {
    private val fragments = mutableMapOf<Int, WeakReference<VideoPageFragment>>()

    override fun getItemCount(): Int = videos.size

    override fun createFragment(position: Int): Fragment {
        return VideoPageFragment.newInstance(position, videos[position]).also {
            fragments[position] = WeakReference(it)
        }
    }

    fun fragmentAt(position: Int): VideoPageFragment? {
        return fragments[position]?.get()
    }

    fun fragmentsSnapshot(): Map<Int, VideoPageFragment> {
        return fragments.mapNotNull { (position, reference) ->
            reference.get()?.let { fragment -> position to fragment }
        }.toMap()
    }
}
