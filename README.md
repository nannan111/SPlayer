# SPlayer

SPlayer 是 SuperPlayer SDK 的 Android 示例工程，包名为 `com.nan.player`。它主要演示短视频列表场景下，如何用 SDK 完成启动预热、上下滑切换、播放控制、磁盘预加载、内存预加载、离线下载和手势缩放。

这个工程只接入 SDK 产物，不依赖播放器 C++ 源码。当前 SDK 以 `app/libs/superPlayer-release.aar` 的形式引入。

## 工程定位

| 模块 | 作用 |
| --- | --- |
| `SPlayerApp` | 初始化 FFmpeg、网络层、播放器池、预加载回调、下载管理器 |
| `WelcomeActivity` | 启动页，进入播放页前先触发首屏缓存预热 |
| `PlayerFeedActivity` | 竖滑视频流页面，基于 `ViewPager2` 管理当前页和相邻页 |
| `VideoPageFragment` | 单个视频页，负责 `SuperPlayerView`、播放生命周期、进度、下载、缩放 |
| `PreloadCoordinator` | 应用层预加载入口，隔离业务和调度细节 |
| `PreloadScheduler` | 预加载策略实现，控制磁盘/内存任务优先级和去重 |
| `VideoCatalog` | 示例播放列表，包含本地 asset、MP4、HLS/M3U8 |

## 运行方式

```bash
./gradlew assembleDebug
./gradlew installDebug
```

主要配置：

| 项 | 当前值 |
| --- | --- |
| `applicationId` | `com.nan.player` |
| `compileSdk` | 35 |
| `targetSdk` | 36 |
| `minSdk` | 24 |
| `ndkVersion` | `27.3.13750724` |
| ABI | `arm64-v8a`、`armeabi-v7a` |
| SDK 依赖 | `implementation(files("libs/superPlayer-release.aar"))` |

Manifest 需要保留：

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

## 启动流程

```text
SPlayerApp.onCreate()
  -> NativePlayer.nativeInitializeFFmpeg()
  -> PlayerManager.nativeInitNetworkLayer(cacheDir)
  -> PlayerManager.setMaxPlayerPoolSize(3)
  -> PlayerManager.setPreloadCompletionCallback(...)
  -> VideoDownloadManager.init(this)

WelcomeActivity.onCreate()
  -> PreloadCoordinator.scheduleStartup(VideoCatalog.videos, 0)
  -> 延迟 2 秒进入 PlayerFeedActivity

PlayerFeedActivity
  -> ViewPager2 上下滑动
  -> 当前页和相邻页保持可播放窗口
  -> 页面变化时调用 PreloadCoordinator.scheduleForPlayback(...)

VideoPageFragment
  -> 创建 SuperPlayer
  -> 绑定 SuperPlayerView
  -> setVideoUrl(url, autoPlay = false)
  -> 准备完成后按页面激活状态 start/pause/release
```

## 当前预加载策略

SPlayer 的目标是让当前播放优先，同时提前给后续视频准备可用缓存。

| 参数 | 当前值 | 说明 |
| --- | --- | --- |
| `DISK_PRELOAD_SECONDS` | 8 秒 | 每个磁盘预加载任务的目标时长 |
| `MEMORY_PRELOAD_SECONDS` | 4 秒 | 内存预加载目标时长 |
| `PRELOAD_BITRATE` | `800 * 1024` bytes/s | 预估码率，用于计算预加载数据量 |
| `STARTUP_DISK_TARGETS` | 3 个 | 启动页优先预加载前 3 个远程视频到磁盘 |
| `PLAYBACK_DISK_TARGETS` | 3 个 | 播放中按滑动方向预加载后续 3 个远程视频 |
| `FEED_PLAYER_POOL_SIZE` | 3 | 全局播放器池容量 |
| `PAGE_OFFSCREEN_LIMIT` | 1 | ViewPager2 保留相邻页面 |
| `ACTIVE_SURFACE_RADIUS` | 1 | 当前页前后各 1 页允许创建播放器 |
| `DEFAULT_BUFFER_MS` | 20000 ms | `VideoPageFragment` 内 `SuperPlayer` 默认缓冲 |

调度规则：

- 启动页只预热首屏附近的视频，不一次性把列表全部打满。
- 磁盘预加载使用队列和优先级，避免多个大下载同时抢播放网络。
- 内存预加载只处理当前滑动方向附近的少量视频。
- 非远程地址、已下载、已排队、正在预加载、已完成的 URL 会跳过。
- 当前播放始终优先于后台预加载，播放页只在视频已预加载或当前页需要播放时启动。

## SDK 接入

### 1. 引入 AAR

```kotlin
dependencies {
    implementation(files("libs/superPlayer-release.aar"))
}
```

### 2. 初始化

建议在 `Application.onCreate()` 中初始化：

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()

        NativePlayer.nativeInitializeFFmpeg()
        PlayerManager.nativeInitNetworkLayer(cacheDir.absolutePath)
        PlayerManager.setMaxPlayerPoolSize(3)
        VideoDownloadManager.init(this)
    }
}
```

如果需要监听预加载完成：

```kotlin
PlayerManager.setPreloadCompletionCallback(object : PreloadCompletionCallback {
    override fun onPreloadComplete(url: String, success: Boolean, preloadType: Int) {
        val type = when (preloadType) {
            PlayerManager.PRELOAD_TYPE_MEMORY -> "MEMORY"
            PlayerManager.PRELOAD_TYPE_DISK -> "DISK"
            else -> "UNKNOWN"
        }
        Log.i("Preload", "url=$url success=$success type=$type")
    }
})
```

回调参数：

| 参数 | 说明 |
| --- | --- |
| `url` | 预加载的视频 URL |
| `success` | 是否成功完成，命中已有缓存也按成功处理 |
| `preloadType` | `PRELOAD_TYPE_MEMORY` 或 `PRELOAD_TYPE_DISK` |

### 3. 单实例播放器 API

`SuperPlayer` 适合单页面、单播放器、独立生命周期场景。SPlayer 的 `VideoPageFragment` 使用这一套。

常用方法：

| API | 说明 |
| --- | --- |
| `setVideoUrl(url, autoPlay)` | 设置播放地址，Surface 未创建时会等待绑定后再处理 |
| `prepare(url)` | 只准备，不自动播放 |
| `start()` / `pause()` / `stop()` | 播放控制 |
| `seekTo(ms)` | 跳转到指定毫秒 |
| `togglePlayPause()` | 播放/暂停切换 |
| `setMute(mute)` | 静音控制 |
| `setVolume(left, right)` | 声道音量 |
| `setSpeed(speed)` | 播放速度 |
| `setRendererType(type)` | 设置渲染方式，常用 `OPENGL_ES20` |
| `setBufferSize(ms)` | 设置缓冲区大小 |
| `setEnableHardwareDecoder(enable)` | 是否优先使用硬解 |
| `setZoomScale(scale)` | 设置画面缩放 |
| `setTranslation(tx, ty, width, height)` | 设置画面平移 |
| `resetZoom()` | 重置缩放和平移 |
| `renderLastFrame()` | Surface 重建后渲染最后一帧 |
| `getCurrentPosition()` / `getDuration()` | 获取进度和时长 |
| `getPlaybackStats()` | 获取实时解码帧率、渲染帧率、动态码率、队列状态 |
| `getAllVideoStreams()` | 获取多码率/多视频流信息 |
| `switchVideoStream(streamIndex)` | 切换视频流 |
| `release()` | 释放播放器实例 |

生命周期建议：

```kotlin
override fun onPause() {
    player.onActivityPause(isFinishing)
    super.onPause()
}

override fun onStop() {
    player.onActivityStop()
    super.onStop()
}

override fun onResume() {
    super.onResume()
    player.onActivityResume()
}

override fun onDestroyView() {
    player.onActivityDestroy()
    player.release()
    super.onDestroyView()
}
```

### 4. 全局播放器 API

`PlayerManager` 适合全局播放器池、跨页面复用、类似抖音的快速切换场景。

常用方法：

| API | 说明 |
| --- | --- |
| `setMaxPlayerPoolSize(size)` | 设置播放器池容量 |
| `attachSurface(surface)` | 绑定渲染 Surface |
| `rememberSurface(surface)` | 只记录 Surface，等选中播放器后再绑定 |
| `attachCurrentSurfaceIfNeeded()` | 当前播放器需要时补绑 Surface |
| `detachSurface()` | 页面退出时解绑 Surface，不销毁播放器 |
| `setDataSource(url)` | 设置或切换数据源 |
| `prepare()` / `start()` / `pause()` / `stop()` | 播放控制 |
| `seekTo(ms)` | 跳转 |
| `resumePlay()` | 从当前状态恢复播放 |
| `renderLastFrame()` | 恢复暂停画面 |
| `canResumeFromPool(url)` | 判断某个 URL 是否可从池中快速恢复 |
| `switchToPooledVideo(url)` | 切到池中已有播放器 |
| `getCurrentPosition()` / `getDuration()` | 当前播放进度和时长 |
| `getVideoWidth()` / `getVideoHeight()` | 当前视频尺寸 |
| `getPlaybackStats()` | 实时性能指标 |
| `setGlobalRendererType(type)` | 设置全局渲染器 |
| `setGlobalBufferSize(ms)` | 设置全局缓冲 |
| `setZoomScale(scale)` / `setTranslation(...)` | 全局播放器画面变换 |
| `release()` | 应用退出时释放全局播放器 |

监听：

```kotlin
PlayerManager.setOnPlayerStateListener(object : OnPlayerStateListener {
    override fun onPlayerStateChanged(state: PlayerState) {}
    override fun onProgressUpdate(currentPosition: Long, duration: Long) {}
    override fun onBufferUpdate(bufferPercentage: Int) {}
    override fun onLoadingChanged(isLoading: Boolean) {}
    override fun onBufferedPositionUpdate(bufferedPosition: Long, duration: Long) {}
})

PlayerManager.setOnErrorListener(object : OnErrorListener {
    override fun onError(errorCode: Int, errorMsg: String) {}
})
```

### 5. 预加载 API

| API | 说明 |
| --- | --- |
| `PlayerManager.preloadVideo(url, preloadSeconds, bitrate)` | 内存预加载，MP4 和 M3U8 会走不同 native 路径 |
| `PlayerManager.preloadToDisk(url, preloadSeconds, bitrate)` | 磁盘预加载，适合短视频列表秒开 |
| `PlayerManager.preloadM3U8(url, preloadSeconds, bitrate)` | M3U8 专用预加载入口 |
| `PlayerManager.isPreload(url)` | 判断 URL 是否已有可用预加载 |
| `PlayerManager.isVideoPreloaded(url)` | 判断普通单文件视频是否预加载 |
| `PlayerManager.isM3u8Preloaded(url)` | 判断 M3U8 是否预加载 |
| `PlayerManager.cancelPreload(url)` | 取消普通视频预加载 |
| `PlayerManager.cancelPreloadM3U8(url)` | 取消 M3U8 预加载 |

推荐应用层不要直接对整个列表同时调用 `preloadToDisk`。可以参考 `PreloadScheduler`：按当前播放位置、滑动方向和距离排优先级，控制并发，跳过已完成任务。

### 6. 离线下载 API

| API | 说明 |
| --- | --- |
| `VideoDownloadManager.init(context)` | 初始化下载管理 |
| `VideoDownloadManager.download(url)` | 开始离线下载 |
| `VideoDownloadManager.cancel(url)` | 取消下载 |
| `VideoDownloadManager.getState(url)` | 获取 `NONE/PENDING/DOWNLOADING/COMPLETED/FAILED/CANCELLED` |
| `VideoDownloadManager.progressFlow(url)` | 订阅下载进度 |
| `VideoDownloadManager.isDownloaded(url)` | 判断是否已离线下载 |
| `VideoDownloadManager.getLocalPlayUrl(url)` | 获取本地可播放地址 |

SPlayer 播放前会优先通过 `getLocalPlayUrl(url)` 取离线地址，命中后直接播放本地文件。

### 7. 画面和手势

SPlayer 当前使用 `SuperPlayerView` 承载视频画面，`PlayerGestureController` 处理点击和双指缩放。

| 能力 | 当前实现 |
| --- | --- |
| 视频尺寸适配 | `VideoPageFragment.resizeSurfaceView(width, height)` 按视频宽高比居中缩放 |
| 双指缩放 | `PlayerGestureController` + `PlayerZoomMode.VIEW_SCALE` |
| OpenGL 缩放接口 | `SuperPlayer.setZoomScale(scale)` |
| OpenGL 平移接口 | `SuperPlayer.setTranslation(tx, ty, width, height)` |
| 点击播放/暂停 | 点击层回调到 `PlayerFeedActivity.onPageTap()` |
| 进度拖动 | `SeekBar` 回调 `onUserSeekStart/Preview/Stop` |

## SDK 能力概览

SuperPlayer SDK 侧核心能力：

- 基于 FFmpeg 的音视频解封装和解码链路。
- 支持常见网络流和文件播放，包括 MP4、HLS/M3U8、RTMP、RTSP 等。
- 支持 H.264、H.265、AV1 视频能力，AV1 依赖 `libdav1d`。
- 支持 AAC、PCM 等音频能力。
- 支持 MediaCodec 硬解优先和 FFmpeg 软解兜底。
- 支持 OpenGL ES 渲染、画面缩放、平移、最后一帧恢复。
- 支持内存预加载、磁盘预加载、离线下载、播放中缓存复用。
- 支持多视频流查询和切换，适合多分辨率 HLS 场景。
- 支持实时性能监控，包括解码 FPS、渲染 FPS、动态码率、队列水位、丢帧数。
- 集成 Crashpad，可生成 native 崩溃 dump，便于定位 C++ 问题。

## 接入建议

- 短视频流优先使用应用层调度器控制预加载，不要一次性预加载整个列表。
- 当前播放、用户 seek、页面切换优先级应高于后台预加载。
- HLS/M3U8 和 MP4 可以统一走 `preloadVideo` 或 `preloadToDisk`，SDK 内部会区分处理路径。
- 离线下载完成的视频，播放前优先使用 `VideoDownloadManager.getLocalPlayUrl(url)`。
- 页面退出时要区分“解绑 Surface”和“释放播放器”。列表快速切换通常只解绑，应用退出再释放。
- 需要展示当前码率和帧率时，播放中定时读取 `getPlaybackStats()`。

