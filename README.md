# MobilePlayer

基于 Jetpack Compose 的 Android 视频播放器库，统一封装 Media3 ExoPlayer 与 IjkPlayer。宿主只依赖 `VideoPlayerController` 和 `PlaybackState`，不需要直接操作具体播放器 SDK。

## 当前能力

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| 网络视频 | 已支持 | HTTP/HTTPS、Media3 HLS/DASH、请求头 |
| 本地视频 | 已支持 | 推荐使用 `content://` URI |
| Media3/Ijk 切换 | 已支持 | 保留视频源、进度、播放意图、音量、倍速和 Surface |
| 页面内竖屏播放 | 已支持 | `INLINE` 展示模式 |
| 横屏全屏播放 | 已支持协议 | 宿主负责方向和沉浸式窗口 |
| PiP/系统悬浮窗 | 已支持协议 | 宿主负责系统 API、权限和前台 Service |
| 悬浮窗返回原模式 | 已支持 | 自动记住进入悬浮窗前的页面内或全屏模式 |
| 网络加载速度 | 已支持 | 准备或缓冲时显示 `B/s`、`KB/s`、`MB/s` |
| 播放进度持久化 | 已支持 | 按稳定 `mediaId` 自动恢复并节流保存，双内核共用 |
| 网络内容缓存 | 已支持 | 渐进式文件边播边存、按 Range 分段复用，Media3/Ijk 共用 |
| 自定义弹幕覆盖层 | 可扩展 | 使用 Compose `Box` 叠加，详见 [EXTENDING.md](EXTENDING.md) |
| MediaSession/音频焦点 | 尚未内置 | 建议由后续后台播放模块统一接入 |
| Maven 发布 | 已配置 | 支持 JitPack 多模块坐标，也可同工程 `project` 依赖 |

最低 Android 版本为 API 23，compile SDK 为 36。

## 模块职责

| 模块 | 职责 |
| --- | --- |
| `player-core` | 视频源、统一状态、内核接口、播放控制、进度与内容缓存、展示模式协议 |
| `player-engine-media3` | Media3 ExoPlayer 适配器 |
| `player-engine-ijk` | IjkPlayer 适配器及多 ABI 原生库 |
| `player-compose` | Surface 生命周期、默认控制 UI 和双内核默认装配 |

依赖方向固定为 `player-compose -> engine -> core`。播放实现不负责 Activity 方向、PiP 权限或业务页面导航。

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
        "com.github.onyou668.video-mobile-player:player-compose:v0.1.0",
    )
}
```

正式发布使用不可变 Git 标签。Flutter/React Native 插件的 submodule 当前固定到主库 `v0.1.0` 对应提交；主库升级后，插件必须先更新 submodule 并通过 CI，再发布自己的新 tag。调用方只依赖 Android、Flutter 或 React Native 对应仓库的 tag，不依赖开发分支。

网络权限会从播放器内核 AAR 的 manifest 合并到宿主。HTTP 明文地址仍需宿主根据安全策略配置 `networkSecurityConfig`，生产环境建议使用 HTTPS。

## 最小示例

```kotlin
@Composable
fun VideoPage(url: String) {
    val player = rememberVideoPlayerController(
        initialEngine = PlayerEngineType.MEDIA3,
    )
    val source = remember(url) {
        VideoSource.network(
            url = url,
            headers = mapOf("Authorization" to "Bearer ..."),
            title = "Video title",
        )
    }

    VideoPlayer(
        controller = player,
        source = source,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f),
    )
}
```

`rememberVideoPlayerController()` 会随当前 Composable 销毁，适合不需要跨 Activity 重建或跨页面继续播放的普通页面。

## 视频源

### 网络视频

```kotlin
val source = VideoSource.network(
    url = "https://example.com/video.m3u8",
    mediaId = "episode-42",
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
    mediaId = "live-channel-1",
    contentCachePolicy = VideoSource.ContentCachePolicy.DISABLED,
)
```

### 本地视频

优先使用 Photo Picker 或 Storage Access Framework 返回的 `content://` URI：

```kotlin
val source = VideoSource.local(
    uri = selectedUri.toString(),
    mediaId = "local-video-42",
    title = "Local video",
)
```

如果 App 重启后仍要访问该 URI，选择文件时需要调用 `takePersistableUriPermission`。播放器库不会主动申请广泛存储权限。

`mediaId` 是进度和内容缓存的稳定键。业务有自己的视频 ID 时应显式传入；未传时默认使用 URI。

## 播放进度与内容缓存

缓存通过 `VideoPlayerCache` 接口注入 Controller。默认的 `AppPrivateVideoPlayerCache` 将进度保存在应用私有 `SharedPreferences`，将网络视频分段放在应用私有 `filesDir`。这些文件不会被 Android 当作临时缓存自动清理；调用 `remove()`/`clear()`、清除 App 数据或卸载 App 时才会丢失，超过配置上限时按最近最少使用的分段淘汰。

```kotlin
class PlayerApplication : Application() {
    val videoPlayerCache by lazy {
        AppPrivateVideoPlayerCache(
            context = this,
            maxMediaCacheBytes = 512L * 1024L * 1024L,
        )
    }
}

class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    val cache = (application as PlayerApplication).videoPlayerCache

    val player = createDefaultVideoPlayerController(
        context = application,
        scope = viewModelScope,
        cache = cache,
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

播放一个资源时的起始进度优先级固定为：

1. 调用方显式传入的 `startPositionMs`；传 `0L` 表示强制从头播放。
2. `VideoPlayerCache` 中该 `mediaId` 的已保存进度。
3. 没有记录时从 `0L` 开始。

```kotlin
player.setMedia(source, startPositionMs = 30_000L)

VideoPlayer(
    controller = player,
    source = source,
    startPositionMs = 30_000L,
)
```

Controller 在播放过程中每 5 秒节流保存一次，并在暂停、Seek、切换资源、切换内核、宿主停止和关闭时强制保存。Media3 与 IjkPlayer 始终读取同一个 Controller 状态和同一个 `mediaId` 记录，不会产生两份进度。接近视频结尾 5 秒的记录默认视为已播完，下次从头开始；可通过 `PlaybackProgressPolicy` 调整。

外部模块也可以直接操作缓存：

```kotlin
val progress = cache.getProgress(source.mediaId)
cache.saveProgress(
    PlaybackProgress(source.mediaId, positionMs = 30_000L, durationMs = 120_000L),
)

viewModelScope.launch {
    // 可选：显式预取完整渐进式文件；普通播放不需要调用。
    player.cacheMedia(source) { download ->
        val fraction = download.fraction
    }
    cache.remove(source.mediaId) // 同时删除该资源的进度和内容
    cache.clear()                // 清空全部进度和内容
}
```

本地 `content://`/文件资源只保存播放进度，不复制视频内容。普通网络 MP4 等渐进式单文件在播放时自动经过读穿代理：命中的 512 KiB 分段直接从本地读取，缺失分段通过上游 HTTP Range 请求边传给播放器边写入磁盘。拖动到未缓存位置时只下载新位置对应的分段；再次打开时已有分段可直接播放，缺失部分继续从网络补齐。

默认目录规则如下，业务不应直接依赖或修改内部文件：

```text
filesDir/video-mobile-player-cache/
  <sha256(mediaId)>/
    metadata.json
    000000000000.segment
    000000000001.segment
    *.part
```

`mediaId` 必须稳定且代表同一份不可变媒体内容。签名 URL 或鉴权 Header 更新时可以继续使用原 `mediaId`；如果视频内容本身更新，必须更换 `mediaId` 或先调用 `remove(mediaId)`，否则旧分段可能被复用。上游服务器需要正确支持 HTTP Range/`206 Partial Content`；不支持时应将 `contentCachePolicy` 设为 `DISABLED` 直连播放。

HLS/DASH、本地文件和显式禁用缓存的网络源都只参与进度保存，不会写入媒体分段。`cacheMedia()` 是可选的整文件离线预取入口，仍复用同一套分段；普通边播边存不需要预先调用它。

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

`setMedia()` 会重新加载，并按“显式进度 -> 缓存进度 -> 0”的顺序决定起点；可重复创建的 UI 容器应使用 `setMediaIfChanged()`。`VideoPlayer` 内部已经使用后者，因此横竖屏、全屏和悬浮容器迁移不会重置相同视频。

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

## 页面内、全屏与悬浮窗

`PlayerPresentationController` 只管理展示状态和请求，不直接操作 Activity 或系统权限：

- `INLINE`：页面内区域，通常配合竖屏。
- `FULLSCREEN`：全窗口区域，通常配合横屏和沉浸模式。
- `FLOATING`：Android PiP 或 App 自己的系统悬浮窗。

### 启动方式

```kotlin
// 默认进入页面内竖屏模式。
rememberPlayerPresentationController()

// 页面创建后请求宿主切换横屏全屏。
rememberPlayerPresentationController(
    startupMode = PlayerPresentationMode.FULLSCREEN,
)

// 宿主原本已经处于横屏全屏，不产生启动切换请求。
rememberPlayerPresentationController(
    initialMode = PlayerPresentationMode.FULLSCREEN,
)
```

`initialMode` 是宿主当前真实模式，`startupMode` 是进入页面后的目标模式。二者不同会产生一次待宿主处理的 `ChangeMode` 请求。

```kotlin
val presentation = rememberPlayerPresentationController(
    startupMode = PlayerPresentationMode.INLINE,
    capabilities = PlayerPresentationCapabilities(
        fullscreen = true,
        floating = true,
        close = true,
    ),
)

VideoPlayer(
    controller = player,
    source = source,
    presentationController = presentation,
    modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(16f / 9f),
)
```

### 宿主处理展示请求

每个请求都必须在平台操作完成后调用 `confirmMode()`/`confirmClosed()`，失败时调用 `rejectPendingRequest()`。请求未完成期间新的展示请求会被拒绝。

```kotlin
LaunchedEffect(presentation, activity) {
    presentation.requests.collect { request ->
        when (request) {
            is PlayerPresentationRequest.ChangeMode -> when (request.to) {
                PlayerPresentationMode.INLINE -> {
                    activity.requestedOrientation =
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    presentation.confirmMode(PlayerPresentationMode.INLINE)
                }

                PlayerPresentationMode.FULLSCREEN -> {
                    activity.requestedOrientation =
                        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    presentation.confirmMode(PlayerPresentationMode.FULLSCREEN)
                }

                PlayerPresentationMode.FLOATING -> {
                    val entered = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                        activity.enterPictureInPictureMode(
                            PictureInPictureParams.Builder().build(),
                        )
                    if (entered) {
                        presentation.confirmMode(PlayerPresentationMode.FLOATING)
                    } else {
                        presentation.rejectPendingRequest("PiP is unavailable")
                    }
                }
            }

            PlayerPresentationRequest.Close -> {
                // 移除播放器 UI、停止前台 Service 或关闭播放 Activity。
                presentation.confirmClosed()
            }
        }
    }
}
```

### PiP 配置与返回

宿主 Activity 需要声明：

```xml
<activity
    android:name=".PlayerActivity"
    android:configChanges="screenSize|smallestScreenSize|screenLayout|orientation"
    android:resizeableActivity="true"
    android:supportsPictureInPicture="true" />
```

进入悬浮窗时会记录此前是 `INLINE` 还是 `FULLSCREEN`。自定义悬浮窗被点击时可调用：

```kotlin
presentation.requestReturnFromFloating()
```

系统 PiP 被点击展开时，Android 会直接恢复 Activity。宿主在 `onPictureInPictureModeChanged(false, ...)` 中恢复布局/方向后确认：

```kotlin
val returnMode = presentation.state.value.floatingReturnMode
presentation.confirmMode(returnMode)
```

这只改变展示模式和 Surface，播放进度及状态不变。`FLOATING` 模式下 `VideoPlayer` 收到宿主 `ON_STOP` 时不会自动暂停。

### 系统级悬浮窗

如果不是 Android PiP，而是 `WindowManager` 系统悬浮窗，宿主需要负责：

- `SYSTEM_ALERT_WINDOW` 权限和授权失败流程。
- 前台 Service、常驻通知和 Android 版本适配。
- 创建、拖拽、缩放和销毁悬浮 View。
- 将同一个 Controller 传给悬浮窗中的 `VideoPlayer`。
- 点击悬浮窗后导航回 App，并确认目标展示模式。

播放器库不主动申请悬浮窗权限，也不会自行启动 Service。

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
        showPresentationControls = true,
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

- `confirmMode()` 表示平台操作已经完成，不应在方向切换或 PiP 进入前提前调用。
- `confirmClosed()` 只更新展示状态，不会自动调用 `player.close()`。
- Controller 关闭后不能复用。
- 系统悬浮窗必须由前台 Service 持有长生命周期 Controller。
- 当前库尚未内置 MediaSession、音频焦点、耳机拔出处理和锁屏控制。
- 当前没有示例 App 和真机自动化测试，PiP、Ijk 原生解码及厂商系统悬浮窗需在目标设备验证。

## 构建验证

```shell
./gradlew build
```

IjkPlayer 当前构建携带 `armeabi`、`armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64` 原生库。
