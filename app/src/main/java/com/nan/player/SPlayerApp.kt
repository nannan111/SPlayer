package com.nan.player

import android.app.Application
import android.util.Log
import com.nannan.superplayer.player.NativePlayer
import com.nannan.superplayer.player.PlayerManager
import com.nannan.superplayer.player.VideoDownloadManager

class SPlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        initSdk()
    }

    private fun initSdk() {
        try {
            NativePlayer.nativeInitializeFFmpeg()
        } catch (error: Throwable) {
            Log.w(TAG, "FFmpeg init failed", error)
        }

        try {
            PlayerManager.nativeInitNetworkLayer(cacheDir.absolutePath)
            PlayerManager.setMaxPlayerPoolSize(FEED_PLAYER_POOL_SIZE)
        } catch (error: Throwable) {
            Log.w(TAG, "Network layer init failed", error)
        }

        try {
            VideoDownloadManager.init(this)
        } catch (error: Throwable) {
            Log.w(TAG, "Download manager init failed", error)
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        try {
            PlayerManager.release()
            PlayerManager.nativeCleanupNetworkLayer()
            NativePlayer.nativeCleanupFFmpeg()
        } catch (error: Throwable) {
            Log.w(TAG, "SDK cleanup failed", error)
        }
    }

    companion object {
        private const val TAG = "SPlayerApp"
        private const val FEED_PLAYER_POOL_SIZE = 9
    }
}
