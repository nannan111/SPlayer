package com.nan.player

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.gyf.immersionbar.BarHide
import com.gyf.immersionbar.ImmersionBar

class WelcomeActivity : AppCompatActivity() {
    private val uiHandler = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView
    private var launched = false

    private val openPlayerRunnable = Runnable {
        openPlayer()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setupImmersiveBars()
        setContentView(createContentView())
        startDiskWarmup()
        uiHandler.postDelayed(openPlayerRunnable, LAUNCH_DELAY_MS)
    }

    override fun onResume() {
        super.onResume()
        setupImmersiveBars()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(openPlayerRunnable)
        super.onDestroy()
    }

    private fun createContentView(): FrameLayout {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(10, 10, 12))
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        root.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )

        val title = TextView(this).apply {
            text = "SPlayer"
            setTextColor(Color.WHITE)
            textSize = 36f
            gravity = Gravity.CENTER
        }
        content.addView(title, matchWrap())

        val subtitle = TextView(this).apply {
            text = "Preparing video feed"
            setTextColor(Color.argb(185, 255, 255, 255))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(20))
        }
        content.addView(subtitle, matchWrap())

        statusText = TextView(this).apply {
            text = "Starting cache warmup..."
            setTextColor(Color.argb(220, 255, 255, 255))
            textSize = 14f
            gravity = Gravity.CENTER
        }
        content.addView(statusText, matchWrap())

        return root
    }

    private fun startDiskWarmup() {
        val count = PreloadCoordinator.preloadVideoListToDisk(VideoCatalog.videos)
        statusText.text = if (count > 0) {
            "Warming disk cache for $count videos..."
        } else {
            "Loading local videos..."
        }
    }

    private fun openPlayer() {
        if (launched) return
        launched = true
        startActivity(Intent(this, PlayerFeedActivity::class.java))
        finish()
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

    private fun matchWrap(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    companion object {
        private const val LAUNCH_DELAY_MS = 2_000L
    }
}
