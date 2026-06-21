package com.nan.player

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.gyf.immersionbar.BarHide
import com.gyf.immersionbar.ImmersionBar
import com.nannan.superplayer.player.DownloadState
import com.nannan.superplayer.player.VideoDownloadManager

class PlayerFeedActivity : AppCompatActivity(), VideoPageFragment.Callbacks {
    private val videos = VideoCatalog.videos

    private lateinit var root: FrameLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var adapter: VideoFeedAdapter

    private var currentIndex = 0
    private var surfaceCenterIndex = 0
    private var playbackIndexes: Set<Int> = emptySet()
    private var pageChangeCallback: ViewPager2.OnPageChangeCallback? = null
    private var scrollState = ViewPager2.SCROLL_STATE_IDLE
    private var lastPreloadCenterIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setupImmersiveBars()
        setupContentView()

        currentIndex = savedInstanceState?.getInt(KEY_CURRENT_INDEX)?.coerceIn(videos.indices) ?: 0
        PreloadCoordinator.scheduleForPlayback(videos, currentIndex)
        viewPager.setCurrentItem(currentIndex, false)
        viewPager.post {
            updatePlaybackWindow(currentIndex)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(KEY_CURRENT_INDEX, currentIndex)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        setupImmersiveBars()
        updatePlaybackWindow(currentIndex)
    }

    override fun onPause() {
        pauseActivePlayers()
        super.onPause()
    }

    override fun onStop() {
        stopActivePlayersForHost()
        super.onStop()
    }

    override fun onDestroy() {
        pageChangeCallback?.let(viewPager::unregisterOnPageChangeCallback)
        pageChangeCallback = null
        adapter.fragmentsSnapshot().values.forEach { it.releasePlayback() }
        PreloadCoordinator.shutdown()
        super.onDestroy()
    }

    override fun onPageReady(position: Int) {
        adapter.fragmentAt(position)?.setPlaybackActive(
            active = position in activeIndexes(surfaceCenterIndex),
            playWhenReady = position in playbackIndexes,
            muted = position != currentIndex,
            allowCreatePlayer = shouldCreatePlayer(position, surfaceCenterIndex)
        )
    }

    override fun onPageTap(position: Int) {
        if (position != currentIndex) return
        val isPlaying = currentFragment()?.togglePlayback() ?: false
        currentFragment()?.showCenterState(isPlaying)
    }

    override fun onDownloadClick(position: Int) {
        if (position !in videos.indices) return
        val item = videos[position]
        if (!item.isDownloadable) {
            Toast.makeText(this, "This item is not downloadable", Toast.LENGTH_SHORT).show()
            return
        }

        when (VideoDownloadManager.getState(item.url)) {
            DownloadState.DOWNLOADING,
            DownloadState.PENDING -> {
                VideoDownloadManager.cancel(item.url)
                Toast.makeText(this, "Download cancelled", Toast.LENGTH_SHORT).show()
            }
            DownloadState.COMPLETED -> {
                Toast.makeText(this, "Already downloaded", Toast.LENGTH_SHORT).show()
            }
            else -> {
                VideoDownloadManager.download(item.url)
                Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show()
            }
        }
        adapter.fragmentAt(position)?.renderDownloadState()
    }

    override fun onSeekStart(position: Int) {
        if (position != currentIndex) return
        currentFragment()?.beginUserSeek()
    }

    override fun onSeekPreview(position: Int, progress: Int) {
        if (position != currentIndex) return
        currentFragment()?.previewSeek(progress)
    }

    override fun onSeekStop(position: Int, progress: Int) {
        if (position != currentIndex) return
        currentFragment()?.finishUserSeek(progress)
    }

    private fun setupContentView() {
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        adapter = VideoFeedAdapter(this, videos)
        viewPager = ViewPager2(this).apply {
            orientation = ViewPager2.ORIENTATION_VERTICAL
            offscreenPageLimit = PAGE_OFFSCREEN_LIMIT.coerceAtMost((videos.size - 1).coerceAtLeast(1))
            adapter = this@PlayerFeedActivity.adapter
            setBackgroundColor(Color.BLACK)
        }
        root.addView(
            viewPager,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )
        setContentView(root)

        pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(
                position: Int,
                positionOffset: Float,
                positionOffsetPixels: Int
            ) {
                if (positionOffset > 0f) {
                    updatePlaybackDuringScroll(position, positionOffset)
                }
            }

            override fun onPageSelected(position: Int) {
                preloadAround(position)
//                if (scrollState == ViewPager2.SCROLL_STATE_IDLE) {
//                    currentIndex = position
//                    updatePlaybackWindow(position, setOf(position))
//                }
            }

            override fun onPageScrollStateChanged(state: Int) {
                scrollState = state
                if (state == ViewPager2.SCROLL_STATE_IDLE) {
                    currentIndex = viewPager.currentItem
                    updatePlaybackWindow(currentIndex, setOf(currentIndex))
                }
            }
        }.also(viewPager::registerOnPageChangeCallback)
    }

    private fun updatePlaybackWindow(centerIndex: Int, playingIndexes: Set<Int> = setOf(centerIndex)) {
        if (centerIndex !in videos.indices) return
        surfaceCenterIndex = centerIndex
        playbackIndexes = playingIndexes.filter { it in videos.indices }.toSet()
        val activeIndexes = activeIndexes(centerIndex)
        adapter.fragmentsSnapshot().forEach { (index, fragment) ->
            fragment.setPlaybackActive(
                active = index in activeIndexes,
                playWhenReady = index in playbackIndexes,
                muted = index != centerIndex,
                allowCreatePlayer = shouldCreatePlayer(index, centerIndex)
            )
        }
//        preloadAround(centerIndex)
    }

    private fun updatePlaybackDuringScroll(position: Int, offset: Float) {
        val activeIndexes = activeIndexes(surfaceCenterIndex)
        val visiblePlaybackIndexes = visiblePlaybackIndexes(position, offset)
        adapter.fragmentsSnapshot().forEach { (index, fragment) ->
            if (index !in activeIndexes) return@forEach

            val canCreatePlayer = shouldCreatePlayer(index, surfaceCenterIndex)
            val canPlayDuringScroll = index == currentIndex || PreloadCoordinator.isPreloaded(videos[index])
            fragment.setPlaybackActive(
                active = true,
                playWhenReady = index in visiblePlaybackIndexes && canPlayDuringScroll,
                muted = index != currentIndex,
                allowCreatePlayer = canCreatePlayer
            )
        }
    }

    private fun preloadAround(centerIndex: Int) {
        if (centerIndex !in videos.indices || lastPreloadCenterIndex == centerIndex) return
        Log.i("PlayerFeedActivity", "preload center=$centerIndex last=$lastPreloadCenterIndex")
        PreloadCoordinator.scheduleForPlayback(videos, centerIndex)
        lastPreloadCenterIndex = centerIndex
    }

    private fun shouldCreatePlayer(index: Int, centerIndex: Int): Boolean {
        return index in activeIndexes(centerIndex)
    }

    private fun pauseActivePlayers() {
        adapter.fragmentsSnapshot().values.forEach { it.pausePlayback() }
    }

    private fun stopActivePlayersForHost() {
        adapter.fragmentsSnapshot().values.forEach { it.stopPlaybackForHost() }
    }

    private fun activeIndexes(centerIndex: Int): Set<Int> {
        return ((centerIndex - ACTIVE_SURFACE_RADIUS)..(centerIndex + ACTIVE_SURFACE_RADIUS))
            .filter { it in videos.indices }
            .toSet()
    }

    private fun visiblePlaybackIndexes(position: Int, offset: Float): Set<Int> {
        if (offset <= SCROLL_PLAY_THRESHOLD) {
            return setOf(viewPager.currentItem.coerceIn(videos.indices))
        }

        return buildSet {
            add(position)
            if (position < videos.lastIndex) add(position + 1)
        }
    }

    private fun currentFragment(): VideoPageFragment? {
        return adapter.fragmentAt(currentIndex)
    }

    private fun setupImmersiveBars() {
        ImmersionBar.with(this)
            .transparentStatusBar()
            .transparentNavigationBar()
            .hideBar(BarHide.FLAG_HIDE_BAR)
            .fullScreen(true)
            .statusBarDarkFont(false)
            .navigationBarDarkIcon(false)
            .init()
    }

    companion object {
        private const val KEY_CURRENT_INDEX = "current_index"
        private const val PAGE_OFFSCREEN_LIMIT = 1
        private const val ACTIVE_SURFACE_RADIUS = 1
        private const val SCROLL_PLAY_THRESHOLD = 0.02f
    }
}
