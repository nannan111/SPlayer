package com.nan.player

object PreloadCoordinator {
    fun scheduleStartup(videos: List<VideoItem>, startIndex: Int) {
        PreloadScheduler.scheduleStartup(videos, startIndex)
    }

    fun scheduleForPlayback(videos: List<VideoItem>, index: Int) {
        PreloadScheduler.scheduleForPlayback(videos, index)
    }

    fun preloadVideoListToDisk(videos: List<VideoItem>): Int {
        return PreloadScheduler.scheduleListToDisk(videos)
    }

    fun preloadAround(videos: List<VideoItem>, index: Int) {
        PreloadScheduler.scheduleForPlayback(videos, index)
    }

    fun isPreloaded(item: VideoItem): Boolean {
        return isPreloaded(item.url)
    }

    fun isPreloaded(url: String): Boolean {
        return PreloadScheduler.isPreloaded(url)
    }

    fun preloadWindow(videos: List<VideoItem>, index: Int, radius: Int) {
        PreloadScheduler.scheduleWindow(videos, index, radius)
    }

    fun preloadToDisk(url: String) {
        PreloadScheduler.preloadToDisk(url)
    }

    fun preloadToMemory(url: String) {
        PreloadScheduler.preloadToMemory(url)
    }

    fun shutdown() {
        PreloadScheduler.shutdown()
    }
}
