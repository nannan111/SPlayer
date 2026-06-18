package com.nan.player

import android.util.Log
import com.nannan.superplayer.player.PlayerManager
import com.nannan.superplayer.player.VideoDownloadManager
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

object PreloadCoordinator {
    private const val TAG = "PreloadCoordinator"
    private const val DISK_PRELOAD_SECONDS = 8
    private const val MEMORY_PRELOAD_SECONDS = 4
    private const val PRELOAD_BITRATE = 800L * 1024L
    private const val MAX_CONCURRENT_PRELOADS = 1

    private val diskTasks = Collections.synchronizedSet(mutableSetOf<String>())
    private val memoryTasks = Collections.synchronizedSet(mutableSetOf<String>())
    @Volatile private var executor: ExecutorService? = null

    fun preloadVideoListToDisk(videos: List<VideoItem>): Int {
        val urls = videos
            .asSequence()
            .map { it.url }
            .filter(::isRemote)
            .distinct()
            .toList()

        urls.forEach(::preloadToDisk)
        return urls.size
    }

    fun preloadAround(videos: List<VideoItem>, index: Int) {
        preloadWindow(videos, index, radius = 1)
    }

    fun preloadWindow(videos: List<VideoItem>, index: Int, radius: Int) {
        if (videos.isEmpty() || index !in videos.indices || radius <= 0) return

        val first = (index - radius).coerceAtLeast(0)
        val last = (index + radius).coerceAtMost(videos.lastIndex)
        val candidateIndexes = (first..last)
            .filter { it != index }
            .sortedWith(compareBy<Int> { kotlin.math.abs(it - index) }.thenBy { it })

        candidateIndexes
            .filter { it in videos.indices }
            .map { videos[it] }
            .filter { it.isHttp }
            .forEach { item ->
                preloadToMemory(item.url)
                preloadToDisk(item.url)
            }
    }

    fun preloadToDisk(url: String) {
        if (!isRemote(url)) return
        if (VideoDownloadManager.isDownloaded(url)) {
            Log.i(TAG, "Skip disk preload, offline download exists: $url")
            return
        }
        if (!diskTasks.add(url)) return

        execute {
            try {
                Log.i(TAG, "Disk preload start: $url")
                PlayerManager.preloadToDisk(url, DISK_PRELOAD_SECONDS, PRELOAD_BITRATE)
                Log.i(TAG, "Disk preload submitted/done: $url")
            } catch (error: Throwable) {
                Log.w(TAG, "Disk preload failed: $url", error)
            } finally {
                diskTasks.remove(url)
            }
        }
    }

    fun preloadToMemory(url: String) {
        if (!isRemote(url)) return
        if (!memoryTasks.add(url)) return

        execute {
            try {
                Log.i(TAG, "Memory preload start: $url")
                PlayerManager.preloadVideo(url, MEMORY_PRELOAD_SECONDS, PRELOAD_BITRATE)
            } catch (error: Throwable) {
                Log.w(TAG, "Memory preload failed: $url", error)
            } finally {
                memoryTasks.remove(url)
            }
        }
    }

    fun shutdown() {
        executor?.shutdownNow()
        executor = null
        diskTasks.clear()
        memoryTasks.clear()
    }

    @Synchronized
    private fun ensureExecutor(): ExecutorService {
        val current = executor
        if (current != null && !current.isShutdown && !current.isTerminated) {
            return current
        }

        return Executors.newFixedThreadPool(MAX_CONCURRENT_PRELOADS).also {
            executor = it
        }
    }

    private fun execute(task: () -> Unit) {
        try {
            ensureExecutor().execute(task)
        } catch (error: RejectedExecutionException) {
            Log.w(TAG, "Preload task rejected", error)
        }
    }

    private fun isRemote(url: String): Boolean {
        return url.startsWith("http://") || url.startsWith("https://")
    }
}
