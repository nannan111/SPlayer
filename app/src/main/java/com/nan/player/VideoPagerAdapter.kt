package com.nan.player

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.nannan.superplayer.player.DownloadProgress
import com.nannan.superplayer.player.DownloadState
import com.nannan.superplayer.player.VideoDownloadManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class VideoPagerAdapter(
    private val context: Context,
    private val videos: List<VideoItem>,
    private val scope: CoroutineScope,
    private val callbacks: Callbacks
) : RecyclerView.Adapter<VideoPagerAdapter.VideoHolder>() {

    interface Callbacks {
        fun onHolderBound(position: Int)
        fun onPageTap(position: Int)
        fun onDownloadClick(position: Int)
        fun onSeekStart(position: Int)
        fun onSeekPreview(position: Int, progress: Int)
        fun onSeekStop(position: Int, progress: Int)
    }

    private val attachedHolders = mutableMapOf<Int, VideoHolder>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoHolder {
        return VideoHolder(context, scope, callbacks)
    }

    override fun onBindViewHolder(holder: VideoHolder, position: Int) {
        holder.bind(videos[position], position)
        attachedHolders[position] = holder
        callbacks.onHolderBound(position)
    }

    override fun onViewAttachedToWindow(holder: VideoHolder) {
        super.onViewAttachedToWindow(holder)
        val position = holder.adapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: holder.boundPosition
        if (position != RecyclerView.NO_POSITION) {
            attachedHolders[position] = holder
        }
    }

    override fun onViewDetachedFromWindow(holder: VideoHolder) {
        val position = holder.adapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: holder.boundPosition
        if (position != RecyclerView.NO_POSITION) {
            attachedHolders.remove(position)
        }
        super.onViewDetachedFromWindow(holder)
    }

    override fun onViewRecycled(holder: VideoHolder) {
        holder.unbind()
        attachedHolders.values.remove(holder)
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = videos.size

    fun holderAt(position: Int): VideoHolder? = attachedHolders[position]

    fun forEachAttached(block: (Int, VideoHolder) -> Unit) {
        attachedHolders.toMap().forEach { (position, holder) -> block(position, holder) }
    }

    fun release() {
        attachedHolders.values.forEach { it.unbind() }
        attachedHolders.clear()
    }

    class VideoHolder(
        context: Context,
        private val scope: CoroutineScope,
        private val callbacks: Callbacks
    ) : RecyclerView.ViewHolder(FrameLayout(context)) {
        val root: FrameLayout = itemView as FrameLayout
        private val playerContainer = FrameLayout(context)
        private val touchLayer = View(context)
        private val titleText = TextView(context)
        private val descText = TextView(context)
        private val loadingBar = ProgressBar(context)
        private val loadingText = TextView(context)
        private val downloadButton = Button(context)
        private val controls = LinearLayout(context)
        private val currentTimeText = TextView(context)
        private val totalTimeText = TextView(context)
        private val seekBar = SeekBar(context)

        private var downloadJob: Job? = null
        private var item: VideoItem? = null
        private var position: Int = RecyclerView.NO_POSITION
        val boundPosition: Int
            get() = position

        init {
            root.setBackgroundColor(Color.BLACK)
            root.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            root.addView(
                playerContainer,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
            )

            root.addView(
                touchLayer,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            touchLayer.setOnClickListener {
                currentPositionOrNull()?.let(callbacks::onPageTap)
            }

            titleText.setTextColor(Color.WHITE)
            titleText.textSize = 20f
            titleText.maxLines = 2
            descText.setTextColor(Color.argb(210, 255, 255, 255))
            descText.textSize = 14f
            descText.maxLines = 2

            val infoColumn = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(titleText, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(descText, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            root.addView(
                infoColumn,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.START
                ).apply {
                    leftMargin = dp(18)
                    rightMargin = dp(92)
                    bottomMargin = dp(92)
                }
            )

            downloadButton.text = "v"
            downloadButton.textSize = 18f
            downloadButton.setTextColor(Color.WHITE)
            downloadButton.setAllCaps(false)
            downloadButton.setBackgroundColor(Color.argb(110, 0, 0, 0))
            downloadButton.setOnClickListener {
                currentPositionOrNull()?.let(callbacks::onDownloadClick)
            }
            root.addView(
                downloadButton,
                FrameLayout.LayoutParams(dp(56), dp(56), Gravity.END or Gravity.CENTER_VERTICAL).apply {
                    rightMargin = dp(18)
                }
            )

            loadingText.text = "Loading"
            loadingText.setTextColor(Color.WHITE)
            loadingText.textSize = 14f
            loadingText.gravity = Gravity.CENTER
            val loadingColumn = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(loadingBar, LinearLayout.LayoutParams(dp(42), dp(42)))
                addView(loadingText, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            root.addView(
                loadingColumn,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )

            controls.orientation = LinearLayout.HORIZONTAL
            controls.gravity = Gravity.CENTER_VERTICAL
            controls.setPadding(dp(12), dp(8), dp(12), dp(8))
            controls.setBackgroundColor(Color.argb(150, 0, 0, 0))
            controls.visibility = View.GONE

            currentTimeText.setTextColor(Color.WHITE)
            currentTimeText.textSize = 12f
            totalTimeText.setTextColor(Color.WHITE)
            totalTimeText.textSize = 12f
            seekBar.max = 1000
            seekBar.progress = 0
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) currentPositionOrNull()?.let { callbacks.onSeekPreview(it, progress) }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    currentPositionOrNull()?.let(callbacks::onSeekStart)
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    currentPositionOrNull()?.let { callbacks.onSeekStop(it, seekBar?.progress ?: 0) }
                }
            })

            controls.addView(currentTimeText, LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT))
            controls.addView(seekBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            controls.addView(totalTimeText, LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT))

            root.addView(
                controls,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM
                ).apply {
                    leftMargin = dp(10)
                    rightMargin = dp(10)
                    bottomMargin = dp(18)
                }
            )

        }

        fun bind(item: VideoItem, position: Int) {
            this.item = item
            this.position = position
            titleText.text = item.title
            descText.text = "${item.description}  ${item.duration}"
            currentTimeText.text = "00:00"
            totalTimeText.text = item.duration
            seekBar.progress = 0
            setControlsVisible(false)
            showLoading(false)
            bindDownloadState(item)
        }

        fun unbind() {
            downloadJob?.cancel()
            downloadJob = null
            playerContainer.removeAllViews()
            item = null
            position = RecyclerView.NO_POSITION
            setControlsVisible(false)
            showLoading(false)
        }

        fun setActive(active: Boolean) {
            if (!active) {
                setControlsVisible(false)
                showLoading(false)
            }
        }

        fun mountPlayerView(playerView: View) {
            val currentParent = playerView.parent as? ViewGroup
            if (currentParent !== playerContainer) {
                currentParent?.removeView(playerView)
                playerContainer.removeAllViews()
                playerContainer.addView(
                    playerView,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER
                    )
                )
            }
        }

        fun setControlsVisible(visible: Boolean) {
            controls.visibility = if (visible) View.VISIBLE else View.GONE
        }

        fun showLoading(show: Boolean, message: String = "Loading") {
            loadingBar.visibility = if (show) View.VISIBLE else View.GONE
            loadingText.visibility = if (show) View.VISIBLE else View.GONE
            loadingText.text = message
        }

        fun showError(message: String) {
            showLoading(true, message)
        }

        fun updateProgress(positionMs: Long, durationMs: Long) {
            val duration = durationMs.coerceAtLeast(0)
            if (duration > 0) {
                seekBar.progress = ((positionMs.coerceAtLeast(0) * 1000L) / duration).toInt().coerceIn(0, 1000)
                totalTimeText.text = formatTime(duration)
            }
            currentTimeText.text = formatTime(positionMs.coerceAtLeast(0))
        }

        fun updateSeekPreview(positionMs: Long, durationMs: Long) {
            currentTimeText.text = formatTime(positionMs.coerceAtLeast(0))
            if (durationMs > 0) totalTimeText.text = formatTime(durationMs)
        }

        private fun bindDownloadState(item: VideoItem) {
            downloadJob?.cancel()
            if (!item.isDownloadable) {
                downloadButton.text = "."
                downloadButton.isEnabled = false
                return
            }

            downloadButton.isEnabled = true
            downloadJob = scope.launch {
                VideoDownloadManager.progressFlow(item.url).collectLatest { progress ->
                    renderDownload(progress)
                }
            }
            renderDownload(
                DownloadProgress(
                    url = item.url,
                    state = VideoDownloadManager.getState(item.url),
                    progress = if (VideoDownloadManager.isDownloaded(item.url)) 1f else 0f,
                    localPath = VideoDownloadManager.getLocalPath(item.url)
                )
            )
        }

        private fun renderDownload(progress: DownloadProgress) {
            downloadButton.text = when (progress.state) {
                DownloadState.PENDING -> "..."
                DownloadState.DOWNLOADING -> "${(progress.progress * 100).toInt().coerceIn(0, 100)}%"
                DownloadState.COMPLETED -> "OK"
                DownloadState.FAILED -> "!"
                DownloadState.CANCELLED,
                DownloadState.NONE -> "v"
            }
        }

        private fun currentPositionOrNull(): Int? {
            val current = adapterPosition
            return if (current == RecyclerView.NO_POSITION) position.takeIf { it != RecyclerView.NO_POSITION } else current
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

        private fun dp(value: Int): Int {
            return (value * root.resources.displayMetrics.density + 0.5f).toInt()
        }
    }
}
