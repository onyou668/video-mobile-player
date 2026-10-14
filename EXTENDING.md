# MobilePlayer 扩展指南

扩展必须保持一条规则：业务 UI 只读取 `PlayerController.state` 并调用 Controller 能力，不能持有 `PlaybackEngine` 或修改 `PlayerState`。

## 自定义 UI

优先使用数据配置完成常见变化：

- `VideoPlayerControlLayout` 控制上、中、下区域的内容与顺序。
- 删除列表项即可隐藏控制；同一个控制可移动到其他区域。
- `VideoPlayerIcons`、`VideoPlayerColors`、`VideoPlayerStrings` 替换视觉资源。
- `PlayerControl.Custom(key)` 与 `customControls` 插入业务按钮。
- `persistentContent` 与上、中、侧、下位置插槽可替换指定区域；`null` 使用默认内容，空 lambda 隐藏该区域。
- `controlsContent`、`loadingContent`、`errorContent`、`overlayContent`、`subtitleContent` 替换完整层。
- `PlayerInteractionConfig` 独立配置点击显隐、自动隐藏、亮度和音量手势；替换控制层不会丢失这些原始宿主行为。

`ContainedVideoPlayer` 与 `ExpandableVideoPlayer` 默认使用各自的成熟控制布局。传入不同于默认值的 `VideoPlayerControlLayout` 时，组件自动改用可配置的上、中、下区域，因此控制项的隐藏、移动和 `Custom` 插入同样有效。

```kotlin
val downloadControl = PlayerControl.Custom("download")

ContainedVideoPlayer(
    controller = controller,
    uiConfig = VideoPlayerUiConfig(
        controls = VideoPlayerControlLayout(
            top = listOf(PlayerControl.Title, downloadControl),
            center = listOf(PlayerControl.PlayPause),
            bottom = listOf(PlayerControl.Time, PlayerControl.Timeline),
        ),
    ),
    customControls = mapOf(
        "download" to PlayerControlContent { scope ->
            IconButton(onClick = ::downloadCurrentVideo) {
                Icon(Icons.Default.Download, contentDescription = "Download")
            }
        },
    ),
)
```

整体替换控制层时传入 `controlsContent`，通过 `PlayerUiScope.actions` 操作，并读取 `controlsVisible`、`gestureFeedback` 等只读 UI 状态。点击显隐、5 秒隐藏和亮度/音量手势仍由 `VideoPlayer` 统一处理；全屏与锁定由 `ExpandableVideoPlayer` 管理，因此完整自定义控制层必须提供自己的锁定/解锁视觉。

需要从空白视觉开始时，直接使用原始宿主与通用组装层：

```kotlin
VideoPlayer(controller = controller, url = videoUrl) { player ->
    PlayerScaffold(
        scope = player,
        overlayContent = { DrawBusinessOverlay(player.state) },
        bottomControlsContent = { CustomControls(player) },
    )
}
```

`VideoPlayer` 只负责 Surface、生命周期、状态采集、显隐计时和通用手势；`PlayerScaffold` 只负责层级与位置；默认图标、状态视图及全屏策略只存在于两个成品播放器中。只有需要连 Surface 生命周期也完全接管时，才直接组合 `PlayerSurface`。

## 覆盖层

弹幕、水印和业务标记使用 `overlayContent`；字幕使用 `subtitleContent`。时间型覆盖层以 `scope.state.timeline.positionMs` 为唯一时钟，并在 `isPlaying=false` 或 `BUFFERING` 时暂停自身动画。

横竖屏必须复用同一个覆盖层状态持有者和同一个 Controller，否则 Surface 迁移时业务状态会重置。

## 新内核 Adapter

实现 `PlaybackEngine` 和 `PlaybackEngineProvider`：

```kotlin
class CustomEngine : PlaybackEngine {
    override val state: StateFlow<EnginePlaybackState> = TODO()

    override fun setSurface(surface: Surface?) = TODO()
    override fun prepare(source: VideoSource, startPositionMs: Long) = TODO()
    override fun play() = TODO()
    override fun pause() = TODO()
    override fun seekTo(positionMs: Long) = TODO()
    override fun setPlayerVolume(volume: Float) = TODO()
    override fun setPlaybackSpeed(speed: Float) = TODO()
    override fun release() = TODO()
}

class CustomEngineProvider : PlaybackEngineProvider {
    override val info = PlayerEngineInfo(
        id = PlayerEngineId("custom"),
        displayName = "Custom",
    )

    override fun supports(source: VideoSource): Boolean = true
    override fun create(): PlaybackEngine = CustomEngine()
}
```

Adapter 不接收 `autoPlay`。`prepare()` 只准备媒体，Controller 随后调用同一个 `play()`；因此 Adapter 必须支持准备完成前收到 `play()` 或 `seekTo()` 并保存待执行意图。

Adapter 还必须满足：

- 所有时间单位为毫秒，未知 duration 使用 `null`。
- 字幕轨道元数据写入 `tracks.subtitles`，当前字幕文本写入 `subtitleCues`；关闭字幕或换源时必须立即清空 Cue。
- Surface 解绑不能重置媒体与进度。
- `release()` 幂等，释放后停止发布有效状态。
- SDK 错误转换为结构化 `PlaybackError`；不支持格式必须是 `RetryPolicy.NEVER`。
- Activity、方向、窗口、系统音量和亮度不进入 Adapter。
- 不暴露 SDK 的 Player、Format、Track 或异常类型。

Compose 默认装配可通过 `additionalEngineProviders` 注册第三方内核；纯 core 使用 `createPlayerController()` 传入完整 Provider 列表。

## 状态扩展

新增统一状态前先确认多个 Adapter 能给出一致语义。扩展顺序：

1. 在 `EnginePlaybackState` 增加内核输出。
2. 为不支持的 Adapter 使用明确的 nullable/能力状态。
3. 在 `PlayerState` 对应分组中公开。
4. 在 `DefaultPlayerController` 归一化。
5. 通过 `PlayerController` Interface 编写换源、切核、错误与关闭测试。

## 平台能力

系统媒体音量和窗口亮度实现 `PlayerEnvironmentAdapter`，不要加入 `PlaybackEngine`。PiP、系统悬浮窗、MediaSession、音频焦点和前台 Service 属于宿主集成；它们持有同一个 Controller，并把系统命令映射为 `play()`、`pause()`、`seekTo()` 等能力。

## 缓存

`VideoContentCache` 只负责媒体字节。自定义 Adapter 必须把 `mediaId` 当作不透明键，并保证换核前后解析到同一内容。内置缓存适用于支持 HTTP Range 的渐进式文件；HLS/DASH 离线、DRM 下载或加密缓存应由专门 Adapter 实现。

## 验证

提交扩展前至少验证：

- Controller Interface 的换源、播放意图、Seek、切核、重试、关闭与宿主生命周期。
- Adapter 的准备前命令、Surface 解绑、状态映射、错误分类和幂等释放。
- UI 的控制项重排、自定义 slot、横竖屏迁移、手势与小尺寸布局。
- 真机或模拟器上的实际视频播放。

```shell
./gradlew build
```
