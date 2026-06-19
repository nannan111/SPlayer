package com.nan.player

import android.annotation.SuppressLint
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
    private const val MAX_CONCURRENT_DISK_PRELOADS = 2
    private const val MAX_CONCURRENT_MEMORY_PRELOADS = 2

    private val diskTasks = Collections.synchronizedSet(mutableSetOf<String>())
    private val memoryTasks = Collections.synchronizedSet(mutableSetOf<String>())
    @Volatile private var diskExecutor: ExecutorService? = null
    @Volatile private var memoryExecutor: ExecutorService? = null

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

    fun isPreloaded(item: VideoItem): Boolean {
        return isPreloaded(item.url)
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

    @SuppressLint("SuspiciousIndentation")
    fun preloadWindow(videos: List<VideoItem>, index: Int, radius: Int) {
        if (videos.isEmpty() || index !in videos.indices || radius <= 0) return
            videos.forEach { item->
            if(item.isHttp){
                Log.i("SPlayerPreload","start:${item.url}")
                preloadToDisk(item.url)
            }
        }
    }

    fun preloadToDisk(url: String) {
        if (!isRemote(url)) return
        if (VideoDownloadManager.isDownloaded(url)) {
            Log.i(TAG, "Skip disk preload, offline download exists: $url")
            return
        }
        if (!diskTasks.add(url)) return

        executeDisk {
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

        executeMemory {
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
        diskExecutor?.shutdownNow()
        memoryExecutor?.shutdownNow()
        diskExecutor = null
        memoryExecutor = null
        diskTasks.clear()
        memoryTasks.clear()
    }

    @Synchronized
    private fun ensureDiskExecutor(): ExecutorService {
        val current = diskExecutor
        if (current != null && !current.isShutdown && !current.isTerminated) {
            return current
        }

        return Executors.newFixedThreadPool(MAX_CONCURRENT_DISK_PRELOADS).also {
            diskExecutor = it
        }
    }

    @Synchronized
    private fun ensureMemoryExecutor(): ExecutorService {
        val current = memoryExecutor
        if (current != null && !current.isShutdown && !current.isTerminated) {
            return current
        }

        return Executors.newFixedThreadPool(MAX_CONCURRENT_MEMORY_PRELOADS).also {
            memoryExecutor = it
        }
    }

    private fun executeDisk(task: () -> Unit) {
        try {
            ensureDiskExecutor().execute(task)
        } catch (error: RejectedExecutionException) {
            Log.w(TAG, "Disk preload task rejected", error)
        }
    }

    private fun executeMemory(task: () -> Unit) {
        try {
            ensureMemoryExecutor().execute(task)
        } catch (error: RejectedExecutionException) {
            Log.w(TAG, "Memory preload task rejected", error)
        }
    }

    private fun isRemote(url: String): Boolean {
        return url.startsWith("http://") || url.startsWith("https://")
    }
}
