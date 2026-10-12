# MobilePlayer 扩展指南

本文说明新增能力时应该使用哪个扩展点，以及哪些职责应继续留在宿主 App。基础接入方式见 [README.md](README.md)，依赖结构见 [ARCHITECTURE.md](ARCHITECTURE.md)。

## 设计原则

MobilePlayer 将播放、展示和 UI 分为三个独立模块：

```text
业务/宿主窗口
      |
      +---- PlayerPresentationController  展示模式请求
      |
      +---- VideoPlayerController          播放命令与统一状态
                    |
                    +---- PlaybackEngine   播放内核 seam
```

核心约束如下：

- 播放状态只由 `VideoPlayerController` 持有。
- 横竖屏、全屏和悬浮只迁移 UI 与 Surface，不创建新的播放会话。
- 具体播放器 SDK 只存在于对应 Adapter 内。
- `PortraitVideoPlayer` 固定竖版播放；`LandscapeVideoPlayer` 由宿主持有 `isFullscreen`，组件负责横屏 Activity、全屏窗口和系统栏；PiP、权限、Service 和导航继续由宿主负责。
- 新功能优先消费 `PlaybackState`，避免直接读取 Media3/IjkPlayer 对象。
- 播放进度始终通过 `VideoPlayerCache` 保存；网络内容缓存通过 `VideoCacheConfig` 和 `VideoSource.ContentCachePolicy` 控制，不在具体内核中各存一份。

这种划分让播放控制保持为深模块：调用方只学习一套 Interface，切核、Surface 迁移和状态连续性都隐藏在 Implementation 内。

## 扩展点总览

| 需求 | 推荐扩展位置 | 是否需要修改核心 |
| --- | --- | --- |
| 弹幕、水印、字幕覆盖层 | `overlayContent` slot | 否 |
| 自定义加载/网速展示 | 读取 `PlaybackState` 的 Compose UI | 否 |
| 默认横屏全屏 | `VideoPlayerHost` | 否 |
| 自定义全屏策略 | 宿主处理 `PlayerPresentationRequest` | 否 |
| Android PiP | 宿主 Activity Adapter | 否 |
| 系统悬浮窗 | 前台 Service + WindowManager Adapter | 否 |
| 通知栏/锁屏控制 | MediaSession + 前台 Service | 否 |
| 新播放器内核 | `PlaybackEngine` Adapter | 需要注册类型与工厂 |
| 自定义进度/内容缓存 | `VideoPlayerCache` Adapter | 否 |
| 新的统一播放状态 | `EnginePlaybackState`/`PlaybackState` | 是 |
| 完全替换内置控制 UI | 新增公开 Compose UI slot/Surface 接口 | 当前需要修改 `player-compose` |

## 弹幕扩展

通过 `overlayContent` 把弹幕绘制在播放器 Surface 上方。`VideoPlayerHost` 会把同一个 slot 同时用于页面内和全屏容器：

```kotlin
@Composable
fun DanmakuVideoPlayer(
    controller: VideoPlayerController,
    source: VideoSource,
    danmaku: List<DanmakuItem>,
) {
    VideoPlayerHost(
        controller = controller,
        source = source,
        modifier = Modifier.fillMaxSize(),
        overlayContent = { playback ->
            DanmakuLayer(
                items = danmaku,
                positionMs = playback.positionMs,
                isPlaying = playback.isPlaying,
                modifier = Modifier.fillMaxSize(),
            )
        },
    )
}
```

弹幕模块建议遵守以下规则：

- 使用 `positionMs` 作为唯一时间基准，不单独维护一直运行的计时器。
- `isPlaying == false` 或 `BUFFERING` 时暂停弹幕运动。
- Seek 后根据新的 `positionMs` 重建当前可见弹幕窗口。
- `source` 改变时清空旧视频的弹幕索引。
- 弹幕层不要持有具体播放器内核。
- 默认不消费触摸事件，避免挡住播放器控制手势。

页面内、全屏和悬浮窗需要复用同一个弹幕状态持有者，方式与共享 `VideoPlayerController` 相同。这样切换展示模式时弹幕时间轴也不会重置。

弹幕等业务扩展应继续使用稳定的 `overlayContent` slot，不要依赖私有的 `VideoSurface`。完全替换内置控制层属于后续独立的 `controlsContent` 扩展，不应把业务页面放进播放器模块。

## 自定义加载与网速 UI

原始吞吐量位于：

```kotlin
playbackState.networkSpeedBytesPerSecond
```

业务可以关闭默认网速文字，并自行渲染：

```kotlin
VideoPlayerHost(
    controller = controller,
    source = source,
    uiConfig = VideoPlayerUiConfig(
        showNetworkSpeedWhileLoading = false,
    ),
    overlayContent = { state ->
        if (
            source.type == VideoSource.Type.NETWORK &&
            state.status in setOf(PlaybackStatus.PREPARING, PlaybackStatus.BUFFERING)
        ) {
            NetworkLoadingIndicator(
                bytesPerSecond = state.networkSpeedBytesPerSecond,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    },
)
```

不要使用系统全局流量差值代替播放器吞吐量，否则 App 中的图片、接口请求也会被计入。

## 自定义全屏策略

普通嵌入场景直接使用 `VideoPlayerHost`，它已经实现区域内播放、横屏沉浸式全屏和返回原位置。只有 App 需要不同策略时，才使用 `PlayerPresentationController` 的请求/确认 Interface 自行选择：

- 同一个 Activity 内切换 Compose 布局。
- 导航到独立播放 Activity。
- 使用 Dialog/Popup 容器。
- 横屏、传感器横屏或保持用户方向。
- 是否隐藏状态栏和导航栏。

无论选择哪种实现，都应遵循：

1. 收到 `ChangeMode`。
2. 完成窗口、方向或导航操作。
3. 将同一个 `VideoPlayerController` 绑定到新 UI。
4. 调用 `confirmMode(target)`。
5. 失败则调用 `rejectPendingRequest(message)`。

不要在 `PlaybackEngine` 中调用 `requestedOrientation` 或 WindowInsets API。这些行为与解码无关，也会阻止内核在 Service 或测试环境中复用。

## 自定义缓存 Adapter

`VideoPlayerCache` 是播放进度和内容缓存的统一 seam，但两类数据在 Implementation 内应保持独立：进度是按 `mediaId` 保存的小记录，媒体内容是可淘汰的大文件。Controller 只负责恢复/保存时机和解析播放源，具体内核不应直接访问缓存。

`mediaId` 由 `VideoSource` 根据来源类型与完整 URI 自动生成，宿主不负责构造，也不能通过公开 factory 覆盖。自定义缓存 Adapter 应把它当作不透明键；标题和请求头不参与身份计算，URI 变化会得到新键。

自定义 Adapter 必须保持以下语义：

- `getProgress()`/`saveProgress()` 必须快速且同步，不能在主线程执行网络请求。
- `getCachedSource()` 可以返回本地文件或读穿代理 URL；返回 `null` 表示原源直连。
- 同一个 `mediaId` 在 Media3/Ijk 切换前后必须解析到同一份缓存内容。
- 本地 URI 不应被复制；HLS/DASH 默认不应进入渐进式文件缓存。
- `remove(mediaId)` 同时删除该资源的进度和内容，`clear()` 删除全部。
- 鉴权信息只能发给可信上游，不能写入文件名、日志或持久化元数据。
- 磁盘写入应先落临时文件并校验，再原子替换可用文件。

内置 Adapter 使用 512 KiB Range 分段与 loopback 代理，适合支持 `206 Partial Content` 的 MP4 等单文件。需要 HLS 离线、DRM 下载、跨账号隔离或自定义加密时，应替换整个 `VideoPlayerCache` Adapter，而不是在两个 `PlaybackEngine` Adapter 中分别实现。

## PiP 与系统悬浮窗 Adapter

### Android PiP

PiP 通常继续使用原 Activity 和同一个 Controller。宿主负责：

- Activity manifest 配置。
- `PictureInPictureParams` 和宽高比。
- 进入/退出 PiP 回调。
- PiP 中隐藏不适合小窗口的控制 UI。
- 点击展开后确认 `floatingReturnMode`。

### WindowManager 系统悬浮窗

系统悬浮窗必须使用前台 Service 持有 Controller。推荐所有权关系：

```text
ForegroundPlayerService
      |
      +---- VideoPlayerController
      +---- MediaSession（后续）
      +---- OverlayWindowAdapter
                 |
                 +---- ComposeView -> VideoPlayer
```

Activity 只绑定 Service 并读取同一个状态，不创建第二个播放器。悬浮窗关闭时要明确区分：

- 只关闭悬浮 UI 并返回 App：保留 Controller。
- 用户结束播放：暂停、移除通知、关闭 Controller、停止 Service。

## 新增播放器内核

`PlaybackEngine` 是播放器 SDK 的 seam。新增内核需要实现一个 Adapter：

```kotlin
class CustomPlaybackEngine(
    context: Context,
) : PlaybackEngine {
    override val state: StateFlow<EnginePlaybackState> = TODO()

    override fun setSurface(surface: Surface?) = TODO()

    override fun prepare(
        source: VideoSource,
        playWhenReady: Boolean,
        startPositionMs: Long,
    ) = TODO()

    override fun play() = TODO()
    override fun pause() = TODO()
    override fun seekTo(positionMs: Long) = TODO()
    override fun setVolume(volume: Float) = TODO()
    override fun setPlaybackSpeed(speed: Float) = TODO()
    override fun release() = TODO()
}
```

Adapter 必须满足这些不变量：

- `state` 始终可以读取，回调统一转换为 `EnginePlaybackState`。
- 所有时间单位均为毫秒。
- `networkSpeedBytesPerSecond` 使用字节每秒，不是 bit/s。
- `prepare()` 必须支持 Controller 指定的起始位置和播放意图；该参数是内部内核 seam，不是宿主传入的起播参数。
- `setSurface(null)` 只解绑画面，不能重置媒体或进度。
- `release()` 必须幂等，释放后不再发布有效状态。
- 错误通过 `PlaybackStatus.ERROR` 和 `PlaybackError` 上报。
- Adapter 不处理 Activity、方向、导航或悬浮窗权限。

当前 `PlayerEngineType` 是包含 `MEDIA3` 与 `IJK` 的 enum，因此加入第三种内核还需要：

1. 在 `PlayerEngineType` 中增加类型。
2. 新建独立 engine 模块和 `PlaybackEngineFactory`。
3. 在 Controller 的 factory map 中注册。
4. 决定默认 Compose 控制栏是否显示该类型。
5. 增加切核状态恢复测试。

如果未来需要让外部 App 在不修改 `player-core` 的情况下注册任意内核，应把 `PlayerEngineType` 演进为稳定的值对象或 key；在完成这个兼容性设计前，不建议公开动态插件承诺。

## 扩展统一状态

只有两个及以上内核都能提供、且业务确实需要消费的数据，才建议加入统一状态。例如字幕轨、音轨、清晰度和直播信息。

新增状态的推荐顺序：

1. 在 `EnginePlaybackState` 定义内核输出。
2. 在每个 Adapter 中提供一致语义；不支持时使用明确默认值。
3. 在 `PlaybackState` 定义宿主可见状态。
4. 在 `VideoPlayerController` 中映射。
5. 增加切核、换源和错误状态下的回归测试。

不要把 Media3 的 `Format`、Ijk 的 metadata 或 SDK 异常类型直接暴露给业务，否则业务层会重新依赖具体内核。

## 后台播放与 MediaSession

建议新增独立的宿主集成模块，而不是把后台能力放进 `player-core`：

- 前台 Service 持有 `VideoPlayerController`。
- MediaSession 将系统命令映射到 `play()`、`pause()`、`seekTo()`。
- 通知栏和锁屏 UI 订阅 `PlaybackState`。
- 音频焦点 Adapter 决定暂停、duck 或恢复策略。
- 耳机拔出、蓝牙断开由宿主策略处理。

这样普通页面播放不需要承担 Service 与 MediaSession 依赖。

## 测试要求

新增能力至少覆盖对应 Interface：

- Controller：换源、Seek、切核、关闭和宿主生命周期。
- 展示协议：请求、确认、拒绝、悬浮返回和关闭。
- 新内核 Adapter：准备、状态映射、Surface 解绑、错误和释放。
- 弹幕：暂停、缓冲、Seek、换源和横竖屏迁移。
- 真机：Media3/Ijk 网络播放、方向切换、PiP、系统悬浮窗和不同 ABI。

提交前执行：

```shell
./gradlew build
```

## 兼容性建议

- 优先给配置数据类增加有默认值的字段。
- 避免修改已有方法语义；新增行为使用新方法或明确配置。
- 状态字段应有稳定单位和默认值。
- 平台能力通过 Adapter 接入，不向核心模块传 Activity。
- 对外文档只承诺已实现并有测试覆盖的行为。
