package com.nan.player

import android.util.Log
import com.nannan.superplayer.player.PlayerManager
import com.nannan.superplayer.player.VideoDownloadManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

object PreloadScheduler {
    private const val TAG = "PreloadScheduler"
    private const val DISK_PRELOAD_SECONDS = 8
    private const val MEMORY_PRELOAD_SECONDS = 4
    private const val PRELOAD_BITRATE = 800L * 1024L
    private const val STARTUP_DISK_TARGETS = 3
    private const val PLAYBACK_DISK_TARGETS = 3
    private const val MAX_REMEMBERED_URLS = 80

    private val sequence = AtomicLong(0L)
    private val lock = Any()
    private val diskQueue = PriorityQueue<DiskRequest>(
        compareBy<DiskRequest> { it.priority }.thenBy { it.sequence }
    )
    private val queuedDiskUrls = mutableSetOf<String>()
    private val runningDiskUrls = mutableSetOf<String>()
    private val completedDiskUrls = linkedSetOf<String>()
    private val requestedMemoryUrls = linkedSetOf<String>()

    private var schedulerJob: Job = SupervisorJob()
    private var schedulerScope: CoroutineScope = CoroutineScope(schedulerJob + Dispatchers.IO)
    private var diskWorkerJob: Job? = null
    private var lastCenterIndex = -1

    fun scheduleStartup(videos: List<VideoItem>, startIndex: Int) {
        if (videos.isEmpty()) return
        val centerIndex = startIndex.coerceIn(videos.indices)
        lastCenterIndex = centerIndex

        Log.i(TAG, "scheduleStartup center=$centerIndex size=${videos.size}")
        enqueueDiskTargets(
            targets = collectForwardTargets(
                videos = videos,
                startIndex = centerIndex,
                includeStart = true,
                maxCount = STARTUP_DISK_TARGETS
            ),
            basePriority = 100,
            replaceQueuedWindow = true,
            reason = "startup"
        )
        enqueueMemoryNear(videos, centerIndex, direction = 1)
    }

    fun scheduleForPlayback(videos: List<VideoItem>, centerIndex: Int) {
        if (videos.isEmpty() || centerIndex !in videos.indices) return

        val previousIndex = lastCenterIndex
        val direction = when {
            previousIndex < 0 -> 1
            centerIndex > previousIndex -> 1
            centerIndex < previousIndex -> -1
            else -> 1
        }
        lastCenterIndex = centerIndex

        Log.i(TAG, "scheduleForPlayback center=$centerIndex previous=$previousIndex direction=$direction")
        enqueueMemoryNear(videos, centerIndex, direction)
        enqueueDiskTargets(
            targets = collectDirectionalTargets(
                videos = videos,
                centerIndex = centerIndex,
                direction = direction,
                maxCount = PLAYBACK_DISK_TARGETS
            ),
            basePriority = 10,
            replaceQueuedWindow = true,
            reason = "playback"
        )
    }

    fun scheduleWindow(videos: List<VideoItem>, centerIndex: Int, radius: Int): Int {
        if (videos.isEmpty() || centerIndex !in videos.indices || radius <= 0) return 0

        val start = (centerIndex - radius).coerceAtLeast(0)
        val end = (centerIndex + radius).coerceAtMost(videos.lastIndex)
        val targets = (start..end)
            .filter { it != centerIndex }
            .sortedWith(compareBy<Int> { abs(it - centerIndex) }.thenBy { it })
            .mapNotNull { index ->
                videos[index].takeIf { it.isHttp }?.let {
                    DiskTarget(index = index, url = it.url, distance = abs(index - centerIndex))
                }
            }

        enqueueDiskTargets(
            targets = targets,
            basePriority = 50,
            replaceQueuedWindow = true,
            reason = "window"
        )
        return targets.size
    }

    fun scheduleListToDisk(videos: List<VideoItem>): Int {
        val targets = videos
            .mapIndexedNotNull { index, item ->
                item.takeIf { it.isHttp }?.let {
                    DiskTarget(index = index, url = it.url, distance = index)
                }
            }
            .distinctBy { it.url }

        enqueueDiskTargets(
            targets = targets,
            basePriority = 1000,
            replaceQueuedWindow = false,
            reason = "list"
        )
        return targets.size
    }

    fun preloadToDisk(url: String) {
        if (!isRemote(url)) return
        enqueueDiskTargets(
            targets = listOf(DiskTarget(index = -1, url = url, distance = 0)),
            basePriority = 0,
            replaceQueuedWindow = false,
            reason = "manual",
            prunable = false
        )
    }

    fun preloadToMemory(url: String) {
        if (!isRemote(url)) return
        if (VideoDownloadManager.isDownloaded(url)) return

        synchronized(lock) {
            if (!requestedMemoryUrls.add(url)) return
            trimRememberedUrlsLocked(requestedMemoryUrls)
        }

        ensureScope().launch {
            try {
                Log.i(TAG, "Memory preload start: $url")
                PlayerManager.preloadVideo(url, MEMORY_PRELOAD_SECONDS, PRELOAD_BITRATE)
            } catch (error: Throwable) {
                Log.w(TAG, "Memory preload failed: $url", error)
            }
        }
    }

    fun isPreloaded(url: String): Boolean {
        if (!isRemote(url)) return true
        if (VideoDownloadManager.isDownloaded(url)) return true
        return try {
            PlayerManager.isPreload(url)
        } catch (error: Throwable) {
            Log.w(TAG, "Check preload failed: $url", error)
            false
        }
    }

    fun shutdown() {
        synchronized(lock) {
            diskQueue.clear()
            queuedDiskUrls.clear()
            requestedMemoryUrls.clear()
            lastCenterIndex = -1
        }
    }

    private fun enqueueMemoryNear(videos: List<VideoItem>, centerIndex: Int, direction: Int) {
        val first = centerIndex + direction
        val second = centerIndex + direction * 2
        listOf(first, second)
            .filter { it in videos.indices }
            .map { videos[it] }
            .filter { it.isHttp }
            .forEach { preloadToMemory(it.url) }
    }

    private fun enqueueDiskTargets(
        targets: List<DiskTarget>,
        basePriority: Int,
        replaceQueuedWindow: Boolean,
        reason: String,
        prunable: Boolean = true
    ) {
        if (targets.isEmpty()) {
            Log.i(TAG, "Disk preload no targets reason=$reason")
            return
        }
        Log.i(TAG, "Disk preload enqueue request reason=$reason targets=${targets.size}")
        synchronized(lock) {
            if (replaceQueuedWindow) {
                pruneQueuedWindowLocked(targets.map { it.url }.toSet())
            }

            targets.forEachIndexed { order, target ->
                val url = target.url
                if (!isRemote(url)) {
                    Log.i(TAG, "Disk preload skip non-remote index=${target.index} url=$url")
                    return@forEachIndexed
                }
                if (queuedDiskUrls.contains(url)) {
                    Log.i(TAG, "Disk preload skip queued index=${target.index} url=$url")
                    return@forEachIndexed
                }
                if (runningDiskUrls.contains(url)) {
                    Log.i(TAG, "Disk preload skip running index=${target.index} url=$url")
                    return@forEachIndexed
                }
                if (completedDiskUrls.contains(url)) {
                    Log.i(TAG, "Disk preload skip completed index=${target.index} url=$url")
                    return@forEachIndexed
                }
                if (VideoDownloadManager.isDownloaded(url)) {
                    Log.i(TAG, "Disk preload skip offline-downloaded index=${target.index} url=$url")
                    return@forEachIndexed
                }

                val request = DiskRequest(
                    url = url,
                    index = target.index,
                    priority = basePriority + target.distance * 10 + order,
                    sequence = sequence.incrementAndGet(),
                    reason = reason,
                    prunable = prunable
                )
                diskQueue.offer(request)
                queuedDiskUrls.add(url)
                Log.i(TAG, "Disk preload queued reason=$reason index=${target.index} url=$url")
            }

            startDiskWorkerLocked()
        }
    }

    private fun startDiskWorkerLocked() {
        if (diskWorkerJob?.isActive == true) return

        diskWorkerJob = ensureScopeLocked().launch {
            while (true) {
                val request = synchronized(lock) {
                    val next = pollDiskRequestLocked()
                    if (next == null) {
                        diskWorkerJob = null
                        return@launch
                    }
                    runningDiskUrls.add(next.url)
                    next
                }

                val success = runDiskPreload(request)

                synchronized(lock) {
                    runningDiskUrls.remove(request.url)
                    if (success) {
                        completedDiskUrls.add(request.url)
                        trimRememberedUrlsLocked(completedDiskUrls)
                    }
                }
            }
        }
    }

    private fun pollDiskRequestLocked(): DiskRequest? {
        while (diskQueue.isNotEmpty()) {
            val request = diskQueue.poll() ?: return null
            queuedDiskUrls.remove(request.url)
            if (completedDiskUrls.contains(request.url)) continue
            if (VideoDownloadManager.isDownloaded(request.url)) {
                completedDiskUrls.add(request.url)
                trimRememberedUrlsLocked(completedDiskUrls)
                continue
            }
            return request
        }
        return null
    }

    private fun runDiskPreload(request: DiskRequest): Boolean {
        if (VideoDownloadManager.isDownloaded(request.url)) return true

        return try {
            Log.i(
                TAG,
                "Disk preload start reason=${request.reason} index=${request.index} url=${request.url}"
            )
            val result = PlayerManager.preloadToDisk(
                request.url,
                DISK_PRELOAD_SECONDS,
                PRELOAD_BITRATE
            )
            val success = result == 0
            Log.i(TAG, "Disk preload finish success=$success result=$result url=${request.url}")
            success
        } catch (error: Throwable) {
            Log.w(TAG, "Disk preload failed: ${request.url}", error)
            false
        }
    }

    private fun pruneQueuedWindowLocked(keepUrls: Set<String>) {
        if (diskQueue.isEmpty()) return

        val iterator = diskQueue.iterator()
        while (iterator.hasNext()) {
            val request = iterator.next()
            if (request.prunable && request.url !in keepUrls) {
                iterator.remove()
                queuedDiskUrls.remove(request.url)
                Log.i(TAG, "Disk preload dropped queued url=${request.url}")
            }
        }
    }

    private fun collectForwardTargets(
        videos: List<VideoItem>,
        startIndex: Int,
        includeStart: Boolean,
        maxCount: Int
    ): List<DiskTarget> {
        val targets = mutableListOf<DiskTarget>()
        var index = if (includeStart) startIndex else startIndex + 1
        while (index in videos.indices && targets.size < maxCount) {
            val item = videos[index]
            if (item.isHttp) {
                targets += DiskTarget(
                    index = index,
                    url = item.url,
                    distance = abs(index - startIndex)
                )
            }
            index++
        }
        return targets
    }

    private fun collectDirectionalTargets(
        videos: List<VideoItem>,
        centerIndex: Int,
        direction: Int,
        maxCount: Int
    ): List<DiskTarget> {
        val targets = mutableListOf<DiskTarget>()
        var index = centerIndex + direction
        while (index in videos.indices && targets.size < maxCount) {
            val item = videos[index]
            if (item.isHttp) {
                targets += DiskTarget(
                    index = index,
                    url = item.url,
                    distance = abs(index - centerIndex)
                )
            }
            index += direction
        }
        return targets
    }

    private fun ensureScope(): CoroutineScope {
        synchronized(lock) {
            return ensureScopeLocked()
        }
    }

    private fun ensureScopeLocked(): CoroutineScope {
        if (!schedulerJob.isActive) {
            schedulerJob = SupervisorJob()
            schedulerScope = CoroutineScope(schedulerJob + Dispatchers.IO)
        }
        return schedulerScope
    }

    private fun trimRememberedUrlsLocked(urls: LinkedHashSet<String>) {
        while (urls.size > MAX_REMEMBERED_URLS) {
            val iterator = urls.iterator()
            if (!iterator.hasNext()) return
            iterator.next()
            iterator.remove()
        }
    }

    private fun isRemote(url: String): Boolean {
        return url.startsWith("http://") || url.startsWith("https://")
    }

    private data class DiskTarget(
        val index: Int,
        val url: String,
        val distance: Int
    )

    private data class DiskRequest(
        val url: String,
        val index: Int,
        val priority: Int,
        val sequence: Long,
        val reason: String,
        val prunable: Boolean
    )
}
