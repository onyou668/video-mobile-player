# MobilePlayer

基于 Jetpack Compose 的 Android 视频播放器库，统一封装 Media3 ExoPlayer 与 IjkPlayer。宿主只依赖 `VideoPlayerController` 和 `PlaybackState`，不需要直接操作具体播放器 SDK。

## 当前能力

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| 网络视频 | 已支持 | HTTP/HTTPS、Media3 HLS/DASH、请求头 |
| 本地视频 | 已支持 | 推荐使用 `content://` URI |
| Media3/Ijk 切换 | 已支持 | 保留视频源、进度、播放意图、音量、倍速和 Surface |
| 竖屏播放 | 已支持 | `PortraitVideoPlayer` 固定竖屏，不提供全屏切换 |
| 横屏/全屏播放 | 已支持 | `LandscapeVideoPlayer` 由外部控制 `isFullscreen`，组件负责横屏窗口和系统栏 |
| 网络加载速度 | 已支持 | 准备或缓冲时显示 `B/s`、`KB/s`、`MB/s` |
| 播放进度 | 外部管理 | 宿主保存 `PlaybackState.positionMs`，通过 `startPositionMs` 恢复 |
| 网络内容缓存 | 已支持 | 渐进式文件边播边存、按 Range 分段复用，Media3/Ijk 共用 |
| 自定义弹幕覆盖层 | 已预留 | 使用 `overlayContent` slot，页面内和全屏共用，详见 [EXTENDING.md](EXTENDING.md) |
| MediaSession/音频焦点 | 尚未内置 | 建议由后续后台播放模块统一接入 |
| Maven 发布 | 已配置 | 支持 JitPack 多模块坐标，也可同工程 `project` 依赖 |

最低 Android 版本为 API 23，compile SDK 为 36。

## 模块职责

| 模块 | 职责 |
| --- | --- |
| `player-core` | 视频源、统一状态、内核接口、播放控制和内容缓存 |
| `player-engine-media3` | Media3 ExoPlayer 适配器 |
| `player-engine-ijk` | IjkPlayer 适配器及多 ABI 原生库 |
| `player-compose` | 竖屏/横屏播放器组件、Surface 生命周期、默认控制 UI 和双内核默认装配 |

依赖方向固定为 `player-compose -> engine -> core`。播放内核不负责 Activity 方向、权限或业务页面导航。

更详细的模块说明见 [ARCHITECTURE.md](ARCHITECTURE.md)，扩展方式见 [EXTENDING.md](EXTENDING.md)。

## 接入

同一 Gradle 工程中的 App 可以直接依赖：

```kotlin
dependencies {
    implementation(project(":player-compose"))
}
```

外部 Android/Flutter/React Native 工程可以添加 JitPack 仓库，并使用 Git 标签或提交哈希作为版本：

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

```kotlin
dependencies {
    implementation(
        "com.github.onyou668.video-mobile-player:player-compose:v0.3.0",
    )
}
```

正式发布使用不可变 Git 标签。Flutter/React Native 插件的 `v0.3.0` submodule 固定到主库 `v0.3.0` 对应提交；主库升级后，插件必须先更新 submodule 并通过 CI，再发布自己的新 tag。调用方只依赖 Android、Flutter 或 React Native 对应仓库的 tag，不依赖开发分支。

网络权限会从播放器内核 AAR 的 manifest 合并到宿主。HTTP 明文地址仍需宿主根据安全策略配置 `networkSecurityConfig`，生产环境建议使用 HTTPS。

## 可嵌入的播放器窗口

库只提供按媒体画幅选择的 `PortraitVideoPlayer` 或
`LandscapeVideoPlayer`。前者只提供竖版播放，不包含全屏切换；后者由外部持有
`isFullscreen`，组件通过 `onFullscreenChange` 请求切换，并负责 Activity 横屏、全屏窗口和系统栏处理：

```kotlin
var isFullscreen by rememberSaveable { mutableStateOf(false) }

LandscapeVideoPlayer(
    controller = player,
    source = source,
    isFullscreen = isFullscreen,
    onFullscreenChange = { isFullscreen = it },
)
```

两个组件始终使用同一个 `VideoPlayerController`；横竖屏迁移不会重新创建播放会话。

默认 `modifier` 会占满可用宽度并按 16:9 计算高度，适合放在详情页顶部。宿主有其他画幅时可以传自己的 `Modifier` 覆盖；进入全屏后组件始终使用整个窗口，不受页面内尺寸约束。

宿主也可以让 Controller 成为唯一的视频源状态。此时 Host 不传 `source/url`，宿主的任意业务操作都可以直接切换播放地址或内核：

```kotlin
class HostPlayerViewModel(application: Application) : AndroidViewModel(application) {
    val player = createDefaultVideoPlayerController(
        context = application,
        scope = viewModelScope,
        initialEngine = PlayerEngineType.MEDIA3,
    )

    fun play(video: HostVideo) {
        player.setMedia(
            VideoSource.network(
                url = video.url,
                title = video.title,
            ),
        )
    }

    fun useIjk() = player.switchEngine(PlayerEngineType.IJK)
    fun useMedia3() = player.switchEngine(PlayerEngineType.MEDIA3)

    override fun onCleared() = player.close()
}

@Composable
fun PlayerWindow(viewModel: HostPlayerViewModel) {
    PortraitVideoPlayer(controller = viewModel.player)
}
```

`VideoPlayerController` 对宿主公开的常用控制包括：

| 调用 | 用途 |
| --- | --- |
| `setMedia(source, playWhenReady, startPositionMs)` | 切换播放地址和标题，并使用宿主传入的起始位置 |
| `play()` / `pause()` / `togglePlayPause()` | 播放控制 |
| `seekTo()` / `seekBy()` | 进度控制 |
| `switchEngine()` | 外部切换 Media3/Ijk，保留当前进度和播放意图 |
| `setPlaybackSpeed()` / `setVolume()` | 倍速和音量 |
| `state` | 获取当前地址、标题、内核、进度、缓冲、网速和错误 |

横屏切换可能触发 Activity 重建，因此需要保证 Controller 的生命周期长于播放器 Composable。建议由 Activity 级 ViewModel 持有；不要为页面内和全屏状态分别创建 Controller。

## 视频源

### 网络视频

```kotlin
val source = VideoSource.network(
    url = "https://example.com/video.m3u8",
    headers = mapOf(
        "Authorization" to "Bearer token",
        "Referer" to "https://example.com/",
    ),
    title = "Network video",
)
```

Media3 支持普通文件、HLS 和 DASH。IjkPlayer 实际支持的封装格式与编解码能力由当前 Ijk 构建决定。

已知的 `.m3u8`/`.mpd` 地址会自动绕过内容缓存。如果 HLS/DASH 地址没有标准扩展名，应显式关闭内容缓存，播放进度仍会正常保存：

```kotlin
val live = VideoSource.network(
    url = "https://example.com/opaque-live-url",
    contentCachePolicy = VideoSource.ContentCachePolicy.DISABLED,
)
```

### 本地视频

优先使用 Photo Picker 或 Storage Access Framework 返回的 `content://` URI：

```kotlin
val source = VideoSource.local(
    uri = selectedUri.toString(),
    title = "Local video",
)
```

如果 App 重启后仍要访问该 URI，选择文件时需要调用 `takePersistableUriPermission`。播放器库不会主动申请广泛存储权限。

调用方不传 `mediaId`。播放器使用“来源类型 + 完整 URI”的 SHA-256 自动生成稳定、不透明的 `mediaId`，标题和请求头变化不会改变它。相同 URI 在 App 重启后仍会命中同一份网络内容缓存；URL 本身变化则视为新资源。

## 播放进度与内容缓存

缓存通过 `VideoContentCache` 接口注入 Controller。默认装配会创建启用内容缓存的
`AppPrivateVideoPlayerCache`，网络视频分段放在应用私有 `filesDir`。进度由宿主单独保存，推荐使用独立的 `PlaybackProgressStore`。
缓存文件不会被 Android 当作临时缓存自动清理；调用 `remove()`/`clear()`、清除 App 数据或卸载 App 时才会丢失，超过配置上限时按最近最少使用的分段淘汰。

```kotlin
class PlayerApplication : Application() {
    val videoPlayerCache by lazy {
        AppPrivateVideoPlayerCache(
            context = this,
            maxMediaCacheBytes = 512L * 1024L * 1024L,
            // 可选：任意应用私有目录，mediaId 算法保持不变。
            cacheDirectory = File(filesDir, "video-cache"),
        )
    }
}

class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    val videoCache = (application as PlayerApplication).videoPlayerCache

    val player = createDefaultVideoPlayerController(
        context = application,
        scope = viewModelScope,
        cache = videoCache,
    )
}
```

同一个进程通常只创建一个 `AppPrivateVideoPlayerCache`，并注入所有 Controller。它在首次播放可缓存网络文件时启动只监听 `127.0.0.1` 的本地 HTTP Range 代理；Media3 和 IjkPlayer 获得同一个代理地址，因此缓存不属于某个播放器内核。

本地代理使用 HTTP loopback。如果宿主的网络安全策略禁止全部明文流量，需要在宿主现有的 Network Security Config 中仅放行 `127.0.0.1`，不要为所有域名开启明文 HTTP：

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">127.0.0.1</domain>
        <domain includeSubdomains="false">localhost</domain>
    </domain-config>
</network-security-config>
```

宿主在 `<application android:networkSecurityConfig="@xml/network_security_config">` 中引用该文件；已有配置时只合并上述 domain，不要替换其他证书或域名策略。

加载资源时，Controller 使用调用方传入的 `startPositionMs`，默认从 `0L` 开始。播放过程中宿主可以从 `PlaybackState` 获取当前位置并按业务节流保存；切换内核和全屏不会改变该状态。

外部模块分别操作进度存储和媒体缓存：

```kotlin
val progressStore: PlaybackProgressStore = TODO("由宿主实现")
val videoCache: VideoContentCache = (application as PlayerApplication).videoPlayerCache
val progress = progressStore.getProgress(source.mediaId)
val progressByUrl = progressStore.getProgressByUrl(videoUrl)
val history = progressStore.getAllProgress()
progressStore.saveProgress(
    PlaybackProgress(source.mediaId, positionMs = 30_000L, durationMs = 120_000L),
)

viewModelScope.launch {
    // 可选：显式预取完整渐进式文件；普通播放不需要调用。
    player.cacheMedia(source) { download ->
        val fraction = download.fraction
    }
    videoCache.remove(source.mediaId)
    videoCache.clear()
}
```

本地 `content://`/文件资源不会被复制。普通网络 MP4 等渐进式单文件在播放时自动经过读穿代理：命中的 512 KiB 分段直接从本地读取，缺失分段通过上游 HTTP Range 请求边传给播放器边写入磁盘。拖动到未缓存位置时只下载新位置对应的分段；再次打开时已有分段可直接播放，缺失部分继续从网络补齐。代理检测到服务端不支持 Range 时会回退到原始地址播放。

内容缓存默认开启，但可以通过 `VideoCacheConfig` 关闭或限制后缀：

```kotlin
val cache = AppPrivateVideoPlayerCache(
    context = application,
    cacheDirectory = File(application.filesDir, "video-cache"),
    config = VideoCacheConfig(
        enabled = true,
        cacheableExtensions = setOf("mp4", "m4v"),
    ),
)
```

进度保存不由内容缓存模块负责。后缀不在允许列表、服务器不支持 HTTP Range，或资源是本地文件时，播放器会继续播放但不写入媒体内容缓存。

默认目录规则如下，业务不应直接依赖或修改内部文件：

```text
filesDir/video-mobile-player-cache/
  <sha256(mediaId)>/
    metadata.json
    000000000000.segment
    000000000001.segment
    *.part
```

自动生成的 `mediaId` 代表来源类型与完整 URI。鉴权 Header 或标题更新不会改变 ID；签名参数等 URL 内容变化会生成新 ID，旧缓存可通过原 `VideoSource.mediaId` 删除。如果同一 URL 返回了新内容，代理会根据长度和上游校验信息使旧分段失效。上游服务器需要正确支持 HTTP Range/`206 Partial Content`；不支持时应将 `contentCachePolicy` 设为 `DISABLED` 直连播放。

HLS/DASH、本地文件和显式禁用缓存的网络源不会写入媒体分段。`cacheMedia()` 是可选的整文件离线预取入口，仍复用同一套分段；普通边播边存不需要预先调用它。

## 播放控制与状态

```kotlin
player.play()
player.pause()
player.togglePlayPause()
player.seekTo(30_000L)
player.seekBy(10_000L)
player.setVolume(0.5f)
player.setPlaybackSpeed(1.5f)
player.switchEngine(PlayerEngineType.IJK)
player.retry()
```

统一状态通过 `StateFlow` 暴露：

```kotlin
val state by player.state.collectAsStateWithLifecycle()

Text("position=${state.positionMs}")
Text("buffered=${state.bufferedPositionMs}")
Text("speed=${state.networkSpeedBytesPerSecond} B/s")
```

切换 Media3/Ijk 时会恢复当前视频源、进度、播放意图、音量和倍速。切核需要释放旧内核并重新准备新内核，因此会有一次短暂的 `PREPARING/BUFFERING`，不是无缝解码器热切换。

`setMedia()` 会重新加载并使用调用方传入的 `startPositionMs`；可重复创建的 UI 容器应使用 `setMediaIfChanged()`。`VideoPlayer` 内部已经使用后者，因此横竖屏和全屏迁移不会重置相同视频。

## Controller 生命周期

| 场景 | 推荐持有位置 |
| --- | --- |
| 单页面播放 | `rememberVideoPlayerController()` |
| 旋转重建后继续播放 | Activity 级 `ViewModel` |
| 页面内、全屏之间共享 | 同一个 `ViewModel` Controller |
| PiP 随 Activity 存活 | Activity 级 `ViewModel` Controller |
| 系统悬浮窗、跨页面、后台播放 | 前台 Service 持有 Controller |

ViewModel 示例：

```kotlin
class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    val player = createDefaultVideoPlayerController(
        context = application,
        scope = viewModelScope,
    )

    override fun onCleared() {
        player.close()
    }
}
```

同一时刻所有页面内、全屏或悬浮 UI 都必须使用同一个 Controller。不要为每个展示模式分别创建播放器，否则无法保证进度和播放状态连续。

## 全屏与系统悬浮窗

播放器组件只负责页面内竖屏、横屏全屏和 Surface 生命周期。横屏全屏由
`LandscapeVideoPlayer` 的 `isFullscreen` 与 `onFullscreenChange` 控制；PiP、系统悬浮窗、权限、前台 Service 和导航由宿主自行实现，并继续复用同一个 `VideoPlayerController`。

## 网络加载速度

网络视频处于 `PREPARING` 或 `BUFFERING` 时，默认在加载动画下显示实时吞吐量。Media3 使用传输监听采样，IjkPlayer 使用 TCP 速度统计，并统一写入：

```kotlin
state.networkSpeedBytesPerSecond
```

关闭默认展示：

```kotlin
VideoPlayer(
    controller = player,
    uiConfig = VideoPlayerUiConfig(
        showNetworkSpeedWhileLoading = false,
    ),
)
```

本地视频不会显示网速。该数值表示播放器读取媒体数据的吞吐量，不代表设备所有网络请求的总速度。

## UI 配置

```kotlin
VideoPlayer(
    controller = player,
    source = source,
    uiConfig = VideoPlayerUiConfig(
        showEngineSwitcher = false,
        showNetworkSpeedWhileLoading = true,
        skipDurationMs = 15_000L,
        controlsAutoHideMs = 4_000L,
        availableSpeeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f),
    ),
    strings = VideoPlayerStrings(
        play = "播放",
        pause = "暂停",
        loading = "加载中",
    ),
    colors = VideoPlayerColors(
        accent = Color(0xFF00C853),
    ),
)
```

## 重要约束

- Controller 关闭后不能复用。
- 系统悬浮窗必须由前台 Service 持有长生命周期 Controller。
- 当前库尚未内置 MediaSession、音频焦点、耳机拔出处理和锁屏控制。
- 当前没有示例 App 和真机自动化测试，PiP、Ijk 原生解码及厂商系统悬浮窗需在目标设备验证。

## 构建验证

```shell
./gradlew build
```

IjkPlayer 当前构建携带 `armeabi`、`armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64` 原生库。
