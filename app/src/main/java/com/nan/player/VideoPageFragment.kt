package com.nan.player

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.nannan.superplayer.player.DownloadState
import com.nannan.superplayer.player.LoadingReason
import com.nannan.superplayer.player.OnErrorListener
import com.nannan.superplayer.player.OnPlayerStateListener
import com.nannan.superplayer.player.PlayerControllerListener
import com.nannan.superplayer.player.PlayerGestureController
import com.nannan.superplayer.player.PlayerState
import com.nannan.superplayer.player.PlayerZoomMode
import com.nannan.superplayer.player.SuperPlayer
import com.nannan.superplayer.player.SuperPlayerView
import com.nannan.superplayer.player.VideoDownloadManager
import com.nannan.superplayer.player.VideoRendererType
import java.io.File
import java.io.FileOutputStream

class VideoPageFragment : Fragment() {
    interface Callbacks {
        fun onPageReady(position: Int)
        fun onPageTap(position: Int)
        fun onDownloadClick(position: Int)
        fun onSeekStart(position: Int)
        fun onSeekPreview(position: Int, progress: Int)
        fun onSeekStop(position: Int, progress: Int)
    }

    private var callbacks: Callbacks? = null
    private var pagePosition = 0
    private lateinit var item: VideoItem

    private var playerView: SuperPlayerView? = null
    private var gestureController: PlayerGestureController? = null

    private var player: SuperPlayer? = null
    private var prepared = false
    private var playbackWindowActive = false
    private var playbackActive = false
    private var playbackMuted = true
    private var userSeeking = false
    private var resolvedPlayPath: String? = null

    private lateinit var loadingBar: ProgressBar
    private lateinit var centerPlayIcon: ImageView
    private lateinit var indexText: TextView
    private lateinit var titleText: TextView
    private lateinit var descText: TextView
    private lateinit var statusText: TextView
    private lateinit var currentTimeText: TextView
    private lateinit var totalTimeText: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var downloadButton: TextView

    override fun onAttach(context: Context) {
        super.onAttach(context)
        callbacks = context as? Callbacks
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = requireArguments()
        pagePosition = args.getInt(ARG_POSITION)
        item = VideoItem(
            title = args.getString(ARG_TITLE).orEmpty(),
            description = args.getString(ARG_DESCRIPTION).orEmpty(),
            url = args.getString(ARG_URL).orEmpty(),
            duration = args.getString(ARG_DURATION).orEmpty()
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_video_page, container, false)
        bindViews(root)

        renderDownloadState()
        renderLoading(false, LoadingReason.NONE, PlayerState.IDLE)
        renderProgress(0L, 0L)
        root.post { callbacks?.onPageReady(pagePosition) }
        return root
    }

    override fun onDestroyView() {
        requestAncestorsDisallowIntercept(requireView(), disallow = false)
        releasePlayback()
        gestureController?.release()
        gestureController = null
        playerView?.release()
        playerView = null
        seekBar.setOnSeekBarChangeListener(null)
        super.onDestroyView()
    }

    override fun onDetach() {
        callbacks = null
        super.onDetach()
    }

    fun setPlaybackActive(
        active: Boolean,
        playWhenReady: Boolean,
        muted: Boolean,
        allowCreatePlayer: Boolean = true
    ) {
        if (
            playbackWindowActive == active &&
            playbackActive == playWhenReady &&
            playbackMuted == muted &&
            (player != null || !active)
        ) {
            return
        }

        Log.i(
            TAG,
            "setPlaybackActive position=$pagePosition active=$active playWhenReady=$playWhenReady muted=$muted allowCreate=$allowCreatePlayer"
        )
        playbackWindowActive = active
        playbackActive = playWhenReady
        playbackMuted = muted
        if (!active) {
            releasePlayback()
            return
        }

        if (!allowCreatePlayer && player == null) {
            return
        }

        ensurePlayer()
        player?.setMute(muted)
        player?.onActivityResume()
        if (playWhenReady) {
            startPlaybackIfReady()
        } else {
            pausePlaybackForWindow()
        }
    }

    fun pausePlayback() {
        try {
            player?.onActivityPause(activity?.isFinishing == true)
        } catch (error: Throwable) {
            Log.w(TAG, "Pause failed: ${item.url}", error)
        }
    }

    fun stopPlaybackForHost() {
        try {
            player?.onActivityStop(pauseOnStop = false)
        } catch (error: Throwable) {
            Log.w(TAG, "Stop failed: ${item.url}", error)
        }
    }

    fun pausePlaybackForWindow() {
        try {
            player?.pause()
        } catch (error: Throwable) {
            Log.w(TAG, "Window pause failed: ${item.url}", error)
        }
    }

    fun releasePlayback() {
        Log.i(TAG, "releasePlayback position=$pagePosition")
        playbackActive = false
        playbackWindowActive = false
        prepared = false
        resolvedPlayPath = null
        val oldPlayer = player ?: return
        player = null
        try {
            oldPlayer.setOnPlayerStateListener(null)
            oldPlayer.setOnErrorListener(null)
            oldPlayer.setControllerListener(null)
            oldPlayer.onActivityDestroy()
            oldPlayer.release()
        } catch (error: Throwable) {
            Log.w(TAG, "Release failed: ${item.url}", error)
        }
    }

    fun togglePlayback(): Boolean {
        val superPlayer = player ?: run {
            playbackActive = true
            playbackMuted = false
            ensurePlayer()
            startPlaybackIfReady()
            return player?.isPlaying() == true
        }

        return if (superPlayer.isPlaying()) {
            superPlayer.pause()
            false
        } else {
            playbackActive = true
            playbackMuted = false
            superPlayer.setMute(false)
            superPlayer.togglePlayPause()
            superPlayer.isPlaying()
        }
    }

    fun beginUserSeek() {
        userSeeking = true
        player?.onUserSeekStart()
    }

    fun previewSeek(progress: Int) {
        val superPlayer = player
        superPlayer?.onUserSeekPreview(progress)
        val duration = superPlayer?.getDuration() ?: 0L
        if (duration > 0L) {
            renderSeekPreview((duration * progress / SEEKBAR_MAX).coerceAtLeast(0L), duration)
        }
    }

    fun finishUserSeek(progress: Int) {
        val superPlayer = player ?: return
        superPlayer.onUserSeekStop(progress)
        userSeeking = false
    }

    fun resetForPlayback() {
        centerPlayIcon.visibility = View.GONE
        seekBar.progress = 0
        currentTimeText.text = "00:00"
        totalTimeText.text = item.duration
        statusText.text = "Preparing"
        renderLoading(true, LoadingReason.PREPARING, PlayerState.IDLE)
    }

    fun renderState(state: PlayerState) {
        when (state) {
            PlayerState.PREPARING -> {
                statusText.text = "Preparing"
                renderLoading(true, LoadingReason.PREPARING, state)
            }
            PlayerState.PREPARED -> statusText.text = "Ready"
            PlayerState.STARTED -> {
                hideLoading()
                statusText.text = "Playing"
                centerPlayIcon.visibility = View.GONE
            }
            PlayerState.PAUSED -> {
                hideLoading()
                statusText.text = "Paused"
                showCenterState(isPlaying = false)
            }
            PlayerState.COMPLETED -> {
                hideLoading()
                statusText.text = "Completed"
                renderProgress(0L, player?.getDuration() ?: 0L)
            }
            PlayerState.ERROR -> {
                hideLoading()
                statusText.text = "Playback error"
            }
            else -> Unit
        }
    }

    fun renderLoading(isLoading: Boolean, reason: LoadingReason, state: PlayerState) {
        if (isLoading) {
            loadingBar.visibility = View.VISIBLE
        } else {
            hideLoading()
            when (state) {
                PlayerState.STARTED -> statusText.text = "Playing"
                PlayerState.PAUSED -> statusText.text = "Paused"
                PlayerState.PREPARED -> statusText.text = "Ready"
                PlayerState.COMPLETED -> statusText.text = "Completed"
                PlayerState.ERROR -> statusText.text = "Playback error"
                else -> Unit
            }
        }
    }

    fun renderProgress(positionMs: Long, durationMs: Long) {
        if (durationMs > 0L) {
            seekBar.progress = ((positionMs.toFloat() / durationMs) * SEEKBAR_MAX).toInt().coerceIn(0, SEEKBAR_MAX)
            totalTimeText.text = formatTime(durationMs)
        } else {
            seekBar.progress = 0
            totalTimeText.text = if (item.duration.isBlank()) "--:--" else item.duration
        }
        currentTimeText.text = formatTime(positionMs.coerceAtLeast(0L))
    }

    fun renderSeekPreview(positionMs: Long, durationMs: Long) {
        currentTimeText.text = formatTime(positionMs.coerceAtLeast(0L))
        if (durationMs > 0L) {
            totalTimeText.text = formatTime(durationMs)
        }
    }

    fun renderError(message: String) {
        hideLoading()
        statusText.text = if (message.isBlank()) "Playback error" else message
    }

    fun renderDownloadState() {
        if (!item.isDownloadable) {
            downloadButton.text = "."
            downloadButton.isEnabled = false
            return
        }
        downloadButton.isEnabled = true
        downloadButton.text = when (VideoDownloadManager.getState(item.url)) {
            DownloadState.DOWNLOADING -> "..."
            DownloadState.PENDING -> "..."
            DownloadState.COMPLETED -> "OK"
            DownloadState.FAILED -> "!"
            DownloadState.CANCELLED,
            DownloadState.NONE -> "v"
        }
    }

    fun showCenterState(isPlaying: Boolean) {
        centerPlayIcon.visibility = if (isPlaying) View.GONE else View.VISIBLE
    }

    private fun bindViews(root: View) {
        playerView = root.findViewById(R.id.playerView)
        resizeSurfaceView(DEFAULT_VIDEO_WIDTH, DEFAULT_VIDEO_HEIGHT)
        loadingBar = root.findViewById(R.id.loadingBar)
        centerPlayIcon = root.findViewById(R.id.centerPlayIcon)
        indexText = root.findViewById(R.id.indexText)
        titleText = root.findViewById(R.id.titleText)
        descText = root.findViewById(R.id.descText)
        statusText = root.findViewById(R.id.statusText)
        currentTimeText = root.findViewById(R.id.currentTimeText)
        totalTimeText = root.findViewById(R.id.totalTimeText)
        seekBar = root.findViewById(R.id.seekBar)
        downloadButton = root.findViewById(R.id.downloadButton)

        val touchLayer = root.findViewById<View>(R.id.clickLayer)
        val renderView = requireNotNull(playerView)
        val controller = PlayerGestureController(
            renderView = renderView,
            zoomMode = PlayerZoomMode.VIEW_SCALE,
            onOpenGLScaleChanged = { scale ->
                player?.setZoomScale(scale)
            },
            onOpenGLTranslationChanged = { tx, ty, width, height ->
                player?.setTranslation(tx, ty, width, height)
            },
            onTap = {
                callbacks?.onPageTap(pagePosition)
            },
        )
        gestureController = controller
        controller.attachTo(touchLayer)
        touchLayer.setOnTouchListener { view, event ->
            handleTouchLayerEvent(view, event)
            controller.onTouch(view, event)
        }
        downloadButton.setOnClickListener {
            callbacks?.onDownloadClick(pagePosition)
        }
        indexText.text = "${pagePosition + 1}/${VideoCatalog.videos.size}"
        titleText.text = item.title
        descText.text = "${item.description}  ${item.duration}"
        totalTimeText.text = item.duration
        setupSeekBar()
    }

    private fun setupSeekBar() {
        seekBar.max = SEEKBAR_MAX
        seekBar.progress = 0
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) callbacks?.onSeekPreview(pagePosition, progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                callbacks?.onSeekStart(pagePosition)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                callbacks?.onSeekStop(pagePosition, seekBar?.progress ?: 0)
            }
        })
    }

    private fun ensurePlayer() {
        if (player != null) return
        if (view == null) return

        resetForPlayback()
        val superPlayer = SuperPlayer()
        player = superPlayer
        prepared = false
        resolvedPlayPath = resolvePlayPath(item.url)
        Log.i(TAG, "ensurePlayer position=$pagePosition url=$resolvedPlayPath")

        superPlayer.setRendererType(VideoRendererType.OPENGL_ES20)
        superPlayer.setBufferSize(DEFAULT_BUFFER_MS)
        superPlayer.setMute(playbackMuted)
        superPlayer.setOnPlayerStateListener(createPlayerStateListener())
        superPlayer.setOnErrorListener(createErrorListener())
        superPlayer.setControllerListener(createControllerListener())
        playerView?.setPlayer(superPlayer)
        resetRenderTransform()

        try {
            superPlayer.setVideoUrl(resolvedPlayPath ?: item.url, autoPlay = false)
        } catch (error: Throwable) {
            Log.e(TAG, "Set video url failed: ${item.url}", error)
            renderError(error.message ?: "set video url failed")
        }
    }

    private fun startPlaybackIfReady() {
        val superPlayer = player ?: return
        if (!playbackActive) {
            Log.i(TAG, "skip start position=$pagePosition active=$playbackActive prepared=$prepared")
            return
        }
        if (!prepared && superPlayer.playerState != PlayerState.PREPARED && superPlayer.playerState != PlayerState.PAUSED) {
            Log.i(TAG, "wait prepared position=$pagePosition state=${superPlayer.playerState}")
            return
        }
        try {
            Log.i(TAG, "startPlayback position=$pagePosition muted=$playbackMuted state=${superPlayer.playerState}")
            superPlayer.setMute(playbackMuted)
            superPlayer.start()
        } catch (error: Throwable) {
            Log.e(TAG, "Start failed: ${item.url}", error)
            renderError(error.message ?: "start failed")
        }
    }

    private fun createPlayerStateListener(): OnPlayerStateListener {
        return object : OnPlayerStateListener {
            override fun onPlayerStateChanged(state: PlayerState) {
                if (view == null) return
                renderState(state)
                if (state == PlayerState.PREPARED) {
                    prepared = true
                    val duration = player?.getDuration() ?: 0L
                    renderProgress(player?.getCurrentPosition()?.coerceAtLeast(0L) ?: 0L, duration)
                    resizeSurfaceView(player?.videoWidth ?: 0, player?.videoHeight ?: 0)
                    hideLoading()
                    startPlaybackIfReady()
                } else if (state == PlayerState.COMPLETED) {
                    Log.i(TAG, "Playback completed, release player position=$pagePosition")
                    view?.post { releasePlayback() }
                }
            }

            override fun onProgressUpdate(currentPosition: Long, duration: Long) {
                if (!userSeeking && view != null) {
                    renderProgress(currentPosition, duration)
                }
            }

            override fun onBufferUpdate(bufferPercentage: Int) = Unit

            override fun onLoadingChanged(isLoading: Boolean) {
                if (view != null) {
                    renderLoading(isLoading, LoadingReason.BUFFERING, player?.playerState ?: PlayerState.IDLE)
                }
            }

            override fun onBufferedPositionUpdate(bufferedPosition: Long, duration: Long) = Unit
        }
    }

    private fun createControllerListener(): PlayerControllerListener {
        return object : PlayerControllerListener {
            override fun onLoadingChanged(isLoading: Boolean, reason: LoadingReason) {
                if (view != null) {
                    renderLoading(isLoading, reason, player?.playerState ?: PlayerState.IDLE)
                }
            }

            override fun onProgressChanged(positionMs: Long, durationMs: Long) {
                if (!userSeeking && view != null) {
                    renderProgress(positionMs, durationMs)
                }
            }

            override fun onSeekPreviewChanged(positionMs: Long, durationMs: Long) {
                if (view != null) {
                    renderSeekPreview(positionMs, durationMs)
                }
            }

            override fun onReadyToPlay(positionMs: Long, durationMs: Long, fromCache: Boolean) {
                if (view != null) {
                    renderProgress(positionMs, durationMs)
                }
            }

            override fun onVideoSizeChanged(width: Int, height: Int) {
                resizeSurfaceView(width, height)
            }

            override fun onError(errorCode: Int, message: String) {
                if (view != null) {
                    renderError(if (message.isBlank()) "Playback error: $errorCode" else message)
                }
            }
        }
    }

    private fun createErrorListener(): OnErrorListener {
        return object : OnErrorListener {
            override fun onError(errorCode: Int, errorMsg: String) {
                if (view == null) return
                renderError(if (errorMsg.isBlank()) "Playback error: $errorCode" else errorMsg)
            }
        }
    }

    private fun resizeSurfaceView(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val surface = playerView ?: return
        val aspectRatio = width.toFloat() / height.toFloat()
        val root = view
        val availableWidth = (root?.width?.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels)
            .coerceAtLeast(1)
        val availableHeight = (root?.height?.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels)
            .coerceAtLeast(1)
        var targetWidth = availableWidth
        var targetHeight = (targetWidth / aspectRatio).toInt()
        if (targetHeight > availableHeight) {
            targetHeight = availableHeight
            targetWidth = (targetHeight * aspectRatio).toInt()
        }
        val params = (surface.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        params.width = targetWidth.coerceAtLeast(1)
        params.height = targetHeight.coerceAtLeast(1)
        params.gravity = Gravity.CENTER
        surface.layoutParams = params
    }

    private fun resetRenderTransform() {
        gestureController?.reset()
        player?.resetZoom()
    }

    private fun handleTouchLayerEvent(source: View, event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestAncestorsDisallowIntercept(source, disallow = false)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                requestAncestorsDisallowIntercept(source, disallow = true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1) {
                    requestAncestorsDisallowIntercept(source, disallow = true)
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                requestAncestorsDisallowIntercept(source, disallow = false)
            }
        }
    }

    private fun requestAncestorsDisallowIntercept(source: View, disallow: Boolean) {
        var parent = source.parent
        while (parent != null) {
            parent.requestDisallowInterceptTouchEvent(disallow)
            parent = parent.parent
        }
    }


    private fun resolvePlayPath(url: String): String {
        val processedUrl = processAssetsPath(url)
        return VideoDownloadManager.getLocalPlayUrl(url)
            ?: VideoDownloadManager.getLocalPlayUrl(processedUrl)
            ?: processedUrl
    }

    private fun processAssetsPath(url: String): String {
        if (!url.startsWith(ASSET_PREFIX)) return url

        return try {
            val assetFileName = url.substring(ASSET_PREFIX.length)
            val tempDir = File(requireContext().cacheDir, "video_cache")
            if (!tempDir.exists()) tempDir.mkdirs()
            val tempFile = File(tempDir, assetFileName)
            if (tempFile.exists()) {
                return tempFile.absolutePath
            }

            requireContext().assets.open(assetFileName).use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            tempFile.absolutePath
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to process asset path: $url", error)
            url
        }
    }

    private fun hideLoading() {
        loadingBar.visibility = View.GONE
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000L
        val seconds = totalSeconds % 60L
        val minutes = (totalSeconds / 60L) % 60L
        val hours = totalSeconds / 3600L
        return if (hours > 0L) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    companion object {
        private const val TAG = "VideoPageFragment"
        private const val ARG_POSITION = "position"
        private const val ARG_TITLE = "title"
        private const val ARG_DESCRIPTION = "description"
        private const val ARG_URL = "url"
        private const val ARG_DURATION = "duration"
        private const val SEEKBAR_MAX = 100
        private const val DEFAULT_BUFFER_MS = 20_000
        private const val DEFAULT_VIDEO_WIDTH = 16
        private const val DEFAULT_VIDEO_HEIGHT = 9
        private const val ASSET_PREFIX = "file:///android_asset/"
        private const val CONTROL_PANEL_AUTO_HIDE_MS = 3_000L
        private const val CONTROL_PANEL_ANIM_MS = 180L
        private const val CONTROL_PANEL_VISIBLE_ALPHA = 0.8f

        fun newInstance(position: Int, item: VideoItem): VideoPageFragment {
            return VideoPageFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_POSITION, position)
                    putString(ARG_TITLE, item.title)
                    putString(ARG_DESCRIPTION, item.description)
                    putString(ARG_URL, item.url)
                    putString(ARG_DURATION, item.duration)
                }
            }
        }
    }
}
